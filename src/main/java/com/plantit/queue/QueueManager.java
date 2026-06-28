package com.plantit.queue;

import com.plantit.queue.config.QueueConfig;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.LinkedList;
import java.util.Optional;
import java.util.UUID;

public class QueueManager {

    private final ProxyServer server;
    private final QueueConfig config;
    private final LinkedList<UUID> queue = new LinkedList<>();

    public QueueManager(ProxyServer server, QueueConfig config) {
        this.server = server;
        this.config = config;
    }

    /** Returns true if the player was added, false if already queued or on wrong server. */
    public boolean enqueue(Player player) {
        if (queue.contains(player.getUniqueId())) {
            player.sendMessage(Component.text("You are already in the queue.", NamedTextColor.RED));
            return false;
        }
        if (!isOnEligibleServer(player)) {
            player.sendMessage(Component.text("You must be on an eligible server to queue.", NamedTextColor.RED));
            return false;
        }
        queue.add(player.getUniqueId());
        int pos = queue.size();
        player.sendMessage(Component.text("You joined the queue! Position: ", NamedTextColor.GREEN)
                .append(Component.text(pos, NamedTextColor.YELLOW)));
        return true;
    }

    public void dequeue(UUID uuid) {
        boolean removed = queue.remove(uuid);
        if (removed) {
            server.getPlayer(uuid).ifPresent(p ->
                    p.sendMessage(Component.text("You left the queue.", NamedTextColor.GRAY)));
        }
    }

    /** Returns 1-based queue position, or -1 if not queued. */
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
     * Called when the game server signals it has open slots.
     * Dequeues and routes the next {@code count} players.
     */
    public void dispatchPlayers(int count) {
        Optional<RegisteredServer> gameServer = server.getServer(config.getGameServer());
        if (gameServer.isEmpty()) return;

        for (int i = 0; i < count && !queue.isEmpty(); i++) {
            UUID uuid = queue.poll();
            server.getPlayer(uuid).ifPresent(p -> {
                p.sendMessage(Component.text("A slot opened! Connecting you to the game...", NamedTextColor.GREEN));
                p.createConnectionRequest(gameServer.get()).fireAndForget();
            });
        }
    }

    /** Sends action bar position updates to every queued player. */
    public void broadcastPositions() {
        int total = queue.size();
        int pos = 1;
        for (UUID uuid : queue) {
            final int finalPos = pos++;
            server.getPlayer(uuid).ifPresent(p ->
                    p.sendActionBar(Component.text("Queue  ", NamedTextColor.GRAY)
                            .append(Component.text(finalPos, NamedTextColor.YELLOW))
                            .append(Component.text(" / ", NamedTextColor.GRAY))
                            .append(Component.text(total, NamedTextColor.YELLOW))));
        }
    }

    private boolean isOnEligibleServer(Player player) {
        return player.getCurrentServer()
                .map(s -> config.getQueueServers().contains(s.getServerInfo().getName()))
                .orElse(false);
    }
}
