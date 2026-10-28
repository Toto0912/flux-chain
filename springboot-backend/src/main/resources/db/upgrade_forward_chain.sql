

-- 管理员多跳转发链
CREATE TABLE IF NOT EXISTS `forward_chain` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL,
  `in_node_id` bigint NOT NULL,
  `in_port` int NOT NULL,
  `target_host` varchar(255) NOT NULL,
  `target_port` int NOT NULL,
  `protocol` varchar(20) NOT NULL DEFAULT 'tls',
  `hops_json` longtext NOT NULL,
  `paths_json` longtext NOT NULL,
  `endpoint_ports_json` longtext NOT NULL,
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

-- FluxChain：仅替换旧项目默认名称，保留自定义的网站名称。
UPDATE `vite_config` SET `value` = 'FluxChain'
WHERE `name` = 'app_name' AND BINARY `value` IN ('flux', 'flux-panel');
