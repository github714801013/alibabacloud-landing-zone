-- V101: 企业微信智能机器人渠道。复用现有平台配置、会话与通知表，仅新增 SHARED 群绑定表，
-- 并给通知偏好追加默认关闭的 wecom 列。
CREATE TABLE IF NOT EXISTS `wecom_robot_binding` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id` BIGINT NOT NULL,
  `platform_config_id` BIGINT UNSIGNED NOT NULL COMMENT 'SHARED 引用的 platform_im_channel_config.id',
  `group_id` VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'SHARED chatid，去空白后精确匹配',
  `agent_id` BIGINT NOT NULL COMMENT '关联数字人；创建人即显式委托的管理员',
  `route_version` BIGINT NOT NULL DEFAULT 0 COMMENT '群/数字人/启停等路由变化时递增',
  `status` VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
  `last_success_at` DATETIME NULL,
  `last_error` VARCHAR(1024) NULL,
  `creator_id` BIGINT NULL COMMENT '显式委托授权人（创建人）',
  `modifier_id` BIGINT NULL,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  `version` INT NOT NULL DEFAULT 0,
  `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `gmt_modified` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wecom_shared_group` (`platform_config_id`, `group_id`),
  KEY `idx_wecom_tenant_agent` (`tenant_id`, `agent_id`, `is_deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='企业微信共享群绑定（仅 SHARED）';

ALTER TABLE `notify_pref` ADD COLUMN `wecom` TINYINT NOT NULL DEFAULT 0 COMMENT '企业微信（0/1）';
