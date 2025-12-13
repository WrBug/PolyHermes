package com.wrbug.polymarketbot.dto

import java.time.LocalDate

/**
 * NBA 比赛 DTO
 */
data class NbaGameDto(
    val id: Long?,
    val nbaGameId: String?,
    val homeTeam: String,
    val awayTeam: String,
    val gameDate: LocalDate,
    val gameTime: Long?,
    val gameStatus: String,
    val homeScore: Int,
    val awayScore: Int,
    val period: Int,
    val timeRemaining: String?,
    val polymarketMarketId: String?
)

/**
 * NBA 比赛列表响应
 */
data class NbaGameListResponse(
    val list: List<NbaGameDto>,
    val total: Long
)

/**
 * NBA 比赛列表请求
 * 前端传递时间戳（毫秒），后端转换为西8区时间
 */
data class NbaGameListRequest(
    val startTimestamp: Long? = null,  // 开始时间戳（毫秒）
    val endTimestamp: Long? = null,     // 结束时间戳（毫秒）
    val gameStatus: String? = null
)

