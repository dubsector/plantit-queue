package com.plantit.queue.command;

import com.plantit.queue.QueueManager;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.List;

public class QueueCommand implements SimpleCommand {

    private static final Component PREFIX = Component.text()
            .append(Component.text("[", NamedTextColor.DARK_GRAY))
            .append(Component.text("Plant It", NamedTextColor.GREEN, TextDecoration.BOLD))
            .append(Component.text("] ", NamedTextColor.DARK_GRAY))
            .build();

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
        String sub = args.length > 0 ? args[0].toLowerCase() : "join";

        switch (sub) {
            case "join" -> queueManager.enqueue(player);
            case "leave" -> queueManager.dequeue(player.getUniqueId());
            case "pos", "position" -> {
                int pos = queueManager.getPosition(player.getUniqueId());
                if (pos == -1) {
                    player.sendMessage(PREFIX.append(
                            Component.text("You are not in the queue. Use ", NamedTextColor.GRAY))
                            .append(Component.text("/queue join", NamedTextColor.GREEN))
                            .append(Component.text(" to join.", NamedTextColor.GRAY)));
                } else {
                    player.sendMessage(PREFIX.append(
                            Component.text("Your position: ", NamedTextColor.GRAY))
                            .append(Component.text("#" + pos, NamedTextColor.YELLOW))
                            .append(Component.text("  of  ", NamedTextColor.DARK_GRAY))
                            .append(Component.text(queueManager.size() + " in queue", NamedTextColor.GRAY)));
                }
            }
            default -> sendHelp(player);
        }
    }

    private void sendHelp(Player player) {
        player.sendMessage(Component.empty());
        player.sendMessage(PREFIX.append(
                Component.text("Queue Commands", NamedTextColor.WHITE, TextDecoration.BOLD)));
        player.sendMessage(Component.text("  /plantit join    ", NamedTextColor.GREEN)
                .append(Component.text("— Join the queue", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("  /plantit leave   ", NamedTextColor.GREEN)
                .append(Component.text("— Leave the queue", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("  /plantit pos     ", NamedTextColor.GREEN)
                .append(Component.text("— Check your position", NamedTextColor.GRAY)));
        player.sendMessage(Component.empty());
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (invocation.arguments().length <= 1) return List.of("join", "leave", "pos");
        return List.of();
    }
}
