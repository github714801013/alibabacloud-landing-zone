import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import type { PromptTemplateCatalog } from '@/features/settings/clarificationPromptTemplates';
import { WorkitemClarificationPanel } from './WorkitemClarificationPanel';
import { readClarificationPrefill, writeClarificationPrefill } from './prefill';

type RealtimeCallback = (event: { type: string; payload: unknown }) => void;
const realtime = vi.hoisted(() => ({ callback: null as RealtimeCallback | null }));

vi.mock('@/shared/realtime/useRealtime', () => ({
  useRealtime: (_channel: unknown, opts: { onEvent: RealtimeCallback }) => {
    realtime.callback = opts.onEvent;
  },
}));

function apiOk<T>(data: T) {
  return { success: true, code: '0', message: '', traceId: null, data };
}

function emptyConversation(id = 1) {
  return {
    id,
    agentId: 42,
    agentName: 'Agent-A',
    channelConversationId: `ch-${id}`,
    status: 'ACTIVE',
    executorOnline: true,
    streamingSupported: true,
    cliSessionRef: null,
    processingStatus: null,
    processingTurnId: null,
    lastTurnAt: null,
    gmtCreate: '2026-01-01T00:00:00',
    turns: [] as unknown[],
  };
}

function mockTemplateHandlers(options: {
  autoSend: boolean;
  promptTemplates?: PromptTemplateCatalog | null;
  clarificationContentMd?: string;
  posted?: string[];
  conversations?: ReturnType<typeof emptyConversation>[];
}) {
  const conversations = options.conversations ?? [emptyConversation()];
  server.use(
    http.get('/api/workspaces/current/ai-defaults', () => HttpResponse.json(apiOk({
      owner: false,
      clarify: {
        defaultSquadId: null,
        defaultAgentId: null,
        autoSendFirstPrompt: options.autoSend,
        promptTemplates: options.promptTemplates ?? null,
        valid: true,
      },
      delivery: { defaultSquadId: null, defaultAgentId: null, valid: true },
    }))),
    http.get('/api/workitems/:workitemId/clarification', () => HttpResponse.json(apiOk(
      options.clarificationContentMd ? { contentMd: options.clarificationContentMd } : null,
    ))),
    http.get('/api/workitems/:workitemId/clarification-conversations', () =>
      HttpResponse.json(apiOk(conversations))),
    http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', ({ params }) => {
      const id = Number(params.conversationId);
      return HttpResponse.json(apiOk(
        conversations.find((item) => item.id === id) ?? emptyConversation(id),
      ));
    }),
    http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId/events', () =>
      HttpResponse.json(apiOk([]))),
    http.post('/api/workitems/:workitemId/clarification-conversations/:conversationId/turns',
      async ({ request }) => {
        const body = await request.json() as { content: string };
        options.posted?.push(body.content);
        return HttpResponse.json(apiOk(null));
      }),
  );
}

function renderPanel() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <WorkitemClarificationPanel workitemId="100" />
    </QueryClientProvider>,
  );
}

/** 假定时器下逐秒推进：每次推进后让 React 结算 effect 并安排下一次倒计时。 */
async function advanceSeconds(seconds: number) {
  for (let i = 0; i < seconds; i += 1) {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });
  }
}

/** 假定时器下推进微任务与已到期定时器，直到查询链路稳定（会话列表 → 详情 → 预填）。 */
async function flush() {
  for (let i = 0; i < 10; i += 1) {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
  }
}

async function typeComposer(value: string) {
  const root = screen.getByTestId('clarification-composer-input');
  const editor = (root as HTMLElement & { __composerEditor?: { commands: { setContent: (v: string, emit: boolean) => void } } }).__composerEditor;
  if (!editor) throw new Error('composer editor not mounted');
  await act(async () => {
    editor.commands.setContent(value, true);
  });
}

function composer() {
  return screen.getByPlaceholderText('输入消息...');
}

function templateSelect() {
  return screen.getByTestId('clarification-template-select');
}

/** 假定时器下打开 antd Select 并选中文案；下拉动画由推进的假定时器结算。 */
async function chooseTemplate(name: string) {
  fireEvent.mouseDown(within(templateSelect()).getByRole('combobox'));
  await act(async () => {
    await vi.advanceTimersByTimeAsync(100);
  });
  fireEvent.click(screen.getByText(name));
}

