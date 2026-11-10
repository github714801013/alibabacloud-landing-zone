import { useTranslation } from 'react-i18next';
import { ChiefOfStaff } from '@/features/chiefOfStaff/ChiefOfStaff';
import { LanguageSwitch } from '@/shared/i18n/LanguageSwitch';
import { PlatformBrand } from '@/shared/ui/PlatformBrand';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Layout, Dropdown, Typography, Button, Drawer, message, theme } from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { CheckOutlined, LoadingOutlined, DownOutlined, MenuFoldOutlined, MenuUnfoldOutlined } from '@ant-design/icons';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { AppearanceSwitch } from '@/shared/theme/AppearanceProvider';
import { EllipsisText } from '@/shared/ui/EllipsisText';
import { Sidebar } from './Sidebar';
import { PlatformAgentStatusBanner } from './PlatformAgentStatusBanner';
import { NotificationBell } from '@/shared/ui/NotificationBell';
import { PageHeader } from '@/shared/ui/PageHeader';
import { UserMenu } from '@/shared/ui/UserMenu';
import { HelpCenterLink } from '@/shared/ui/HelpCenterLink';
import { useAuthStore } from '@/shared/auth/store';
import { refreshCurrentMembership } from '@/shared/auth/refreshCurrentMembership';
import { myWorkspacesQueryKey } from '@/features/auth/api';
import { apiClient } from '@/shared/api/client';
import type { WorkspaceInfo, SwitchWorkspaceResponse } from '@/shared/types/common';
import { ApiError } from '@/shared/types/common';
import { refreshTenantScopedQueries } from '@/features/workitem/queryCache';
import { useIsMobile } from '@/shared/ui/useIsMobile';

const { Sider, Content } = Layout;
const { Text } = Typography;

const SIDEBAR_COLLAPSED_KEY = 'autowonder.sidebar.collapsed';

function readCollapsedPref(): boolean {
  try {
    return localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === '1';
  } catch {
    return false;
  }
}

function writeCollapsedPref(collapsed: boolean) {
  try {
    localStorage.setItem(SIDEBAR_COLLAPSED_KEY, collapsed ? '1' : '0');
  } catch {
    /* ignore */
  }
}

export function getWorkspaceDeepLinkId(search: string): number | null {
  const value = new URLSearchParams(search).get('workspaceId');
  if (!value || !/^\d+$/.test(value)) {
    return null;
  }
  const id = Number(value);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
}

export function removeWorkspaceDeepLink(search: string): string {
  const params = new URLSearchParams(search);
  params.delete('workspaceId');
  const nextSearch = params.toString();
  return nextSearch ? `?${nextSearch}` : '';
}

