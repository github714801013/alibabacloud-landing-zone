import { useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Alert, Button, Card, Form, Select, Space, Spin, Switch, Typography, message } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { i18n } from '@/shared/i18n';
import { listSquads, getSquadMembers } from '@/features/squad/api';
import type { SquadMember } from '@/features/squad/api';
import { getAiCollaborationDefaults, updateAiCollaborationDefaults } from './api';
import {
  clearLocalClarifyDefaults,
  clearLocalDeliveryDefaults,
  diffClarifyDefaults,
  diffDeliveryDefaults,
  hasClarifyLocalOverride,
  hasDeliveryLocalOverride,
  mergeClarifyDefaults,
  mergeDeliveryDefaults,
  readLocalClarifyDefaults,
  readLocalDeliveryDefaults,
  writeLocalClarifyDefaults,
  writeLocalDeliveryDefaults,
} from './aiDefaults';
import type { LocalClarifyDefaults, LocalDeliveryDefaults } from './aiDefaults';
import {
  BUILTIN_FIRST_TEMPLATE_ID,
  BUILTIN_RECLARIFY_TEMPLATE_ID,
  MAX_CUSTOM_TEMPLATES,
  buildCatalogDraft,
  isBuiltinTemplate,
  newCustomTemplateId,
  resolvePromptTemplates,
  serializeCatalogDraft,
  withBuiltinContent,
  withCustomTemplate,
  withoutBuiltinContent,
  withoutCustomTemplate,
  type CatalogDraft,
  type PromptTemplate,
} from './clarificationPromptTemplates';
import { ClarificationTemplateManager } from './ClarificationTemplateManager';
import type { TemplateFormValues } from './ClarificationTemplateManager';

interface DefaultsFormValues {
  clarifySquadId?: number;
  clarifyAgentId?: number;
  clarifyAutoSend?: boolean;
  clarifyFirstTemplateId?: string;
  clarifyReclarifyTemplateId?: string;
  deliverySquadId?: number;
  deliveryAgentId?: number;
}

function toSelectValue(value: number | null): number | undefined {
  return value ?? undefined;
}

