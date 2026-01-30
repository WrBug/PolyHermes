package com.wrbug.polymarketbot.enums

/**
 * 错误码枚举
 * 按业务模块划分错误码范围
 * 
 * 错误码范围划分：
 * - 1001-1999: 参数错误 (Param Error)
 * - 2001-2999: 认证/权限错误 (Auth Error)
 * - 3001-3999: 资源不存在 (Not Found)
 * - 4001-4999: 业务逻辑错误 (Business Error)
 *   - 4001-4099: Leader 管理
 *   - 4101-4199: 模板管理
 *   - 4201-4299: 跟单管理
 *   - 4301-4399: 订单相关
 *   - 4401-4499: 市场相关
 *   - 4501-4599: 仓位相关
 * - 5001-5999: 服务器内部错误 (Server Error)
 */
enum class ErrorCode(
    val code: Int,
    val message: String,
    val messageKey: String
) {
    // ==================== 参数错误 (1001-1999) ====================
    PARAM_ERROR(1001, "参数错误", "error.param.error"),
    PARAM_EMPTY(1002, "参数不能为空", "error.param.empty"),
    PARAM_INVALID(1003, "参数无效", "error.param.invalid"),
    
    // 账户相关参数错误
    PARAM_PRIVATE_KEY_EMPTY(1101, "私钥不能为空", "error.param.private_key_empty"),
    PARAM_WALLET_ADDRESS_EMPTY(1102, "钱包地址不能为空", "error.param.wallet_address_empty"),
    PARAM_WALLET_ADDRESS_INVALID(1103, "钱包地址格式无效", "error.param.wallet_address_invalid"),
    PARAM_ACCOUNT_ID_INVALID(1104, "账户ID无效", "error.param.account_id_invalid"),
    PARAM_ACCOUNT_NAME_EMPTY(1105, "账户名称不能为空", "error.param.account_name_empty"),
    
    // Leader 相关参数错误
    PARAM_LEADER_ADDRESS_EMPTY(1201, "Leader 地址不能为空", "error.param.leader_address_empty"),
    PARAM_LEADER_ADDRESS_INVALID(1202, "Leader 地址格式无效", "error.param.leader_address_invalid"),
    PARAM_LEADER_ID_INVALID(1203, "Leader ID 无效", "error.param.leader_id_invalid"),
    PARAM_LEADER_NAME_EMPTY(1204, "Leader 名称不能为空", "error.param.leader_name_empty"),
    PARAM_CATEGORY_INVALID(1205, "分类无效，只支持 sports 或 crypto", "error.param.category_invalid"),
    
    // 模板相关参数错误
    PARAM_TEMPLATE_NAME_EMPTY(1301, "模板名称不能为空", "error.param.template_name_empty"),
    PARAM_TEMPLATE_ID_INVALID(1302, "模板 ID 无效", "error.param.template_id_invalid"),
    PARAM_COPY_MODE_INVALID(1303, "copyMode 必须是 RATIO 或 FIXED", "error.param.copy_mode_invalid"),
    PARAM_COPY_RATIO_INVALID(1304, "跟单比例无效", "error.param.copy_ratio_invalid"),
    PARAM_FIXED_AMOUNT_INVALID(1305, "固定金额无效", "error.param.fixed_amount_invalid"),
    
    // 跟单相关参数错误
    PARAM_COPY_TRADING_ID_INVALID(1401, "跟单关系ID无效", "error.param.copy_trading_id_invalid"),
    PARAM_ORDER_TYPE_INVALID(1402, "订单类型无效", "error.param.order_type_invalid"),
    PARAM_ORDER_TYPE_MUST_BE_MARKET_OR_LIMIT(1403, "订单类型必须是MARKET或LIMIT", "error.param.order_type_must_be_market_or_limit"),
    PARAM_QUANTITY_EMPTY(1404, "数量不能为空", "error.param.quantity_empty"),
    PARAM_PRICE_EMPTY(1405, "限价订单必须提供价格", "error.param.price_empty"),
    PARAM_SIDE_EMPTY(1406, "方向不能为空", "error.param.side_empty"),
    PARAM_MARKET_ID_EMPTY(1407, "市场ID不能为空", "error.param.market_id_empty"),
    PARAM_ORDER_TYPE_EMPTY(1408, "订单类型不能为空", "error.param.order_type_empty"),
    
    // 市场相关参数错误
    PARAM_TOKEN_ID_EMPTY(1501, "tokenId 不能为空", "error.param.token_id_empty"),
    PARAM_CONDITION_ID_EMPTY(1502, "conditionId 不能为空", "error.param.condition_id_empty"),
    PARAM_REDEEM_POSITIONS_EMPTY(1503, "赎回仓位列表不能为空", "error.param.redeem_positions_empty"),
    PARAM_INDEX_SETS_INVALID(1504, "结果索引无效", "error.param.index_sets_invalid"),
    
    // 统计相关参数错误
    PARAM_ORDER_TYPE_INVALID_FOR_TRACKING(1601, "订单类型无效，必须是: buy, sell, matched", "error.param.order_type_invalid_for_tracking"),
    
    // ==================== 认证/权限错误 (2001-2999) ====================
    AUTH_ERROR(2001, "认证失败", "error.auth.error"),
    AUTH_TOKEN_INVALID(2002, "认证令牌无效", "error.auth.token_invalid"),
    AUTH_TOKEN_EXPIRED(2003, "认证令牌已过期", "error.auth.token_expired"),
    AUTH_PERMISSION_DENIED(2004, "权限不足", "error.auth.permission_denied"),
    AUTH_API_KEY_INVALID(2005, "API Key 无效", "error.auth.api_key_invalid"),
    AUTH_API_SECRET_INVALID(2006, "API Secret 无效", "error.auth.api_secret_invalid"),
    AUTH_API_PASSPHRASE_INVALID(2007, "API Passphrase 无效", "error.auth.api_passphrase_invalid"),
    AUTH_API_CREDENTIALS_MISSING(2008, "API 凭证未配置", "error.auth.api_credentials_missing"),
    AUTH_USERNAME_OR_PASSWORD_ERROR(2009, "用户名或密码错误", "error.auth.username_or_password_error"),
    AUTH_RESET_KEY_INVALID(2010, "重置密钥错误", "error.auth.reset_key_invalid"),
    AUTH_RESET_PASSWORD_RATE_LIMIT(2011, "频率限制：1分钟内最多尝试3次，请稍后再试", "error.auth.reset_password_rate_limit"),
    AUTH_USER_NOT_FOUND(2012, "用户不存在", "error.auth.user_not_found"),
    AUTH_PASSWORD_WEAK(2013, "密码长度不符合要求，至少6位", "error.auth.password_weak"),
    BUILDER_API_KEY_NOT_CONFIGURED(2014, "Builder API Key 未配置", "error.builder_api_key_not_configured"),
    
    // ==================== 资源不存在 (3001-3999) ====================
    NOT_FOUND(3001, "资源不存在", "error.not_found"),
    ACCOUNT_NOT_FOUND(3002, "账户不存在", "error.account_not_found"),
    LEADER_NOT_FOUND(3003, "Leader 不存在", "error.leader_not_found"),
    TEMPLATE_NOT_FOUND(3004, "模板不存在", "error.template_not_found"),
    COPY_TRADING_NOT_FOUND(3005, "跟单关系不存在", "error.copy_trading_not_found"),
    MARKET_NOT_FOUND(3006, "市场不存在", "error.market_not_found"),
    ORDER_NOT_FOUND(3007, "订单不存在", "error.order_not_found"),
    POSITION_NOT_FOUND(3008, "仓位不存在", "error.position_not_found"),
    
    // ==================== 业务逻辑错误 (4001-4999) ====================
    BUSINESS_ERROR(4001, "业务逻辑错误", "error.business.error"),
    
    // Leader 管理 (4001-4099)
    LEADER_ALREADY_EXISTS(4001, "该 Leader 地址已存在", "error.leader_already_exists"),
    LEADER_ADDRESS_SAME_AS_ACCOUNT(4002, "Leader 地址不能与自己的账户地址相同", "error.leader_address_same_as_account"),
    LEADER_HAS_COPY_TRADINGS(4003, "该 Leader 还有跟单关系，请先删除跟单关系", "error.leader_has_copy_tradings"),
    
    // 模板管理 (4101-4199)
    TEMPLATE_NAME_ALREADY_EXISTS(4101, "模板名称已存在", "error.template_name_already_exists"),
    TEMPLATE_HAS_COPY_TRADINGS(4102, "该模板还有跟单关系在使用，请先删除跟单关系", "error.template_has_copy_tradings"),
    
    // 跟单管理 (4201-4299)
    COPY_TRADING_ALREADY_EXISTS(4201, "该跟单关系已存在", "error.copy_trading_already_exists"),
    COPY_TRADING_DISABLED(4202, "跟单关系已禁用", "error.copy_trading_disabled"),
    COPY_TRADING_ENABLED(4203, "跟单关系已启用", "error.copy_trading_enabled"),
    NO_ENABLED_COPY_TRADINGS(4204, "没有启用的跟单关系", "error.no_enabled_copy_tradings"),
    
    // 订单相关 (4301-4399)
    ORDER_CREATE_FAILED(4301, "创建订单失败", "error.order_create_failed"),
    ORDER_CANCEL_FAILED(4302, "取消订单失败", "error.order_cancel_failed"),
    ORDER_NOT_MATCHED(4303, "订单未匹配", "error.order_not_matched"),
    ORDER_ALREADY_FILLED(4304, "订单已成交", "error.order_already_filled"),
    ORDER_INSUFFICIENT_BALANCE(4305, "余额不足", "error.order_insufficient_balance"),
    ORDER_AMOUNT_TOO_SMALL(4306, "订单金额低于最小限制", "error.order_amount_too_small"),
    ORDER_AMOUNT_TOO_LARGE(4307, "订单金额超过最大限制", "error.order_amount_too_large"),
    ORDER_PRICE_INVALID(4308, "订单价格无效", "error.order_price_invalid"),
    ORDER_QUANTITY_INVALID(4309, "订单数量无效", "error.order_quantity_invalid"),
    
    // 市场相关 (4401-4499)
    MARKET_PRICE_FETCH_FAILED(4401, "获取市场价格失败", "error.market_price_fetch_failed"),
    MARKET_ORDERBOOK_EMPTY(4402, "订单簿为空", "error.market_orderbook_empty"),
    MARKET_TOKEN_ID_INVALID(4403, "Token ID 无效", "error.market_token_id_invalid"),
    
    // 仓位相关 (4501-4599)
    POSITION_REDEEM_FAILED(4501, "赎回仓位失败", "error.position_redeem_failed"),
    POSITION_NOT_REDEEMABLE(4502, "仓位不可赎回", "error.position_not_redeemable"),
    POSITION_INSUFFICIENT(4503, "仓位不足", "error.position_insufficient"),
    POSITION_ALREADY_REDEEMED(4504, "仓位已赎回", "error.position_already_redeemed"),
    
    // 通知配置相关 (4601-4699)
    NOTIFICATION_CONFIG_NOT_FOUND(4601, "通知配置不存在", "error.notification_config_not_found"),
    NOTIFICATION_CONFIG_ID_EMPTY(4602, "配置ID不能为空", "error.notification_config_id_empty"),
    NOTIFICATION_CONFIG_TYPE_EMPTY(4603, "推送类型不能为空", "error.notification_config_type_empty"),
    NOTIFICATION_CONFIG_NAME_EMPTY(4604, "配置名称不能为空", "error.notification_config_name_empty"),
    NOTIFICATION_CONFIG_DATA_EMPTY(4605, "配置信息不能为空", "error.notification_config_data_empty"),
    NOTIFICATION_CONFIG_BOT_TOKEN_EMPTY(4606, "Bot Token 不能为空", "error.notification_config_bot_token_empty"),
    NOTIFICATION_CONFIG_CREATE_FAILED(4607, "创建配置失败", "error.notification_config_create_failed"),
    NOTIFICATION_CONFIG_UPDATE_FAILED(4608, "更新配置失败", "error.notification_config_update_failed"),
    NOTIFICATION_CONFIG_DELETE_FAILED(4609, "删除配置失败", "error.notification_config_delete_failed"),
    NOTIFICATION_CONFIG_UPDATE_ENABLED_FAILED(4610, "更新启用状态失败", "error.notification_config_update_enabled_failed"),
    NOTIFICATION_CONFIG_FETCH_FAILED(4611, "获取配置失败", "error.notification_config_fetch_failed"),
    NOTIFICATION_TEST_FAILED(4612, "发送测试消息失败，请检查配置", "error.notification_test_failed"),
    NOTIFICATION_GET_CHAT_IDS_FAILED(4613, "获取 Chat IDs 失败", "error.notification_get_chat_ids_failed"),
    
    // 账户相关业务错误 (4601-4699)
    ACCOUNT_ALREADY_EXISTS(4601, "账户已存在", "error.account_already_exists"),
    ACCOUNT_IS_DEFAULT(4702, "账户已是默认账户", "error.account_is_default"),
    ACCOUNT_HAS_ACTIVE_ORDERS(4703, "账户有活跃订单", "error.account_has_active_orders"),
    ACCOUNT_IS_LAST_ONE(4704, "不能删除最后一个账户", "error.account_is_last_one"),
    ACCOUNT_API_KEY_CREATE_FAILED(4705, "自动获取 API Key 失败", "error.account_api_key_create_failed"),
    ACCOUNT_PROXY_ADDRESS_FETCH_FAILED(4706, "获取代理地址失败", "error.account_proxy_address_fetch_failed"),
    ACCOUNT_BALANCE_FETCH_FAILED(4707, "查询账户余额失败", "error.account_balance_fetch_failed"),
    ACCOUNT_POSITIONS_FETCH_FAILED(4708, "查询仓位列表失败", "error.account_positions_fetch_failed"),
    
    // 统计相关 (4801-4899)
    STATISTICS_FETCH_FAILED(4801, "获取统计信息失败", "error.statistics_fetch_failed"),
    ORDER_LIST_FETCH_FAILED(4802, "查询订单列表失败", "error.order_list_fetch_failed"),
    
    // ==================== 服务器内部错误 (5001-5999) ====================
    SERVER_ERROR(5001, "服务器内部错误", "error.server.error"),
    SERVER_DATABASE_ERROR(5002, "数据库错误", "error.server.database_error"),
    SERVER_NETWORK_ERROR(5003, "网络错误", "error.server.network_error"),
    SERVER_TIMEOUT(5004, "请求超时", "error.server.timeout"),
    SERVER_EXTERNAL_API_ERROR(5005, "外部API调用失败", "error.server.external_api_error"),
    SERVER_RPC_ERROR(5006, "RPC调用失败", "error.server.rpc_error"),
    SERVER_WEBSOCKET_ERROR(5007, "WebSocket连接错误", "error.server.websocket_error"),
    SERVER_ENCRYPTION_ERROR(5008, "加密/解密错误", "error.server.encryption_error"),
    SERVER_SIGNATURE_ERROR(5009, "签名错误", "error.server.signature_error"),
    
    // 账户服务错误 (5101-5199)
    SERVER_ACCOUNT_IMPORT_FAILED(5101, "导入账户失败", "error.server.account_import_failed"),
    SERVER_ACCOUNT_UPDATE_FAILED(5102, "更新账户失败", "error.server.account_update_failed"),
    SERVER_ACCOUNT_DELETE_FAILED(5103, "删除账户失败", "error.server.account_delete_failed"),
    SERVER_ACCOUNT_LIST_FETCH_FAILED(5104, "查询账户列表失败", "error.server.account_list_fetch_failed"),
    SERVER_ACCOUNT_DETAIL_FETCH_FAILED(5105, "查询账户详情失败", "error.server.account_detail_fetch_failed"),
    SERVER_ACCOUNT_BALANCE_FETCH_FAILED(5106, "查询账户余额失败", "error.server.account_balance_fetch_failed"),
    SERVER_ACCOUNT_DEFAULT_SET_FAILED(5107, "设置默认账户失败", "error.server.account_default_set_failed"),
    SERVER_ACCOUNT_POSITIONS_FETCH_FAILED(5108, "查询仓位列表失败", "error.server.account_positions_fetch_failed"),
    SERVER_ACCOUNT_ORDER_CREATE_FAILED(5109, "创建卖出订单失败", "error.server.account_order_create_failed"),
    SERVER_ACCOUNT_REDEEM_POSITIONS_FAILED(5110, "赎回仓位失败", "error.server.account_redeem_positions_failed"),
    
    // Leader 服务错误 (5201-5299)
    SERVER_LEADER_ADD_FAILED(5201, "添加 Leader 失败", "error.server.leader_add_failed"),
    SERVER_LEADER_UPDATE_FAILED(5202, "更新 Leader 失败", "error.server.leader_update_failed"),
    SERVER_LEADER_DELETE_FAILED(5203, "删除 Leader 失败", "error.server.leader_delete_failed"),
    SERVER_LEADER_LIST_FETCH_FAILED(5204, "查询 Leader 列表失败", "error.server.leader_list_fetch_failed"),
    SERVER_LEADER_DETAIL_FETCH_FAILED(5205, "查询 Leader 详情失败", "error.server.leader_detail_fetch_failed"),
    
    // 模板服务错误 (5301-5399)
    SERVER_TEMPLATE_CREATE_FAILED(5301, "创建模板失败", "error.server.template_create_failed"),
    SERVER_TEMPLATE_UPDATE_FAILED(5302, "更新模板失败", "error.server.template_update_failed"),
    SERVER_TEMPLATE_DELETE_FAILED(5303, "删除模板失败", "error.server.template_delete_failed"),
    SERVER_TEMPLATE_COPY_FAILED(5304, "复制模板失败", "error.server.template_copy_failed"),
    SERVER_TEMPLATE_LIST_FETCH_FAILED(5305, "查询模板列表失败", "error.server.template_list_fetch_failed"),
    SERVER_TEMPLATE_DETAIL_FETCH_FAILED(5306, "查询模板详情失败", "error.server.template_detail_fetch_failed"),
    
    // 跟单服务错误 (5401-5499)
    SERVER_COPY_TRADING_CREATE_FAILED(5401, "创建跟单失败", "error.server.copy_trading_create_failed"),
    SERVER_COPY_TRADING_UPDATE_FAILED(5402, "更新跟单失败", "error.server.copy_trading_update_failed"),
    SERVER_COPY_TRADING_DELETE_FAILED(5403, "删除跟单失败", "error.server.copy_trading_delete_failed"),
    SERVER_COPY_TRADING_LIST_FETCH_FAILED(5404, "查询跟单列表失败", "error.server.copy_trading_list_fetch_failed"),
    SERVER_COPY_TRADING_TEMPLATES_FETCH_FAILED(5405, "查询钱包绑定的模板失败", "error.server.copy_trading_templates_fetch_failed"),
    
    // 市场服务错误 (5501-5599)
    SERVER_MARKET_PRICE_FETCH_FAILED(5501, "获取市场价格失败", "error.server.market_price_fetch_failed"),
    SERVER_MARKET_LATEST_PRICE_FETCH_FAILED(5502, "获取最新价失败", "error.server.market_latest_price_fetch_failed"),
    
    // 统计服务错误 (5601-5699)
    SERVER_STATISTICS_FETCH_FAILED(5601, "获取统计信息失败", "error.server.statistics_fetch_failed"),
    SERVER_ORDER_TRACKING_LIST_FETCH_FAILED(5602, "查询订单列表失败", "error.server.order_tracking_list_fetch_failed"),
    
    // 区块链服务错误 (5701-5799)
    SERVER_BLOCKCHAIN_RPC_ERROR(5701, "区块链RPC调用失败", "error.server.blockchain_rpc_error"),
    SERVER_BLOCKCHAIN_PROXY_ADDRESS_FETCH_FAILED(5702, "获取代理地址失败", "error.server.blockchain_proxy_address_fetch_failed"),
    SERVER_BLOCKCHAIN_BALANCE_FETCH_FAILED(5703, "查询余额失败", "error.server.blockchain_balance_fetch_failed"),
    SERVER_BLOCKCHAIN_POSITIONS_FETCH_FAILED(5704, "查询仓位失败", "error.server.blockchain_positions_fetch_failed"),
    SERVER_BLOCKCHAIN_REDEEM_FAILED(5705, "赎回仓位交易失败", "error.server.blockchain_redeem_failed"),
    
    // WebSocket 服务错误 (5801-5899)
    SERVER_WEBSOCKET_CONNECTION_FAILED(5801, "WebSocket连接失败", "error.server.websocket_connection_failed"),
    SERVER_WEBSOCKET_MESSAGE_SEND_FAILED(5802, "WebSocket消息发送失败", "error.server.websocket_message_send_failed"),
    SERVER_WEBSOCKET_SUBSCRIBE_FAILED(5803, "WebSocket订阅失败", "error.server.websocket_subscribe_failed"),
    
    // 订单跟踪服务错误 (5901-5999)
    SERVER_ORDER_TRACKING_PROCESS_FAILED(5901, "处理订单跟踪失败", "error.server.order_tracking_process_failed"),
    SERVER_ORDER_TRACKING_BUY_FAILED(5902, "处理买入订单失败", "error.server.order_tracking_buy_failed"),
    SERVER_ORDER_TRACKING_SELL_FAILED(5903, "处理卖出订单失败", "error.server.order_tracking_sell_failed"),
    SERVER_ORDER_TRACKING_MATCH_FAILED(5904, "订单匹配失败", "error.server.order_tracking_match_failed"),
    
    // 回测服务错误 (4601-4699)
    BACKTEST_TASK_NOT_FOUND(4601, "回测任务不存在", "error.backtest.task_not_found"),
    BACKTEST_LEADER_NOT_FOUND(4602, "Leader不存在", "error.backtest.leader_not_found"),
    BACKTEST_DAYS_INVALID(4603, "回测天数超出限制", "error.backtest.days_invalid"),
    BACKTEST_INITIAL_BALANCE_INVALID(4604, "初始金额无效", "error.backtest.initial_balance_invalid"),
    BACKTEST_TASK_RUNNING(4605, "回测任务正在运行，无法删除", "error.backtest.task_running"),
    SERVER_BACKTEST_CREATE_FAILED(5603, "创建回测任务失败", "error.server.backtest_create_failed"),
    SERVER_BACKTEST_UPDATE_FAILED(5604, "更新回测任务失败", "error.server.backtest_update_failed"),
    SERVER_BACKTEST_DELETE_FAILED(5605, "删除回测任务失败", "error.server.backtest_delete_failed"),
    SERVER_BACKTEST_LIST_FETCH_FAILED(5606, "查询回测列表失败", "error.server.backtest_list_fetch_failed"),
    SERVER_BACKTEST_DETAIL_FETCH_FAILED(5607, "查询回测详情失败", "error.server.backtest_detail_fetch_failed"),
    SERVER_BACKTEST_TRADES_FETCH_FAILED(5608, "查询回测交易记录失败", "error.server.backtest_trades_fetch_failed"),
    SERVER_BACKTEST_EXECUTE_FAILED(5609, "回测执行失败", "error.server.backtest_execute_failed"),
    SERVER_BACKTEST_HISTORICAL_DATA_FETCH_FAILED(5610, "历史数据获取失败", "error.server.backtest_historical_data_fetch_failed"),
    SERVER_BACKTEST_STOP_FAILED(5611, "停止回测任务失败", "error.server.backtest_stop_failed");
    
    companion object {
        /**
         * 根据错误码查找枚举
         */
        fun fromCode(code: Int): ErrorCode? {
            return values().find { it.code == code }
        }
        
        /**
         * 根据错误码获取错误消息（已废弃，使用 messageKey + MessageSource）
         */
        @Deprecated("使用 messageKey + MessageSource 获取多语言消息", ReplaceWith("使用 ErrorCode.messageKey"))
        fun getMessage(code: Int): String {
            return fromCode(code)?.message ?: "未知错误"
        }
    }
}

