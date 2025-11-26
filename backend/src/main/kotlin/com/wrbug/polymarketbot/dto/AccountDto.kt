package com.wrbug.polymarketbot.dto

/**
 * 账户导入请求
 */
data class AccountImportRequest(
    val privateKey: String,  // 私钥（前端加密后传输）
    val walletAddress: String,  // 钱包地址（前端从私钥推导，用于验证）
    val accountName: String? = null,
    val isDefault: Boolean = false
)

/**
 * 账户更新请求
 */
data class AccountUpdateRequest(
    val accountId: Long,
    val accountName: String? = null,
    val isDefault: Boolean? = null
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
    val accountId: Long? = null  // 不提供则返回默认账户
)

/**
 * 账户余额请求
 */
data class AccountBalanceRequest(
    val accountId: Long? = null  // 不提供则查询默认账户
)

/**
 * 设置默认账户请求
 */
data class SetDefaultAccountRequest(
    val accountId: Long
)

/**
 * 账户信息响应
 */
data class AccountDto(
    val id: Long,
    val walletAddress: String,
    val accountName: String?,
    val isDefault: Boolean,
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

