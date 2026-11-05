import { beforeEach, describe, expect, it } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { AiCollaborationDefaultsPage } from './AiCollaborationDefaultsPage';
import type { AiCollaborationDefaultItem } from './api';
import type { PromptTemplateCatalog } from './clarificationPromptTemplates';

function apiOk<T>(data: T) {
  return { success: true, code: '0', message: '', traceId: null, data };
}

const squads = [
  { id: 9, name: '澄清小队', description: '', memberCount: 1, gmtCreate: '' },
  { id: 8, name: '交付小队', description: '', memberCount: 2, gmtCreate: '' },
  { id: 7, name: '备用小队', description: '', memberCount: 1, gmtCreate: '' },
];

function membersFor(squadId: string) {
  if (squadId === '9') return [{ agentId: 42, agentName: 'Agent-A', roleCode: 'AW_FS_DEV' }];
  if (squadId === '8') {
    return [
      { agentId: 41, agentName: 'Agent-B', roleCode: 'AW_QA', agentKind: 'STANDARD' },
      { agentId: 43, agentName: 'Platform-Agent', roleCode: 'PLATFORM', agentKind: 'PLATFORM' },
    ];
  }
  return [{ agentId: 71, agentName: 'Agent-C', roleCode: 'AW_FS_DEV' }];
}

function useDefaultsHandlers(options: {
  owner: boolean;
  saved?: { items?: AiCollaborationDefaultItem[] };
  promptTemplates?: PromptTemplateCatalog | null;
}) {
  server.use(
    http.get('/api/squads', () => HttpResponse.json(apiOk({
      list: squads,
      total: squads.length,
      pageNum: 1,
      pageSize: 100,
    }))),
    http.get('/api/squads/:squadId/members', ({ params }) =>
      HttpResponse.json(apiOk(membersFor(String(params.squadId))))),
    http.get('/api/workspaces/current/ai-defaults', () => HttpResponse.json(apiOk({
      owner: options.owner,
      clarify: {
        defaultSquadId: 9,
        defaultAgentId: 42,
        autoSendFirstPrompt: true,
        promptTemplates: options.promptTemplates ?? null,
        valid: true,
      },
      delivery: { defaultSquadId: 8, defaultAgentId: 41, valid: true },
    }))),
    http.put('/api/workspaces/current/ai-defaults', async ({ request }) => {
      const body = await request.json() as { items?: AiCollaborationDefaultItem[] };
      if (options.saved) options.saved.items = body.items;
      return HttpResponse.json(apiOk(null));
    }),
  );
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <AiCollaborationDefaultsPage />
    </QueryClientProvider>,
  );
}

