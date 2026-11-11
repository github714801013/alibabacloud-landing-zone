import type {
  RealtimeEvent,
  EventHandler,
  Subscription,
  ConnectionState,
  RealtimeClientLike,
} from './types';
import { isServerSubscriptionChannel } from './channels';
import { isAuthFailureClose } from './sharedWorkerProtocol';
import { SharedWorkerClient } from './sharedWorkerClient';
import { refreshRealtimeSession } from '@/shared/auth/sessionRefresh';

function normalizeWsProtocol(protocol: string): string {
  if (protocol === 'https:') return 'wss:';
  if (protocol === 'http:') return 'ws:';
  return protocol;
}

type RealtimeEnv = { VITE_WS_URL?: string; VITE_WS_BASE?: string };

const defaultRealtimeEnv = import.meta.env as RealtimeEnv;

export function getConfiguredRealtimeBase(env: RealtimeEnv = defaultRealtimeEnv): string | undefined {
  return env.VITE_WS_URL?.trim() || env.VITE_WS_BASE?.trim() || undefined;
}

export function buildRealtimeUrl(base = getConfiguredRealtimeBase()): string {
  const currentHref = typeof window !== 'undefined' ? window.location.href : 'http://localhost/';
  const currentUrl = new URL(currentHref);
  const trimmedBase = base?.trim();

  let wsUrl: URL;
  if (trimmedBase) {
    const isAbsolute = /^[a-z]+:\/\//i.test(trimmedBase);
    wsUrl = new URL(isAbsolute ? trimmedBase : trimmedBase.startsWith('/') ? trimmedBase : `/${trimmedBase}`, currentUrl);
  } else {
    wsUrl = new URL(currentUrl.origin);
  }

  wsUrl.protocol = normalizeWsProtocol(wsUrl.protocol === ':' ? currentUrl.protocol : wsUrl.protocol);
  const normalizedPath = wsUrl.pathname.replace(/\/+$/, '');
  wsUrl.pathname = normalizedPath.endsWith('/ws') ? normalizedPath || '/ws' : `${normalizedPath || ''}/ws`;
  return wsUrl.toString();
}

export class RealtimeClient implements RealtimeClientLike {
  private url: string;
  private ws: WebSocket | null = null;
  private token: string | null = null;
  private subscribers = new Map<string, Set<EventHandler>>();
  private serverSubscribedChannels = new Set<string>();
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private reconnectAttempt = 0;
  private maxReconnectDelay = 30000;
  private _state: ConnectionState = 'disconnected';
  private stateListeners = new Set<(s: ConnectionState) => void>();
  private authRefreshInFlight = false;

  constructor(url: string) {
    this.url = url;
  }

  get state(): ConnectionState {
    return this._state;
  }

  onStateChange(listener: (s: ConnectionState) => void): () => void {
    this.stateListeners.add(listener);
    return () => this.stateListeners.delete(listener);
  }

  private setState(s: ConnectionState) {
    this._state = s;
    this.stateListeners.forEach((l) => l(s));
  }

  connect(token: string) {
    this.token = token;
    this.doConnect();
  }

  rebind(token: string) {
    if (this.token === token) return;
    this.disconnect();
    // disconnect 清空了服务端订阅记录，重连前按本地订阅恢复。
    for (const channel of this.subscribers.keys()) {
      if (isServerSubscriptionChannel(channel)) {
        this.serverSubscribedChannels.add(channel);
      }
    }
    this.connect(token);
  }

  disconnect() {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.ws?.close();
    this.ws = null;
    this.serverSubscribedChannels.clear();
    this.setState('disconnected');
  }