describe('WorkitemClarificationPanel 提示词模板', () => {
  beforeEach(() => {
    localStorage.clear();
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('预填空间首次模板并自动发送替换后的正文', async () => {
    const posted: string[] = [];
    mockTemplateHandlers({
      autoSend: true,
      posted,
      promptTemplates: {
        version: 1,
        builtin: { bootstrap: { content: '空间首次 {{workitemId}}' } },
      },
    });

    renderPanel();
    await flush();

    expect(composer()).toHaveValue('空间首次 100');
    expect(templateSelect()).toHaveTextContent('首次澄清');
    expect(screen.getByTestId('clarification-auto-send-banner')).toHaveTextContent('5');

    await advanceSeconds(5);
    await flush();

    expect(posted).toEqual(['空间首次 100']);
  });

  it('默认模板正文为空时保持空白且不启动自动发送', async () => {
    const posted: string[] = [];
    mockTemplateHandlers({
      autoSend: true,
      posted,
      promptTemplates: {
        version: 1,
        custom: [{ id: 'custom-1', name: '空模板', content: '' }],
        defaults: { first: 'custom-1', reclarify: 'custom-1' },
      },
    });

    renderPanel();
    await flush();

    expect(composer()).toHaveValue('');
    expect(templateSelect()).toHaveTextContent('空模板');
    expect(screen.queryByTestId('clarification-auto-send-banner')).not.toBeInTheDocument();

    await advanceSeconds(5);
    await flush();
    expect(posted).toEqual([]);
  });

  it('已有结构化澄清结果时预填再次澄清默认模板', async () => {
    const posted: string[] = [];
    mockTemplateHandlers({
      autoSend: true,
      posted,
      clarificationContentMd: '已有澄清结果',
      promptTemplates: {
        version: 1,
        custom: [{ id: 'custom-2', name: '再次模板', content: '再次 {{workitemId}}' }],
        defaults: { first: 'reclarify', reclarify: 'custom-2' },
      },
    });

    renderPanel();
    await flush();

    expect(composer()).toHaveValue('再次 100');
    expect(templateSelect()).toHaveTextContent('再次模板');
    expect(screen.queryByTestId('clarification-auto-send-banner')).not.toBeInTheDocument();
    expect(posted).toEqual([]);
  });

  it('手动切换模板会取消自动发送且不重新计时', async () => {
    const posted: string[] = [];
    mockTemplateHandlers({
      autoSend: true,
      posted,
      promptTemplates: {
        version: 1,
        builtin: { bootstrap: { content: '默认 {{workitemId}}' } },
        custom: [{ id: 'custom-1', name: '盘问清单', content: '自定义正文' }],
      },
    });

    renderPanel();
    await flush();

    expect(screen.getByTestId('clarification-auto-send-banner')).toHaveTextContent('5');
    await chooseTemplate('盘问清单');

    expect(screen.queryByTestId('clarification-auto-send-banner')).not.toBeInTheDocument();
    await advanceSeconds(6);
    await flush();
    expect(posted).toEqual([]);
  });
});

describe('WorkitemClarificationPanel 提示词模板：选择器交互', () => {
  beforeEach(() => {
    localStorage.clear();
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('选择模板只替换输入且不发送、不持久化', async () => {
    const posted: string[] = [];
    mockTemplateHandlers({
      autoSend: false,
      posted,
      promptTemplates: {
        version: 1,
        builtin: { bootstrap: { content: '默认 {{workitemId}}' } },
        custom: [
          { id: 'custom-1', name: '盘问清单', content: '自定义 {{workitemId}}' },
          { id: 'custom-empty', name: '空模板', content: '' },
        ],
      },
    });

    renderPanel();
    await waitFor(() => expect(composer()).toHaveValue('默认 100'));
    const prefillBefore = localStorage.getItem('aw:clarification-prefill:100');
    fireEvent.mouseDown(within(templateSelect()).getByRole('combobox'));
    fireEvent.click(await screen.findByText('盘问清单'));

    expect(composer()).toHaveValue('自定义 100');
    expect(templateSelect()).toHaveTextContent('盘问清单');

    // 手动编辑输入不改变「最近一次填入的模板」。
    await typeComposer('手动修改');
    expect(templateSelect()).toHaveTextContent('盘问清单');

    // 空正文模板也要清空输入，而不是保留上一份正文。
    fireEvent.mouseDown(within(templateSelect()).getByRole('combobox'));
    fireEvent.click(await screen.findByText('空模板'));
    expect(composer()).toHaveValue('');
    expect(templateSelect()).toHaveTextContent('空模板');

    expect(posted).toEqual([]);
    // 精确比较原始字符串：即使按相同 squad/agent 重写，updatedAt 变化也会暴露。
    expect(localStorage.getItem('aw:clarification-prefill:100')).toBe(prefillBefore);
    expect(readClarificationPrefill('100')).toMatchObject({ squadId: 9, agentId: 42 });
    expect(localStorage.getItem('aw:clarify-defaults:local')).toBeNull();
  });
});

describe('WorkitemClarificationPanel 提示词模板：默认值查询未落定', () => {
  beforeEach(() => {
    localStorage.clear();
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
  });

  it('默认值查询未落定时禁用模板选择器，落定后恢复', async () => {
    let release!: () => void;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    mockTemplateHandlers({ autoSend: false });
    server.use(http.get('/api/workspaces/current/ai-defaults', async () => {
      await gate;
      return HttpResponse.json(apiOk({
        owner: false,
        clarify: {
          defaultSquadId: null,
          defaultAgentId: null,
          autoSendFirstPrompt: false,
          promptTemplates: null,
          valid: true,
        },
        delivery: { defaultSquadId: null, defaultAgentId: null, valid: true },
      }));
    }));

    renderPanel();
    const select = await screen.findByTestId('clarification-template-select');
    expect(within(select).getByRole('combobox')).toBeDisabled();

    await act(async () => {
      release();
    });

    await waitFor(() => {
      expect(within(select).getByRole('combobox')).not.toBeDisabled();
    });
  });
});

describe('WorkitemClarificationPanel 提示词模板：默认值查询失败', () => {
  beforeEach(() => {
    localStorage.clear();
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
  });

  it('查询失败后忽略本地覆盖、仍列出两个内置模板并可选择', async () => {
    localStorage.setItem('aw:clarify-defaults:local', JSON.stringify({
      firstTemplateId: 'custom-1',
      templateContents: { bootstrap: '本地首次正文' },
    }));
    mockTemplateHandlers({ autoSend: false });
    server.use(http.get('/api/workspaces/current/ai-defaults', () =>
      HttpResponse.json(
        { success: false, code: '500', message: 'boom', traceId: null, data: null },
        { status: 500 },
      )));

    renderPanel();
    const select = await screen.findByTestId('clarification-template-select');
    await waitFor(() => {
      expect(within(select).getByRole('combobox')).not.toBeDisabled();
    });
    // 查询失败时按出厂模板预填，不采用无法确认归属的本地覆盖。
    await waitFor(() => expect(composer())
      .toHaveValue('请通过平台 MCP 读取工单 #100，与我进行需求澄清。'));
    expect(templateSelect()).toHaveTextContent('首次澄清');

    fireEvent.mouseDown(within(select).getByRole('combobox'));
    expect(await screen.findAllByRole('option')).toHaveLength(2);
    expect(screen.queryByText('盘问清单')).not.toBeInTheDocument();
  });
});

describe('WorkitemClarificationPanel 提示词模板：切换会话', () => {
  beforeEach(() => {
    localStorage.clear();
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
  });

  it('切换会话后选择器回到该会话状态的默认模板', async () => {
    mockTemplateHandlers({
      autoSend: false,
      conversations: [emptyConversation(1), emptyConversation(2)],
      promptTemplates: {
        version: 1,
        builtin: { bootstrap: { content: '默认 {{workitemId}}' } },
        custom: [{ id: 'custom-1', name: '盘问清单', content: '自定义正文' }],
      },
    });

    renderPanel();
    await waitFor(() => expect(composer()).toHaveValue('默认 100'));
    fireEvent.mouseDown(within(templateSelect()).getByRole('combobox'));
    fireEvent.click(await screen.findByText('盘问清单'));
    expect(templateSelect()).toHaveTextContent('盘问清单');

    const conversationSelect = screen.getByTestId('clarification-conversation-select');
    fireEvent.mouseDown(within(conversationSelect).getByRole('combobox'));
    fireEvent.click(await screen.findByText('会话 #2'));

    await waitFor(() => expect(templateSelect()).toHaveTextContent('首次澄清'));
  });
});
