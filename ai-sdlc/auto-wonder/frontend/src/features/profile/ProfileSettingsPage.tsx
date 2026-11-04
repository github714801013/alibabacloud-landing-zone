import { useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { PageHeading } from '@/shared/ui/PageHeading';
import { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Alert, Button, Card, Form, Input, Space, Tabs, Tag, Tooltip, Typography, message } from 'antd';
import { SaveOutlined, SendOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { McpTokenSettingsPanel } from '@/features/open-platform/McpTokenSettingsPanel';
import { BasicProfilePanel } from './BasicProfilePanel';
import { ChangePasswordPanel } from './ChangePasswordPanel';
import { DeactivationPanel } from './DeactivationPanel';
import { FederatedIdentitiesPanel } from './FederatedIdentitiesPanel';
import {
  USER_IM_IDENTITIES_QUERY_KEY,
  getMyWecomIdentityTest,
  listMyImIdentities,
  sendMyDingTalkIdentityTest,
  updateMyDingTalkIdentity,
  updateMyFeishuIdentity,
  sendMyFeishuIdentityTest,
  sendMyWecomIdentityTest,
  updateMyWecomIdentity,
  type UserImIdentity,
} from './profileApi';

const { Text } = Typography;

const DEFAULT_TAB = 'basic';

type ImIdentityFormValues = {
  externalUserId: string;
};

const EMPTY_IM_IDENTITY: UserImIdentity = {
  provider: 'DINGTALK',
  externalUserId: '',
  configured: false,
  platformReady: true,
  testAvailable: false,
};

const WECOM_TEST_STATUS_LABEL_KEYS = {
  QUEUED: 'im.testStatusQueued',
  SENT: 'im.testStatusSent',
  FAILED: 'im.testStatusFailed',
  UNKNOWN: 'im.testStatusUnknown',
  NONE: 'im.testStatusNone',
} as const;

const WECOM_TEST_STATUS_COLORS: Readonly<Record<string, string>> = {
  QUEUED: 'gold',
  SENT: 'green',
  FAILED: 'red',
  UNKNOWN: 'orange',
  NONE: 'default',
};

const WECOM_TEST_POLL_LIMIT = 20;

function getApiMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback;
}

function getImTestStatusText(hasIdentityRow: boolean, hasSavedIdentity: boolean, identity: UserImIdentity) {
  if (!hasIdentityRow || !hasSavedIdentity) {
    return i18n.t('profile:im.saveFirst');
  }
  if (!identity.platformReady) {
    return i18n.t('profile:im.robotUnavailable');
  }
  return i18n.t(identity.testAvailable ? 'profile:im.testAvailable' : 'profile:im.saveFirst');
}

export function ProfileSettingsPage() {
  const { t } = useTranslation('profile');
  const [searchParams, setSearchParams] = useSearchParams();
  const requestedTab = searchParams.get('tab');
  const activeTab = requestedTab === 'federated' || requestedTab === 'im' || requestedTab === 'mcp' || requestedTab === 'password' || requestedTab === 'deactivate'
    ? requestedTab
    : DEFAULT_TAB;
  const [form] = Form.useForm<ImIdentityFormValues>();
  const [wecomTestKey, setWecomTestKey] = useState<string | null>(null);
  const queryClient = useQueryClient();
  const hydratedRef = useRef(false);
  const identityQuery = useQuery({
    queryKey: USER_IM_IDENTITIES_QUERY_KEY,
    queryFn: listMyImIdentities,
    retry: false,
  });

  const identityRow = identityQuery.data?.[0];
  const provider = identityRow?.provider ?? 'DINGTALK';
  const isWecom = provider === 'WECOM';
  const providerLabel = provider === 'FEISHU' ? t('im.feishu') : provider === 'WECOM' ? t('im.wecom') : provider === 'DINGTALK' ? t('im.dingtalk') : provider;
  // 企业微信测试通知由通知工人异步投递，按 deliveryKey 有界轮询到终态；排队不等于送达。
  const wecomTestQuery = useQuery({
    queryKey: ['wecom-im-test-status', wecomTestKey],
    queryFn: getMyWecomIdentityTest,
    enabled: isWecom && Boolean(wecomTestKey),
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      if (status && status !== 'QUEUED' && status !== 'NONE') return false;
      // 成功与失败都计入上限，避免查询持续报错时无限轮询。
      const attempts = query.state.dataUpdateCount + query.state.errorUpdateCount;
      return attempts >= WECOM_TEST_POLL_LIMIT ? false : 1500;
    },
  });
  const wecomTestStatus = isWecom ? wecomTestQuery.data?.status ?? (wecomTestKey ? 'QUEUED' : null) : null;
  const wecomTestStatusKey = wecomTestStatus
    ? WECOM_TEST_STATUS_LABEL_KEYS[wecomTestStatus as keyof typeof WECOM_TEST_STATUS_LABEL_KEYS]
    : undefined;
  const hydratedProviderRef = useRef(provider);
  const hasIdentityRow = Boolean(identityRow);
  const identity = identityRow || EMPTY_IM_IDENTITY;
  const savedExternalUserId = identity.externalUserId || '';
  const currentExternalUserId = Form.useWatch('externalUserId', form) ?? savedExternalUserId;
  const normalizedCurrentExternalUserId = currentExternalUserId.trim();
  const normalizedSavedExternalUserId = savedExternalUserId.trim();
  const hasSavedIdentity = Boolean(identity.configured && normalizedSavedExternalUserId);
  const testBlockedByRobot = !identityQuery.isLoading && hasIdentityRow && !identity.platformReady;
  const testBlockedByIdentity =
    !normalizedCurrentExternalUserId ||
    !hasSavedIdentity ||
    normalizedCurrentExternalUserId !== normalizedSavedExternalUserId;

  useEffect(() => {
    if (identityQuery.data) {
      const fieldTouched = form.isFieldTouched('externalUserId');
      if (!hydratedRef.current || !fieldTouched || hydratedProviderRef.current !== provider) {
        hydratedProviderRef.current = provider;
        form.setFieldsValue({ externalUserId: savedExternalUserId });
        hydratedRef.current = true;
      }
    }
  }, [form, identityQuery.data, savedExternalUserId, provider]);

  useEffect(() => {
    if (!isWecom) setWecomTestKey(null);
  }, [isWecom]);

  const saveMutation = useMutation({
    mutationFn: async () => {
      const values = await form.validateFields();
      const update = provider === 'FEISHU'
        ? updateMyFeishuIdentity
        : provider === 'WECOM' ? updateMyWecomIdentity : updateMyDingTalkIdentity;
      return update({ externalUserId: values.externalUserId?.trim() || '' });
    },
    onSuccess: async () => {
      message.success(i18n.t('profile:im.saved'));
      await queryClient.invalidateQueries({ queryKey: USER_IM_IDENTITIES_QUERY_KEY });
    },
    onError: (error) => {
      message.error(getApiMessage(error, i18n.t('profile:im.saveFailed')));
    },
  });

  const testMutation = useMutation({
    mutationFn: async () => {
      if (provider === 'WECOM') {
        const started = await sendMyWecomIdentityTest();
        setWecomTestKey(started.deliveryKey);
        return started;
      }
      if (provider === 'FEISHU') await sendMyFeishuIdentityTest();
      else await sendMyDingTalkIdentityTest();
      return null;
    },
    onSuccess: () => {
      message.success(i18n.t(provider === 'WECOM' ? 'profile:im.testQueued' : 'profile:im.testSent'));
    },
    onError: (error) => {
      message.error(getApiMessage(error, i18n.t('profile:im.testFailed')));
    },
  });

  const testDisabled =
    identityQuery.isLoading ||
    saveMutation.isPending ||
    testMutation.isPending ||
    testBlockedByRobot ||
    testBlockedByIdentity;
  const testDisabledReason = testBlockedByRobot
    ? t('im.robotBlocked', { provider: providerLabel })
    : testBlockedByIdentity
      ? t('im.identityBlocked', { provider: providerLabel })
      : '';
  const testStatusText = getImTestStatusText(hasIdentityRow, hasSavedIdentity, identity);

  const imSettings = (
    <Card title={t('im.title')} styles={{ body: { padding: 18 } }}>
        {identityQuery.error ? (
          <Alert
            type="error"
            showIcon
            message={t('im.loadFailed')}
            description={getApiMessage(identityQuery.error, t('retryLater'))}
          />
        ) : (
          <Form
            form={form}
            layout="vertical"
            disabled={identityQuery.isLoading || saveMutation.isPending}
            initialValues={{ externalUserId: '' }}
            onFinish={() => saveMutation.mutate()}
          >
            <div style={{ display: 'grid', gap: 16 }}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16, flexWrap: 'wrap' }}>
                <div>
                  <Text strong>{providerLabel}</Text>
                  <div>
                    <Text type="secondary" style={{ fontSize: 12 }}>{t('im.hint', { provider: providerLabel })}</Text>
                  </div>
                </div>
                <Space size={[8, 8]} wrap>
                  <Tag color={hasSavedIdentity ? 'green' : 'default'}>{hasSavedIdentity ? t('im.enabled') : t('im.disabled')}</Tag>
                  <Tag color={identity.testAvailable ? 'green' : 'orange'}>
                    {testStatusText}
                  </Tag>
                  {isWecom && wecomTestStatus && wecomTestStatusKey && (
                    <Tag color={WECOM_TEST_STATUS_COLORS[wecomTestStatus] ?? 'default'}>
                      {t(wecomTestStatusKey)}
                    </Tag>
                  )}
                </Space>
              </div>

              {isWecom && <Alert type="info" showIcon message={t('im.wecomPrivateChat')} />}

              <Form.Item
                label={isWecom ? t('im.wecomLabel') : t('im.label', { provider: providerLabel })}
                name="externalUserId"
                extra={isWecom ? t('im.wecomHint') : provider === 'FEISHU' ? t('im.feishuHint') : t('im.dingtalkHint')}
              >
                <Input maxLength={256} placeholder={t('im.placeholder')} autoComplete="off" />
              </Form.Item>

              {testDisabledReason ? (
                <Alert type="info" showIcon message={testDisabledReason} />
              ) : null}

              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, flexWrap: 'wrap' }}>
                <Tooltip title={testDisabledReason || undefined}>
                  <Button aria-label={t('im.test')}
                    icon={<SendOutlined aria-hidden="true" />}
                    disabled={testDisabled}
                    loading={testMutation.isPending}
                    onClick={() => testMutation.mutate()}
                  >{t('im.test')}</Button>
                </Tooltip>
                <Button aria-label={t('save')} type="primary" htmlType="submit" icon={<SaveOutlined aria-hidden="true" />} loading={saveMutation.isPending}>{t('save')}</Button>
              </div>
            </div>
          </Form>
        )}
    </Card>
  );

  return (
    <div data-testid="profile-settings-shell">
      <PageHeading title={t('title')} description={t('description')} />

      <Tabs
        activeKey={activeTab}
        onChange={(key) => {
          const next = new URLSearchParams(searchParams);
          if (key === DEFAULT_TAB) {
            next.delete('tab');
          } else {
            next.set('tab', key);
          }
          setSearchParams(next, { replace: true });
        }}
        items={[
          { key: 'basic', label: t('basic.title'), children: <BasicProfilePanel /> },
          { key: 'im', label: t('im.title'), children: imSettings },
          { key: 'federated', label: t('federated.title'), children: <FederatedIdentitiesPanel /> },
          { key: 'mcp', label: t('tabs.mcp'), children: <McpTokenSettingsPanel /> },
          { key: 'password', label: t('password.submit'), children: <ChangePasswordPanel /> },
          { key: 'deactivate', label: t('deactivation.title'), children: <DeactivationPanel /> },
        ]}
      />
    </div>
  );
}
