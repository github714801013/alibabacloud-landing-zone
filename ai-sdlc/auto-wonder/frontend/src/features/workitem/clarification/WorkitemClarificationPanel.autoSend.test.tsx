import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { WorkitemClarificationPanel } from './WorkitemClarificationPanel';
import { writeClarificationPrefill } from './prefill';

type RealtimeCallback = (event: { type: string; payload: unknown }) => void;
const realtime = vi.hoisted(() => ({ callback: null as RealtimeCallback | null }));

vi.mock('@/shared/realtime/useRealtime', () => ({
  useRealtime: (_channel: unknown, opts: { onEvent: RealtimeCallback }) => {
    realtime.callback = opts.onEvent;
  },
}));

const BOOTSTRAP_PROMPT = '请通过平台 MCP 读取工单 #100，与我进行需求澄清。';

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

function mockAutoSendHandlers(options: {
  autoSend: boolean;
  conversation?: ReturnType<typeof emptyConversation>;
  onTurn?: () => Response | Promise<Response>;
}) {
  const conversation = options.conversation ?? emptyConversation();
  server.use(
    http.get('/api/workspaces/current/ai-defaults', () => HttpResponse.json(apiOk({
      owner: false,
      clarify: {
        defaultSquadId: null,
        defaultAgentId: null,
        autoSendFirstPrompt: options.autoSend,
        valid: true,
      },
      delivery: { defaultSquadId: null, defaultAgentId: null, valid: true },
    }))),
    http.get('/api/workitems/:workitemId/clarification-conversations', () =>
      HttpResponse.json(apiOk([conversation]))),
    http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () =>
      HttpResponse.json(apiOk(conversation))),
    http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId/events', () =>
      HttpResponse.json(apiOk([]))),
    http.post('/api/workitems/:workitemId/clarification-conversations/:conversationId/turns', () =>
      HttpResponse.json(apiOk(null))),
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

/** 假定时器下推进微任务与已到期定时器，直到查询链路稳定（会话列表 → 详情 → 倒计时）。 */
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

