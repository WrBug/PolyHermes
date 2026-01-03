package com.wrbug.polymarketbot.dto

/**
 * 账户导入请求
 */
data class AccountImportRequest(
    val privateKey: String,  // 私钥（前端加密后传输）
    val walletAddress: String,  // 钱包地址（前端从私钥推导，用于验证）
    val accountName: String? = null,
    val isEnabled: Boolean = true,  // 是否启用（用于订单推送等功能的开关）
    val walletType: String = "magic"  // 钱包类型：magic（邮箱/OAuth登录）或 safe（MetaMask浏览器钱包）
)

/**
 * 账户更新请求
 */
data class AccountUpdateRequest(
    val accountId: Long,
    val accountName: String? = null,
    val isEnabled: Boolean? = null  // 是否启用（用于订单推送等功能的开关）
)

/**
 * 系统配置更新请求
 */
data class SystemConfigUpdateRequest(
    val builderApiKey: String? = null,  // Builder API Key（前端加密后传输）
    val builderSecret: String? = null,  // Builder Secret（前端加密后传输）
    val builderPassphrase: String? = null,  // Builder Passphrase（前端加密后传输）
    val autoRedeem: Boolean? = null  // 自动赎回（系统级别配置）
)

/**
 * 系统配置响应
 */
data class SystemConfigDto(
    val builderApiKeyConfigured: Boolean,  // Builder API Key 是否已配置
    val builderSecretConfigured: Boolean,  // Builder Secret 是否已配置
    val builderPassphraseConfigured: Boolean,  // Builder Passphrase 是否已配置
    val builderApiKeyDisplay: String? = null,  // Builder API Key 显示值（部分显示，用于前端展示）
    val builderSecretDisplay: String? = null,  // Builder Secret 显示值（部分显示，用于前端展示）
    val builderPassphraseDisplay: String? = null,  // Builder Passphrase 显示值（部分显示，用于前端展示）
    val autoRedeemEnabled: Boolean = true  // 自动赎回（系统级别配置，默认开启）
)

/**
 * 账户删除请求
 */
data class AccountDeleteRequest(
    val accountId: Long
)

/**
 * 账户详情请求
 */
data class AccountDetailRequest(
    val accountId: Long? = null  // 账户ID（必需）
)

/**
 * 账户余额请求
 */
data class AccountBalanceRequest(
    val accountId: Long? = null  // 账户ID（必需）
)

/**
 * 账户信息响应
 */
data class AccountDto(
    val id: Long,
    val walletAddress: String,
    val proxyAddress: String,  // Polymarket 代理钱包地址
    val accountName: String?,
    val isEnabled: Boolean,  // 是否启用（用于订单推送等功能的开关）
    val apiKeyConfigured: Boolean,  // API Key 是否已配置（不返回实际 Key）
    val apiSecretConfigured: Boolean,  // API Secret 是否已配置
    val apiPassphraseConfigured: Boolean,  // API Passphrase 是否已配置
    val balance: String? = null,  // 账户余额（可选）
    val totalOrders: Long? = null,  // 总订单数（可选）
    val totalPnl: String? = null,  // 总盈亏（可选）
    val activeOrders: Long? = null,  // 活跃订单数（可选）
    val completedOrders: Long? = null,  // 已完成订单数（可选）
    val positionCount: Long? = null  // 持仓数量（可选）
)

/**
 * 账户列表响应
 */
data class AccountListResponse(
    val list: List<AccountDto>,
    val total: Long
)

/**
 * 账户余额响应
 */
data class AccountBalanceResponse(
    val availableBalance: String,  // 可用余额（RPC 查询的 USDC 余额）
    val positionBalance: String,  // 仓位余额（持仓总价值）
    val totalBalance: String,  // 总余额 = 可用余额 + 仓位余额
    val positions: List<PositionDto> = emptyList()
)

/**
 * 持仓信息
 */
data class PositionDto(
    val marketId: String,
    val side: String,  // YES 或 NO
    val quantity: String,
    val avgPrice: String,
    val currentValue: String,
    val pnl: String? = null
)

/**
 * 账户仓位信息（用于仓位管理页面）
 */
data class AccountPositionDto(
    val accountId: Long,
    val accountName: String?,
    val walletAddress: String,
    val proxyAddress: String,
    val marketId: String,
    val marketTitle: String?,
    val marketSlug: String?,
    val marketIcon: String?,  // 市场图标 URL
    val side: String,  // 结果名称（如 "YES", "NO", "Pakistan" 等）
    val outcomeIndex: Int? = null,  // 结果索引（0, 1, 2...），用于计算 tokenId
    val quantity: String,  // 显示用的数量（可能被截位）
    val originalQuantity: String? = null,  // 原始数量（保留完整精度，用于100%出售）
    val avgPrice: String,
    val currentPrice: String,
    val currentValue: String,
    val initialValue: String,
    val pnl: String,
    val percentPnl: String,
    val realizedPnl: String?,
    val percentRealizedPnl: String?,
    val redeemable: Boolean,
    val mergeable: Boolean,
    val endDate: String?,
    val isCurrent: Boolean = true  // true: 当前仓位（有持仓），false: 历史仓位（已平仓）
)

