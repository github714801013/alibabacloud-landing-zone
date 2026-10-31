import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
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

const agentNames: Record<number, string> = {
  42: 'Agent-A',
  71: 'Agent-B',
};

type ClarifyDefaults = {
  defaultSquadId: number | null;
  defaultAgentId: number | null;
  autoSendFirstPrompt: boolean;
  valid: boolean;
};

function mockDefaultsHandlers(clarify: ClarifyDefaults, postedAgentIds: number[], owner = false) {
  server.use(
    http.get('/api/workspaces/current/ai-defaults', () => HttpResponse.json(apiOk({
      owner,
      clarify,
      delivery: { defaultSquadId: null, defaultAgentId: null, valid: true },
    }))),
    http.get('/api/squads', () => HttpResponse.json(apiOk({
      list: [
        { id: 9, name: '澄清小队', description: '', memberCount: 1, gmtCreate: '' },
        { id: 8, name: '交付小队', description: '', memberCount: 1, gmtCreate: '' },
      ],
      total: 2,
      pageNum: 1,
      pageSize: 100,
    }))),
    http.get('/api/squads/:squadId/members', ({ params }) => HttpResponse.json(apiOk(
      String(params.squadId) === '8'
        ? [{ agentId: 71, agentName: 'Agent-B', roleCode: 'AW_FS_DEV' }]
        : [{ agentId: 42, agentName: 'Agent-A', roleCode: 'AW_FS_DEV' }],
    ))),
    http.get('/api/workitems/:workitemId/clarification-conversations', () => HttpResponse.json(apiOk([]))),
    http.post('/api/workitems/:workitemId/clarification-conversations', async ({ request }) => {
      const body = await request.json() as { agentId?: number };
      const agentId = body.agentId ?? 0;
      postedAgentIds.push(agentId);
      return HttpResponse.json(apiOk({
        id: 5,
        agentId,
        agentName: agentNames[agentId] ?? 'Agent',
        channelConversationId: 'ch-5',
        status: 'ACTIVE',
        executorOnline: true,
        streamingSupported: true,
        cliSessionRef: null,
        processingStatus: null,
        processingTurnId: null,
        lastTurnAt: null,
        gmtCreate: '2026-01-01T00:00:00',
        turns: [],
      }));
    }),
    http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () =>
      HttpResponse.json(apiOk(null))),
    http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId/events', () =>
      HttpResponse.json(apiOk([]))),
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

describe('WorkitemClarificationPanel 默认小队/数字人', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('没有工单记忆时用空间默认自动创建首个会话', async () => {
    const postedAgentIds: number[] = [];
    mockDefaultsHandlers({
      defaultSquadId: 9, defaultAgentId: 42, autoSendFirstPrompt: false, valid: true,
    }, postedAgentIds);

    renderPanel();

    await waitFor(() => {
      expect(postedAgentIds).toEqual([42]);
    });
    expect(screen.queryByTestId('clarification-default-invalid')).not.toBeInTheDocument();
  });

  it('工单记忆优先于空间默认', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    const postedAgentIds: number[] = [];
    mockDefaultsHandlers({
      defaultSquadId: 8, defaultAgentId: 71, autoSendFirstPrompt: false, valid: true,
    }, postedAgentIds);

    renderPanel();

    await waitFor(() => {
      expect(postedAgentIds).toEqual([42]);
    });
  });

  it('默认组合被服务端判定失效时跳过默认并提示', async () => {
    const postedAgentIds: number[] = [];
    mockDefaultsHandlers({
      defaultSquadId: 9, defaultAgentId: 99, autoSendFirstPrompt: false, valid: false,
    }, postedAgentIds);

    renderPanel();

    expect(await screen.findByTestId('clarification-default-invalid')).toBeInTheDocument();
    expect(screen.getByTestId('clarification-selection-column')).toBeInTheDocument();
    expect(postedAgentIds).toEqual([]);
  });

  it('本地默认失效时回退到有效的空间默认并提示', async () => {
    localStorage.setItem('aw:clarify-defaults:local', JSON.stringify({
      defaultSquadId: 8,
      defaultAgentId: 99,
    }));
    const postedAgentIds: number[] = [];
    mockDefaultsHandlers({
      defaultSquadId: 9, defaultAgentId: 42, autoSendFirstPrompt: false, valid: true,
    }, postedAgentIds);

    renderPanel();

    await waitFor(() => {
      expect(postedAgentIds).toEqual([42]);
    });
    expect(await screen.findByText('已设置的默认小队或数字员工不可用，已跳过该默认。')).toBeInTheDocument();
  });

  it('本地默认有效时优先于空间默认', async () => {
    localStorage.setItem('aw:clarify-defaults:local', JSON.stringify({
      defaultSquadId: 8,
      defaultAgentId: 71,
    }));
    const postedAgentIds: number[] = [];
    mockDefaultsHandlers({
      defaultSquadId: 9, defaultAgentId: 42, autoSendFirstPrompt: false, valid: true,
    }, postedAgentIds);

    renderPanel();

    await waitFor(() => {
      expect(postedAgentIds).toEqual([71]);
    });
  });

  it('空间拥有人忽略浏览器里残留的本地覆盖', async () => {
    localStorage.setItem('aw:clarify-defaults:local', JSON.stringify({
      defaultSquadId: 8,
      defaultAgentId: 71,
    }));
    const postedAgentIds: number[] = [];
    mockDefaultsHandlers({
      defaultSquadId: 9, defaultAgentId: 42, autoSendFirstPrompt: false, valid: true,
    }, postedAgentIds, true);

    renderPanel();

    await waitFor(() => {
      expect(postedAgentIds).toEqual([42]);
    });
  });

  it('本地显式清空默认时不回退空间默认', async () => {
    localStorage.setItem('aw:clarify-defaults:local', JSON.stringify({
      defaultSquadId: null,
      defaultAgentId: null,
    }));
    const postedAgentIds: number[] = [];
    mockDefaultsHandlers({
      defaultSquadId: 9, defaultAgentId: 42, autoSendFirstPrompt: false, valid: true,
    }, postedAgentIds);

    renderPanel();

    await waitFor(() => {
      expect(screen.getByTestId('clarification-selection-column')).toBeInTheDocument();
    });
    expect(postedAgentIds).toEqual([]);
    expect(screen.queryByTestId('clarification-default-invalid')).not.toBeInTheDocument();
  });

  it('使用空间默认会清除工单记忆并按默认重新选人', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    const postedAgentIds: number[] = [];
    mockDefaultsHandlers({
      defaultSquadId: 8, defaultAgentId: 71, autoSendFirstPrompt: false, valid: true,
    }, postedAgentIds);

    renderPanel();
    await waitFor(() => {
      expect(postedAgentIds).toEqual([42]);
    });

    await userEvent.click(screen.getByRole('button', { name: '切换数字员工' }));
    await screen.findByTestId('clarification-selection-column');
    await userEvent.click(screen.getByRole('button', { name: '使用空间默认' }));

    await waitFor(() => {
      expect(postedAgentIds).toEqual([42, 71]);
    });
    expect(readClarificationPrefill('100')).toBeNull();
  });
});
