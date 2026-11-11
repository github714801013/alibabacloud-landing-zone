import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { message } from 'antd';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { WecomBindingPanel } from './WecomBindingPanel';
import type { WecomBinding } from './wecomApi';

const ok = (data: unknown) => ({ success: true, code: '0', message: '', data });

const binding: WecomBinding = {
  id: 1,
  platformConfigId: 9,
  groupId: 'wecom-chat',
  routeVersion: 3,
  agentId: 7,
  creatorId: 100,
  status: 'ENABLED',
  version: 4,
  lastSuccessAt: null,
  lastError: null,
};

function publicConfig(overrides: Record<string, unknown> = {}) {
  return {
    provider: 'WECOM',
    configured: true,
    ready: true,
    desiredEnabled: true,
    appKey: 'wecom-bot',
    robotCode: null,
    selectionGeneration: 1,
    status: 'ACTIVE',
    drainingCount: 0,
    failedCount: 0,
    unknownCount: 0,
    ...overrides,
  };
}

function renderPanel() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter><WecomBindingPanel /></MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  useAuthStore.getState().clear();
  useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'ADMIN');
  server.use(
    http.get('/api/agents', () => HttpResponse.json(ok([{ id: 7, name: 'Alpha' }, { id: 8, name: 'Beta' }]))),
    http.get('/api/integrations/channels/group-config', () => HttpResponse.json(ok(publicConfig()))),
    http.get('/api/integrations/wecom/bindings', () => HttpResponse.json(ok([]))),
  );
});
afterEach(() => message.destroy());

describe('WecomBindingPanel', () => {
  it('creates a group binding only after the delegation is confirmed', async () => {
    const user = userEvent.setup();
    let payload: Record<string, unknown> | undefined;
    server.use(
      http.post('/api/integrations/wecom/bindings', async ({ request }) => {
        payload = await request.json() as Record<string, unknown>;
        return HttpResponse.json(ok({ ...binding, id: 2 }));
      }),
    );
    renderPanel();

    await user.click(await screen.findByRole('button', { name: '新建企业微信绑定' }));
    expect(await screen.findByText('先获取群 ID')).toBeInTheDocument();
    expect(screen.getByText(/委托主体：创建该绑定的空间管理员/)).toBeInTheDocument();
    await user.type(screen.getByLabelText('群 ID'), 'wecom-chat');
    await user.click(screen.getByLabelText('关联数字员工'));
    await user.click(await screen.findByText('Alpha', { selector: '.ant-select-item-option-content' }));

    await user.click(screen.getByRole('button', { name: '保存' }));
    expect(await screen.findByText('请先确认已获得该群的委托授权')).toBeInTheDocument();
    expect(payload).toBeUndefined();

    await user.click(screen.getByRole('checkbox', { name: /我确认已获得该群管理员/ }));
    await user.click(screen.getByRole('button', { name: '保存' }));
    await waitFor(() => expect(payload).toMatchObject({
      groupId: 'wecom-chat',
      agentId: 7,
      status: 'ENABLED',
      authorizationConfirmed: true,
    }));
    expect(payload).not.toHaveProperty('version');
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '新建企业微信绑定' })).not.toBeInTheDocument());
  });

  it('shows the binding creator as the delegation subject in the detail drawer', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/integrations/wecom/bindings', () => HttpResponse.json(ok([binding]))),
      http.get('/api/workspaces/current/members', () => HttpResponse.json(ok([{
        userId: 100,
        username: 'delegator',
        email: 'delegator@example.test',
        nickname: '委托管理员',
        joinedAt: '2026-01-01T00:00:00Z',
        owner: true,
        accessLevel: 'ADMIN',
        identityTags: [],
        roleId: 1,
        roleCode: null,
        roleName: null,
        readOnlyDomains: [],
      }]))),
    );
    renderPanel();

    await user.click(await screen.findByRole('button', { name: /详\s*情/ }));
    const drawer = within(await screen.findByRole('dialog', { name: '绑定详情' }));
    expect(await drawer.findByText('委托主体: delegator')).toBeInTheDocument();
  });

  it('sends the row version when disabling a binding without re-confirming delegation', async () => {
    const user = userEvent.setup();
    let payload: Record<string, unknown> | undefined;
    server.use(
      http.get('/api/integrations/wecom/bindings', () => HttpResponse.json(ok([binding]))),
      http.put('/api/integrations/wecom/bindings/1', async ({ request }) => {
        payload = await request.json() as Record<string, unknown>;
        return HttpResponse.json(ok({ ...binding, status: 'DISABLED', version: 5 }));
      }),
    );
    renderPanel();

    await user.click(await screen.findByRole('button', { name: /编\s*辑/ }));
    const drawer = within(await screen.findByRole('dialog', { name: '编辑企业微信绑定' }));
    await user.click(drawer.getByLabelText('绑定状态'));
    await user.click(await screen.findByText('停用', { selector: '.ant-select-item-option-content' }));
    await user.click(drawer.getByRole('button', { name: '保存' }));

    await waitFor(() => expect(payload).toEqual({
      groupId: 'wecom-chat',
      agentId: 7,
      status: 'DISABLED',
      version: 4,
      authorizationConfirmed: false,
    }));
  });

  it('requires confirmation when changing the delegated agent and surfaces the conflict', async () => {
    const user = userEvent.setup();
    let writes = 0;
    server.use(
      http.get('/api/integrations/wecom/bindings', () => HttpResponse.json(ok([binding]))),
      http.put('/api/integrations/wecom/bindings/1', () => {
        writes += 1;
        return HttpResponse.json({ success: false, code: '409', message: '绑定已被其他管理员修改' });
      }),
    );
    renderPanel();

    await user.click(await screen.findByRole('button', { name: /编\s*辑/ }));
    const drawer = within(await screen.findByRole('dialog', { name: '编辑企业微信绑定' }));
    await user.click(drawer.getByLabelText('关联数字员工'));
    await user.click(await screen.findByText('Beta', { selector: '.ant-select-item-option-content' }));
    await user.click(drawer.getByRole('button', { name: '保存' }));

    expect(await screen.findByText('请先确认已获得该群的委托授权')).toBeInTheDocument();
    expect(writes).toBe(0);

    await user.click(drawer.getByRole('checkbox', { name: /我确认已获得该群管理员/ }));
    await user.click(drawer.getByRole('button', { name: '保存' }));
    expect(await drawer.findByText('绑定已被其他管理员修改')).toBeInTheDocument();
    expect(writes).toBe(1);
  });

  it('deletes a binding after the action-column confirmation', async () => {
    const user = userEvent.setup();
    let deleted = false;
    server.use(
      http.get('/api/integrations/wecom/bindings', () => HttpResponse.json(ok(deleted ? [] : [binding]))),
      http.delete('/api/integrations/wecom/bindings/1', () => {
        deleted = true;
        return HttpResponse.json(ok(null));
      }),
    );
    renderPanel();

    const row = (await screen.findByText('wecom-chat')).closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: /删\s*除/ }));
    const popup = within(await screen.findByRole('tooltip'));
    await user.click(popup.getByRole('button', { name: '删除绑定' }));

    await waitFor(() => expect(deleted).toBe(true));
  });

  it('disables creation until the platform WeCom bot is ready', async () => {
    server.use(http.get('/api/integrations/channels/group-config', () => HttpResponse.json(ok(
      publicConfig({ configured: false, ready: false, desiredEnabled: false }),
    ))));
    renderPanel();

    expect(await screen.findByText('平台企业微信群聊机器人未就绪，暂时无法新建绑定')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '新建企业微信绑定' })).toBeDisabled();
  });
});
