package com.wrbug.polymarketbot.config

import com.google.gson.Gson
import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.util.createClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Retrofit 配置类
 * 用于创建 Polymarket CLOB API 客户端（跟单系统需要）
 * 
 * 注意：
 * - 查询类接口（如 /book, /price, /trades）不需要认证
 * - 操作类接口（如 /orders）需要认证，应使用账户级别的 API Key
 * - 账户 API Key 在调用时动态设置，不在此处配置
 */
@Configuration
class RetrofitConfig(
    private val gson: Gson
) {
    
    @Value("\${polymarket.clob.base-url}")
    private lateinit var clobBaseUrl: String
    
    /**
     * 创建 CLOB API 客户端
     * 用于跟单系统的订单操作和交易查询
     * 
     * 注意：此客户端不包含全局认证拦截器
     * 需要认证的请求应在调用时使用账户级别的 API Key 动态设置认证头
     */
    @Bean
    fun polymarketClobApi(): PolymarketClobApi {
        val okHttpClient = createClient().build()
        
        return Retrofit.Builder()
            .baseUrl(clobBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(PolymarketClobApi::class.java)
    }
}

