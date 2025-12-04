package com.wrbug.polymarketbot.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import java.util.*

/**
 * 语言拦截器
 * 从 HTTP Header 读取语言设置，并设置到 LocaleContextHolder
 * 
 * 支持的 Header：
 * - Accept-Language: 标准 HTTP Header（如 zh-CN, zh-TW, en）
 * - X-Language: 自定义 Header（如 zh-CN, zh-TW, en）
 * 
 * 语言映射规则：
 * - zh-CN, zh -> zh-CN (简体中文)
 * - zh-TW, zh-HK -> zh-TW (繁体中文)
 * - 其他 -> en (英文，默认)
 */
@Component
class LocaleInterceptor : HandlerInterceptor {
    
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean {
        // 优先从 X-Language Header 读取
        val language = request.getHeader("X-Language")
            ?: request.getHeader("Accept-Language")
            ?: "en"
        
        // 解析语言
        val locale = parseLocale(language)
        
        // 设置到 LocaleContextHolder，供 MessageSource 使用
        LocaleContextHolder.setLocale(locale)
        
        return true
    }
    
    /**
     * 解析语言字符串为 Locale
     * 支持格式：zh-CN, zh_TW, zh, en, en-US 等
     */
    private fun parseLocale(language: String): Locale {
        // 移除空格并转为小写
        val lang = language.trim().lowercase()
        
        // 处理 zh-CN, zh_CN 等格式
        if (lang.startsWith("zh")) {
            // 检查是否是繁体中文
            if (lang.contains("tw") || lang.contains("hk") || lang.contains("mo")) {
                return Locale("zh", "TW")
            }
            // 默认简体中文
            return Locale("zh", "CN")
        }
        
        // 英文（默认）
        return Locale("en")
    }
}