  subscribe(channel: string, handler: EventHandler): Subscription {
    const isNewChannel = !this.subscribers.has(channel);
    if (!this.subscribers.has(channel)) {
      this.subscribers.set(channel, new Set());
    }
    this.subscribers.get(channel)!.add(handler);

    if (isNewChannel && isServerSubscriptionChannel(channel)) {
      this.sendServerSubscribe(channel);
    }

    return {
      channel,
      handler,
      unsubscribe: () => {
        const set = this.subscribers.get(channel);
        if (set) {
          set.delete(handler);
          if (set.size === 0) {
            this.subscribers.delete(channel);
            if (isServerSubscriptionChannel(channel)) {
              this.sendServerUnsubscribe(channel);
            }
          }
        }
      },
    };
  }

  private sendServerSubscribe(channel: string) {
    this.serverSubscribedChannels.add(channel);
    this.sendFrame({ type: 'subscribe', channel });
  }

  private sendServerUnsubscribe(channel: string) {
    this.serverSubscribedChannels.delete(channel);
    this.sendFrame({ type: 'unsubscribe', channel });
  }

  private sendFrame(frame: Record<string, unknown>) {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(frame));
    }
  }

  private resubscribeAll() {
    for (const channel of this.serverSubscribedChannels) {
      this.sendFrame({ type: 'subscribe', channel });
    }
  }

  private doConnect() {
    this.setState('connecting');
    const ws = new WebSocket(this.url);
    this.ws = ws;

    // 所有回调都校验 ws 仍是当前连接：rebind 关闭旧 socket 后，迟到的 close 不能触发多余重连。
    ws.onopen = () => {
      if (this.ws !== ws) return;
      ws.send(JSON.stringify({ type: 'auth', token: this.token }));
      this.reconnectAttempt = 0;
      this.setState('connected');
      this.resubscribeAll();
    };

    ws.onmessage = (e: MessageEvent) => {
      if (this.ws !== ws) return;
      try {
        const event = JSON.parse(e.data) as RealtimeEvent;
        const handlers = this.subscribers.get(event.channel);
        if (handlers) {
          handlers.forEach((h) => h(event));
        }
      } catch { /* malformed message, skip */ }
    };

    ws.onclose = (event: CloseEvent) => {
      if (this.ws !== ws || this._state === 'disconnected') return;
      if (isAuthFailureClose(event.code, event.reason)) void this.refreshAuth();
      this.scheduleReconnect();
    };

    ws.onerror = () => {
      if (this.ws === ws) ws.close();
    };
  }

  // 服务端因 token 过期拒绝鉴权时刷新一次并改绑，避免拿着旧 token 无限重连。
  private async refreshAuth() {
    if (this.authRefreshInFlight) return;
    this.authRefreshInFlight = true;
    try {
      const result = await refreshRealtimeSession();
      if (result.kind === 'refreshed') {
        this.rebind(result.token);
      } else if (result.kind === 'expired') {
        this.disconnect();
      }
    } finally {
      this.authRefreshInFlight = false;
    }
  }

  private scheduleReconnect() {
    this.setState('reconnecting');
    const delay = Math.min(1000 * Math.pow(2, this.reconnectAttempt), this.maxReconnectDelay);
    this.reconnectAttempt++;
    this.reconnectTimer = setTimeout(() => {
      this.doConnect();
    }, delay);
  }
}

let _instance: RealtimeClientLike | null = null;

export function getRealtimeClient(): RealtimeClientLike {
  if (!_instance) {
    _instance = createRealtimeClient();
  }
  return _instance;
}

// 仅在界面已请求过实时客户端时同步，避免登录页等无实时消费的场景新建连接。
export function syncRealtimeAuth(token: string) {
  _instance?.rebind(token);
}

export function disconnectRealtime() {
  _instance?.disconnect();
}

function createRealtimeClient(): RealtimeClientLike {
  if (typeof SharedWorker === 'undefined') {
    return new RealtimeClient(buildRealtimeUrl());
  }
  try {
    return new SharedWorkerClient({
      buildUrl: () => buildRealtimeUrl(),
      createFallback: () => new RealtimeClient(buildRealtimeUrl()),
    });
  } catch {
    // worker 构造失败时保留每标签页一条连接的现有行为。
    return new RealtimeClient(buildRealtimeUrl());
  }
}
