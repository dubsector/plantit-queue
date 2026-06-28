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
    private String gameServer = "plantit-1";
    private int broadcastInterval = 3;

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
        config.gameServer = root.node("game-server").getString("plantit-1");
        config.broadcastInterval = root.node("position-broadcast-interval").getInt(3);
        return config;
    }

    public List<String> getQueueServers() {
        return queueServers;
    }

    public String getGameServer() {
        return gameServer;
    }

    public int getBroadcastInterval() {
        return broadcastInterval;
    }
}
