package com.plantit.queue.plan;

import com.djrapitops.plan.extension.DataExtension;
import com.djrapitops.plan.extension.annotation.NumberProvider;
import com.djrapitops.plan.extension.annotation.PluginInfo;
import com.djrapitops.plan.extension.annotation.StringProvider;
import com.djrapitops.plan.extension.annotation.TableProvider;
import com.djrapitops.plan.extension.icon.Color;
import com.djrapitops.plan.extension.icon.Family;
import com.djrapitops.plan.extension.icon.Icon;
import com.djrapitops.plan.extension.table.Table;
import com.plantit.queue.QueueManager;

import java.util.Map;
import java.util.UUID;

@PluginInfo(name = "PlantIt Queue", iconName = "users", iconFamily = Family.SOLID, color = Color.GREEN)
public class PlantItExtension implements DataExtension {

    private final QueueManager queueManager;

    PlantItExtension(QueueManager queueManager) {
        this.queueManager = queueManager;
    }

    @NumberProvider(text = "Queue Joins (All Time)", iconName = "sign-in-alt", priority = 100)
    public long totalQueueJoins() {
        return queueManager.getTotalQueueJoins();
    }

    @NumberProvider(text = "Dispatches (All Time)", iconName = "play", priority = 90)
    public long totalDispatches() {
        return queueManager.getTotalDispatches();
    }

    @TableProvider(tableColor = Color.GREEN)
    public Table serverDispatchTable() {
        Table.Factory factory = Table.builder()
                .columnOne("Server", Icon.called("server").of(Family.SOLID).build())
                .columnTwo("Dispatches", Icon.called("play").of(Family.SOLID).build());
        for (Map.Entry<String, Long> entry : queueManager.getServerDispatchCounts().entrySet()) {
            factory.addRow(entry.getKey(), entry.getValue());
        }
        return factory.build();
    }

    @NumberProvider(text = "Times Queued", iconName = "list", priority = 50)
    public long timesQueued(UUID playerUUID) {
        return queueManager.getPlayerQueueJoins(playerUUID);
    }

    @StringProvider(text = "Last Server Dispatched To", iconName = "server", priority = 40)
    public String lastServer(UUID playerUUID) {
        String server = queueManager.getPlayerLastServer(playerUUID);
        return server != null ? server : "Never dispatched";
    }
}
