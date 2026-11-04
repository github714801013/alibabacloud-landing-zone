package com.aliyun.autowonder.im;

public interface ImProvider {
    String provider();

    /**
     * 本实例当前能否真正发出该渠道消息。单连接渠道只有连接持有者返回 true；
     * 非持有者保留队列消息由持有者领取，避免误 ACK 或耗尽重试次数。
     */
    default boolean canDeliverNow() {
        return true;
    }

    void send(ImSendCommand command);
}
