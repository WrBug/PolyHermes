package com.wrbug.polymarketbot.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Gson 配置类
 * 统一配置 Gson 实例，使用 lenient 模式允许解析格式不严格的 JSON
 */
@Configuration
class GsonConfig {
    
    /**
     * 创建 Gson Bean
     * 使用 lenient 模式，允许解析格式不严格的 JSON
     */
    @Bean
    fun gson(): Gson {
        return GsonBuilder()
            .setLenient()
            .create()
    }
}

