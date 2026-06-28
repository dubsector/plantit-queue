package com.plantit.queue.command;

import com.plantit.queue.PlantItQueue;
import com.plantit.queue.QueueManager;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.List;

/**
 * /piq — all Plant It Queue commands in one place.
 *
 * Player:
 *   /piq join    — join the queue
 *   /piq leave   — leave the queue
 *   /piq pos     — check position
 *
 * Admin (requires plantit.admin):
 *   /piq reload  — reload config from disk
 */
public class PiqCommand implements SimpleCommand {

    private static final Component PREFIX = Component.text()
            .append(Component.text("[", NamedTextColor.DARK_GRAY))
            .append(Component.text("Plant It", NamedTextColor.GREEN, TextDecoration.BOLD))
            .append(Component.text("] ", NamedTextColor.DARK_GRAY))
            .build();

    private final QueueManager queueManager;
    private final PlantItQueue plugin;

    public PiqCommand(QueueManager queueManager, PlantItQueue plugin) {
        this.queueManager = queueManager;
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments();
        String sub = args.length > 0 ? args[0].toLowerCase() : "";

        switch (sub) {
            case "join" -> {
                requirePlayer(invocation, player -> queueManager.enqueue(player));
            }
            case "leave" -> {
                requirePlayer(invocation, player -> queueManager.dequeue(player.getUniqueId()));
            }
            case "pos", "position" -> {
                requirePlayer(invocation, player -> {
                    int pos = queueManager.getPosition(player.getUniqueId());
                    if (pos == -1) {
                        player.sendMessage(PREFIX.append(
                                Component.text("You are not in the queue. Use ", NamedTextColor.GRAY))
                                .append(Component.text("/piq join", NamedTextColor.GREEN))
                                .append(Component.text(" to join.", NamedTextColor.GRAY)));
                    } else {
                        player.sendMessage(PREFIX.append(
                                Component.text("Position: ", NamedTextColor.GRAY))
                                .append(Component.text("#" + pos, NamedTextColor.YELLOW))
                                .append(Component.text("  of  ", NamedTextColor.DARK_GRAY))
                                .append(Component.text(queueManager.size() + " in queue", NamedTextColor.GRAY)));
                    }
                });
            }
            case "stop" -> {
                if (!invocation.source().hasPermission("plantit.admin")) {
                    invocation.source().sendMessage(PREFIX.append(
                            Component.text("You don't have permission to do that.", NamedTextColor.RED)));
                    return;
                }
                if (queueManager.isStopped()) {
                    invocation.source().sendMessage(PREFIX.append(
                            Component.text("The queue is already stopped.", NamedTextColor.RED)));
                    return;
                }
                queueManager.stop();
                invocation.source().sendMessage(PREFIX.append(
                        Component.text("Queue stopped. Players will see an unavailability message.", NamedTextColor.GOLD)));
            }

            case "start" -> {
                if (!invocation.source().hasPermission("plantit.admin")) {
                    invocation.source().sendMessage(PREFIX.append(
                            Component.text("You don't have permission to do that.", NamedTextColor.RED)));
                    return;
                }
                if (!queueManager.isStopped()) {
                    invocation.source().sendMessage(PREFIX.append(
                            Component.text("The queue is already running.", NamedTextColor.RED)));
                    return;
                }
                queueManager.start();
                invocation.source().sendMessage(PREFIX.append(
                        Component.text("Queue started. Players can now join.", NamedTextColor.GREEN)));
            }

            case "reload" -> {
                if (!invocation.source().hasPermission("plantit.admin")) {
                    invocation.source().sendMessage(PREFIX.append(
                            Component.text("You don't have permission to do that.", NamedTextColor.RED)));
                    return;
                }
                plugin.reload();
                invocation.source().sendMessage(PREFIX.append(
                        Component.text("Config reloaded.", NamedTextColor.GREEN)));
            }
            case "front" -> {
                if (!invocation.source().hasPermission("plantit.admin")) {
                    invocation.source().sendMessage(PREFIX.append(
                            Component.text("You don't have permission to do that.", NamedTextColor.RED)));
                    return;
                }
                if (args.length < 2) {
                    invocation.source().sendMessage(PREFIX.append(
                            Component.text("Usage: /piq front <player>", NamedTextColor.RED)));
                    return;
                }
                var targetPlayer = queueManager.findPlayer(args[1]);
                if (targetPlayer == null) {
                    invocation.source().sendMessage(PREFIX.append(
                            Component.text("Player '" + args[1] + "' is not online.", NamedTextColor.RED)));
                    return;
                }
                queueManager.enqueueFirst(targetPlayer);
                invocation.source().sendMessage(PREFIX
                        .append(Component.text(targetPlayer.getUsername(), NamedTextColor.YELLOW))
                        .append(Component.text(" moved to the front of the queue.", NamedTextColor.GREEN)));
            }
            case "open" -> {
                if (!invocation.source().hasPermission("plantit.admin")) {
                    invocation.source().sendMessage(PREFIX.append(
                            Component.text("You don't have permission to do that.", NamedTextColor.RED)));
                    return;
                }
                if (!queueManager.isDebugMode()) {
                    invocation.source().sendMessage(PREFIX.append(
                            Component.text("This command is only available in debug mode.", NamedTextColor.RED)));
                    return;
                }
                if (args.length < 3) {
                    invocation.source().sendMessage(PREFIX.append(
                            Component.text("Usage: /piq open <server> <slots>", NamedTextColor.RED)));
                    return;
                }
                String serverName = args[1];
                int slots;
                try {
                    slots = Integer.parseInt(args[2]);
                    if (slots < 1) throw new NumberFormatException();
                } catch (NumberFormatException e) {
                    invocation.source().sendMessage(PREFIX.append(
                            Component.text("Slots must be a positive number.", NamedTextColor.RED)));
                    return;
                }
                String error = queueManager.debugOpen(serverName, slots);
                if (error != null) {
                    invocation.source().sendMessage(PREFIX.append(
                            Component.text(error, NamedTextColor.RED)));
                } else {
                    invocation.source().sendMessage(PREFIX.append(
                            Component.text("[DEBUG] Simulated SLOT_OPEN:" + slots + " for '" + serverName + "'.", NamedTextColor.GOLD)));
                }
            }
            default -> sendHelp(invocation);
        }
    }

