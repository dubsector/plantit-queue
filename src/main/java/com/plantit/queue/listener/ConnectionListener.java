package com.plantit.queue.listener;

import com.plantit.queue.QueueManager;
import com.plantit.queue.config.QueueConfig;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;

public class ConnectionListener {

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

    /**
     * Dequeue players who manually switch to a non-queue-eligible server.
     * Players dispatched to a game server are already removed before the connection fires,
     * so this only catches manual switches (e.g. /server minigames while queued).
     */
    @Subscribe
    public void onServerSwitch(ServerConnectedEvent event) {
        String destination = event.getServer().getServerInfo().getName();
        boolean eligibleToQueue = config.getQueueServers().contains(destination);
        boolean isGameServer = config.isGameServer(destination);

        if (!eligibleToQueue && !isGameServer) {
            queueManager.dequeue(event.getPlayer().getUniqueId());
        }
    }
}
