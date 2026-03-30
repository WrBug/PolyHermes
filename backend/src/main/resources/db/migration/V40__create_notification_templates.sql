-- 消息模板表
CREATE TABLE notification_templates (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_type VARCHAR(50) NOT NULL COMMENT '模板类型',
    template_content TEXT NOT NULL COMMENT '模板内容，支持 {{variable}} 变量',
    is_default TINYINT(1) DEFAULT 0 COMMENT '是否使用默认模板（0=自定义，1=默认）',
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE KEY uk_template_type (template_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息推送模板';

-- 插入默认模板
INSERT INTO notification_templates (template_type, template_content, is_default, created_at, updated_at) VALUES
('ORDER_SUCCESS', '🚀 <b>订单创建成功</b>

📊 <b>订单信息：</b>
• 订单ID: <code>{{order_id}}</code>
• 市场: <a href="{{market_link}}">{{market_title}}</a>
• 市场方向: <b>{{outcome}}</b>
• 方向: <b>{{side}}</b>
• 价格: <code>{{price}}</code>
• 数量: <code>{{quantity}}</code> shares
• 金额: <code>{{amount}}</code> USDC
• 账户: {{account_name}}
• 可用余额: <code>{{available_balance}}</code> USDC

⏰ 时间: <code>{{time}}</code>', 1, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000),

('ORDER_FAILED', '❌ <b>订单创建失败</b>

📊 <b>订单信息：</b>
• 市场: <a href="{{market_link}}">{{market_title}}</a>
• 市场方向: <b>{{outcome}}</b>
• 方向: <b>{{side}}</b>
• 价格: <code>{{price}}</code>
• 数量: <code>{{quantity}}</code> shares
• 金额: <code>{{amount}}</code> USDC
• 账户: {{account_name}}

⚠️ <b>错误信息：</b>
<code>{{error_message}}</code>

⏰ 时间: <code>{{time}}</code>', 1, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000),

('ORDER_FILTERED', '🚫 <b>订单被过滤</b>

📊 <b>订单信息：</b>
• 市场: <a href="{{market_link}}">{{market_title}}</a>
• 市场方向: <b>{{outcome}}</b>
• 方向: <b>{{side}}</b>
• 价格: <code>{{price}}</code>
• 数量: <code>{{quantity}}</code> shares
• 金额: <code>{{amount}}</code> USDC
• 账户: {{account_name}}

⚠️ <b>过滤类型：</b> <code>{{filter_type}}</code>

📝 <b>过滤原因：</b>
<code>{{filter_reason}}</code>

⏰ 时间: <code>{{time}}</code>', 1, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000),

('CRYPTO_TAIL_SUCCESS', '🚀 <b>加密价差策略下单成功</b>

📊 <b>订单信息：</b>
• 订单ID: <code>{{order_id}}</code>
• 策略: {{strategy_name}}
• 市场: <a href="{{market_link}}">{{market_title}}</a>
• 市场方向: <b>{{outcome}}</b>
• 方向: <b>{{side}}</b>
• 价格: <code>{{price}}</code>
• 数量: <code>{{quantity}}</code> shares
• 金额: <code>{{amount}}</code> USDC
• 账户: {{account_name}}

⏰ 时间: <code>{{time}}</code>', 1, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000),

('REDEEM_SUCCESS', '💸 <b>仓位赎回成功</b>

📊 <b>赎回信息：</b>
• 账户: {{account_name}}
• 交易哈希: <code>{{transaction_hash}}</code>
• 赎回总价值: <code>{{total_value}}</code> USDC
• 可用余额: <code>{{available_balance}}</code> USDC

⏰ 时间: <code>{{time}}</code>', 1, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000),

('REDEEM_NO_RETURN', '📋 <b>仓位已结算（无收益）</b>

📊 <b>结算信息：</b>
<i>市场已结算，您的预测未命中，赎回价值为 0。</i>

• 账户: {{account_name}}
• 交易哈希: <code>{{transaction_hash}}</code>
• 可用余额: <code>{{available_balance}}</code> USDC

⏰ 时间: <code>{{time}}</code>', 1, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000);
