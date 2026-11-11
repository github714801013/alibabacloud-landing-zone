import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { RealtimeClient, buildRealtimeUrl, getConfiguredRealtimeBase } from './client';
import { useAuthStore } from '@/shared/auth/store';
import { refreshRealtimeSession } from '@/shared/auth/sessionRefresh';

vi.mock('@/shared/auth/sessionRefresh', () => ({
  refreshRealtimeSession: vi.fn(),
}));

class MockWebSocket {
  static instances: MockWebSocket[] = [];
  static OPEN = 1;
  static CLOSED = 3;
  url: string;
  readyState = 0;
  onopen: (() => void) | null = null;
  onclose: ((event: { code?: number; reason?: string }) => void) | null = null;
  onmessage: ((e: { data: string }) => void) | null = null;
  onerror: (() => void) | null = null;
  close = vi.fn();
  send = vi.fn();

  constructor(url: string) {
    this.url = url;
    MockWebSocket.instances.push(this);
  }

  simulateOpen() {
    this.readyState = 1;
    this.onopen?.();
  }

  simulateMessage(data: unknown) {
    this.onmessage?.({ data: JSON.stringify(data) });
  }

  simulateClose(event: { code?: number; reason?: string } = {}) {
    this.readyState = 3;
    this.onclose?.(event);
  }
}