describe('AiCollaborationDefaultsPage', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('shows the space defaults to the owner and saves them as space values', async () => {
    const saved: { items?: AiCollaborationDefaultItem[] } = {};
    useDefaultsHandlers({ owner: true, saved });

    renderPage();

    expect(await screen.findByText('保存后对所有成员生效')).toBeInTheDocument();
    expect(await screen.findByText('澄清小队')).toBeInTheDocument();
    expect(screen.getByText('Agent-A (AW_FS_DEV)')).toBeInTheDocument();
    expect(screen.getByRole('switch')).toBeChecked();
    expect(screen.queryByRole('button', { name: '恢复为空间默认' })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /保\s*存/ }));

    await waitFor(() => {
      expect(saved.items).toEqual([
        { key: 'clarify.defaultSquadId', value: '9' },
        { key: 'clarify.defaultAgentId', value: '42' },
        { key: 'clarify.autoSendFirstPrompt', value: 'true' },
        { key: 'delivery.defaultSquadId', value: '8' },
        { key: 'delivery.defaultAgentId', value: '41' },
      ]);
    });
    expect(localStorage.getItem('aw:clarify-defaults:local')).toBeNull();
  });

  it('merges member local overrides and shows the space default beside overridden fields', async () => {
    localStorage.setItem('aw:clarify-defaults:local', JSON.stringify({
      defaultSquadId: 7,
      defaultAgentId: 71,
      autoSendFirstPrompt: false,
    }));
    useDefaultsHandlers({ owner: false });

    renderPage();

    expect(await screen.findByText('保存后仅对当前浏览器生效')).toBeInTheDocument();
    expect(await screen.findByText('备用小队')).toBeInTheDocument();
    expect(screen.getByText('Agent-C (AW_FS_DEV)')).toBeInTheDocument();
    expect(screen.getByText('空间默认：澄清小队')).toBeInTheDocument();
    expect(screen.getByText('空间默认：Agent-A')).toBeInTheDocument();
    expect(screen.getByText('空间默认：开')).toBeInTheDocument();
    expect(screen.getByRole('switch')).not.toBeChecked();
    expect(screen.getByRole('button', { name: '恢复为空间默认' })).toBeInTheDocument();
  });

  it('saves member changes to local storage instead of the space API', async () => {
    let spacePutCalls = 0;
    localStorage.setItem('aw:clarify-defaults:local', JSON.stringify({
      defaultSquadId: 7,
      defaultAgentId: 71,
      autoSendFirstPrompt: false,
    }));
    useDefaultsHandlers({ owner: false });
    server.use(
      http.put('/api/workspaces/current/ai-defaults', () => {
        spacePutCalls += 1;
        return HttpResponse.json(apiOk(null));
      }),
    );

    renderPage();
    await screen.findByText('备用小队');
    await userEvent.click(screen.getByRole('switch'));
    await userEvent.click(screen.getByRole('button', { name: /保\s*存/ }));

    await waitFor(() => {
      expect(JSON.parse(localStorage.getItem('aw:clarify-defaults:local') ?? '{}')).toEqual({
        defaultSquadId: 7,
        defaultAgentId: 71,
      });
    });
    expect(spacePutCalls).toBe(0);
  });

  it('shows built-in and custom prompt templates with the effective defaults', async () => {
    useDefaultsHandlers({
      owner: false,
      promptTemplates: {
        version: 1,
        builtin: { bootstrap: { content: '空间首次正文' } },
        custom: [{ id: 'custom-1', name: '盘问清单', content: '' }],
        defaults: { first: 'custom-1', reclarify: 'reclarify' },
      },
    });

    renderPage();

    // 等成员查询落定，避免用例结束后仍有请求命中已重置的 mock。
    expect(await screen.findByText('Agent-A (AW_FS_DEV)')).toBeInTheDocument();
    expect((await screen.findAllByText('Agent-B (AW_QA)')).length).toBeGreaterThan(0);
    expect(await screen.findByText('自定义')).toBeInTheDocument();
    expect(screen.getByText('空正文')).toBeInTheDocument();
    expect(screen.getAllByText('盘问清单')).toHaveLength(1);
    expect(screen.getByText('盘问清单（自定义）')).toBeInTheDocument();
    expect(screen.getAllByText('首次澄清')).toHaveLength(1);
    expect(screen.getAllByText('再次澄清')).toHaveLength(1);
    expect(screen.getByText('再次澄清（内置）')).toBeInTheDocument();
    expect(screen.getByText('空间首次正文')).toBeInTheDocument();
    // 成员的内置模板始终显示恢复入口；自定义模板无本地覆盖时不显示。
    expect(screen.getAllByRole('button', { name: '恢复空间版本' })).toHaveLength(2);
    expect(screen.queryByRole('button', { name: '恢复空间正文' })).not.toBeInTheDocument();
  });

  it('ignores local template overrides for the owner', async () => {
    localStorage.setItem('aw:clarify-defaults:local', JSON.stringify({
      firstTemplateId: 'custom-1',
      templateContents: { bootstrap: '本地首次正文' },
    }));
    useDefaultsHandlers({
      owner: true,
      promptTemplates: {
        version: 1,
        custom: [{ id: 'custom-1', name: '盘问清单', content: '空间自定义正文' }],
        defaults: { first: 'reclarify' },
      },
    });

    renderPage();

    expect(await screen.findByText('Agent-A (AW_FS_DEV)')).toBeInTheDocument();
    expect((await screen.findAllByText('Agent-B (AW_QA)')).length).toBeGreaterThan(0);
    // 两个默认选择项都显示再次澄清，模板列表中的内置项只算一处。
    expect(screen.getAllByText('再次澄清（内置）')).toHaveLength(2);
    expect(screen.getAllByText('再次澄清')).toHaveLength(1);
    expect(screen.getAllByText('盘问清单')).toHaveLength(1);
    expect(screen.queryByText('本地首次正文')).not.toBeInTheDocument();
  });

  it('saves owner template edits as the canonical catalog payload', async () => {
    const saved: { items?: AiCollaborationDefaultItem[] } = {};
    useDefaultsHandlers({ owner: true, saved });

    renderPage();
    await screen.findByText('保存后对所有成员生效');
    await userEvent.click(screen.getByRole('button', { name: '新增模板' }));
    await screen.findByText('新增提示词模板');
    await userEvent.type(screen.getByLabelText('模板名称'), '新清单');
    await userEvent.type(screen.getByLabelText('模板正文'), '请补充需求');
    await userEvent.click(screen.getByRole('button', { name: /确\s*定/ }));
    await userEvent.click(screen.getByRole('button', { name: /保\s*存/ }));

    await waitFor(() => {
      expect(saved.items).toHaveLength(6);
    });
    const catalogItem = saved.items?.find((item) => item.key === 'clarify.promptTemplates');
    expect(catalogItem).toBeDefined();
    const catalog = JSON.parse(catalogItem!.value);
    expect(catalog.custom).toHaveLength(1);
    expect(catalog.custom[0]).toMatchObject({ name: '新清单', content: '请补充需求' });
    expect(catalog.custom[0].id).toMatch(/^custom-/);
    expect(catalog.defaults).toEqual({ first: 'bootstrap', reclarify: 'reclarify' });
  });

  it('falls back to the built-in default when the owner deletes a referenced template', async () => {
    const saved: { items?: AiCollaborationDefaultItem[] } = {};
    useDefaultsHandlers({
      owner: true,
      saved,
      promptTemplates: {
        version: 1,
        custom: [{ id: 'custom-1', name: '盘问清单', content: '内容' }],
        defaults: { first: 'custom-1', reclarify: 'reclarify' },
      },
    });

    renderPage();
    await screen.findByText('盘问清单');
    await userEvent.click(screen.getByRole('button', { name: /删\s*除/ }));
    const confirmButtons = await screen.findAllByRole('button', { name: /删\s*除/ });
    await userEvent.click(confirmButtons[confirmButtons.length - 1]);
    await userEvent.click(screen.getByRole('button', { name: /保\s*存/ }));

    await waitFor(() => {
      expect(saved.items).toHaveLength(6);
    });
    const catalogItem = saved.items?.find((item) => item.key === 'clarify.promptTemplates');
    const catalog = JSON.parse(catalogItem!.value);
    expect(catalog.custom).toEqual([]);
    expect(catalog.defaults).toEqual({ first: 'bootstrap', reclarify: 'reclarify' });
  });

  it('restores owner built-in content to the factory text before saving', async () => {
    const saved: { items?: AiCollaborationDefaultItem[] } = {};
    useDefaultsHandlers({
      owner: true,
      saved,
      promptTemplates: { version: 1, builtin: { bootstrap: { content: '空间首次正文' } } },
    });

    renderPage();
    expect(await screen.findByText('空间首次正文')).toBeInTheDocument();
    // 首个内置模板是「首次澄清」，第二个「再次澄清」没有覆盖，点击不会产生保存项。
    await userEvent.click(screen.getAllByRole('button', { name: '恢复出厂文案' })[0]);
    await userEvent.click(screen.getByRole('button', { name: /保\s*存/ }));

    await waitFor(() => {
      expect(saved.items).toHaveLength(6);
    });
    const catalogItem = saved.items?.find((item) => item.key === 'clarify.promptTemplates');
    const catalog = JSON.parse(catalogItem!.value);
    expect(catalog.builtin).toEqual({});
  });

  it('keeps member template overrides when saving and clears them on restore', async () => {
    localStorage.setItem('aw:clarify-defaults:local', JSON.stringify({
      firstTemplateId: 'custom-1',
      templateContents: { bootstrap: '本地首次正文' },
    }));
    useDefaultsHandlers({
      owner: false,
      promptTemplates: {
        version: 1,
        builtin: { bootstrap: { content: '空间首次正文' } },
        custom: [{ id: 'custom-1', name: '盘问清单', content: '空间自定义正文' }],
      },
    });

    renderPage();
    expect(await screen.findByText('本地首次正文')).toBeInTheDocument();
    expect(screen.getByText('盘问清单（自定义）')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /保\s*存/ }));

    await waitFor(() => {
      expect(JSON.parse(localStorage.getItem('aw:clarify-defaults:local') ?? '{}')).toEqual({
        firstTemplateId: 'custom-1',
        templateContents: { bootstrap: '本地首次正文' },
      });
    });

    await userEvent.click(screen.getByRole('button', { name: '恢复为空间默认' }));

    await waitFor(() => {
      expect(localStorage.getItem('aw:clarify-defaults:local')).toBeNull();
    });
    expect(screen.getByText('首次澄清（内置）')).toBeInTheDocument();
    expect(screen.getByText('空间首次正文')).toBeInTheDocument();
    expect(screen.queryByText('本地首次正文')).not.toBeInTheDocument();
  });

  it('saves a member content edit as a local template override', async () => {
    useDefaultsHandlers({
      owner: false,
      promptTemplates: {
        version: 1,
        custom: [{ id: 'custom-1', name: '盘问清单', content: '空间自定义正文' }],
      },
    });

    renderPage();
    await screen.findByText('空间自定义正文');
    await userEvent.click(screen.getAllByRole('button', { name: /编\s*辑/ })[2]);
    const content = screen.getByLabelText('模板正文');
    await userEvent.clear(content);
    await userEvent.type(content, '本地正文');
    await userEvent.click(screen.getByRole('button', { name: /确\s*定/ }));
    await userEvent.click(screen.getByRole('button', { name: /保\s*存/ }));

    await waitFor(() => {
      expect(JSON.parse(localStorage.getItem('aw:clarify-defaults:local') ?? '{}')).toEqual({
        templateContents: { 'custom-1': '本地正文' },
      });
    });
  });

  it('saves a member default template selection without touching space defaults', async () => {
    let spacePutCalls = 0;
    useDefaultsHandlers({
      owner: false,
      promptTemplates: {
        version: 1,
        custom: [{ id: 'custom-1', name: '盘问清单', content: '空间自定义正文' }],
        defaults: { first: 'reclarify', reclarify: 'reclarify' },
      },
    });
    server.use(
      http.put('/api/workspaces/current/ai-defaults', () => {
        spacePutCalls += 1;
        return HttpResponse.json(apiOk(null));
      }),
    );

    renderPage();
    await screen.findByText('盘问清单');
    fireEvent.mouseDown(screen.getByLabelText('首次澄清默认模板'));
    fireEvent.click(await screen.findByText('盘问清单（自定义）'));
    await userEvent.click(screen.getByRole('button', { name: /保\s*存/ }));

    await waitFor(() => {
      expect(JSON.parse(localStorage.getItem('aw:clarify-defaults:local') ?? '{}')).toEqual({
        firstTemplateId: 'custom-1',
      });
    });
    expect(spacePutCalls).toBe(0);
  });

  it('clears the local content override when a member restores a template', async () => {
    localStorage.setItem('aw:clarify-defaults:local', JSON.stringify({
      templateContents: { 'custom-1': '本地正文' },
    }));
    useDefaultsHandlers({
      owner: false,
      promptTemplates: {
        version: 1,
        custom: [{ id: 'custom-1', name: '盘问清单', content: '空间自定义正文' }],
      },
    });

    renderPage();
    await screen.findByText('本地正文');
    await userEvent.click(screen.getByRole('button', { name: '恢复空间正文' }));
    await userEvent.click(screen.getByRole('button', { name: /保\s*存/ }));

    await waitFor(() => {
      expect(localStorage.getItem('aw:clarify-defaults:local')).toBeNull();
    });
    expect(screen.getByText('空间自定义正文')).toBeInTheDocument();
  });

  it('restores each section independently to the space defaults', async () => {
    localStorage.setItem('aw:clarify-defaults:local', JSON.stringify({
      defaultSquadId: 7,
      defaultAgentId: 71,
    }));
    localStorage.setItem('aw:delivery-defaults:local', JSON.stringify({
      defaultSquadId: null,
      defaultAgentId: null,
    }));
    useDefaultsHandlers({ owner: false });

    renderPage();
    await screen.findByText('备用小队');
    const restoreButtons = screen.getAllByRole('button', { name: '恢复为空间默认' });
    expect(restoreButtons).toHaveLength(2);

    await userEvent.click(restoreButtons[0]);

    await waitFor(() => {
      expect(localStorage.getItem('aw:clarify-defaults:local')).toBeNull();
    });
    expect(localStorage.getItem('aw:delivery-defaults:local')).not.toBeNull();
    expect(await screen.findByText('澄清小队')).toBeInTheDocument();
    expect(screen.queryByText('空间默认：澄清小队')).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: '恢复为空间默认' }));

    await waitFor(() => {
      expect(localStorage.getItem('aw:delivery-defaults:local')).toBeNull();
    });
    expect(screen.queryByRole('button', { name: '恢复为空间默认' })).not.toBeInTheDocument();
  });
});
