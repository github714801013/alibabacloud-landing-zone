package com.aliyun.autowonder.conversation;

public interface ConversationChannelSink {
    /** 渠道标识,如 "DINGTALK"。 */
    String channel();

    void deliverReply(AgentConversationDO conv, String replyMarkdown, String sourceExternalMsgId);

    /**
     * Whether this instance can deliver the channel right now. Single-connection channels override
     * this so only the connection owner claims outbound work; others leave it to the owner.
     */
    default boolean canDeliverNow() {
        return true;
    }

    /**
     * Notification that the turn reached a terminal status. Only channels that need to converge an
     * external task override this; message channels keep their existing behaviour untouched.
     */
    default void turnTerminal(AgentConversationDO conv, String status, String replyMarkdown) {
    }

    /** Optional diagnostic from the executor; older sinks retain their existing callback. */
    default void turnTerminal(AgentConversationDO conv, String status, String replyMarkdown,
            String error) {
        turnTerminal(conv, status, replyMarkdown);
    }
}
