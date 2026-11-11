package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.dispatch.DispatchRuntimeEventDO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class WorkitemProgressEventsTest {

    @Test
    void mergesWhitelistAndLatestEventsByIdInArrivalOrder() {
        DispatchRuntimeEventDO first = event(3L, "step.started");
        DispatchRuntimeEventDO second = event(5L, "step.completed");
        DispatchRuntimeEventDO latestOnly = event(9L, "agent.message");

        List<DispatchRuntimeEventDO> merged = WorkitemService.mergeProgressEvents(
                List.of(second, first), List.of(second, latestOnly));

        assertEquals(List.of(3L, 5L, 9L), merged.stream().map(DispatchRuntimeEventDO::getId).toList());
        assertEquals(3, merged.size());
    }

    @Test
    void returnsWhitelistEventsUnchangedWhenNoLatestRowsExist() {
        List<DispatchRuntimeEventDO> progress = List.of(event(1L, "step.started"));
        assertSame(progress, WorkitemService.mergeProgressEvents(progress, List.of()));
    }

    private static DispatchRuntimeEventDO event(long id, String type) {
        DispatchRuntimeEventDO event = new DispatchRuntimeEventDO();
        event.setId(id);
        event.setEventType(type);
        return event;
    }
}
