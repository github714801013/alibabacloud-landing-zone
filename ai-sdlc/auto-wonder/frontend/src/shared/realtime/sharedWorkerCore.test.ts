import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { SharedWorkerCore, type SocketLike } from './sharedWorkerCore';
import type { WorkerToTabMessage } from './sharedWorkerProtocol';
import type { RealtimeEvent } from './types';

class FakeSocket implements SocketLike {
  readyState = 0;
  sent: string[] = [];
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  onclose: ((event?: { code?: number; reason?: string }) => void) | null = null;
  onerror: (() => void) | null = null;
  close = vi.fn(() => {
    this.readyState = 3;
  });

  constructor(readonly url: string) {}

  send(data: string) {
    this.sent.push(data);
  }

  open() {
    this.readyState = 1;
    this.onopen?.();
  }

  emit(event: RealtimeEvent) {
    this.onmessage?.({ data: JSON.stringify(event) });
  }

  serverClose() {
    this.readyState = 3;
    this.onclose?.();
  }

  authClose() {
    this.readyState = 3;
    this.onclose?.({ code: 1008, reason: 'auth required' });
  }
}

function createCore() {
  const sockets: FakeSocket[] = [];
  const messages: Array<{ tabId: number; message: WorkerToTabMessage }> = [];
  const core = new SharedWorkerCore(
    (url) => {
      const socket = new FakeSocket(url);
      sockets.push(socket);
      return socket;
    },
    (tabId, message) => messages.push({ tabId, message }),
  );
  return { core, sockets, messages };
}

const event = (channel: string): RealtimeEvent => ({
  channel,
  type: 'EVENT',
  payload: {},
  timestamp: 1,
});

describe('SharedWorkerCore 单标签页链路', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('hello 握手回复 ready', () => {
    const { core, messages } = createCore();
    core.handleMessage(1, { type: 'hello' });
    expect(messages).toContainEqual({ tabId: 1, message: { type: 'ready' } });
  });

  it('为一个标签页建立一条连接并在打开后发送鉴权帧', () => {
    const { core, sockets, messages } = createCore();
    core.handleMessage(1, { type: 'connect', key: '1:1', token: 'token-a', url: 'ws://x/ws' });

    expect(sockets).toHaveLength(1);
    expect(sockets[0].url).toBe('ws://x/ws');
    sockets[0].open();

    expect(sockets[0].sent).toContain(JSON.stringify({ type: 'auth', token: 'token-a' }));
    expect(messages).toContainEqual({ tabId: 1, message: { type: 'state', state: 'connected' } });
  });

  it('同一连接池键重复 connect 复用已有连接', () => {
    const { core, sockets } = createCore();
    core.handleMessage(1, { type: 'connect', key: '1:1', token: 'token-a', url: 'ws://x/ws' });
    sockets[0].open();

    core.handleMessage(1, { type: 'connect', key: '1:1', token: 'token-a', url: 'ws://x/ws' });
    expect(sockets).toHaveLength(1);
  });

  it('首个订阅在连接打开后下发 subscribe 帧', () => {
    const { core, sockets } = createCore();
    core.handleMessage(1, { type: 'connect', key: '1:1', token: 'token-a', url: 'ws://x/ws' });

    core.handleMessage(1, { type: 'subscribe', key: '1:1', channel: 'conversation:9' });
    expect(sockets[0].sent).not.toContain(JSON.stringify({ type: 'subscribe', channel: 'conversation:9' }));

    sockets[0].open();
    expect(sockets[0].sent).toContain(JSON.stringify({ type: 'subscribe', channel: 'conversation:9' }));
  });

  it('通知频道同样向服务端下发 subscribe 帧', () => {
    const { core, sockets } = createCore();
    core.handleMessage(1, { type: 'connect', key: '1:1', token: 'token-a', url: 'ws://x/ws' });
    sockets[0].open();

    core.handleMessage(1, { type: 'subscribe', key: '1:1', channel: 'notification:1:99' });
    expect(sockets[0].sent).toContain(JSON.stringify({ type: 'subscribe', channel: 'notification:1:99' }));
  });

  it('只把事件转发给订阅该频道的标签页', () => {
    const { core, sockets, messages } = createCore();
    core.handleMessage(1, { type: 'connect', key: '1:1', token: 'token-a', url: 'ws://x/ws' });
    sockets[0].open();
    core.handleMessage(1, { type: 'subscribe', key: '1:1', channel: 'conversation:9' });

    const received = event('conversation:9');
    sockets[0].emit(received);
    sockets[0].emit(event('conversation:10'));

    expect(messages).toContainEqual({ tabId: 1, message: { type: 'event', event: received } });
    expect(messages.filter((item) => item.message.type === 'event')).toHaveLength(1);
  });

  it('发往服务端的帧只有 auth、subscribe、unsubscribe', () => {
    const { core, sockets } = createCore();
    core.handleMessage(1, { type: 'connect', key: '1:1', token: 'token-a', url: 'ws://x/ws' });
    sockets[0].open();
    core.handleMessage(1, { type: 'subscribe', key: '1:1', channel: 'conversation:9' });
    core.handleMessage(1, { type: 'unsubscribe', key: '1:1', channel: 'conversation:9' });

    const types = sockets[0].sent.map((frame) => JSON.parse(frame).type).sort();
    expect(types).toEqual(['auth', 'subscribe', 'unsubscribe']);
  });

  it('ai:session 频道只做本地路由，不下发服务端订阅帧', () => {
    const { core, sockets, messages } = createCore();
    core.handleMessage(1, { type: 'connect', key: '1:1', token: 'token-a', url: 'ws://x/ws' });
    sockets[0].open();

    core.handleMessage(1, { type: 'subscribe', key: '1:1', channel: 'ai:session:9' });
    expect(sockets[0].sent.some((frame) => frame.includes('subscribe'))).toBe(false);

    const received = event('ai:session:9');
    sockets[0].emit(received);
    expect(messages).toContainEqual({ tabId: 1, message: { type: 'event', event: received } });
  });
});

