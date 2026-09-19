package dev.espero.festival.persistence;

import dev.espero.festival.media.GoodsImageAssociationInput;
import java.util.ArrayList;
import java.time.OffsetDateTime;
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

    /** Locks every current association and media lifecycle row, including malformed lifecycle states. */
    public List<LockedGoodsImage> lockCurrent(UUID festivalId, UUID goodsId) {
        return jdbc.query("""
            SELECT image.media_id, image.festival_id, media.purpose,
                   media.attached_at, media.detached_at
            FROM goods_images AS image
            JOIN media_assets AS media
              ON media.id = image.media_id
             AND media.festival_id = image.festival_id
            WHERE image.goods_id = :goodsId
              AND image.festival_id = :festivalId
            ORDER BY image.media_id
            FOR UPDATE OF image, media
            """, Map.of("festivalId", festivalId, "goodsId", goodsId), (resultSet, rowNumber) -> new LockedGoodsImage(
            resultSet.getObject("media_id", UUID.class),
            resultSet.getObject("festival_id", UUID.class),
            resultSet.getString("purpose"),
            resultSet.getObject("attached_at", OffsetDateTime.class),
            resultSet.getObject("detached_at", OffsetDateTime.class)
        ));
    }

    public int deleteForGoods(UUID festivalId, UUID goodsId) {
        return jdbc.update("""
            DELETE FROM goods_images
            WHERE festival_id = :festivalId AND goods_id = :goodsId
            """, Map.of("festivalId", festivalId, "goodsId", goodsId));
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

    public record LockedGoodsImage(
        UUID mediaId,
        UUID festivalId,
        String purpose,
        OffsetDateTime attachedAt,
        OffsetDateTime detachedAt
    ) {}
}
