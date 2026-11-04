import { formatDateTime } from '@/shared/i18n/format';
import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { i18n } from '@/shared/i18n';
import { useTranslation } from 'react-i18next';
import { PlusOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { Alert, Button, Card, Checkbox, Drawer, Form, Input, Popconfirm, Select, Space, Tag, Typography, message } from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { listAgents } from '@/features/agent/api';
import { listMembers } from '@/features/settings/api';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { EllipsisText } from '@/shared/ui/EllipsisText';
import {
  GROUP_CHAT_PUBLIC_QUERY_KEY,
  getPublicGroupChatConfig,
} from '@/features/platform/brandingApi';
import { GroupIdGuide } from '../GroupIdGuide';
import { GroupBindingDetailDrawer, type GroupBindingDetailModel } from '../GroupBindingDetailDrawer';
import {
  deleteWecomBinding,
  listWecomBindings,
  saveWecomBinding,
  type WecomBinding,
  type WecomBindingRequest,
} from './wecomApi';

const statusLabelKeys = new Map([['ENABLED', 'statusEnabled'], ['DISABLED', 'statusDisabled']] as const);

export function WecomBindingPanel() {
  const { t } = useTranslation('integration');
  const runWithAccess = useAccessCommand();
  const client = useQueryClient();
  const [open, setOpen] = useState(false);
  const [record, setRecord] = useState<WecomBinding>();
  const [detail, setDetail] = useState<WecomBinding | null>(null);
  const [conflictError, setConflictError] = useState<unknown>(null);
  const [form] = Form.useForm<WecomBindingRequest>();
  useLocalizedValidation(form, open);
  const bindings = useQuery({ queryKey: ['wecom-bindings'], queryFn: listWecomBindings, refetchInterval: 15000 });
  const agents = useQuery({ queryKey: ['agents-for-binding'], queryFn: () => listAgents({ page: 1, size: 200 }) });
  // 委托主体是创建绑定的管理员，详情按需解析用户名。
  const members = useQuery({
    queryKey: ['workspace-members'],
    queryFn: listMembers,
    enabled: detail !== null,
  });
  // 新建依赖平台群聊机器人就绪；未就绪时后端会拒绝创建，这里提前禁用并说明原因。
  const platform = useQuery({ queryKey: GROUP_CHAT_PUBLIC_QUERY_KEY, queryFn: getPublicGroupChatConfig });
  const platformBlocked = Boolean(platform.data)
    && (platform.data?.provider !== 'WECOM' || !platform.data?.desiredEnabled || !platform.data?.ready);
  const refresh = () => client.invalidateQueries({ queryKey: ['wecom-bindings'] });

  function showRecord(value: WecomBinding) {
    setRecord(value);
    setConflictError(null);
    form.resetFields();
    form.setFieldsValue({ groupId: value.groupId, agentId: value.agentId, status: value.status, authorizationConfirmed: false });
    setOpen(true);
  }

  /** 新建与改绑/重新启用需要显式委托确认；仅切换为停用不需要。 */
  const requiresAuthorization = (values: WecomBindingRequest): boolean => {
    if (!record) return true;
    return (values.groupId ?? '') !== record.groupId
      || values.agentId !== record.agentId
      || (values.status === 'ENABLED' && record.status !== 'ENABLED');
  };

  const save = useMutation({
    mutationFn: (values: WecomBindingRequest) => saveWecomBinding(record?.id, {
      groupId: values.groupId,
      agentId: values.agentId,
      status: values.status,
      version: record?.version,
      authorizationConfirmed: values.authorizationConfirmed,
    }),
    onSuccess: () => {
      refresh();
      message.success(i18n.t('integration:wecomSaved'));
      setOpen(false);
      setDetail(null);
    },
    onError: (error: Error) => {
      setConflictError(error);
      message.error(error.message || i18n.t('integration:saveFailed'));
    },
  });

  const remove = useMutation({
    mutationFn: deleteWecomBinding,
    onSuccess: () => {
      setDetail(null);
      refresh();
      message.success(i18n.t('integration:bindingDeleted'));
    },
    onError: (error: Error) => message.error(error.message || i18n.t('integration:deleteFailed')),
  });

  function edit(value?: WecomBinding) {
    runWithAccess('ADMIN', value ? t('editWecom') : t('newWecom'), () => {
      setDetail(null);
      if (value) {
        showRecord(value);
        return;
      }
      setRecord(undefined);
      form.resetFields();
      form.setFieldsValue({ status: 'ENABLED', authorizationConfirmed: false });
      setOpen(true);
    });
  }

  function submit() {
    runWithAccess('ADMIN', t('saveWecom'), async () => {
      try {
        const values = await form.validateFields();
        save.mutate(values);
      } catch {
        // 校验错误由表单展示。
      }
    });
  }

  function agentLabel(id: number) {
    const agent = agents.data?.find((item) => item.id === id);
    return agent ? agent.name : `#${id}`;
  }

  function delegationSubject(value: WecomBinding): string {
    if (value.creatorId == null) return t('notAvailable');
    const creator = members.data?.find((item) => item.userId === value.creatorId);
    return creator?.username || `#${value.creatorId}`;
  }

  function toDetailModel(value: WecomBinding): GroupBindingDetailModel {
    return {
      bindingMode: 'SHARED',
      groupId: value.groupId,
      agentLabel: agentLabel(value.agentId),
      robotIdentity: t('wecomRobotIdentity', { id: value.platformConfigId ?? '—' }),
      transportLabel: t('wecomTransport'),
      enabled: value.status === 'ENABLED',
      diagnostics: [
        { label: t('wecomDelegationSubject'), value: delegationSubject(value) },
        { label: t('lastProcessed'), value: value.lastSuccessAt ? formatDateTime(value.lastSuccessAt, { dateStyle: 'medium', timeStyle: 'medium' }) : t('noHistory') },
        { label: t('lastError'), value: value.lastError || t('notAvailable') },
      ],
    };
  }

  return <Card title={t('wecomTitle')} extra={
    <Button
      type="primary"
      icon={<PlusOutlined aria-hidden="true" />}
      aria-label={t('newWecom')}
      // 平台机器人未就绪时无法创建，加载中同样先禁用，避免保存后才发现配置不可用。
      disabled={platform.isLoading || platformBlocked}
      onClick={() => edit()}
    >{t('newWecom')}</Button>
  }>
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert type="info" showIcon message={t('wecomBot')} description={t('wecomDescription')} />
      {platformBlocked && (
        <Alert
          type="warning"
          showIcon
          message={t('wecomBindingBlocked')}
          description={platform.data?.desiredEnabled ? t('runtimeErrorHint') : t('runtimeDisabledHint')}
        />
      )}
      {bindings.isError && <Alert type="error" message={t('wecomFailed')} description={(bindings.error as Error).message} action={<Button onClick={() => bindings.refetch()}>{t('retry')}</Button>} />}
      <Table<WecomBinding> rowKey="id" dataSource={bindings.data ?? []} loading={bindings.isLoading}
        locale={{ emptyText: t('wecomEmpty') }} scroll={{ x: 900 }} columns={[
          { title: t('groupChatGroupId'), dataIndex: 'groupId', width: 280, render: (groupId: string) => (
            <EllipsisText tooltip={groupId} style={{ maxWidth: 270 }}>
              <Typography.Text code>{groupId}</Typography.Text>
            </EllipsisText>
          ) },
          { title: t('agent'), dataIndex: 'agentId', width: 220, render: (id: number) => {
            const label = agentLabel(id);
            return <EllipsisText tooltip={label}>{label}</EllipsisText>;
          } },
          { title: t('status'), dataIndex: 'status', width: 100, render: (status: WecomBinding['status']) => {
            const key = statusLabelKeys.get(status);
            return <Tag color={status === 'ENABLED' ? 'green' : 'default'}>{key ? t(key) : status}</Tag>;
          } },
          { title: t('actions'), key: 'actions', width: 180, fixed: 'right', render: (_, row) => <Space size={4}>
            <Button type="link" size="small" onClick={() => setDetail(row)}>{t('detail')}</Button>
            <Button type="link" size="small" onClick={() => edit(row)}>{t('edit')}</Button>
            <Popconfirm
              title={t('confirmDeleteBinding')}
              description={t('deleteHelp')}
              okText={t('deleteBinding')}
              cancelText={t('cancel')}
              okButtonProps={{ danger: true }}
              onConfirm={() => runWithAccess('ADMIN', t('deleteBinding'), () => remove.mutate(row.id))}
            >
              <Button type="link" size="small" danger>{t('delete')}</Button>
            </Popconfirm>
          </Space> },
        ]} />
      <Drawer closable={{ 'aria-label': t('close') }} title={record ? t('editWecom') : t('newWecom')} open={open} onClose={() => setOpen(false)} width={560}
        extra={<Button type="primary" aria-label={t('save')} loading={save.isPending} onClick={submit}>{t('save')}</Button>}>
        <Form form={form} layout="vertical">
          <Alert type="info" showIcon style={{ marginBottom: 16 }} message={t('wecomSharedHint')} description={t('wecomDelegation')} />
          <GroupIdGuide provider="WECOM" />
          <Form.Item name="groupId" label={t('groupChatGroupId')} rules={[{ required: true, whitespace: true, message: t('requiredGroupId') }]}>
            <Input placeholder={t('wecomGroupIdPlaceholder')} maxLength={200} />
          </Form.Item>
          <Form.Item name="agentId" label={t('linkedAgent')} rules={[{ required: true, message: t('selectAgent') }]}>
            <Select showSearch optionFilterProp="label" loading={agents.isLoading} options={(agents.data ?? []).map(a => ({ label: a.name, value: a.id }))} />
          </Form.Item>
          <Form.Item name="status" label={t('bindingStatus')}><Select options={[{ label: t('enabled'), value: 'ENABLED' }, { label: t('disabled'), value: 'DISABLED' }]} /></Form.Item>
          <Alert type="warning" showIcon style={{ marginBottom: 16 }} message={t('wecomGroupPublic')} />
          <Form.Item
            name="authorizationConfirmed"
            valuePropName="checked"
            rules={[({ getFieldValue }) => ({
              validator: (_, value) => {
                const needs = requiresAuthorization({
                  groupId: getFieldValue('groupId'),
                  agentId: getFieldValue('agentId'),
                  status: getFieldValue('status'),
                });
                return !needs || value
                  ? Promise.resolve()
                  : Promise.reject(new Error(t('wecomAuthorizationRequired')));
              },
            })]}
          >
            <Checkbox>{t('wecomAuthorization')}</Checkbox>
          </Form.Item>
          {agents.isError && <Alert type="error" message={t('agentsFailed')} />}
          {conflictError != null && <Alert type="error" showIcon message={t('saveFailed')} description={conflictError instanceof Error ? conflictError.message : undefined} />}
        </Form>
      </Drawer>
      <GroupBindingDetailDrawer
        open={detail !== null}
        model={detail ? toDetailModel(detail) : null}
        onClose={() => setDetail(null)}
        onEdit={() => { if (detail) edit(detail); }}
        onDelete={() => { if (detail) remove.mutate(detail.id); }}
        deleteLoading={remove.isPending}
      />
    </Space>
  </Card>;
}
