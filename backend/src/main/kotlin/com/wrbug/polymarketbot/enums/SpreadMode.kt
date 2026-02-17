package com.wrbug.polymarketbot.enums

/**
 * 价差模式枚举
 */
enum class SpreadMode(val value: Int, val description: String) {
    /**
     * 不校验价差
     */
    NONE(0, "无"),
    
    /**
     * 固定值：用户输入一个数值
     */
    FIXED(1, "固定"),
    
    /**
     * 自动：系统按历史 K 线计算建议价差
     */
    AUTO(2, "自动");
    
    companion object {
        /**
         * 从数值解析价差模式
         */
        fun fromValue(value: Int?): SpreadMode {
            if (value == null) {
                return NONE  // 默认返回 NONE
            }
            return values().find { it.value == value }
                ?: throw IllegalArgumentException("未知的价差模式: $value")
        }
        
        /**
         * 安全地从数值解析价差模式，解析失败返回默认值
         */
        fun fromValueOrDefault(value: Int?, default: SpreadMode = NONE): SpreadMode {
            if (value == null) {
                return default
            }
            return values().find { it.value == value } ?: default
        }
        
        /**
         * 从字符串解析价差模式（兼容旧逻辑）
         */
        fun fromString(value: String?): SpreadMode {
            if (value.isNullOrBlank()) {
                return NONE
            }
            return values().find { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("未知的价差模式: $value")
        }
    }
}
