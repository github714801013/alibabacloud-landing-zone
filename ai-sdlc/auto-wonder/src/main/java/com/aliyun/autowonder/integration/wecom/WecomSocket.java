package com.aliyun.autowonder.integration.wecom;

/** 长连接的最小收发接口，测试用替身替换真实 WebSocket。 */
public interface WecomSocket {
    void connect(String url, Listener listener);

    void send(String frame);

    void close();

    boolean isOpen();

    interface Listener {
        void onOpen();

        void onText(String text);

        void onClosed();

        void onFailure(Throwable error);
    }
}
