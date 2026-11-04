package com.aliyun.autowonder.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.im.PlatformImChannelConfigDao;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.aliyun.autowonder.notification.dto.UpdatePrefRequest;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationImPreferenceTest {
    @AfterEach void cleanup() { AutoWonderContext.destroy(); }

    @Test void rejectsUnselectedPreferencesBeforeWritingAnyRows() {
        var prefs = mock(NotifyPrefDao.class);
        var controller = controller(prefs);
        var valid = new UpdatePrefRequest.PrefItem(); valid.setType("WORKITEM_ASSIGNED"); valid.setFeishu(true);
        var invalid = new UpdatePrefRequest.PrefItem(); invalid.setType("REVIEW_REQUIRED"); invalid.setDingtalk(true);
        var request = new UpdatePrefRequest(); request.setItems(List.of(valid, invalid));
        assertThrows(BizException.class, () -> controller.updatePrefs(request));
        verifyNoInteractions(prefs);
    }

    @Test void persistsFeishuPreferenceSeparatelyAndHidesInactiveDingTalkPreference() {
        var prefs = mock(NotifyPrefDao.class);
        var controller = controller(prefs);
        var item = new UpdatePrefRequest.PrefItem(); item.setType("WORKITEM_ASSIGNED"); item.setFeishu(true); item.setInApp(true);
        var request = new UpdatePrefRequest(); request.setItems(List.of(item));
        controller.updatePrefs(request);
        verify(prefs).insert(argThat(p -> p.getFeishu() == 1 && p.getDingtalk() == 0
                && p.getWecom() == 0 && p.getTenantId() == 1L));
        var row = new NotifyPrefDO(); row.setId(10L); row.setType("WORKITEM_ASSIGNED"); row.setDingtalk(1); row.setFeishu(1);
        when(prefs.listByUser(1L, 2L)).thenReturn(List.of(row));
        var result = controller.listPrefs().getData().get(0);
        assertTrue(result.isFeishu()); assertFalse(result.isDingtalk());
        when(prefs.findByUserAndType(1L, 2L, item.getType())).thenReturn(row);
        controller.updatePrefs(request);
        verify(prefs).update(10L, 1, 0, 1, 0);
    }

    @Test void persistsWecomPreferenceOnlyForTheSelectedProvider() {
        var prefs = mock(NotifyPrefDao.class);
        var controller = controller(prefs, "WECOM");
        var item = new UpdatePrefRequest.PrefItem();
        item.setType("WORKITEM_ASSIGNED"); item.setWecom(true); item.setInApp(true);
        var request = new UpdatePrefRequest(); request.setItems(List.of(item));
        controller.updatePrefs(request);
        verify(prefs).insert(argThat(p -> p.getWecom() == 1 && p.getInApp() == 1
                && p.getDingtalk() == 0 && p.getFeishu() == 0));
        var row = new NotifyPrefDO(); row.setId(11L); row.setType("WORKITEM_ASSIGNED");
        row.setDingtalk(0); row.setFeishu(0); row.setWecom(1);
        when(prefs.listByUser(1L, 2L)).thenReturn(List.of(row));
        var result = controller.listPrefs().getData().get(0);
        assertTrue(result.isWecom()); assertFalse(result.isDingtalk()); assertFalse(result.isFeishu());
        when(prefs.findByUserAndType(1L, 2L, item.getType())).thenReturn(row);
        controller.updatePrefs(request);
        verify(prefs).update(11L, 1, 0, 0, 1);
    }

    @Test void rejectsWecomPreferenceWhenAnotherProviderIsSelected() {
        var prefs = mock(NotifyPrefDao.class);
        var controller = controller(prefs, "FEISHU");
        var item = new UpdatePrefRequest.PrefItem(); item.setType("WORKITEM_ASSIGNED"); item.setWecom(true);
        var request = new UpdatePrefRequest(); request.setItems(List.of(item));
        assertThrows(BizException.class, () -> controller.updatePrefs(request));
        verifyNoInteractions(prefs);
    }

    private NotificationController controller(NotifyPrefDao prefs) {
        return controller(prefs, "FEISHU");
    }

    private NotificationController controller(NotifyPrefDao prefs, String selectedProvider) {
        AutoWonderContext.get().setCurrentWorkspaceId(1L); AutoWonderContext.get().setUserId(2L);
        var dao = mock(PlatformImChannelConfigDao.class); when(dao.selectedProvider()).thenReturn(selectedProvider);
        var configs = new PlatformImChannelConfigService(dao, mock(SecretCrypto.class), new ObjectMapper());
        return new NotificationController(mock(NotificationDao.class), prefs, mock(NotifyService.class), configs);
    }
}