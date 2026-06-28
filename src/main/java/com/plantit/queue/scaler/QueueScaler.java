package com.plantit.queue.scaler;

import com.plantit.queue.QueueManager;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Optional Pterodactyl auto-scaler.
 *
 * Scale-up:  when queue length exceeds the threshold, starts the next stopped server.
 * Scale-down: when a server has been empty for {@code scaleDownIdleMinutes}, stops it
 *             (unless it is marked always-on).
 *
 * Enabled via {@code pterodactyl.enabled: true} in config.yml.
 */
public class QueueScaler {

    private final ProxyServer proxy;
    private final QueueManager queueManager;
    private final PterodactylConfig pteroConfig;
    private final PterodactylClient client;
    private final Logger logger;
    private final Object plugin;

    /** Tracks when each server last had players. Null = server not yet seen as empty. */
    private final Map<String, Instant> idleSince = new HashMap<>();

    public QueueScaler(Object plugin, ProxyServer proxy, QueueManager queueManager,
                       PterodactylConfig pteroConfig, Logger logger) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.queueManager = queueManager;
        this.pteroConfig = pteroConfig;
        this.logger = logger;
        this.client = new PterodactylClient(pteroConfig.getPanelUrl(), pteroConfig.getApiKey());
    }

    /** Call once on enable — starts always-on servers and schedules the check loop. */
    public void start() {
        // Boot always-on servers immediately
        for (PterodactylConfig.ServerEntry entry : pteroConfig.getServers()) {
            if (entry.alwaysOn()) {
                startServer(entry);
            }
        }

        // Run scale check every 30 seconds
        proxy.getScheduler()
                .buildTask(plugin, this::scaleTick)
                .repeat(30, TimeUnit.SECONDS)
                .delay(30, TimeUnit.SECONDS)
                .schedule();
    }

    private void scaleTick() {
        int queueSize = queueManager.size();

        for (PterodactylConfig.ServerEntry entry : pteroConfig.getServers()) {
            String velocityName = entry.velocityName();
            boolean hasPlayers = proxy.getServer(velocityName)
                    .map(s -> !s.getPlayersConnected().isEmpty())
                    .orElse(false);

            if (hasPlayers) {
                idleSince.remove(velocityName);
            } else {
                // Scale up: start server if queue is long and server is offline
                if (queueSize >= pteroConfig.getScaleUpThreshold()) {
                    tryScaleUp(entry);
                }

                // Scale down: stop server if idle long enough and not always-on
                if (!entry.alwaysOn()) {
                    Instant idle = idleSince.computeIfAbsent(velocityName, k -> Instant.now());
                    long idleMinutes = (Instant.now().toEpochMilli() - idle.toEpochMilli()) / 60_000;
                    if (idleMinutes >= pteroConfig.getScaleDownIdleMinutes()) {
                        stopServer(entry);
                        idleSince.remove(velocityName);
                    }
                }
            }
        }
    }

    private void tryScaleUp(PterodactylConfig.ServerEntry entry) {
        proxy.getScheduler().buildTask(plugin, () -> {
            try {
                String state = client.getPowerState(entry.identifier());
                if (state.equals("offline")) {
                    logger.info("Queue length {} >= threshold {} — starting '{}'.",
                            queueManager.size(), pteroConfig.getScaleUpThreshold(), entry.velocityName());
                    startServer(entry);
                }
            } catch (Exception e) {
                logger.error("Failed to check state of '{}': {}", entry.velocityName(), e.getMessage());
            }
        }).schedule();
    }

    private void startServer(PterodactylConfig.ServerEntry entry) {
        proxy.getScheduler().buildTask(plugin, () -> {
            try {
                client.sendPowerSignal(entry.identifier(), "start");
                logger.info("Started Pterodactyl server '{}'.", entry.velocityName());
            } catch (Exception e) {
                logger.error("Failed to start '{}': {}", entry.velocityName(), e.getMessage());
            }
        }).schedule();
    }

    private void stopServer(PterodactylConfig.ServerEntry entry) {
        proxy.getScheduler().buildTask(plugin, () -> {
            try {
                client.sendPowerSignal(entry.identifier(), "stop");
                logger.info("Stopped idle Pterodactyl server '{}'.", entry.velocityName());
            } catch (Exception e) {
                logger.error("Failed to stop '{}': {}", entry.velocityName(), e.getMessage());
            }
        }).schedule();
    }
}
