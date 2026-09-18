package dev.espero.festival.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class TransferLinkPolicyTest {

    @Test
    void acceptsOnlyHttpsOnAnExactApprovedHost() {
        OperationalAccountProperties properties = new OperationalAccountProperties();
        properties.setTransferLinkAllowedHosts(List.of("example.test"));
        TransferLinkPolicy policy = new TransferLinkPolicy(properties);

        assertThat(policy.validateOptional("https://EXAMPLE.test/transfer?source=ticket"))
            .isEqualTo("https://EXAMPLE.test/transfer?source=ticket");
        assertThat(policy.validateOptional(null)).isNull();
        for (String invalid : List.of(
            "http://example.test/transfer",
            "https://sub.example.test/transfer",
            "https://example.test.evil/transfer",
            "https://operator@example.test/transfer",
            "https://example.test/transfer#fragment",
            "https://example.test:8443/transfer"
        )) {
            assertThatThrownBy(() -> policy.validateOptional(invalid))
                .isInstanceOf(OperationalAccountException.class)
                .extracting(exception -> ((OperationalAccountException) exception).code())
                .isEqualTo("TRANSFER_LINK_INVALID");
        }
    }

    @Test
    void rejectsAnInvalidConfiguredAllowlistInsteadOfSilentlyIgnoringIt() {
        OperationalAccountProperties properties = new OperationalAccountProperties();
        properties.setTransferLinkAllowedHosts(List.of("example.test/path"));

        assertThatThrownBy(() -> new TransferLinkPolicy(properties))
            .isInstanceOf(OperationalAccountException.class)
            .extracting(exception -> ((OperationalAccountException) exception).code())
            .isEqualTo("TRANSFER_LINK_ALLOWLIST_INVALID");
    }
}
