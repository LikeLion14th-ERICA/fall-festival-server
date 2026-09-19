package dev.espero.festival.persistence;

import dev.espero.festival.domain.Goods;
import dev.espero.festival.domain.GoodsAvailability;
import dev.espero.festival.domain.GoodsColor;
import dev.espero.festival.domain.GoodsColorTranslation;
import dev.espero.festival.domain.GoodsCombination;
import dev.espero.festival.domain.GoodsImage;
import dev.espero.festival.domain.GoodsImageTranslation;
import dev.espero.festival.domain.GoodsOptionMode;
import dev.espero.festival.domain.GoodsSize;
import dev.espero.festival.domain.GoodsSizeTranslation;
import dev.espero.festival.domain.GoodsTranslation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL access for festival_id-scoped goods content. */
@Repository
@Profile("db")
public class GoodsStore {

    private final NamedParameterJdbcTemplate jdbc;

    public GoodsStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a validated new goods aggregate except for media associations. */
    public void insertForCreation(Goods goods) {
        MapSqlParameterSource goodsParameters = new MapSqlParameterSource()
            .addValue("id", goods.id())
            .addValue("festivalId", goods.festivalId())
            .addValue("optionMode", goods.optionMode().name())
            .addValue("priceAmount", goods.priceAmount())
            .addValue("createdAt", atUtc(goods.createdAt()))
            .addValue("updatedAt", atUtc(goods.updatedAt()));
        jdbc.update("""
            INSERT INTO goods (
                id, festival_id, option_mode, price_amount, price_currency, created_at, updated_at
            ) VALUES (
                :id, :festivalId, :optionMode, :priceAmount, 'KRW', :createdAt, :updatedAt
            )
            """, goodsParameters);

        batch("""
            INSERT INTO goods_translations (goods_id, locale, name, description)
            VALUES (:goodsId, :locale, :name, :description)
            """, goods.translations().entrySet().stream()
            .map(entry -> new MapSqlParameterSource()
                .addValue("goodsId", goods.id())
                .addValue("locale", entry.getKey())
                .addValue("name", entry.getValue().name())
                .addValue("description", entry.getValue().description()))
            .toList());

        List<MapSqlParameterSource> colors = new ArrayList<>();
        List<MapSqlParameterSource> colorTranslations = new ArrayList<>();
        for (int index = 0; index < goods.colors().size(); index++) {
            GoodsColor color = goods.colors().get(index);
            colors.add(new MapSqlParameterSource()
                .addValue("id", color.id())
                .addValue("goodsId", goods.id())
                .addValue("sortOrder", index));
            color.translations().forEach((locale, translation) ->
                colorTranslations.add(new MapSqlParameterSource()
                    .addValue("colorId", color.id())
                    .addValue("locale", locale)
                    .addValue("name", translation.name())));
        }
        batch("""
            INSERT INTO goods_colors (id, goods_id, sort_order)
            VALUES (:id, :goodsId, :sortOrder)
            """, colors);
        batch("""
            INSERT INTO goods_color_translations (color_id, locale, name)
            VALUES (:colorId, :locale, :name)
            """, colorTranslations);

        List<MapSqlParameterSource> sizes = new ArrayList<>();
        List<MapSqlParameterSource> sizeTranslations = new ArrayList<>();
        for (int index = 0; index < goods.sizes().size(); index++) {
            GoodsSize size = goods.sizes().get(index);
            sizes.add(new MapSqlParameterSource()
                .addValue("id", size.id())
                .addValue("goodsId", goods.id())
                .addValue("sortOrder", index));
            size.translations().forEach((locale, translation) ->
                sizeTranslations.add(new MapSqlParameterSource()
                    .addValue("sizeId", size.id())
                    .addValue("locale", locale)
                    .addValue("label", translation.label())));
        }
        batch("""
            INSERT INTO goods_sizes (id, goods_id, sort_order)
            VALUES (:id, :goodsId, :sortOrder)
            """, sizes);
        batch("""
            INSERT INTO goods_size_translations (size_id, locale, label)
            VALUES (:sizeId, :locale, :label)
            """, sizeTranslations);

        batch("""
            INSERT INTO goods_combinations (
                id, goods_id, color_id, size_id, availability, updated_at
            ) VALUES (
                :id, :goodsId, :colorId, :sizeId, :availability, :updatedAt
            )
            """, goods.combinations().stream()
            .map(combination -> new MapSqlParameterSource()
                .addValue("id", combination.id())
                .addValue("goodsId", goods.id())
                .addValue("colorId", combination.colorId())
                .addValue("sizeId", combination.sizeId())
                .addValue("availability", combination.availability().name())
                .addValue("updatedAt", atUtc(combination.updatedAt())))
            .toList());
    }