/**
 * 仓位列表响应
 */
data class PositionListResponse(
    val currentPositions: List<AccountPositionDto>,
    val historyPositions: List<AccountPositionDto>
)

/**
 * 仓位卖出请求
 */
data class PositionSellRequest(
    val accountId: Long,           // 账户ID（必需）
    val marketId: String,          // 市场ID（必需）
    val side: String,              // 结果名称（如 "YES", "NO", "Pakistan" 等）（必需）
    val outcomeIndex: Int? = null, // 结果索引（0, 1, 2...），用于计算 tokenId（推荐提供）
    val orderType: String,         // 订单类型：MARKET（市价）或 LIMIT（限价）（必需）
    val quantity: String? = null,  // 卖出数量（可选，BigDecimal字符串，手动输入时使用）
    val percent: String? = null,   // 卖出百分比（可选，BigDecimal字符串，支持小数，0-100之间，选择百分比按钮时使用）
    val price: String? = null      // 限价价格（限价订单必需，市价订单不需要）
)

/**
 * 仓位卖出响应
 */
data class PositionSellResponse(
    val orderId: String,            // 订单ID
    val marketId: String,          // 市场ID
    val side: String,               // 方向
    val orderType: String,         // 订单类型
    val quantity: String,          // 订单数量
    val price: String?,             // 订单价格（限价订单）
    val status: String,             // 订单状态
    val createdAt: Long             // 创建时间戳
)

/**
 * 市场价格请求
 */
data class MarketPriceRequest(
    val marketId: String,  // 市场ID
    val outcomeIndex: Int? = null  // 结果索引（可选）：0, 1, 2...，用于确定需要查询哪个 outcome 的价格。如果提供了 outcomeIndex，会转换价格（1 - 第一个outcome的价格）
)

/**
 * 获取最新价请求（通过 tokenId）
 */
data class LatestPriceRequest(
    val tokenId: String  // token ID（通过 marketId 和 outcomeIndex 计算得出）
)

/**
 * 市场当前价格响应
 */
data class MarketPriceResponse(
    val marketId: String,
    val currentPrice: String   // 当前价格（通过 MarketPriceService 获取，支持多数据源降级）
)

/**
 * 仓位赎回请求
 */
data class PositionRedeemRequest(
    val positions: List<AccountRedeemPositionItem>  // 要赎回的仓位列表（支持多账户）
)

/**
 * 账户赎回仓位项（包含账户ID）
 */
data class AccountRedeemPositionItem(
    val accountId: Long,           // 账户ID（必需）
    val marketId: String,          // 市场ID（conditionId）
    val outcomeIndex: Int,          // 结果索引（0, 1, 2...）
    val side: String? = null        // 结果名称（可选，用于显示）
)

/**
 * 赎回仓位项
 */
data class RedeemPositionItem(
    val marketId: String,          // 市场ID（conditionId）
    val outcomeIndex: Int,         // 结果索引（0, 1, 2...）
    val side: String? = null      // 结果名称（可选，用于显示）
)

/**
 * 仓位赎回响应
 */
data class PositionRedeemResponse(
    val transactions: List<AccountRedeemTransaction>,  // 每个账户的赎回交易
    val totalRedeemedValue: String,  // 赎回总价值（USDC）
    val createdAt: Long             // 创建时间戳
)

/**
 * 账户赎回交易信息
 */
data class AccountRedeemTransaction(
    val accountId: Long,
    val accountName: String?,
    val transactionHash: String,    // 交易哈希
    val positions: List<RedeemedPositionInfo>  // 赎回的仓位信息
)

/**
 * 赎回的仓位信息
 */
data class RedeemedPositionInfo(
    val marketId: String,
    val side: String,
    val outcomeIndex: Int,
    val quantity: String,          // 赎回数量
    val value: String               // 赎回价值（USDC，1:1）
)

/**
 * 可赎回仓位统计响应
 */
data class RedeemablePositionsSummary(
    val totalCount: Int,            // 可赎回仓位总数
    val totalValue: String,        // 可赎回总价值（USDC）
    val positions: List<RedeemablePositionInfo>  // 可赎回仓位列表
)

/**
 * 可赎回仓位信息
 */
data class RedeemablePositionInfo(
    val accountId: Long,
    val accountName: String?,
    val marketId: String,
    val marketTitle: String?,
    val side: String,
    val outcomeIndex: Int,
    val quantity: String,
    val value: String               // 价值（USDC，1:1）
)

