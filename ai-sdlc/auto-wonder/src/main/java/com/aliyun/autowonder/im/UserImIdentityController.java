package com.aliyun.autowonder.im;

import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.im.dto.ImTestStatusVO;
import com.aliyun.autowonder.im.dto.UpdateUserImIdentityRequest;
import com.aliyun.autowonder.im.dto.UserImIdentityVO;
import com.aliyun.autowonder.im.notification.ImTestNotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/users/me/im-identities")
public class UserImIdentityController {
    private final UserImIdentityService identityService;
    private final ImTestNotificationService testNotifications;

    @Autowired
    public UserImIdentityController(UserImIdentityService identityService,
                                    ImTestNotificationService testNotifications) {
        this.identityService = identityService;
        this.testNotifications = testNotifications;
    }

    @GetMapping
    public Result<List<UserImIdentityVO>> list() {
        return Result.ok(identityService.list(AutoWonderContext.get().getUserId()));
    }

    @PutMapping("/feishu")
    public Result<UserImIdentityVO> updateFeishu(@Valid @RequestBody UpdateUserImIdentityRequest request) {
        return Result.ok(identityService.update(AutoWonderContext.get().getUserId(), "FEISHU", request.getExternalUserId()));
    }

    @PostMapping("/feishu/test")
    public Result<Void> testFeishu() {
        identityService.sendTest(AutoWonderContext.get().getUserId(), "FEISHU");
        return Result.ok(null);
    }

    @PutMapping("/dingtalk")
    public Result<UserImIdentityVO> updateDingTalk(
            @Valid @RequestBody UpdateUserImIdentityRequest request) {
        return Result.ok(identityService.update(
                AutoWonderContext.get().getUserId(),
                ImProviderType.DINGTALK.getKey(),
                request.getExternalUserId()));
    }

    @PostMapping("/dingtalk/test")
    public Result<Void> testDingTalk() {
        identityService.sendTest(
                AutoWonderContext.get().getUserId(),
                ImProviderType.DINGTALK.getKey());
        return Result.ok(null);
    }

    @PutMapping("/wecom")
    public Result<UserImIdentityVO> updateWecom(@Valid @RequestBody UpdateUserImIdentityRequest request) {
        return Result.ok(identityService.update(
                AutoWonderContext.get().getUserId(),
                ImProviderType.WECOM.getKey(),
                request.getExternalUserId()));
    }

    /** 单连接渠道由通知工人异步发送，立即返回受理状态，客户端有界轮询查询终态。 */
    @PostMapping("/wecom/test")
    public Result<ImTestStatusVO> testWecom() {
        return Result.ok(toVO(testNotifications.send(currentTenantId(),
                AutoWonderContext.get().getUserId(), ImProviderType.WECOM.getKey())));
    }

    @GetMapping("/wecom/test")
    public Result<ImTestStatusVO> testWecomStatus() {
        return Result.ok(toVO(testNotifications.latest(
                AutoWonderContext.get().getUserId(), ImProviderType.WECOM.getKey())));
    }

    private static long currentTenantId() {
        Long workspaceId = AutoWonderContext.get().getCurrentWorkspaceId();
        // 个人页可能没有当前工作区；测试通知不依赖工作区，缺省按 0 记录。
        return workspaceId == null ? 0L : workspaceId;
    }

    private static ImTestStatusVO toVO(ImTestNotificationService.Status status) {
        ImTestStatusVO vo = new ImTestStatusVO();
        vo.setDeliveryKey(status.deliveryKey());
        vo.setStatus(status.status());
        return vo;
    }
}
