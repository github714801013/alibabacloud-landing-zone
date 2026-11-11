import { useAuthStore } from '@/shared/auth/store';
import { refreshRealtimeSession } from '@/shared/auth/sessionRefresh';
import type { ConnectionState, EventHandler, RealtimeClientLike, Subscription } from './types';
import { SHARED_WORKER_NAME, type TabToWorkerMessage, type WorkerToTabMessage } from './sharedWorkerProtocol';

export const SHARED_WORKER_HANDSHAKE_TIMEOUT_MS = 2000;
export const SHARED_WORKER_HEARTBEAT_INTERVAL_MS = 10000;
export const SHARED_WORKER_PONG_TIMEOUT_MS = 3000;

interface SharedWorkerClientOptions {
  buildUrl: () => string;
  createFallback: () => RealtimeClientLike;
}

/**
 * 标签页侧代理：对外暴露与 RealtimeClient 相同的接口，连接与订阅经 MessagePort 交给 SharedWorker。
 * 握手超时、worker 报错或心跳失联时整体切换到每标签页一条连接，现有消费方无需感知。
 */
export class SharedWorkerClient implements RealtimeClientLike {
  private port: MessagePort;
  private token = '';
  private key: string | null = null;
  private _state: ConnectionState = 'disconnected';
  private stateListeners = new Set<(s: ConnectionState) => void>();
  private handlers = new Map<string, Set<EventHandler>>();
  private fallback: RealtimeClientLike | null = null;
  private fallbackSubscriptions = new Map<string, Subscription>();
  private handshakeTimer: ReturnType<typeof setTimeout> | null = null;
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private pongTimer: ReturnType<typeof setTimeout> | null = null;
  private authRefreshInFlight = false;
  private readonly buildUrl: () => string;
  private readonly createFallback: () => RealtimeClientLike;

  constructor(options: SharedWorkerClientOptions) {
    this.buildUrl = options.buildUrl;
    this.createFallback = options.createFallback;
    const worker = new SharedWorker(new URL('./sharedWorker.worker.ts', import.meta.url), {
      type: 'module',
      name: SHARED_WORKER_NAME,
    });
    worker.onerror = () => this.activateFallback('worker-error');
    this.port = worker.port;
    this.port.onmessage = (event: MessageEvent) => {
      this.handleMessage(event.data as WorkerToTabMessage);
    };
    this.port.start();
    this.post({ type: 'hello' });
    this.handshakeTimer = setTimeout(
      () => this.activateFallback('ready-timeout'),
      SHARED_WORKER_HANDSHAKE_TIMEOUT_MS,
    );
    if (typeof window !== 'undefined') {
      window.addEventListener('pagehide', this.handlePageHide);
    }
  }

  get state(): ConnectionState {
    return this._state;
  }

  onStateChange(listener: (s: ConnectionState) => void): () => void {
    this.stateListeners.add(listener);
    return () => this.stateListeners.delete(listener);
  }

  connect(token: string) {
    if (this.fallback) return this.fallback.connect(token);
    const key = this.currentKey();
    if (!key || !token) return;
    if (this.key === key && this.token === token && this._state !== 'disconnected') return;

    this.key = key;
    this.token = token;
    this.setState('connecting');
    this.post({ type: 'connect', key, token, url: this.buildUrl() });
    for (const channel of this.handlers.keys()) {
      this.post({ type: 'subscribe', key, channel });
    }
  }

  rebind(token: string) {
    if (this.fallback) return this.fallback.rebind(token);
    const key = this.currentKey();
    if (key && key === this.key) {
      // 同键改绑复用 connect：携带新 token 会触发 worker 换连接，连接已被释放时则重建。
      if (token && token !== this.token) this.connect(token);
      return;
    }
    // 换键或失去身份：leave 只释放本标签页的绑定，不断开同键其他标签页。
    if (this.key) this.post({ type: 'leave' });
    this.resetLocal();
    // 旧工作区的订阅不向新工作区连接重放，避免串台。
    if (key) this.connect(token);
  }

  disconnect() {
    if (this.fallback) return this.fallback.disconnect();
    if (this.key) this.post({ type: 'disconnect', key: this.key });
    this.resetLocal();
  }

  subscribe(channel: string, handler: EventHandler): Subscription {
    let handlers = this.handlers.get(channel);
    const isFirst = !handlers;
    if (!handlers) {
      handlers = new Set();
      this.handlers.set(channel, handlers);
    }
    handlers.add(handler);
    if (isFirst) this.attach(channel);

    return {
      channel,
      handler,
      unsubscribe: () => {
        const current = this.handlers.get(channel);
        if (!current) return;
        current.delete(handler);
        if (current.size > 0) return;
        this.handlers.delete(channel);
        this.detach(channel);
      },
    };
  }

