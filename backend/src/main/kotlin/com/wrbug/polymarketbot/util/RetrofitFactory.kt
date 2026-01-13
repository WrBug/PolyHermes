package com.wrbug.polymarketbot.util

import com.google.gson.Gson
import com.wrbug.polymarketbot.api.BuilderRelayerApi
import com.wrbug.polymarketbot.api.EthereumRpcApi
import com.wrbug.polymarketbot.api.GitHubApi
import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.api.PolymarketDataApi
import com.wrbug.polymarketbot.api.PolymarketGammaApi
import com.wrbug.polymarketbot.constants.PolymarketConstants
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import jakarta.annotation.PreDestroy

/**
 * Retrofit 客户端工厂
 * 用于创建带认证的 Polymarket CLOB API 客户端和 Ethereum RPC API 客户端
 * 
 * 注意：为了避免内存泄漏，本类会缓存和复用客户端实例
 */
@Component
class RetrofitFactory(
    private val gson: Gson
) {
    
    private val logger = LoggerFactory.getLogger(RetrofitFactory::class.java)
    
    // 共享的 OkHttpClient（用于不需要认证的 API）
    private val sharedOkHttpClient: OkHttpClient by lazy {
        createClient().build()
    }
    
    // 共享的 OkHttpClient（用于需要跟随重定向的 API）
    private val sharedOkHttpClientWithRedirect: OkHttpClient by lazy {
        createClient()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
    
    // 缓存 Gamma API 客户端（单例）
    private val gammaApi: PolymarketGammaApi by lazy {
        val baseUrl = if (PolymarketConstants.GAMMA_BASE_URL.endsWith("/")) {
            PolymarketConstants.GAMMA_BASE_URL.dropLast(1)
        } else {
            PolymarketConstants.GAMMA_BASE_URL
        }
        
        Retrofit.Builder()
            .baseUrl("$baseUrl/")
            .client(sharedOkHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(PolymarketGammaApi::class.java)
    }
    
    // 缓存 Data API 客户端（单例）
    private val dataApi: PolymarketDataApi by lazy {
        val baseUrl = PolymarketConstants.DATA_API_BASE_URL
        
        Retrofit.Builder()
            .baseUrl("$baseUrl/")
            .client(sharedOkHttpClientWithRedirect)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(PolymarketDataApi::class.java)
    }
    
    // 缓存 GitHub API 客户端（单例）
    private val githubApi: GitHubApi by lazy {
        val baseUrl = "https://api.github.com"
        
        // 添加拦截器，设置 Accept 头以获取 reactions 数据
        val githubInterceptor = object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request().newBuilder()
                    .header("Accept", "application/vnd.github+json")
                    .build()
                return chain.proceed(request)
            }
        }
        
        val okHttpClient = createClient()
            .addInterceptor(githubInterceptor)
            .build()
        
        Retrofit.Builder()
            .baseUrl("$baseUrl/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(GitHubApi::class.java)
    }
    
    // 缓存不带认证的 CLOB API 客户端（单例）
    private val clobApiWithoutAuth: PolymarketClobApi by lazy {
        Retrofit.Builder()
            .baseUrl(PolymarketConstants.CLOB_BASE_URL)
            .client(sharedOkHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(PolymarketClobApi::class.java)
    }
    
    // 缓存带认证的 CLOB API 客户端：walletAddress -> PolymarketClobApi
    // 注意：每个账户使用不同的 API Key，需要不同的客户端
    private val clobApiCache = ConcurrentHashMap<String, PolymarketClobApi>()
    
    // 缓存 RPC API 客户端：rpcUrl -> EthereumRpcApi
    private val rpcApiCache = ConcurrentHashMap<String, EthereumRpcApi>()
    
    // 缓存 Builder Relayer API 客户端：relayerUrl -> BuilderRelayerApi
    private val builderRelayerApiCache = ConcurrentHashMap<String, BuilderRelayerApi>()
    
    /**
     * 创建带认证的 Polymarket CLOB API 客户端
     * 按钱包地址缓存，避免重复创建
     * @param apiKey API Key
     * @param apiSecret API Secret
     * @param apiPassphrase API Passphrase
     * @param walletAddress 钱包地址（用于 POLY_ADDRESS 请求头）
     * @return PolymarketClobApi 客户端
     */
    fun createClobApi(
        apiKey: String,
        apiSecret: String,
        apiPassphrase: String,
        walletAddress: String
    ): PolymarketClobApi {
        // 使用钱包地址作为缓存键（每个账户使用不同的 API Key）
        return clobApiCache.computeIfAbsent(walletAddress) {
            val authInterceptor = PolymarketAuthInterceptor(apiKey, apiSecret, apiPassphrase, walletAddress)
            
            // 添加响应日志拦截器，用于调试 JSON 解析错误
            val responseLoggingInterceptor = ResponseLoggingInterceptor()
            
            val okHttpClient = createClient()
                .addInterceptor(authInterceptor)
                .addInterceptor(responseLoggingInterceptor)
                .build()
            
            Retrofit.Builder()
                .baseUrl(PolymarketConstants.CLOB_BASE_URL)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
                .create(PolymarketClobApi::class.java)
        }
    }
    
    /**
     * 创建不带认证的 Polymarket CLOB API 客户端
     * 用于不需要认证的查询接口
     * @return PolymarketClobApi 客户端（单例）
     */
    fun createClobApiWithoutAuth(): PolymarketClobApi {
        return clobApiWithoutAuth
    }
    
    /**
     * 创建 Ethereum RPC API 客户端
     * 使用固定的 baseUrl，通过拦截器动态替换为实际的 RPC URL
     * 如果 RPC 不可用，将抛出异常
     * 按 RPC URL 缓存，避免重复创建
     * @param rpcUrl RPC 节点 URL
     * @return EthereumRpcApi 客户端
     * @throws IllegalArgumentException 如果 RPC URL 无效或不可用
     */
    fun createEthereumRpcApi(rpcUrl: String): EthereumRpcApi {
        // 确保实际的 RPC URL 以 / 结尾
        val actualRpcUrl = if (rpcUrl.endsWith("/")) {
            rpcUrl
        } else {
            "$rpcUrl/"
        }
        
        // 使用 RPC URL 作为缓存键
        return rpcApiCache.computeIfAbsent(actualRpcUrl) {
            // 验证 RPC 是否可用（仅在新创建时验证）
            validateRpcAvailability(actualRpcUrl)
            
            // 使用固定的 baseUrl（Retrofit 要求 baseUrl 必须以 / 结尾）
            val fixedBaseUrl = "https://polyrpc.polyhermes/"
            
            // 创建 URL 替换拦截器
            val urlReplaceInterceptor = RpcUrlReplaceInterceptor(fixedBaseUrl, actualRpcUrl)
            
            val okHttpClient = createClient()
                .addInterceptor(urlReplaceInterceptor)
                .build()
            
            Retrofit.Builder()
                .baseUrl(fixedBaseUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
                .create(EthereumRpcApi::class.java)
        }
    }
    
    /**
     * 验证 RPC 节点是否可用
     * 通过发送一个简单的 eth_blockNumber 请求来验证
     * @param rpcUrl RPC 节点 URL
     * @throws IllegalArgumentException 如果 RPC 不可用
     */
    private fun validateRpcAvailability(rpcUrl: String) {
        try {
            // 解析 URL
            val httpUrl = rpcUrl.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("无效的 RPC URL: $rpcUrl")
            
            // 创建 JSON-RPC 请求体
            val jsonRpcRequest = """
                {
                    "jsonrpc": "2.0",
                    "method": "eth_blockNumber",
                    "params": [],
                    "id": 1
                }
            """.trimIndent()
            
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = jsonRpcRequest.toRequestBody(mediaType)
            
            // 创建请求
            val request = Request.Builder()
                .url(httpUrl)
                .post(requestBody)
                .header("Content-Type", "application/json")
                .build()
            
            // 创建临时客户端用于验证（使用较短的超时时间）
            val testClient = createClient()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build()
            
            // 发送请求
            val response = testClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                throw IllegalArgumentException("RPC 节点不可用: HTTP ${response.code} ${response.message}")
            }
            
            val responseBody = response.body?.string()
            if (responseBody.isNullOrBlank()) {
                throw IllegalArgumentException("RPC 节点响应为空")
            }
            
            // 检查响应是否包含错误
            if (responseBody.contains("\"error\"")) {
                throw IllegalArgumentException("RPC 节点返回错误: $responseBody")
            }
            
            // 检查响应是否包含 result
            if (!responseBody.contains("\"result\"")) {
                throw IllegalArgumentException("RPC 节点响应格式错误: $responseBody")
            }
            
            logger.debug("RPC 节点验证成功: $rpcUrl")
        } catch (e: IllegalArgumentException) {
            logger.error("RPC 节点验证失败: $rpcUrl - ${e.message}")
            throw e
        } catch (e: Exception) {
            logger.error("RPC 节点验证失败: $rpcUrl - ${e.message}", e)
            throw IllegalArgumentException("RPC 节点不可用: ${e.message}", e)
        }
    }
    
    /**
     * 创建 Polymarket Gamma API 客户端
     * Gamma API 是公开 API，不需要认证
     * @return PolymarketGammaApi 客户端（单例）
     */
    fun createGammaApi(): PolymarketGammaApi {
        return gammaApi
    }
    
    /**
     * 创建 Polymarket Data API 客户端
     * Data API 是公开 API，不需要认证
     * @return PolymarketDataApi 客户端（单例）
     */
    fun createDataApi(): PolymarketDataApi {
        return dataApi
    }
    
    /**
     * 创建 Builder Relayer API 客户端
     * 按 relayerUrl 缓存，避免重复创建
     * @param relayerUrl Builder Relayer URL
     * @param apiKey Builder API Key
     * @param secret Builder Secret
     * @param passphrase Builder Passphrase
     * @return BuilderRelayerApi 客户端
     */
    fun createBuilderRelayerApi(
        relayerUrl: String,
        apiKey: String,
        secret: String,
        passphrase: String
    ): BuilderRelayerApi {
        val baseUrl = if (relayerUrl.endsWith("/")) {
            relayerUrl.dropLast(1)
        } else {
            relayerUrl
        }
        
        // 使用 baseUrl 作为缓存键（注意：如果 API Key 变化，需要清理缓存）
        return builderRelayerApiCache.computeIfAbsent(baseUrl) {
            // 添加 Builder 认证拦截器
            val builderAuthInterceptor = BuilderAuthInterceptor(apiKey, secret, passphrase)
            val okHttpClient = createClient()
                .addInterceptor(builderAuthInterceptor)
                .build()
            
            Retrofit.Builder()
                .baseUrl("$baseUrl/")
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
                .create(BuilderRelayerApi::class.java)
        }
    }
    
    /**
     * 创建 GitHub API 客户端
     * GitHub API 是公开 API，不需要认证（但建议使用 token 提高速率限制）
     * 添加 Accept 头以获取 reactions 数据
     * @return GitHubApi 客户端（单例）
     */
    fun createGitHubApi(): GitHubApi {
        return githubApi
    }
    
    /**
     * 清理缓存（用于测试或配置变更时）
     */
    @PreDestroy
    fun destroy() {
        logger.info("清理 RetrofitFactory 缓存")
        clobApiCache.clear()
        rpcApiCache.clear()
        builderRelayerApiCache.clear()
    }
    
    /**
     * 清理指定钱包地址的 CLOB API 缓存
     * 用于 API Key 变更时
     */
    fun clearClobApiCache(walletAddress: String) {
        clobApiCache.remove(walletAddress)
        logger.debug("已清理 CLOB API 缓存: $walletAddress")
    }
    
    /**
     * 清理指定 RPC URL 的 RPC API 缓存
     * 用于 RPC 节点变更时
     */
    fun clearRpcApiCache(rpcUrl: String) {
        val actualRpcUrl = if (rpcUrl.endsWith("/")) rpcUrl else "$rpcUrl/"
        rpcApiCache.remove(actualRpcUrl)
        logger.debug("已清理 RPC API 缓存: $actualRpcUrl")
    }
}

/**
 * RPC URL 替换拦截器
 * 用于将固定的 baseUrl 替换为实际的 RPC URL
 */
class RpcUrlReplaceInterceptor(
    private val fixedBaseUrl: String,
    private val actualRpcUrl: String
) : Interceptor {
    private val logger = LoggerFactory.getLogger(RpcUrlReplaceInterceptor::class.java)
    
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val originalUrl = originalRequest.url
        
        // 将固定 baseUrl 替换为实际的 RPC URL
        val originalUrlString = originalUrl.toString()
        val newUrlString = originalUrlString.replace(fixedBaseUrl, actualRpcUrl)
        
        // 使用 HttpUrl 解析新 URL，确保格式正确
        val newUrl = newUrlString.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("无效的 RPC URL: $newUrlString")
        
        logger.debug("RPC URL 替换: $originalUrlString -> $newUrlString")
        
        val newRequest = originalRequest.newBuilder()
            .url(newUrl)
            .build()
        
        return chain.proceed(newRequest)
    }
}

/**
 * 响应日志拦截器
 * 用于记录 API 响应的原始内容，帮助调试 JSON 解析错误
 */
class ResponseLoggingInterceptor : Interceptor {
    private val logger = LoggerFactory.getLogger(ResponseLoggingInterceptor::class.java)
    
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        
        // 只在响应不成功或可能有问题时记录响应体
        if (response.isSuccessful) {
            try {
                // 使用 peekBody 读取响应体，避免消费响应流
                // 只读取前 2KB，避免内存问题
                val responseBody = response.peekBody(2048)
                val responseBodyString = responseBody.string()
                
                // 检查响应体是否为空
                val isEmpty = responseBodyString.isBlank()
                
                // 检查是否是有效的 JSON
                val trimmedBody = responseBodyString.trim()
                val isJson = !isEmpty && (
                    trimmedBody.startsWith("{") || 
                    trimmedBody.startsWith("[")
                )
                
                // 如果响应体为空或不是 JSON，记录警告
                if (isEmpty || !isJson) {
                    val bodyPreview = if (isEmpty) {
                        "(空响应体)"
                    } else {
                        trimmedBody.take(500)
                    }
                    logger.warn(
                        "API 响应异常: method=${request.method}, url=${request.url}, " +
                        "code=${response.code}, isJson=$isJson, isEmpty=$isEmpty, " +
                        "responseBody=$bodyPreview"
                    )
                }
            } catch (e: Exception) {
                // 如果读取响应体失败，记录异常但不影响响应
                logger.debug("读取响应体失败: ${e.message}")
            }
        }
        
        return response
    }
}
