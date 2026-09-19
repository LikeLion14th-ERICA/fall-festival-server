package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Each 2026 goods draft is a product request body whose only gap is the
 * uploaded image id. Filling that id must give a body the admin API accepts.
 */
class OperationalGoodsDraftTest {

    private static final Path DRAFT = Path.of("ops/catalog/hanyang-2026/goods-draft.json");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void everyProductIsValidOnceItsImageIsUploaded() throws IOException {
        JsonNode products = JSON.readTree(Files.readString(DRAFT)).path("products");
        assertThat(products).hasSize(7);
        for (JsonNode product : products) {
            ObjectNode body = (ObjectNode) product.path("body").deepCopy();
            for (JsonNode image : body.path("images")) {
                assertThat(image.path("mediaId").isNull()).as(product.path("key").asString()).isTrue();
                ((ObjectNode) image).put("mediaId", UUID.randomUUID().toString());
            }
            GoodsInput input = JSON.treeToValue(body, GoodsInput.class);
            GoodsInputValidator.validate(input);
        }
    }
}
