package com.plantit.queue.scaler;

import com.plantit.queue.QueueManager;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    private static final int STARTUP_HISTORY_SIZE = 10;
    private static final long DEFAULT_STARTUP_ESTIMATE_MS = 60_000;

    private final ProxyServer proxy;
    private final QueueManager queueManager;
    private final PterodactylConfig pteroConfig;
    private final PterodactylClient client;
    private final Logger logger;
    private final Object plugin;

    private final Map<String, Instant> idleSince = new HashMap<>();

    /** velocityName → time start signal was sent. Non-empty while servers are booting. */
    private final Map<String, Instant> serverStartedAt = new ConcurrentHashMap<>();

    /** Rolling history of observed startup durations in milliseconds. */
    private final ArrayDeque<Long> startupHistory = new ArrayDeque<>();

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
        proxy.getScheduler().buildTask(plugin, this::scaleTick).schedule();
    }

    /** Called when the queue is re-enabled via /piq start. Exits drain mode. */
    public void notifyQueueStarted() {
        drainMode.set(false);
        logger.info("Queue started — scaler exiting drain mode.");
    }

    /**
     * Called by QueueManager when players have no server assigned (all servers at capacity).
     * Triggers an immediate scale-up check so a new server starts as fast as possible.
     */
    public void notifyUnassignedPlayers(int unassignedCount) {
        if (drainMode.get()) return;
        logger.info("{} player(s) unassigned — triggering scale-up check.", unassignedCount);
        proxy.getScheduler().buildTask(plugin, this::scaleTick).schedule();
    }

    /** Returns true if at least one server has been started and hasn't reported running yet. */
    public boolean isAnyServerStarting() {
        return !serverStartedAt.isEmpty();
    }

    /**
     * Returns how many seconds until the soonest starting server is expected to be ready,
     * based on the rolling average of historical startup durations.
     * Returns 0 if the ETA has already elapsed (server is overdue).
     * Returns -1 if no server is currently starting.
     */
    public long getStartupEtaSeconds() {
        if (serverStartedAt.isEmpty()) return -1;

        long avgMs = averageStartupMillis();
        long now = Instant.now().toEpochMilli();

        long minEtaMs = Long.MAX_VALUE;
        for (Instant startedAt : serverStartedAt.values()) {
            long elapsed = now - startedAt.toEpochMilli();
            long remaining = avgMs - elapsed;
            minEtaMs = Math.min(minEtaMs, remaining);
        }

        return minEtaMs == Long.MAX_VALUE ? -1 : Math.max(0, minEtaMs / 1000);
    }

    /** Returns the current rolling average startup time in seconds (0 if no history yet). */
    public long getAverageStartupSeconds() {
        return averageStartupMillis() / 1000;
    }

    private long averageStartupMillis() {
        synchronized (startupHistory) {
            if (startupHistory.isEmpty()) return DEFAULT_STARTUP_ESTIMATE_MS;
            return startupHistory.stream().mapToLong(Long::longValue).sum() / startupHistory.size();
        }
    }

    private void recordStartupDuration(long durationMs) {
        synchronized (startupHistory) {
            if (startupHistory.size() >= STARTUP_HISTORY_SIZE) startupHistory.pollFirst();
            startupHistory.addLast(durationMs);
        }
        logger.info("Server startup completed in {}s. Rolling avg now {}s ({} sample(s)).",
                durationMs / 1000, getAverageStartupSeconds(), startupHistory.size());
    }

    private void scaleTick() {
        boolean draining = drainMode.get();
        int unassigned = queueManager.getUnassignedCount();

        for (PterodactylConfig.ServerEntry entry : pteroConfig.getServers()) {
            String velocityName = entry.velocityName();
            boolean hasPlayers = proxy.getServer(velocityName)
                    .map(s -> !s.getPlayersConnected().isEmpty())
                    .orElse(false);

            if (hasPlayers) {
                idleSince.remove(velocityName);
                continue;
            }

            // Empty server path
            if (!entry.alwaysOn()) {
                if (draining) {
                    logger.info("Drain mode: stopping empty server '{}'.", velocityName);
                    stopServer(entry);
                    idleSince.remove(velocityName);
                } else {
                    Instant idle = idleSince.computeIfAbsent(velocityName, k -> Instant.now());
                    long idleMinutes = (Instant.now().toEpochMilli() - idle.toEpochMilli()) / 60_000;
                    if (idleMinutes >= pteroConfig.getScaleDownIdleMinutes()) {
                        stopServer(entry);
                        idleSince.remove(velocityName);
                    }
                }
            }

            // Scale up when there are players with no server to go to
            if (!draining && unassigned > 0) {
                tryScaleUp(entry);
            }
        }
    }

    private void tryScaleUp(PterodactylConfig.ServerEntry entry) {
        // Already issued a start command for this server — don't double-start
        if (serverStartedAt.containsKey(entry.velocityName())) return;

        proxy.getScheduler().buildTask(plugin, () -> {
            try {
                String state = client.getPowerState(entry.identifier());
                if (state.equals("offline")) {
                    logger.info("{} unassigned player(s) — starting '{}' (avg startup: {}s).",
                            queueManager.getUnassignedCount(), entry.velocityName(), getAverageStartupSeconds());
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
                serverStartedAt.put(entry.velocityName(), Instant.now());
                logger.info("Start signal sent to '{}' — polling every 5s for ready state.", entry.velocityName());
                scheduleStartupPoll();
            } catch (Exception e) {
                logger.error("Failed to start '{}': {}", entry.velocityName(), e.getMessage());
            }
        }).schedule();
    }

    private void stopServer(PterodactylConfig.ServerEntry entry) {
        proxy.getScheduler().buildTask(plugin, () -> {
            try {
                client.sendPowerSignal(entry.identifier(), "stop");
                serverStartedAt.remove(entry.velocityName());
                logger.info("Stopped server '{}'.", entry.velocityName());
            } catch (Exception e) {
                logger.error("Failed to stop '{}': {}", entry.velocityName(), e.getMessage());
            }
        }).schedule();
    }

    private void scheduleStartupPoll() {
        proxy.getScheduler()
                .buildTask(plugin, this::pollStartingServers)
                .delay(5, TimeUnit.SECONDS)
                .schedule();
    }

    /** Polls Pterodactyl for each booting server and records when it reaches running state. */
    private void pollStartingServers() {
        if (serverStartedAt.isEmpty()) return;

        for (PterodactylConfig.ServerEntry entry : pteroConfig.getServers()) {
            Instant startedAt = serverStartedAt.get(entry.velocityName());
            if (startedAt == null) continue;

            try {
                String state = client.getPowerState(entry.identifier());
                if (state.equals("running")) {
                    long durationMs = Instant.now().toEpochMilli() - startedAt.toEpochMilli();
                    serverStartedAt.remove(entry.velocityName());
                    recordStartupDuration(durationMs);
                } else if (state.equals("offline")) {
                    // Went back offline before reaching running — remove so scale-up can retry
                    serverStartedAt.remove(entry.velocityName());
                    logger.warn("Server '{}' returned offline before reaching running state.", entry.velocityName());
                }
                // "starting" or "stopping" → keep polling
            } catch (Exception e) {
                logger.error("Failed to poll state of '{}': {}", entry.velocityName(), e.getMessage());
            }
        }

        if (!serverStartedAt.isEmpty()) {
            scheduleStartupPoll();
        }
    }
}
