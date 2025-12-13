package com.wrbug.polymarketbot.enums

/**
 * Polymarket 体育项目 Tag ID 枚举
 * 用于标识不同体育项目在 Polymarket 中的 tag ID
 */
enum class SportsTagId(val tagId: String, val displayName: String) {
    /**
     * 美国职业篮球联赛
     */
    NBA("745", "NBA"),
    
    /**
     * 美国职业棒球大联盟
     */
    MLB("100381", "MLB"),
    
    /**
     * 美国国家橄榄球联盟
     */
    NFL("450", "NFL"),
    
    /**
     * 美国大学橄榄球
     */
    CFB("100351", "CFB"),
    
    /**
     * 美国国家冰球联盟
     */
    NHL("899", "NHL"),
    
    /**
     * 游戏/电子竞技
     */
    GAMES("100639", "GAMES"),
    
    /**
     * 美国大学篮球
     */
    CBB("101178", "CBB");
    
    companion object {
        /**
         * 根据 tag ID 查找枚举
         */
        fun fromTagId(tagId: String): SportsTagId? {
            return values().find { it.tagId == tagId }
        }
        
        /**
         * 根据显示名称查找枚举
         */
        fun fromDisplayName(displayName: String): SportsTagId? {
            return values().find { it.displayName.equals(displayName, ignoreCase = true) }
        }
        
        /**
         * 获取所有 tag IDs 列表
         */
        fun getAllTagIds(): List<String> {
            return values().map { it.tagId }
        }
    }
}

