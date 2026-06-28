package com.plantit.queue;

import com.plantit.queue.config.QueueConfig;
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

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.UUID;

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

    private final ProxyServer server;
    private QueueConfig config;
    private final LinkedList<UUID> queue = new LinkedList<>();
    private final Map<UUID, BossBar> bossBars = new HashMap<>();

    // Track previous positions to detect moves — only ping on change
    private final Map<UUID, Integer> lastKnownPosition = new HashMap<>();

    public QueueManager(ProxyServer server, QueueConfig config) {
        this.server = server;
        this.config = config;
    }

    /** Hot-swaps the config after a /queue reload. */
    public void updateConfig(QueueConfig config) {
        this.config = config;
    }

    /** Returns true if the player was added. */
    public boolean enqueue(Player player) {
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
        int pos = queue.size();

        BossBar bar = buildBossBar(pos, pos);
        bossBars.put(player.getUniqueId(), bar);
        player.showBossBar(bar);

        player.showTitle(Title.title(
                Component.text("Plant It", NamedTextColor.GREEN, TextDecoration.BOLD),
                Component.text("Joining queue — position #" + pos, NamedTextColor.GRAY),
                Title.Times.times(
                        Duration.ofMillis(200),
                        Duration.ofMillis(2500),
                        Duration.ofMillis(500))));

        player.sendMessage(Component.empty());
        player.sendMessage(PREFIX.append(
                Component.text("You joined the queue for ", NamedTextColor.GRAY))
                .append(Component.text("Plant It", NamedTextColor.GREEN, TextDecoration.BOLD))
                .append(Component.text("!", NamedTextColor.GRAY)));
        player.sendMessage(PREFIX.append(
                Component.text("Position: ", NamedTextColor.GRAY))
                .append(Component.text("#" + pos, NamedTextColor.YELLOW))
                .append(Component.text("  |  Use ", NamedTextColor.DARK_GRAY))
                .append(Component.text("/piq leave", NamedTextColor.GREEN))
                .append(Component.text(" to leave.", NamedTextColor.DARK_GRAY)));
        player.sendMessage(Component.empty());

        player.playSound(SOUND_JOIN);
        updateTabList(player, pos, queue.size());
        lastKnownPosition.put(player.getUniqueId(), pos);

        if (config.isDebugMode()) {
            debugDispatch(player);
        }

        return true;
    }

    /**
     * Debug-mode fast path: immediately dispatch this player to the first reachable
     * game server without waiting for a SLOT_OPEN signal.
     */
    private void debugDispatch(Player player) {
        for (String name : config.getGameServers().isEmpty()
                ? server.getAllServers().stream().map(s -> s.getServerInfo().getName()).toList()
                : config.getGameServers()) {

            var registered = server.getServer(name);
            if (registered.isPresent()) {
                player.sendMessage(PREFIX
                        .append(Component.text("[DEBUG] Bypassing queue — dispatching to ", NamedTextColor.GOLD))
                        .append(Component.text(name, NamedTextColor.YELLOW)));
                dispatchPlayers(1, registered.get());
                return;
            }
        }
        player.sendMessage(PREFIX.append(
                Component.text("[DEBUG] No reachable game server found — staying in queue.", NamedTextColor.RED)));
    }

    public void dequeue(UUID uuid) {
        boolean removed = queue.remove(uuid);
        bossBars.remove(uuid);
        lastKnownPosition.remove(uuid);

        if (removed) {
            server.getPlayer(uuid).ifPresent(p -> {
                p.hideBossBar(getBossBarSafe(uuid));
                p.sendMessage(PREFIX.append(
                        Component.text("You left the queue.", NamedTextColor.GRAY)));
                p.playSound(SOUND_LEAVE);
                clearTabList(p);
            });
        }
    }

    /** Returns 1-based position, or -1 if not queued. */
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

    /**
     * Dispatches the next {@code count} players in the queue to {@code destination}.
     * Called by {@link com.plantit.queue.listener.MessagingListener} with the exact
     * server that sent the SLOT_OPEN signal, so each game server fills independently.
     */
    public void dispatchPlayers(int count, RegisteredServer destination) {
        for (int i = 0; i < count && !queue.isEmpty(); i++) {
            UUID uuid = queue.poll();
            BossBar bar = bossBars.remove(uuid);
            lastKnownPosition.remove(uuid);

            server.getPlayer(uuid).ifPresent(p -> {
                if (bar != null) p.hideBossBar(bar);
                clearTabList(p);

                p.showTitle(Title.title(
                        Component.text("Connecting...", NamedTextColor.GREEN, TextDecoration.BOLD),
                        Component.text("Get ready to play!", NamedTextColor.GRAY),
                        Title.Times.times(
                                Duration.ofMillis(100),
                                Duration.ofMillis(3000),
                                Duration.ofMillis(500))));

                p.sendMessage(Component.empty());
                p.sendMessage(PREFIX.append(
                        Component.text("Found a match! Connecting you to the server...", NamedTextColor.GREEN)));
                p.sendMessage(Component.empty());
                p.playSound(SOUND_CONNECT);

                p.createConnectionRequest(destination).fireAndForget();
            });
        }
    }

    /** Called on the broadcast interval — updates boss bars, tab list, pings on position changes. */
    public void broadcastPositions() {
        int total = queue.size();
        int pos = 1;

        for (UUID uuid : new LinkedList<>(queue)) {
            final int finalPos = pos++;
            server.getPlayer(uuid).ifPresent(p -> {
                // Boss bar
                BossBar bar = bossBars.get(uuid);
                if (bar != null) {
                    bar.name(buildBossBarText(finalPos, total));
                    bar.progress(barProgress(finalPos, total));
                    bar.color(barColor(finalPos));
                }

                // Action bar — secondary info line
                p.sendActionBar(Component.text("Position ", NamedTextColor.GRAY)
                        .append(Component.text("#" + finalPos, NamedTextColor.YELLOW))
                        .append(Component.text("  |  ", NamedTextColor.DARK_GRAY))
                        .append(Component.text(total + " in queue", NamedTextColor.GRAY))
                        .append(Component.text("  |  ", NamedTextColor.DARK_GRAY))
                        .append(Component.text("/piq leave", NamedTextColor.GREEN)));

                // Tab list
                updateTabList(p, finalPos, total);

                // Ping when position improves
                Integer prev = lastKnownPosition.get(uuid);
                if (prev != null && finalPos < prev) {
                    p.playSound(SOUND_MOVE_UP);
                }
                lastKnownPosition.put(uuid, finalPos);
            });
        }
    }

    // -------------------------------------------------------------------------

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

    private void updateTabList(Player player, int pos, int total) {
        Component header = Component.text("\n")
                .append(Component.text("  Plant It  ", NamedTextColor.GREEN, TextDecoration.BOLD))
                .append(Component.text("\n"));

        Component footer = Component.text("\n")
                .append(Component.text("  Queue Position  ", NamedTextColor.GRAY))
                .append(Component.text("#" + pos, NamedTextColor.YELLOW))
                .append(Component.text("  of  ", NamedTextColor.DARK_GRAY))
                .append(Component.text(total, NamedTextColor.GRAY))
                .append(Component.text("\n  ", NamedTextColor.GRAY))
                .append(Component.text("/piq leave", NamedTextColor.GREEN))
                .append(Component.text(" to leave  \n", NamedTextColor.GRAY));

        player.getTabList().setHeaderAndFooter(header, footer);
    }

    private void clearTabList(Player player) {
        player.getTabList().setHeaderAndFooter(Component.empty(), Component.empty());
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