describe('WorkitemClarificationPanel 首次会话自动发送', () => {
  beforeEach(() => {
    localStorage.clear();
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('倒计时结束后自动发送一次默认澄清提示词', async () => {
    const posted: string[] = [];
    mockAutoSendHandlers({ autoSend: true });
    server.use(
      http.post('/api/workitems/:workitemId/clarification-conversations/:conversationId/turns',
        async ({ request }) => {
          const body = await request.json() as { content: string };
          posted.push(body.content);
          return HttpResponse.json(apiOk(null));
        }),
    );

    renderPanel();
    await flush();

    expect(screen.getByTestId('clarification-auto-send-banner')).toHaveTextContent('5');

    await advanceSeconds(4);
    expect(posted).toEqual([]);

    await advanceSeconds(1);
    await flush();
    expect(posted).toEqual([BOOTSTRAP_PROMPT]);
    expect(screen.queryByTestId('clarification-auto-send-banner')).not.toBeInTheDocument();
  });

  it('会话已有消息时不出现倒计时', async () => {
    const conversation = emptyConversation();
    conversation.turns = [
      { id: 1, direction: 'IN', content: '已有消息', status: 'COMPLETED', error: null, gmtCreate: '2026-01-01T00:00:01' },
    ];
    mockAutoSendHandlers({ autoSend: true, conversation });

    renderPanel();
    await flush();
    await advanceSeconds(5);

    expect(screen.queryByTestId('clarification-auto-send-banner')).not.toBeInTheDocument();
  });

  it('开关关闭时不出现倒计时', async () => {
    mockAutoSendHandlers({ autoSend: false });

    renderPanel();
    await flush();
    await advanceSeconds(5);

    expect(screen.queryByTestId('clarification-auto-send-banner')).not.toBeInTheDocument();
  });

  it('点取消后不再发送', async () => {
    const posted: string[] = [];
    mockAutoSendHandlers({ autoSend: true });
    server.use(
      http.post('/api/workitems/:workitemId/clarification-conversations/:conversationId/turns',
        async ({ request }) => {
          const body = await request.json() as { content: string };
          posted.push(body.content);
          return HttpResponse.json(apiOk(null));
        }),
    );

    renderPanel();
    await flush();
    fireEvent.click(screen.getByRole('button', { name: '取消自动发送' }));
    await advanceSeconds(5);

    expect(posted).toEqual([]);
    expect(screen.queryByTestId('clarification-auto-send-banner')).not.toBeInTheDocument();
  });

  it('开始输入后不再发送', async () => {
    const posted: string[] = [];
    mockAutoSendHandlers({ autoSend: true });
    server.use(
      http.post('/api/workitems/:workitemId/clarification-conversations/:conversationId/turns',
        async ({ request }) => {
          const body = await request.json() as { content: string };
          posted.push(body.content);
          return HttpResponse.json(apiOk(null));
        }),
    );

    renderPanel();
    await flush();
    await typeComposer('我自己来写');
    await advanceSeconds(5);

    expect(posted).toEqual([]);
    expect(screen.queryByTestId('clarification-auto-send-banner')).not.toBeInTheDocument();
  });

  it('切换数字员工后不再发送', async () => {
    const posted: string[] = [];
    mockAutoSendHandlers({ autoSend: true });
    server.use(
      http.post('/api/workitems/:workitemId/clarification-conversations/:conversationId/turns',
        async ({ request }) => {
          const body = await request.json() as { content: string };
          posted.push(body.content);
          return HttpResponse.json(apiOk(null));
        }),
    );

    renderPanel();
    await flush();
    fireEvent.click(screen.getByRole('button', { name: '切换数字员工' }));
    await advanceSeconds(5);

    expect(posted).toEqual([]);
  });

  it('空间开关关闭但浏览器本地打开时仍自动发送', async () => {
    const posted: string[] = [];
    localStorage.setItem('aw:clarify-defaults:local', JSON.stringify({ autoSendFirstPrompt: true }));
    mockAutoSendHandlers({ autoSend: false });
    server.use(
      http.post('/api/workitems/:workitemId/clarification-conversations/:conversationId/turns',
        async ({ request }) => {
          const body = await request.json() as { content: string };
          posted.push(body.content);
          return HttpResponse.json(apiOk(null));
        }),
    );

    renderPanel();
    await flush();

    expect(screen.getByTestId('clarification-auto-send-banner')).toBeInTheDocument();
    await advanceSeconds(5);
    await flush();
    expect(posted).toEqual([BOOTSTRAP_PROMPT]);
  });

  it('本地关闭覆盖空间开启时不自动发送', async () => {
    localStorage.setItem('aw:clarify-defaults:local', JSON.stringify({ autoSendFirstPrompt: false }));
    mockAutoSendHandlers({ autoSend: true });

    renderPanel();
    await flush();
    await advanceSeconds(5);

    expect(screen.queryByTestId('clarification-auto-send-banner')).not.toBeInTheDocument();
  });

  it('倒计时最后时刻取消后不再发送', async () => {
    const posted: string[] = [];
    mockAutoSendHandlers({ autoSend: true });
    server.use(
      http.post('/api/workitems/:workitemId/clarification-conversations/:conversationId/turns',
        async ({ request }) => {
          const body = await request.json() as { content: string };
          posted.push(body.content);
          return HttpResponse.json(apiOk(null));
        }),
    );

    renderPanel();
    await flush();
    await advanceSeconds(4);
    fireEvent.click(screen.getByRole('button', { name: '取消自动发送' }));
    await advanceSeconds(2);
    await flush();

    expect(posted).toEqual([]);
    expect(screen.queryByTestId('clarification-auto-send-banner')).not.toBeInTheDocument();
  });

  it('已有结构化澄清结果时不出现倒计时', async () => {
    mockAutoSendHandlers({ autoSend: true });
    server.use(
      http.get('/api/workitems/:workitemId/clarification', () =>
        HttpResponse.json(apiOk({ contentMd: '已有澄清结果' }))),
    );

    renderPanel();
    await flush();
    await advanceSeconds(5);

    expect(screen.queryByTestId('clarification-auto-send-banner')).not.toBeInTheDocument();
  });

  it('发送失败时回填提示词并提示，会话不阻塞', async () => {
    mockAutoSendHandlers({ autoSend: true });
    server.use(
      http.post('/api/workitems/:workitemId/clarification-conversations/:conversationId/turns', () =>
        HttpResponse.json(
          { success: false, code: 'SEND_FAILED', message: '发送失败', traceId: null, data: null },
          { status: 500 },
        )),
    );

    renderPanel();
    await flush();
    await advanceSeconds(5);
    await flush();

    expect(screen.getByText('消息发送失败，请重试')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('输入消息...')).toHaveValue(BOOTSTRAP_PROMPT);
    expect(screen.queryByTestId('clarification-auto-send-banner')).not.toBeInTheDocument();
  });
});
