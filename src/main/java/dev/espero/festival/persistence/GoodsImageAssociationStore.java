package dev.espero.festival.persistence;

import dev.espero.festival.media.GoodsImageAssociationInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence boundary for new-goods image associations and alt translations. */
@Repository
@Profile("db")
public class GoodsImageAssociationStore {

    private final NamedParameterJdbcTemplate jdbc;

    public GoodsImageAssociationStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean lockOwnedGoods(UUID festivalId, UUID goodsId) {
        return !jdbc.query("""
            SELECT id
            FROM goods
            WHERE id = :goodsId
              AND festival_id = :festivalId
            FOR UPDATE
            """,
            Map.of("festivalId", festivalId, "goodsId", goodsId),
            (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class)
        ).isEmpty();
    }

    public void insertAssociations(
        UUID festivalId,
        UUID goodsId,
        List<GoodsImageAssociationInput> images
    ) {
        MapSqlParameterSource[] parameters = images.stream()
            .map(image -> new MapSqlParameterSource()
                .addValue("mediaId", image.mediaId())
                .addValue("festivalId", festivalId)
                .addValue("goodsId", goodsId)
                .addValue("sortOrder", image.sortOrder()))
            .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
            INSERT INTO goods_images (media_id, festival_id, goods_id, sort_order)
            VALUES (:mediaId, :festivalId, :goodsId, :sortOrder)
            """, parameters);
    }

    public void insertTranslations(List<GoodsImageAssociationInput> images) {
        List<MapSqlParameterSource> parameters = new ArrayList<>();
        for (GoodsImageAssociationInput image : images) {
            image.alt().forEach((locale, alt) -> {
                if (alt != null) {
                    parameters.add(new MapSqlParameterSource()
                        .addValue("mediaId", image.mediaId())
                        .addValue("locale", locale)
                        .addValue("alt", alt));
                }
            });
        }
        jdbc.batchUpdate("""
            INSERT INTO goods_image_translations (media_id, locale, alt_text)
            VALUES (:mediaId, :locale, :alt)
            """, parameters.toArray(MapSqlParameterSource[]::new));
    }
}