    public List<Goods> findAll(UUID festivalId) {
        List<GoodsHeader> headers = jdbc.query("""
            SELECT id, festival_id, option_mode, price_amount, created_at, updated_at
            FROM goods
            WHERE festival_id = :festivalId
            ORDER BY created_at DESC, id
            """,
            new MapSqlParameterSource().addValue("festivalId", festivalId),
            (resultSet, rowNumber) -> mapHeader(resultSet)
        );
        return hydrate(headers);
    }

    /** All goods for administrator editing, most recently updated first. */
    public List<Goods> findAllForAdmin(UUID festivalId) {
        List<GoodsHeader> headers = jdbc.query("""
            SELECT id, festival_id, option_mode, price_amount, created_at, updated_at
            FROM goods
            WHERE festival_id = :festivalId
            ORDER BY updated_at DESC, id
            """,
            new MapSqlParameterSource().addValue("festivalId", festivalId),
            (resultSet, rowNumber) -> mapHeader(resultSet)
        );
        return hydrate(headers);
    }

    public Optional<Goods> findById(UUID festivalId, UUID goodsId) {
        List<GoodsHeader> headers = jdbc.query("""
            SELECT id, festival_id, option_mode, price_amount, created_at, updated_at
            FROM goods
            WHERE festival_id = :festivalId AND id = :id
            """,
            new MapSqlParameterSource().addValue("festivalId", festivalId).addValue("id", goodsId),
            (resultSet, rowNumber) -> mapHeader(resultSet)
        );
        return hydrate(headers).stream().findFirst();
    }

    /** Locks the goods row before an administrator checks its ETag and writes it. */
    public Optional<Goods> findForUpdate(UUID festivalId, UUID goodsId) {
        List<GoodsHeader> headers = jdbc.query("""
            SELECT id, festival_id, option_mode, price_amount, created_at, updated_at
            FROM goods
            WHERE festival_id = :festivalId AND id = :id
            FOR UPDATE
            """,
            new MapSqlParameterSource().addValue("festivalId", festivalId).addValue("id", goodsId),
            (resultSet, rowNumber) -> mapHeader(resultSet)
        );
        return hydrate(headers).stream().findFirst();
    }

    /** Rejects client-stable option identifiers already owned by a different goods row. */
    public boolean hasForeignOptionIds(UUID goodsId, List<UUID> colorIds, List<UUID> sizeIds) {
        return hasForeignIds("goods_colors", goodsId, colorIds)
            || hasForeignIds("goods_sizes", goodsId, sizeIds);
    }

    /** Replaces editable product content while retaining matching combination rows. */
    public void updateForEdit(Goods current, Goods updated) {
        int baseUpdated = jdbc.update("""
            UPDATE goods
            SET option_mode = :optionMode,
                price_amount = :priceAmount,
                updated_at = :updatedAt
            WHERE id = :id AND festival_id = :festivalId
            """, new MapSqlParameterSource()
            .addValue("id", current.id())
            .addValue("festivalId", current.festivalId())
            .addValue("optionMode", updated.optionMode().name())
            .addValue("priceAmount", updated.priceAmount())
            .addValue("updatedAt", atUtc(updated.updatedAt())));
        if (baseUpdated != 1) {
            throw new IllegalStateException("Locked goods row was not updated");
        }

        jdbc.update("DELETE FROM goods_translations WHERE goods_id = :goodsId", Map.of("goodsId", current.id()));
        insertProductTranslations(updated);

        if (current.optionMode() != updated.optionMode()) {
            replaceAllOptions(updated);
        } else if (updated.optionMode() == GoodsOptionMode.OPTIONS) {
            updateOptionsDifferentially(current, updated);
        }
    }

