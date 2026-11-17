import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { WorkitemClarificationPanel } from './WorkitemClarificationPanel';
import { mockLatestClarificationConversation } from '@/test/mocks/clarificationLatest';
import { clearClarificationPrefill, writeClarificationPrefill } from './prefill';

type RealtimeCallback = (event: { type: string; payload: unknown }) => void;
const realtime = vi.hoisted(() => ({ callback: null as RealtimeCallback | null }));

vi.mock('@/shared/realtime/useRealtime', () => ({
  useRealtime: (_channel: unknown, opts: { onEvent: RealtimeCallback }) => {
    realtime.callback = opts.onEvent;
  },
}));

const RETRY_PATH =
  '/api/workitems/:workitemId/clarification-conversations/:conversationId/turns/:turnId/retry';

function ok(data: unknown) {
  return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data });
}

interface Turn {
  id: number;
  direction: string;
  content: string;
  status: string;
  error: string | null;
  gmtCreate: string;
}

function turn(id: number, direction: string, content: string, status: string, error: string | null = null): Turn {
  return { id, direction, content, status, error, gmtCreate: '2026-01-01T00:00:00' };
}

function conversation(overrides: Record<string, unknown> = {}) {
  return {
    id: 1, agentId: 42, agentName: 'Agent-X', channelConversationId: 'ch-1',
    status: 'ACTIVE', executorOnline: true, streamingSupported: true, cancelSupported: true,
    cliSessionRef: null, processingStatus: null, processingTurnId: null,
    lastTurnAt: '2026-01-01T00:00:00', gmtCreate: '2026-01-01T00:00:00',
    turns: [],
    ...overrides,
  };
}

/** 详情由可变引用提供：重试成功后模拟服务端把同一 IN 轮次改回处理中。 */
function mockConversation(current: () => ReturnType<typeof conversation>) {
  server.use(
    http.get('/api/workitems/:workitemId/clarification-conversations', () => ok([current()])),
    http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () => ok(current())),
    http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId/events', () => ok([])),
  );
  // 必须最后注册：`:conversationId` 详情 handler 也会匹配 `latest`。
  mockLatestClarificationConversation();
}

function renderPanel() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <WorkitemClarificationPanel workitemId="100" />
    </QueryClientProvider>,
  );
}

async function waitForUserBubble() {
  const bubbles = await screen.findAllByTestId('clarification-user-bubble', {}, { timeout: 5_000 });
  return bubbles[0];
}

/** 会话详情与输入框都就绪：否则「重试」可能因加载态被暂时隐藏。 */
async function waitForPanelReady() {
  const bubble = await waitForUserBubble();
  const composer = await screen.findByTestId('clarification-composer-input');
  await waitFor(() => expect(composer).toHaveAttribute('data-disabled', 'false'));
  return bubble;
}

