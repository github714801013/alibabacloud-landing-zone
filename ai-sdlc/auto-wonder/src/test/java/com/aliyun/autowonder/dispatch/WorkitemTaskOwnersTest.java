package com.aliyun.autowonder.dispatch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkitemTaskOwnersTest {

    private static String contentWithTasks(String tasksJson) {
        return "需求说明\n\n<!-- autowonder-jira-status:start\n"
                + "{\"schema\":1,\"scope_confirmed\":true,\"tasks\":" + tasksJson + "}\n"
                + "autowonder-jira-status:end -->\n";
    }

    @Test
    void returnsOwnersInTaskOrderAndDeduplicates() {
        String contentMd = contentWithTasks("["
                + "{\"key\":\"HS-1\",\"included\":true,\"aw_user_id\":10032,\"repos\":[\"a\"]},"
                + "{\"key\":\"HS-2\",\"included\":true,\"aw_user_id\":10033,\"repos\":[\"b\"]},"
                + "{\"key\":\"HS-3\",\"included\":true,\"aw_user_id\":10032,\"repos\":[\"c\"]}"
                + "]");

        assertEquals(List.of(10032L, 10033L), WorkitemTaskOwners.orderedOwnerIds(contentMd));
    }

    @Test
    void skipsExcludedTasksAndInvalidOwnerIds() {
        String contentMd = contentWithTasks("["
                + "{\"key\":\"HS-1\",\"included\":false,\"aw_user_id\":10032},"
                + "{\"key\":\"HS-2\",\"included\":true,\"aw_user_id\":0},"
                + "{\"key\":\"HS-3\",\"included\":true,\"aw_user_id\":\"bad\"},"
                + "{\"key\":\"HS-4\",\"included\":true,\"jira_assignee\":\"16078\"},"
                + "{\"key\":\"HS-5\",\"included\":true,\"aw_user_id\":10033}"
                + "]");

        assertEquals(List.of(10033L), WorkitemTaskOwners.orderedOwnerIds(contentMd));
    }

    @Test
    void ignoresJiraAssigneeWhenAwUserIdIsAbsent() {
        String contentMd = contentWithTasks("[{\"key\":\"HS-1\",\"included\":true,\"jira_assignee\":\"16078\"}]");

        assertTrue(WorkitemTaskOwners.orderedOwnerIds(contentMd).isEmpty());
    }

    @Test
    void returnsEmptyWhenBlockIsMissingEmptyOrMalformed() {
        assertTrue(WorkitemTaskOwners.orderedOwnerIds(null).isEmpty());
        assertTrue(WorkitemTaskOwners.orderedOwnerIds("普通正文").isEmpty());
        assertTrue(WorkitemTaskOwners.orderedOwnerIds(contentWithTasks("[]")).isEmpty());
        assertTrue(WorkitemTaskOwners.orderedOwnerIds(
                "<!-- autowonder-jira-status:start\n{not-json}\nautowonder-jira-status:end -->").isEmpty());
    }
}
