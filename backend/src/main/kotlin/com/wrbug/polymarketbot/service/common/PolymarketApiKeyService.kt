package com.wrbug.polymarketbot.service.common

import com.google.gson.Gson
import com.wrbug.polymarketbot.api.ApiKeyResponse
import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.util.PolymarketL1AuthInterceptor
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.createClient
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Polymarket API Key 服务
 * 用于自动创建或获取 API Key
 */
@Service
class PolymarketApiKeyService(
    @Value("\${polymarket.clob.base-url}")
    private val clobBaseUrl: String,
    private val gson: Gson
) {
    
    private val logger = LoggerFactory.getLogger(PolymarketApiKeyService::class.java)
    
    /**
     * API Key 凭证数据类
     */
    data class ApiKeyCreds(
        val apiKey: String,
        val secret: String,
        val passphrase: String
    )
    
    /**
     * 创建或获取 API Key
     * 先尝试获取现有的（derive），如果不存在，则创建新的
     * 与 JavaScript 实现保持一致：先 derive 后 create
     * 
     * @param privateKey 私钥（十六进制字符串）
     * @param walletAddress 钱包地址
     * @param chainId 链 ID（默认 137，Polygon 主网）
     * @return API Key 凭证，如果失败则返回错误
     */
    fun createOrDeriveApiKey(
        privateKey: String,
        walletAddress: String,
        chainId: Long = 137L
    ): Result<ApiKeyCreds> {
        return runBlocking {
            try {
                // 先尝试获取现有的 API Key（derive）
                val deriveResult = deriveApiKey(privateKey, walletAddress, chainId)
                val maskedAddress = "${walletAddress.take(6)}...${walletAddress.takeLast(4)}"
                if (deriveResult.isSuccess) {
                    val creds = deriveResult.getOrNull()
                    if (creds != null && isApiCreds(creds)) {
                        logger.debug("成功获取现有 API Key: $maskedAddress")
                        return@runBlocking Result.success(creds)
                    }
                }

                // 如果获取失败或返回无效，尝试创建新的
                logger.debug("获取现有 API Key 失败，尝试创建新的: $maskedAddress")
                val createResult = createApiKey(privateKey, walletAddress, chainId)
                if (createResult.isSuccess) {
                    val creds = createResult.getOrNull()
                    if (creds != null && isApiCreds(creds)) {
                        logger.debug("成功创建新 API Key: $maskedAddress")
                        return@runBlocking Result.success(creds)
                    }
                }

                // 两个都失败
                val error = createResult.exceptionOrNull() ?: deriveResult.exceptionOrNull()
                val errorMsg = error?.message ?: "未知错误"
                logger.error("获取和创建 API Key 都失败: $maskedAddress", error)
                Result.failure(
                    IllegalStateException("无法获取或创建 API Key: $errorMsg")
                )
            } catch (e: Exception) {
                val maskedAddress = "${walletAddress.take(6)}...${walletAddress.takeLast(4)}"
                logger.error("创建或获取 API Key 异常: $maskedAddress", e)
                Result.failure(e)
            }
        }
    }
    
    /**
     * 判断 API Key 凭证是否有效
     * 与 JavaScript 的 isApiCreds() 函数对应
     */
    private fun isApiCreds(creds: ApiKeyCreds?): Boolean {
        return creds != null && 
               creds.apiKey.isNotBlank() && 
               creds.secret.isNotBlank() && 
               creds.passphrase.isNotBlank()
    }
    
    /**
     * 创建新的 API Key
     */
    private suspend fun createApiKey(
        privateKey: String,
        walletAddress: String,
        chainId: Long
    ): Result<ApiKeyCreds> {
        return try {
            // 获取服务器时间（可选，用于更准确的时间戳）
            val serverTime = try {
                val timeApi = createUnauthenticatedApi()
                val timeResponse = timeApi.getServerTime()
                if (timeResponse.isSuccessful && timeResponse.body() != null) {
                    timeResponse.body()!!.timestamp
                } else {
                    null
                }
            } catch (e: Exception) {
                logger.warn("获取服务器时间失败，使用本地时间", e)
                null
            }
            
            // 创建带 L1 认证的 API 客户端
            val api = createL1AuthenticatedApi(privateKey, walletAddress, chainId, serverTime)
            
            // 调用创建 API Key 接口
            val response = api.createApiKey()
            
            if (response.isSuccessful && response.body() != null) {
                val apiKeyResponse = response.body()!!
                Result.success(
                    ApiKeyCreds(
                        apiKey = apiKeyResponse.apiKey,
                        secret = apiKeyResponse.secret,
                        passphrase = apiKeyResponse.passphrase
                    )
                )
            } else {
                val errorBody = response.errorBody()?.string() ?: "未知错误"
                logger.warn("创建 API Key 失败: ${response.code()} $errorBody")
                Result.failure(
                    IllegalStateException("创建 API Key 失败: ${response.code()} $errorBody")
                )
            }
        } catch (e: Exception) {
            logger.error("创建 API Key 异常", e)
            Result.failure(e)
        }
    }
    
    /**
     * 获取现有的 API Key
     */
    private suspend fun deriveApiKey(
        privateKey: String,
        walletAddress: String,
        chainId: Long
    ): Result<ApiKeyCreds> {
        return try {
            // 获取服务器时间（可选）
            val serverTime = try {
                val timeApi = createUnauthenticatedApi()
                val timeResponse = timeApi.getServerTime()
                if (timeResponse.isSuccessful && timeResponse.body() != null) {
                    timeResponse.body()!!.timestamp
                } else {
                    null
                }
            } catch (e: Exception) {
                logger.warn("获取服务器时间失败，使用本地时间", e)
                null
            }
            
            // 创建带 L1 认证的 API 客户端
            val api = createL1AuthenticatedApi(privateKey, walletAddress, chainId, serverTime)
            
            // 调用获取 API Key 接口
            val response = api.deriveApiKey()
            
            if (response.isSuccessful && response.body() != null) {
                val apiKeyResponse = response.body()!!
                Result.success(
                    ApiKeyCreds(
                        apiKey = apiKeyResponse.apiKey,
                        secret = apiKeyResponse.secret,
                        passphrase = apiKeyResponse.passphrase
                    )
                )
            } else {
                val errorBody = response.errorBody()?.string() ?: "未知错误"
                logger.warn("获取 API Key 失败: ${response.code()} $errorBody")
                Result.failure(
                    IllegalStateException("获取 API Key 失败: ${response.code()} $errorBody")
                )
            }
        } catch (e: Exception) {
            logger.error("获取 API Key 异常", e)
            Result.failure(e)
        }
    }
    
    /**
     * 创建带 L1 认证的 API 客户端
     */
    private fun createL1AuthenticatedApi(
        privateKey: String,
        walletAddress: String,
        chainId: Long,
        serverTime: Long? = null
    ): PolymarketClobApi {
        val authInterceptor = PolymarketL1AuthInterceptor(
            privateKey = privateKey,
            walletAddress = walletAddress,
            chainId = chainId,
            useServerTime = serverTime != null,
            serverTime = serverTime
        )
        
        val okHttpClient = createClient()
            .addInterceptor(authInterceptor)
            .build()
        
        return Retrofit.Builder()
            .baseUrl(clobBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(PolymarketClobApi::class.java)
    }
    
    /**
     * 创建未认证的 API 客户端（用于获取服务器时间等公开接口）
     */
    private fun createUnauthenticatedApi(): PolymarketClobApi {
        val okHttpClient = createClient().build()
        
        return Retrofit.Builder()
            .baseUrl(clobBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(PolymarketClobApi::class.java)
    }
}

