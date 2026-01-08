package com.wrbug.polymarketbot.util

import com.wrbug.polymarketbot.api.MarketResponse

/**
 * MarketResponse 扩展函数
 * 从 events[0].slug 获取 slug，用于网页跳转
 */
fun MarketResponse?.getEventSlug(): String? {
    return this?.events?.firstOrNull()?.slug ?: this?.slug
}

/**
 * MarketResponse 扩展函数
 * 获取显示用的 slug（使用原来的 slug 字段）
 */
fun MarketResponse?.getDisplaySlug(): String? {
    return this?.slug
}

