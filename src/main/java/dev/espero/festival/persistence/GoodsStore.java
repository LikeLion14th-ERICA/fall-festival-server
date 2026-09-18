package dev.espero.festival.persistence;

import dev.espero.festival.domain.Goods;
import dev.espero.festival.domain.GoodsAvailability;
import dev.espero.festival.domain.GoodsColor;
import dev.espero.festival.domain.GoodsColorTranslation;
import dev.espero.festival.domain.GoodsCombination;
import dev.espero.festival.domain.GoodsOptionMode;
import dev.espero.festival.domain.GoodsSize;
import dev.espero.festival.domain.GoodsSizeTranslation;
import dev.espero.festival.domain.GoodsTranslation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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

    private List<Goods> hydrate(List<GoodsHeader> headers) {
        if (headers.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = headers.stream().map(GoodsHeader::id).toList();
        Map<UUID, Map<String, GoodsTranslation>> translationsByGoods = loadTranslations(ids);
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
                colorsByGoods.getOrDefault(header.id(), List.of()),
                sizesByGoods.getOrDefault(header.id(), List.of()),
                combinationsByGoods.getOrDefault(header.id(), List.of()),
                header.createdAt(),
                header.updatedAt()
            ));
        }
        return result;
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
}
