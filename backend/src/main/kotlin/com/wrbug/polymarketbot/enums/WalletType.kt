package com.wrbug.polymarketbot.enums

/**
 * 钱包类型枚举
 */
enum class WalletType(val value: String, val description: String) {
    /**
     * Magic 钱包（邮箱/OAuth 登录）
     * 使用 PROXY 代理合约，通过 Builder Relayer 执行 Gasless 交易
     */
    MAGIC("magic", "Magic（邮箱/OAuth登录）"),
    
    /**
     * Safe 钱包（MetaMask 等 Web3 钱包）
     * 使用 Gnosis Safe 代理合约，支持 Builder Relayer Gasless 或手动交易
     */
    SAFE("safe", "Safe（Web3钱包）");
    
    companion object {
        /**
         * 从字符串值解析钱包类型（不区分大小写）
         */
        fun fromString(value: String?): WalletType {
            if (value.isNullOrBlank()) {
                return SAFE  // 默认返回 SAFE
            }
            return values().find { it.value.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("未知的钱包类型: $value")
        }
        
        /**
         * 安全地从字符串值解析钱包类型（不区分大小写），解析失败返回默认值
         */
        fun fromStringOrDefault(value: String?, default: WalletType = SAFE): WalletType {
            if (value.isNullOrBlank()) {
                return default
            }
            return values().find { it.value.equals(value, ignoreCase = true) } ?: default
        }
        
        /**
         * 检查字符串是否为有效的钱包类型
         */
        fun isValid(value: String?): Boolean {
            if (value.isNullOrBlank()) {
                return false
            }
            return values().any { it.value.equals(value, ignoreCase = true) }
        }
    }
}
