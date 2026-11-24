-- H2 版 agent_conversation_turn_event 建表语句，镜像 docs/autowonder-schema.sql 的列与
-- 唯一键 uk_turn_event_chunk，仅用于 DAO 层批量落库幂等与批内去重查询回归测试。
CREATE TABLE IF NOT EXISTS agent_conversation_turn_event (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id         BIGINT       NOT NULL,
    conversation_id   BIGINT       NOT NULL,
    turn_id           BIGINT       NOT NULL,
    dispatch_attempt  INT          NOT NULL,
    event_seq         BIGINT       NOT NULL,
    chunk_index       INT          NOT NULL DEFAULT 0,
    chunk_count       INT          NOT NULL DEFAULT 1,
    event_type        VARCHAR(32)  NOT NULL,
    payload_fragment  CLOB         NOT NULL,
    gmt_create        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_turn_event_chunk UNIQUE (tenant_id, turn_id, dispatch_attempt, event_seq, chunk_index)
);

CREATE INDEX IF NOT EXISTS idx_turn_event_page
    ON agent_conversation_turn_event (tenant_id, conversation_id, turn_id, id);
