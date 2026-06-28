package com.plantit.queue;

import com.plantit.queue.command.PiqCommand;
import com.plantit.queue.config.QueueConfig;
import com.plantit.queue.listener.ConnectionListener;
import com.plantit.queue.listener.MessagingListener;
import com.plantit.queue.plan.PlanHook;
import com.plantit.queue.scaler.PterodactylConfig;
import com.plantit.queue.scaler.QueueScaler;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;

import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Plugin(
        id = "plantit-queue",
        name = "PlantIt Queue",
        version = "1.0.0-SNAPSHOT",
        description = "Proxy-level queue system for Plant It game servers",
        authors = {"dubsector"},
        dependencies = {@Dependency(id = "plan", optional = true)}
)
public class PlantItQueue {

    static final MinecraftChannelIdentifier QUEUE_CHANNEL =
            MinecraftChannelIdentifier.from(MessagingListener.CHANNEL);

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private QueueManager queueManager;

    @Inject
    public PlantItQueue(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        QueueConfig config;
        try {
            config = QueueConfig.load(dataDirectory);
        } catch (IOException e) {
            logger.error("Failed to load config, using defaults", e);
            config = new QueueConfig();
        }

        queueManager = new QueueManager(server, config, logger);

        server.getChannelRegistrar().register(QUEUE_CHANNEL);

        server.getEventManager().register(this, new ConnectionListener(queueManager, config));
        server.getEventManager().register(this, new MessagingListener(queueManager, config, logger));

        server.getCommandManager().register(
                server.getCommandManager().metaBuilder("piq").build(),
                new PiqCommand(queueManager, this));

        final QueueConfig finalConfig = config;
        server.getScheduler()
                .buildTask(this, queueManager::broadcastPositions)
                .repeat(finalConfig.getBroadcastInterval(), TimeUnit.SECONDS)
                .schedule();

        // Optional Pterodactyl auto-scaler
        try {
            PterodactylConfig pteroConfig = PterodactylConfig.from(
                    YamlConfigurationLoader.builder()
                            .path(dataDirectory.resolve("config.yml"))
                            .build()
                            .load());
            if (pteroConfig.isEnabled()) {
                QueueScaler scaler = new QueueScaler(this, server, queueManager, pteroConfig, logger);
                queueManager.setScaler(scaler);
                scaler.start();
                logger.info("Pterodactyl auto-scaler enabled ({} servers configured).",
                        pteroConfig.getServers().size());
            }
        } catch (Exception e) {
            logger.warn("Pterodactyl scaler not loaded: {}", e.getMessage());
        }

        // Optional Plan integration — registers queue metrics in the Plan web UI
        try {
            PlanHook.register(queueManager);
        } catch (NoClassDefFoundError ignored) {
            // Plan not installed
        } catch (Exception e) {
            logger.warn("Plan hook failed to register: {}", e.getMessage());
        }

        if (config.isDebugMode()) {
            logger.warn("=================================================");
            logger.warn("  DEBUG MODE IS ENABLED — disable before going live");
            logger.warn("=================================================");
        }

        logger.info("PlantIt Queue enabled. Eligible servers: {}", config.getQueueServers());
    }

    /** Reloads config from disk and hot-swaps it into the queue manager. */
    public void reload() {
        try {
            QueueConfig fresh = QueueConfig.load(dataDirectory);
            queueManager.updateConfig(fresh);
            if (fresh.isDebugMode()) {
                logger.warn("Debug mode is ENABLED after reload.");
            }
            logger.info("Config reloaded. Debug mode: {}", fresh.isDebugMode());
        } catch (IOException e) {
            logger.error("Failed to reload config: {}", e.getMessage());
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        server.getChannelRegistrar().unregister(QUEUE_CHANNEL);
        logger.info("PlantIt Queue disabled.");
    }
}
