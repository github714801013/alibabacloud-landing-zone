package com.aliyun.autowonder.conversation;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AgentConversationOwnerDaoTest {

    private SqlSessionFactory sessions;

    @BeforeEach
    void setUp() throws Exception {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:clarification-owner-" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE agent_conversation (
                        id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, owner_user_id BIGINT,
                        channel VARCHAR(32) NOT NULL, version INT NOT NULL DEFAULT 0)
                    """);
            statement.execute("""
                    INSERT INTO agent_conversation (id, tenant_id, owner_user_id, channel) VALUES
                    (77, 1, NULL, 'WORKITEM_CLARIFICATION'),
                    (78, 2, NULL, 'WORKITEM_CLARIFICATION'),
                    (79, 1, NULL, 'DINGTALK'),
                    (80, 1, 7, 'PLATFORM_ASSISTANT'),
                    (81, 1, 42, 'WORKITEM_CLARIFICATION')
                    """);
        }
        var configuration = new Configuration();
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        try (var input = getClass().getResourceAsStream("/mapping/AgentConversationDao.xml")) {
            new XMLMapperBuilder(input, configuration, "mapping/AgentConversationDao.xml",
                    configuration.getSqlFragments()).parse();
        }
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    @Test
    void firstClaimIsFixedAndLimitedToTheTenantAndClarificationChannel() {
        try (var session = sessions.openSession()) {
            AgentConversationDao dao = session.getMapper(AgentConversationDao.class);

            assertEquals(1, dao.bindClarificationOwnerIfAbsent(1L, 77L, 42L));
            assertEquals(0, dao.bindClarificationOwnerIfAbsent(1L, 77L, 99L));
            assertEquals(42L, dao.findClarificationOwnerForUpdate(1L, 77L));
            assertEquals(0, dao.bindClarificationOwnerIfAbsent(1L, 78L, 42L));
            assertEquals(0, dao.bindClarificationOwnerIfAbsent(1L, 79L, 42L));
            assertEquals(0, dao.bindClarificationOwnerIfAbsent(1L, 80L, 42L));
            assertEquals(0, dao.bindClarificationOwnerIfAbsent(1L, 81L, 99L));
            assertNull(dao.findClarificationOwnerForUpdate(1L, 78L));
            assertNull(dao.findClarificationOwnerForUpdate(1L, 79L));
            assertEquals(42L, dao.findClarificationOwnerForUpdate(1L, 81L));
            session.commit();
        }
    }

    @Test
    void rolledBackClaimDoesNotPreventTheNextValidSpeakerFromBecomingOwner() {
        try (var session = sessions.openSession()) {
            assertEquals(1, session.getMapper(AgentConversationDao.class)
                    .bindClarificationOwnerIfAbsent(1L, 77L, 42L));
            session.rollback();
        }
        try (var session = sessions.openSession()) {
            AgentConversationDao dao = session.getMapper(AgentConversationDao.class);
            assertNull(dao.findClarificationOwnerForUpdate(1L, 77L));
            assertEquals(1, dao.bindClarificationOwnerIfAbsent(1L, 77L, 99L));
            session.commit();
        }
        try (var session = sessions.openSession()) {
            assertEquals(99L, session.getMapper(AgentConversationDao.class)
                    .findClarificationOwnerForUpdate(1L, 77L));
        }
    }

    @Test
    void competingTransactionCannotOverwriteTheCommittedFirstSpeaker() throws Exception {
        var started = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try (var first = sessions.openSession()) {
            assertEquals(1, first.getMapper(AgentConversationDao.class)
                    .bindClarificationOwnerIfAbsent(1L, 77L, 42L));
            var competing = executor.submit(() -> {
                try (var second = sessions.openSession()) {
                    AgentConversationDao dao = second.getMapper(AgentConversationDao.class);
                    started.countDown();
                    assertEquals(0, dao.bindClarificationOwnerIfAbsent(1L, 77L, 99L));
                    Long owner = dao.findClarificationOwnerForUpdate(1L, 77L);
                    second.commit();
                    return owner;
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            first.commit();

            assertEquals(42L, competing.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }
}
