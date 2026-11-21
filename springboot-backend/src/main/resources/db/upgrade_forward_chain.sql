

-- 管理员多跳转发链
CREATE TABLE IF NOT EXISTS `forward_chain` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint DEFAULT NULL,
  `sort_order` int DEFAULT NULL,
  `name` varchar(100) NOT NULL,
  `in_node_id` bigint NOT NULL,
  `in_port` int NOT NULL,
  `target_host` varchar(255) NOT NULL,
  `target_port` int NOT NULL,
  `protocol` varchar(20) NOT NULL DEFAULT 'tls',
  `hops_json` longtext NOT NULL,
  `paths_json` longtext NOT NULL,
  `endpoint_ports_json` longtext NOT NULL,
  `probe_mode` varchar(12) NOT NULL DEFAULT 'tcp',
  `probe_interval_ms` int NOT NULL DEFAULT 300000,
  `probe_timeout_ms` int NOT NULL DEFAULT 3000,
  `switch_threshold_ms` int NOT NULL DEFAULT 1000,
  `selected_path_index` int NOT NULL DEFAULT 0,
  `last_probe_time` bigint NOT NULL DEFAULT 0,
  `in_flow` bigint NOT NULL DEFAULT 0,
  `out_flow` bigint NOT NULL DEFAULT 0,
  `created_time` bigint NOT NULL,
  `updated_time` bigint NOT NULL,
  `status` int NOT NULL DEFAULT 1,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_forward_chain_entry_port` (`in_node_id`, `in_port`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 同时兼容已有转发链的数据库及 MySQL 5.7。
SET @chain_probe_migration = IF(
  EXISTS (SELECT 1 FROM information_schema.COLUMNS
          WHERE table_schema = DATABASE() AND table_name = 'forward_chain' AND column_name = 'probe_mode'),
  'SELECT 1',
  'ALTER TABLE `forward_chain` ADD COLUMN `probe_mode` varchar(12) NOT NULL DEFAULT ''tcp'''
);
PREPARE chain_probe_stmt FROM @chain_probe_migration;
EXECUTE chain_probe_stmt;
DEALLOCATE PREPARE chain_probe_stmt;

SET @chain_user_migration = IF(
  EXISTS (SELECT 1 FROM information_schema.COLUMNS
          WHERE table_schema = DATABASE() AND table_name = 'forward_chain' AND column_name = 'user_id'),
  'SELECT 1',
  'ALTER TABLE `forward_chain` ADD COLUMN `user_id` bigint DEFAULT NULL'
);
PREPARE chain_user_stmt FROM @chain_user_migration;
EXECUTE chain_user_stmt;
DEALLOCATE PREPARE chain_user_stmt;

SET @chain_order_migration = IF(
  EXISTS (SELECT 1 FROM information_schema.COLUMNS
          WHERE table_schema = DATABASE() AND table_name = 'forward_chain' AND column_name = 'sort_order'),
  'SELECT 1',
  'ALTER TABLE `forward_chain` ADD COLUMN `sort_order` int DEFAULT NULL'
);
PREPARE chain_order_stmt FROM @chain_order_migration;
EXECUTE chain_order_stmt;
DEALLOCATE PREPARE chain_order_stmt;

-- FluxChain：仅替换旧项目默认名称，保留自定义的网站名称。
UPDATE `vite_config` SET `value` = 'FluxChain'
WHERE `name` = 'app_name' AND BINARY `value` IN ('flux', 'flux-panel');
