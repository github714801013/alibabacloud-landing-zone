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
  | { type: 'state'; state: ConnectionState }
  | { type: 'event'; event: RealtimeEvent };