describe('RealtimeClient', () => {
  let client: RealtimeClient;

  beforeEach(() => {
    MockWebSocket.instances = [];
    vi.stubGlobal('WebSocket', MockWebSocket);
    vi.mocked(refreshRealtimeSession).mockClear();
    useAuthStore.getState().clear();
    client = new RealtimeClient('ws://localhost:8080/ws');
  });

  afterEach(() => {
    client.disconnect();
    useAuthStore.getState().clear();
    vi.unstubAllGlobals();
  });

  it('connects with token', () => {
    client.connect('my-token');
    expect(MockWebSocket.instances).toHaveLength(1);
    expect(MockWebSocket.instances[0].url).toBe('ws://localhost:8080/ws');
    MockWebSocket.instances[0].simulateOpen();
    expect(MockWebSocket.instances[0].send).toHaveBeenCalledWith(
      JSON.stringify({ type: 'auth', token: 'my-token' }),
    );
  });

  it('dispatches events to subscribers', () => {
    client.connect('t');
    MockWebSocket.instances[0].simulateOpen();

    const handler = vi.fn();
    client.subscribe('workitem:42', handler);

    MockWebSocket.instances[0].simulateMessage({
      channel: 'workitem:42',
      type: 'STATUS_CHANGE',
      payload: { status: 'IN_PROGRESS' },
      timestamp: 1000,
    });

    expect(handler).toHaveBeenCalledWith({
      channel: 'workitem:42',
      type: 'STATUS_CHANGE',
      payload: { status: 'IN_PROGRESS' },
      timestamp: 1000,
    });
  });

  it('does not dispatch to unrelated channel', () => {
    client.connect('t');
    MockWebSocket.instances[0].simulateOpen();

    const handler = vi.fn();
    client.subscribe('workitem:42', handler);

    MockWebSocket.instances[0].simulateMessage({
      channel: 'workitem:99',
      type: 'COMMENT',
      payload: {},
      timestamp: 1000,
    });

    expect(handler).not.toHaveBeenCalled();
  });

  it('registers notification channels with the server and unregisters on unsubscribe', () => {
    client.connect('t');
    MockWebSocket.instances[0].simulateOpen();

    const handler = vi.fn();
    const subscription = client.subscribe('notification:10002:10018', handler);

    expect(MockWebSocket.instances[0].send).toHaveBeenCalledWith(
      JSON.stringify({ type: 'subscribe', channel: 'notification:10002:10018' }),
    );

    MockWebSocket.instances[0].simulateMessage({
      channel: 'notification:10002:10018',
      type: 'NOTIFICATION_CREATED',
      payload: { workspaceId: 10002, recipientId: 10018, notificationId: 9 },
      timestamp: 1000,
    });
    expect(handler).toHaveBeenCalledTimes(1);

    subscription.unsubscribe();
    expect(MockWebSocket.instances[0].send).toHaveBeenCalledWith(
      JSON.stringify({ type: 'unsubscribe', channel: 'notification:10002:10018' }),
    );
  });

  it('unsubscribe stops delivery', () => {
    client.connect('t');
    MockWebSocket.instances[0].simulateOpen();

    const handler = vi.fn();
    const sub = client.subscribe('workitem:42', handler);
    sub.unsubscribe();

    MockWebSocket.instances[0].simulateMessage({
      channel: 'workitem:42',
      type: 'EVENT',
      payload: {},
      timestamp: 1000,
    });

    expect(handler).not.toHaveBeenCalled();
  });

  it('reports connection state', () => {
    expect(client.state).toBe('disconnected');
    client.connect('t');
    expect(client.state).toBe('connecting');
    MockWebSocket.instances[0].simulateOpen();
    expect(client.state).toBe('connected');
  });

  it('reconnects after an unexpected close', () => {
    vi.useFakeTimers();
    const states: string[] = [];
    client.onStateChange((state) => states.push(state));

    client.connect('t');
    MockWebSocket.instances[0].simulateOpen();
    MockWebSocket.instances[0].simulateClose();

    expect(client.state).toBe('reconnecting');
    vi.advanceTimersByTime(1000);

    expect(MockWebSocket.instances).toHaveLength(2);
    expect(states).toEqual(['connecting', 'connected', 'reconnecting', 'connecting']);
    vi.useRealTimers();
  });

  it('rebinds with the refreshed token after an auth-required close', async () => {
    vi.useFakeTimers();
    vi.mocked(refreshRealtimeSession).mockResolvedValue({ kind: 'refreshed', token: 'fresh' });
    client.connect('stale');
    MockWebSocket.instances[0].simulateOpen();
    MockWebSocket.instances[0].simulateClose({ code: 1008, reason: 'auth required' });
    await vi.advanceTimersByTimeAsync(0);

    expect(MockWebSocket.instances).toHaveLength(2);
    MockWebSocket.instances[1].simulateOpen();
    expect(MockWebSocket.instances[1].send).toHaveBeenCalledWith(
      JSON.stringify({ type: 'auth', token: 'fresh' }),
    );
    vi.useRealTimers();
  });

  it('stops reconnecting when the session is revoked and cannot be refreshed', async () => {
    vi.useFakeTimers();
    vi.mocked(refreshRealtimeSession).mockResolvedValue({ kind: 'expired' });
    client.connect('revoked');
    MockWebSocket.instances[0].simulateOpen();
    MockWebSocket.instances[0].simulateClose({ code: 1008, reason: 'auth required' });
    await vi.advanceTimersByTimeAsync(0);

    expect(client.state).toBe('disconnected');
    await vi.advanceTimersByTimeAsync(60_000);
    expect(MockWebSocket.instances).toHaveLength(1);
    expect(refreshRealtimeSession).toHaveBeenCalledTimes(1);
    vi.useRealTimers();
  });

  it('uses fresh credentials when reconnecting and ignores the replaced socket', async () => {
    vi.useFakeTimers();
    let token = 'old';
    client = new RealtimeClient('ws://localhost/ws', async () => token);
    client.connect('old');
    await vi.advanceTimersByTimeAsync(0);
    const first = MockWebSocket.instances[0];
    first.simulateOpen();
    token = 'fresh';
    first.simulateClose();
    await vi.advanceTimersByTimeAsync(1000);
    const second = MockWebSocket.instances[1];
    second.simulateOpen();
    expect(second.send).toHaveBeenCalledWith(JSON.stringify({ type: 'auth', token: 'fresh' }));
    first.simulateClose();
    expect(client.state).toBe('connected');
    vi.useRealTimers();
  });

  it('does not reopen after logout while credential refresh is pending', async () => {
    let resolve!: (token: string) => void;
    client = new RealtimeClient('ws://localhost/ws', () => new Promise(r => { resolve = r; }));
    client.connect('old');
    client.disconnect();
    resolve('fresh');
    await Promise.resolve();
    expect(MockWebSocket.instances).toHaveLength(0);
    expect(client.state).toBe('disconnected');
  });

  it('preserves subscriptions for credential rotation but clears them for an identity change', () => {
    client.connect('old');
    MockWebSocket.instances[0].simulateOpen();
    const delivered = vi.fn();
    client.subscribe('conversation:362', delivered);
    client.connect('fresh');
    const rotated = MockWebSocket.instances[1];
    rotated.simulateOpen();
    expect(rotated.send).toHaveBeenCalledWith(JSON.stringify({ type: 'subscribe', channel: 'conversation:362' }));
    client.disconnect(true);
    client.connect('other-user');
    const other = MockWebSocket.instances[2];
    other.simulateOpen();
    expect(other.send).not.toHaveBeenCalledWith(JSON.stringify({ type: 'subscribe', channel: 'conversation:362' }));
    other.simulateMessage({ channel: 'conversation:362', type: 'CONVERSATION_TURN_EVENT' });
    expect(delivered).not.toHaveBeenCalled();
  });

  it('ignores malformed frames and closes the socket on error', () => {
    client.connect('t');
    MockWebSocket.instances[0].simulateOpen();

    expect(() => {
      MockWebSocket.instances[0].onmessage?.({ data: 'not-json' });
    }).not.toThrow();

    MockWebSocket.instances[0].onerror?.();
    expect(MockWebSocket.instances[0].close).toHaveBeenCalled();
  });
});

