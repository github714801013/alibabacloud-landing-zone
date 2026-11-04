package com.aliyun.autowonder.integration.wecom;

import com.aliyun.autowonder.conversation.AgentConversationTurnDao;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WecomFeedbackTurnDaoTest {
    @Test
    void scanOnlyReturnsRecentActiveWecomInboundTurnsWithTenantJoinAndPaging() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:wecom-feedback-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE agent_conversation (id BIGINT, tenant_id BIGINT, channel VARCHAR(32))");
            statement.execute("""
                    CREATE TABLE agent_conversation_turn (
                        id BIGINT PRIMARY KEY, tenant_id BIGINT, conversation_id BIGINT,
                        direction VARCHAR(8), content TEXT, external_msg_id VARCHAR(128),
                        request_id VARCHAR(128), source_context TEXT, submitted_user_id BIGINT,
                        status VARCHAR(32), error TEXT, source_inbound_turn_id BIGINT,
                        delivery_status VARCHAR(32), delivery_attempt INT, delivery_available_at TIMESTAMP,
                        delivery_error TEXT, last_dispatch_at TIMESTAMP, dispatch_attempt INT, gmt_create TIMESTAMP)
                    """);
            statement.execute("INSERT INTO agent_conversation VALUES (10,1,'WECOM'),(20,2,'WECOM'),(30,1,'DINGTALK')");
            statement.execute("""
                    INSERT INTO agent_conversation_turn (id,tenant_id,conversation_id,direction,status,gmt_create) VALUES
                    (1,1,10,'IN','PROCESSING','2026-11-04 08:00:00'),
                    (2,1,10,'IN','QUEUED','2026-11-04 08:00:00'),
                    (3,1,10,'OUT','PROCESSING','2026-11-04 08:00:00'),
                    (4,1,10,'IN','SUCCESS','2026-11-04 08:00:00'),
                    (5,1,30,'IN','PROCESSING','2026-11-04 08:00:00'),
                    (6,1,20,'IN','PROCESSING','2026-11-04 08:00:00'),
                    (7,1,10,'IN','PROCESSING','2026-11-01 08:00:00'),
                    (8,2,20,'IN','PROCESSING','2026-11-04 08:00:00')
                    """);
        }
        var config = new Configuration();
        config.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        try (var input = getClass().getResourceAsStream("/mapping/AgentConversationTurnDao.xml")) {
            new XMLMapperBuilder(input, config, "mapping/AgentConversationTurnDao.xml", config.getSqlFragments()).parse();
        }
        try (var session = new SqlSessionFactoryBuilder().build(config).openSession()) {
            var dao = session.getMapper(AgentConversationTurnDao.class);
            Date cutoff = Date.from(Instant.parse("2026-11-03T08:00:00Z"));
            assertEquals(List.of(1L, 2L), dao.listActiveWecomTurns(0, cutoff, 2).stream()
                    .map(turn -> turn.getId()).toList());
            assertEquals(List.of(8L), dao.listActiveWecomTurns(2, cutoff, 2).stream()
                    .map(turn -> turn.getId()).toList());
        }
    }
}