describe('SharedWorkerCore 多标签页与订阅聚合', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  const connect = (core: SharedWorkerCore, tabId: number, key = '1:1') => {
    core.handleMessage(tabId, { type: 'connect', key, token: 'token-a', url: 'ws://x/ws' });
  };

  it('同一连接池键的多个标签页只建立一条连接', () => {
    const { core, sockets } = createCore();
    connect(core, 1);
    sockets[0].open();
    connect(core, 2);
    connect(core, 3);

    expect(sockets).toHaveLength(1);
  });

  it('新标签页加入已有连接并收到自己订阅的事件', () => {
    const { core, sockets, messages } = createCore();
    connect(core, 1);
    sockets[0].open();
    core.handleMessage(1, { type: 'subscribe', key: '1:1', channel: 'conversation:9' });

    connect(core, 2);
    core.handleMessage(2, { type: 'subscribe', key: '1:1', channel: 'conversation:9' });
    sockets[0].emit(event('conversation:9'));

    expect(messages.filter((item) => item.tabId === 1 && item.message.type === 'event')).toHaveLength(1);
    expect(messages.filter((item) => item.tabId === 2 && item.message.type === 'event')).toHaveLength(1);
  });

  it('不同连接池键各自建立连接且事件不串流', () => {
    const { core, sockets, messages } = createCore();
    connect(core, 1, '1:1');
    connect(core, 2, '1:2');
    expect(sockets).toHaveLength(2);

    sockets[0].open();
    sockets[1].open();
    core.handleMessage(1, { type: 'subscribe', key: '1:1', channel: 'conversation:9' });
    core.handleMessage(2, { type: 'subscribe', key: '1:2', channel: 'dispatch:9' });

    sockets[0].emit(event('conversation:9'));
    sockets[1].emit(event('conversation:9'));

    expect(messages.filter((item) => item.message.type === 'event')).toHaveLength(1);
    expect(messages.find((item) => item.message.type === 'event')?.tabId).toBe(1);
  });

  it('多个标签页订阅同一频道只下发一次，最后退订才下发', () => {
    const { core, sockets } = createCore();
    connect(core, 1);
    connect(core, 2);
    sockets[0].open();
    core.handleMessage(1, { type: 'subscribe', key: '1:1', channel: 'conversation:9' });
    core.handleMessage(2, { type: 'subscribe', key: '1:1', channel: 'conversation:9' });

    expect(sockets[0].sent.filter((frame) => frame.includes('"subscribe"'))).toHaveLength(1);

    core.handleMessage(1, { type: 'unsubscribe', key: '1:1', channel: 'conversation:9' });
    expect(sockets[0].sent).not.toContain(JSON.stringify({ type: 'unsubscribe', channel: 'conversation:9' }));

    core.handleMessage(2, { type: 'unsubscribe', key: '1:1', channel: 'conversation:9' });
    expect(sockets[0].sent.filter((frame) => frame.includes('"unsubscribe"'))).toHaveLength(1);
  });

  it('同一标签页重复订阅同一频道不重复下发', () => {
    const { core, sockets } = createCore();
    connect(core, 1);
    sockets[0].open();
    core.handleMessage(1, { type: 'subscribe', key: '1:1', channel: 'conversation:9' });
    core.handleMessage(1, { type: 'subscribe', key: '1:1', channel: 'conversation:9' });

    expect(sockets[0].sent.filter((frame) => frame.includes('"subscribe"'))).toHaveLength(1);
  });

  it('标签页离开只清理自己的订阅，其他标签页继续接收', () => {
    const { core, sockets, messages } = createCore();
    connect(core, 1);
    connect(core, 2);
    sockets[0].open();
    core.handleMessage(1, { type: 'subscribe', key: '1:1', channel: 'conversation:9' });
    core.handleMessage(2, { type: 'subscribe', key: '1:1', channel: 'conversation:9' });

    core.handleMessage(2, { type: 'leave' });
    expect(sockets[0].sent.filter((frame) => frame.includes('"unsubscribe"'))).toHaveLength(0);

    sockets[0].emit(event('conversation:9'));
    expect(messages.filter((item) => item.tabId === 2 && item.message.type === 'event')).toHaveLength(0);
    expect(messages.filter((item) => item.tabId === 1 && item.message.type === 'event')).toHaveLength(1);

    core.handleMessage(1, { type: 'leave' });
    expect(sockets[0].close).toHaveBeenCalled();
  });

  it('重连后按所有标签页的频道并集重放且不重复', () => {
    const { core, sockets } = createCore();
    connect(core, 1);
    connect(core, 2);
    sockets[0].open();
    core.handleMessage(1, { type: 'subscribe', key: '1:1', channel: 'conversation:9' });
    core.handleMessage(2, { type: 'subscribe', key: '1:1', channel: 'dispatch:9' });

    sockets[0].serverClose();
    vi.advanceTimersByTime(1000);
    expect(sockets).toHaveLength(2);
    sockets[1].open();

    const replayed = sockets[1].sent
      .map((frame) => JSON.parse(frame))
      .filter((frame) => frame.type === 'subscribe')
      .map((frame) => frame.channel)
      .sort();
    expect(replayed).toEqual(['conversation:9', 'dispatch:9']);
  });

  it('鉴权失败关闭时只通知一个标签页刷新 token', () => {
    const { core, sockets, messages } = createCore();
    connect(core, 1);
    connect(core, 2);
    sockets[0].open();

    sockets[0].authClose();

    const authFailed = messages.filter((item) => item.message.type === 'auth-failed');
    expect(authFailed).toHaveLength(1);
    expect(authFailed[0].tabId).toBe(1);
    expect(messages).toContainEqual({ tabId: 1, message: { type: 'state', state: 'reconnecting' } });
  });

  it('普通断开不触发 token 刷新通知', () => {
    const { core, sockets, messages } = createCore();
    connect(core, 1);
    sockets[0].open();

    sockets[0].serverClose();

    expect(messages.filter((item) => item.message.type === 'auth-failed')).toHaveLength(0);
  });

  it('无人订阅的频道事件被丢弃且不抛异常', () => {
    const { core, sockets, messages } = createCore();
    connect(core, 1);
    sockets[0].open();

    expect(() => sockets[0].emit(event('conversation:404'))).not.toThrow();
    expect(messages.filter((item) => item.message.type === 'event')).toHaveLength(0);
  });

  it('最后一个标签页离开时清理重连定时器', () => {
    const { core, sockets } = createCore();
    connect(core, 1);
    sockets[0].open();
    core.handleMessage(1, { type: 'subscribe', key: '1:1', channel: 'conversation:9' });

    sockets[0].serverClose();
    core.handleMessage(1, { type: 'leave' });
    vi.advanceTimersByTime(30000);

    expect(sockets).toHaveLength(1);
  });

  it('空 token 不建立连接', () => {
    const { core, sockets } = createCore();
    core.handleMessage(1, { type: 'connect', key: '1:1', token: '', url: 'ws://x/ws' });
    expect(sockets).toHaveLength(0);
  });

  it('缺少连接地址时下发 fatal 且不建立连接', () => {
    const { core, sockets, messages } = createCore();
    core.handleMessage(1, { type: 'connect', key: '1:1', token: 'token-a', url: '' });

    expect(sockets).toHaveLength(0);
    expect(messages).toContainEqual({
      tabId: 1,
      message: { type: 'fatal', reason: 'missing-url' },
    });
  });

  it('服务端拒绝无权限频道时其他频道与连接状态不受影响', () => {
    const { core, sockets, messages } = createCore();
    connect(core, 1);
    sockets[0].open();
    core.handleMessage(1, { type: 'subscribe', key: '1:1', channel: 'dispatch:7' });

    expect(() => sockets[0].onmessage?.({ data: 'forbidden' })).not.toThrow();
    sockets[0].emit(event('secret:1'));
    expect(messages.filter((item) => item.message.type === 'event')).toHaveLength(0);

    sockets[0].emit(event('dispatch:7'));
    expect(messages).toContainEqual({
      tabId: 1,
      message: { type: 'event', event: event('dispatch:7') },
    });
    expect(sockets[0].close).not.toHaveBeenCalled();
    expect(messages).toContainEqual({ tabId: 1, message: { type: 'state', state: 'connected' } });
  });

  it('同键 connect 携带空 token 不重连已有连接', () => {
    const { core, sockets } = createCore();
    connect(core, 1);
    sockets[0].open();

    core.handleMessage(2, { type: 'connect', key: '1:1', token: '', url: 'ws://x/ws' });

    expect(sockets).toHaveLength(1);
    expect(sockets[0].close).not.toHaveBeenCalled();
  });

  it('同键 connect 携带新 token 时更新共享连接', () => {
    const { core, sockets } = createCore();
    connect(core, 1);
    sockets[0].open();
    core.handleMessage(1, { type: 'subscribe', key: '1:1', channel: 'conversation:9' });

    core.handleMessage(2, { type: 'connect', key: '1:1', token: 'token-b', url: 'ws://x/ws' });

    expect(sockets).toHaveLength(2);
    sockets[1].open();
    expect(sockets[1].sent).toContain(JSON.stringify({ type: 'auth', token: 'token-b' }));
    expect(sockets[1].sent).toContain(JSON.stringify({ type: 'subscribe', channel: 'conversation:9' }));
  });

  it('标签页切换连接池键时释放旧连接', () => {
    const { core, sockets, messages } = createCore();
    connect(core, 1, '1:1');
    sockets[0].open();
    core.handleMessage(1, { type: 'subscribe', key: '1:1', channel: 'conversation:9' });

    core.handleMessage(1, { type: 'connect', key: '1:2', token: 'token-b', url: 'ws://x/ws' });

    expect(sockets).toHaveLength(2);
    expect(sockets[0].close).toHaveBeenCalled();
    sockets[1].open();
    messages.length = 0;
    sockets[0].emit(event('conversation:9'));
    expect(messages.filter((item) => item.message.type === 'event')).toHaveLength(0);
  });

  it('disconnect 关闭该键的连接并在重新登录后重建', () => {
    const { core, sockets, messages } = createCore();
    connect(core, 1);
    connect(core, 2);
    sockets[0].open();

    core.handleMessage(1, { type: 'disconnect', key: '1:1' });

    expect(sockets[0].close).toHaveBeenCalled();
    expect(
      messages.filter((item) => item.message.type === 'state' && item.message.state === 'disconnected'),
    ).toHaveLength(2);

    connect(core, 1);
    expect(sockets).toHaveLength(2);
  });

  it('一个标签页切换连接池键不影响同键其他标签页', () => {
    const { core, sockets, messages } = createCore();
    connect(core, 1);
    connect(core, 2);
    sockets[0].open();
    core.handleMessage(1, { type: 'subscribe', key: '1:1', channel: 'conversation:9' });
    core.handleMessage(2, { type: 'subscribe', key: '1:1', channel: 'conversation:9' });

    core.handleMessage(1, { type: 'leave' });
    core.handleMessage(1, { type: 'connect', key: '1:2', token: 'token-b', url: 'ws://x/ws' });

    expect(sockets).toHaveLength(2);
    expect(sockets[0].close).not.toHaveBeenCalled();

    messages.length = 0;
    sockets[0].emit(event('conversation:9'));
    expect(messages.filter((item) => item.message.type === 'event')).toEqual([
      { tabId: 2, message: { type: 'event', event: event('conversation:9') } },
    ]);

    sockets[1].open();
    core.handleMessage(1, { type: 'subscribe', key: '1:2', channel: 'dispatch:7' });
    messages.length = 0;
    sockets[1].emit(event('dispatch:7'));
    expect(messages.filter((item) => item.message.type === 'event')).toEqual([
      { tabId: 1, message: { type: 'event', event: event('dispatch:7') } },
    ]);
  });

  it('并发订阅与退订后引用归零且帧数正确', () => {
    const { core, sockets } = createCore();
    connect(core, 1);
    connect(core, 2);
    sockets[0].open();

    core.handleMessage(1, { type: 'subscribe', key: '1:1', channel: 'conversation:9' });
    core.handleMessage(2, { type: 'subscribe', key: '1:1', channel: 'conversation:9' });
    core.handleMessage(1, { type: 'unsubscribe', key: '1:1', channel: 'conversation:9' });
    core.handleMessage(2, { type: 'unsubscribe', key: '1:1', channel: 'conversation:9' });

    expect(sockets[0].sent.filter((frame) => frame.includes('"subscribe"'))).toHaveLength(1);
    expect(sockets[0].sent.filter((frame) => frame.includes('"unsubscribe"'))).toHaveLength(1);
  });
});
