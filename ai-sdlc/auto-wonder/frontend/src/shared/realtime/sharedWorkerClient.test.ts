import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  SharedWorkerClient,
  SHARED_WORKER_HANDSHAKE_TIMEOUT_MS,
  SHARED_WORKER_HEARTBEAT_INTERVAL_MS,
  SHARED_WORKER_PONG_TIMEOUT_MS,
} from './sharedWorkerClient';
import { RealtimeClient } from './client';
import { useAuthStore } from '@/shared/auth/store';
import type { ConnectionState, RealtimeEvent } from './types';

class MockPort {
  onmessage: ((event: { data: unknown }) => void) | null = null;
  postMessage = vi.fn();
  start = vi.fn();
  close = vi.fn();

  receive(message: unknown) {
    this.onmessage?.({ data: message });
  }
}

class MockSharedWorker {
  static instances: MockSharedWorker[] = [];
  port = new MockPort();
  onerror: (() => void) | null = null;

  constructor(readonly url: URL, readonly options: { type?: string; name?: string }) {
    MockSharedWorker.instances.push(this);
  }
}

class FakeWebSocket {
  static instances: FakeWebSocket[] = [];
  static OPEN = 1;
  readyState = 0;
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;
  close = vi.fn();
  send = vi.fn();

  constructor(readonly url: string) {
    FakeWebSocket.instances.push(this);
  }

  open() {
    this.readyState = 1;
    this.onopen?.();
  }
}

function createClient() {
  return new SharedWorkerClient({
    buildUrl: () => 'ws://example.test/ws',
    createFallback: () => new RealtimeClient('ws://example.test/ws'),
  });
}

const event = (channel: string): RealtimeEvent => ({
  channel,
  type: 'EVENT',
  payload: { value: 1 },
  timestamp: 1,
});

