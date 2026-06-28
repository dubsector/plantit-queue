package com.plantit.queue.command;

import com.plantit.queue.PlantItQueue;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.List;

/**
 * Admin command: /piq
 * Requires the plantit.admin permission.
 *
 * Usage:
 *   /piq reload   — reload config from disk
 */
public class PiqCommand implements SimpleCommand {

    private static final Component PREFIX = Component.text()
            .append(Component.text("[", NamedTextColor.DARK_GRAY))
            .append(Component.text("Plant It", NamedTextColor.GREEN, TextDecoration.BOLD))
            .append(Component.text("] ", NamedTextColor.DARK_GRAY))
            .build();

    private final PlantItQueue plugin;

    public PiqCommand(PlantItQueue plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments();
        String sub = args.length > 0 ? args[0].toLowerCase() : "";

        switch (sub) {
            case "reload" -> {
                plugin.reload();
                invocation.source().sendMessage(PREFIX.append(
                        Component.text("Config reloaded.", NamedTextColor.GREEN)));
            }
            default -> sendHelp(invocation);
        }
    }

    private void sendHelp(Invocation invocation) {
        invocation.source().sendMessage(Component.empty());
        invocation.source().sendMessage(PREFIX.append(
                Component.text("Admin Commands", NamedTextColor.WHITE, TextDecoration.BOLD)));
        invocation.source().sendMessage(Component.text("  /piq reload  ", NamedTextColor.GOLD)
                .append(Component.text("— Reload config from disk", NamedTextColor.GRAY)));
        invocation.source().sendMessage(Component.empty());
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("plantit.admin");
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (invocation.arguments().length <= 1) return List.of("reload");
        return List.of();
    }
}
