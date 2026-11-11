import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { UserMenu } from './UserMenu';
import { useAuthStore } from '@/shared/auth/store';

const realtime = vi.hoisted(() => ({ disconnectRealtime: vi.fn() }));
vi.mock('@/shared/realtime/client', () => realtime);
vi.mock('@/features/auth/api', () => ({ logout: vi.fn().mockResolvedValue(undefined) }));

describe('UserMenu 登出', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    realtime.disconnectRealtime.mockClear();
  });

  it('登出时关闭实时连接', async () => {
    useAuthStore.getState().setUser({
      id: 1,
      username: 'alice',
      nickname: '爱丽丝',
      email: 'alice@example.com',
    });
    useAuthStore.getState().setTokens('access', 'refresh');
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/workitems']}>
          <UserMenu />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '用户菜单' }));
    await user.click(screen.getByText('退出登录'));

    await waitFor(() => expect(realtime.disconnectRealtime).toHaveBeenCalledTimes(1));
  });
});
