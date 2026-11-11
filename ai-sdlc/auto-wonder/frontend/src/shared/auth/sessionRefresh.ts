import { refreshAccessToken, redirectToLogin } from '@/shared/api/client';
import { useAuthStore } from './store';

export type RealtimeRefreshOutcome =
  | { kind: 'refreshed'; token: string }
  | { kind: 'expired' }
  | { kind: 'retry' };

// 实时连接被服务端以「未鉴权」关闭后的统一恢复路径：复用 HTTP 401 的单飞续期引擎，
// 成功则回写新 token 供改绑，明确失效则清理登录态（调用方负责断开连接），瞬时失败保持现状等下一次重试。
export async function refreshRealtimeSession(): Promise<RealtimeRefreshOutcome> {
  let token: string | null;
  try {
    token = await refreshAccessToken('background');
  } catch {
    return { kind: 'retry' };
  }
  if (token) {
    useAuthStore.getState().setAccessToken(token);
    return { kind: 'refreshed', token };
  }
  useAuthStore.getState().clearSession();
  redirectToLogin();
  return { kind: 'expired' };
}
