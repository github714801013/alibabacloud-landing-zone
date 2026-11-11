import { SharedWorkerCore, type SocketLike } from './sharedWorkerCore';

const ports = new Map<number, MessagePort>();
let nextTabId = 1;

const core = new SharedWorkerCore(
  (url) => new WebSocket(url) as unknown as SocketLike,
  (tabId, message) => {
    ports.get(tabId)?.postMessage(message);
  },
);

const workerScope = self as unknown as {
  onconnect: ((event: MessageEvent) => void) | null;
};

workerScope.onconnect = (event: MessageEvent) => {
  const port = event.ports[0];
  const tabId = nextTabId;
  nextTabId += 1;
  ports.set(tabId, port);
  port.onmessage = (message) => {
    if (!message.data || typeof message.data !== 'object') return;
    const type = (message.data as { type?: string }).type;
    // 心跳只验证 worker 存活，不进连接池逻辑。
    if (type === 'ping') {
      port.postMessage({ type: 'pong' });
      return;
    }
    core.handleMessage(tabId, message.data);
    // leave 只由页面卸载发送；此时回收端口，避免 worker 内残留已关闭标签页。
    if (type === 'leave') {
      ports.delete(tabId);
      port.close();
    }
  };
  port.start();
};
