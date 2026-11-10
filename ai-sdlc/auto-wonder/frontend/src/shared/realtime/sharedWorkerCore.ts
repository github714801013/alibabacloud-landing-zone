import type { ConnectionState, RealtimeEvent } from './types';
import { isServerSubscriptionChannel } from './channels';
import type { TabToWorkerMessage, WorkerToTabMessage } from './sharedWorkerProtocol';

export interface SocketLike {
  send(data: string): void;
  close(): void;
  onopen: (() => void) | null;
  onmessage: ((event: { data: string }) => void) | null;
  onclose: (() => void) | null;
  onerror: (() => void) | null;
}

export type SendToTab = (tabId: number, message: WorkerToTabMessage) => void;

interface PoolEntry {
  key: string;
  token: string;
  url: string;
  socket: SocketLike | null;
  state: ConnectionState;
  tabs: Set<number>;
  channels: Map<string, Set<number>>;
  serverChannels: Set<string>;
  reconnectAttempt: number;
  reconnectTimer: ReturnType<typeof setTimeout> | null;
}

/**
 * SharedWorker 内的连接池与订阅路由；不依赖浏览器 API，便于单测。
 * 连接池键由标签页侧按「用户 + 工作区」计算后传入。
 */
export class SharedWorkerCore {
  private pool = new Map<string, PoolEntry>();
  private tabKeys = new Map<number, string>();

  constructor(
    private readonly createSocket: (url: string) => SocketLike,
    private readonly sendToTab: SendToTab,
  ) {}

  removeTab(tabId: number) {
    const key = this.tabKeys.get(tabId);
    if (key) this.releaseTab(key, tabId);
  }

  handleMessage(tabId: number, message: TabToWorkerMessage) {
    switch (message.type) {
      case 'hello':
        this.sendToTab(tabId, { type: 'ready' });
        break;
      case 'connect':
        this.connect(tabId, message.key, message.token, message.url);
        break;
      case 'disconnect':
        this.disconnect(message.key);
        break;
      case 'leave':
        this.removeTab(tabId);
        break;
      case 'subscribe':
        this.subscribe(tabId, message.key, message.channel);
        break;
      case 'unsubscribe':
        this.unsubscribe(tabId, message.key, message.channel);
        break;
      default:
        break;
    }
  }

  private connect(tabId: number, key: string, token: string, url: string) {
    if (!token) return;
    if (!url) {
      this.sendToTab(tabId, { type: 'fatal', reason: 'missing-url' });
      return;
    }
    const previousKey = this.tabKeys.get(tabId);
    if (previousKey && previousKey !== key) this.releaseTab(previousKey, tabId);

    let entry = this.pool.get(key);
    if (!entry) {
      entry = {
        key,
        token,
        url,
        socket: null,
        state: 'connecting',
        tabs: new Set(),
        channels: new Map(),
        serverChannels: new Set(),
        reconnectAttempt: 0,
        reconnectTimer: null,
      };
      this.pool.set(key, entry);
      entry.tabs.add(tabId);
      this.tabKeys.set(tabId, key);
      this.openSocket(entry);
    } else {
      entry.tabs.add(tabId);
      this.tabKeys.set(tabId, key);
      if (entry.token !== token) {
        entry.token = token;
        this.reconnect(entry);
      }
    }
    this.sendToTab(tabId, { type: 'state', state: entry.state });
  }

  // 换 token 必须换连接：服务端只在建连时鉴权一次。
  private reconnect(entry: PoolEntry) {
    if (entry.reconnectTimer) {
      clearTimeout(entry.reconnectTimer);
      entry.reconnectTimer = null;
    }
    const socket = entry.socket;
    entry.socket = null;
    socket?.close();
    entry.reconnectAttempt = 0;
    this.openSocket(entry);
  }

  // 登出会关闭该连接池键的共享连接，同键其他标签页随之中断；这是规格约定的跨标签页登录态语义。
  private disconnect(key: string) {
    const entry = this.pool.get(key);
    if (!entry) return;
    for (const tabId of entry.tabs) {
      this.sendToTab(tabId, { type: 'state', state: 'disconnected' });
    }
    this.closeEntry(entry);
  }

