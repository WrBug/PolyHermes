package com.wrbug.polymarketbot.enums

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * SpreadMode 枚举的 JPA 转换器
 * 数据库存储为 TINYINT (0 = NONE, 1 = FIXED, 2 = AUTO)
 */
@Converter(autoApply = false)
class SpreadModeConverter : AttributeConverter<SpreadMode, Int> {
    
    override fun convertToDatabaseColumn(attribute: SpreadMode?): Int {
        return attribute?.value ?: SpreadMode.NONE.value
    }
    
    override fun convertToEntityAttribute(dbData: Int?): SpreadMode {
        return SpreadMode.fromValueOrDefault(dbData)
    }
}
