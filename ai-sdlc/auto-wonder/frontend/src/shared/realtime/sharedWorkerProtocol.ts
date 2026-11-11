import type { ConnectionState, RealtimeEvent } from './types';

export const SHARED_WORKER_NAME = 'auto-wonder-realtime';

export type TabToWorkerMessage =
  | { type: 'hello' }
  | { type: 'connect'; key: string; token: string; url: string }
  | { type: 'disconnect'; key: string }
  | { type: 'leave' }
  | { type: 'ping' }
  | { type: 'subscribe'; key: string; channel: string }
  | { type: 'unsubscribe'; key: string; channel: string };

export type WorkerToTabMessage =
  | { type: 'ready' }
  | { type: 'pong' }
  | { type: 'fatal'; reason: string }
  | { type: 'auth-failed' }
  | { type: 'state'; state: ConnectionState }
  | { type: 'event'; event: RealtimeEvent };

// BrowserRealtimeEndpoint 对未通过鉴权的连接以 1008 + "auth required" 关闭；
// 该组合只会在握手 token 过期/失效时出现，其它 1008 原因不应触发 token 刷新。
export function isAuthFailureClose(code?: number, reason?: string): boolean {
  return code === 1008 && reason === 'auth required';
}
