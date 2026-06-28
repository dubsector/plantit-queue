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

    /** Remove players from the queue if they switch to a non-eligible, non-game server. */
    @Subscribe
    public void onServerSwitch(ServerConnectedEvent event) {
        String destination = event.getServer().getServerInfo().getName();
        boolean eligible = config.getQueueServers().contains(destination)
                || destination.equals(config.getGameServer());
        if (!eligible) {
            queueManager.dequeue(event.getPlayer().getUniqueId());
        }
    }
}