describe('WorkitemClarificationPanel 会话重试', () => {
  beforeEach(() => {
    window.localStorage.clear();
    realtime.callback = null;
    useAuthStore.setState({ accessLevel: 'READ_WRITE', readOnlyDomains: [] });
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
  });

  afterEach(() => {
    cleanup();
    useAuthStore.setState({ accessLevel: null, readOnlyDomains: [] });
    clearClarificationPrefill('100');
  });

  it('offers retry on the latest failed user message and posts that turn id', async () => {
    let current = conversation({
      turns: [
        turn(11, 'IN', '请帮我确认需求', 'FAILED'),
        turn(12, 'OUT', '', 'FAILED', 'conversation actor capabilities unavailable'),
      ],
    });
    mockConversation(() => current);
    let retried: string | null = null;
    server.use(http.post(RETRY_PATH, ({ params }) => {
      retried = `${params.conversationId}/${params.turnId}`;
      return ok(null);
    }));

    renderPanel();
    const bubble = await waitForPanelReady();
    const retry = await within(bubble).findByRole('button', { name: /重试/ });

    fireEvent.click(retry);

    await waitFor(() => expect(retried).toBe('1/11'));
  });

  it('hides the stale failure bubble and the button once the retried turn is processing again', async () => {
    let current = conversation({
      turns: [
        turn(11, 'IN', '请帮我确认需求', 'FAILED'),
        turn(12, 'OUT', '', 'FAILED', 'conversation actor capabilities unavailable'),
      ],
    });
    mockConversation(() => current);
    server.use(http.post(RETRY_PATH, () => {
      current = conversation({
        processingStatus: 'PROCESSING',
        processingTurnId: 11,
        turns: [
          turn(11, 'IN', '请帮我确认需求', 'PROCESSING'),
          turn(12, 'OUT', '', 'FAILED', 'conversation actor capabilities unavailable'),
        ],
      });
      return ok(null);
    }));

    renderPanel();
    const bubble = await waitForPanelReady();
    expect(screen.getByText('conversation actor capabilities unavailable')).toBeInTheDocument();

    fireEvent.click(within(bubble).getByRole('button', { name: /重试/ }));

    await waitFor(() => {
      expect(screen.queryByText('conversation actor capabilities unavailable')).toBeNull();
      expect(screen.queryByRole('button', { name: /重试/ })).toBeNull();
    });
  });

  it('hides the stale failure bubble while the retried turn waits in the queue', async () => {
    let current = conversation({
      turns: [
        turn(11, 'IN', '请帮我确认需求', 'FAILED'),
        turn(12, 'OUT', '', 'FAILED', 'conversation actor capabilities unavailable'),
      ],
    });
    mockConversation(() => current);
    server.use(http.post(RETRY_PATH, () => {
      current = conversation({
        turns: [
          turn(11, 'IN', '请帮我确认需求', 'QUEUED'),
          turn(12, 'OUT', '', 'FAILED', 'conversation actor capabilities unavailable'),
        ],
      });
      return ok(null);
    }));

    renderPanel();
    const bubble = await waitForPanelReady();
    expect(screen.getByText('conversation actor capabilities unavailable')).toBeInTheDocument();

    fireEvent.click(within(bubble).getByRole('button', { name: /重试/ }));

    await waitFor(() => {
      expect(screen.queryByText('conversation actor capabilities unavailable')).toBeNull();
    });
  });

  it('shows the server message when the retry is rejected', async () => {
    let current = conversation({
      turns: [
        turn(11, 'IN', '请帮我确认需求', 'FAILED'),
        turn(12, 'OUT', '', 'FAILED', 'conversation actor capabilities unavailable'),
      ],
    });
    mockConversation(() => current);
    server.use(http.post(RETRY_PATH, () => HttpResponse.json(
      { success: false, code: '13030', message: '执行器离线，请稍后重试', traceId: null, data: null },
      { status: 500 },
    )));

    renderPanel();
    const bubble = await waitForPanelReady();

    fireEvent.click(await within(bubble).findByRole('button', { name: /重试/ }));

    // 执行器离线属于渠道层错误，只存在于服务端文案里，不能吞成通用失败提示。
    expect(await screen.findByText('执行器离线，请稍后重试')).toBeInTheDocument();
    await waitFor(() => expect(within(bubble).getByRole('button', { name: /重试/ })).toBeEnabled());
  });

  it('keeps the button hidden for read-only members', async () => {
    useAuthStore.setState({ accessLevel: 'READ_ONLY', readOnlyDomains: [] });
    mockConversation(() => conversation({
      turns: [
        turn(11, 'IN', '请帮我确认需求', 'FAILED'),
        turn(12, 'OUT', '', 'FAILED', 'conversation actor capabilities unavailable'),
      ],
    }));

    renderPanel();
    const bubble = await waitForUserBubble();

    expect(within(bubble).queryByRole('button', { name: /重试/ })).toBeNull();
  });

  it('only offers retry for the latest inbound turn, never an older failure', async () => {
    mockConversation(() => conversation({
      turns: [
        turn(11, 'IN', '更早的失败消息', 'FAILED'),
        turn(12, 'OUT', '', 'FAILED', 'conversation actor capabilities unavailable'),
        turn(13, 'IN', '后续已成功的消息', 'SUCCESS'),
        turn(14, 'OUT', '后续回复', 'SUCCESS'),
      ],
    }));

    renderPanel();
    await waitForUserBubble();

    expect(await screen.findByText('后续已成功的消息')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /重试/ })).toBeNull();
    // 旧失败气泡在它自己的 IN 仍为 FAILED 时保留，只是不再提供重试入口。
    expect(screen.getByText('conversation actor capabilities unavailable')).toBeInTheDocument();
  });
});
