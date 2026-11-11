-- 交付进展需保留所有带 error 的事件，该条件在运行事件大表上会退化为全表扫描。
-- 仅判定 error 是否为空，前缀取 1 个字符即可，不必为 VARCHAR(1024) 建完整索引。
ALTER TABLE dispatch_runtime_event
  ADD INDEX idx_dispatch_event_error (tenant_id, error(1), dispatch_id),
  ALGORITHM=INPLACE, LOCK=NONE;
