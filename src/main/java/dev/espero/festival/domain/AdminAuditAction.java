package dev.espero.festival.domain;

/** Minimal action vocabulary; operational integrations add values when implemented. */
public enum AdminAuditAction {
    CROWDING_UPDATED,
    NOTICE_CREATED,
    NOTICE_UPDATED,
    NOTICE_DELETED,
    GOODS_AVAILABILITY_UPDATED,
    GOODS_IMAGE_UPLOADED,
    PRODUCT_CREATED
}
