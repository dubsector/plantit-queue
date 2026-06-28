package com.plantit.queue.listener;

import com.plantit.queue.QueueManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ServerConnection;

import java.nio.charset.StandardCharsets;

/**
 * Receives plugin messages from the game server over the {@code plantit:queue} channel.
 *
 * Protocol (UTF-8 string):
 *   SLOT_OPEN:<count>   — game server has <count> open slots, send next players
 */
public class MessagingListener {

    static final String CHANNEL = "plantit:queue";

    private final QueueManager queueManager;

    public MessagingListener(QueueManager queueManager) {
        this.queueManager = queueManager;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().getId().equals(CHANNEL)) return;
        // Only accept messages originating from a backend server, not a client.
        if (!(event.getSource() instanceof ServerConnection)) return;

        event.setResult(PluginMessageEvent.ForwardResult.handled());

        String message = new String(event.getData(), StandardCharsets.UTF_8).trim();

        if (message.startsWith("SLOT_OPEN:")) {
            try {
                int count = Integer.parseInt(message.substring("SLOT_OPEN:".length()).trim());
                queueManager.dispatchPlayers(count);
            } catch (NumberFormatException ignored) { }
        }
    }
}
