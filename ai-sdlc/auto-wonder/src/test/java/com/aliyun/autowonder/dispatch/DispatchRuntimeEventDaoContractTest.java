package com.aliyun.autowonder.dispatch;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatchRuntimeEventDaoContractTest {

    @Test
    void progressProjectionPreservesPlansHistoryAndIsolationInBothSchemas() throws Exception {
        for (String databaseId : java.util.List.of("autowonder-source-aware", "autowonder-legacy")) {
            org.apache.ibatis.session.Configuration config = new org.apache.ibatis.session.Configuration();
            config.setDatabaseId(databaseId);
            try (var input = getClass().getResourceAsStream("/mapping/DispatchRuntimeEventDao.xml")) {
                new org.apache.ibatis.builder.xml.XMLMapperBuilder(input, config,
                        "mapping/DispatchRuntimeEventDao.xml", config.getSqlFragments()).parse();
            }
            String sql = config.getMappedStatement(DispatchRuntimeEventDao.class.getName() + ".listForProgress")
                    .getBoundSql(java.util.Map.of("tenantId", 7L, "workitemId", 100L)).getSql();
            assertTrue(sql.contains("CASE WHEN e.event_type = 'workflow.plan_applied' THEN e.detail_json ELSE NULL END"));
            assertTrue(sql.contains("e.event_type LIKE 'step.%'"));
            assertTrue(sql.contains("'completion_requested', 'dispatch.completed', 'workflow.plan_applied', 'dispatch.failed'"));
            assertTrue(sql.contains("e.error IS NOT NULL"));
            assertTrue(sql.contains("FROM dispatch d STRAIGHT_JOIN dispatch_runtime_event e"));
            assertTrue(sql.contains("e.tenant_id = d.tenant_id AND e.dispatch_id = d.id"));
            assertTrue(sql.contains("FORCE INDEX (idx_dispatch_event_type)"));
            assertTrue(sql.contains("d.tenant_id = ? AND d.workitem_id = ?"));
            assertTrue(sql.contains("d.is_deleted = 0"));
            assertTrue(sql.contains("UNION ALL"));
            assertTrue(sql.contains("AND NOT ("));
            assertFalse(sql.contains("EXISTS"));
            assertTrue(sql.contains("ORDER BY merged.id ASC"));
            assertFalse(sql.contains("LIMIT"));
            assertTrue(sql.contains("d.source_type = 'WORKITEM'") == databaseId.equals("autowonder-source-aware"));
            String fullSql = config.getMappedStatement(DispatchRuntimeEventDao.class.getName() + ".listByWorkitem")
                    .getBoundSql(java.util.Map.of("tenantId", 7L, "workitemId", 100L)).getSql();
            assertFalse(fullSql.contains("CASE WHEN"));
        }
    }

    @Test
    void latestProgressKeepsOneNewestEventPerDispatchInBothSchemas() throws Exception {
        for (String databaseId : java.util.List.of("autowonder-source-aware", "autowonder-legacy")) {
            org.apache.ibatis.session.Configuration config = new org.apache.ibatis.session.Configuration();
            config.setDatabaseId(databaseId);
            try (var input = getClass().getResourceAsStream("/mapping/DispatchRuntimeEventDao.xml")) {
                new org.apache.ibatis.builder.xml.XMLMapperBuilder(input, config,
                        "mapping/DispatchRuntimeEventDao.xml", config.getSqlFragments()).parse();
            }
            String sql = config.getMappedStatement(DispatchRuntimeEventDao.class.getName() + ".listLatestForProgress")
                    .getBoundSql(java.util.Map.of("tenantId", 7L, "workitemId", 100L)).getSql();
            assertTrue(sql.contains("MAX(e2.id)"));
            assertTrue(sql.contains("GROUP BY e2.dispatch_id"));
            assertTrue(sql.contains("latest.max_id = e.id"));
            assertTrue(sql.contains("e.tenant_id = ? AND e.workitem_id = ?"));
            assertTrue(sql.contains("e2.tenant_id = d.tenant_id"));
            assertTrue(sql.contains("d.tenant_id = ?"));
            assertTrue(sql.contains("d.workitem_id = ?"));
            assertTrue(sql.contains("d.is_deleted = 0"));
            assertTrue(sql.contains("e2.event_type = 'agent.progress'"));
            assertTrue(sql.contains("CASE WHEN event_type = 'workflow.plan_applied' THEN detail_json ELSE NULL END"));
            assertFalse(sql.contains("SELECT e.*"));
            assertTrue(sql.contains("ORDER BY e.id ASC"));
            assertTrue(sql.contains("d.source_type = 'WORKITEM'") == databaseId.equals("autowonder-source-aware"));
        }
    }

    @Test
    void workitemTimelineRequiresMatchingWorkitemDispatchSource() throws Exception {
        String xml = new String(getClass().getResourceAsStream(
                "/mapping/DispatchRuntimeEventDao.xml").readAllBytes(), StandardCharsets.UTF_8);
        int start = xml.indexOf("id=\"listByWorkitem\"");
        int end = xml.indexOf("</select>", start);
        String sql = xml.substring(start, end);

        assertTrue(sql.contains("EXISTS"));
        assertTrue(sql.contains("d.tenant_id = #{tenantId}"));
        assertTrue(sql.contains("d.source_type = 'WORKITEM'"));
        assertTrue(sql.contains("d.workitem_id = #{workitemId}"));
        assertTrue(sql.contains("d.id = e.dispatch_id"));
    }

    @Test
    void traceProjectionExcludesAgentMessagesAndKeepsArrivalOrder() throws Exception {
        String xml = new String(getClass().getResourceAsStream(
                "/mapping/DispatchRuntimeEventDao.xml").readAllBytes(), StandardCharsets.UTF_8);
        int start = xml.indexOf("id=\"listByDispatchForTrace\"");
        assertTrue(start >= 0, "listByDispatchForTrace select must exist");
        int end = xml.indexOf("</select>", start);
        String sql = xml.substring(start, end).replace("&lt;", "<").replace("&gt;", ">");

        assertTrue(sql.contains("tenant_id = #{tenantId}"));
        assertTrue(sql.contains("dispatch_id = #{dispatchId}"));
        assertTrue(sql.contains("event_type <> 'agent.message'"));
        assertTrue(sql.contains("ORDER BY id ASC"));

        int latestStart = xml.indexOf("id=\"findLatestIdForTrace\"");
        assertTrue(latestStart >= 0, "findLatestIdForTrace select must exist");
        String latestSql = xml.substring(latestStart, xml.indexOf("</select>", latestStart))
                .replace("&lt;", "<").replace("&gt;", ">");
        assertTrue(latestSql.contains("MAX(id)"));
        assertTrue(latestSql.contains("event_type <> 'agent.message'"));
        assertTrue(latestSql.contains("tenant_id = #{tenantId}"));
        assertTrue(latestSql.contains("dispatch_id = #{dispatchId}"));
    }

    @Test
    void activityTimelineUsesPersistentArrivalOrderWithoutSequenceFallback() throws Exception {
        String xml = new String(getClass().getResourceAsStream(
                "/mapping/DispatchRuntimeEventDao.xml").readAllBytes(), StandardCharsets.UTF_8);
        int start = xml.indexOf("id=\"listByDispatchInArrivalOrder\"");
        int end = xml.indexOf("</select>", start);
        String sql = xml.substring(start, end);

        assertTrue(sql.contains("ORDER BY id ASC"));
        assertFalse(sql.contains("COALESCE(seq, id)"));
    }

    @Test
    void dispatchAfterIdCursorRequiresTenantIsolationAndStrictlyGreaterId() throws Exception {
        String xml = new String(getClass().getResourceAsStream(
                "/mapping/DispatchRuntimeEventDao.xml").readAllBytes(), StandardCharsets.UTF_8);
        int start = xml.indexOf("id=\"listByDispatchAfterId\"");
        assertTrue(start >= 0, "listByDispatchAfterId select must exist");
        int end = xml.indexOf("</select>", start);
        assertTrue(end > start, "listByDispatchAfterId select must be closed");
        String sql = xml.substring(start, end);
        String normalized = sql.replace("&gt;", ">").replace("&lt;", "<");

        assertTrue(normalized.contains("tenant_id = #{tenantId}"));
        assertTrue(normalized.contains("dispatch_id = #{dispatchId}"));
        assertTrue(normalized.contains("id > #{afterId}"));
        assertFalse(normalized.contains("id >= #{afterId}"),
                "afterId cursor must be strictly greater, otherwise reconnect re-delivers the cursor row");
        assertTrue(normalized.contains("ORDER BY id ASC"));
        assertFalse(normalized.contains("COALESCE(seq, id)"),
                "failover re-runs restart the runtime-numbered seq, so the cursor must follow arrival id");
        assertFalse(sql.contains("${"));
    }
}