export function AiCollaborationDefaultsPage() {
  const { t } = useTranslation('settings');
  const { t: tWorkitem } = useTranslation('workitem');
  const queryClient = useQueryClient();
  const [form] = Form.useForm<DefaultsFormValues>();
  const [localClarify, setLocalClarify] = useState<LocalClarifyDefaults>(readLocalClarifyDefaults);
  const [localDelivery, setLocalDelivery] = useState<LocalDeliveryDefaults>(readLocalDeliveryDefaults);
  const [localTemplateContents, setLocalTemplateContents] = useState<Record<string, string>>(
    () => readLocalClarifyDefaults().templateContents ?? {},
  );
  const [catalogDraft, setCatalogDraft] = useState<CatalogDraft | null>(null);
  const initialCatalogRef = useRef<CatalogDraft | null>(null);
  // 成员保存时与页面加载时的空间默认比较，避免后台刷新后把陈旧选择误存为本地覆盖。
  const initialTemplateDefaultsRef = useRef<{ first: string; reclarify: string } | null>(null);
  const initializedRef = useRef(false);

  const defaultsQuery = useQuery({
    queryKey: ['ai-collaboration-defaults'],
    queryFn: getAiCollaborationDefaults,
  });
  const squadsQuery = useQuery({
    queryKey: ['squads', 'ai-defaults'],
    queryFn: () => listSquads({ pageNum: 1, pageSize: 100 }),
  });

  const clarifySquadId = Form.useWatch('clarifySquadId', form);
  const deliverySquadId = Form.useWatch('deliverySquadId', form);
  const spaceClarifySquadId = defaultsQuery.data?.clarify.defaultSquadId ?? null;
  const spaceDeliverySquadId = defaultsQuery.data?.delivery.defaultSquadId ?? null;

  const clarifyMembersQuery = useQuery({
    queryKey: ['squad-members', clarifySquadId],
    queryFn: () => getSquadMembers(clarifySquadId!),
    enabled: clarifySquadId != null,
  });
  const deliveryMembersQuery = useQuery({
    queryKey: ['squad-members', deliverySquadId],
    queryFn: () => getSquadMembers(deliverySquadId!),
    enabled: deliverySquadId != null,
  });
  const spaceClarifyMembersQuery = useQuery({
    queryKey: ['squad-members', spaceClarifySquadId],
    queryFn: () => getSquadMembers(spaceClarifySquadId!),
    enabled: spaceClarifySquadId != null,
  });
  const spaceDeliveryMembersQuery = useQuery({
    queryKey: ['squad-members', spaceDeliverySquadId],
    queryFn: () => getSquadMembers(spaceDeliverySquadId!),
    enabled: spaceDeliverySquadId != null,
  });

  const space = defaultsQuery.data;
  const isOwner = space?.owner ?? false;
  const builtinTemplateTexts = useMemo(() => ({
    firstName: tWorkitem('clarification.builtinFirstTemplateName'),
    firstContent: tWorkitem('clarification.bootstrapPrompt', { workitemId: '{{workitemId}}' }),
    reclarifyName: tWorkitem('clarification.builtinReclarifyTemplateName'),
    reclarifyContent: tWorkitem('clarification.reclarifyPrompt', { workitemId: '{{workitemId}}' }),
  }), [tWorkitem]);

  useEffect(() => {
    if (!space || initializedRef.current) return;
    initializedRef.current = true;
    const clarify = isOwner ? space.clarify : mergeClarifyDefaults(space.clarify, localClarify);
    const delivery = isOwner ? space.delivery : mergeDeliveryDefaults(space.delivery, localDelivery);
    const initialCatalog = buildCatalogDraft(space.clarify.promptTemplates);
    initialCatalogRef.current = initialCatalog;
    setCatalogDraft(initialCatalog);
    const initialTemplates = resolvePromptTemplates(
      builtinTemplateTexts,
      isOwner ? initialCatalog : space.clarify.promptTemplates,
      isOwner ? {} : localClarify,
    );
    const spaceTemplates = resolvePromptTemplates(builtinTemplateTexts, space.clarify.promptTemplates, {});
    initialTemplateDefaultsRef.current = {
      first: spaceTemplates.firstDefault.id,
      reclarify: spaceTemplates.reclarifyDefault.id,
    };
    form.setFieldsValue({
      clarifySquadId: toSelectValue(clarify.defaultSquadId),
      clarifyAgentId: toSelectValue(clarify.defaultAgentId),
      clarifyAutoSend: clarify.autoSendFirstPrompt,
      clarifyFirstTemplateId: initialTemplates.firstDefault.id,
      clarifyReclarifyTemplateId: initialTemplates.reclarifyDefault.id,
      deliverySquadId: toSelectValue(delivery.defaultSquadId),
      deliveryAgentId: toSelectValue(delivery.defaultAgentId),
    });
  }, [space, isOwner, localClarify, localDelivery, builtinTemplateTexts, form]);

  const saveMut = useMutation({
    mutationFn: async () => {
      if (!space) return;
      const values = await form.validateFields();
      const clarify = {
        defaultSquadId: values.clarifySquadId ?? null,
        defaultAgentId: values.clarifyAgentId ?? null,
        autoSendFirstPrompt: Boolean(values.clarifyAutoSend),
      };
      const delivery = {
        defaultSquadId: values.deliverySquadId ?? null,
        defaultAgentId: values.deliveryAgentId ?? null,
      };
      const firstTemplateId = values.clarifyFirstTemplateId ?? BUILTIN_FIRST_TEMPLATE_ID;
      const reclarifyTemplateId = values.clarifyReclarifyTemplateId ?? BUILTIN_RECLARIFY_TEMPLATE_ID;
      if (space.owner) {
        const items = [
          { key: 'clarify.defaultSquadId', value: clarify.defaultSquadId == null ? '' : String(clarify.defaultSquadId) },
          { key: 'clarify.defaultAgentId', value: clarify.defaultAgentId == null ? '' : String(clarify.defaultAgentId) },
          { key: 'clarify.autoSendFirstPrompt', value: clarify.autoSendFirstPrompt ? 'true' : 'false' },
          { key: 'delivery.defaultSquadId', value: delivery.defaultSquadId == null ? '' : String(delivery.defaultSquadId) },
          { key: 'delivery.defaultAgentId', value: delivery.defaultAgentId == null ? '' : String(delivery.defaultAgentId) },
        ];
        const initialCatalog = initialCatalogRef.current ?? buildCatalogDraft(space.clarify.promptTemplates);
        const draft: CatalogDraft = {
          ...(catalogDraft ?? initialCatalog),
          defaults: { first: firstTemplateId, reclarify: reclarifyTemplateId },
        };
        const serialized = serializeCatalogDraft(draft);
        if (serialized !== serializeCatalogDraft(initialCatalog)) {
          items.push({ key: 'clarify.promptTemplates', value: serialized });
        }
        await updateAiCollaborationDefaults(items);
        initialCatalogRef.current = draft;
        return;
      }
      const spaceEffective = resolvePromptTemplates(builtinTemplateTexts, space.clarify.promptTemplates, {});
      const templateBaseline = initialTemplateDefaultsRef.current ?? {
        first: spaceEffective.firstDefault.id,
        reclarify: spaceEffective.reclarifyDefault.id,
      };
      const nextClarify: LocalClarifyDefaults = {
        ...diffClarifyDefaults(space.clarify, clarify),
        ...(firstTemplateId !== templateBaseline.first ? { firstTemplateId } : {}),
        ...(reclarifyTemplateId !== templateBaseline.reclarify
          ? { reclarifyTemplateId }
          : {}),
        ...(Object.keys(localTemplateContents).length > 0
          ? { templateContents: localTemplateContents }
          : {}),
      };
      const nextDelivery = diffDeliveryDefaults(space.delivery, delivery);
      if (hasClarifyLocalOverride(nextClarify)) writeLocalClarifyDefaults(nextClarify);
      else clearLocalClarifyDefaults();
      if (hasDeliveryLocalOverride(nextDelivery)) writeLocalDeliveryDefaults(nextDelivery);
      else clearLocalDeliveryDefaults();
      setLocalClarify(nextClarify);
      setLocalDelivery(nextDelivery);
    },
    onSuccess: () => {
      message.success(i18n.t('settings:aiDefaults.saved'));
      if (space?.owner) {
        queryClient.invalidateQueries({ queryKey: ['ai-collaboration-defaults'] });
      }
    },
    onError: (error: unknown) => {
      message.error(error instanceof Error ? error.message : i18n.t('settings:operationFailed'));
    },
  });

  const handleRestore = (section: 'clarify' | 'delivery') => {
    if (!space) return;
    if (section === 'clarify') {
      clearLocalClarifyDefaults();
      setLocalClarify({});
      setLocalTemplateContents({});
      const spaceEffective = resolvePromptTemplates(builtinTemplateTexts, space.clarify.promptTemplates, {});
      initialTemplateDefaultsRef.current = {
        first: spaceEffective.firstDefault.id,
        reclarify: spaceEffective.reclarifyDefault.id,
      };
      form.setFieldsValue({
        clarifySquadId: toSelectValue(space.clarify.defaultSquadId),
        clarifyAgentId: toSelectValue(space.clarify.defaultAgentId),
        clarifyAutoSend: space.clarify.autoSendFirstPrompt,
        clarifyFirstTemplateId: spaceEffective.firstDefault.id,
        clarifyReclarifyTemplateId: spaceEffective.reclarifyDefault.id,
      });
    } else {
      clearLocalDeliveryDefaults();
      setLocalDelivery({});
      form.setFieldsValue({
        deliverySquadId: toSelectValue(space.delivery.defaultSquadId),
        deliveryAgentId: toSelectValue(space.delivery.defaultAgentId),
      });
    }
    message.success(i18n.t('settings:aiDefaults.restored'));
  };

  if (defaultsQuery.isLoading || squadsQuery.isLoading) {
    return <Spin />;
  }
  if (defaultsQuery.isError || !space) {
    return <Alert type="error" message={t('aiDefaults.loadFailed')} />;
  }

  const squads = squadsQuery.data?.list ?? [];
  const squadName = (id: number | null) =>
    id == null ? t('aiDefaults.notSet') : squads.find((squad) => squad.id === id)?.name ?? t('aiDefaults.invalidDefault');
  const agentName = (members: SquadMember[] | undefined, id: number | null) =>
    id == null ? t('aiDefaults.notSet') : members?.find((member) => member.agentId === id)?.agentName
      ?? t('aiDefaults.invalidDefault');

  const agentOptions = (members: SquadMember[] | undefined, delivery: boolean) =>
    (members ?? [])
      .filter((member) => !delivery || member.agentKind !== 'PLATFORM')
      .map((member) => ({
        value: member.agentId,
        label: member.roleCode
          ? t('aiDefaults.agentOptionLabel', { name: member.agentName, role: member.roleCode })
          : member.agentName,
      }));

  const squadOptions = squads.map((squad) => ({ value: squad.id, label: squad.name }));
  const showClarifyRestore = !isOwner && hasClarifyLocalOverride(localClarify);
  const showDeliveryRestore = !isOwner && hasDeliveryLocalOverride(localDelivery);

  const clarifySquadExtra = !isOwner && localClarify.defaultSquadId !== undefined
    ? t('aiDefaults.spaceDefault', { value: squadName(space.clarify.defaultSquadId) })
    : undefined;
  const clarifyAgentExtra = !isOwner && localClarify.defaultAgentId !== undefined
    ? t('aiDefaults.spaceDefault', {
      value: agentName(spaceClarifyMembersQuery.data, space.clarify.defaultAgentId),
    })
    : undefined;
  const clarifySwitchExtra = !isOwner && localClarify.autoSendFirstPrompt !== undefined
    ? t('aiDefaults.spaceDefault', {
      value: space.clarify.autoSendFirstPrompt ? t('aiDefaults.on') : t('aiDefaults.off'),
    })
    : undefined;
  const deliverySquadExtra = !isOwner && localDelivery.defaultSquadId !== undefined
    ? t('aiDefaults.spaceDefault', { value: squadName(space.delivery.defaultSquadId) })
    : undefined;
  const deliveryAgentExtra = !isOwner && localDelivery.defaultAgentId !== undefined
    ? t('aiDefaults.spaceDefault', {
      value: agentName(spaceDeliveryMembersQuery.data, space.delivery.defaultAgentId),
    })
    : undefined;

  const effectiveTemplates = resolvePromptTemplates(
    builtinTemplateTexts,
    isOwner ? catalogDraft ?? space.clarify.promptTemplates : space.clarify.promptTemplates,
    isOwner ? {} : { templateContents: localTemplateContents },
  );
  const templateOptions = effectiveTemplates.templates.map((template) => ({
    value: template.id,
    label: isBuiltinTemplate(template)
      ? t('aiDefaults.template.optionBuiltin', { name: template.name })
      : t('aiDefaults.template.optionCustom', { name: template.name }),
  }));
  const customTemplateCount = effectiveTemplates.templates
    .filter((template) => !isBuiltinTemplate(template)).length;
  const canAddTemplate = customTemplateCount < MAX_CUSTOM_TEMPLATES;

  const handleAddTemplate = ({ name, content }: TemplateFormValues) => {
    if (!canAddTemplate) {
      message.warning(i18n.t('settings:aiDefaults.template.limitReached', { max: MAX_CUSTOM_TEMPLATES }));
      return;
    }
    setCatalogDraft((draft) => withCustomTemplate(
      draft ?? buildCatalogDraft(space.clarify.promptTemplates),
      { id: newCustomTemplateId(), name, content },
    ));
  };

  const handleUpdateTemplate = (templateId: string, { name, content }: TemplateFormValues) => {
    const template = effectiveTemplates.templates.find((item) => item.id === templateId);
    if (!template) return;
    if (!isOwner) {
      setLocalTemplateContents((contents) => ({ ...contents, [templateId]: content }));
      return;
    }
    setCatalogDraft((draft) => {
      const base = draft ?? buildCatalogDraft(space.clarify.promptTemplates);
      return isBuiltinTemplate(template)
        ? withBuiltinContent(base, templateId, content)
        : withCustomTemplate(base, { id: templateId, name, content });
    });
  };

  const handleDeleteTemplate = (templateId: string) => {
    setCatalogDraft((draft) => withoutCustomTemplate(
      draft ?? buildCatalogDraft(space.clarify.promptTemplates),
      templateId,
    ));
    if (form.getFieldValue('clarifyFirstTemplateId') === templateId) {
      form.setFieldValue('clarifyFirstTemplateId', BUILTIN_FIRST_TEMPLATE_ID);
    }
    if (form.getFieldValue('clarifyReclarifyTemplateId') === templateId) {
      form.setFieldValue('clarifyReclarifyTemplateId', BUILTIN_RECLARIFY_TEMPLATE_ID);
    }
    message.success(i18n.t('settings:aiDefaults.template.deleted'));
  };

  const handleRestoreTemplate = (template: PromptTemplate) => {
    if (!hasTemplateOverride(template)) return;
    if (isOwner) {
      if (!isBuiltinTemplate(template)) return;
      setCatalogDraft((draft) => withoutBuiltinContent(
        draft ?? buildCatalogDraft(space.clarify.promptTemplates),
        template.id,
      ));
    } else {
      setLocalTemplateContents((contents) => {
        const next = { ...contents };
        delete next[template.id];
        return next;
      });
    }
    message.success(i18n.t('settings:aiDefaults.template.restored'));
  };

  const canRestoreTemplate = (template: PromptTemplate) => {
    // 规格要求内置模板始终显示恢复入口；自定义模板仅成员有本地覆盖时显示。
    if (isBuiltinTemplate(template)) return true;
    return !isOwner && localTemplateContents[template.id] !== undefined;
  };

  const hasTemplateOverride = (template: PromptTemplate) => (isOwner
    ? catalogDraft?.builtin[template.id] !== undefined
    : localTemplateContents[template.id] !== undefined);

  const agentRequiredRule = (squadField: keyof DefaultsFormValues) => ({
    validator: (_: unknown, value: number | undefined) => {
      const squadId = form.getFieldValue(squadField);
      return squadId != null && value == null
        ? Promise.reject(new Error(t('aiDefaults.agentRequired')))
        : Promise.resolve();
    },
  });

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Alert type="info" message={isOwner ? t('aiDefaults.ownerHint') : t('aiDefaults.memberHint')} />
      <Form form={form} layout="vertical">
        <Card title={t('aiDefaults.clarifySection')} size="small" style={{ marginBottom: 16 }}>
          <Form.Item label={t('aiDefaults.defaultSquad')} name="clarifySquadId" extra={clarifySquadExtra}>
            <Select
              allowClear
              placeholder={t('aiDefaults.selectSquad')}
              options={squadOptions}
              onChange={() => form.setFieldValue('clarifyAgentId', undefined)}
            />
          </Form.Item>
          <Form.Item
            label={t('aiDefaults.defaultAgent')}
            name="clarifyAgentId"
            extra={clarifyAgentExtra}
            dependencies={['clarifySquadId']}
            rules={[agentRequiredRule('clarifySquadId')]}
          >
            <Select
              allowClear
              disabled={clarifySquadId == null}
              placeholder={clarifySquadId == null
                ? t('aiDefaults.selectSquadFirst')
                : t('aiDefaults.selectAgent')}
              options={agentOptions(clarifyMembersQuery.data, false)}
            />
          </Form.Item>
          <Form.Item label={t('aiDefaults.autoSend')} name="clarifyAutoSend" valuePropName="checked" extra={clarifySwitchExtra ?? t('aiDefaults.autoSendHint')}>
            <Switch />
          </Form.Item>
          <Form.Item label={t('aiDefaults.firstDefaultTemplate')} name="clarifyFirstTemplateId">
            <Select options={templateOptions} />
          </Form.Item>
          <Form.Item label={t('aiDefaults.reclarifyDefaultTemplate')} name="clarifyReclarifyTemplateId">
            <Select options={templateOptions} />
          </Form.Item>
          <div style={{ marginBottom: 16 }}>
            <Typography.Text type="secondary">{t('aiDefaults.templateList')}</Typography.Text>
            <ClarificationTemplateManager
              templates={effectiveTemplates.templates}
              isOwner={isOwner}
              canAdd={canAddTemplate}
              onAdd={handleAddTemplate}
              onUpdate={handleUpdateTemplate}
              onDelete={handleDeleteTemplate}
              onRestore={handleRestoreTemplate}
              canRestore={canRestoreTemplate}
            />
          </div>
          {showClarifyRestore && (
            <Button type="link" style={{ paddingInline: 0 }} onClick={() => handleRestore('clarify')}>
              {t('aiDefaults.restore')}
            </Button>
          )}
        </Card>
        <Card title={t('aiDefaults.deliverySection')} size="small" style={{ marginBottom: 16 }}>
          <Form.Item label={t('aiDefaults.defaultSquad')} name="deliverySquadId" extra={deliverySquadExtra}>
            <Select
              allowClear
              placeholder={t('aiDefaults.selectSquad')}
              options={squadOptions}
              onChange={() => form.setFieldValue('deliveryAgentId', undefined)}
            />
          </Form.Item>
          <Form.Item
            label={t('aiDefaults.defaultAgent')}
            name="deliveryAgentId"
            extra={deliveryAgentExtra}
            dependencies={['deliverySquadId']}
            rules={[agentRequiredRule('deliverySquadId')]}
          >
            <Select
              allowClear
              disabled={deliverySquadId == null}
              placeholder={deliverySquadId == null
                ? t('aiDefaults.selectSquadFirst')
                : t('aiDefaults.selectAgent')}
              options={agentOptions(deliveryMembersQuery.data, true)}
            />
          </Form.Item>
          {showDeliveryRestore && (
            <Button type="link" style={{ paddingInline: 0 }} onClick={() => handleRestore('delivery')}>
              {t('aiDefaults.restore')}
            </Button>
          )}
        </Card>
      </Form>
      <Space>
        <Button type="primary" loading={saveMut.isPending} onClick={() => saveMut.mutate()}>
          {t('aiDefaults.save')}
        </Button>
      </Space>
    </Space>
  );
}