describe('getRealtimeClient 能力选择', () => {
  beforeEach(() => {
    vi.resetModules();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.resetModules();
  });

  it('SharedWorker 不可用时回退现有客户端', async () => {
    vi.stubGlobal('SharedWorker', undefined);
    const { getRealtimeClient, RealtimeClient } = await import('./client');
    expect(getRealtimeClient()).toBeInstanceOf(RealtimeClient);
  });

  it('SharedWorker 可用时返回标签页代理', async () => {
    class FakeSharedWorker {
      port = { onmessage: null, start: () => {}, postMessage: () => {}, close: () => {} };
    }
    vi.stubGlobal('SharedWorker', FakeSharedWorker);
    const { getRealtimeClient } = await import('./client');
    const { SharedWorkerClient } = await import('./sharedWorkerClient');
    expect(getRealtimeClient()).toBeInstanceOf(SharedWorkerClient);
  });

  it('worker 构造失败时回退现有客户端', async () => {
    class ThrowingSharedWorker {
      constructor() {
        throw new Error('blocked');
      }
    }
    vi.stubGlobal('SharedWorker', ThrowingSharedWorker);
    const { getRealtimeClient, RealtimeClient } = await import('./client');
    expect(getRealtimeClient()).toBeInstanceOf(RealtimeClient);
  });
});

describe('buildRealtimeUrl', () => {
  it('prefers the documented VITE_WS_URL over the legacy VITE_WS_BASE', () => {
    expect(getConfiguredRealtimeBase({
      VITE_WS_URL: 'wss://demo.example.com/ws',
      VITE_WS_BASE: 'ws://legacy.example.com/ws',
    })).toBe('wss://demo.example.com/ws');
  });

  it('converts http bases into websocket urls without duplicating the ws path', () => {
    expect(buildRealtimeUrl('http://localhost:7001')).toBe('ws://localhost:7001/ws');
    expect(buildRealtimeUrl('http://localhost:7001/ws')).toBe('ws://localhost:7001/ws');
  });

  it('uses secure websocket protocol for https pages', () => {
    vi.stubGlobal('window', {
      location: {
        href: 'https://demo.example.com/workitems/1',
      },
    });

    expect(buildRealtimeUrl()).toBe('wss://demo.example.com/ws');
  });

  it('keeps existing websocket urls and resolves relative paths', () => {
    vi.stubGlobal('window', {
      location: {
        href: 'http://localhost:3000/workitems/1',
      },
    });

    expect(buildRealtimeUrl('ws://localhost:7001/ws/')).toBe('ws://localhost:7001/ws');
    expect(buildRealtimeUrl('/socket')).toBe('ws://localhost:3000/socket/ws');
  });
});