describe('SharedWorkerClient 标签页代理', () => {
  beforeEach(() => {
    MockSharedWorker.instances = [];
    FakeWebSocket.instances = [];
    vi.stubGlobal('SharedWorker', MockSharedWorker);
    vi.stubGlobal('WebSocket', FakeWebSocket);
    vi.useFakeTimers();
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    useAuthStore.setState({
      accessToken: 'token-a',
      user: { id: 1, username: 'alice', nickname: 'Alice', email: 'alice@example.test' },
      currentWorkspace: { id: 2, name: 'Workspace', description: '' },
      accessLevel: 'READ_WRITE',
    });
  });

  afterEach(() => {
    useAuthStore.getState().clear();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it('按用户与工作区计算连接池键并转发 connect', () => {
    const client = createClient();
    client.connect('token-a');

    const port = MockSharedWorker.instances[0].port;
    expect(port.postMessage).toHaveBeenCalledWith({ type: 'hello' });
    expect(port.postMessage).toHaveBeenCalledWith({
      type: 'connect',
      key: '1:2',
      token: 'token-a',
      url: 'ws://example.test/ws',
    });
    expect(client.state).toBe('connecting');
  });

  it('connect 幂等，重复调用不重复建立连接', () => {
    const client = createClient();
    client.connect('token-a');
    client.connect('token-a');

    const port = MockSharedWorker.instances[0].port;
    expect(port.postMessage.mock.calls.filter((call) => call[0].type === 'connect')).toHaveLength(1);
  });

  it('用户或工作区缺失时不建立连接', () => {
    useAuthStore.setState({ currentWorkspace: null });
    const client = createClient();
    client.connect('token-a');

    const port = MockSharedWorker.instances[0].port;
    expect(port.postMessage.mock.calls.some((call) => call[0].type === 'connect')).toBe(false);
    expect(client.state).toBe('disconnected');
  });

  it('订阅转发到 worker，重复订阅同一频道只转发一次', () => {
    const client = createClient();
    client.connect('token-a');
    const handler = vi.fn();
    client.subscribe('conversation:9', handler);
    client.subscribe('conversation:9', handler);

    const port = MockSharedWorker.instances[0].port;
    expect(port.postMessage.mock.calls.filter((call) => call[0].type === 'subscribe')).toHaveLength(1);
  });

  it('最后一个退订才转发 unsubscribe', () => {
    const client = createClient();
    client.connect('token-a');
    const first = client.subscribe('conversation:9', vi.fn());
    const second = client.subscribe('conversation:9', vi.fn());

    first.unsubscribe();
    const port = MockSharedWorker.instances[0].port;
    expect(port.postMessage.mock.calls.filter((call) => call[0].type === 'unsubscribe')).toHaveLength(0);

    second.unsubscribe();
    expect(port.postMessage).toHaveBeenCalledWith({
      type: 'unsubscribe',
      key: '1:2',
      channel: 'conversation:9',
    });
  });

  it('退订后重新订阅复用同一条连接', () => {
    const client = createClient();
    client.connect('token-a');
    const first = client.subscribe('conversation:9', vi.fn());
    first.unsubscribe();
    client.subscribe('conversation:9', vi.fn());

    const port = MockSharedWorker.instances[0].port;
    expect(port.postMessage.mock.calls.filter((call) => call[0].type === 'connect')).toHaveLength(1);
    expect(port.postMessage.mock.calls.filter((call) => call[0].type === 'subscribe')).toHaveLength(2);
  });

  it('连接前记录的订阅在 connect 后补发', () => {
    const client = createClient();
    client.subscribe('conversation:9', vi.fn());
    client.connect('token-a');

    const port = MockSharedWorker.instances[0].port;
    expect(port.postMessage).toHaveBeenCalledWith({
      type: 'subscribe',
      key: '1:2',
      channel: 'conversation:9',
    });
  });

  it('空 token 不建立连接', () => {
    const client = createClient();
    client.connect('');

    const port = MockSharedWorker.instances[0].port;
    expect(port.postMessage.mock.calls.some((call) => call[0].type === 'connect')).toBe(false);
    expect(client.state).toBe('disconnected');
  });

  it('同连接池键 rebind 携带新 token 重新 connect', () => {
    const client = createClient();
    client.connect('token-a');
    client.rebind('token-b');

    const port = MockSharedWorker.instances[0].port;
    expect(port.postMessage).toHaveBeenLastCalledWith({
      type: 'connect',
      key: '1:2',
      token: 'token-b',
      url: 'ws://example.test/ws',
    });
    expect(port.postMessage.mock.calls.filter((call) => call[0].type === 'connect')).toHaveLength(2);
  });

  it('同键但连接已断开时 rebind 重新拉起连接', () => {
    const client = createClient();
    client.connect('token-a');
    const port = MockSharedWorker.instances[0].port;
    port.receive({ type: 'state', state: 'disconnected' });

    client.rebind('token-b');

    expect(port.postMessage).toHaveBeenLastCalledWith({
      type: 'connect',
      key: '1:2',
      token: 'token-b',
      url: 'ws://example.test/ws',
    });
  });

  it('换工作区 rebind 改绑新键且不重放旧工作区订阅', () => {
    const client = createClient();
    client.connect('token-a');
    client.subscribe('conversation:9', vi.fn());

    useAuthStore.setState({ currentWorkspace: { id: 3, name: 'Other', description: '' } });
    client.rebind('token-b');

    const port = MockSharedWorker.instances[0].port;
    expect(port.postMessage).toHaveBeenCalledWith({
      type: 'connect',
      key: '1:3',
      token: 'token-b',
      url: 'ws://example.test/ws',
    });
    expect(port.postMessage.mock.calls.filter((call) => call[0].type === 'subscribe')).toHaveLength(1);
    expect(client.state).toBe('connecting');
  });

  it('工作区缺失时 rebind 释放当前绑定', () => {
    const client = createClient();
    client.connect('token-a');
    useAuthStore.setState({ currentWorkspace: null });

    client.rebind('token-b');

    const port = MockSharedWorker.instances[0].port;
    expect(port.postMessage).toHaveBeenCalledWith({ type: 'leave' });
    expect(client.state).toBe('disconnected');
  });

  it('页面卸载时通知 worker 释放订阅并关闭端口', () => {
    const client = createClient();
    client.connect('token-a');
    const port = MockSharedWorker.instances[0].port;

    window.dispatchEvent(new Event('pagehide'));

    expect(port.postMessage).toHaveBeenCalledWith({ type: 'leave' });
    expect(port.close).toHaveBeenCalled();
  });

  it('bfcache 恢复场景不释放订阅', () => {
    const client = createClient();
    client.connect('token-a');
    const port = MockSharedWorker.instances[0].port;
    const pagehide = new Event('pagehide');
    Object.defineProperty(pagehide, 'persisted', { value: true });

    window.dispatchEvent(pagehide);

    expect(port.postMessage).not.toHaveBeenCalledWith({ type: 'leave' });
    expect(port.close).not.toHaveBeenCalled();
  });

  it('worker 状态与事件转发到标签页', () => {
    const client = createClient();
    const states: ConnectionState[] = [];
    client.onStateChange((state) => states.push(state));
    client.connect('token-a');

    const port = MockSharedWorker.instances[0].port;
    port.receive({ type: 'state', state: 'connected' });
    expect(client.state).toBe('connected');

    const handler = vi.fn();
    client.subscribe('conversation:9', handler);
    port.receive({ type: 'event', event: event('conversation:9') });
    expect(handler).toHaveBeenCalledWith(event('conversation:9'));
    expect(states).toEqual(['connecting', 'connected']);
  });

  it('握手超时回退到每标签页连接并迁移连接与订阅', () => {
    const client = createClient();
    const handler = vi.fn();
    client.connect('token-a');
    client.subscribe('conversation:9', handler);

    vi.advanceTimersByTime(SHARED_WORKER_HANDSHAKE_TIMEOUT_MS);

    expect(MockSharedWorker.instances[0].port.close).toHaveBeenCalled();
    expect(FakeWebSocket.instances).toHaveLength(1);
    const socket = FakeWebSocket.instances[0];
    socket.open();
    expect(socket.send).toHaveBeenCalledWith(JSON.stringify({ type: 'auth', token: 'token-a' }));
    expect(socket.send).toHaveBeenCalledWith(
      JSON.stringify({ type: 'subscribe', channel: 'conversation:9' }),
    );

    socket.onmessage?.({ data: JSON.stringify(event('conversation:9')) });
    expect(handler).toHaveBeenCalledWith(event('conversation:9'));

    expect(console.warn).toHaveBeenCalledWith(expect.stringContaining('ready-timeout'));
    expect(console.warn).not.toHaveBeenCalledWith(expect.stringContaining('token-a'));
  });

  it('worker 报错时回退', () => {
    const client = createClient();
    client.connect('token-a');

    MockSharedWorker.instances[0].onerror?.();

    expect(FakeWebSocket.instances).toHaveLength(1);
  });

  it('收到 fatal 时回退并忽略迟到的 worker 消息', () => {
    const client = createClient();
    client.connect('token-a');
    const port = MockSharedWorker.instances[0].port;

    port.receive({ type: 'fatal', reason: 'missing-url' });
    expect(FakeWebSocket.instances).toHaveLength(1);

    port.receive({ type: 'state', state: 'connected' });
    expect(client.state).toBe('connecting');
  });

  it('心跳无响应时回退', () => {
    const client = createClient();
    client.connect('token-a');
    const port = MockSharedWorker.instances[0].port;
    port.receive({ type: 'ready' });

    vi.advanceTimersByTime(SHARED_WORKER_HEARTBEAT_INTERVAL_MS);
    expect(port.postMessage).toHaveBeenCalledWith({ type: 'ping' });
    expect(FakeWebSocket.instances).toHaveLength(0);

    vi.advanceTimersByTime(SHARED_WORKER_PONG_TIMEOUT_MS);
    expect(FakeWebSocket.instances).toHaveLength(1);

    const pings = () => port.postMessage.mock.calls.filter((call) => call[0].type === 'ping').length;
    const pinged = pings();
    vi.advanceTimersByTime(SHARED_WORKER_HEARTBEAT_INTERVAL_MS * 2);
    expect(pings()).toBe(pinged);
  });

  it('收到 pong 后保持共享连接', () => {
    const client = createClient();
    client.connect('token-a');
    const port = MockSharedWorker.instances[0].port;
    port.receive({ type: 'ready' });

    vi.advanceTimersByTime(SHARED_WORKER_HEARTBEAT_INTERVAL_MS);
    port.receive({ type: 'pong' });
    vi.advanceTimersByTime(SHARED_WORKER_PONG_TIMEOUT_MS);

    expect(FakeWebSocket.instances).toHaveLength(0);
  });

  it('降级后订阅与退订走回退连接且服务端帧类型不变', () => {
    const client = createClient();
    client.connect('token-a');
    const port = MockSharedWorker.instances[0].port;
    port.receive({ type: 'fatal', reason: 'missing-url' });
    const socket = FakeWebSocket.instances[0];
    socket.open();

    const handler = vi.fn();
    const subscription = client.subscribe('dispatch:7', handler);
    subscription.unsubscribe();

    const types = socket.send.mock.calls.map((call) => JSON.parse(call[0] as string).type).sort();
    expect(types).toEqual(['auth', 'subscribe', 'unsubscribe']);
  });
});
