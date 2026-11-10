import { describe, expect, it, vi } from 'vitest';
import './sharedWorker.worker';

class FakePort {
  onmessage: ((event: { data: unknown }) => void) | null = null;
  postMessage = vi.fn();
  start = vi.fn();
  close = vi.fn();

  receive(data: unknown) {
    this.onmessage?.({ data });
  }
}

type ConnectHandler = (event: { ports: FakePort[] }) => void;

function connectTab(): FakePort {
  const port = new FakePort();
  (self as unknown as { onconnect: ConnectHandler }).onconnect({ ports: [port] });
  return port;
}

describe('sharedWorker 入口', () => {
  it('回复 ready 与 pong，并在 leave 后回收端口', () => {
    const port = connectTab();
    expect(port.start).toHaveBeenCalled();

    port.receive({ type: 'hello' });
    expect(port.postMessage).toHaveBeenCalledWith({ type: 'ready' });

    port.receive({ type: 'ping' });
    expect(port.postMessage).toHaveBeenCalledWith({ type: 'pong' });

    port.receive({ type: 'leave' });
    expect(port.close).toHaveBeenCalled();
  });
});
