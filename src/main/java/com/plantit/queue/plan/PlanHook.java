package com.plantit.queue.plan;

import com.djrapitops.plan.extension.ExtensionService;
import com.plantit.queue.QueueManager;

public class PlanHook {

    private PlanHook() {}

    public static void register(QueueManager queueManager) {
        ExtensionService.getInstance().register(new PlantItExtension(queueManager));
    }
}
