package dev.espero.festival.workbench;

import dev.espero.festival.CatalogCliException;
import dev.espero.festival.CatalogExportService;
import dev.espero.festival.CatalogManifestReader;
import dev.espero.festival.CatalogRevisionService;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The local workbench API. Export, validation and diff use the read-only
 * export role; import and publish use the separate publish role. Responses
 * never include database connection details.
 */
@RestController
@Profile(CatalogWorkbenchApplication.PROFILE)
class WorkbenchController {

    private static final Logger log = LoggerFactory.getLogger(WorkbenchController.class);
    private static final Map<String, MediaType> ASSETS = Map.of(
        "index.html", MediaType.TEXT_HTML,
        "workbench.js", MediaType.valueOf("text/javascript"),
        "workbench.css", MediaType.valueOf("text/css")
    );

    private final WorkbenchRoleContexts roles;
    private final CatalogManifestReader reader;
    private final WorkbenchBackendCheck backend;
    private final UUID festivalId;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    WorkbenchController(
        WorkbenchRoleContexts roles,
        CatalogManifestReader reader,
        WorkbenchBackendCheck backend,
        UUID festivalId
    ) {
        this.roles = roles;
        this.reader = reader;
        this.backend = backend;
        this.festivalId = festivalId;
    }

    @GetMapping({"/", "/index.html"})
    ResponseEntity<byte[]> index() {
        return asset("index.html");
    }

    @GetMapping("/workbench.js")
    ResponseEntity<byte[]> script() {
        return asset("workbench.js");
    }

    @GetMapping("/workbench.css")
    ResponseEntity<byte[]> style() {
        return asset("workbench.css");
    }

    @GetMapping("/api/status")
    Map<String, Object> status() {
        WorkbenchRevisionQueries queries = roles.revisions();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("festivalId", festivalId);
        status.put("publishEnabled", roles.publisher().isPresent());
        status.put("backendCheckConfigured", backend.configured());
        status.put("published", queries.published(festivalId).orElse(null));
        status.put("revisions", queries.revisions(festivalId));
        return status;
    }

