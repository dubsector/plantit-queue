package com.plantit.queue.vote;

import com.plantit.queue.PlantItQueue;
import com.plantit.queue.QueueManager;
import com.plantit.queue.listener.MessagingListener;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class VoteSession {

    private static final Component PREFIX = Component.text()
            .append(Component.text("[", NamedTextColor.DARK_GRAY))
            .append(Component.text("Plant It", NamedTextColor.GREEN, TextDecoration.BOLD))
            .append(Component.text("] ", NamedTextColor.DARK_GRAY))
            .build();

    private static final Sound SOUND_VOTE = Sound.sound(
            Key.key("minecraft:block.note_block.pling"), Sound.Source.MASTER, 0.6f, 1.5f);
    private static final Sound SOUND_START = Sound.sound(
            Key.key("minecraft:entity.player.levelup"), Sound.Source.MASTER, 1f, 1f);

    private final List<UUID> committed;
    private final com.velocitypowered.api.proxy.server.RegisteredServer server;
    private final List<String> maps;
    private final String defaultMap;
    private final ProxyServer proxy;
    private final PlantItQueue plugin;
    private final QueueManager queueManager;
    private final Logger logger;

    private final Map<String, Integer> tally = new LinkedHashMap<>();
    private final Map<UUID, String> playerVotes = new ConcurrentHashMap<>();

    private int secondsLeft;
    private ScheduledTask task;

    public VoteSession(List<UUID> committed,
                       com.velocitypowered.api.proxy.server.RegisteredServer server,
                       List<String> maps, String defaultMap, int durationSeconds,
                       ProxyServer proxy, PlantItQueue plugin,
                       QueueManager queueManager, Logger logger) {
        this.committed    = committed;
        this.server       = server;
        this.maps         = maps;
        this.defaultMap   = defaultMap;
        this.secondsLeft  = durationSeconds;
        this.proxy        = proxy;
        this.plugin       = plugin;
        this.queueManager = queueManager;
        this.logger       = logger;
        maps.forEach(m -> tally.put(m, 0));
    }

    public void start() {
        committed.forEach(uuid -> proxy.getPlayer(uuid).ifPresent(this::sendVotePromptTo));
        task = proxy.getScheduler()
                .buildTask(plugin, this::tick)
                .repeat(1, TimeUnit.SECONDS)
                .schedule();
    }

    public boolean vote(UUID uuid, String map) {
        if (!committed.contains(uuid)) return false;
        String normalised = maps.stream().filter(m -> m.equalsIgnoreCase(map)).findFirst().orElse(null);
        if (normalised == null) return false;

        String prev = playerVotes.put(uuid, normalised);
        if (prev != null) tally.merge(prev, -1, Integer::sum);
        tally.merge(normalised, 1, Integer::sum);

        proxy.getPlayer(uuid).ifPresent(p -> {
            sendVotePromptTo(p);
            p.playSound(SOUND_VOTE);
        });
        return true;
    }

    public boolean hasPlayer(UUID uuid) {
        return committed.contains(uuid);
    }

    public void removePlayer(UUID uuid) {
        committed.remove(uuid);
        String prev = playerVotes.remove(uuid);
        if (prev != null) tally.merge(prev, -1, Integer::sum);
        if (committed.isEmpty()) cancel();
    }

    public void cancel() {
        if (task != null) { task.cancel(); task = null; }
    }

    // -------------------------------------------------------------------------

    private void tick() {
        secondsLeft--;
        broadcastActionBar();
        if (secondsLeft <= 0) {
            task.cancel();
            finish();
        }
    }

    private void sendVotePromptTo(Player p) {
        String voted = playerVotes.get(p.getUniqueId());
        p.sendMessage(Component.empty());
        p.sendMessage(PREFIX.append(
                Component.text("Vote for the map — connecting in ", NamedTextColor.WHITE, TextDecoration.BOLD)
                        .append(Component.text(secondsLeft + "s", NamedTextColor.YELLOW))));

        Component line = Component.text("  ");
        for (String map : maps) {
            boolean selected = map.equals(voted);
            NamedTextColor color = selected ? NamedTextColor.GREEN : NamedTextColor.GRAY;
            TextDecoration deco  = selected ? TextDecoration.BOLD  : TextDecoration.ITALIC;
            int count = tally.getOrDefault(map, 0);
            line = line.append(
                    Component.text("[" + map + " " + count + "]", color, deco)
                            .clickEvent(ClickEvent.runCommand("/piq vote " + map))
                            .hoverEvent(HoverEvent.showText(Component.text("Click to vote for " + map)))
            ).append(Component.text("  "));
        }
        p.sendMessage(line);
        p.sendMessage(Component.empty());
    }

    private void broadcastActionBar() {
        Component bar = Component.text("MAP VOTE  ", NamedTextColor.GOLD, TextDecoration.BOLD);
        for (Map.Entry<String, Integer> entry : tally.entrySet()) {
            bar = bar.append(Component.text(entry.getKey() + " ", NamedTextColor.WHITE))
                     .append(Component.text(entry.getValue() + "  ", NamedTextColor.YELLOW));
        }
        bar = bar.append(Component.text("| ", NamedTextColor.DARK_GRAY))
                 .append(Component.text(secondsLeft + "s", secondsLeft <= 5 ? NamedTextColor.RED : NamedTextColor.GREEN));

        Component finalBar = bar;
        committed.forEach(uuid -> proxy.getPlayer(uuid).ifPresent(p -> p.sendActionBar(finalBar)));
    }

    private void finish() {
        String winner = pickWinner();
        logger.info("Map vote ended for '{}' — winner: {} (tally: {})",
                server.getServerInfo().getName(), winner, tally);

        // Send MAP_SELECTED to backend through any player already connected there
        byte[] msg = ("MAP_SELECTED:" + winner).getBytes(StandardCharsets.UTF_8);
        MinecraftChannelIdentifier channelId = MinecraftChannelIdentifier.from(MessagingListener.CHANNEL);
        boolean sent = server.getPlayersConnected().stream()
                .filter(p -> p.getCurrentServer().isPresent())
                .anyMatch(p -> p.getCurrentServer().get().sendPluginMessage(channelId, msg));
        if (!sent) {
            logger.warn("No carrier player on '{}' to send MAP_SELECTED — map may default on server.",
                    server.getServerInfo().getName());
        }

        // Show winner to committed players
        Component title = Component.text(winner, NamedTextColor.GREEN, TextDecoration.BOLD);
        Component sub   = Component.text("Get ready to play!", NamedTextColor.GRAY);
        Title.Times times = Title.Times.times(
                Duration.ofMillis(200), Duration.ofMillis(2000), Duration.ofMillis(500));
        committed.forEach(uuid -> proxy.getPlayer(uuid).ifPresent(p -> {
            p.showTitle(Title.title(title, sub, times));
            p.playSound(SOUND_START);
        }));

        // Short delay to let MAP_SELECTED reach the backend before players arrive
        proxy.getScheduler()
                .buildTask(plugin, () -> queueManager.dispatchCommitted(committed, server))
                .delay(500, TimeUnit.MILLISECONDS)
                .schedule();
    }

    private String pickWinner() {
        if (playerVotes.isEmpty()) return defaultMap.isEmpty() ? maps.get(0) : defaultMap;
        return tally.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(defaultMap.isEmpty() ? maps.get(0) : defaultMap);
    }
}
