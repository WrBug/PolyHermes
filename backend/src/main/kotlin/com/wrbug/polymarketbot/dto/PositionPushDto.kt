package com.wrbug.polymarketbot.dto

/**
 * 仓位推送消息类型
 */
enum class PositionPushMessageType {
    FULL,      // 全量推送
    INCREMENTAL // 增量推送
}

/**
 * 仓位推送消息
 */
data class PositionPushMessage(
    val type: PositionPushMessageType,  // 消息类型：FULL（全量）或 INCREMENTAL（增量）
    val timestamp: Long,                 // 消息时间戳
    val currentPositions: List<AccountPositionDto> = emptyList(),  // 当前仓位列表（全量或增量）
    val historyPositions: List<AccountPositionDto> = emptyList(),  // 历史仓位列表（全量或增量）
    val removedPositionKeys: List<String> = emptyList()  // 已删除的仓位键（仅增量推送时使用）
)

/**
 * 仓位键（用于唯一标识一个仓位）
 * 格式：accountId-marketId-side
 */
fun AccountPositionDto.getPositionKey(): String {
    return "${accountId}-${marketId}-${side}"
}

