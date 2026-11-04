package com.aliyun.autowonder.integration.wecom;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;
import java.util.List;

@RestController
@RequestMapping("/api/integrations/wecom/bindings")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "管理企业微信绑定",
        actionKey = "access.action.manage_wecom_bindings")
public class WecomBindingController {
    public record View(Long id, Long platformConfigId, String groupId, Long routeVersion,
                       Long agentId, Long creatorId, String status, Integer version, Date lastSuccessAt,
                       String lastError) {
    }

    private final WecomBindingService service;
    private final PlatformImChannelConfigService imConfigs;

    public WecomBindingController(WecomBindingService service,
                                  PlatformImChannelConfigService imConfigs) {
        this.service = service;
        this.imConfigs = imConfigs;
    }

    @GetMapping
    public Result<List<View>> list() {
        return Result.ok(service.list(tenant()).stream().map(this::view).toList());
    }

    @PostMapping
    public Result<View> create(@RequestBody WecomBindingService.Request req) {
        imConfigs.requireSelected("WECOM");
        return Result.ok(view(service.create(tenant(), AutoWonderContext.get().getUserId(), req)));
    }

    @PutMapping("/{id}")
    public Result<View> update(@PathVariable Long id,
                               @RequestBody WecomBindingService.Request req) {
        return Result.ok(view(service.update(tenant(), AutoWonderContext.get().getUserId(), id, req)));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(tenant(), id);
        return Result.ok(null);
    }

    private View view(WecomRobotBindingDO row) {
        return new View(row.getId(), row.getPlatformConfigId(), row.getGroupId(),
                row.getRouteVersion() == null ? 0L : row.getRouteVersion(), row.getAgentId(),
                row.getCreatorId(), row.getStatus(), row.getVersion(), row.getLastSuccessAt(),
                row.getLastError());
    }

    private Long tenant() {
        return AutoWonderContext.get().getCurrentWorkspaceId();
    }
}
