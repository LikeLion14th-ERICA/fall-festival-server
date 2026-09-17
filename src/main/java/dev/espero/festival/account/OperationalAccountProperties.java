package dev.espero.festival.account;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Environment-owned allowlist for outbound transfer links. */
@ConfigurationProperties(prefix = "festival.operational-account")
public class OperationalAccountProperties {

    private List<String> transferLinkAllowedHosts = new ArrayList<>();

    public List<String> getTransferLinkAllowedHosts() {
        return List.copyOf(transferLinkAllowedHosts);
    }

    public void setTransferLinkAllowedHosts(List<String> transferLinkAllowedHosts) {
        this.transferLinkAllowedHosts = transferLinkAllowedHosts == null
            ? new ArrayList<>() : new ArrayList<>(transferLinkAllowedHosts);
    }
}
