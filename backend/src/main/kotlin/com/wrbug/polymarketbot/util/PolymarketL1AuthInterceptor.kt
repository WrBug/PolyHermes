package com.wrbug.polymarketbot.util

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.slf4j.LoggerFactory
import java.io.IOException

/**
 * Polymarket API L1 认证拦截器
 * 用于创建/获取 API Key 时的认证
 * 
 * 参考 clob-client/src/headers/index.ts 的 createL1Headers 实现
 * 
 * 认证方式：
 * 1. 使用 EIP-712 签名对请求进行签名
 * 2. 在请求头中添加：
 *    - POLY_ADDRESS: 钱包地址
 *    - POLY_SIGNATURE: EIP-712 签名字符串
 *    - POLY_TIMESTAMP: 时间戳（秒，字符串）
 *    - POLY_NONCE: 随机数（字符串，默认 0）
 */
class PolymarketL1AuthInterceptor(
    private val privateKey: String,
    private val walletAddress: String,
    private val chainId: Long = 137L,  // Polygon 主网
    private val nonce: Long = 0L,
    private val useServerTime: Boolean = false,
    private val serverTime: Long? = null
) : Interceptor {
    
    private val logger = LoggerFactory.getLogger(PolymarketL1AuthInterceptor::class.java)
    
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        
        // 获取时间戳（优先使用服务器时间，否则使用当前时间）
        val timestamp = if (useServerTime && serverTime != null) {
            serverTime
        } else {
            System.currentTimeMillis() / 1000
        }
        
        // 生成 EIP-712 签名
        val signature = try {
            Eip712Signer.buildClobEip712Signature(
                privateKey = privateKey,
                chainId = chainId,
                timestamp = timestamp,
                nonce = nonce
            )
        } catch (e: Exception) {
            logger.error("生成 EIP-712 签名失败", e)
            throw IOException("生成签名失败: ${e.message}", e)
        }
        
        // 构建新的请求，添加 L1 认证头
        val newRequest = originalRequest.newBuilder()
            .header("POLY_ADDRESS", walletAddress)
            .header("POLY_SIGNATURE", signature)
            .header("POLY_TIMESTAMP", timestamp.toString())
            .header("POLY_NONCE", nonce.toString())
            .build()
        
        return chain.proceed(newRequest)
    }
}

