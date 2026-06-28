package com.plantit.queue.scaler;

import com.plantit.queue.QueueManager;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional Pterodactyl auto-scaler.
 *
 * Scale-up:  when queue length exceeds the threshold, starts the next stopped server.
 * Scale-down (normal): when a server has been empty for {@code scaleDownIdleMinutes}, stops it.
 * Scale-down (queue stopped): when /piq stop is called, empty non-always-on servers are
 *             stopped immediately on the next tick without waiting for the idle timer.
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

    private final Map<String, Instant> idleSince = new HashMap<>();

    /** Set to true by /piq stop — skips idle timer and stops empty servers immediately. */
    private final AtomicBoolean drainMode = new AtomicBoolean(false);

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
        for (PterodactylConfig.ServerEntry entry : pteroConfig.getServers()) {
            if (entry.alwaysOn()) startServer(entry);
        }

        proxy.getScheduler()
                .buildTask(plugin, this::scaleTick)
                .repeat(30, TimeUnit.SECONDS)
                .delay(30, TimeUnit.SECONDS)
                .schedule();
    }

    /**
     * Called when the queue is stopped via /piq stop.
     * Switches to drain mode: empty non-always-on servers are stopped on the next tick
     * without waiting for the normal idle timer.
     */
    public void notifyQueueStopped() {
        drainMode.set(true);
        logger.info("Queue stopped — scaler entering drain mode, will shut down empty servers.");
        // Run an immediate tick so servers that are already empty stop right away
        proxy.getScheduler().buildTask(plugin, this::scaleTick).schedule();
    }

    /** Called when the queue is re-enabled via /piq start. Exits drain mode. */
    public void notifyQueueStarted() {
        drainMode.set(false);
        logger.info("Queue started — scaler exiting drain mode.");
    }

    private void scaleTick() {
        int queueSize = queueManager.size();
        boolean draining = drainMode.get();

        for (PterodactylConfig.ServerEntry entry : pteroConfig.getServers()) {
            String velocityName = entry.velocityName();
            boolean hasPlayers = proxy.getServer(velocityName)
                    .map(s -> !s.getPlayersConnected().isEmpty())
                    .orElse(false);

            if (hasPlayers) {
                idleSince.remove(velocityName);
                return;
            }

            // Empty server path
            if (!entry.alwaysOn()) {
                if (draining) {
                    // Queue is stopped — shut down empty servers immediately
                    logger.info("Drain mode: stopping empty server '{}'.", velocityName);
                    stopServer(entry);
                    idleSince.remove(velocityName);
                } else {
                    // Normal idle timer
                    Instant idle = idleSince.computeIfAbsent(velocityName, k -> Instant.now());
                    long idleMinutes = (Instant.now().toEpochMilli() - idle.toEpochMilli()) / 60_000;
                    if (idleMinutes >= pteroConfig.getScaleDownIdleMinutes()) {
                        stopServer(entry);
                        idleSince.remove(velocityName);
                    }
                }
            }

            // Scale up only when queue is running and has demand
            if (!draining && queueSize >= pteroConfig.getScaleUpThreshold()) {
                tryScaleUp(entry);
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
                logger.info("Stopped server '{}'.", entry.velocityName());
            } catch (Exception e) {
                logger.error("Failed to stop '{}': {}", entry.velocityName(), e.getMessage());
            }
        }).schedule();
    }
}
