package com.plantit.queue.listener;

import com.plantit.queue.QueueManager;
import com.plantit.queue.config.QueueConfig;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ServerConnection;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;

/**
 * Receives plugin messages from game servers over the {@code plantit:queue} channel.
 * Each game server signals independently — the queue dispatches to whichever server
 * sent the signal, supporting any number of concurrent game servers.
 *
 * Protocol (UTF-8 string):
 *   SLOT_OPEN:<count>   — this server has <count> open player slots
 */
public class MessagingListener {

    static final String CHANNEL = "plantit:queue";

    private final QueueManager queueManager;
    private final QueueConfig config;
    private final Logger logger;

    public MessagingListener(QueueManager queueManager, QueueConfig config, Logger logger) {
        this.queueManager = queueManager;
        this.config = config;
        this.logger = logger;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().getId().equals(CHANNEL)) return;
        if (!(event.getSource() instanceof ServerConnection source)) return;

        event.setResult(PluginMessageEvent.ForwardResult.handled());

        String serverName = source.getServerInfo().getName();

        if (!config.isGameServer(serverName)) {
            logger.warn("Server '{}' sent a queue message but is not in game-servers list — ignoring.", serverName);
            return;
        }

        String message = new String(event.getData(), StandardCharsets.UTF_8).trim();

        if (message.startsWith("SLOT_OPEN:")) {
            try {
                int count = Integer.parseInt(message.substring("SLOT_OPEN:".length()).trim());
                if (count > 0) {
                    logger.info("Server '{}' signalled {} open slot(s) — dispatching from queue.", serverName, count);
                    queueManager.dispatchPlayers(count, source.getServer());
                }
            } catch (NumberFormatException ignored) {
                logger.warn("Malformed SLOT_OPEN message from '{}': {}", serverName, message);
            }
        }
    }
}
