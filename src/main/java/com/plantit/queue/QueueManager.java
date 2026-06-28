package com.plantit.queue;

import com.plantit.queue.config.QueueConfig;
import com.plantit.queue.scaler.QueueScaler;
import com.plantit.queue.vote.VoteSession;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class QueueManager {

    private static final Component PREFIX = Component.text()
            .append(Component.text("[", NamedTextColor.DARK_GRAY))
            .append(Component.text("Plant It", NamedTextColor.GREEN, TextDecoration.BOLD))
            .append(Component.text("] ", NamedTextColor.DARK_GRAY))
            .build();

    private static final Sound SOUND_JOIN = Sound.sound(
            Key.key("minecraft:entity.experience_orb.pickup"), Sound.Source.MASTER, 0.8f, 1.2f);
    private static final Sound SOUND_LEAVE = Sound.sound(
            Key.key("minecraft:block.note_block.bass"), Sound.Source.MASTER, 0.8f, 0.8f);
    private static final Sound SOUND_CONNECT = Sound.sound(
            Key.key("minecraft:entity.player.levelup"), Sound.Source.MASTER, 1f, 1f);
    private static final Sound SOUND_MOVE_UP = Sound.sound(
            Key.key("minecraft:block.note_block.pling"), Sound.Source.MASTER, 0.6f, 1.5f);

    private final ProxyServer proxy;
    private final Logger logger;
    private QueueConfig config;
    private PlantItQueue plugin;

    /** Global queue — defines position order and membership. */
    private final LinkedList<UUID> queue = new LinkedList<>();
    private final Map<UUID, BossBar> bossBars = new HashMap<>();
    private final Map<UUID, Integer> lastKnownPosition = new HashMap<>();

    /**
     * Server assignment tracking.
     * assignment:    player → assigned server name (absent = unassigned)
     * serverQueues:  server → ordered list of assigned players (insertion order = queue order)
     */
    private final Map<UUID, String> assignment = new HashMap<>();
    private final Map<String, LinkedList<UUID>> serverQueues = new LinkedHashMap<>();

    /** Vote session tracking: committed players waiting for map vote to end. */
    private final Map<UUID, VoteSession> committedToSession = new HashMap<>();

    private boolean stopped = false;
    private QueueScaler scaler = null;

    // Metrics — persisted in memory for Plan integration
    private final AtomicLong totalQueueJoins = new AtomicLong(0);
    private final AtomicLong totalDispatches = new AtomicLong(0);
    private final Map<String, AtomicLong> serverDispatchCounts = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> playerJoinCounts = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerLastServer = new ConcurrentHashMap<>();

    public QueueManager(ProxyServer proxy, QueueConfig config, Logger logger) {
        this.proxy = proxy;
        this.config = config;
        this.logger = logger;
    }

    public void updateConfig(QueueConfig config) {
        this.config = config;
        tryAssignUnassigned();
    }

    public void setPlugin(PlantItQueue plugin) {
        this.plugin = plugin;
    }

    public void setScaler(QueueScaler scaler) {
        this.scaler = scaler;
    }

    // -------------------------------------------------------------------------
    // Enqueue / dequeue
    // -------------------------------------------------------------------------

    public boolean enqueue(Player player) {
        if (stopped) {
            player.sendMessage(PREFIX.append(
                    Component.text("Plant It is currently unavailable. Check back soon!", NamedTextColor.RED)));
            return false;
        }
        if (queue.contains(player.getUniqueId())) {
            player.sendMessage(PREFIX.append(
                    Component.text("You are already in the queue.", NamedTextColor.RED)));
            return false;
        }
        if (!isOnEligibleServer(player)) {
            player.sendMessage(PREFIX.append(
                    Component.text("You can only queue from the lobby or other eligible servers.", NamedTextColor.RED)));
            return false;
        }

        queue.add(player.getUniqueId());
        totalQueueJoins.incrementAndGet();
        playerJoinCounts.computeIfAbsent(player.getUniqueId(), k -> new AtomicLong()).incrementAndGet();
        int pos = queue.size();

        BossBar bar = buildBossBar(pos, pos);
        bossBars.put(player.getUniqueId(), bar);
        player.showBossBar(bar);

        player.showTitle(Title.title(
                Component.text("Plant It", NamedTextColor.GREEN, TextDecoration.BOLD),
                Component.text("Joining queue — position #" + pos, NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2500), Duration.ofMillis(500))));

        player.sendMessage(Component.empty());
        player.sendMessage(PREFIX
                .append(Component.text("You joined the queue for ", NamedTextColor.GRAY))
                .append(Component.text("Plant It", NamedTextColor.GREEN, TextDecoration.BOLD))
                .append(Component.text("!", NamedTextColor.GRAY)));
        player.sendMessage(PREFIX
                .append(Component.text("Position: ", NamedTextColor.GRAY))
                .append(Component.text("#" + pos, NamedTextColor.YELLOW))
                .append(Component.text("  |  ", NamedTextColor.DARK_GRAY))
                .append(buildServerStatus()));
        player.sendMessage(PREFIX
                .append(Component.text("Use ", NamedTextColor.DARK_GRAY))
                .append(Component.text("/piq leave", NamedTextColor.GREEN))
                .append(Component.text(" to leave.", NamedTextColor.DARK_GRAY)));
        player.sendMessage(Component.empty());

        player.playSound(SOUND_JOIN);
        updateTabList(player, pos, queue.size(), null);
        lastKnownPosition.put(player.getUniqueId(), pos);

        return true;
    }

    /**
     * Admin-only: places a player at the front of the queue, bypassing eligibility checks.
     * If the player is already queued, they are moved to position #1.
     */
    public void enqueueFirst(Player player) {
        boolean alreadyQueued = queue.remove(player.getUniqueId());
        queue.addFirst(player.getUniqueId());

        if (!alreadyQueued) {
            totalQueueJoins.incrementAndGet();
            playerJoinCounts.computeIfAbsent(player.getUniqueId(), k -> new AtomicLong()).incrementAndGet();
        }

        BossBar existing = bossBars.get(player.getUniqueId());
        if (existing == null) {
            BossBar bar = buildBossBar(1, queue.size());
            bossBars.put(player.getUniqueId(), bar);
            player.showBossBar(bar);
        }

        player.showTitle(Title.title(
                Component.text("Priority", NamedTextColor.GOLD, TextDecoration.BOLD),
                Component.text("You've been moved to #1 in the queue!", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2500), Duration.ofMillis(500))));

        player.sendMessage(Component.empty());
        player.sendMessage(PREFIX
                .append(Component.text("An admin placed you at the front of the queue.", NamedTextColor.YELLOW)));
        player.sendMessage(PREFIX
                .append(Component.text("Position: ", NamedTextColor.GRAY))
                .append(Component.text("#1", NamedTextColor.YELLOW))
                .append(Component.text("  |  ", NamedTextColor.DARK_GRAY))
                .append(buildServerStatus()));
        player.sendMessage(Component.empty());

        player.playSound(SOUND_MOVE_UP);
        updateTabList(player, 1, queue.size(), null);
        lastKnownPosition.put(player.getUniqueId(), 1);
    }

    public void dequeue(UUID uuid) {
        boolean removed = queue.remove(uuid);
        BossBar bar = bossBars.remove(uuid);
        lastKnownPosition.remove(uuid);
        unassign(uuid);

        // Also remove from any active vote session
        VoteSession session = committedToSession.remove(uuid);
        if (session != null) session.removePlayer(uuid);

        if (removed || session != null) {
            proxy.getPlayer(uuid).ifPresent(p -> {
                if (bar != null) p.hideBossBar(bar);
                p.sendMessage(PREFIX.append(Component.text("You left the queue.", NamedTextColor.GRAY)));
                p.playSound(SOUND_LEAVE);
                clearTabList(p);
            });
        }
    }

    // -------------------------------------------------------------------------
    // Dispatch
    // -------------------------------------------------------------------------

    /**
     * Dispatches up to {@code count} players to {@code destination}.
     * Players already assigned to that server are pulled first (in queue order),
     * then unassigned players fill any remaining slots.
     */
    public void dispatchPlayers(int count, RegisteredServer destination) {
        String serverName = destination.getServerInfo().getName();

        for (int i = 0; i < count; i++) {
            UUID uuid = queue.peek();
            if (uuid == null) break;

            queue.remove(uuid);
            assignment.remove(uuid);
            BossBar bar = bossBars.remove(uuid);
            lastKnownPosition.remove(uuid);

            totalDispatches.incrementAndGet();
            serverDispatchCounts.computeIfAbsent(serverName, k -> new AtomicLong()).incrementAndGet();
            playerLastServer.put(uuid, serverName);

            proxy.getPlayer(uuid).ifPresent(p -> {
                if (bar != null) p.hideBossBar(bar);
                clearTabList(p);

                p.showTitle(Title.title(
                        Component.text("Connecting...", NamedTextColor.GREEN, TextDecoration.BOLD),
                        Component.text("Get ready to play!", NamedTextColor.GRAY),
                        Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(3000), Duration.ofMillis(500))));

                p.sendMessage(Component.empty());
                p.sendMessage(PREFIX.append(
                        Component.text("Found a match! Connecting you to the server...", NamedTextColor.GREEN)));
                p.sendMessage(Component.empty());
                p.playSound(SOUND_CONNECT);

                if (config.isDebugMode()) {
                    broadcastDebug(Component.text("[DEBUG] ", NamedTextColor.GOLD, TextDecoration.BOLD)
                            .append(Component.text(p.getUsername(), NamedTextColor.YELLOW))
                            .append(Component.text(" → ", NamedTextColor.GRAY))
                            .append(Component.text(serverName, NamedTextColor.GREEN)));
                }

                p.createConnectionRequest(destination).fireAndForget();
            });
        }

        // Notify scaler — players remain unassigned until a server signals SLOT_OPEN
        if (scaler != null && getUnassignedCount() > 0) {
            scaler.notifyUnassignedPlayers(getUnassignedCount());
        }
    }

    // -------------------------------------------------------------------------
    // Map vote
    // -------------------------------------------------------------------------

    /**
     * Commits the next {@code count} queued players to a vote session for {@code destination}.
     * If maps are not configured, falls back to immediate dispatch.
     */
    public void startVoteSession(int count, RegisteredServer destination) {
        if (!config.hasVoteMaps() || plugin == null) {
            dispatchPlayers(count, destination);
            return;
        }
        if (queue.size() < count) count = queue.size();
        if (count == 0) return;

        String serverName = destination.getServerInfo().getName();
        List<UUID> committed = new java.util.ArrayList<>();

        for (int i = 0; i < count; i++) {
            UUID uuid = queue.poll();
            if (uuid == null) break;
            assignment.remove(uuid);
            committed.add(uuid);

            BossBar bar = bossBars.remove(uuid);
            lastKnownPosition.remove(uuid);
            proxy.getPlayer(uuid).ifPresent(p -> {
                if (bar != null) p.hideBossBar(bar);
                clearTabList(p);
            });
        }

        if (committed.isEmpty()) return;

        VoteSession session = new VoteSession(
                committed,
                destination,
                config.getMaps(),
                config.getDefaultMap(),
                config.getVoteDuration(),
                proxy, plugin, this, logger);

        committed.forEach(uuid -> committedToSession.put(uuid, session));

        logger.info("Map vote started for '{}' — {} player(s), {}s window, maps: {}",
                serverName, committed.size(), config.getVoteDuration(), config.getMaps());
        session.start();
    }

    /** Records a map vote for a committed player. Returns false if they are not in a vote. */
    public boolean vote(UUID uuid, String map) {
        VoteSession session = committedToSession.get(uuid);
        if (session == null) return false;
        return session.vote(uuid, map);
    }

    public boolean isCommitted(UUID uuid) {
        return committedToSession.containsKey(uuid);
    }

    /** Called by VoteSession when the vote ends — dispatches the specific committed players. */
    public void dispatchCommitted(List<UUID> committed, RegisteredServer destination) {
        String serverName = destination.getServerInfo().getName();

        for (UUID uuid : committed) {
            committedToSession.remove(uuid);
            totalDispatches.incrementAndGet();
            serverDispatchCounts.computeIfAbsent(serverName, k -> new AtomicLong()).incrementAndGet();
            playerLastServer.put(uuid, serverName);

            proxy.getPlayer(uuid).ifPresent(p -> {
                p.showTitle(Title.title(
                        Component.text("Connecting...", NamedTextColor.GREEN, TextDecoration.BOLD),
                        Component.text("Get ready to play!", NamedTextColor.GRAY),
                        Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(3000), Duration.ofMillis(500))));
                p.sendMessage(Component.empty());
                p.sendMessage(PREFIX.append(
                        Component.text("Found a match! Connecting you to the server...", NamedTextColor.GREEN)));
                p.sendMessage(Component.empty());
                p.createConnectionRequest(destination).fireAndForget();
            });
        }

        if (scaler != null && getUnassignedCount() > 0) {
            scaler.notifyUnassignedPlayers(getUnassignedCount());
        }
    }

    // -------------------------------------------------------------------------
    // Assignment
    // -------------------------------------------------------------------------

    /**
     * Picks the best server to assign the next player to.
     *
     * Strategy (fill-first):
     *   1. Partially-filled server closest to capacity (concentrate players first)
     *   2. Empty running server (begin filling a fresh one)
     *   3. null — all servers at capacity (caller should signal scale-up)
     */
    private String pickServer() {
        int cap = config.getMaxPlayersPerServer();
        String bestFilling = null;
        int bestFillingCount = -1;
        String bestEmpty = null;

        for (String name : gameServerNames()) {
            int assigned = serverQueues.getOrDefault(name, new LinkedList<>()).size();
            if (assigned >= cap) continue;

            if (assigned > 0) {
                if (assigned > bestFillingCount) {
                    bestFillingCount = assigned;
                    bestFilling = name;
                }
            } else if (bestEmpty == null) {
                bestEmpty = name;
            }
        }

        return bestFilling != null ? bestFilling : bestEmpty;
    }

    /** Notifies the scaler if there are players waiting with no server available. */
    public void tryAssignUnassigned() {
        if (scaler != null && getUnassignedCount() > 0) {
            scaler.notifyUnassignedPlayers(getUnassignedCount());
        }
    }

    private void assign(UUID uuid, String serverName) {
        assignment.put(uuid, serverName);
        serverQueues.computeIfAbsent(serverName, k -> new LinkedList<>()).add(uuid);
    }

    private void unassign(UUID uuid) {
        String serverName = assignment.remove(uuid);
        if (serverName != null) {
            LinkedList<UUID> sq = serverQueues.get(serverName);
            if (sq != null) sq.remove(uuid);
        }
    }

    // -------------------------------------------------------------------------
    // Debug
    // -------------------------------------------------------------------------

    private void broadcastDebug(Component message) {
        Component prefixed = PREFIX.append(message);
        proxy.getAllPlayers().stream()
                .filter(p -> p.hasPermission("plantit.admin"))
                .forEach(p -> p.sendMessage(prefixed));
        logger.info("[DEBUG] {}", PlainTextComponentSerializer.plainText().serialize(message));
    }

    // -------------------------------------------------------------------------
    // Position broadcast
    // -------------------------------------------------------------------------

    public void broadcastPositions() {
        int total = queue.size();
        int pos = 1;

        for (UUID uuid : new LinkedList<>(queue)) {
            final int finalPos = pos++;
            proxy.getPlayer(uuid).ifPresent(p -> {
                BossBar bar = bossBars.get(uuid);
                if (bar != null) {
                    bar.name(buildBossBarText(finalPos, total));
                    bar.progress(barProgress(finalPos, total));
                    bar.color(barColor(finalPos));
                }

                Component actionBar = Component.text("Position ", NamedTextColor.GRAY)
                        .append(Component.text("#" + finalPos, NamedTextColor.YELLOW))
                        .append(Component.text("  |  ", NamedTextColor.DARK_GRAY))
                        .append(Component.text(total + " in queue", NamedTextColor.GRAY))
                        .append(Component.text("  |  ", NamedTextColor.DARK_GRAY))
                        .append(buildServerStatus())
                        .append(Component.text("  |  ", NamedTextColor.DARK_GRAY))
                        .append(Component.text("/piq leave", NamedTextColor.GREEN));
                p.sendActionBar(actionBar);

                updateTabList(p, finalPos, total, null);

                Integer prev = lastKnownPosition.get(uuid);
                if (prev != null && finalPos < prev) p.playSound(SOUND_MOVE_UP);
                lastKnownPosition.put(uuid, finalPos);
            });
        }
    }

    // -------------------------------------------------------------------------
    // Stop / start
    // -------------------------------------------------------------------------

    public void stop() {
        stopped = true;
        if (scaler != null) scaler.notifyQueueStopped();
        new LinkedList<>(queue).forEach(uuid -> {
            BossBar bar = bossBars.remove(uuid);
            lastKnownPosition.remove(uuid);
            unassign(uuid);
            queue.remove(uuid);
            proxy.getPlayer(uuid).ifPresent(p -> {
                if (bar != null) p.hideBossBar(bar);
                clearTabList(p);
                p.sendMessage(Component.empty());
                p.sendMessage(PREFIX.append(
                        Component.text("The queue has been closed by an administrator.", NamedTextColor.RED)));
                p.sendMessage(PREFIX.append(
                        Component.text("Plant It is currently unavailable. Check back soon!", NamedTextColor.GRAY)));
                p.sendMessage(Component.empty());
            });
        });
    }

    public void start() {
        stopped = false;
        if (scaler != null) scaler.notifyQueueStarted();
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public int getPosition(UUID uuid) {
        int pos = 1;
        for (UUID id : queue) {
            if (id.equals(uuid)) return pos;
            pos++;
        }
        return -1;
    }

    public boolean isQueued(UUID uuid) {
        return queue.contains(uuid);
    }

    public int size() {
        return queue.size();
    }

    /** Number of queued players with no server assigned — used by the scaler. */
    public int getUnassignedCount() {
        return (int) queue.stream().filter(id -> !assignment.containsKey(id)).count();
    }

    public boolean isStopped() {
        return stopped;
    }

    public boolean isDebugMode() {
        return config.isDebugMode();
    }

    public Player findPlayer(String name) {
        return proxy.getPlayer(name).orElse(null);
    }

    public record QueueStats(int currentSize, long totalJoins, long totalDispatches,
                              Map<String, Long> serverCounts) {}

    public QueueStats getStats() {
        return new QueueStats(queue.size(), totalQueueJoins.get(), totalDispatches.get(),
                getServerDispatchCounts());
    }

    /**
     * Debug-only: manually simulate a SLOT_OPEN signal from a server.
     * Returns an error message on failure, or null on success.
     */
    public String debugOpen(String velocityServerName, int slots) {
        var server = proxy.getServer(velocityServerName);
        if (server.isEmpty()) return "Unknown server '" + velocityServerName + "' — check velocity.toml";
        if (queue.isEmpty()) return "Queue is empty — nobody to dispatch";
        int dispatched = Math.min(slots, queue.size());
        dispatchPlayers(dispatched, server.get());
        return null;
    }

    public void loadMetrics(Path file) {
        if (!Files.exists(file)) return;
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
            totalQueueJoins.set(Long.parseLong(props.getProperty("totalQueueJoins", "0")));
            totalDispatches.set(Long.parseLong(props.getProperty("totalDispatches", "0")));
            props.stringPropertyNames().stream()
                    .filter(k -> k.startsWith("server."))
                    .forEach(k -> {
                        try {
                            serverDispatchCounts
                                    .computeIfAbsent(k.substring(7), n -> new AtomicLong())
                                    .set(Long.parseLong(props.getProperty(k, "0")));
                        } catch (NumberFormatException ignored) { }
                    });
            props.stringPropertyNames().stream()
                    .filter(k -> k.startsWith("player."))
                    .forEach(k -> {
                        try {
                            String[] parts = k.substring(7).split("\\.", 2);
                            if (parts[0].equals("joins")) {
                                playerJoinCounts
                                        .computeIfAbsent(UUID.fromString(parts[1]), u -> new AtomicLong())
                                        .set(Long.parseLong(props.getProperty(k, "0")));
                            } else if (parts[0].equals("lastServer")) {
                                playerLastServer.put(UUID.fromString(parts[1]), props.getProperty(k));
                            }
                        } catch (IllegalArgumentException ignored) { }
                    });
            logger.info("Loaded persisted metrics (joins={}, dispatches={}).",
                    totalQueueJoins.get(), totalDispatches.get());
        } catch (IOException | IllegalArgumentException e) {
            logger.warn("Could not load metrics: {}", e.getMessage());
        }
    }

    public void saveMetrics(Path file) {
        Properties props = new Properties();
        props.setProperty("totalQueueJoins", String.valueOf(totalQueueJoins.get()));
        props.setProperty("totalDispatches", String.valueOf(totalDispatches.get()));
        serverDispatchCounts.forEach((name, count) ->
                props.setProperty("server." + name, String.valueOf(count.get())));
        playerJoinCounts.forEach((uuid, count) ->
                props.setProperty("player.joins." + uuid, String.valueOf(count.get())));
        playerLastServer.forEach((uuid, server) ->
                props.setProperty("player.lastServer." + uuid, server));
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "PlantIt Queue metrics — do not edit manually");
            }
        } catch (IOException e) {
            logger.warn("Could not save metrics: {}", e.getMessage());
        }
    }

    // Plan metrics accessors
    public long getTotalQueueJoins() { return totalQueueJoins.get(); }
    public long getTotalDispatches() { return totalDispatches.get(); }
    public long getPlayerQueueJoins(UUID uuid) {
        AtomicLong c = playerJoinCounts.get(uuid);
        return c != null ? c.get() : 0L;
    }
    public String getPlayerLastServer(UUID uuid) { return playerLastServer.get(uuid); }
    public Map<String, Long> getServerDispatchCounts() {
        Map<String, Long> result = new LinkedHashMap<>();
        serverDispatchCounts.forEach((k, v) -> result.put(k, v.get()));
        return Collections.unmodifiableMap(result);
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private List<String> gameServerNames() {
        List<String> configured = config.getGameServers();
        if (!configured.isEmpty()) return configured;
        return proxy.getAllServers().stream().map(s -> s.getServerInfo().getName()).toList();
    }

    private Component buildServerStatus() {
        if (scaler != null && scaler.isAnyServerStarting()) {
            long eta = scaler.getStartupEtaSeconds();
            if (eta <= 0) {
                return Component.text("Server ready any moment...", NamedTextColor.YELLOW);
            }
            return Component.text("Server starting... ~" + eta + "s", NamedTextColor.YELLOW);
        }
        return Component.text("Waiting for a server...", NamedTextColor.GRAY);
    }

    private Component serverTag(String serverName) {
        if (serverName == null) {
            return Component.text("Waiting for server...", NamedTextColor.GRAY);
        }
        return Component.text("→ ", NamedTextColor.DARK_GRAY)
                .append(Component.text(serverName, NamedTextColor.GREEN));
    }

    private BossBar buildBossBar(int pos, int total) {
        return BossBar.bossBar(
                buildBossBarText(pos, total),
                barProgress(pos, total),
                barColor(pos),
                BossBar.Overlay.PROGRESS);
    }

    private Component buildBossBarText(int pos, int total) {
        return Component.text("Plant It  ", NamedTextColor.WHITE, TextDecoration.BOLD)
                .append(Component.text("◆  ", NamedTextColor.DARK_GRAY))
                .append(Component.text("Position ", NamedTextColor.GRAY))
                .append(Component.text("#" + pos, positionColor(pos)))
                .append(Component.text("  of  ", NamedTextColor.DARK_GRAY))
                .append(Component.text(total, NamedTextColor.GRAY));
    }

    private void updateTabList(Player player, int pos, int total, String serverName) {
        Component header = Component.text("\n")
                .append(Component.text("  Plant It  ", NamedTextColor.GREEN, TextDecoration.BOLD))
                .append(Component.text("\n"));

        Component footer = Component.text("\n")
                .append(Component.text("  Queue Position  ", NamedTextColor.GRAY))
                .append(Component.text("#" + pos, NamedTextColor.YELLOW))
                .append(Component.text("  of  ", NamedTextColor.DARK_GRAY))
                .append(Component.text(total, NamedTextColor.GRAY))
                .append(Component.text("\n  ", NamedTextColor.GRAY))
                .append(serverTag(serverName))
                .append(Component.text("\n  ", NamedTextColor.GRAY))
                .append(Component.text("/piq leave", NamedTextColor.GREEN))
                .append(Component.text(" to leave  \n", NamedTextColor.GRAY));

        player.sendPlayerListHeaderAndFooter(header, footer);
    }

    private void clearTabList(Player player) {
        player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
    }

    private static float barProgress(int pos, int total) {
        if (total <= 1) return 1.0f;
        return Math.max(0.05f, 1.0f - ((float) (pos - 1) / (total - 1)));
    }

    private static BossBar.Color barColor(int pos) {
        if (pos <= 3) return BossBar.Color.GREEN;
        if (pos <= 8) return BossBar.Color.YELLOW;
        return BossBar.Color.RED;
    }

    private static NamedTextColor positionColor(int pos) {
        if (pos <= 3) return NamedTextColor.GREEN;
        if (pos <= 8) return NamedTextColor.YELLOW;
        return NamedTextColor.RED;
    }

    private BossBar getBossBarSafe(UUID uuid) {
        return bossBars.getOrDefault(uuid,
                BossBar.bossBar(Component.empty(), 1f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS));
    }

    private boolean isOnEligibleServer(Player player) {
        return player.getCurrentServer()
                .map(s -> config.getQueueServers().contains(s.getServerInfo().getName()))
                .orElse(false);
    }
}
