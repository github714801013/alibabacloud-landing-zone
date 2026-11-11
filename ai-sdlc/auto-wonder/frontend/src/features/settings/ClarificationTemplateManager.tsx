import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Button, Form, Input, List, Modal, Popconfirm, Space, Tag, Typography } from 'antd';
import {
  MAX_CUSTOM_TEMPLATES,
  MAX_TEMPLATE_CONTENT_LENGTH,
  MAX_TEMPLATE_NAME_LENGTH,
  isBuiltinTemplate,
  type PromptTemplate,
} from './clarificationPromptTemplates';

export interface TemplateFormValues {
  name: string;
  content: string;
}

export interface ClarificationTemplateManagerProps {
  templates: PromptTemplate[];
  isOwner: boolean;
  canAdd: boolean;
  onAdd: (values: TemplateFormValues) => void;
  onUpdate: (templateId: string, values: TemplateFormValues) => void;
  onDelete: (templateId: string) => void;
  onRestore: (template: PromptTemplate) => void;
  canRestore: (template: PromptTemplate) => boolean;
}

export function ClarificationTemplateManager({
  templates,
  isOwner,
  canAdd,
  onAdd,
  onUpdate,
  onDelete,
  onRestore,
  canRestore,
}: ClarificationTemplateManagerProps) {
  const { t } = useTranslation('settings');
  const [form] = Form.useForm<TemplateFormValues>();
  const [modalOpen, setModalOpen] = useState(false);
  const [editingTemplate, setEditingTemplate] = useState<PromptTemplate | null>(null);

  const openAdd = () => {
    setEditingTemplate(null);
    form.setFieldsValue({ name: '', content: '' });
    setModalOpen(true);
  };

  const openEdit = (template: PromptTemplate) => {
    setEditingTemplate(template);
    form.setFieldsValue({ name: template.name, content: template.content });
    setModalOpen(true);
  };

  const closeModal = () => {
    setModalOpen(false);
    form.resetFields();
  };

  const validateName = (_: unknown, value: string | undefined) => {
    const name = (value ?? '').trim();
    if (!name) {
      return Promise.reject(new Error(t('aiDefaults.template.nameRequired')));
    }
    if ([...name].length > MAX_TEMPLATE_NAME_LENGTH) {
      return Promise.reject(new Error(t('aiDefaults.template.nameTooLong', { max: MAX_TEMPLATE_NAME_LENGTH })));
    }
    const duplicate = templates.some((template) => template.kind === 'custom'
      && template.id !== editingTemplate?.id
      && template.name.trim() === name);
    return duplicate
      ? Promise.reject(new Error(t('aiDefaults.template.nameDuplicate')))
      : Promise.resolve();
  };

  const validateContent = (_: unknown, value: string | undefined) => {
    const content = value ?? '';
    if ([...content].length > MAX_TEMPLATE_CONTENT_LENGTH) {
      return Promise.reject(
        new Error(t('aiDefaults.template.contentTooLong', { max: MAX_TEMPLATE_CONTENT_LENGTH })),
      );
    }
    if (editingTemplate && isBuiltinTemplate(editingTemplate) && content.trim() === '') {
      return Promise.reject(new Error(t('aiDefaults.template.builtinContentRequired')));
    }
    return Promise.resolve();
  };

  const handleOk = async () => {
    try {
      const values = await form.validateFields();
      const payload = { name: values.name.trim(), content: values.content ?? '' };
      if (editingTemplate) onUpdate(editingTemplate.id, payload);
      else onAdd(payload);
      closeModal();
    } catch {
      // 校验失败由表单项展示错误。
    }
  };

  const summary = (content: string) => {
    const text = content.replace(/\s+/g, ' ').trim();
    if (!text) return t('aiDefaults.templateEmptyContent');
    return text.length > 60 ? `${text.slice(0, 60)}…` : text;
  };

  const restoreLabel = (template: PromptTemplate) => {
    if (isBuiltinTemplate(template)) {
      return isOwner
        ? t('aiDefaults.template.restoreFactory')
        : t('aiDefaults.template.restoreSpace');
    }
    return t('aiDefaults.template.restoreSpaceContent');
  };

  const nameDisabled = !isOwner || (editingTemplate !== null && isBuiltinTemplate(editingTemplate));

  return (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      {isOwner && (
        <Space size={8}>
          <Button disabled={!canAdd} onClick={openAdd}>{t('aiDefaults.template.add')}</Button>
          {!canAdd && (
            <Typography.Text type="secondary">
              {t('aiDefaults.template.limitReached', { max: MAX_CUSTOM_TEMPLATES })}
            </Typography.Text>
          )}
        </Space>
      )}
      <List
        size="small"
        dataSource={templates}
        renderItem={(template) => {
          const actions: ReactNode[] = [
            <Button key="edit" type="link" onClick={() => openEdit(template)}>{t('edit')}</Button>,
          ];
          if (isOwner && template.kind === 'custom') {
            actions.push(
              <Popconfirm
                key="delete"
                title={t('aiDefaults.template.deleteConfirm', { name: template.name })}
                okText={t('aiDefaults.template.delete')}
                cancelText={t('cancel')}
                onConfirm={() => onDelete(template.id)}
              >
                <Button type="link" danger>{t('aiDefaults.template.delete')}</Button>
              </Popconfirm>,
            );
          }
          if (canRestore(template)) {
            actions.push(
              <Button key="restore" type="link" onClick={() => onRestore(template)}>
                {restoreLabel(template)}
              </Button>,
            );
          }
          return (
            <List.Item actions={actions}>
              <List.Item.Meta
                title={(
                  <Space size={6}>
                    <span>{template.name}</span>
                    <Tag>{isBuiltinTemplate(template)
                      ? t('aiDefaults.builtinTemplate')
                      : t('aiDefaults.customTemplate')}
                    </Tag>
                  </Space>
                )}
                description={<Typography.Text type="secondary">{summary(template.content)}</Typography.Text>}
              />
            </List.Item>
          );
        }}
      />
      <Modal
        open={modalOpen}
        title={editingTemplate
          ? t('aiDefaults.template.editTitle')
          : t('aiDefaults.template.addTitle')}
        okText={t('aiDefaults.template.confirm')}
        cancelText={t('cancel')}
        onOk={handleOk}
        onCancel={closeModal}
        destroyOnClose
      >
        <Form form={form} layout="vertical" preserve={false}>
          <Form.Item
            label={t('aiDefaults.template.name')}
            name="name"
            rules={[{ validator: validateName }]}
          >
            <Input
              disabled={nameDisabled}
              placeholder={t('aiDefaults.template.namePlaceholder')}
            />
          </Form.Item>
          <Form.Item
            label={t('aiDefaults.template.content')}
            name="content"
            rules={[{ validator: validateContent }]}
          >
            <Input.TextArea
              rows={6}
              placeholder={t('aiDefaults.template.contentPlaceholder', { workitemId: '{{workitemId}}' })}
            />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  );
}
