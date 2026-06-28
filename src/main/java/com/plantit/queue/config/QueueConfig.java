package com.plantit.queue.config;

import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class QueueConfig {

    private List<String> queueServers = List.of("lobby");
    private List<String> gameServers = List.of();
    private int broadcastInterval = 3;
    private int maxPlayersPerServer = 10;
    private boolean debugMode = false;

    /** No-arg constructor yields safe defaults. */
    public QueueConfig() { }

    public static QueueConfig load(Path dataDirectory) throws IOException {
        Path configFile = dataDirectory.resolve("config.yml");

        if (!Files.exists(configFile)) {
            Files.createDirectories(dataDirectory);
            try (InputStream in = QueueConfig.class.getResourceAsStream("/config.yml")) {
                if (in != null) Files.copy(in, configFile);
            }
        }

        YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
                .path(configFile)
                .build();

        ConfigurationNode root = loader.load();
        QueueConfig config = new QueueConfig();
        config.queueServers = root.node("queue-servers").getList(String.class, List.of("lobby"));
        config.gameServers = root.node("game-servers").getList(String.class, List.of());
        config.broadcastInterval = root.node("position-broadcast-interval").getInt(3);
        config.maxPlayersPerServer = root.node("max-players-per-server").getInt(10);
        config.debugMode = root.node("debug", "enabled").getBoolean(false);
        return config;
    }

    /** Returns true if {@code serverName} is allowed to dispatch players from the queue. */
    public boolean isGameServer(String serverName) {
        // Empty list = allow any server (single-server setups or trusted networks)
        return gameServers.isEmpty() || gameServers.contains(serverName);
    }

    public List<String> getQueueServers() {
        return queueServers;
    }

    public List<String> getGameServers() {
        return gameServers;
    }

    public int getBroadcastInterval() {
        return broadcastInterval;
    }

    public int getMaxPlayersPerServer() {
        return maxPlayersPerServer;
    }

    public boolean isDebugMode() {
        return debugMode;
    }
}
