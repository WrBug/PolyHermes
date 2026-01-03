package com.wrbug.polymarketbot.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Web MVC 配置
 * 注册JWT认证拦截器和语言拦截器
 */
@Configuration
class WebMvcConfig(
    private val jwtAuthenticationInterceptor: JwtAuthenticationInterceptor,
    private val localeInterceptor: LocaleInterceptor
) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        // 先注册语言拦截器（优先级更高）
        registry.addInterceptor(localeInterceptor)
            .addPathPatterns("/api/**")
        // 再注册JWT认证拦截器
        registry.addInterceptor(jwtAuthenticationInterceptor)
            .addPathPatterns("/api/**")
    }
}

