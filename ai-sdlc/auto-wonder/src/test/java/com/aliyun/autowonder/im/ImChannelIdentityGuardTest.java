package com.aliyun.autowonder.im;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.integration.dingtalk.DingtalkRobotBindingDO;
import com.aliyun.autowonder.integration.dingtalk.DingtalkRobotBindingDao;
import com.aliyun.autowonder.integration.feishu.FeishuBinding;
import com.aliyun.autowonder.integration.feishu.FeishuBindingDao;
import com.aliyun.autowonder.integration.wecom.WecomRobotBindingDO;
import com.aliyun.autowonder.integration.wecom.WecomRobotBindingDao;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImChannelIdentityGuardTest {
    private final PlatformImChannelConfigDao configDao = mock(PlatformImChannelConfigDao.class);
    private final DingtalkRobotBindingDao dingtalk = mock(DingtalkRobotBindingDao.class);
    private final FeishuBindingDao feishu = mock(FeishuBindingDao.class);
    private final WecomRobotBindingDao wecom = mock(WecomRobotBindingDao.class);
    private final ImChannelIdentityGuard guard =
            new ImChannelIdentityGuard(configDao, dingtalk, feishu, wecom);

    @Test
    void groupChatIdentityCannotReuseLegacyDingTalkRobot() {
        DingtalkRobotBindingDO row = new DingtalkRobotBindingDO();
        row.setTenantId(31L);
        when(dingtalk.findLegacyByAppKeyGlobal("app")).thenReturn(row);
        ImIdentityConflictException error = assertConflict(
                () -> guard.assertGroupChatIdentityAvailable("DINGTALK", "app", null));
        assertEquals(ImIdentityConflictReason.LEGACY_BINDING_OCCUPIED, error.getReason());
        assertEquals(java.util.List.of(31L), error.getOccupantTenantIds());

        reset(dingtalk);
        DingtalkRobotBindingDO robotRow = new DingtalkRobotBindingDO();
        robotRow.setTenantId(32L);
        when(dingtalk.findByRobotCodeGlobal("robot")).thenReturn(robotRow);
        ImIdentityConflictException robotError = assertConflict(
                () -> guard.assertGroupChatIdentityAvailable("DINGTALK", null, "robot"));
        assertEquals(ImIdentityConflictReason.LEGACY_BINDING_OCCUPIED, robotError.getReason());
        assertEquals(java.util.List.of(32L), robotError.getOccupantTenantIds());
    }

    @Test
    void legacyOccupancyWithoutReadableWorkspaceStillConflicts() {
        when(dingtalk.findLegacyByAppKeyGlobal("app")).thenReturn(new DingtalkRobotBindingDO());

        ImIdentityConflictException error = assertConflict(
                () -> guard.assertGroupChatIdentityAvailable("DINGTALK", "app", null));
        assertEquals(ImIdentityConflictReason.LEGACY_BINDING_OCCUPIED, error.getReason());
        assertTrue(error.getOccupantTenantIds().isEmpty());
    }

    @Test
    void groupChatIdentityPassesWhenLegacyLookupsAreEmptyOrValuesBlank() {
        guard.assertGroupChatIdentityAvailable("DINGTALK", "app", "robot");
        guard.assertGroupChatIdentityAvailable("DINGTALK", " ", null);
        verify(dingtalk).findLegacyByAppKeyGlobal("app");
        verify(dingtalk).findByRobotCodeGlobal("robot");
    }

    @Test
    void groupChatFeishuIdentityCannotReuseLegacyApp() {
        FeishuBinding row = new FeishuBinding();
        row.setTenantId(41L);
        when(feishu.findLegacyByAppIdGlobal("cli_app")).thenReturn(row);
        ImIdentityConflictException error = assertConflict(
                () -> guard.assertGroupChatIdentityAvailable("FEISHU", "cli_app", null));
        assertEquals(ImIdentityConflictReason.LEGACY_BINDING_OCCUPIED, error.getReason());
        assertEquals(java.util.List.of(41L), error.getOccupantTenantIds());

        guard.assertGroupChatIdentityAvailable("FEISHU", null, "ignored");
        verifyNoInteractions(dingtalk);
    }

    @Test
    void legacyIdentityCannotReuseGroupChatApplication() {
        PlatformImChannelConfigDO config = new PlatformImChannelConfigDO();
        config.setProvider("DINGTALK");
        config.setAppKey("shared-app");
        config.setRobotCode("shared-robot");
        when(configDao.findByProviderAndPurpose("DINGTALK", "GROUP_CHAT")).thenReturn(config);

        assertEquals(ImIdentityConflictReason.SHARED_IDENTITY_OCCUPIED,
                assertConflict(() -> guard.assertLegacyIdentityAvailable("DINGTALK", "shared-app", null)).getReason());
        assertEquals(ImIdentityConflictReason.SHARED_IDENTITY_OCCUPIED,
                assertConflict(() -> guard.assertLegacyIdentityAvailable("DINGTALK", null, "shared-robot")).getReason());
        guard.assertLegacyIdentityAvailable("DINGTALK", "legacy-app", "legacy-robot");
    }

    @Test
    void legacyFeishuIdentityCannotReuseGroupChatApp() {
        PlatformImChannelConfigDO config = new PlatformImChannelConfigDO();
        config.setProvider("FEISHU");
        config.setAppKey("cli_shared");
        when(configDao.findByProviderAndPurpose("FEISHU", "GROUP_CHAT")).thenReturn(config);

        assertConflict(() -> guard.assertLegacyIdentityAvailable("FEISHU", "cli_shared", null));
        guard.assertLegacyIdentityAvailable("FEISHU", "cli_other", null);
        guard.assertLegacyIdentityAvailable("FEISHU", null, "ignored");
    }

    @Test
    void legacyIdentityCheckWithoutGroupChatConfigIsNoOp() {
        guard.assertLegacyIdentityAvailable("DINGTALK", "app", "robot");
        verifyNoInteractions(dingtalk, feishu);
    }

    @Test
    void groupAvailabilityRejectsDifferentBindingButAllowsSameOne() {
        DingtalkRobotBindingDO row = new DingtalkRobotBindingDO();
        row.setId(11L);
        when(dingtalk.findSharedByConfigAndGroup(8L, "cid_group")).thenReturn(row);

        BizException error = assertThrows(BizException.class,
                () -> guard.assertGroupAvailable("DINGTALK", 8L, " cid_group ", null));
        assertEquals("10409", error.getCode());
        assertEquals("integration.group_already_bound", error.getMessageKey());

        guard.assertGroupAvailable("DINGTALK", 8L, "cid_group", 11L);
    }

    @Test
    void groupAvailabilityChecksFeishuAndShortCircuitsMissingInputs() {
        FeishuBinding row = new FeishuBinding();
        row.setId(12L);
        when(feishu.findSharedByConfigAndGroup(8L, "oc_group")).thenReturn(row);

        BizException error = assertThrows(BizException.class,
                () -> guard.assertGroupAvailable("FEISHU", 8L, "oc_group", null));
        assertEquals("10409", error.getCode());
        assertEquals("integration.group_already_bound", error.getMessageKey());
        guard.assertGroupAvailable("FEISHU", 8L, "oc_group", 12L);
        guard.assertGroupAvailable("FEISHU", null, "oc_group", null);
        guard.assertGroupAvailable("FEISHU", 8L, " ", null);
        guard.assertGroupAvailable("OTHER", 8L, "group", null);
        verify(feishu, times(2)).findSharedByConfigAndGroup(8L, "oc_group");
        verifyNoInteractions(dingtalk);
    }

    @Test
    void wecomHasNoLegacyBindingButStillChecksGroupOccupancy() {
        guard.assertGroupChatIdentityAvailable("WECOM", "bot-id", null);
        guard.assertLegacyIdentityAvailable("WECOM", "bot-id", null);

        verifyNoInteractions(dingtalk, feishu);

        WecomRobotBindingDO row = new WecomRobotBindingDO();
        row.setId(13L);
        when(wecom.findSharedByConfigAndGroup(8L, "group")).thenReturn(row);
        BizException error = assertThrows(BizException.class,
                () -> guard.assertGroupAvailable("WECOM", 8L, "group", null));
        assertEquals("integration.group_already_bound", error.getMessageKey());
        guard.assertGroupAvailable("WECOM", 8L, "group", 13L);
    }

    private static ImIdentityConflictException assertConflict(Runnable action) {
        ImIdentityConflictException error = assertThrows(ImIdentityConflictException.class, action::run);
        assertEquals("10409", error.getCode());
        return error;
    }
}