  private subscribe(tabId: number, key: string, channel: string) {
    const entry = this.pool.get(key);
    if (!entry || this.tabKeys.get(tabId) !== key) return;
    let tabs = entry.channels.get(channel);
    if (!tabs) {
      tabs = new Set();
      entry.channels.set(channel, tabs);
    }
    const isFirst = tabs.size === 0;
    tabs.add(tabId);
    if (isFirst && isServerSubscriptionChannel(channel)) {
      entry.serverChannels.add(channel);
      this.sendFrame(entry, { type: 'subscribe', channel });
    }
  }

  private unsubscribe(tabId: number, key: string, channel: string) {
    const entry = this.pool.get(key);
    if (!entry) return;
    const tabs = entry.channels.get(channel);
    if (!tabs || !tabs.delete(tabId)) return;
    if (tabs.size > 0) return;
    entry.channels.delete(channel);
    if (entry.serverChannels.delete(channel)) {
      this.sendFrame(entry, { type: 'unsubscribe', channel });
    }
  }

  private releaseTab(key: string, tabId: number) {
    this.tabKeys.delete(tabId);
    const entry = this.pool.get(key);
    if (!entry) return;
    entry.tabs.delete(tabId);
    for (const [channel, tabs] of entry.channels) {
      if (!tabs.delete(tabId) || tabs.size > 0) continue;
      entry.channels.delete(channel);
      if (entry.serverChannels.delete(channel)) {
        this.sendFrame(entry, { type: 'unsubscribe', channel });
      }
    }
    if (entry.tabs.size === 0) this.closeEntry(entry);
  }

  private closeEntry(entry: PoolEntry) {
    if (entry.reconnectTimer) {
      clearTimeout(entry.reconnectTimer);
      entry.reconnectTimer = null;
    }
    const socket = entry.socket;
    entry.socket = null;
    socket?.close();
    for (const tabId of entry.tabs) this.tabKeys.delete(tabId);
    entry.tabs.clear();
    entry.channels.clear();
    entry.serverChannels.clear();
    this.pool.delete(entry.key);
  }

  private openSocket(entry: PoolEntry) {
    this.setState(entry, 'connecting');
    let socket: SocketLike;
    try {
      socket = this.createSocket(entry.url);
    } catch {
      this.scheduleReconnect(entry);
      return;
    }
    entry.socket = socket;

    socket.onopen = () => {
      if (entry.socket !== socket) return;
      socket.send(JSON.stringify({ type: 'auth', token: entry.token }));
      entry.reconnectAttempt = 0;
      this.setState(entry, 'connected');
      for (const channel of entry.serverChannels) {
        socket.send(JSON.stringify({ type: 'subscribe', channel }));
      }
    };

    socket.onmessage = (message) => {
      if (entry.socket !== socket) return;
      try {
        const event = JSON.parse(message.data) as RealtimeEvent;
        const tabs = entry.channels.get(event.channel);
        if (!tabs) return;
        for (const tabId of tabs) {
          this.sendToTab(tabId, { type: 'event', event });
        }
      } catch {
        // 非 JSON 帧直接忽略，与现有客户端一致。
      }
    };

    socket.onclose = () => {
      if (entry.socket !== socket) return;
      entry.socket = null;
      this.scheduleReconnect(entry);
    };

    socket.onerror = () => {
      if (entry.socket === socket) socket.close();
    };
  }

  private scheduleReconnect(entry: PoolEntry) {
    if (entry.reconnectTimer) return;
    this.setState(entry, 'reconnecting');
    const delay = Math.min(1000 * Math.pow(2, entry.reconnectAttempt), 30000);
    entry.reconnectAttempt += 1;
    entry.reconnectTimer = setTimeout(() => {
      entry.reconnectTimer = null;
      this.openSocket(entry);
    }, delay);
  }

  private sendFrame(entry: PoolEntry, frame: Record<string, unknown>) {
    if (entry.socket && entry.state === 'connected') {
      entry.socket.send(JSON.stringify(frame));
    }
  }

  private setState(entry: PoolEntry, state: ConnectionState) {
    entry.state = state;
    for (const tabId of entry.tabs) {
      this.sendToTab(tabId, { type: 'state', state });
    }
  }
}
