import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useSwitchWorkspace } from './useSwitchWorkspace';
import { useAuthStore } from '@/shared/auth/store';

const realtime = vi.hoisted(() => ({ syncRealtimeAuth: vi.fn() }));
vi.mock('@/shared/realtime/client', () => realtime);

describe('useSwitchWorkspace 实时连接改绑', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    realtime.syncRealtimeAuth.mockClear();
  });

  it('切换成功后用新 token 改绑实时连接', async () => {
    server.use(
      http.post('/api/workspaces/9/switch', () => HttpResponse.json({
        success: true, code: '0', message: '',
        data: { accessToken: 'workspace-token', accessLevel: 'READ_WRITE' },
        traceId: null,
      })),
    );
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { result } = renderHook(() => useSwitchWorkspace(), {
      wrapper: ({ children }) => (
        <QueryClientProvider client={queryClient}>
          <MemoryRouter>{children}</MemoryRouter>
        </QueryClientProvider>
      ),
    });

    let switched = false;
    await act(async () => {
      switched = await result.current({ id: 9, name: 'Workspace', description: '' });
    });

    expect(switched).toBe(true);
    expect(realtime.syncRealtimeAuth).toHaveBeenCalledWith('workspace-token');
  });
});
