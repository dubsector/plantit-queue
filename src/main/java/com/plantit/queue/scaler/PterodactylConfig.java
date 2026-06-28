package com.plantit.queue.scaler;

import org.spongepowered.configurate.ConfigurationNode;

import java.util.ArrayList;
import java.util.List;

public class PterodactylConfig {

    private final boolean enabled;
    private final String panelUrl;
    private final String apiKey;
    private final int scaleDownIdleMinutes;
    private final List<ServerEntry> servers;

    private PterodactylConfig(boolean enabled, String panelUrl, String apiKey,
                               int scaleDownIdleMinutes, List<ServerEntry> servers) {
        this.enabled = enabled;
        this.panelUrl = panelUrl;
        this.apiKey = apiKey;
        this.scaleDownIdleMinutes = scaleDownIdleMinutes;
        this.servers = servers;
    }

    public static PterodactylConfig from(ConfigurationNode root) throws Exception {
        ConfigurationNode ptero = root.node("pterodactyl");

        boolean enabled = ptero.node("enabled").getBoolean(false);
        String panelUrl = ptero.node("panel-url").getString("");
        String apiKey = ptero.node("api-key").getString("");
        int idleMinutes = ptero.node("scale-down-idle-minutes").getInt(5);

        List<ServerEntry> servers = new ArrayList<>();
        ConfigurationNode serversNode = ptero.node("servers");
        for (var entry : serversNode.childrenMap().entrySet()) {
            String velocityName = entry.getKey().toString();
            ConfigurationNode node = entry.getValue();
            String identifier = node.node("identifier").getString("");
            boolean alwaysOn = node.node("always-on").getBoolean(false);
            servers.add(new ServerEntry(velocityName, identifier, alwaysOn));
        }

        return new PterodactylConfig(enabled, panelUrl, apiKey, idleMinutes, servers);
    }

    public boolean isEnabled() { return enabled; }
    public String getPanelUrl() { return panelUrl; }
    public String getApiKey() { return apiKey; }
    public int getScaleDownIdleMinutes() { return scaleDownIdleMinutes; }
    public List<ServerEntry> getServers() { return servers; }

    public record ServerEntry(String velocityName, String identifier, boolean alwaysOn) { }
}
