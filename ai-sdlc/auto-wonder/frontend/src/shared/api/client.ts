import { protectBigInts } from './protectBigInts';
import { i18n, normalizeLocale } from '@/shared/i18n';
import axios, { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { ApiError, ErrorCodes } from '@/shared/types/common';
import type { ApiResult } from '@/shared/types/common';
import { useAuthStore } from '@/shared/auth/store';
import { refreshCurrentMembership } from '@/shared/auth/refreshCurrentMembership';
import { refreshAccessToken, redirectToLogin } from '@/shared/auth/sessionRefresh';
import { disconnectRealtime, syncRealtimeAuth } from '@/shared/realtime/client';


function safeParse(text: string) {
  return JSON.parse(protectBigInts(text));
}

function isAccessDenied(code: string): boolean {
  return code === ErrorCodes.NO_PERMISSION
    || code === ErrorCodes.WORKSPACE_ACCESS_INSUFFICIENT;
}

// A 401 from the login endpoints is a business error (wrong credentials, expired
// one-time SSO ticket), not a session expiry; routing it through handleUnauthorized
// would redirect/reload the login page and swallow the backend's specific error message.
const AUTH_ENTRY_URLS = new Set(['/api/auth/login', '/api/auth/sso/session', '/api/auth/sso/bind']);

function isAuthEntryRequest(config?: InternalAxiosRequestConfig): boolean {
  return config?.url != null && AUTH_ENTRY_URLS.has(config.url);
}

async function synchronizeWorkspaceAfterFailure(code: string): Promise<void> {
  if (code === ErrorCodes.ORG_DELETED_OR_DISABLED) {
    leaveUnusableWorkspace();
    return;
  }
  if (code === ErrorCodes.WORKSPACE_NOT_MEMBER) {
    await refreshCurrentMembership();
    return;
  }
  if (isAccessDenied(code)) {
    await refreshCurrentMembership();
  }
}

function redirectToWorkspaceSelect(): void {
  try {
    const loc = (typeof window !== 'undefined' ? window.location : null) as
      | { pathname?: string; href?: string }
      | null;
    if (!loc || loc.pathname === '/workspaces') return;
    loc.href = '/workspaces';
  } catch {
    // Same jsdom navigation caveat as redirectToLogin.
  }
}

// F6.3: the workspace this access token was issued for is deleted or disabled, so every
// workspace-scoped call from here on is rejected. Dropping the binding also stops the next
// refresh from re-declaring a dead workspaceId. The tokens themselves stay: RouteGuard would
// send a tokenless user to /login rather than to the select page, and /api/workspaces/mine is
// exempt from the workspace check, so the select page still renders and the user can re-enter.
function leaveUnusableWorkspace(): void {
  useAuthStore.getState().clearCurrentWorkspace();
  redirectToWorkspaceSelect();
}

async function handleUnauthorized(
  originalConfig: InternalAxiosRequestConfig,
): Promise<AxiosResponse> {
  const rt = useAuthStore.getState().refreshToken;
  if (!rt || originalConfig.url === '/api/auth/refresh') {
    disconnectRealtime();
    useAuthStore.getState().clear();
    redirectToLogin();
    return Promise.reject(new ApiError(ErrorCodes.UNAUTHORIZED, i18n.t('account.expired'), null));
  }

  const result = await refreshAccessToken();
  if (result.token) {
    useAuthStore.getState().setAccessToken(result.token);
    syncRealtimeAuth(result.token);
    originalConfig.headers.Authorization = `Bearer ${result.token}`;
    return apiClient(originalConfig);
  }

  // 刷新服务 5xx / 网络错误：会话可能仍有效，保留凭证并停在当前页面，只让本次请求失败。
  // 只有服务端明确判定 refreshToken 失效时才登出。
  if (result.sessionExpired) {
    disconnectRealtime();
    useAuthStore.getState().clear();
    redirectToLogin();
  }
  return Promise.reject(new ApiError(ErrorCodes.UNAUTHORIZED, i18n.t('account.expired'), null));
}

export const apiClient = axios.create({
  baseURL: '',
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' },
  transformResponse: [(data) => {
    if (typeof data === 'string') {
      try {
        return safeParse(data);
      } catch {
        return data;
      }
    }
    return data;
  }],
});

apiClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  config.headers['Accept-Language'] = normalizeLocale(i18n.language);
  const token = useAuthStore.getState().accessToken;
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

apiClient.interceptors.response.use(
  async (response) => {
    const body = response.data as ApiResult<unknown>;
    if (!body.success) {
      if (body.code === ErrorCodes.UNAUTHORIZED && !isAuthEntryRequest(response.config)) {
        return handleUnauthorized(response.config);
      }
      const apiError = new ApiError(body.code, body.message, body.traceId, body.data);
      await synchronizeWorkspaceAfterFailure(body.code);
      throw apiError;
    }
    response.data = body.data;
    return response;
  },
  async (error) => {
    if (error.response?.data?.code) {
      const body = error.response.data;

      if (body.code === ErrorCodes.UNAUTHORIZED && !isAuthEntryRequest(error.config)) {
        return handleUnauthorized(error.config);
      }
      const apiErr = new ApiError(body.code, body.message, body.traceId, body.data);
      await synchronizeWorkspaceAfterFailure(body.code);
      throw apiErr;
    }
    throw new ApiError('10000', error.message || i18n.t('account.networkError'), null);
  },
);
