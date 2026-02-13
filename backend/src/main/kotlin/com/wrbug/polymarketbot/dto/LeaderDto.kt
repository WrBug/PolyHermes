package com.wrbug.polymarketbot.dto

/**
 * Leader 添加请求
 */
data class LeaderAddRequest(
    val leaderAddress: String,
    val leaderName: String? = null,
    val category: String? = null,  // sports 或 crypto
    val remark: String? = null,  // Leader 备注（可选）
    val website: String? = null  // Leader 网站（可选）
)

/**
 * Leader 更新请求
 */
data class LeaderUpdateRequest(
    val leaderId: Long,
    val leaderName: String? = null,
    val category: String? = null,
    val remark: String? = null,  // Leader 备注（可选）
    val website: String? = null  // Leader 网站（可选）
)

/**
 * Leader 删除请求
 */
data class LeaderDeleteRequest(
    val leaderId: Long
)

/**
 * Leader 列表请求
 */
data class LeaderListRequest(
    val category: String? = null  // sports 或 crypto
)

/**
 * Leader 余额请求
 */
data class LeaderBalanceRequest(
    val leaderId: Long  // LeaderID（必需）
)

/**
 * Leader 信息响应
 */
data class LeaderDto(
    val id: Long,
    val leaderAddress: String,
    val leaderName: String?,
    val category: String?,
    val remark: String? = null,  // Leader 备注（可选）
    val website: String? = null,  // Leader 网站（可选）
    val copyTradingCount: Long = 0,  // 跟单关系数量
    val backtestCount: Long = 0,  // 回测数量
    val totalOrders: Long? = null,  // 总订单数（可选）
    val totalPnl: String? = null,  // 总盈亏（可选）
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Leader 列表响应
 */
data class LeaderListResponse(
    val list: List<LeaderDto>,
    val total: Long
)

/**
 * Leader 余额响应
 */
data class LeaderBalanceResponse(
    val leaderId: Long,
    val leaderAddress: String,
    val leaderName: String?,
    val availableBalance: String,  // 可用余额（RPC 查询的 USDC 余额）
    val positionBalance: String,  // 仓位余额（持仓总价值）
    val totalBalance: String,  // 总余额 = 可用余额 + 仓位余额
    val positions: List<PositionDto> = emptyList()
)

