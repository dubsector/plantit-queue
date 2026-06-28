package com.plantit.queue.command;

import com.plantit.queue.QueueManager;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public class QueueCommand implements SimpleCommand {

    private final QueueManager queueManager;

    public QueueCommand(QueueManager queueManager) {
        this.queueManager = queueManager;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return;
        }

        String[] args = invocation.arguments();

        if (args.length == 0 || args[0].equalsIgnoreCase("join")) {
            queueManager.enqueue(player);
            return;
        }

        if (args[0].equalsIgnoreCase("leave")) {
            queueManager.dequeue(player.getUniqueId());
            return;
        }

        if (args[0].equalsIgnoreCase("pos") || args[0].equalsIgnoreCase("position")) {
            int pos = queueManager.getPosition(player.getUniqueId());
            if (pos == -1) {
                player.sendMessage(Component.text("You are not in the queue.", NamedTextColor.GRAY));
            } else {
                player.sendMessage(Component.text("Queue position: ", NamedTextColor.GRAY)
                        .append(Component.text(pos + " / " + queueManager.size(), NamedTextColor.YELLOW)));
            }
            return;
        }

        player.sendMessage(Component.text("Usage: /queue [join|leave|pos]", NamedTextColor.GRAY));
    }
}