export function AppLayout({ helpCenter = false }: { helpCenter?: boolean }) {
  const { t } = useTranslation('common');
  const { token } = theme.useToken();
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const user = useAuthStore((s) => s.user);
  const currentWorkspace = useAuthStore((s) => s.currentWorkspace);
  const setAccessToken = useAuthStore((s) => s.setAccessToken);
  const setCurrentWorkspace = useAuthStore((s) => s.setCurrentWorkspace);

  const [collapsed, setCollapsed] = useState<boolean>(readCollapsedPref);
  const isMobile = useIsMobile();
  const [mobileNavOpen, setMobileNavOpen] = useState(false);

  useEffect(() => {
    if (!isMobile) setMobileNavOpen(false);
  }, [isMobile]);

  useEffect(() => {
    if (isMobile) setMobileNavOpen(false);
  }, [isMobile, location.pathname]);

  useEffect(() => {
    if (helpCenter) return;
    const refresh = () => {
      void refreshCurrentMembership();
    };
    refresh();
    window.addEventListener('focus', refresh);
    return () => window.removeEventListener('focus', refresh);
  }, [helpCenter]);

  const { data: workspaces } = useQuery({
    queryKey: myWorkspacesQueryKey(user?.id ?? null),
    enabled: !helpCenter,
    queryFn: async () => {
      const resp = await apiClient.get<WorkspaceInfo[]>('/api/workspaces/mine');
      return resp.data;
    },
  });
  const [switchingWorkspaceId, setSwitchingWorkspaceId] = useState<number | null>(null);
  const switchingRef = useRef<number | null>(null);

  const handleWorkspaceSwitch = useCallback(async (workspace: WorkspaceInfo) => {
    if (workspace.id === currentWorkspace?.id || switchingRef.current === workspace.id) return;
    switchingRef.current = workspace.id;
    setSwitchingWorkspaceId(workspace.id);
    try {
      const resp = await apiClient.post<SwitchWorkspaceResponse>(`/api/workspaces/${workspace.id}/switch`);
      setAccessToken(resp.data.accessToken);
      setCurrentWorkspace(workspace, resp.data.accessLevel);
      await refreshTenantScopedQueries(queryClient);
      message.success(t('workspace.switched', { name: workspace.name }));
    } catch (e) {
      message.error(e instanceof ApiError ? e.message : t('workspace.switchFailed'));
    } finally {
      switchingRef.current = null;
      setSwitchingWorkspaceId(null);
    }
  }, [currentWorkspace?.id, setAccessToken, setCurrentWorkspace, queryClient, t]);

  useEffect(() => {
    if (helpCenter) return;
    const targetWorkspaceId = getWorkspaceDeepLinkId(location.search);
    if (!targetWorkspaceId) {
      return;
    }
    const cleanPath = `${location.pathname}${removeWorkspaceDeepLink(location.search)}${location.hash}`;
    if (currentWorkspace?.id === targetWorkspaceId) {
      navigate(cleanPath, { replace: true });
      return;
    }
    let cancelled = false;
    async function switchToLinkedWorkspace() {
      try {
        const workspacesResp = await apiClient.get<WorkspaceInfo[]>('/api/workspaces/mine');
        if (cancelled) {
          return;
        }
        const targetWorkspace = workspacesResp.data.find((workspace) => workspace.id === targetWorkspaceId);
        if (!targetWorkspace) {
          message.error(t('workspace.notMember'));
          return;
        }
        const switchResp = await apiClient.post<SwitchWorkspaceResponse>(`/api/workspaces/${targetWorkspaceId}/switch`);
        if (cancelled) {
          return;
        }
        setAccessToken(switchResp.data.accessToken);
        setCurrentWorkspace(targetWorkspace, switchResp.data.accessLevel);
        await refreshTenantScopedQueries(queryClient);
        navigate(cleanPath, { replace: true });
      } catch {
        if (!cancelled) {
          message.error(t('workspace.linkedSwitchFailed'));
        }
      }
    }
    void switchToLinkedWorkspace();
    return () => {
      cancelled = true;
    };
  }, [
    t,
    helpCenter,
    currentWorkspace?.id,
    location.hash,
    location.pathname,
    location.search,
    navigate,
    queryClient,
    setAccessToken,
    setCurrentWorkspace,
  ]);

  const toggleCollapsed = () => {
    setCollapsed((prev) => {
      const next = !prev;
      writeCollapsedPref(next);
      return next;
    });
  };

  const workspaceMenuItems = [
    ...(workspaces || []).map((workspace) => ({
      key: `workspace-${workspace.id}`,
      label: (
        <EllipsisText tooltip={workspace.name}>
          {workspace.name}
        </EllipsisText>
      ),
      icon: workspace.id === currentWorkspace?.id
        ? (workspace.id === switchingWorkspaceId ? <LoadingOutlined /> : <CheckOutlined style={{ color: 'var(--aw-accent-text)' }} />)
        : (workspace.id === switchingWorkspaceId ? <LoadingOutlined /> : undefined),
      disabled: workspace.id === switchingWorkspaceId,
      onClick: () => { void handleWorkspaceSwitch(workspace); },
    })),
    { type: 'divider' as const },
    { key: 'manage-workspaces', label: t('workspace.manage'), onClick: () => navigate('/workspaces') },
  ];

  const workspaceName = currentWorkspace?.name || t('workspace.unselected');
  const menuButtonLabel = collapsed ? t('navigation.expand') : t('navigation.collapse');

  const sidebarBody = (collapsedView: boolean) => (
    <>
      <div style={{ height: 60, display: 'flex', alignItems: 'center', justifyContent: collapsedView ? 'center' : 'flex-start', padding: collapsedView ? 0 : '0 18px', borderBottom: `1px solid ${token.colorBorderSecondary}`, gap: 10 }}>
        <PlatformBrand to="/workitems" collapsed={collapsedView} />
      </div>

      <Dropdown menu={{ items: workspaceMenuItems }} trigger={['click']} placement="bottomLeft"
        overlayClassName="aw-workspace-menu" overlayStyle={{ width: 196, minWidth: 196, maxWidth: 196 }}>
        <div
          title={collapsedView ? workspaceName : undefined}
          style={{
            margin: collapsedView ? '8px auto' : '8px 12px',
            padding: collapsedView ? 0 : '7px 10px',
            width: collapsedView ? 36 : 'auto',
            height: collapsedView ? 36 : 'auto',
            borderRadius: 7,
            border: `1px solid ${token.colorBorder}`,
            background: token.colorFillAlter,
            cursor: 'pointer',
            display: 'flex',
            alignItems: 'center',
            justifyContent: collapsedView ? 'center' : 'flex-start',
            gap: 8,
            minWidth: 0,
          }}
        >
          <div className="aw-initial-mark" style={{
            width: 20, height: 20, borderRadius: 5,

            display: 'flex', alignItems: 'center', justifyContent: 'center',
            fontWeight: 600, fontSize: 11, flexShrink: 0,
          }}>
            {workspaceName[0]}
          </div>
          {!collapsedView && (
            <>
              <EllipsisText
                tooltip={workspaceName}
                style={{
                  fontSize: 12,
                  flex: 1,
                  minWidth: 0,
                  color: token.colorText,
                  fontWeight: 500,
                }}
              >
                {workspaceName}
              </EllipsisText>
              <DownOutlined style={{ fontSize: 9, color: token.colorTextSecondary }} />
            </>
          )}
        </div>
      </Dropdown>

      <Sidebar collapsed={collapsedView} />
    </>
  );

  return (
    <Layout className={helpCenter ? 'help-layout' : 'aw-shell'} style={{ height: '100vh', minWidth: 0 }}>
      {!helpCenter && !isMobile && (
        <Sider
          width={220}
          collapsedWidth={72}
          collapsed={collapsed}
          theme="light"
          style={{ borderRight: `1px solid ${token.colorBorderSecondary}`, overflow: 'auto' }}
        >
          {sidebarBody(collapsed)}
        </Sider>
      )}
      {!helpCenter && isMobile && (
        <Drawer
          open={mobileNavOpen}
          onClose={() => setMobileNavOpen(false)}
          placement="left"
          width={220}
          closable={false}
          styles={{ body: { padding: 0, overflow: 'auto' } }}
        >
          {sidebarBody(false)}
        </Drawer>
      )}

      <Layout style={{ minWidth: 0 }}>
        <PageHeader actions={<>
            {!helpCenter && <HelpCenterLink />}
            <LanguageSwitch />
            <AppearanceSwitch />
            {(!helpCenter || user) && <NotificationBell fetchUnread={!helpCenter} />}
            {helpCenter && !user ? <Button href="/login">{t('account.login')}</Button> : <UserMenu />}
          </>}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0, flex: 1, overflow: 'hidden' }}>
            {helpCenter ? (
              <PlatformBrand to="/workitems" />
            ) : <Button
              className="aw-sidebar-toggle"
              type="text"
              onClick={isMobile ? () => setMobileNavOpen(true) : toggleCollapsed}
              aria-label={isMobile ? t('navigation.openMenu') : menuButtonLabel}
              icon={isMobile || collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
              style={{ width: 32, padding: 0, fontSize: 16, color: token.colorTextSecondary, flexShrink: 0 }}
            />}
            {helpCenter && <Text type="secondary" style={{ marginLeft: 12 }}>{t('navigation.help')}</Text>}
          </div>
        </PageHeader>
        {!helpCenter && <PlatformAgentStatusBanner />}
        {user && <ChiefOfStaff />}
        <Content style={{ padding: helpCenter ? 0 : 12, overflow: helpCenter ? 'hidden' : 'auto', background: token.colorBgLayout, minWidth: 0, minHeight: 0 }}>
          {helpCenter ? <Outlet /> : <section className="aw-content-frame" aria-label={t('navigation.content')}><Outlet /></section>}
        </Content>
      </Layout>
    </Layout>
  );
}
