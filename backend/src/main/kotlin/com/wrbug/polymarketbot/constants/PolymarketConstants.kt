package com.wrbug.polymarketbot.constants

/**
 * Polymarket API 常量
 * 集中管理所有 Polymarket API 的 URL 配置
 */
object PolymarketConstants {
    
    /**
     * Polymarket CLOB API 基础 URL
     */
    const val CLOB_BASE_URL = "https://clob.polymarket.com"
    
    /**
     * Polymarket RTDS WebSocket URL
     * 用于订单推送服务
     */
    const val RTDS_WS_URL = "wss://ws-subscriptions-clob.polymarket.com"
    
    /**
     * Polymarket User Channel WebSocket URL
     * 用于跟单服务（订阅 Leader 交易）
     */
    const val USER_WS_URL = "wss://ws-live-data.polymarket.com"
    
    /**
     * Polymarket Activity WebSocket URL
     * 用于 Activity 全局交易流监听
     */
    const val ACTIVITY_WS_URL = "wss://ws-live-data.polymarket.com"
    
    /**
     * Polymarket Data API 基础 URL
     */
    const val DATA_API_BASE_URL = "https://data-api.polymarket.com"
    
    /**
     * Polymarket Gamma API 基础 URL
     */
    const val GAMMA_BASE_URL = "https://gamma-api.polymarket.com"
    
    /**
     * Builder Relayer API URL
     * 用于 Gasless 交易
     */
    const val BUILDER_RELAYER_URL = "https://relayer-v2.polymarket.com/"

    /**
     * Polymarket Safe 代理工厂合约地址（Polygon 主网）
     * 用于 Safe 类型账户的代理部署（SAFE-CREATE）
     */
    const val SAFE_PROXY_FACTORY_ADDRESS = "0xaacFeEa03eb1561C4e67d661e40682Bd20E3541b"

    /** SafeCreate 用 EIP-712 domain name，与 builder-relayer-client 一致 */
    const val SAFE_FACTORY_EIP712_NAME = "Polymarket Contract Proxy Factory"
}

