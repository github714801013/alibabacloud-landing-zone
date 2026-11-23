-- 会话事件重复落库修复：派发代次单调递增、跨代次判重与自动重投预算。
-- 存量数据库执行一次本迁移；新建数据库使用 docs/autowonder-schema.sql。
-- 不会自动执行迁移：请在发布代码前手动执行；先迁移、再发布服务端。
--
-- 模型：
--   agent_conversation_turn.auto_redelivery_count  自上次手动重试以来的自动重投次数。
--   agent_conversation_turn.dispatch_attempt       改为只增代次；手动重试不再重置为 0。
--   agent_conversation_turn_event.idx_turn_event_dedup  支撑按序号回溯旧代次分片。
--
-- 重复执行：加列/加索引前先查 information_schema，可安全重跑。

SET @ddl = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `agent_conversation_turn` ADD COLUMN `auto_redelivery_count` INT NOT NULL DEFAULT 0 COMMENT ''自上次手动重试以来的自动重投次数'' AFTER `dispatch_attempt`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_conversation_turn' AND COLUMN_NAME = 'auto_redelivery_count'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `agent_conversation_turn_event` ADD INDEX `idx_turn_event_dedup` (`tenant_id`, `turn_id`, `event_seq`, `chunk_index`, `dispatch_attempt`)',
    'SELECT 1')
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_conversation_turn_event' AND INDEX_NAME = 'idx_turn_event_dedup'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
