package com.wrbug.polymarketbot.enums

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * SpreadDirection 枚举的 JPA 转换器
 * 数据库存储为 TINYINT (0 = MIN, 1 = MAX)
 */
@Converter(autoApply = false)
class SpreadDirectionConverter : AttributeConverter<SpreadDirection, Int> {
    
    override fun convertToDatabaseColumn(attribute: SpreadDirection?): Int {
        return attribute?.value ?: SpreadDirection.MIN.value
    }
    
    override fun convertToEntityAttribute(dbData: Int?): SpreadDirection {
        return SpreadDirection.fromValueOrDefault(dbData)
    }
}
