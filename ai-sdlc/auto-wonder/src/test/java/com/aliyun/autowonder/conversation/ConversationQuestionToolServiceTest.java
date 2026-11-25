package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONObject;
import com.aliyun.autowonder.mcp.McpAccessTokenService;
import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationQuestionToolServiceTest {

    private AgentConversationElicitationDao elicitationDao;
    private AgentConversationDao conversationDao;
    private ConversationRuntimePresence runtimePresence;
    private ConversationBrowserEventPublisher browserEventPublisher;
    private RedisManager redisManager;
    private ConversationQuestionToolService service;

    @BeforeEach
    void setUp() {
        elicitationDao = mock(AgentConversationElicitationDao.class);
        conversationDao = mock(AgentConversationDao.class);
        runtimePresence = mock(ConversationRuntimePresence.class);
        browserEventPublisher = mock(ConversationBrowserEventPublisher.class);
        redisManager = mock(RedisManager.class);
        service = new ConversationQuestionToolService(elicitationDao, conversationDao, runtimePresence,
                browserEventPublisher, redisManager);
        AgentConversationDO conv = new AgentConversationDO();
        conv.setExecutorId(7L);
        when(conversationDao.findById(1L, 10L)).thenReturn(conv);
        when(runtimePresence.isExecutorOnline(7L)).thenReturn(true);
        when(runtimePresence.supportsProtocolFeature(7L, ConversationProtocolFeatures.ACP_INTERACTION))
                .thenReturn(true);
        when(elicitationDao.listPendingByConversation(1L, 10L)).thenReturn(List.of());
    }

    @Test
    void askPublishesOneMergedCardAndReturnsStructuredAnswers() throws Exception {
        Map<String, Object> result = askAndWake("ANSWERED", "{\"q1\":\"方案A\",\"q2\":[\"选项B\"]}");

        assertEquals("answered", result.get("status"));
        JSONObject answers = (JSONObject) result.get("answers");
        assertEquals("方案A", answers.getString("q1"));

        ArgumentCaptor<AgentConversationElicitationDO> card =
                ArgumentCaptor.forClass(AgentConversationElicitationDO.class);
        verify(elicitationDao).insertIfAbsent(card.capture());
        assertEquals("选择方案", card.getValue().getMessage());
        assertTrue(card.getValue().getRequestId().startsWith("mcpq-"));
        assertEquals("PENDING", card.getValue().getStatus());

        JSONObject schema = JSON.parseObject(card.getValue().getSchemaJson());
        JSONObject properties = schema.getJSONObject("properties");
        assertEquals(2, properties.size());
        assertEquals("方案", properties.getJSONObject("q1").getString("title"));
        assertEquals("先做后端", splitHint(properties.getJSONObject("q1").getString("description")));
        assertNotNull(properties.getJSONObject("q1").getJSONArray("oneOf"));
        assertEquals("array", properties.getJSONObject("q2").getString("type"));
        assertNotNull(properties.getJSONObject("q2").getJSONObject("items").getJSONArray("anyOf"));
    }

    @Test
    void declineReturnsStatusWithoutAnswers() throws Exception {
        Map<String, Object> result = askAndWake("DECLINED", null);

        assertEquals("declined", result.get("status"));
        assertFalse(result.containsKey("answers"));
    }

    @Test
    void askRejectsEmptyQuestionListWithoutCreatingCard() {
        BizException failure = assertThrows(BizException.class,
                () -> service.ask(principal(), Map.of("questions", List.of())));

        assertEquals(ErrorCode.MCP_TOOL_ARGUMENT_INVALID.getCode(), failure.getCode());
        verify(elicitationDao, never()).insertIfAbsent(any());
    }

    @Test
    void askRejectsWhileConversationHasPendingQuestion() {
        when(elicitationDao.listPendingByConversation(1L, 10L))
                .thenReturn(List.of(new AgentConversationElicitationDO()));

        BizException failure = assertThrows(BizException.class, () -> service.ask(principal(), args()));

        assertEquals(ErrorCode.CONFLICT.getCode(), failure.getCode());
        verify(elicitationDao, never()).insertIfAbsent(any());
    }

    @Test
    void askFailsClosedForPersonalCredential() {
        McpAccessTokenService.Principal personal = new McpAccessTokenService.Principal(null, 2L, 3L,
                null, McpAccessTokenService.CredentialType.LONG_LIVED);

        assertFalse(service.isAvailable(personal));
        BizException failure = assertThrows(BizException.class, () -> service.ask(personal, args()));
        assertEquals(ErrorCode.NO_PERMISSION.getCode(), failure.getCode());
    }

    @Test
    void askFailsClosedWhenSessionDoesNotSupportAcpInteraction() {
        when(runtimePresence.supportsProtocolFeature(7L, ConversationProtocolFeatures.ACP_INTERACTION))
                .thenReturn(false);

        assertFalse(service.isAvailable(principal()));
    }

    @Test
    void askFailsClosedWhenExecutorOffline() {
        when(runtimePresence.isExecutorOnline(7L)).thenReturn(false);

        assertFalse(service.isAvailable(principal()));
        assertThrows(BizException.class, () -> service.ask(principal(), args()));
    }

    @Test
    void fourQuestionsMergeIntoOneCardWithFourAnswerKeys() throws Exception {
        Map<String, Object> four = Map.of("questions", List.of(
                question("一"), question("二"), question("三"), question("四")));
        Map<String, Object> result = askAndWake(four, "ANSWERED",
                "{\"q1\":\"A\",\"q2\":\"A\",\"q3\":\"A\",\"q4\":\"A\"}");

        JSONObject answers = (JSONObject) result.get("answers");
        assertEquals(4, answers.size());
        ArgumentCaptor<AgentConversationElicitationDO> card =
                ArgumentCaptor.forClass(AgentConversationElicitationDO.class);
        verify(elicitationDao, times(1)).insertIfAbsent(card.capture());
        assertEquals(4, JSON.parseObject(card.getValue().getSchemaJson())
                .getJSONObject("properties").size());
    }

    @Test
    void askRejectsMoreThanFourQuestionsWithoutCreatingCard() {
        Map<String, Object> five = Map.of("questions", List.of(
                question("一"), question("二"), question("三"), question("四"), question("五")));

        BizException failure = assertThrows(BizException.class, () -> service.ask(principal(), five));
        assertEquals(ErrorCode.MCP_TOOL_ARGUMENT_INVALID.getCode(), failure.getCode());
        verify(elicitationDao, never()).insertIfAbsent(any());
    }

    @Test
    void waitTimeoutSettlesCardExpiredAndReturnsExpired() {
        setField("waitSeconds", 1);
        when(elicitationDao.insertIfAbsent(any())).thenReturn(1);
        when(elicitationDao.settleIfPending(eq(1L), eq(10L), any(), eq("EXPIRED"), isNull()))
                .thenReturn(1);

        Map<String, Object> result = service.ask(principal(), args());

        assertEquals("expired", result.get("status"));
        assertFalse(result.containsKey("answers"));
        verify(elicitationDao).settleIfPending(eq(1L), eq(10L), any(), eq("EXPIRED"), isNull());
        verify(browserEventPublisher).publishServerEvent(eq(1L), eq(10L), eq(20L),
                eq("acp_elicitation_resolved"), any());
    }

    @Test
    void askRejectsWhenInstanceWaiterLimitReached() {
        setField("waiters", new java.util.concurrent.atomic.AtomicInteger(100));

        BizException failure = assertThrows(BizException.class, () -> service.ask(principal(), args()));

        assertEquals(ErrorCode.CONFLICT.getCode(), failure.getCode());
        verify(elicitationDao, never()).insertIfAbsent(any());
    }

    @Test
    void interruptedRequestCancelsCardAndReturnsCanceled() throws Exception {
        AtomicReference<String> requestId = new AtomicReference<>();
        when(elicitationDao.insertIfAbsent(any())).thenAnswer(invocation -> {
            requestId.set(invocation.<AgentConversationElicitationDO>getArgument(0).getRequestId());
            return 1;
        });
        when(elicitationDao.settleIfPending(eq(1L), eq(10L), any(), eq("CANCELED"), isNull()))
                .thenReturn(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Map<String, Object>> waiting = pool.submit(() -> service.ask(principal(), args()));
            long deadline = System.currentTimeMillis() + 2000;
            while (requestId.get() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertNotNull(requestId.get());
            pool.shutdownNow();
            Map<String, Object> result = waiting.get(2, TimeUnit.SECONDS);
            assertEquals("canceled", result.get("status"));
            verify(elicitationDao).settleIfPending(1L, 10L, requestId.get(), "CANCELED", null);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void askRejectsOptionCountOutsideTwoToFour() {
        Map<String, Object> oneOption = Map.of("questions", List.of(Map.of(
                "header", "栏", "question", "只有一个选项",
                "options", List.of(Map.of("label", "A")))));
        Map<String, Object> fiveOptions = Map.of("questions", List.of(Map.of(
                "header", "栏", "question", "五个选项",
                "options", List.of(Map.of("label", "A"), Map.of("label", "B"),
                        Map.of("label", "C"), Map.of("label", "D"), Map.of("label", "E")))));

        assertEquals(ErrorCode.MCP_TOOL_ARGUMENT_INVALID.getCode(),
                assertThrows(BizException.class, () -> service.ask(principal(), oneOption)).getCode());
        assertEquals(ErrorCode.MCP_TOOL_ARGUMENT_INVALID.getCode(),
                assertThrows(BizException.class, () -> service.ask(principal(), fiveOptions)).getCode());
        verify(elicitationDao, never()).insertIfAbsent(any());
    }

    @Test
    void askFailsWhenCardInsertConflicts() {
        when(elicitationDao.insertIfAbsent(any())).thenReturn(0);

        BizException failure = assertThrows(BizException.class, () -> service.ask(principal(), args()));
        assertEquals(ErrorCode.CONFLICT.getCode(), failure.getCode());
    }

    @Test
    void publishAnswerPushesResolvedEvent() {
        service.publishAnswer(1L, 10L, 20L, "mcpq-x", "ANSWERED", "{\"q1\":\"A\"}");

        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(browserEventPublisher).publishServerEvent(eq(1L), eq(10L), eq(20L),
                type.capture(), payload.capture());
        assertEquals("acp_elicitation_resolved", type.getValue());
        assertTrue(payload.getValue().contains("\"status\":\"ANSWERED\""));
        verify(redisManager).publish(eq("autowonder:question-answer"), any());
    }

    private static Map<String, Object> question(String text) {
        return Map.of("header", "栏", "question", text,
                "options", List.of(Map.of("label", "A"), Map.of("label", "B")));
    }

    private Map<String, Object> askAndWake(String status, String answerJson) throws Exception {
        return askAndWake(args(), status, answerJson);
    }

    private Map<String, Object> askAndWake(Map<String, Object> args, String status, String answerJson)
            throws Exception {
        AtomicReference<String> requestId = new AtomicReference<>();
        when(elicitationDao.insertIfAbsent(any())).thenAnswer(invocation -> {
            requestId.set(invocation.<AgentConversationElicitationDO>getArgument(0).getRequestId());
            return 1;
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Map<String, Object>> waiting = pool.submit(() -> service.ask(principal(), args));
            long deadline = System.currentTimeMillis() + 2000;
            while (requestId.get() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertNotNull(requestId.get());
            service.wake(10L, requestId.get(), status, answerJson);
            return waiting.get(2, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 覆盖上限与超时这类非公开配置字段，避免为此在生产代码开 setter。 */
    private void setField(String name, Object value) {
        try {
            java.lang.reflect.Field field = ConversationQuestionToolService.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(service, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String splitHint(String description) {
        // 前端 splitOptionHints 以「<label>: <说明>」行承载选项说明。
        return description.substring(description.indexOf("方案A:") + "方案A:".length()).trim();
    }

    private static Map<String, Object> args() {
        return Map.of("questions", List.of(
                Map.of("header", "方案", "question", "选择方案", "options", List.of(
                        Map.of("label", "方案A", "description", "先做后端"),
                        Map.of("label", "方案B"))),
                Map.of("header", "附加", "question", "附加项", "multiSelect", true,
                        "options", List.of(Map.of("label", "选项B"), Map.of("label", "选项C")))));
    }

    private static McpAccessTokenService.Principal principal() {
        return new McpAccessTokenService.Principal(1L, 2L, 3L, WorkspaceAccessLevel.READ_WRITE,
                McpAccessTokenService.CredentialType.CONVERSATION,
                new McpAccessTokenService.ExecutionScope(10L, 20L, 1, 7L, 5L, 6L));
    }
}
