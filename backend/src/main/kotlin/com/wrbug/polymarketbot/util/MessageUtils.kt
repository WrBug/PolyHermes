package com.wrbug.polymarketbot.util

import com.wrbug.polymarketbot.enums.ErrorCode
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.stereotype.Component

/**
 * 消息工具类
 * 用于获取国际化消息
 */
@Component
class MessageUtils(
    private val messageSource: MessageSource
) {
    /**
     * 根据 ErrorCode 获取国际化错误消息
     */
    fun getMessage(errorCode: ErrorCode): String {
        return try {
            messageSource.getMessage(
                errorCode.messageKey,
                null,
                errorCode.message, // 默认消息（fallback）
                LocaleContextHolder.getLocale()
            ) ?: errorCode.message
        } catch (e: Exception) {
            // 如果获取失败，使用默认消息
            errorCode.message
        }
    }
    
    /**
     * 根据消息键获取国际化消息
     * @param key 消息键
     * @param defaultMessage 默认消息（如果找不到消息键）
     * @param args 消息参数（用于占位符替换）
     */
    fun getMessage(key: String, defaultMessage: String = key, vararg args: Any?): String {
        return try {
            messageSource.getMessage(
                key,
                args,
                defaultMessage,
                LocaleContextHolder.getLocale()
            ) ?: defaultMessage
        } catch (e: Exception) {
            defaultMessage
        }
    }
}