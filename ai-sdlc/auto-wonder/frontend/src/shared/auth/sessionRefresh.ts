import { i18n, normalizeLocale } from '@/shared/i18n';
import { useAuthStore } from './store';

export type RefreshResult = { token: string | null; sessionExpired: boolean };

export type RealtimeRefreshOutcome =
  | { kind: 'refreshed'; token: string }
  | { kind: 'expired' }
  | { kind: 'retry' };

let refreshPromise: Promise<RefreshResult> | null = null;

async function performRefresh(): Promise<RefreshResult> {
  const { refreshToken: rt, currentWorkspace } = useAuthStore.getState();
  if (!rt) return { token: null, sessionExpired: true };
  try {
    const resp = await fetch('/api/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Accept-Language': normalizeLocale(i18n.language) },
      // The workspace lives as a claim inside the access token, so it must be re-declared here.
      // Otherwise the refreshed token is detached from the workspace, the next workspace-scoped
      // call returns WORKSPACE_NOT_MEMBER, and RouteGuard bounces the user to /workspaces.
      body: JSON.stringify({ refreshToken: rt, workspaceId: currentWorkspace?.id ?? null }),
    });
    // 只有 401 是「明确的未授权」；5xx、网络错误和其它 4xx 都不能判定 refreshToken 失效：
    // 误判会清空凭证并把用户踢回登录页，当前页面与已加载数据随之丢失。
    if (!resp.ok) return { token: null, sessionExpired: resp.status === 401 };
    const body = await resp.json();
    if (body.success && body.data?.accessToken) {
      return { token: body.data.accessToken as string, sessionExpired: false };
    }
    return { token: null, sessionExpired: true };
  } catch {
    return { token: null, sessionExpired: false };
  }
}

// 同一标签页内的并发刷新合并为一次：HTTP 401 与实时连接鉴权失败可能同时触发。
export function refreshAccessToken(): Promise<RefreshResult> {
  if (!refreshPromise) {
    refreshPromise = performRefresh().finally(() => {
      refreshPromise = null;
    });
  }
  return refreshPromise;
}

export function redirectToLogin(): void {
  try {
    const loc = (typeof window !== 'undefined' ? window.location : null) as { href?: string } | null;
    if (loc) loc.href = '/login';
  } catch {
    // jsdom may throw "Not implemented: navigation" or "Invalid base URL"
    // when assigning a relative href — safe to ignore in tests.
  }
}

// 实时连接被服务端以「未鉴权」关闭后的统一恢复路径：成功则回写新 token 供改绑，
// 明确失效则清理登录态（调用方负责断开连接），瞬时失败保持现状等下一次重试。
export async function refreshRealtimeSession(): Promise<RealtimeRefreshOutcome> {
  const result = await refreshAccessToken();
  if (result.token) {
    useAuthStore.getState().setAccessToken(result.token);
    return { kind: 'refreshed', token: result.token };
  }
  if (result.sessionExpired) {
    useAuthStore.getState().clear();
    redirectToLogin();
    return { kind: 'expired' };
  }
  return { kind: 'retry' };
}
