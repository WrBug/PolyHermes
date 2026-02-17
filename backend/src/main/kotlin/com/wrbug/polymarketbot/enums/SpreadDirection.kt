package com.wrbug.polymarketbot.enums

/**
 * 价差方向枚举
 */
enum class SpreadDirection(val value: Int, val description: String) {
    /**
     * 最小价差：价差 >= 配置值时触发，买入价固定 0.99
     */
    MIN(0, "最小价差"),
    
    /**
     * 最大价差：价差 <= 配置值时触发，买入价 = 触发价 + 0.02
     */
    MAX(1, "最大价差");
    
    companion object {
        /**
         * 从数值解析价差方向
         */
        fun fromValue(value: Int?): SpreadDirection {
            if (value == null) {
                return MIN  // 默认返回 MIN
            }
            return values().find { it.value == value }
                ?: throw IllegalArgumentException("未知的价差方向: $value")
        }
        
        /**
         * 安全地从数值解析价差方向，解析失败返回默认值
         */
        fun fromValueOrDefault(value: Int?, default: SpreadDirection = MIN): SpreadDirection {
            if (value == null) {
                return default
            }
            return values().find { it.value == value } ?: default
        }
        
        /**
         * 从字符串解析价差方向（兼容旧逻辑）
         */
        fun fromString(value: String?): SpreadDirection {
            if (value.isNullOrBlank()) {
                return MIN
            }
            return values().find { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("未知的价差方向: $value")
        }
    }
}
