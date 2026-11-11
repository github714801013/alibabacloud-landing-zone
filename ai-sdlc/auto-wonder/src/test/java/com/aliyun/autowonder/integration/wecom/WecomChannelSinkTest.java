package com.aliyun.autowonder.integration.wecom;

import com.aliyun.autowonder.conversation.AgentConversationDO;
import com.aliyun.autowonder.conversation.AgentConversationTurnDO;
import com.aliyun.autowonder.conversation.AgentConversationTurnDao;
import com.aliyun.autowonder.conversation.ImDeliveryFailedException;
import com.aliyun.autowonder.conversation.ImDeliveryRetryableException;
import com.aliyun.autowonder.conversation.ImDeliveryTargetChangedException;
import com.aliyun.autowonder.conversation.ImDeliveryUnknownException;
import com.aliyun.autowonder.im.ImChannelPurpose;
import com.aliyun.autowonder.im.ImRouteSnapshot;
import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WecomChannelSinkTest {
    private static final String EXTERNAL_ID = "WECOM:bot:msg-1";
    private final ObjectMapper json = new ObjectMapper();
    private final AgentConversationTurnDao turns = mock(AgentConversationTurnDao.class);
    private final WecomRobotBindingDao bindings = mock(WecomRobotBindingDao.class);
    private final PlatformImChannelConfigService imConfigs =
            mock(PlatformImChannelConfigService.class);
    private final WorkspaceMemberDao members = mock(WorkspaceMemberDao.class);
    private final WecomBotConnectionManager manager = mock(WecomBotConnectionManager.class);
    private final WecomChannelSink sink = new WecomChannelSink(turns, bindings, imConfigs, members,
            manager, json, null);

    @Test
    void onlyTheGroupChatConnectionOwnerCanDeliver() {
        PlatformImChannelConfigDO config = new PlatformImChannelConfigDO();
        config.setProvider("WECOM");
        config.setPurpose("GROUP_CHAT");
        config.setEnabled(1);
        when(imConfigs.selectedProviderOrNull()).thenReturn("WECOM");
        when(imConfigs.findGroupChat("WECOM")).thenReturn(config);
        when(manager.isConnectionOwner(ImChannelPurpose.GROUP_CHAT)).thenReturn(false);
        assertFalse(sink.canDeliverNow());

        when(manager.isConnectionOwner(ImChannelPurpose.GROUP_CHAT)).thenReturn(true);
        assertTrue(sink.canDeliverNow());
    }

    @Test
    void deselectedChannelReleasesQueuedRepliesForCancellation() {
        arrange(System.currentTimeMillis(), 3, 3, 4, 1, "ENABLED");
        when(imConfigs.selectedProviderOrNull()).thenReturn("DINGTALK");
        when(manager.isConnectionOwner(ImChannelPurpose.GROUP_CHAT)).thenReturn(false);

        assertTrue(sink.canDeliverNow());
        assertThrows(ImDeliveryTargetChangedException.class,
                () -> sink.deliverReply(conversation(), "答复", EXTERNAL_ID));
        verify(manager, never()).respondStream(any(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void disabledGroupChatConfigReleasesQueuedRepliesForCancellation() {
        arrange(System.currentTimeMillis(), 3, 3, 4, 0, "ENABLED");
        when(manager.isConnectionOwner(ImChannelPurpose.GROUP_CHAT)).thenReturn(false);

        assertTrue(sink.canDeliverNow());
        assertThrows(ImDeliveryTargetChangedException.class,
                () -> sink.deliverReply(conversation(), "答复", EXTERNAL_ID));
        verify(manager, never()).respondStream(any(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void finishesStreamAndRecordsSuccess() {
        arrange(System.currentTimeMillis(), 3, 3, 4, 1, "ENABLED");
        when(manager.respondStream(ImChannelPurpose.GROUP_CHAT, "bot", "req-1",
                "aw-msg-1-0", "答复", true))
                .thenReturn(WecomSendResult.SENT);

        sink.deliverReply(conversation(), "答复", EXTERNAL_ID);

        verify(manager).respondStream(ImChannelPurpose.GROUP_CHAT, "bot", "req-1",
                "aw-msg-1-0", "答复", true);
        verify(bindings).health(any(), isNull());
    }

    @Test
    void messageOlderThanTwentyFourHoursFailsWithoutSending() {
        arrange(System.currentTimeMillis() - WecomChannelSink.REPLY_WINDOW_MILLIS - 1, 3, 3, 4, 1,
                "ENABLED");

        ImDeliveryFailedException error = assertThrows(ImDeliveryFailedException.class,
                () -> sink.deliverReply(conversation(), "答复", EXTERNAL_ID));

        assertTrue(error.getMessage().contains("24"));
        verifyNoInteractions(manager);
        verify(bindings).health(any(), anyString());
    }

    @Test
    void bindingRouteChangeCancelsReply() {
        arrange(System.currentTimeMillis(), 3, 4, 4, 1, "ENABLED");

        assertThrows(ImDeliveryTargetChangedException.class,
                () -> sink.deliverReply(conversation(), "答复", EXTERNAL_ID));

        verifyNoInteractions(manager);
    }

    @Test
    void disabledBindingCancelsReply() {
        arrange(System.currentTimeMillis(), 3, 3, 4, 1, "DISABLED");

        assertThrows(ImDeliveryTargetChangedException.class,
                () -> sink.deliverReply(conversation(), "答复", EXTERNAL_ID));

        verifyNoInteractions(manager);
    }

    @Test
    void configRouteChangeCancelsReply() {
        arrange(System.currentTimeMillis(), 3, 3, 5, 1, "ENABLED");

        assertThrows(ImDeliveryTargetChangedException.class,
                () -> sink.deliverReply(conversation(), "答复", EXTERNAL_ID));

        verifyNoInteractions(manager);
    }

    @Test
    void revokedDelegatedAdminCancelsReply() {
        arrange(System.currentTimeMillis(), 3, 3, 4, 1, "ENABLED");
        when(members.findByWorkspaceAndUser(1L, 100L)).thenReturn(null);

        assertThrows(ImDeliveryTargetChangedException.class,
                () -> sink.deliverReply(conversation(), "答复", EXTERNAL_ID));

        verifyNoInteractions(manager);
    }

    @Test
    void longReplyIsSplitOnCharacterBoundaries() {
        String content = "中文😀".repeat(4_000);
        arrange(System.currentTimeMillis(), 3, 3, 4, 1, "ENABLED");
        when(manager.respondStream(eq(ImChannelPurpose.GROUP_CHAT), eq("bot"), eq("req-1"),
                anyString(), anyString(), eq(true))).thenReturn(WecomSendResult.SENT);

        sink.deliverReply(conversation(), content, EXTERNAL_ID);

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(manager, atLeast(2)).respondStream(eq(ImChannelPurpose.GROUP_CHAT), eq("bot"),
                eq("req-1"), anyString(), captor.capture(), eq(true));
        List<String> chunks = captor.getAllValues();
        assertEquals(content, String.join("", chunks));
        for (String chunk : chunks) {
            assertTrue(chunk.getBytes(StandardCharsets.UTF_8).length
                    <= WecomChannelSink.MAX_CONTENT_BYTES);
            assertFalse(chunk.isEmpty());
        }
    }

    @Test
    void retryableFirstChunkCanBeRetried() {
        String content = "中".repeat(10_000);
        arrange(System.currentTimeMillis(), 3, 3, 4, 1, "ENABLED");
        when(manager.respondStream(eq(ImChannelPurpose.GROUP_CHAT), eq("bot"), eq("req-1"),
                anyString(), anyString(), eq(true))).thenReturn(WecomSendResult.RETRYABLE);

        assertThrows(ImDeliveryRetryableException.class,
                () -> sink.deliverReply(conversation(), content, EXTERNAL_ID));
        verify(manager, times(1)).respondStream(any(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void rejectedFirstChunkFailsWithoutRetry() {
        arrange(System.currentTimeMillis(), 3, 3, 4, 1, "ENABLED");
        when(manager.respondStream(eq(ImChannelPurpose.GROUP_CHAT), eq("bot"), eq("req-1"),
                anyString(), anyString(), eq(true))).thenReturn(WecomSendResult.REJECTED);

        assertThrows(ImDeliveryFailedException.class,
                () -> sink.deliverReply(conversation(), "答复", EXTERNAL_ID));
    }

    @Test
    void failureAfterFirstChunkIsUnknownAndNeverResentWholeContent() {
        String content = "中".repeat(10_000);
        arrange(System.currentTimeMillis(), 3, 3, 4, 1, "ENABLED");
        when(manager.respondStream(eq(ImChannelPurpose.GROUP_CHAT), eq("bot"), eq("req-1"),
                anyString(), anyString(), eq(true))).thenReturn(WecomSendResult.SENT, WecomSendResult.UNKNOWN);

        assertThrows(ImDeliveryUnknownException.class,
                () -> sink.deliverReply(conversation(), content, EXTERNAL_ID));
        verify(manager, times(2)).respondStream(any(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void progressAndFinalReplyUpdateTheSameStream() {
        arrange(System.currentTimeMillis(), 3, 3, 4, 1, "ENABLED");
        turns.findByExternalMsgId(1L, EXTERNAL_ID).setStatus("PROCESSING");
        when(manager.isConnectionOwner(ImChannelPurpose.GROUP_CHAT)).thenReturn(true);
        when(manager.respondStream(any(), anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(WecomSendResult.SENT);

        sink.deliverProgress(conversation(), EXTERNAL_ID, "已收到消息", 0);
        sink.deliverReply(conversation(), "答复", EXTERNAL_ID);

        var order = inOrder(manager);
        order.verify(manager).respondStream(ImChannelPurpose.GROUP_CHAT, "bot", "req-1",
                "aw-msg-1-0", "已收到消息", false);
        order.verify(manager).respondStream(ImChannelPurpose.GROUP_CHAT, "bot", "req-1",
                "aw-msg-1-0", "答复", true);
    }

    @Test
    void terminalTurnNeverReopensTheProgressStream() {
        arrange(System.currentTimeMillis(), 3, 3, 4, 1, "ENABLED");
        when(manager.isConnectionOwner(ImChannelPurpose.GROUP_CHAT)).thenReturn(true);
        for (String status : List.of("SUCCESS", "FAILED", "CANCELED")) {
            turns.findByExternalMsgId(1L, EXTERNAL_ID).setStatus(status);
            sink.deliverProgress(conversation(), EXTERNAL_ID, "迟到进度", 0);
        }
        verify(manager, never()).respondStream(any(), anyString(), anyString(), anyString(),
                anyString(), anyBoolean());
    }

    @Test
    void blankProgressIsIgnored() {
        arrange(System.currentTimeMillis(), 3, 3, 4, 1, "ENABLED");
        turns.findByExternalMsgId(1L, EXTERNAL_ID).setStatus("PROCESSING");
        when(manager.isConnectionOwner(ImChannelPurpose.GROUP_CHAT)).thenReturn(true);

        assertDoesNotThrow(() -> sink.deliverProgress(conversation(), EXTERNAL_ID, null, 0));
        assertDoesNotThrow(() -> sink.deliverProgress(conversation(), EXTERNAL_ID, "  ", 0));

        verify(manager, never()).respondStream(any(), anyString(), anyString(), anyString(),
                anyString(), anyBoolean());
    }

    @Test
    void failureAndCancellationUseSafeTerminalText() {
        arrange(System.currentTimeMillis(), 3, 3, 4, 1, "ENABLED");
        when(manager.respondStream(any(), anyString(), anyString(), anyString(), anyString(), eq(true)))
                .thenReturn(WecomSendResult.SENT);
        turns.findByExternalMsgId(1L, EXTERNAL_ID).setStatus("FAILED");
        sink.deliverReply(conversation(), "内部错误 token=不应发送", EXTERNAL_ID);
        verify(manager).respondStream(eq(ImChannelPurpose.GROUP_CHAT), eq("bot"), eq("req-1"),
                eq("aw-msg-1-0"), eq("本次处理失败，任务已停止。请稍后重试或联系管理员查看执行记录。"), eq(true));

        turns.findByExternalMsgId(1L, EXTERNAL_ID).setStatus("CANCELED");
        sink.deliverReply(conversation(), "工具未完成的内部输出", EXTERNAL_ID);
        verify(manager).respondStream(ImChannelPurpose.GROUP_CHAT, "bot", "req-1",
                "aw-msg-1-0", "本次处理已取消。", true);
    }

    @Test
    void progressWithoutOwnershipOrWithChangedRouteIsNotSent() {
        arrange(System.currentTimeMillis(), 3, 4, 4, 1, "ENABLED");
        turns.findByExternalMsgId(1L, EXTERNAL_ID).setStatus("PROCESSING");
        sink.deliverProgress(conversation(), EXTERNAL_ID, "进度", 0);
        when(manager.isConnectionOwner(ImChannelPurpose.GROUP_CHAT)).thenReturn(true);
        assertThrows(ImDeliveryTargetChangedException.class,
                () -> sink.deliverProgress(conversation(), EXTERNAL_ID, "进度", 0));

        verify(manager, never()).respondStream(any(), anyString(), anyString(), anyString(),
                anyString(), anyBoolean());
    }

    @Test
    void utf8SplitNeverBreaksSurrogatePairs() {
        String value = "a😀b";
        List<String> parts = WecomChannelSink.splitUtf8(value, 4);

        assertEquals(value, String.join("", parts));
        for (String part : parts) {
            assertTrue(part.getBytes(StandardCharsets.UTF_8).length <= 4);
            assertFalse(Character.isLowSurrogate(part.charAt(0)));
        }
    }

    private void arrange(long receivedAt, long sourceBindingRouteVersion,
                         long bindingRouteVersion, long configRouteVersion, int configEnabled,
                         String bindingStatus) {
        WecomSourceContext source = new WecomSourceContext(5L, "bot", "chat-1", "msg-1", "u1",
                "req-1", receivedAt, 100L,
                ImRouteSnapshot.shared("WECOM", 5L, 9L, sourceBindingRouteVersion,
                        4, 1L, 7L, "chat-1", 2, receivedAt));
        AgentConversationTurnDO turn = new AgentConversationTurnDO();
        turn.setId(11L);
        turn.setTenantId(1L);
        turn.setConversationId(2L);
        turn.setSourceContext(write(source));
        when(turns.findByExternalMsgId(1L, EXTERNAL_ID)).thenReturn(turn);

        WecomRobotBindingDO binding = new WecomRobotBindingDO();
        binding.setId(5L);
        binding.setTenantId(1L);
        binding.setPlatformConfigId(9L);
        binding.setGroupId("chat-1");
        binding.setAgentId(7L);
        binding.setRouteVersion(bindingRouteVersion);
        binding.setStatus(bindingStatus);
        binding.setCreatorId(100L);
        when(bindings.find(1L, 5L)).thenReturn(binding);
        WorkspaceMemberDO admin = new WorkspaceMemberDO();
        admin.setIsDeleted(0);
        admin.setStatus(0);
        when(members.findByWorkspaceAndUser(1L, 100L)).thenReturn(admin);

        PlatformImChannelConfigDO config = new PlatformImChannelConfigDO();
        config.setId(9L);
        config.setProvider("WECOM");
        config.setPurpose("GROUP_CHAT");
        config.setEnabled(configEnabled);
        config.setAppKey("bot");
        config.setRouteVersion(configRouteVersion);
        when(imConfigs.selectedProviderOrNull()).thenReturn("WECOM");
        when(imConfigs.findGroupChat("WECOM")).thenReturn(config);
        when(imConfigs.findGroupChatById(9L)).thenReturn(config);
    }

    private AgentConversationDO conversation() {
        AgentConversationDO conv = new AgentConversationDO();
        conv.setId(2L);
        conv.setTenantId(1L);
        conv.setAgentId(7L);
        conv.setChannel("WECOM");
        conv.setChannelConversationId("5:chat-1");
        return conv;
    }

    private String write(WecomSourceContext source) {
        try {
            return json.writeValueAsString(source);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
