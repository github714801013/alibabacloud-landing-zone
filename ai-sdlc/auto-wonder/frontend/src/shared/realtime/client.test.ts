import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { RealtimeClient, buildRealtimeUrl, getConfiguredRealtimeBase } from './client';

class MockWebSocket {
  static instances: MockWebSocket[] = [];
  static OPEN = 1;
  url: string;
  readyState = 0;
  onopen: (() => void) | null = null;
  onclose: (() => void) | null = null;
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

  simulateClose() {
    this.readyState = 3;
    this.onclose?.();
  }
}

describe('RealtimeClient', () => {
  let client: RealtimeClient;

  beforeEach(() => {
    MockWebSocket.instances = [];
    vi.stubGlobal('WebSocket', MockWebSocket);
    client = new RealtimeClient('ws://localhost:8080/ws');
  });

  afterEach(() => {
    client.disconnect();
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

  it('rebind reconnects with the new token and replays server subscriptions', () => {
    client.connect('old-token');
    MockWebSocket.instances[0].simulateOpen();
    client.subscribe('conversation:9', vi.fn());

    client.rebind('new-token');

    expect(MockWebSocket.instances).toHaveLength(2);
    expect(MockWebSocket.instances[0].close).toHaveBeenCalled();
    MockWebSocket.instances[1].simulateOpen();
    expect(MockWebSocket.instances[1].send).toHaveBeenCalledWith(
      JSON.stringify({ type: 'auth', token: 'new-token' }),
    );
    expect(MockWebSocket.instances[1].send).toHaveBeenCalledWith(
      JSON.stringify({ type: 'subscribe', channel: 'conversation:9' }),
    );
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
