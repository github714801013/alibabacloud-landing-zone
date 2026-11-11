import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ClarificationTemplateManager } from './ClarificationTemplateManager';
import type { PromptTemplate } from './clarificationPromptTemplates';

const builtinFirst: PromptTemplate = {
  id: 'bootstrap',
  name: '首次澄清',
  content: '首次正文 {{workitemId}}',
  kind: 'builtin',
};
const builtinReclarify: PromptTemplate = {
  id: 'reclarify',
  name: '再次澄清',
  content: '再次正文',
  kind: 'builtin',
};
const customTemplate: PromptTemplate = {
  id: 'custom-1',
  name: '盘问清单',
  content: '',
  kind: 'custom',
};

const templates = [builtinFirst, builtinReclarify, customTemplate];

function renderManager(overrides: Partial<Parameters<typeof ClarificationTemplateManager>[0]> = {}) {
  const props = {
    templates,
    isOwner: true,
    canAdd: true,
    onAdd: vi.fn(),
    onUpdate: vi.fn(),
    onDelete: vi.fn(),
    onRestore: vi.fn(),
    canRestore: () => false,
    ...overrides,
  };
  render(<ClarificationTemplateManager {...props} />);
  return props;
}

describe('ClarificationTemplateManager', () => {
  it('shows owner actions and only allows deleting custom templates', () => {
    renderManager({ canRestore: (template) => template.kind === 'builtin' });

    expect(screen.getByRole('button', { name: '新增模板' })).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /编\s*辑/ })).toHaveLength(3);
    expect(screen.getAllByRole('button', { name: /删\s*除/ })).toHaveLength(1);
    expect(screen.getAllByRole('button', { name: '恢复出厂文案' })).toHaveLength(2);
    expect(screen.getAllByText('内置')).toHaveLength(2);
    expect(screen.getByText('自定义')).toBeInTheDocument();
    expect(screen.getByText('空正文')).toBeInTheDocument();
  });

  it('disables adding when the custom template limit is reached', () => {
    renderManager({ canAdd: false });

    expect(screen.getByRole('button', { name: '新增模板' })).toBeDisabled();
    expect(screen.getByText('自定义模板最多 100 个')).toBeInTheDocument();
  });

  it('adds a template with trimmed name and content', async () => {
    const props = renderManager();

    await userEvent.click(screen.getByRole('button', { name: '新增模板' }));
    expect(await screen.findByText('新增提示词模板')).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('模板名称'), '  新清单  ');
    await userEvent.type(screen.getByLabelText('模板正文'), '正文');
    await userEvent.click(screen.getByRole('button', { name: /确\s*定/ }));

    expect(props.onAdd).toHaveBeenCalledWith({ name: '新清单', content: '正文' });
  });

  it('rejects empty, duplicate and too long names', async () => {
    const props = renderManager();

    await userEvent.click(screen.getByRole('button', { name: '新增模板' }));
    await userEvent.click(screen.getByRole('button', { name: /确\s*定/ }));
    expect(await screen.findByText('模板名称不能为空')).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText('模板名称'), '盘问清单');
    await userEvent.click(screen.getByRole('button', { name: /确\s*定/ }));
    expect(await screen.findByText('模板名称已存在')).toBeInTheDocument();

    await userEvent.clear(screen.getByLabelText('模板名称'));
    await userEvent.type(screen.getByLabelText('模板名称'), 'x'.repeat(51));
    await userEvent.click(screen.getByRole('button', { name: /确\s*定/ }));
    expect(await screen.findByText('模板名称不能超过 50 个字符')).toBeInTheDocument();
    expect(props.onAdd).not.toHaveBeenCalled();
  });

  it('keeps built-in names read-only and rejects blank built-in content', async () => {
    const props = renderManager();

    await userEvent.click(screen.getAllByRole('button', { name: /编\s*辑/ })[0]);

    expect(screen.getByLabelText('模板名称')).toBeDisabled();
    await userEvent.clear(screen.getByLabelText('模板正文'));
    await userEvent.click(screen.getByRole('button', { name: /确\s*定/ }));

    expect(await screen.findByText('内置模板正文不能为空')).toBeInTheDocument();
    expect(props.onUpdate).not.toHaveBeenCalled();
  });

  it('updates an existing template', async () => {
    const props = renderManager();

    await userEvent.click(screen.getAllByRole('button', { name: /编\s*辑/ })[2]);
    await userEvent.clear(screen.getByLabelText('模板名称'));
    await userEvent.type(screen.getByLabelText('模板名称'), '新清单');
    await userEvent.click(screen.getByRole('button', { name: /确\s*定/ }));

    expect(props.onUpdate).toHaveBeenCalledWith('custom-1', { name: '新清单', content: '' });
  });

  it('deletes a custom template after confirmation', async () => {
    const props = renderManager();

    await userEvent.click(screen.getByRole('button', { name: /删\s*除/ }));
    const confirmButtons = await screen.findAllByRole('button', { name: /删\s*除/ });
    await userEvent.click(confirmButtons[confirmButtons.length - 1]);

    expect(props.onDelete).toHaveBeenCalledWith('custom-1');
  });

  it('gives members content editing and restore only, without name editing', async () => {
    const props = renderManager({
      isOwner: false,
      canRestore: (template) => template.id === 'bootstrap' || template.id === 'custom-1',
    });

    expect(screen.queryByRole('button', { name: '新增模板' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /删\s*除/ })).not.toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: '恢复空间版本' })).toHaveLength(1);
    expect(screen.getAllByRole('button', { name: '恢复空间正文' })).toHaveLength(1);

    await userEvent.click(screen.getAllByRole('button', { name: /编\s*辑/ })[2]);
    expect(screen.getByLabelText('模板名称')).toBeDisabled();
    await userEvent.type(screen.getByLabelText('模板正文'), '本地正文');
    await userEvent.click(screen.getByRole('button', { name: /确\s*定/ }));

    expect(props.onUpdate).toHaveBeenCalledWith('custom-1', { name: '盘问清单', content: '本地正文' });

    await userEvent.click(screen.getByRole('button', { name: '恢复空间正文' }));
    expect(props.onRestore).toHaveBeenCalledWith(customTemplate);
  });
});
