package com.plantit.queue;

import com.plantit.queue.config.QueueConfig;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QueueManagerTest {

    @Mock ProxyServer proxy;
    @Mock Logger logger;
    @Mock Player playerA;
    @Mock Player playerB;
    @Mock Player playerC;

    private QueueConfig config;
    private QueueManager manager;

    @BeforeEach
    void setUp() {
        config = new QueueConfig();
        manager = new QueueManager(proxy, config, logger);

        // Stub players to behave like they're on an eligible server
        stubPlayer(playerA, UUID.randomUUID(), "PlayerA");
        stubPlayer(playerB, UUID.randomUUID(), "PlayerB");
        stubPlayer(playerC, UUID.randomUUID(), "PlayerC");
    }

    private void stubPlayer(Player player, UUID uuid, String name) {
        lenient().when(player.getUniqueId()).thenReturn(uuid);
        lenient().when(player.getUsername()).thenReturn(name);
        lenient().doNothing().when(player).sendMessage(any(Component.class));
        lenient().doNothing().when(player).showBossBar(any(BossBar.class));
        lenient().doNothing().when(player).hideBossBar(any(BossBar.class));
        lenient().doNothing().when(player).showTitle(any(Title.class));
        lenient().doNothing().when(player).sendActionBar(any(Component.class));
        lenient().doNothing().when(player).playSound(any());
        lenient().doNothing().when(player).sendPlayerListHeaderAndFooter(any(), any());
        // Eligible server stub — default config has "First Capital" as queue server
        var serverInfo = new ServerInfo("First Capital", new InetSocketAddress("localhost", 25565));
        var connectedServer = mock(com.velocitypowered.api.proxy.ServerConnection.class);
        var registeredServer = mock(RegisteredServer.class);
        lenient().when(registeredServer.getServerInfo()).thenReturn(serverInfo);
        lenient().when(connectedServer.getServerInfo()).thenReturn(serverInfo);
        lenient().when(connectedServer.getServer()).thenReturn(registeredServer);
        lenient().when(player.getCurrentServer()).thenReturn(Optional.of(connectedServer));
        lenient().when(proxy.getPlayer(uuid)).thenReturn(Optional.of(player));
    }

    // -------------------------------------------------------------------------
    // Enqueue
    // -------------------------------------------------------------------------

    @Test
    void enqueue_addsPlayerToQueue() {
        assertTrue(manager.enqueue(playerA));
        assertEquals(1, manager.size());
        assertEquals(1, manager.getPosition(playerA.getUniqueId()));
    }

    @Test
    void enqueue_secondPlayerIsPositionTwo() {
        manager.enqueue(playerA);
        manager.enqueue(playerB);
        assertEquals(2, manager.getPosition(playerB.getUniqueId()));
    }

    @Test
    void enqueue_rejectsAlreadyQueuedPlayer() {
        manager.enqueue(playerA);
        assertFalse(manager.enqueue(playerA));
        assertEquals(1, manager.size());
    }

    @Test
    void enqueue_rejectsWhenStopped() {
        manager.stop();
        assertFalse(manager.enqueue(playerA));
        assertEquals(0, manager.size());
    }

    @Test
    void enqueue_incrementsTotalJoinsMetric() {
        manager.enqueue(playerA);
        manager.enqueue(playerB);
        assertEquals(2, manager.getTotalQueueJoins());
    }

    // -------------------------------------------------------------------------
    // Dequeue
    // -------------------------------------------------------------------------

    @Test
    void dequeue_removesPlayer() {
        manager.enqueue(playerA);
        manager.enqueue(playerB);
        manager.dequeue(playerA.getUniqueId());
        assertEquals(1, manager.size());
        assertEquals(-1, manager.getPosition(playerA.getUniqueId()));
    }

    @Test
    void dequeue_shiftedPositions() {
        manager.enqueue(playerA);
        manager.enqueue(playerB);
        manager.enqueue(playerC);
        manager.dequeue(playerA.getUniqueId());
        assertEquals(1, manager.getPosition(playerB.getUniqueId()));
        assertEquals(2, manager.getPosition(playerC.getUniqueId()));
    }

    @Test
    void dequeue_nonQueuedPlayerIsNoOp() {
        manager.enqueue(playerA);
        manager.dequeue(playerB.getUniqueId()); // not in queue
        assertEquals(1, manager.size());
    }

    // -------------------------------------------------------------------------
    // EnqueueFirst
    // -------------------------------------------------------------------------

    @Test
    void enqueueFirst_newPlayerGoesToFront() {
        manager.enqueue(playerA);
        manager.enqueue(playerB);
        manager.enqueueFirst(playerC);
        assertEquals(1, manager.getPosition(playerC.getUniqueId()));
        assertEquals(2, manager.getPosition(playerA.getUniqueId()));
        assertEquals(3, manager.getPosition(playerB.getUniqueId()));
    }

    @Test
    void enqueueFirst_existingPlayerMovesToFront() {
        manager.enqueue(playerA);
        manager.enqueue(playerB);
        manager.enqueue(playerC);
        manager.enqueueFirst(playerC);
        assertEquals(1, manager.getPosition(playerC.getUniqueId()));
        assertEquals(3, manager.size()); // no duplicate
    }

    // -------------------------------------------------------------------------
    // Stop / start
    // -------------------------------------------------------------------------

    @Test
    void stop_preventsNewJoins() {
        manager.stop();
        assertTrue(manager.isStopped());
        assertFalse(manager.enqueue(playerA));
    }

    @Test
    void start_allowsJoinsAfterStop() {
        manager.stop();
        manager.start();
        assertFalse(manager.isStopped());
        assertTrue(manager.enqueue(playerA));
    }

    // -------------------------------------------------------------------------
    // Stats
    // -------------------------------------------------------------------------

    @Test
    void getStats_reflectsCurrentState() {
        manager.enqueue(playerA);
        manager.enqueue(playerB);
        var stats = manager.getStats();
        assertEquals(2, stats.currentSize());
        assertEquals(2, stats.totalJoins());
        assertEquals(0, stats.totalDispatches());
    }

    // -------------------------------------------------------------------------
    // debugOpen
    // -------------------------------------------------------------------------

    @Test
    void debugOpen_returnsErrorWhenQueueEmpty() {
        var server = mock(RegisteredServer.class);
        when(proxy.getServer("plantit-1")).thenReturn(Optional.of(server));
        String error = manager.debugOpen("plantit-1", 1);
        assertNotNull(error);
        assertTrue(error.contains("empty"));
    }

    @Test
    void debugOpen_returnsErrorForUnknownServer() {
        when(proxy.getServer("unknown")).thenReturn(Optional.empty());
        manager.enqueue(playerA);
        String error = manager.debugOpen("unknown", 1);
        assertNotNull(error);
    }
}