  private attach(channel: string) {
    if (this.fallback) {
      this.fallbackSubscriptions.set(channel, this.fallback.subscribe(channel, this.forward(channel)));
    } else if (this.key) {
      this.post({ type: 'subscribe', key: this.key, channel });
    }
  }

  private detach(channel: string) {
    const subscription = this.fallbackSubscriptions.get(channel);
    if (subscription) {
      subscription.unsubscribe();
      this.fallbackSubscriptions.delete(channel);
    } else if (this.key) {
      this.post({ type: 'unsubscribe', key: this.key, channel });
    }
  }

  private forward(channel: string): EventHandler {
    return (event) => this.handlers.get(channel)?.forEach((handler) => handler(event));
  }

  private resetLocal() {
    this.key = null;
    this.token = '';
    this.handlers.clear();
    this.setState('disconnected');
  }

  private activateFallback(reason: string) {
    if (this.fallback) return;
    this.stopTimers();
    const fallback = this.createFallback();
    this.fallback = fallback;
    fallback.onStateChange((state) => this.setState(state));
    const { token } = this;
    const channels = [...this.handlers.keys()];
    if (this.key) this.post({ type: 'leave' });
    // worker 冻结时 leave 可能永远得不到处理，关闭端口才能让浏览器终止 worker 并释放连接。
    this.port.close();
    this.key = null;
    console.warn(`[realtime] 共享连接不可用（${reason}），回退到每标签页连接`);
    if (token) fallback.connect(token);
    for (const channel of channels) {
      this.fallbackSubscriptions.set(channel, fallback.subscribe(channel, this.forward(channel)));
    }
    this.setState(fallback.state);
  }

  private readonly handlePageHide = (event: PageTransitionEvent) => {
    if (event.persisted) return;
    this.stopTimers();
    if (this.fallback) return;
    this.post({ type: 'leave' });
    this.port.close();
  };

  private currentKey(): string | null {
    const { user, currentWorkspace } = useAuthStore.getState();
    if (!user?.id || !currentWorkspace?.id) return null;
    return `${user.id}:${currentWorkspace.id}`;
  }

  private handleMessage(message: WorkerToTabMessage) {
    if (this.fallback) return;
    switch (message.type) {
      case 'ready':
        this.startHeartbeat();
        break;
      case 'pong':
        this.clearPongTimer();
        break;
      case 'fatal':
        this.activateFallback(message.reason);
        break;
      case 'auth-failed':
        void this.refreshAuth();
        break;
      case 'state':
        this.setState(message.state);
        break;
      case 'event': {
        const handlers = this.handlers.get(message.event.channel);
        handlers?.forEach((handler) => handler(message.event));
        break;
      }
      default:
        break;
    }
  }

  // worker 只在服务端以「未鉴权」关闭连接时通知；刷新成功用新 token 重连，失败则等下一次重连通知。
  private async refreshAuth() {
    if (this.fallback || this.authRefreshInFlight || !this.key) return;
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

  private startHeartbeat() {
    if (this.handshakeTimer) {
      clearTimeout(this.handshakeTimer);
      this.handshakeTimer = null;
    }
    if (this.heartbeatTimer) return;
    // worker 被终止时端口不会报错，只能靠存活探测发现并降级。
    this.heartbeatTimer = setInterval(() => {
      this.post({ type: 'ping' });
      if (this.pongTimer) return;
      this.pongTimer = setTimeout(
        () => this.activateFallback('heartbeat-timeout'),
        SHARED_WORKER_PONG_TIMEOUT_MS,
      );
    }, SHARED_WORKER_HEARTBEAT_INTERVAL_MS);
  }

  private clearPongTimer() {
    if (this.pongTimer) {
      clearTimeout(this.pongTimer);
      this.pongTimer = null;
    }
  }

  private stopTimers() {
    if (this.handshakeTimer) {
      clearTimeout(this.handshakeTimer);
      this.handshakeTimer = null;
    }
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
    this.clearPongTimer();
  }

  private post(message: TabToWorkerMessage) {
    this.port.postMessage(message);
  }

  private setState(state: ConnectionState) {
    if (this._state === state) return;
    this._state = state;
    this.stateListeners.forEach((listener) => listener(state));
  }
}
