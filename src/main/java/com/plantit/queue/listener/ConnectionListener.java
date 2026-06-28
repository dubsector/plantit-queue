package com.plantit.queue.listener;

import com.plantit.queue.QueueManager;
import com.plantit.queue.config.QueueConfig;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

public class ConnectionListener {

    private static final Component PREFIX = Component.text()
            .append(Component.text("[", NamedTextColor.DARK_GRAY))
            .append(Component.text("Plant It", NamedTextColor.GREEN, TextDecoration.BOLD))
            .append(Component.text("] ", NamedTextColor.DARK_GRAY))
            .build();

    private final QueueManager queueManager;
    private final QueueConfig config;

    public ConnectionListener(QueueManager queueManager, QueueConfig config) {
        this.queueManager = queueManager;
        this.config = config;
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        queueManager.dequeue(event.getPlayer().getUniqueId());
    }

    @Subscribe
    public void onServerSwitch(ServerConnectedEvent event) {
        String destination = event.getServer().getServerInfo().getName();
        boolean eligibleToQueue = config.getQueueServers().contains(destination);
        boolean isGameServer = config.isGameServer(destination);

        // Confirm successful match connection
        if (isGameServer) {
            event.getPlayer().sendMessage(PREFIX
                    .append(Component.text("Connected! Good luck.", NamedTextColor.GREEN)));
        }

        // Player returning from a game server to the lobby — put them back in queue automatically
        String previousServerName = event.getPreviousServer()
                .map(s -> s.getServerInfo().getName())
                .orElse(null);
        if (previousServerName != null
                && config.isExplicitGameServer(previousServerName)
                && eligibleToQueue) {
            queueManager.enqueue(event.getPlayer());
            return;
        }

        // Dequeue players who manually switch away from queue-eligible and game servers
        if (!eligibleToQueue && !isGameServer) {
            queueManager.dequeue(event.getPlayer().getUniqueId());
        }
    }
}
