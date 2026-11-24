package com.aliyun.autowonder.conversation;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 基于 H2 的会话事件 DAO 回归测试：批量多值 {@code INSERT IGNORE} 的列名、唯一键幂等，
 * 以及批内去重查询的 IN 列表与代次过滤，在真实 SQL 方言下可执行且结果正确。
 */
class AgentConversationTurnEventDaoTest {

    static final String JDBC_URL = "jdbc:h2:mem:turn_event_test;MODE=MySQL;DB_CLOSE_DELAY=-1";
    static AgentConversationTurnEventDao dao;

    @Test
    void insertChunksIfAbsentIsIdempotentOnUniqueKey() {
        dao.insertChunksIfAbsent(List.of(row(1, 0, 2, "{\"a\":"), row(1, 1, 2, "1}")));
        dao.insertChunksIfAbsent(List.of(row(1, 0, 2, "{\"a\":"), row(1, 1, 2, "1}")));

        List<AgentConversationTurnEventDO> chunks = dao.listLogicalEventChunks(1L, 20L, 1, 1L);
        assertEquals(2, chunks.size());
        assertEquals(List.of(0, 1),
                chunks.stream().map(AgentConversationTurnEventDO::getChunkIndex).toList());
    }

    @Test
    void listChunksForSeqsUpToAttemptMergesGenerationsDescending() {
        dao.insertChunksIfAbsent(List.of(row(1, 0, 1, "{\"a\":1}")));
        dao.insertChunksIfAbsent(List.of(row(2, 0, 1, "{\"a\":2}")));

        List<AgentConversationTurnEventDO> chunks =
                dao.listChunksForSeqsUpToAttempt(1L, 20L, 2, List.of(1L));
        assertEquals(2, chunks.size());
        assertEquals(List.of(2, 1),
                chunks.stream().map(AgentConversationTurnEventDO::getDispatchAttempt).toList());
        assertEquals("{\"a\":2}", chunks.get(0).getPayloadFragment());
    }

    private AgentConversationTurnEventDO row(int attempt, int chunkIndex, int chunkCount, String payload) {
        AgentConversationTurnEventDO row = new AgentConversationTurnEventDO();
        row.setTenantId(1L);
        row.setConversationId(10L);
        row.setTurnId(20L);
        row.setDispatchAttempt(attempt);
        row.setEventSeq(1L);
        row.setChunkIndex(chunkIndex);
        row.setChunkCount(chunkCount);
        row.setEventType("text");
        row.setPayloadFragment(payload);
        return row;
    }

    @BeforeAll
    static void initDb() throws Exception {
        try (InputStream in = Resources.getResourceAsStream("mybatis-turn-event-test-config.xml")) {
            SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(in);
            dao = new SqlSessionTemplate(factory).getMapper(AgentConversationTurnEventDao.class);
        }
        execScript("agent-conversation-turn-event-schema-h2.sql");
    }

    @BeforeEach
    void cleanTable() throws Exception {
        exec("DELETE FROM agent_conversation_turn_event");
    }

    private static void execScript(String resource) throws Exception {
        String sql;
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String stmt : sql.split(";")) {
            if (!stmt.isBlank()) {
                exec(stmt);
            }
        }
    }

    private static void exec(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
             Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }
}
