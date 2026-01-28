package com.wrbug.polymarketbot.util

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * 日期工具类
 * 用于处理日期字符串和时间戳之间的转换
 */
object DateUtils {
    
    /**
     * ISO 8601 日期时间格式化器
     */
    private val isoFormatter = DateTimeFormatter.ISO_DATE_TIME
    
    /**
     * 使用系统时区的日期时间格式化器（用于显示）
     * 格式：yyyy-MM-dd HH:mm:ss
     */
    private val displayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())
    
    /**
     * 将 ISO 8601 格式的日期字符串转换为时间戳（毫秒）
     * @param dateString ISO 8601 格式的日期字符串，如 "2020-11-04T00:00:00Z"
     * @return 时间戳（毫秒），如果转换失败返回 null
     */
    fun parseToTimestamp(dateString: String?): Long? {
        if (dateString.isNullOrBlank()) {
            return null
        }
        
        return try {
            // 尝试解析 ISO 8601 格式
            val instant = Instant.parse(dateString)
            instant.toEpochMilli()
        } catch (e: DateTimeParseException) {
            // 如果解析失败，尝试其他格式
            try {
                // 尝试使用 ISO_DATE_TIME 格式化器
                val dateTime = java.time.ZonedDateTime.parse(dateString, isoFormatter)
                dateTime.toInstant().toEpochMilli()
            } catch (e2: Exception) {
                // 所有解析都失败，返回 null
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 将时间戳（毫秒）转换为 ISO 8601 格式的日期字符串
     * @param timestamp 时间戳（毫秒）
     * @return ISO 8601 格式的日期字符串
     */
    fun formatFromTimestamp(timestamp: Long?): String? {
        if (timestamp == null) {
            return null
        }
        
        return try {
            val instant = Instant.ofEpochMilli(timestamp)
            instant.toString()
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 将时间戳（毫秒）格式化为可读的日期时间字符串（使用系统时区）
     * @param timestamp 时间戳（毫秒），如果为 null 则使用当前时间
     * @return 格式化的日期时间字符串，格式：yyyy-MM-dd HH:mm:ss
     */
    fun formatDateTime(timestamp: Long? = null): String {
        val instant = if (timestamp != null) {
            Instant.ofEpochMilli(timestamp)
        } else {
            Instant.now()
        }
        return displayFormatter.format(instant)
    }
    
    /**
     * 将时间间隔（毫秒）格式化为可读的字符串
     * 格式：X天X小时X分钟 或 X小时X分钟 或 X分钟
     * 只显示有意义的单位，不显示0值单位
     * 
     * @param milliseconds 时间间隔（毫秒）
     * @return 格式化的时间间隔字符串，如 "2天3小时15分钟"、"5小时30分钟"、"45分钟"
     */
    fun formatDuration(milliseconds: Long): String {
        if (milliseconds < 0) {
            return "0分钟"
        }
        
        val totalSeconds = milliseconds / 1000
        val days = totalSeconds / (24 * 60 * 60)
        val hours = (totalSeconds % (24 * 60 * 60)) / (60 * 60)
        val minutes = (totalSeconds % (60 * 60)) / 60
        
        val parts = mutableListOf<String>()
        
        if (days > 0) {
            parts.add("${days}天")
        }
        if (hours > 0) {
            parts.add("${hours}小时")
        }
        if (minutes > 0 || parts.isEmpty()) {
            parts.add("${minutes}分钟")
        }
        
        return parts.joinToString("")
    }
}

