package com.aliyun.autowonder.integration.wecom;

import com.aliyun.autowonder.conversation.AgentConversationService;
import com.aliyun.autowonder.conversation.PlatformConversationImageService;
import com.aliyun.autowonder.im.ImChannelAdmissionService;
import com.aliyun.autowonder.im.ImChannelPurpose;
import com.aliyun.autowonder.im.ImRouteSnapshot;
import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WecomInboundServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final WecomRobotBindingDao bindings = mock(WecomRobotBindingDao.class);
    private final AgentConversationService conversations = mock(AgentConversationService.class);
    private final PlatformImChannelConfigService imConfigs =
            mock(PlatformImChannelConfigService.class);
    private final ImChannelAdmissionService admission = mock(ImChannelAdmissionService.class);
    private final WecomBotConnectionManager manager = mock(WecomBotConnectionManager.class);
    private final WecomTurnFeedbackService feedback = mock(WecomTurnFeedbackService.class);
    private final WecomImageDownloader imageDownloader = mock(WecomImageDownloader.class);
    private final PlatformConversationImageService imageStore =
            mock(PlatformConversationImageService.class);
    private final WecomInboundService service = new WecomInboundService(bindings, conversations,
            admission, imConfigs, manager, json, null, feedback, imageDownloader, imageStore);

    @Test
    void validGroupTextSubmitsTurnWithRouteAndSenderSnapshot() throws Exception {
        arrangeConfig();
        WecomRobotBindingDO binding = arrangeBinding("ENABLED");
        when(bindings.findSharedByConfigAndGroup(9L, "chat-1")).thenReturn(binding);
        when(bindings.findGlobal(5L)).thenReturn(binding);

        service.handle(message("group", "text", "请处理", "alice", "msg-1", "bot"));

        ArgumentCaptor<String> context = ArgumentCaptor.forClass(String.class);
        verify(conversations).submitTurn(eq(1L), eq(7L), eq("WECOM"), eq("5:chat-1"),
                eq("请处理"), eq("WECOM:bot:msg-1"), context.capture());
        WecomSourceContext source = json.readValue(context.getValue(),
                WecomSourceContext.class);
        assertEquals("alice", source.senderUserId());
        assertEquals("req-1", source.reqId());
        assertEquals(100L, source.creatorId());
        assertEquals("chat-1", source.chatId());
        assertEquals("WECOM", source.route().provider());
        assertEquals(5L, source.route().bindingId());
        assertEquals(3L, source.route().bindingRouteVersion());
        assertEquals(4L, source.route().configRouteVersion());
        assertTrue(source.receivedAt() > 0);
        var order = inOrder(conversations, feedback);
        order.verify(conversations).submitTurn(eq(1L), eq(7L), eq("WECOM"), eq("5:chat-1"),
                eq("请处理"), eq("WECOM:bot:msg-1"), anyString());
        order.verify(feedback).received(1L, 7L, "5:chat-1", "WECOM:bot:msg-1");
        verify(bindings).health(binding, null);
    }

    @Test
    void twoSendersShareTheSameGroupConversation() {
        arrangeConfig();
        WecomRobotBindingDO binding = arrangeBinding("ENABLED");
        when(bindings.findSharedByConfigAndGroup(9L, "chat-1")).thenReturn(binding);
        when(bindings.findGlobal(5L)).thenReturn(binding);

        service.handle(message("group", "text", "第一句", "alice", "msg-1", "bot"));
        service.handle(message("group", "text", "第二句", "bob", "msg-2", "bot"));

        ArgumentCaptor<String> context = ArgumentCaptor.forClass(String.class);
        verify(conversations, times(2)).submitTurn(eq(1L), eq(7L), eq("WECOM"), eq("5:chat-1"),
                anyString(), anyString(), context.capture());
        try {
            assertEquals("alice", json.readValue(context.getAllValues().get(0),
                    WecomSourceContext.class).senderUserId());
            assertEquals("bob", json.readValue(context.getAllValues().get(1),
                    WecomSourceContext.class).senderUserId());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void duplicateExternalMessageIsIgnored() {
        arrangeConfig();
        WecomRobotBindingDO binding = arrangeBinding("ENABLED");
        when(bindings.findSharedByConfigAndGroup(9L, "chat-1")).thenReturn(binding);
        when(conversations.hasExternalMessage(1L, "WECOM:bot:msg-1")).thenReturn(true);

        service.handle(message("group", "text", "请处理", "alice", "msg-1", "bot"));

        verify(conversations, never()).submitTurn(anyLong(), anyLong(), anyString(),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void rejectedSubmissionDoesNotAcknowledgeReceipt() {
        arrangeConfig();
        WecomRobotBindingDO binding = arrangeBinding("ENABLED");
        when(bindings.findSharedByConfigAndGroup(9L, "chat-1")).thenReturn(binding);
        when(bindings.findGlobal(5L)).thenReturn(binding);
        doThrow(new IllegalStateException("受理失败")).when(conversations).submitTurn(
                anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyString());

        assertThrows(IllegalStateException.class,
                () -> service.handle(message("group", "text", "请处理", "alice", "msg-1", "bot")));

        verifyNoInteractions(feedback);
    }

    @Test
    void unknownGroupNeverStartsAnAgentTurn() {
        arrangeConfig();
        when(bindings.findSharedByConfigAndGroup(9L, "chat-1")).thenReturn(null);

        service.handle(message("group", "text", "请处理", "alice", "msg-1", "bot"));

        verify(conversations, never()).submitTurn(anyLong(), anyLong(), anyString(),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void disabledBindingNeverStartsAnAgentTurn() {
        arrangeConfig();
        WecomRobotBindingDO binding = arrangeBinding("DISABLED");
        when(bindings.findSharedByConfigAndGroup(9L, "chat-1")).thenReturn(binding);

        service.handle(message("group", "text", "请处理", "alice", "msg-1", "bot"));

        verify(conversations, never()).submitTurn(anyLong(), anyLong(), anyString(),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void unsupportedVoiceInBoundGroupGetsAnExplicitReply() {
        arrangeConfig();
        WecomRobotBindingDO binding = arrangeBinding("ENABLED");
        when(bindings.findSharedByConfigAndGroup(9L, "chat-1")).thenReturn(binding);
        when(admission.allowsManagement("WECOM")).thenReturn(true);
        when(manager.respondMarkdown(eq(ImChannelPurpose.GROUP_CHAT), eq("bot"), eq("req-1"),
                anyString())).thenReturn(WecomSendResult.SENT);

        service.handle(message("group", "voice", "", "alice", "msg-1", "bot"));

        verify(manager).respondMarkdown(eq(ImChannelPurpose.GROUP_CHAT), eq("bot"), eq("req-1"),
                contains("暂不支持语音"));
        verifyNoInteractions(conversations);
    }

    @Test
    void imageMessageIsStoredAndSubmittedWithDefaultPrompt() {
        arrangeConfig();
        WecomRobotBindingDO binding = arrangeBinding("ENABLED");
        when(bindings.findSharedByConfigAndGroup(9L, "chat-1")).thenReturn(binding);
        when(bindings.findGlobal(5L)).thenReturn(binding);
        when(imageDownloader.download("https://wwcdn.weixin.qq.com/a.png", "key"))
                .thenReturn(png());
        when(imageStore.upload(eq(1L), eq("wecom/5:chat-1"), any(byte[].class),
                eq("wecom-image"))).thenReturn(image("img-1"));
        when(imageStore.attach(anyString(), eq(1L), eq("wecom/5:chat-1"), eq(List.of("img-1"))))
                .thenAnswer(i -> i.getArgument(0));

        service.handle(imageMessage("msg-1", "https://wwcdn.weixin.qq.com/a.png", "key"));

        verify(conversations).submitTurn(eq(1L), eq(7L), eq("WECOM"), eq("5:chat-1"),
                eq("请查看并处理这张图片。"), eq("WECOM:bot:msg-1"), anyString());
    }

    @Test
    void mixedMessageKeepsTextAlongsideImages() {
        arrangeConfig();
        WecomRobotBindingDO binding = arrangeBinding("ENABLED");
        when(bindings.findSharedByConfigAndGroup(9L, "chat-1")).thenReturn(binding);
        when(bindings.findGlobal(5L)).thenReturn(binding);
        when(imageDownloader.download("https://wwcdn.weixin.qq.com/a.png", "key"))
                .thenReturn(png());
        when(imageStore.upload(eq(1L), eq("wecom/5:chat-1"), any(byte[].class),
                eq("wecom-image"))).thenReturn(image("img-1"));
        when(imageStore.attach(anyString(), eq(1L), eq("wecom/5:chat-1"), eq(List.of("img-1"))))
                .thenAnswer(i -> i.getArgument(0));

        service.handle(mixedMessage("msg-1", "看看这个", "https://wwcdn.weixin.qq.com/a.png",
                "key"));

        verify(conversations).submitTurn(eq(1L), eq(7L), eq("WECOM"), eq("5:chat-1"),
                eq("看看这个"), eq("WECOM:bot:msg-1"), anyString());
    }

    @Test
    void tooManyImagesReplyInsteadOfSubmitting() {
        arrangeConfig();
        WecomRobotBindingDO binding = arrangeBinding("ENABLED");
        when(bindings.findSharedByConfigAndGroup(9L, "chat-1")).thenReturn(binding);
        when(manager.respondMarkdown(eq(ImChannelPurpose.GROUP_CHAT), eq("bot"), eq("req-1"),
                anyString())).thenReturn(WecomSendResult.SENT);

        service.handle(mixedMessageWithImages("msg-1", 11));

        verify(manager).respondMarkdown(eq(ImChannelPurpose.GROUP_CHAT), eq("bot"), eq("req-1"),
                contains("一次最多支持 10 张图片"));
        verify(conversations, never()).submitTurn(anyLong(), anyLong(), anyString(),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void imageDownloadFailureRepliesInsteadOfSubmitting() {
        arrangeConfig();
        WecomRobotBindingDO binding = arrangeBinding("ENABLED");
        when(bindings.findSharedByConfigAndGroup(9L, "chat-1")).thenReturn(binding);
        when(imageDownloader.download(anyString(), anyString()))
                .thenThrow(new IllegalStateException("下载失败"));
        when(manager.respondMarkdown(eq(ImChannelPurpose.GROUP_CHAT), eq("bot"), eq("req-1"),
                anyString())).thenReturn(WecomSendResult.SENT);

        service.handle(imageMessage("msg-1", "https://wwcdn.weixin.qq.com/a.png", "key"));

        verify(manager).respondMarkdown(eq(ImChannelPurpose.GROUP_CHAT), eq("bot"), eq("req-1"),
                contains("图片接收失败"));
        verify(conversations, never()).submitTurn(anyLong(), anyLong(), anyString(),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void duplicateImageCallbackIsIgnoredBeforeDownload() {
        arrangeConfig();
        WecomRobotBindingDO binding = arrangeBinding("ENABLED");
        when(bindings.findSharedByConfigAndGroup(9L, "chat-1")).thenReturn(binding);
        when(conversations.hasExternalMessage(1L, "WECOM:bot:msg-1")).thenReturn(true);

        service.handle(imageMessage("msg-1", "https://wwcdn.weixin.qq.com/a.png", "key"));

        verifyNoInteractions(imageDownloader, imageStore);
        verify(conversations, never()).submitTurn(anyLong(), anyLong(), anyString(),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void nonTextAndSingleChatMessagesAreIgnored() {
        arrangeConfig();
        WecomRobotBindingDO binding = arrangeBinding("ENABLED");
        when(bindings.findSharedByConfigAndGroup(9L, "chat-1")).thenReturn(binding);

        service.handle(message("group", "voice", "", "alice", "msg-1", "bot"));
        service.handle(message("single", "text", "请处理", "alice", "msg-2", "bot"));

        verify(conversations, never()).submitTurn(anyLong(), anyLong(), anyString(),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void messageForAnotherBotIsIgnored() {
        arrangeConfig();
        WecomRobotBindingDO binding = arrangeBinding("ENABLED");
        when(bindings.findSharedByConfigAndGroup(9L, "chat-1")).thenReturn(binding);

        service.handle(message("group", "text", "请处理", "alice", "msg-1", "other-bot"));

        verify(conversations, never()).submitTurn(anyLong(), anyLong(), anyString(),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void groupInfoRepliesWithOnlyTheGroupId() {
        arrangeConfig();
        when(admission.allowsManagement("WECOM")).thenReturn(true);
        when(manager.respondMarkdown(eq(ImChannelPurpose.GROUP_CHAT), eq("bot"), eq("req-1"),
                anyString())).thenReturn(WecomSendResult.SENT);

        service.handle(message("group", "text", "群信息", "alice", "msg-1", "bot"));

        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(manager).respondMarkdown(eq(ImChannelPurpose.GROUP_CHAT), eq("bot"), eq("req-1"),
                reply.capture());
        assertTrue(reply.getValue().contains("群 ID：chat-1"));
        assertFalse(reply.getValue().contains("绑定状态"));
        verifyNoInteractions(conversations);
    }

    @Test
    void groupInfoCommandWithLeadingMentionReplies() {
        arrangeConfig();
        when(admission.allowsManagement("WECOM")).thenReturn(true);
        when(manager.respondMarkdown(eq(ImChannelPurpose.GROUP_CHAT), eq("bot"), eq("req-1"),
                anyString())).thenReturn(WecomSendResult.SENT);

        service.handle(message("group", "text", "@机器人 群信息", "alice", "msg-1", "bot"));

        verify(manager).respondMarkdown(eq(ImChannelPurpose.GROUP_CHAT), eq("bot"), eq("req-1"),
                contains("群 ID：chat-1"));
        verifyNoInteractions(conversations);
    }

    @Test
    void leadingMentionIsRemovedFromAgentText() {
        arrangeConfig();
        WecomRobotBindingDO binding = arrangeBinding("ENABLED");
        when(bindings.findSharedByConfigAndGroup(9L, "chat-1")).thenReturn(binding);
        when(bindings.findGlobal(5L)).thenReturn(binding);

        service.handle(message("group", "text", "@机器人 请处理", "alice", "msg-1", "bot"));

        verify(conversations).submitTurn(eq(1L), eq(7L), eq("WECOM"), eq("5:chat-1"),
                eq("请处理"), eq("WECOM:bot:msg-1"), anyString());
    }

    @Test
    void quotedTextIsIncludedInAgentInput() {
        arrangeConfig();
        WecomRobotBindingDO binding = arrangeBinding("ENABLED");
        when(bindings.findSharedByConfigAndGroup(9L, "chat-1")).thenReturn(binding);
        when(bindings.findGlobal(5L)).thenReturn(binding);
        JsonNode frame = message("group", "text", "请处理", "alice", "msg-1", "bot");
        ObjectNode quote = ((ObjectNode) frame.path("body")).putObject("quote");
        quote.put("msgtype", "text");
        quote.putObject("text").put("content", "被引用的原文");

        service.handle(frame);

        verify(conversations).submitTurn(eq(1L), eq(7L), eq("WECOM"), eq("5:chat-1"),
                eq("[引用内容: 被引用的原文]\n\n请处理"), eq("WECOM:bot:msg-1"), anyString());
    }

    @Test
    void quotedMixedTextIsIncludedInAgentInput() {
        arrangeConfig();
        WecomRobotBindingDO binding = arrangeBinding("ENABLED");
        when(bindings.findSharedByConfigAndGroup(9L, "chat-1")).thenReturn(binding);
        when(bindings.findGlobal(5L)).thenReturn(binding);
        JsonNode frame = message("group", "text", "请处理", "alice", "msg-1", "bot");
        ObjectNode quote = ((ObjectNode) frame.path("body")).putObject("quote");
        quote.put("msgtype", "mixed");
        var items = quote.putObject("mixed").putArray("msg_item");
        items.addObject().put("msgtype", "text").putObject("text").put("content", "第一段");
        items.addObject().put("msgtype", "text").putObject("text").put("content", "第二段");
        items.addObject().put("msgtype", "image").putObject("image")
                .put("url", "https://wwcdn.weixin.qq.com/quote.png");

        service.handle(frame);

        verify(conversations).submitTurn(eq(1L), eq(7L), eq("WECOM"), eq("5:chat-1"),
                eq("[引用内容: 第一段\n第二段]\n\n请处理"), eq("WECOM:bot:msg-1"), anyString());
    }

    @Test
    void imageMessageWithQuoteKeepsQuoteBeforePrompt() {
        arrangeConfig();
        WecomRobotBindingDO binding = arrangeBinding("ENABLED");
        when(bindings.findSharedByConfigAndGroup(9L, "chat-1")).thenReturn(binding);
        when(bindings.findGlobal(5L)).thenReturn(binding);
        when(imageDownloader.download("https://wwcdn.weixin.qq.com/a.png", "key"))
                .thenReturn(png());
        when(imageStore.upload(eq(1L), eq("wecom/5:chat-1"), any(byte[].class),
                eq("wecom-image"))).thenReturn(image("img-1"));
        when(imageStore.attach(anyString(), eq(1L), eq("wecom/5:chat-1"), eq(List.of("img-1"))))
                .thenAnswer(i -> i.getArgument(0));
        JsonNode frame = imageMessage("msg-1", "https://wwcdn.weixin.qq.com/a.png", "key");
        ObjectNode quote = ((ObjectNode) frame.path("body")).putObject("quote");
        quote.put("msgtype", "text");
        quote.putObject("text").put("content", "被引用的原文");

        service.handle(frame);

        verify(conversations).submitTurn(eq(1L), eq(7L), eq("WECOM"), eq("5:chat-1"),
                eq("[引用内容: 被引用的原文]\n\n请查看并处理这张图片。"),
                eq("WECOM:bot:msg-1"), anyString());
    }

    @Test
    void mixedMessageWithQuoteKeepsQuoteBeforeText() {
        arrangeConfig();
        WecomRobotBindingDO binding = arrangeBinding("ENABLED");
        when(bindings.findSharedByConfigAndGroup(9L, "chat-1")).thenReturn(binding);
        when(bindings.findGlobal(5L)).thenReturn(binding);
        when(imageDownloader.download("https://wwcdn.weixin.qq.com/a.png", "key"))
                .thenReturn(png());
        when(imageStore.upload(eq(1L), eq("wecom/5:chat-1"), any(byte[].class),
                eq("wecom-image"))).thenReturn(image("img-1"));
        when(imageStore.attach(anyString(), eq(1L), eq("wecom/5:chat-1"), eq(List.of("img-1"))))
                .thenAnswer(i -> i.getArgument(0));
        JsonNode frame = mixedMessage("msg-1", "看看这个", "https://wwcdn.weixin.qq.com/a.png",
                "key");
        ObjectNode quote = ((ObjectNode) frame.path("body")).putObject("quote");
        quote.put("msgtype", "text");
        quote.putObject("text").put("content", "被引用的原文");

        service.handle(frame);

        verify(conversations).submitTurn(eq(1L), eq(7L), eq("WECOM"), eq("5:chat-1"),
                eq("[引用内容: 被引用的原文]\n\n看看这个"), eq("WECOM:bot:msg-1"),
                anyString());
    }

    @Test
    void quotedTextKeepsLeadingMentionStripped() {
        arrangeConfig();
        WecomRobotBindingDO binding = arrangeBinding("ENABLED");
        when(bindings.findSharedByConfigAndGroup(9L, "chat-1")).thenReturn(binding);
        when(bindings.findGlobal(5L)).thenReturn(binding);
        JsonNode frame = message("group", "text", "@机器人 请处理", "alice", "msg-1", "bot");
        ObjectNode quote = ((ObjectNode) frame.path("body")).putObject("quote");
        quote.put("msgtype", "text");
        quote.putObject("text").put("content", "被引用的原文");

        service.handle(frame);

        verify(conversations).submitTurn(eq(1L), eq(7L), eq("WECOM"), eq("5:chat-1"),
                eq("[引用内容: 被引用的原文]\n\n请处理"), eq("WECOM:bot:msg-1"), anyString());
    }

    @Test
    void quotedGroupInfoCommandIsStillHandledAsCommand() {
        arrangeConfig();
        WecomRobotBindingDO binding = arrangeBinding("ENABLED");
        when(bindings.findSharedByConfigAndGroup(9L, "chat-1")).thenReturn(binding);
        when(admission.allowsManagement("WECOM")).thenReturn(true);
        when(manager.respondMarkdown(eq(ImChannelPurpose.GROUP_CHAT), eq("bot"), eq("req-1"),
                anyString())).thenReturn(WecomSendResult.SENT);
        JsonNode frame = message("group", "text", "/groupinfo", "alice", "msg-1", "bot");
        ObjectNode quote = ((ObjectNode) frame.path("body")).putObject("quote");
        quote.put("msgtype", "text");
        quote.putObject("text").put("content", "被引用的原文");

        service.handle(frame);

        verify(manager).respondMarkdown(eq(ImChannelPurpose.GROUP_CHAT), eq("bot"), eq("req-1"),
                contains("群 ID"));
        verifyNoInteractions(conversations);
    }

    @Test
    void unsupportedQuoteTypeDoesNotAlterAgentInput() {
        arrangeConfig();
        WecomRobotBindingDO binding = arrangeBinding("ENABLED");
        when(bindings.findSharedByConfigAndGroup(9L, "chat-1")).thenReturn(binding);
        when(bindings.findGlobal(5L)).thenReturn(binding);
        JsonNode frame = message("group", "text", "请处理", "alice", "msg-1", "bot");
        ObjectNode quote = ((ObjectNode) frame.path("body")).putObject("quote");
        quote.put("msgtype", "voice");

        service.handle(frame);

        verify(conversations).submitTurn(eq(1L), eq(7L), eq("WECOM"), eq("5:chat-1"),
                eq("请处理"), eq("WECOM:bot:msg-1"), anyString());
    }

    @Test
    void leadingMentionIsRemovedBeforeAnyWhitespace() {
        assertEquals("请处理", WecomInboundService.commandText("@机器人\n请处理"));
        assertEquals("请处理", WecomInboundService.commandText("@机器人\t请处理"));
        assertEquals("@机器人", WecomInboundService.commandText("@机器人"));
        assertEquals("", WecomInboundService.commandText(null));
    }

    @Test
    void admissionPersistsTheReReadBindingSnapshot() {
        arrangeConfig();
        WecomRobotBindingDO binding = arrangeBinding("ENABLED");
        when(bindings.findSharedByConfigAndGroup(9L, "chat-1")).thenReturn(binding);
        WecomRobotBindingDO current = arrangeBinding("ENABLED");
        current.setRouteVersion(5L);
        when(bindings.findGlobal(5L)).thenReturn(current);
        when(admission.admit(eq("WECOM"), any(Instant.class),
                any(ImChannelAdmissionService.ReceiptWriter.class))).thenAnswer(invocation -> {
                    ImChannelAdmissionService.ReceiptWriter writer = invocation.getArgument(2);
                    var context = new ImChannelAdmissionService.Context("WECOM", "WECOM", 9,
                            null, config());
                    ImRouteSnapshot snapshot = writer.persist(context);
                    return new ImChannelAdmissionService.Result(
                            ImChannelAdmissionService.Decision.ACCEPTED, "accepted", snapshot);
                });

        service.handle(message("group", "text", "请处理", "alice", "msg-1", "bot"));

        ArgumentCaptor<String> context = ArgumentCaptor.forClass(String.class);
        verify(conversations).submitTurn(eq(1L), eq(7L), eq("WECOM"), eq("5:chat-1"),
                eq("请处理"), eq("WECOM:bot:msg-1"), context.capture());
        try {
            WecomSourceContext source = json.readValue(context.getValue(),
                    WecomSourceContext.class);
            assertEquals(5L, source.route().bindingRouteVersion());
            assertEquals(9L, source.route().selectionGeneration());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void bindingChangedDuringAdmissionDoesNotSubmitTurn() {
        arrangeConfig();
        WecomRobotBindingDO binding = arrangeBinding("ENABLED");
        when(bindings.findSharedByConfigAndGroup(9L, "chat-1")).thenReturn(binding);
        when(bindings.findGlobal(5L)).thenReturn(arrangeBinding("DISABLED"));

        service.handle(message("group", "text", "请处理", "alice", "msg-1", "bot"));

        verify(conversations, never()).submitTurn(any(), any(), any(), any(), any(), any(),
                any());
    }

    private void arrangeConfig() {
        when(imConfigs.findGroupChat("WECOM")).thenReturn(config());
        lenient().when(admission.admit(eq("WECOM"), any(Instant.class),
                any(ImChannelAdmissionService.ReceiptWriter.class))).thenAnswer(invocation -> {
                    ImChannelAdmissionService.ReceiptWriter writer = invocation.getArgument(2);
                    var context = new ImChannelAdmissionService.Context("WECOM", "WECOM", 0,
                            null, config());
                    if (writer.alreadyReceived(context)) {
                        return new ImChannelAdmissionService.Result(
                                ImChannelAdmissionService.Decision.DUPLICATE, "duplicate", null);
                    }
                    ImRouteSnapshot snapshot = writer.persist(context);
                    if (snapshot == null) {
                        return new ImChannelAdmissionService.Result(
                                ImChannelAdmissionService.Decision.IGNORED, "not_admitted", null);
                    }
                    return new ImChannelAdmissionService.Result(
                            ImChannelAdmissionService.Decision.ACCEPTED, "accepted", snapshot);
                });
    }

    private PlatformImChannelConfigDO config() {
        PlatformImChannelConfigDO config = new PlatformImChannelConfigDO();
        config.setId(9L);
        config.setProvider("WECOM");
        config.setPurpose("GROUP_CHAT");
        config.setEnabled(1);
        config.setAppKey("bot");
        config.setRouteVersion(4L);
        return config;
    }

    private WecomRobotBindingDO arrangeBinding(String status) {
        WecomRobotBindingDO binding = new WecomRobotBindingDO();
        binding.setId(5L);
        binding.setTenantId(1L);
        binding.setPlatformConfigId(9L);
        binding.setGroupId("chat-1");
        binding.setAgentId(7L);
        binding.setRouteVersion(3L);
        binding.setStatus(status);
        binding.setCreatorId(100L);
        return binding;
    }

    private JsonNode message(String chatType, String msgType, String content, String user,
                             String msgId, String botId) {
        ObjectNode frame = json.createObjectNode();
        frame.put("cmd", WecomProtocol.CMD_MSG_CALLBACK);
        frame.putObject("headers").put("req_id", "req-1");
        ObjectNode body = frame.putObject("body");
        body.put("msgid", msgId);
        body.put("aibotid", botId);
        body.put("chatid", "chat-1");
        body.put("chattype", chatType);
        body.put("msgtype", msgType);
        body.putObject("from").put("userid", user);
        body.putObject("text").put("content", content);
        return frame;
    }

    private JsonNode imageMessage(String msgId, String url, String aesKey) {
        ObjectNode frame = frame(msgId, "image");
        ObjectNode image = ((ObjectNode) frame.path("body")).putObject("image");
        image.put("url", url);
        image.put("aeskey", aesKey);
        return frame;
    }

    private JsonNode mixedMessage(String msgId, String content, String url, String aesKey) {
        ObjectNode frame = frame(msgId, "mixed");
        ObjectNode mixed = ((ObjectNode) frame.path("body")).putObject("mixed");
        var items = mixed.putArray("msg_item");
        items.addObject().put("msgtype", "text").putObject("text").put("content", content);
        ObjectNode image = items.addObject().put("msgtype", "image").putObject("image");
        image.put("url", url);
        image.put("aeskey", aesKey);
        return frame;
    }

    private JsonNode mixedMessageWithImages(String msgId, int count) {
        ObjectNode frame = frame(msgId, "mixed");
        var items = ((ObjectNode) frame.path("body")).putObject("mixed").putArray("msg_item");
        for (int i = 0; i < count; i++) {
            ObjectNode image = items.addObject().put("msgtype", "image").putObject("image");
            image.put("url", "https://wwcdn.weixin.qq.com/" + i + ".png");
            image.put("aeskey", "key");
        }
        return frame;
    }

    private ObjectNode frame(String msgId, String msgType) {
        ObjectNode frame = json.createObjectNode();
        frame.put("cmd", WecomProtocol.CMD_MSG_CALLBACK);
        frame.putObject("headers").put("req_id", "req-1");
        ObjectNode body = frame.putObject("body");
        body.put("msgid", msgId);
        body.put("aibotid", "bot");
        body.put("chatid", "chat-1");
        body.put("chattype", "group");
        body.put("msgtype", msgType);
        body.putObject("from").put("userid", "alice");
        return frame;
    }

    private static byte[] png() {
        return new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    }

    private static PlatformConversationImageService.Image image(String id) {
        return new PlatformConversationImageService.Image(id, "wecom-image", "image/png", 8L,
                "https://oss.example/" + id);
    }
}
