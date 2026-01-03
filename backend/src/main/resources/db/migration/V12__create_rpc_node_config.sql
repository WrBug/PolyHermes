-- 创建 RPC 节点配置表
CREATE TABLE rpc_node_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    provider_type VARCHAR(50) NOT NULL COMMENT '服务商类型: ALCHEMY, INFURA, QUICKNODE, CHAINSTACK, GETBLOCK, CUSTOM, PUBLIC',
    name VARCHAR(100) NOT NULL COMMENT '节点名称',
    http_url VARCHAR(500) NOT NULL COMMENT 'HTTP RPC URL',
    ws_url VARCHAR(500) COMMENT 'WebSocket URL (可选)',
    api_key VARCHAR(200) COMMENT 'API Key (加密存储)',
    enabled BOOLEAN DEFAULT TRUE COMMENT '是否启用',
    priority INT DEFAULT 0 COMMENT '优先级(数字越小优先级越高)',
    last_check_time BIGINT COMMENT '最后检查时间(毫秒时间戳)',
    last_check_status VARCHAR(20) COMMENT '最后检查状态: HEALTHY, UNHEALTHY, UNKNOWN',
    response_time_ms INT COMMENT '最后一次响应时间(毫秒)',
    created_at BIGINT NOT NULL COMMENT '创建时间(毫秒时间戳)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(毫秒时间戳)',
    INDEX idx_enabled_priority (enabled, priority),
    INDEX idx_last_check_status (last_check_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Polygon RPC 节点配置表';

-- 插入默认公共节点 (PublicNode)
INSERT INTO rpc_node_config (
    provider_type,
    name,
    http_url,
    ws_url,
    enabled,
    priority,
    created_at,
    updated_at,
    last_check_status
) VALUES (
    'PUBLIC',
    'PublicNode (Default)',
    'https://polygon.publicnode.com',
    'wss://polygon.publicnode.com',
    TRUE,
    999,
    UNIX_TIMESTAMP() * 1000,
    UNIX_TIMESTAMP() * 1000,
    'UNKNOWN'
);
