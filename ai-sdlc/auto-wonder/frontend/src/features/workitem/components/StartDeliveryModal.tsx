import { i18n } from '@/shared/i18n';
import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { useTranslation } from 'react-i18next';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Modal, Form, Select, DatePicker, message } from 'antd';
import type { Dayjs } from 'dayjs';
import { listSquads, getSquadMembers } from '@/features/squad/api';
import type { SquadMember } from '@/features/squad/api';
import { ApiError } from '@/shared/types/common';
import { useAssignWorkitem } from '../hooks';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import {
  readClarificationPrefill,
  writeClarificationPrefill,
  clearClarificationPrefill,
} from '../clarification/prefill';
import { getAiCollaborationDefaults } from '@/features/settings/api';
import {
  mergeDeliveryDefaults,
  readLocalDeliveryDefaults,
  resolveDeliverySelection,
} from '@/features/settings/aiDefaults';

interface StartDeliveryModalProps {
  open: boolean;
  workitemId: number | string;
  /** When true the workitem already has an SDLC bound; this is a re-assign, so the SDLC picker is hidden. */
  hasSdlc?: boolean;
  onClose: () => void;
}

function createRestartToken(): string {
  const webCrypto = globalThis.crypto;
  if (typeof webCrypto?.randomUUID === 'function') {
    return webCrypto.randomUUID();
  }
  return `restart-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}

export function StartDeliveryModal({ open, workitemId, hasSdlc, onClose }: StartDeliveryModalProps) {
  const { t } = useTranslation('workitem');
  const [form] = Form.useForm();
  useLocalizedValidation(form, open);
  const assignMut = useAssignWorkitem();
  const accessCommand = useAccessCommand();
  const queryClient = useQueryClient();
  const squadId = Form.useWatch('squadId', form);
  // One token per modal session: duplicate submits and network retries of this
  // reassignment converge onto one restart round server-side; reopening the modal
  // and reassigning again is an explicit new round.
  const restartTokenRef = useRef(createRestartToken());
  useEffect(() => {
    if (open) {
      restartTokenRef.current = createRestartToken();
    }
  }, [open]);

  // 本次选择 > 工单记忆 > 我的本地交付默认 > 交付空间默认。
  // 记忆命中时不再应用默认值；重新指派（hasSdlc）保持原有的不预填行为。
  const [memoryPrefill, setMemoryPrefill] = useState<ReturnType<typeof readClarificationPrefill>>(null);
  const [prefillChecked, setPrefillChecked] = useState(false);
  useEffect(() => {
    if (!open) {
      setMemoryPrefill(null);
      setPrefillChecked(false);
      return;
    }
    if (hasSdlc) {
      setMemoryPrefill(null);
      setPrefillChecked(true);
      return;
    }
    const prefill = readClarificationPrefill(workitemId);
    setMemoryPrefill(prefill);
    setPrefillChecked(true);
    if (prefill) form.setFieldsValue({ squadId: prefill.squadId, agentId: prefill.agentId });
  }, [open, hasSdlc, workitemId, form]);

  const aiDefaults = useQuery({
    queryKey: ['ai-collaboration-defaults'],
    queryFn: getAiCollaborationDefaults,
    staleTime: 5 * 60_000,
    enabled: open,
  });
  // 空间拥有人没有本地覆盖，其自身始终按空间默认解析。
  const localDeliveryDefaults = useMemo(
    () => (open && !aiDefaults.data?.owner ? readLocalDeliveryDefaults() : {}),
    [open, aiDefaults.data?.owner],
  );
  const deliveryDefaults = useMemo(
    () => (open
      ? mergeDeliveryDefaults(aiDefaults.data?.delivery, localDeliveryDefaults)
      : { defaultSquadId: null, defaultAgentId: null }),
    [open, aiDefaults.data, localDeliveryDefaults],
  );
  const localPairOverridden = localDeliveryDefaults.defaultSquadId !== undefined
    || localDeliveryDefaults.defaultAgentId !== undefined;
  const defaultPairComplete = deliveryDefaults.defaultSquadId != null
    && deliveryDefaults.defaultAgentId != null;
  // 本地默认组合必须仍是该小队的当前成员，且不能是平台数字人，才允许预选。
  const { data: defaultMembers, isPending: defaultMembersPending, fetchStatus: defaultMembersFetchStatus } = useQuery({
    queryKey: ['squad-members', 'delivery-default', deliveryDefaults.defaultSquadId],
    queryFn: () => getSquadMembers(deliveryDefaults.defaultSquadId as number),
    enabled: open && !hasSdlc && localPairOverridden && defaultPairComplete && prefillChecked && !memoryPrefill,
  });

  const { data: squads } = useQuery({
    queryKey: ['squads', 'delivery-start'],
    queryFn: () => listSquads({ pageNum: 1, pageSize: 100 }),
    enabled: open,
  });

  const userTouchedRef = useRef(false);
  useEffect(() => {
    if (open) userTouchedRef.current = false;
  }, [open]);
  useEffect(() => {
    if (!open || hasSdlc || !prefillChecked || memoryPrefill || userTouchedRef.current) return;
    if (!aiDefaults.isSuccess) return;
    // 只有本地档需要按成员校验；空间档的合法性由服务端 valid 判定。
    if (localPairOverridden && defaultPairComplete
        && defaultMembersPending && defaultMembersFetchStatus !== 'idle') return;
    const localValid = !!defaultMembers?.some((item) => item.agentId === deliveryDefaults.defaultAgentId
      && item.agentKind !== 'PLATFORM');
    const resolved = resolveDeliverySelection({
      memory: null,
      local: localDeliveryDefaults,
      space: aiDefaults.data?.delivery,
      localValid,
    });
    if (resolved.selection) form.setFieldsValue(resolved.selection);
  }, [open, hasSdlc, prefillChecked, memoryPrefill, aiDefaults.isSuccess, aiDefaults.data,
    localDeliveryDefaults, localPairOverridden, defaultPairComplete, defaultMembers,
    defaultMembersPending, defaultMembersFetchStatus,
    deliveryDefaults.defaultAgentId, form]);
  const { data: members = [], isFetching: membersLoading } = useQuery({
    queryKey: ['squad-members', squadId],
    queryFn: () => getSquadMembers(squadId),
    enabled: open && !!squadId,
  });

  const handleOk = async () => {
    const values = await form.validateFields().catch(() => null);
    if (!values) return;
    const scheduledStartAt = values.scheduledStartAt
      ? (values.scheduledStartAt as Dayjs).toISOString()
      : undefined;
    accessCommand('READ_WRITE', hasSdlc ? t('start.reassignCommand') : t('start.deliveryCommand'), async () => {
      try {
        await assignMut.mutateAsync({
          id: workitemId,
          assigneeRef: values.agentId,
          squadId: values.squadId,
          scheduledStartAt,
          restartToken: restartTokenRef.current,
        });
        // The restart itself is asynchronous (stop old executions, then start the
        // new round); its live progress shows in the delivery recovery panel.
        if (hasSdlc) {
          message.success(i18n.t(scheduledStartAt ? 'workitem:start.reassignedScheduled' : 'workitem:start.reassigned'));
        } else {
          message.success(i18n.t('workitem:start.started'));
        }
        queryClient.invalidateQueries({ queryKey: ['workitem-recovery', String(workitemId)] });
        if (!hasSdlc) {
          clearClarificationPrefill(workitemId);
        }
        form.resetFields();
        onClose();
      } catch (err) {
        message.error(err instanceof ApiError ? err.message : i18n.t('workitem:start.failed'));
      }
    });
  };

  return (
    <Modal
      title={hasSdlc ? t('start.reassign') : t('start.title')}
      open={open}
      onOk={handleOk}
      onCancel={() => {
        form.resetFields();
        onClose();
      }}
      confirmLoading={assignMut.isPending}
      okText={hasSdlc ? t('start.reassign') : t('start.title')}
      cancelText={t('schedule.cancelEdit')}
      destroyOnHidden
    >
      <Form form={form} layout="vertical">
        <Form.Item name="squadId" label={t('start.squad')} rules={[{ required: true, message: t('start.squadRequired') }]}>
          <Select
            placeholder={t('start.squadPlaceholder')}
            options={(squads?.list || []).map((s) => ({ value: s.id, label: s.name }))}
            onChange={() => {
              userTouchedRef.current = true;
              form.setFieldValue('agentId', undefined);
            }}
          />
        </Form.Item>
        <Form.Item name="agentId" label={t('start.agent')} rules={[{ required: true, message: t('start.agentRequired') }]}>
          <Select
            placeholder={squadId ? t('start.memberPlaceholder') : t('start.chooseSquadFirst')}
            disabled={!squadId}
            loading={membersLoading}
            options={members
              .filter((member: SquadMember) => member.agentKind !== 'PLATFORM')
              .map((member: SquadMember) => ({
                value: member.agentId,
                label: member.roleCode ? `${member.agentName} (${member.roleCode})` : member.agentName,
              }))}
            onChange={(value: number) => {
              userTouchedRef.current = true;
              if (squadId) writeClarificationPrefill(workitemId, { squadId, agentId: value });
            }}
          />
        </Form.Item>
        <Form.Item name="scheduledStartAt" label={t('start.scheduledTime')}>
          <DatePicker
            showTime
            style={{ width: '100%' }}
            placeholder={t('start.timePlaceholder')}
            disabledDate={(current) => !!current && current.isBefore(new Date(), 'minute')}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
}
