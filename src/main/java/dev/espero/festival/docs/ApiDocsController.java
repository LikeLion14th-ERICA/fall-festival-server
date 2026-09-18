package dev.espero.festival.docs;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Opt-in Swagger UI for local and development servers ({@code API_DOCS_ENABLED=true}).
 *
 * <p>It shows the repository's hand-authored API v2 contract and sends "Try it
 * out" requests to this server. Contract operations that this server does not
 * route are labelled so a reader can tell real endpoints from mock-only
 * ones.</p>
 */
@RestController
@ConditionalOnProperty(prefix = "festival.api-docs", name = "enabled", havingValue = "true")
class ApiDocsController {

    static final String NOT_IMPLEMENTED = "[서버 미구현] ";
    private static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "patch");
    private static final Map<String, MediaType> ASSETS = Map.of(
        "swagger-ui.css", MediaType.valueOf("text/css"),
        "swagger-ui-bundle.js", MediaType.valueOf("text/javascript")
    );

    private final Supplier<Set<String>> implementedRoutes;
    private final JsonMapper json = JsonMapper.builder().build();
    private volatile byte[] spec;

    @Autowired
    ApiDocsController(ApplicationContext context) {
        this(() -> routes(context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class)));
    }

    ApiDocsController(Supplier<Set<String>> implementedRoutes) {
        this.implementedRoutes = implementedRoutes;
    }

    @GetMapping({"/docs", "/docs/"})
    ResponseEntity<byte[]> page() {
        return ok(classpath("api-docs/index.html"), MediaType.TEXT_HTML);
    }

    @GetMapping("/docs/init.js")
    ResponseEntity<byte[]> init() {
        return ok(classpath("api-docs/init.js"), MediaType.valueOf("text/javascript"));
    }

    @GetMapping("/docs/openapi.json")
    ResponseEntity<byte[]> openApi() {
        byte[] current = spec;
        if (current == null) {
            current = annotatedSpec();
            spec = current;
        }
        return ok(current, MediaType.APPLICATION_JSON);
    }

    @GetMapping("/docs/assets/{name}")
    ResponseEntity<byte[]> asset(@PathVariable String name) {
        MediaType type = ASSETS.get(name);
        if (type == null) {
            return ResponseEntity.notFound().build();
        }
        return ok(classpath("META-INF/resources/webjars/swagger-ui/" + swaggerUiVersion() + "/" + name), type);
    }

    /** The contract with this server as its target and unrouted operations labelled. */
    private byte[] annotatedSpec() {
        ObjectNode document = (ObjectNode) json.readTree(classpath("api-docs/openapi.json"));
        document.putArray("servers").addObject().put("url", "/").put("description", "이 서버");
        Set<String> routes = implementedRoutes.get();
        for (Map.Entry<String, JsonNode> path : document.path("paths").properties()) {
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                if (!HTTP_METHODS.contains(operation.getKey())
                    || routes.contains(route(operation.getKey(), path.getKey()))) {
                    continue;
                }
                ObjectNode node = (ObjectNode) operation.getValue();
                node.put("summary", NOT_IMPLEMENTED + node.path("summary").asString(""));
                node.put("description", "이 서버에는 아직 구현되지 않았습니다. 목 서버(api-v2)에서만 동작합니다.\n\n"
                    + node.path("description").asString(""));
            }
        }
        return json.writeValueAsBytes(document);
    }

    static Set<String> routes(RequestMappingHandlerMapping mapping) {
        Set<String> routes = new HashSet<>();
        for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
            Set<String> methods = new HashSet<>();
            info.getMethodsCondition().getMethods().forEach(method -> methods.add(method.name()));
            if (methods.isEmpty()) {
                methods.addAll(Set.of("GET", "PUT", "POST", "DELETE", "PATCH"));
            }
            for (String pattern : info.getPatternValues()) {
                methods.forEach(method -> routes.add(route(method, pattern)));
            }
        }
        return routes;
    }

    /** Normalizes path variable names so {@code /spaces/{spaceId}} matches {@code /spaces/{id}}. */
    static String route(String method, String path) {
        return method.toUpperCase(Locale.ROOT) + " " + path.replaceAll("\\{[^}]+}", "{}");
    }

    private static String swaggerUiVersion() {
        Properties properties = new Properties();
        try (InputStream input = new ClassPathResource(
            "META-INF/maven/org.webjars/swagger-ui/pom.properties"
        ).getInputStream()) {
            properties.load(input);
        } catch (IOException exception) {
            throw new UncheckedIOException("Swagger UI webjar is missing", exception);
        }
        return properties.getProperty("version");
    }

    private static byte[] classpath(String path) {
        try (InputStream input = new ClassPathResource(path).getInputStream()) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException("API docs resource is missing: " + path, exception);
        }
    }

    private static ResponseEntity<byte[]> ok(byte[] body, MediaType type) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .contentType(type.getCharset() == null && type.getType().equals("text")
                ? new MediaType(type, StandardCharsets.UTF_8)
                : type)
            .body(body);
    }
}
