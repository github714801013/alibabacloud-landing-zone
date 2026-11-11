package com.aliyun.autowonder.integration.wecom;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class OkHttpWecomSocketFactory implements WecomSocketFactory {
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ZERO)
            .build();

    @Override
    public WecomSocket create() {
        return new OkHttpWecomSocket(http);
    }

    private static final class OkHttpWecomSocket implements WecomSocket {
        private final OkHttpClient http;
        private volatile WebSocket socket;
        private volatile boolean open;

        private OkHttpWecomSocket(OkHttpClient http) {
            this.http = http;
        }

        @Override
        public void connect(String url, Listener listener) {
            Request request;
            try {
                request = new Request.Builder().url(url).build();
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("企业微信连接地址无效", e);
            }
            socket = http.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                    open = true;
                    listener.onOpen();
                }

                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    listener.onText(text);
                }

                @Override
                public void onClosed(WebSocket webSocket, int code, String reason) {
                    open = false;
                    listener.onClosed();
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    open = false;
                    listener.onFailure(t);
                }
            });
        }

        @Override
        public void send(String frame) {
            WebSocket current = socket;
            if (current == null || !current.send(frame)) {
                // 帧未写入连接时调用方可以安全重试；继续等待确认只会得到结果未知。
                throw new IllegalStateException("企业微信帧未写入连接");
            }
        }

        @Override
        public void close() {
            WebSocket current = socket;
            socket = null;
            open = false;
            if (current != null) {
                current.close(1000, "closed");
            }
        }

        @Override
        public boolean isOpen() {
            return open;
        }
    }
}
