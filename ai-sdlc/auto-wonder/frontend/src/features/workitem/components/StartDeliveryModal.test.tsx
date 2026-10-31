import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { message } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { ApiError } from '@/shared/types/common';
import { StartDeliveryModal } from './StartDeliveryModal';
import {
  readClarificationPrefill,
  writeClarificationPrefill,
} from '../clarification/prefill';

const { mutateAsync } = vi.hoisted(() => ({ mutateAsync: vi.fn() }));

vi.mock('../hooks', () => ({
  useAssignWorkitem: () => ({ mutateAsync, isPending: false }),
}));

function renderModal(hasSdlc = false, workitemId: number | string = 10000) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <StartDeliveryModal
        open
        workitemId={workitemId}
        hasSdlc={hasSdlc}
        onClose={() => {}}
      />
    </QueryClientProvider>,
  );
}

describe('StartDeliveryModal', () => {
  beforeEach(() => {
    window.localStorage.clear();
    mutateAsync.mockReset();
  });

  it('keeps invalid delivery confirmation inline without assignment or closing', async () => {
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    const close = vi.fn();
    render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><StartDeliveryModal open workitemId={10000} onClose={close} /></QueryClientProvider>);
    await userEvent.click(screen.getByRole('button', { name: /启\s*动\s*交\s*付/ }));
    expect(await screen.findByText('请选择小队')).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(mutateAsync).not.toHaveBeenCalled(); expect(close).not.toHaveBeenCalled();
  });

  it('loads squads instead of online agents and hides SDLC selection', async () => {
    const requestedAgentUrls: string[] = [];
    let squadsRequested = false;

    server.use(
      http.get('/api/squads', () => {
        squadsRequested = true;
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: { list: [{ id: 1, name: '交付小队', description: '', memberCount: 2, gmtCreate: '' }], total: 1, pageNum: 1, pageSize: 100 },
        });
      }),
      http.get('/api/agents', ({ request }) => {
        requestedAgentUrls.push(request.url);
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [] });
      }),
    );

    renderModal();

    expect(screen.queryByLabelText('SDLC 流程')).not.toBeInTheDocument();
    expect(await screen.findByLabelText('小队')).toBeInTheDocument();
    expect(screen.getByLabelText('首步执行 Agent')).toBeInTheDocument();
    await waitFor(() => expect(squadsRequested).toBe(true));
    expect(requestedAgentUrls.some((url) => url.includes('status=ONLINE'))).toBe(false);
  });

  it('prefills from clarification localStorage for a fresh delivery', async () => {
    writeClarificationPrefill('10000', { squadId: 1, agentId: 77 });
    server.use(
      http.get('/api/squads', () =>
        HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: { list: [{ id: 1, name: '交付小队', description: '', memberCount: 1, gmtCreate: '' }], total: 1, pageNum: 1, pageSize: 100 },
        }),
      ),
      http.get('/api/squads/:squadId/members', () =>
        HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: [{ agentId: 77, agentName: 'Agent-77', roleCode: 'AW_FS_DEV' }],
        }),
      ),
    );

    renderModal(false, '10000');
    expect(await screen.findByText('交付小队')).toBeInTheDocument();
  });

  it('does not prefill when reassigning an existing delivery', async () => {
    writeClarificationPrefill('10000', { squadId: 1, agentId: 77 });
    server.use(
      http.get('/api/squads', () =>
        HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: { list: [{ id: 1, name: '交付小队', description: '', memberCount: 1, gmtCreate: '' }], total: 1, pageNum: 1, pageSize: 100 },
        }),
      ),
      http.get('/api/squads/:squadId/members', () =>
        HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: [{ agentId: 77, agentName: 'Agent-77', roleCode: 'AW_FS_DEV' }],
        }),
      ),
    );

    renderModal(true, '10000');
    // Wait for squads to finish loading; no squad label should appear because
    // the re-assign flow skips the clarification prefill.
    await waitFor(() => expect(screen.getByLabelText('小队')).toBeInTheDocument());
    expect(screen.queryByText('交付小队')).not.toBeInTheDocument();
  });

  it('exposes an optional scheduled start time picker', async () => {
    server.use(
      http.get('/api/squads', () =>
        HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: { list: [{ id: 1, name: '交付小队', description: '', memberCount: 1, gmtCreate: '' }], total: 1, pageNum: 1, pageSize: 100 },
        }),
      ),
    );

    renderModal();

    const field = await screen.findByLabelText('计划执行时间（可选）');
    expect(field).toBeInTheDocument();
    expect(screen.getByPlaceholderText('留空则立即执行')).toBeInTheDocument();
  });

  const squadHandlers = [
    http.get('/api/squads', () =>
      HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { list: [{ id: 1, name: 'AW交付组', description: '', memberCount: 1, gmtCreate: '' }], total: 1, pageNum: 1, pageSize: 100 },
      }),
    ),
    http.get('/api/squads/:squadId/members', () =>
      HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{ agentId: 77, agentName: 'Agent-77', roleCode: 'AW_FS_DEV' }],
      }),
    ),
  ];

  async function pickSquadAndAgent() {
    await userEvent.click(screen.getByLabelText('小队'));
    await userEvent.click(await screen.findByText('AW交付组'));
    await userEvent.click(await screen.findByLabelText('首步执行 Agent'));
    await userEvent.click(await screen.findByText('Agent-77 (AW_FS_DEV)'));
  }

  it('delivers immediately without a scheduled time when left blank', async () => {
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    mutateAsync.mockResolvedValue({ id: 10000 });
    const success = vi.spyOn(message, 'success').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.success>,
    );
    server.use(...squadHandlers);
    const onClose = vi.fn();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <StartDeliveryModal open workitemId={10000} onClose={onClose} />
      </QueryClientProvider>,
    );
    await pickSquadAndAgent();

    await userEvent.click(screen.getByRole('button', { name: /启\s*动\s*交\s*付/ }));

    await waitFor(() => {
      expect(mutateAsync).toHaveBeenCalledWith(
        expect.objectContaining({ assigneeRef: 77, squadId: 1, scheduledStartAt: undefined }),
      );
    });
    expect(success).toHaveBeenCalledWith('已启动交付');
    expect(onClose).toHaveBeenCalled();
    success.mockRestore();
  });

  it('surfaces the backend error message when assignment fails', async () => {
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    mutateAsync.mockRejectedValue(new ApiError('10001', '参数不合法', 'trace'));
    const error = vi.spyOn(message, 'error').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.error>,
    );
    server.use(...squadHandlers);
    const onClose = vi.fn();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <StartDeliveryModal open workitemId={10000} onClose={onClose} />
      </QueryClientProvider>,
    );
    await pickSquadAndAgent();

    await userEvent.click(screen.getByRole('button', { name: /启\s*动\s*交\s*付/ }));

    await waitFor(() => expect(error).toHaveBeenCalledWith('参数不合法'));
    expect(onClose).not.toHaveBeenCalled();
    error.mockRestore();
  });

  it('shows a fallback message when the failure is not an ApiError', async () => {
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    mutateAsync.mockRejectedValue(new Error('boom'));
    const error = vi.spyOn(message, 'error').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.error>,
    );
    server.use(...squadHandlers);
    const onClose = vi.fn();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <StartDeliveryModal open workitemId={10000} onClose={onClose} />
      </QueryClientProvider>,
    );
    await pickSquadAndAgent();

    await userEvent.click(screen.getByRole('button', { name: /启\s*动\s*交\s*付/ }));

    await waitFor(() => expect(error).toHaveBeenCalledWith('启动交付失败，请稍后重试'));
    expect(onClose).not.toHaveBeenCalled();
    error.mockRestore();
  });

  it('excludes platform agents from first-step executor options', async () => {
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    server.use(
      http.get('/api/squads', () =>
        HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { list: [{ id: 1, name: 'AW交付组', description: '', memberCount: 2, gmtCreate: '' }], total: 1, pageNum: 1, pageSize: 100 },
        }),
      ),
      http.get('/api/squads/:squadId/members', () =>
        HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [
            { agentId: 77, agentName: 'Agent-77', roleCode: 'AW_FS_DEV', agentKind: 'STANDARD' },
            { agentId: 9, agentName: 'Chief of Staff', roleCode: 'chief_of_staff', agentKind: 'PLATFORM' },
          ],
        }),
      ),
    );

    renderModal();

    await userEvent.click(await screen.findByLabelText('小队'));
    await userEvent.click(await screen.findByText('AW交付组'));
    await userEvent.click(await screen.findByLabelText('首步执行 Agent'));
    expect(await screen.findByText('Agent-77 (AW_FS_DEV)')).toBeInTheDocument();
    expect(screen.queryByText(/Chief of Staff/)).not.toBeInTheDocument();
  });

  it('sends a restart token and restart-aware message when reassigning immediately', async () => {
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    mutateAsync.mockResolvedValue({ id: 10000 });
    const success = vi.spyOn(message, 'success').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.success>,
    );
    server.use(...squadHandlers);
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <StartDeliveryModal open workitemId={10000} hasSdlc onClose={() => {}} />
      </QueryClientProvider>,
    );
    await pickSquadAndAgent();

    await userEvent.click(screen.getByRole('button', { name: /重\s*新\s*指\s*派/ }));

    await waitFor(() => {
      expect(mutateAsync).toHaveBeenCalledWith(
        expect.objectContaining({
          assigneeRef: 77,
          squadId: 1,
          scheduledStartAt: undefined,
          restartToken: expect.any(String),
        }),
      );
    });
    expect(success).toHaveBeenCalledWith('已重新指派，正在重启交付');
    success.mockRestore();
  });

  it('announces a deferred restart when reassigning with a scheduled start time', async () => {
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    mutateAsync.mockResolvedValue({ id: 10000 });
    const success = vi.spyOn(message, 'success').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.success>,
    );
    server.use(...squadHandlers);
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <StartDeliveryModal open workitemId={10000} hasSdlc onClose={() => {}} />
      </QueryClientProvider>,
    );
    await pickSquadAndAgent();

    const tomorrow = new Date(Date.now() + 86_400_000);
    const pad = (n: number) => String(n).padStart(2, '0');
    const planned = `${tomorrow.getFullYear()}-${pad(tomorrow.getMonth() + 1)}-${pad(tomorrow.getDate())} 23:59:00`;
    const dateInput = screen.getByPlaceholderText('留空则立即执行');
    await userEvent.type(dateInput, planned);
    await userEvent.keyboard('{Enter}');

    await userEvent.click(screen.getByRole('button', { name: /重\s*新\s*指\s*派/ }));

    await waitFor(() => {
      expect(mutateAsync).toHaveBeenCalledWith(
        expect.objectContaining({ scheduledStartAt: expect.any(String) }),
      );
    });
    expect(success).toHaveBeenCalledWith('已重新指派，将在计划时间重启交付');
    success.mockRestore();
  });

  function mockDeliveryDefaults(options: {
    space: { squadId: number | null; agentId: number | null; valid?: boolean };
    clarify?: { squadId: number | null; agentId: number | null };
  }) {
    server.use(
      http.get('/api/workspaces/current/ai-defaults', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: {
          owner: false,
          clarify: {
            defaultSquadId: options.clarify?.squadId ?? null,
            defaultAgentId: options.clarify?.agentId ?? null,
            autoSendFirstPrompt: true,
            valid: true,
          },
          delivery: {
            defaultSquadId: options.space.squadId,
            defaultAgentId: options.space.agentId,
            valid: options.space.valid ?? true,
          },
        },
      })),
      http.get('/api/squads', () =>
        HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            list: [
              { id: 1, name: '交付小队', description: '', memberCount: 2, gmtCreate: '' },
              { id: 2, name: '本地小队', description: '', memberCount: 1, gmtCreate: '' },
              { id: 9, name: '澄清小队', description: '', memberCount: 1, gmtCreate: '' },
            ],
            total: 3,
            pageNum: 1,
            pageSize: 100,
          },
        })),
      http.get('/api/squads/:squadId/members', ({ params }) => {
        const id = String(params.squadId);
        const members = id === '2'
          ? [{ agentId: 88, agentName: 'Agent-88', roleCode: 'AW_FS_DEV', agentKind: 'STANDARD' }]
          : id === '9'
            ? [{ agentId: 42, agentName: 'Agent-42', roleCode: 'AW_FS_DEV', agentKind: 'STANDARD' }]
            : [
              { agentId: 77, agentName: 'Agent-77', roleCode: 'AW_FS_DEV', agentKind: 'STANDARD' },
              { agentId: 9, agentName: 'Chief of Staff', roleCode: 'chief_of_staff', agentKind: 'PLATFORM' },
            ];
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: members });
      }),
    );
  }

  it('prefills the delivery defaults independently from clarification defaults', async () => {
    mockDeliveryDefaults({
      space: { squadId: 1, agentId: 77 },
      clarify: { squadId: 9, agentId: 42 },
    });

    renderModal();

    expect(await screen.findByText('交付小队')).toBeInTheDocument();
    expect(await screen.findByText('Agent-77 (AW_FS_DEV)')).toBeInTheDocument();
    expect(screen.queryByText('澄清小队')).not.toBeInTheDocument();
  });

  it('uses the local delivery override before the space delivery default', async () => {
    window.localStorage.setItem('aw:delivery-defaults:local', JSON.stringify({
      defaultSquadId: 2,
      defaultAgentId: 88,
    }));
    mockDeliveryDefaults({ space: { squadId: 1, agentId: 77 } });

    renderModal();

    expect(await screen.findByText('本地小队')).toBeInTheDocument();
    expect(await screen.findByText('Agent-88 (AW_FS_DEV)')).toBeInTheDocument();
    expect(screen.queryByText('交付小队')).not.toBeInTheDocument();
  });

  it('skips a delivery default the server marked invalid without blocking the modal', async () => {
    mockDeliveryDefaults({ space: { squadId: 1, agentId: 99, valid: false } });

    renderModal();

    expect(await screen.findByText('选择交付小队')).toBeInTheDocument();
    expect(screen.queryByText('交付小队')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /启\s*动\s*交\s*付/ })).toBeInTheDocument();
  });

  it('falls back to the space delivery default when the local pair is invalid', async () => {
    window.localStorage.setItem('aw:delivery-defaults:local', JSON.stringify({
      defaultSquadId: 2,
      defaultAgentId: 99,
    }));
    mockDeliveryDefaults({ space: { squadId: 1, agentId: 77 } });

    renderModal();

    expect(await screen.findByText('交付小队')).toBeInTheDocument();
    expect(await screen.findByText('Agent-77 (AW_FS_DEV)')).toBeInTheDocument();
    expect(screen.queryByText('本地小队')).not.toBeInTheDocument();
  });

  it('ignores stale local delivery defaults for the space owner', async () => {
    window.localStorage.setItem('aw:delivery-defaults:local', JSON.stringify({
      defaultSquadId: 2,
      defaultAgentId: 88,
    }));
    mockDeliveryDefaults({ space: { squadId: 1, agentId: 77 } });
    server.use(
      http.get('/api/workspaces/current/ai-defaults', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: {
          owner: true,
          clarify: {
            defaultSquadId: null, defaultAgentId: null, autoSendFirstPrompt: false, valid: true,
          },
          delivery: { defaultSquadId: 1, defaultAgentId: 77, valid: true },
        },
      })),
    );

    renderModal();

    expect(await screen.findByText('交付小队')).toBeInTheDocument();
    expect(await screen.findByText('Agent-77 (AW_FS_DEV)')).toBeInTheDocument();
    expect(screen.queryByText('本地小队')).not.toBeInTheDocument();
  });

  it('does not use clarification defaults when delivery defaults are unset', async () => {
    mockDeliveryDefaults({
      space: { squadId: null, agentId: null },
      clarify: { squadId: 1, agentId: 77 },
    });

    renderModal();

    expect(await screen.findByText('选择交付小队')).toBeInTheDocument();
    expect(screen.queryByText('交付小队')).not.toBeInTheDocument();
  });

  it('writes the picked pair into the workitem memory', async () => {
    server.use(...squadHandlers);

    renderModal();
    await pickSquadAndAgent();

    expect(readClarificationPrefill('10000')).toMatchObject({ squadId: 1, agentId: 77 });
  });

  it('allocates a fresh restart token per modal session for explicit new rounds', async () => {
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    mutateAsync.mockResolvedValue({ id: 10000 });
    server.use(...squadHandlers);
    const tokens: string[] = [];

    for (let session = 0; session < 2; session++) {
      const view = render(
        <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
          <StartDeliveryModal open workitemId={10000} hasSdlc onClose={() => {}} />
        </QueryClientProvider>,
      );
      await pickSquadAndAgent();
      await userEvent.click(screen.getByRole('button', { name: /重\s*新\s*指\s*派/ }));
      await waitFor(() => expect(mutateAsync).toHaveBeenCalledTimes(session + 1));
      tokens.push(mutateAsync.mock.calls[session][0].restartToken);
      view.unmount();
    }

    expect(tokens[0]).toEqual(expect.any(String));
    expect(tokens[1]).not.toBe(tokens[0]);
  });
});