    /** Hard-deletes one locked festival-owned goods row after image associations are removed. */
    public void delete(UUID festivalId, UUID goodsId) {
        int deleted = jdbc.update(
            "DELETE FROM goods WHERE festival_id = :festivalId AND id = :goodsId",
            Map.of("festivalId", festivalId, "goodsId", goodsId)
        );
        if (deleted != 1) {
            throw new IllegalStateException("Locked goods row was not deleted");
        }
    }

    /** Updates one combination only when the complete festival/goods ownership chain matches. */
    public boolean updateAvailability(
        UUID festivalId,
        UUID goodsId,
        UUID combinationId,
        GoodsAvailability availability,
        Instant updatedAt
    ) {
        int updated = jdbc.update("""
            UPDATE goods_combinations AS combination
            SET availability = :availability, updated_at = :updatedAt
            FROM goods AS goods
            WHERE combination.id = :combinationId
              AND combination.goods_id = :goodsId
              AND goods.id = combination.goods_id
              AND goods.festival_id = :festivalId
            """, new MapSqlParameterSource()
            .addValue("festivalId", festivalId)
            .addValue("goodsId", goodsId)
            .addValue("combinationId", combinationId)
            .addValue("availability", availability.name())
            .addValue("updatedAt", OffsetDateTime.ofInstant(updatedAt, ZoneOffset.UTC)));
        return updated == 1;
    }

