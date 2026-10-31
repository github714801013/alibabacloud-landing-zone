import { beforeEach, describe, expect, it } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { AiCollaborationDefaultsPage } from './AiCollaborationDefaultsPage';
import type { AiCollaborationDefaultItem } from './api';

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
