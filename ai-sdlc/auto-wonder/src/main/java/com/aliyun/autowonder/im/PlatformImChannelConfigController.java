package com.aliyun.autowonder.im;

import com.aliyun.autowonder.access.SystemAdminService;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.im.dto.GroupChatConfigVO;
import com.aliyun.autowonder.im.dto.PlatformImChannelConfigVO;
import com.aliyun.autowonder.im.dto.PlatformImSelectionVO;
import com.aliyun.autowonder.im.dto.SelectImChannelRequest;
import com.aliyun.autowonder.im.dto.UpdateDingTalkChannelRequest;
import com.aliyun.autowonder.im.dto.UpdateGroupChatConfigRequest;
import com.aliyun.autowonder.integration.wecom.WecomConnectionStatusService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/platform/im-channels")
public class PlatformImChannelConfigController {
    private final PlatformImChannelConfigService configService;
    private final SystemAdminService systemAdminService;
    private final ImChannelRuntimeStatusService statusService;
    private final WecomConnectionStatusService wecomStatusService;

    public PlatformImChannelConfigController(
            PlatformImChannelConfigService configService,
            SystemAdminService systemAdminService) {
        this(configService, systemAdminService, null, null);
    }

    public PlatformImChannelConfigController(
            PlatformImChannelConfigService configService,
            SystemAdminService systemAdminService,
            ImChannelRuntimeStatusService statusService) {
        this(configService, systemAdminService, statusService, null);
    }

    @Autowired
    public PlatformImChannelConfigController(
            PlatformImChannelConfigService configService,
            SystemAdminService systemAdminService,
            ImChannelRuntimeStatusService statusService,
            WecomConnectionStatusService wecomStatusService) {
        this.configService = configService;
        this.systemAdminService = systemAdminService;
        this.statusService = statusService;
        this.wecomStatusService = wecomStatusService;
    }

    @GetMapping
    public Result<List<PlatformImChannelConfigVO>> list() {
        List<PlatformImChannelConfigVO> channels =
                configService.list(AutoWonderContext.get().getUserId());
        for (PlatformImChannelConfigVO channel : channels) {
            if ("WECOM".equals(channel.getProvider())) {
                channel.setConnectionStatus(connectionStatus(ImChannelPurpose.NOTIFY));
            }
        }
        return Result.ok(channels);
    }

    @PutMapping("/feishu")
    public Result<PlatformImChannelConfigVO> updateFeishu(
            @Valid @RequestBody UpdateDingTalkChannelRequest request) {
        systemAdminService.requireSystemAdmin(AutoWonderContext.get().getUserId(), "配置协作通知", "access.admin.im_denied");
        return Result.ok(configService.update(AutoWonderContext.get().getUserId(), "FEISHU", request));
    }

    @PutMapping("/dingtalk")
    public Result<PlatformImChannelConfigVO> updateDingTalk(
            @Valid @RequestBody UpdateDingTalkChannelRequest request) {
        systemAdminService.requireSystemAdmin(AutoWonderContext.get().getUserId(), "配置协作通知", "access.admin.im_denied");
        return Result.ok(configService.updateDingTalk(
                AutoWonderContext.get().getUserId(), request));
    }

    @PutMapping("/wecom")
    public Result<PlatformImChannelConfigVO> updateWecom(
            @Valid @RequestBody UpdateDingTalkChannelRequest request) {
        systemAdminService.requireSystemAdmin(AutoWonderContext.get().getUserId(), "配置协作通知", "access.admin.im_denied");
        PlatformImChannelConfigVO result = configService.update(
                AutoWonderContext.get().getUserId(), "WECOM", request);
        result.setConnectionStatus(connectionStatus(ImChannelPurpose.NOTIFY));
        return Result.ok(result);
    }

    @GetMapping("/selection")
    public Result<PlatformImSelectionVO> selection() {
        return Result.ok(configService.selection());
    }

    @PutMapping("/selection")
    public Result<PlatformImSelectionVO> selectProvider(
            @Valid @RequestBody SelectImChannelRequest request) {
        systemAdminService.requireSystemAdmin(AutoWonderContext.get().getUserId(), "配置协作渠道", "access.admin.im_selection_denied");
        return Result.ok(configService.selectProvider(AutoWonderContext.get().getUserId(),
                request.getProvider(), request.getExpectedGeneration()));
    }

    @GetMapping("/{provider}/group-chat")
    public Result<GroupChatConfigVO> groupChat(@PathVariable String provider) {
        systemAdminService.requireSystemAdmin(AutoWonderContext.get().getUserId(), "配置共享群聊机器人", "access.admin.im_group_chat_denied");
        return Result.ok(withStatus(configService.groupChat(provider)));
    }

    @PutMapping("/{provider}/group-chat")
    public Result<GroupChatConfigVO> updateGroupChat(@PathVariable String provider,
                                                     @Valid @RequestBody UpdateGroupChatConfigRequest request) {
        systemAdminService.requireSystemAdmin(AutoWonderContext.get().getUserId(), "配置共享群聊机器人", "access.admin.im_group_chat_denied");
        return Result.ok(withStatus(configService.updateGroupChat(
                AutoWonderContext.get().getUserId(), provider, request)));
    }

    private GroupChatConfigVO withStatus(GroupChatConfigVO vo) {
        if ("WECOM".equals(vo.getProvider())) {
            vo.setConnectionStatus(connectionStatus(ImChannelPurpose.GROUP_CHAT));
        }
        if (statusService == null) {
            vo.setStatus(ImChannelRuntimeStatusService.Status.DISABLED.name());
            return vo;
        }
        ImChannelRuntimeStatusService.RuntimeStatus status =
                statusService.status(vo.getProvider());
        vo.setStatus(status.status().name());
        vo.setDrainingCount(status.drainingCount());
        vo.setFailedCount(status.failedCount());
        vo.setUnknownCount(status.unknownCount());
        return vo;
    }

    private String connectionStatus(ImChannelPurpose purpose) {
        return wecomStatusService == null ? null : wecomStatusService.status(purpose).name();
    }
}
