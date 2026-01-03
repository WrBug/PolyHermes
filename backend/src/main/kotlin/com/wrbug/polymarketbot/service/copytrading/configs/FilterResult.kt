package com.wrbug.polymarketbot.service.copytrading.configs

import com.wrbug.polymarketbot.api.OrderbookResponse

/**
 * 过滤结果状态枚举
 */
enum class FilterStatus {
    /** 通过 */
    PASSED,
    /** 失败：价格区间 */
    FAILED_PRICE_RANGE,
    /** 失败：订单簿获取失败 */
    FAILED_ORDERBOOK_ERROR,
    /** 失败：订单簿为空 */
    FAILED_ORDERBOOK_EMPTY,
    /** 失败：价差过大 */
    FAILED_SPREAD,
    /** 失败：订单深度不足 */
    FAILED_ORDER_DEPTH,
    /** 失败：超过最大仓位金额 */
    FAILED_MAX_POSITION_VALUE,
    /** 失败：超过最大仓位数量 */
    FAILED_MAX_POSITION_COUNT
}

/**
 * 过滤结果
 */
data class FilterResult(
    /** 过滤状态 */
    val status: FilterStatus,
    /** 失败原因（仅在失败时有效） */
    val reason: String = "",
    /** 订单簿（仅在需要时返回） */
    val orderbook: OrderbookResponse? = null
) {
    /** 是否通过 */
    val isPassed: Boolean
        get() = status == FilterStatus.PASSED

    companion object {
        /** 通过 */
        fun passed(orderbook: OrderbookResponse? = null) = FilterResult(
            status = FilterStatus.PASSED,
            orderbook = orderbook
        )

        /** 价格区间失败 */
        fun priceRangeFailed(reason: String) = FilterResult(
            status = FilterStatus.FAILED_PRICE_RANGE,
            reason = reason
        )

        /** 订单簿获取失败 */
        fun orderbookError(reason: String) = FilterResult(
            status = FilterStatus.FAILED_ORDERBOOK_ERROR,
            reason = reason
        )

        /** 订单簿为空 */
        fun orderbookEmpty() = FilterResult(
            status = FilterStatus.FAILED_ORDERBOOK_EMPTY,
            reason = "订单簿为空"
        )

        /** 价差过大 */
        fun spreadFailed(reason: String, orderbook: OrderbookResponse) = FilterResult(
            status = FilterStatus.FAILED_SPREAD,
            reason = reason,
            orderbook = orderbook
        )

        /** 订单深度不足 */
        fun orderDepthFailed(reason: String, orderbook: OrderbookResponse) = FilterResult(
            status = FilterStatus.FAILED_ORDER_DEPTH,
            reason = reason,
            orderbook = orderbook
        )

        /** 超过最大仓位金额 */
        fun maxPositionValueFailed(reason: String) = FilterResult(
            status = FilterStatus.FAILED_MAX_POSITION_VALUE,
            reason = reason
        )

        /** 超过最大仓位数量 */
        fun maxPositionCountFailed(reason: String) = FilterResult(
            status = FilterStatus.FAILED_MAX_POSITION_COUNT,
            reason = reason
        )
    }
}