    @PostMapping(path = "/api/export", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> export(@RequestBody JsonNode request) {
        UUID revisionId = uuid(request, "revisionId");
        requireRevisionBelongsToFestival(revisionId);
        CatalogExportService.ExportResult result = roles.exports().export(revisionId);
        return Map.of("manifest", result.manifest(), "findings", result.findings());
    }

    @PostMapping(path = "/api/validate", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> validate(@RequestBody JsonNode request) {
        JsonNode manifest = manifest(request);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("baseline", baseline(manifest, baselineOverride(request)));
        try {
            withManifestFile(manifest, path -> reader.read(path, festivalId));
            report.put("valid", true);
        } catch (CatalogCliException exception) {
            report.put("valid", false);
            report.put("error", exception.getMessage());
        }
        return report;
    }

    @PostMapping(path = "/api/diff", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> diff(@RequestBody JsonNode request) {
        JsonNode manifest = manifest(request);
        UUID against = request.hasNonNull("againstRevisionId")
            ? uuid(request, "againstRevisionId")
            : roles.revisions().published(festivalId).map(WorkbenchRevisionQueries.RevisionSummary::id).orElse(null);
        if (against != null) {
            requireRevisionBelongsToFestival(against);
        }
        JsonNode before = against == null
            ? json.createObjectNode()
            : json.valueToTree(roles.exports().export(against).manifest());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("againstRevisionId", against);
        result.put("sections", ManifestDiff.diff(before, manifest));
        return result;
    }

    @PostMapping(path = "/api/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> importManifest(@RequestBody JsonNode request) {
        CatalogRevisionService publisher = publisher();
        String actor = actor(request);
        JsonNode manifest = manifest(request);
        Optional<UUID> override = baselineOverride(request);
        UUID revisionId = withManifestFile(manifest, path -> publisher.importManifest(
            path,
            actor,
            festivalId,
            override == null ? null : new CatalogRevisionService.BaselineOverride(override.orElse(null))
        ));
        log.info("catalog_workbench action=IMPORT revision={}", revisionId);
        return Map.of("revisionId", revisionId);
    }

    @PostMapping(path = "/api/publish", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> publish(@RequestBody JsonNode request) {
        String actor = actor(request);
        UUID revisionId = uuid(request, "revisionId");
        requireRevisionBelongsToFestival(revisionId);
        CatalogRevisionService publisher = publisher();
        publisher.publish(revisionId, actor);
        log.info("catalog_workbench action=PUBLISH revision={}", revisionId);
        return Map.of(
            "revisionId", revisionId,
            "published", roles.revisions().published(festivalId).orElse(null),
            "nextSteps", List.of(
                "정해진 시간에 백엔드를 재시작합니다.",
                "/readyz가 200인지 확인합니다.",
                "공개 API의 meta.revision이 게시한 revision 번호와 같은지 확인합니다."
            )
        );
    }

    @PostMapping(path = "/api/post-publish-check", consumes = MediaType.APPLICATION_JSON_VALUE)
    WorkbenchBackendCheck.Result postPublishCheck(@RequestBody(required = false) JsonNode ignored) {
        Long expected = roles.revisions().published(festivalId)
            .map(WorkbenchRevisionQueries.RevisionSummary::revisionNumber)
            .orElse(null);
        return backend.check(expected);
    }

    @ExceptionHandler(CatalogCliException.class)
    ResponseEntity<Map<String, String>> rejected(CatalogCliException exception) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "CATALOG_REJECTED", exception.getMessage());
    }

    @ExceptionHandler(WorkbenchRequestException.class)
    ResponseEntity<Map<String, String>> badRequest(WorkbenchRequestException exception) {
        return error(exception.status(), exception.code(), exception.getMessage());
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, String>> database(DataAccessException exception) {
        log.warn("catalog_workbench database_error type={}", exception.getClass().getSimpleName());
        return error(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE", "The database request failed.");
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> unavailable(IllegalStateException exception) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "UNAVAILABLE", exception.getMessage());
    }

    private Map<String, Object> baseline(JsonNode manifest, Optional<UUID> override) {
        UUID stated = override != null
            ? override.orElse(null)
            : (manifest.hasNonNull("baselineRevisionId")
                ? parseUuid(manifest.get("baselineRevisionId").asString(), "manifest.baselineRevisionId")
                : null);
        UUID current = roles.revisions().published(festivalId)
            .map(WorkbenchRevisionQueries.RevisionSummary::id)
            .orElse(null);
        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("statedBaselineRevisionId", stated);
        baseline.put("currentPublishedRevisionId", current);
        baseline.put("matches", Objects.equals(stated, current));
        return baseline;
    }

    /**
     * {@code null} when the request does not state a baseline, an empty value
     * for "none" (no published revision expected), otherwise the revision.
     */
    private Optional<UUID> baselineOverride(JsonNode request) {
        if (!request.hasNonNull("baselineRevisionId")) {
            return null;
        }
        String value = request.get("baselineRevisionId").asString();
        return value.equals("none") ? Optional.empty() : Optional.of(parseUuid(value, "baselineRevisionId"));
    }

    private CatalogRevisionService publisher() {
        return roles.publisher().orElseThrow(() -> new WorkbenchRequestException(
            HttpStatus.FORBIDDEN, "PUBLISH_ROLE_NOT_CONFIGURED",
            "This workbench runs export-only. Configure the publish role to import or publish."
        ));
    }

    private void requireRevisionBelongsToFestival(UUID revisionId) {
        roles.revisions().festivalForRevision(revisionId).ifPresent(owner -> {
            if (!owner.equals(festivalId)) {
                throw new WorkbenchRequestException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "REVISION_FESTIVAL_MISMATCH",
                    "The revision does not belong to the configured festival."
                );
            }
        });
    }

    private <T> T withManifestFile(JsonNode manifest, Function<Path, T> action) {
        Path directory = null;
        Path file = null;
        try {
            directory = Files.createTempDirectory("catalog-workbench-");
            file = directory.resolve("manifest.json");
            Files.write(file, json.writeValueAsBytes(manifest));
            return action.apply(file);
        } catch (IOException exception) {
            throw new UncheckedIOException("Manifest could not be staged.", exception);
        } finally {
            deleteQuietly(file);
            deleteQuietly(directory);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("catalog_workbench staged_file_not_deleted type={}", exception.getClass().getSimpleName());
        }
    }

    private static JsonNode manifest(JsonNode request) {
        JsonNode manifest = request.path("manifest");
        if (!manifest.isObject()) {
            throw new WorkbenchRequestException(HttpStatus.BAD_REQUEST, "MANIFEST_REQUIRED", "A manifest object is required.");
        }
        return manifest;
    }

    private static String actor(JsonNode request) {
        String actor = request.path("actor").asString("").strip();
        if (actor.isEmpty() || actor.length() > 100) {
            throw new WorkbenchRequestException(
                HttpStatus.BAD_REQUEST, "ACTOR_REQUIRED", "An operator name of at most 100 characters is required."
            );
        }
        return actor;
    }

    private static UUID uuid(JsonNode request, String field) {
        if (!request.hasNonNull(field)) {
            throw new WorkbenchRequestException(HttpStatus.BAD_REQUEST, "FIELD_REQUIRED", field + " is required.");
        }
        return parseUuid(request.get(field).asString(), field);
    }

    private static UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new WorkbenchRequestException(HttpStatus.BAD_REQUEST, "INVALID_UUID", field + " must be a UUID.");
        }
    }

    private static ResponseEntity<byte[]> asset(String name) {
        try (InputStream input = new ClassPathResource("workbench/" + name).getInputStream()) {
            return ResponseEntity.ok().contentType(ASSETS.get(name)).body(input.readAllBytes());
        } catch (IOException exception) {
            throw new UncheckedIOException("Workbench asset is missing: " + name, exception);
        }
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("error", code, "message", message == null ? "" : message));
    }
}
