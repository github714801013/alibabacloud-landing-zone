package com.aliyun.autowonder.setting;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.setting.dto.AiCollaborationDefaultsVO;
import com.aliyun.autowonder.setting.dto.UpdateAiCollaborationDefaultsRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/current/ai-defaults")
public class AiCollaborationDefaultsController {

    private final AiCollaborationDefaultsService defaultsService;

    public AiCollaborationDefaultsController(AiCollaborationDefaultsService defaultsService) {
        this.defaultsService = defaultsService;
    }

    @GetMapping
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY,
            action = "查看 AI 协作默认值", actionKey = "access.action.view_ai_collaboration_defaults")
    public Result<AiCollaborationDefaultsVO> get() {
        return Result.ok(defaultsService.get(
                AutoWonderContext.get().getCurrentWorkspaceId(),
                AutoWonderContext.get().getUserId()));
    }

    @PutMapping
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE,
            action = "更新 AI 协作默认值", actionKey = "access.action.update_ai_collaboration_defaults")
    public Result<Void> update(@RequestBody UpdateAiCollaborationDefaultsRequest request) {
        defaultsService.update(request,
                AutoWonderContext.get().getCurrentWorkspaceId(),
                AutoWonderContext.get().getUserId());
        return Result.ok(null);
    }
}