    private void requirePlayer(Invocation invocation, java.util.function.Consumer<Player> action) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(
                    Component.text("Only players can use this command.", NamedTextColor.RED));
            return;
        }
        action.accept(player);
    }

    private void sendHelp(Invocation invocation) {
        boolean isAdmin = invocation.source().hasPermission("plantit.admin");
        invocation.source().sendMessage(Component.empty());
        invocation.source().sendMessage(PREFIX.append(
                Component.text("Commands", NamedTextColor.WHITE, TextDecoration.BOLD)));
        invocation.source().sendMessage(Component.text("  /piq join    ", NamedTextColor.GREEN)
                .append(Component.text("— Join the queue", NamedTextColor.GRAY)));
        invocation.source().sendMessage(Component.text("  /piq leave   ", NamedTextColor.GREEN)
                .append(Component.text("— Leave the queue", NamedTextColor.GRAY)));
        invocation.source().sendMessage(Component.text("  /piq pos     ", NamedTextColor.GREEN)
                .append(Component.text("— Check your position", NamedTextColor.GRAY)));
        if (isAdmin) {
            invocation.source().sendMessage(Component.text("  /piq stop           ", NamedTextColor.GOLD)
                    .append(Component.text("— Disable the queue (clears all waiting players)", NamedTextColor.GRAY)));
            invocation.source().sendMessage(Component.text("  /piq start          ", NamedTextColor.GOLD)
                    .append(Component.text("— Re-enable the queue", NamedTextColor.GRAY)));
            invocation.source().sendMessage(Component.text("  /piq reload         ", NamedTextColor.GOLD)
                    .append(Component.text("— Reload config from disk", NamedTextColor.GRAY)));
            invocation.source().sendMessage(Component.text("  /piq front <player> ", NamedTextColor.GOLD)
                    .append(Component.text("— Move a player to position #1", NamedTextColor.GRAY)));
            if (queueManager.isDebugMode()) {
                invocation.source().sendMessage(Component.text("  /piq open <server> <slots>  ", NamedTextColor.GOLD)
                        .append(Component.text("— [DEBUG] Simulate SLOT_OPEN from a server", NamedTextColor.GRAY)));
            }
        }
        invocation.source().sendMessage(Component.empty());
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (invocation.arguments().length <= 1) {
            if (invocation.source().hasPermission("plantit.admin")) {
                if (queueManager.isDebugMode()) {
                    return List.of("join", "leave", "pos", "stop", "start", "reload", "front", "open");
                }
                return List.of("join", "leave", "pos", "stop", "start", "reload", "front");
            }
            return List.of("join", "leave", "pos");
        }
        return List.of();
    }
}