    private boolean hasForeignIds(String table, UUID goodsId, List<UUID> ids) {
        if (ids.isEmpty()) {
            return false;
        }
        Long count = jdbc.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE id IN (:ids) AND goods_id <> :goodsId",
            new MapSqlParameterSource().addValue("ids", ids).addValue("goodsId", goodsId),
            Long.class
        );
        return count != null && count > 0;
    }

    private void replaceAllOptions(Goods updated) {
        Map<String, Object> goodsId = Map.of("goodsId", updated.id());
        jdbc.update("DELETE FROM goods_combinations WHERE goods_id = :goodsId", goodsId);
        jdbc.update("DELETE FROM goods_colors WHERE goods_id = :goodsId", goodsId);
        jdbc.update("DELETE FROM goods_sizes WHERE goods_id = :goodsId", goodsId);
        insertColors(updated);
        insertSizes(updated);
        insertCombinations(updated.id(), updated.combinations());
    }

    private void updateOptionsDifferentially(Goods current, Goods updated) {
        Set<UUID> desiredCombinationIds = updated.combinations().stream()
            .map(GoodsCombination::id).collect(Collectors.toSet());
        List<UUID> removedCombinationIds = current.combinations().stream()
            .map(GoodsCombination::id).filter(id -> !desiredCombinationIds.contains(id)).toList();
        deleteByIds("goods_combinations", removedCombinationIds);

        updateColorsDifferentially(current, updated);
        updateSizesDifferentially(current, updated);

        Set<UUID> currentCombinationIds = current.combinations().stream()
            .map(GoodsCombination::id).collect(Collectors.toSet());
        insertCombinations(updated.id(), updated.combinations().stream()
            .filter(combination -> !currentCombinationIds.contains(combination.id())).toList());
    }

    private void updateColorsDifferentially(Goods current, Goods updated) {
        Set<UUID> currentIds = current.colors().stream().map(GoodsColor::id).collect(Collectors.toSet());
        Set<UUID> desiredIds = updated.colors().stream().map(GoodsColor::id).collect(Collectors.toSet());
        jdbc.update(
            "UPDATE goods_colors SET sort_order = sort_order + 1000000 WHERE goods_id = :goodsId",
            Map.of("goodsId", updated.id())
        );
        insertColors(updated.id(), updated.colors().stream().filter(color -> !currentIds.contains(color.id())).toList());
        batch("UPDATE goods_colors SET sort_order = :sortOrder WHERE id = :id AND goods_id = :goodsId",
            indexedColors(updated));
        deleteByIds("goods_colors", currentIds.stream().filter(id -> !desiredIds.contains(id)).toList());
        jdbc.update("""
            DELETE FROM goods_color_translations
            WHERE color_id IN (SELECT id FROM goods_colors WHERE goods_id = :goodsId)
            """, Map.of("goodsId", updated.id()));
        insertColorTranslations(updated.colors());
    }

    private void updateSizesDifferentially(Goods current, Goods updated) {
        Set<UUID> currentIds = current.sizes().stream().map(GoodsSize::id).collect(Collectors.toSet());
        Set<UUID> desiredIds = updated.sizes().stream().map(GoodsSize::id).collect(Collectors.toSet());
        jdbc.update(
            "UPDATE goods_sizes SET sort_order = sort_order + 1000000 WHERE goods_id = :goodsId",
            Map.of("goodsId", updated.id())
        );
        insertSizes(updated.id(), updated.sizes().stream().filter(size -> !currentIds.contains(size.id())).toList());
        batch("UPDATE goods_sizes SET sort_order = :sortOrder WHERE id = :id AND goods_id = :goodsId",
            indexedSizes(updated));
        deleteByIds("goods_sizes", currentIds.stream().filter(id -> !desiredIds.contains(id)).toList());
        jdbc.update("""
            DELETE FROM goods_size_translations
            WHERE size_id IN (SELECT id FROM goods_sizes WHERE goods_id = :goodsId)
            """, Map.of("goodsId", updated.id()));
        insertSizeTranslations(updated.sizes());
    }

    private void insertProductTranslations(Goods goods) {
        batch("""
            INSERT INTO goods_translations (goods_id, locale, name, description)
            VALUES (:goodsId, :locale, :name, :description)
            """, goods.translations().entrySet().stream()
            .map(entry -> new MapSqlParameterSource()
                .addValue("goodsId", goods.id())
                .addValue("locale", entry.getKey())
                .addValue("name", entry.getValue().name())
                .addValue("description", entry.getValue().description()))
            .toList());
    }

    private void insertColors(Goods goods) {
        batch("""
            INSERT INTO goods_colors (id, goods_id, sort_order)
            VALUES (:id, :goodsId, :sortOrder)
            """, indexedColors(goods));
        insertColorTranslations(goods.colors());
    }

    private void insertColors(UUID goodsId, List<GoodsColor> colors) {
        List<MapSqlParameterSource> parameters = new ArrayList<>();
        for (int index = 0; index < colors.size(); index++) {
            GoodsColor color = colors.get(index);
            parameters.add(new MapSqlParameterSource().addValue("id", color.id())
                .addValue("goodsId", goodsId).addValue("sortOrder", -1 - index));
        }
        batch("INSERT INTO goods_colors (id, goods_id, sort_order) VALUES (:id, :goodsId, :sortOrder)", parameters);
    }

    private List<MapSqlParameterSource> indexedColors(Goods goods) {
        List<MapSqlParameterSource> result = new ArrayList<>();
        for (int index = 0; index < goods.colors().size(); index++) {
            result.add(new MapSqlParameterSource().addValue("id", goods.colors().get(index).id())
                .addValue("goodsId", goods.id()).addValue("sortOrder", index));
        }
        return result;
    }

    private void insertColorTranslations(List<GoodsColor> colors) {
        List<MapSqlParameterSource> parameters = new ArrayList<>();
        colors.forEach(color -> color.translations().forEach((locale, translation) ->
            parameters.add(new MapSqlParameterSource().addValue("colorId", color.id())
                .addValue("locale", locale).addValue("name", translation.name()))));
        batch("""
            INSERT INTO goods_color_translations (color_id, locale, name)
            VALUES (:colorId, :locale, :name)
            """, parameters);
    }

    private void insertSizes(Goods goods) {
        batch("""
            INSERT INTO goods_sizes (id, goods_id, sort_order)
            VALUES (:id, :goodsId, :sortOrder)
            """, indexedSizes(goods));
        insertSizeTranslations(goods.sizes());
    }

    private void insertSizes(UUID goodsId, List<GoodsSize> sizes) {
        List<MapSqlParameterSource> parameters = new ArrayList<>();
        for (int index = 0; index < sizes.size(); index++) {
            GoodsSize size = sizes.get(index);
            parameters.add(new MapSqlParameterSource().addValue("id", size.id())
                .addValue("goodsId", goodsId).addValue("sortOrder", -1 - index));
        }
        batch("INSERT INTO goods_sizes (id, goods_id, sort_order) VALUES (:id, :goodsId, :sortOrder)", parameters);
    }

    private List<MapSqlParameterSource> indexedSizes(Goods goods) {
        List<MapSqlParameterSource> result = new ArrayList<>();
        for (int index = 0; index < goods.sizes().size(); index++) {
            result.add(new MapSqlParameterSource().addValue("id", goods.sizes().get(index).id())
                .addValue("goodsId", goods.id()).addValue("sortOrder", index));
        }
        return result;
    }

    private void insertSizeTranslations(List<GoodsSize> sizes) {
        List<MapSqlParameterSource> parameters = new ArrayList<>();
        sizes.forEach(size -> size.translations().forEach((locale, translation) ->
            parameters.add(new MapSqlParameterSource().addValue("sizeId", size.id())
                .addValue("locale", locale).addValue("label", translation.label()))));
        batch("""
            INSERT INTO goods_size_translations (size_id, locale, label)
            VALUES (:sizeId, :locale, :label)
            """, parameters);
    }

    private void insertCombinations(UUID goodsId, List<GoodsCombination> combinations) {
        batch("""
            INSERT INTO goods_combinations (
                id, goods_id, color_id, size_id, availability, updated_at
            ) VALUES (
                :id, :goodsId, :colorId, :sizeId, :availability, :updatedAt
            )
            """, combinations.stream().map(combination -> new MapSqlParameterSource()
            .addValue("id", combination.id()).addValue("goodsId", goodsId)
            .addValue("colorId", combination.colorId()).addValue("sizeId", combination.sizeId())
            .addValue("availability", combination.availability().name())
            .addValue("updatedAt", atUtc(combination.updatedAt()))).toList());
    }

    private void deleteByIds(String table, List<UUID> ids) {
        if (!ids.isEmpty()) {
            jdbc.update("DELETE FROM " + table + " WHERE id IN (:ids)", new MapSqlParameterSource("ids", ids));
        }
    }

    private List<Goods> hydrate(List<GoodsHeader> headers) {
        if (headers.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = headers.stream().map(GoodsHeader::id).toList();
        Map<UUID, Map<String, GoodsTranslation>> translationsByGoods = loadTranslations(ids);
        Map<UUID, List<GoodsImage>> imagesByGoods = loadImages(ids);
        Map<UUID, List<GoodsColor>> colorsByGoods = loadColors(ids);
        Map<UUID, List<GoodsSize>> sizesByGoods = loadSizes(ids);
        Map<UUID, List<GoodsCombination>> combinationsByGoods = loadCombinations(ids);
        List<Goods> result = new ArrayList<>(headers.size());
        for (GoodsHeader header : headers) {
            result.add(new Goods(
                header.id(),
                header.festivalId(),
                header.optionMode(),
                translationsByGoods.getOrDefault(header.id(), Map.of()),
                header.priceAmount(),
                imagesByGoods.getOrDefault(header.id(), List.of()),
                colorsByGoods.getOrDefault(header.id(), List.of()),
                sizesByGoods.getOrDefault(header.id(), List.of()),
                combinationsByGoods.getOrDefault(header.id(), List.of()),
                header.createdAt(),
                header.updatedAt()
            ));
        }
        return result;
    }

    private Map<UUID, List<GoodsImage>> loadImages(List<UUID> goodsIds) {
        List<ImageHeader> imageHeaders = jdbc.query("""
            SELECT gi.goods_id, gi.media_id
            FROM goods_images AS gi
            JOIN goods AS goods
              ON goods.id = gi.goods_id
             AND goods.festival_id = gi.festival_id
            JOIN media_assets AS media
              ON media.id = gi.media_id
             AND media.festival_id = gi.festival_id
            WHERE gi.goods_id IN (:goodsIds)
              AND media.purpose = 'GOODS_IMAGE'
              AND media.attached_at IS NOT NULL
              AND media.detached_at IS NULL
            ORDER BY gi.goods_id, gi.sort_order
            """,
            new MapSqlParameterSource("goodsIds", goodsIds),
            (resultSet, rowNumber) -> new ImageHeader(
                resultSet.getObject("goods_id", UUID.class),
                resultSet.getObject("media_id", UUID.class)
            )
        );
        if (imageHeaders.isEmpty()) {
            return Map.of();
        }

        List<UUID> mediaIds = imageHeaders.stream().map(ImageHeader::mediaId).toList();
        Map<UUID, Map<String, GoodsImageTranslation>> translationsByMedia = new LinkedHashMap<>();
        jdbc.query("""
            SELECT media_id, locale, alt_text
            FROM goods_image_translations
            WHERE media_id IN (:mediaIds)
            """,
            new MapSqlParameterSource("mediaIds", mediaIds),
            (resultSet, rowNumber) -> {
                UUID mediaId = resultSet.getObject("media_id", UUID.class);
                translationsByMedia.computeIfAbsent(mediaId, key -> new LinkedHashMap<>())
                    .put(resultSet.getString("locale"), new GoodsImageTranslation(resultSet.getString("alt_text")));
                return null;
            }
        );

        Map<UUID, List<GoodsImage>> imagesByGoods = new LinkedHashMap<>();
        for (ImageHeader header : imageHeaders) {
            imagesByGoods.computeIfAbsent(header.goodsId(), key -> new ArrayList<>())
                .add(new GoodsImage(
                    header.mediaId(),
                    translationsByMedia.getOrDefault(header.mediaId(), Map.of())
                ));
        }
        return imagesByGoods;
    }

    private Map<UUID, Map<String, GoodsTranslation>> loadTranslations(List<UUID> goodsIds) {
        Map<UUID, Map<String, GoodsTranslation>> result = new LinkedHashMap<>();
        jdbc.query("""
            SELECT goods_id, locale, name, description
            FROM goods_translations
            WHERE goods_id IN (:goodsIds)
            """,
            new MapSqlParameterSource("goodsIds", goodsIds),
            (resultSet, rowNumber) -> {
                UUID goodsId = resultSet.getObject("goods_id", UUID.class);
                result.computeIfAbsent(goodsId, key -> new LinkedHashMap<>())
                    .put(resultSet.getString("locale"), new GoodsTranslation(
                        resultSet.getString("name"),
                        resultSet.getString("description")
                    ));
                return null;
            }
        );
        return result;
    }

    private Map<UUID, List<GoodsColor>> loadColors(List<UUID> goodsIds) {
        List<ColorHeader> colorHeaders = jdbc.query("""
            SELECT id, goods_id
            FROM goods_colors
            WHERE goods_id IN (:goodsIds)
            ORDER BY sort_order
            """,
            new MapSqlParameterSource("goodsIds", goodsIds),
            (resultSet, rowNumber) -> new ColorHeader(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("goods_id", UUID.class)
            )
        );
        if (colorHeaders.isEmpty()) {
            return Map.of();
        }
        List<UUID> colorIds = colorHeaders.stream().map(ColorHeader::id).toList();
        Map<UUID, Map<String, GoodsColorTranslation>> translationsByColor = new LinkedHashMap<>();
        jdbc.query("""
            SELECT color_id, locale, name
            FROM goods_color_translations
            WHERE color_id IN (:colorIds)
            """,
            new MapSqlParameterSource("colorIds", colorIds),
            (resultSet, rowNumber) -> {
                UUID colorId = resultSet.getObject("color_id", UUID.class);
                translationsByColor.computeIfAbsent(colorId, key -> new LinkedHashMap<>())
                    .put(resultSet.getString("locale"), new GoodsColorTranslation(resultSet.getString("name")));
                return null;
            }
        );
        Map<UUID, List<GoodsColor>> colorsByGoods = new LinkedHashMap<>();
        for (ColorHeader header : colorHeaders) {
            colorsByGoods.computeIfAbsent(header.goodsId(), key -> new ArrayList<>())
                .add(new GoodsColor(header.id(), translationsByColor.getOrDefault(header.id(), Map.of())));
        }
        return colorsByGoods;
    }

    private Map<UUID, List<GoodsSize>> loadSizes(List<UUID> goodsIds) {
        List<SizeHeader> sizeHeaders = jdbc.query("""
            SELECT id, goods_id
            FROM goods_sizes
            WHERE goods_id IN (:goodsIds)
            ORDER BY sort_order
            """,
            new MapSqlParameterSource("goodsIds", goodsIds),
            (resultSet, rowNumber) -> new SizeHeader(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("goods_id", UUID.class)
            )
        );
        if (sizeHeaders.isEmpty()) {
            return Map.of();
        }
        List<UUID> sizeIds = sizeHeaders.stream().map(SizeHeader::id).toList();
        Map<UUID, Map<String, GoodsSizeTranslation>> translationsBySize = new LinkedHashMap<>();
        jdbc.query("""
            SELECT size_id, locale, label
            FROM goods_size_translations
            WHERE size_id IN (:sizeIds)
            """,
            new MapSqlParameterSource("sizeIds", sizeIds),
            (resultSet, rowNumber) -> {
                UUID sizeId = resultSet.getObject("size_id", UUID.class);
                translationsBySize.computeIfAbsent(sizeId, key -> new LinkedHashMap<>())
                    .put(resultSet.getString("locale"), new GoodsSizeTranslation(resultSet.getString("label")));
                return null;
            }
        );
        Map<UUID, List<GoodsSize>> sizesByGoods = new LinkedHashMap<>();
        for (SizeHeader header : sizeHeaders) {
            sizesByGoods.computeIfAbsent(header.goodsId(), key -> new ArrayList<>())
                .add(new GoodsSize(header.id(), translationsBySize.getOrDefault(header.id(), Map.of())));
        }
        return sizesByGoods;
    }

    private Map<UUID, List<GoodsCombination>> loadCombinations(List<UUID> goodsIds) {
        Map<UUID, List<GoodsCombination>> combinationsByGoods = new LinkedHashMap<>();
        jdbc.query("""
            SELECT id, goods_id, color_id, size_id, availability, updated_at
            FROM goods_combinations
            WHERE goods_id IN (:goodsIds)
            ORDER BY updated_at, id
            """,
            new MapSqlParameterSource("goodsIds", goodsIds),
            (resultSet, rowNumber) -> {
                UUID goodsId = resultSet.getObject("goods_id", UUID.class);
                combinationsByGoods.computeIfAbsent(goodsId, key -> new ArrayList<>())
                    .add(new GoodsCombination(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("color_id", UUID.class),
                        resultSet.getObject("size_id", UUID.class),
                        GoodsAvailability.valueOf(resultSet.getString("availability")),
                        resultSet.getObject("updated_at", OffsetDateTime.class).toInstant()
                    ));
                return null;
            }
        );
        return combinationsByGoods;
    }

    private GoodsHeader mapHeader(ResultSet resultSet) throws SQLException {
        return new GoodsHeader(
            resultSet.getObject("id", UUID.class),
            resultSet.getObject("festival_id", UUID.class),
            GoodsOptionMode.valueOf(resultSet.getString("option_mode")),
            resultSet.getLong("price_amount"),
            resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
            resultSet.getObject("updated_at", OffsetDateTime.class).toInstant()
        );
    }

    private void batch(String sql, List<MapSqlParameterSource> parameters) {
        if (!parameters.isEmpty()) {
            jdbc.batchUpdate(sql, parameters.toArray(MapSqlParameterSource[]::new));
        }
    }

    private static OffsetDateTime atUtc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record GoodsHeader(
        UUID id,
        UUID festivalId,
        GoodsOptionMode optionMode,
        long priceAmount,
        Instant createdAt,
        Instant updatedAt
    ) {}

    private record ColorHeader(UUID id, UUID goodsId) {}

    private record SizeHeader(UUID id, UUID goodsId) {}

    private record ImageHeader(UUID goodsId, UUID mediaId) {}
}
