package com.wrbug.polymarketbot.util

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import org.slf4j.LoggerFactory
import java.io.IOException
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

/**
 * Polymarket API 认证拦截器
 * 实现 L2 认证（使用 API Key、Secret、Passphrase）
 * 
 * 参考 clob-client 实现：
 * - 请求头使用 POLY_* 前缀
 * - Secret 需要 base64 解码后用于 HMAC
 * - 签名结果需要 URL-safe base64 编码（+ -> -, / -> _）
 * 
 * 认证方式：
 * 1. 生成时间戳（秒）
 * 2. 使用 Secret（base64 解码后）对 (timestamp + method + requestPath + body) 进行 HMAC-SHA256 签名
 * 3. 在请求头中添加：
 *    - POLY_ADDRESS: 钱包地址
 *    - POLY_SIGNATURE: URL-safe Base64 编码的签名
 *    - POLY_TIMESTAMP: 时间戳（字符串）
 *    - POLY_API_KEY: API Key
 *    - POLY_PASSPHRASE: Passphrase
 */
class PolymarketAuthInterceptor(
    private val apiKey: String,
    private val apiSecret: String,
    private val apiPassphrase: String,
    private val walletAddress: String
) : Interceptor {
    
    private val logger = LoggerFactory.getLogger(PolymarketAuthInterceptor::class.java)
    
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        
        // 生成时间戳（秒）
        val timestamp = Instant.now().epochSecond
        
        // 构建签名字符串: timestamp + method + requestPath + body
        // requestPath 不包含 query string（根据 clob-client 实现）
        val method = originalRequest.method.uppercase()
        val requestPath = originalRequest.url.encodedPath
        
        // 读取请求体（如果存在）
        // 注意：读取后需要重新创建请求体，否则原始请求体会被消费
        val bodyString = originalRequest.body?.let { requestBody ->
            val buffer = Buffer()
            requestBody.writeTo(buffer)
            buffer.readUtf8()
        }
        
        // 构建签名字符串（与 clob-client 保持一致）
        // clob-client: timestamp + method + requestPath + (body !== undefined ? body : "")
        // 注意：如果 body 是空字符串 ""，也会添加到签名字符串中（虽然结果相同）
        val signString = if (bodyString != null) {
            "$timestamp$method$requestPath$bodyString"
        } else {
            "$timestamp$method$requestPath"
        }
        
        // 使用 HMAC-SHA256 生成签名（Secret 需要 base64 解码）
        val signature = generateSignature(signString, apiSecret)
        
        // 调试日志（仅在 DEBUG 级别输出）
        logger.debug("L2 认证签名生成: method=$method, path=$requestPath, bodyLength=${bodyString?.length ?: 0}, timestamp=$timestamp")
        logger.debug("签名字符串: $signString")
        logger.debug("签名结果: ${signature.take(20)}...")
        
        // 重新创建请求体（如果原始请求有请求体）
        val newRequestBody = originalRequest.body?.let { requestBody ->
            val contentType = requestBody.contentType()
            RequestBody.create(
                contentType,
                bodyString?.toByteArray() ?: ByteArray(0)
            )
        }
        
        // 构建新的请求，添加认证头（使用 POLY_* 前缀）
        // 参考 clob-client/src/http-helpers/index.ts 的 overloadHeaders 函数
        // 添加标准 HTTP 请求头以匹配 clob-client 的行为
        val newRequestBuilder = originalRequest.newBuilder()
            .header("POLY_ADDRESS", walletAddress)
            .header("POLY_SIGNATURE", signature)
            .header("POLY_TIMESTAMP", timestamp.toString())
            .header("POLY_API_KEY", apiKey)
            .header("POLY_PASSPHRASE", apiPassphrase)
            .header("User-Agent", "@polymarket/clob-client")
            .header("Accept", "*/*")
            .header("Connection", "keep-alive")
            .header("Content-Type", "application/json")
            .apply {
                // GET 请求添加 Accept-Encoding: gzip
                if (method == "GET") {
                    header("Accept-Encoding", "gzip")
                }
            }
        
        // 如果有请求体，重新设置请求体
        if (newRequestBody != null) {
            newRequestBuilder.method(originalRequest.method, newRequestBody)
        }
        
        return chain.proceed(newRequestBuilder.build())
    }
    
    /**
     * 使用 HMAC-SHA256 生成签名
     * 参考 clob-client/src/signing/hmac.ts 实现
     * 
     * 注意：Node.js 的 Buffer.from(secret, "base64") 可以处理 URL-safe base64（包含 - 和 _）
     * Java 提供了 Base64.getUrlDecoder() 来直接处理 URL-safe base64
     * 
     * 1. Secret 需要 base64 解码（支持标准 base64 和 URL-safe base64）
     * 2. 使用解码后的 secret 进行 HMAC-SHA256
     * 3. 结果进行 URL-safe base64 编码（+ -> -, / -> _）
     */
    private fun generateSignature(message: String, secret: String): String {
        // Secret 可能是标准 base64 或 URL-safe base64
        // 优先尝试标准 base64，如果失败则使用 URL-safe 解码器
        val decodedSecret = try {
            // 先尝试标准 base64 解码
            Base64.getDecoder().decode(secret)
        } catch (e: Exception) {
            // 如果失败，可能是 URL-safe base64，使用 URL 解码器
            // Base64.getUrlDecoder() 可以直接处理 URL-safe base64（- 和 _）
            try {
                Base64.getUrlDecoder().decode(secret)
            } catch (e2: Exception) {
                // 如果都失败，尝试转换为标准格式后再解码（向后兼容）
                val standardBase64 = secret.replace("-", "+").replace("_", "/")
                try {
                    Base64.getDecoder().decode(standardBase64)
                } catch (e3: Exception) {
                    // 最后尝试直接使用原始字符串（向后兼容）
                    secret.toByteArray()
                }
            }
        }
        
        val mac = Mac.getInstance("HmacSHA256")
        val secretKeySpec = SecretKeySpec(decodedSecret, "HmacSHA256")
        mac.init(secretKeySpec)
        val hash = mac.doFinal(message.toByteArray())
        
        // Base64 编码
        val base64Signature = Base64.getEncoder().encodeToString(hash)
        
        // URL-safe base64 编码：将 + 替换为 -，将 / 替换为 _
        // 注意：保留 = 后缀（根据 clob-client 注释）
        // 使用 replaceAll 替换所有匹配项（与 clob-client 的 replaceAll 函数一致）
        return base64Signature.replace("+", "-").replace("/", "_")
    }
}

