package com.wrbug.polymarketbot.api

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * Builder Relayer API 接口
 * 用于执行 Gasless 交易
 * 参考: https://docs.polymarket.com/developers/builders/relayer-client
 * 参考: builder-relayer-client/src/endpoints.ts
 */
interface BuilderRelayerApi {
    
    /**
     * 提交 Safe 交易（Gasless）
     * POST /submit
     * 
     * 参考: builder-relayer-client/src/client.ts 的 execute 方法
     * 需要 Builder 认证头（通过拦截器添加）：
     * - POLY_BUILDER_SIGNATURE: HMAC 签名
     * - POLY_BUILDER_TIMESTAMP: 时间戳
     * - POLY_BUILDER_API_KEY: API Key
     * - POLY_BUILDER_PASSPHRASE: Passphrase
     */
    @POST("/submit")
    suspend fun submitTransaction(
        @Body request: TransactionRequest
    ): Response<RelayerTransactionResponse>
    
    /**
     * 获取 nonce
     * GET /nonce?address={address}&type={type}
     */
    @GET("/nonce")
    suspend fun getNonce(
        @Query("address") address: String,
        @Query("type") type: String
    ): Response<NoncePayload>

    /**
     * 获取 Relay Payload（PROXY 类型执行时使用）
     * GET /relay-payload?address={address}&type=PROXY
     * 参考: builder-relayer-client endpoints GET_RELAY_PAYLOAD
     */
    @GET("/relay-payload")
    suspend fun getRelayPayload(
        @Query("address") address: String,
        @Query("type") type: String
    ): Response<RelayPayload>
    
    /**
     * 获取交易状态
     * GET /transaction?id={transactionId}
     */
    @GET("/transaction")
    suspend fun getTransaction(
        @Query("id") transactionId: String
    ): Response<List<RelayerTransaction>>
    
    /**
     * 检查 Safe 是否已部署
     * GET /deployed?address={address}
     */
    @GET("/deployed")
    suspend fun getDeployed(
        @Query("address") address: String
    ): Response<GetDeployedResponse>
    
    /**
     * 交易请求
     * 参考: builder-relayer-client/src/types.ts 的 TransactionRequest
     */
    data class TransactionRequest(
        @SerializedName("type")
        val type: String,  // "SAFE" 或 "SAFE-CREATE"
        
        @SerializedName("from")
        val from: String,  // 用户地址（EOA）
        
        @SerializedName("to")
        val to: String,  // 目标合约地址
        
        @SerializedName("proxyWallet")
        val proxyWallet: String,  // Safe 地址（proxyAddress）
        
        @SerializedName("data")
        val data: String,  // 调用数据（十六进制字符串，带 0x 前缀）
        
        @SerializedName("nonce")
        val nonce: String,  // Safe nonce（字符串）
        
        @SerializedName("signature")
        val signature: String,  // Safe 签名（packed signature，十六进制字符串，带 0x 前缀）
        
        @SerializedName("signatureParams")
        val signatureParams: SignatureParams,  // 签名参数
        
        @SerializedName("metadata")
        val metadata: String? = null  // 元数据（可选，最多 500 字符）
    )
    
    /**
     * 签名参数
     * 参考: builder-relayer-client/src/types.ts 的 SignatureParams
     * Safe 使用 operation/safeTxnGas/baseGas 等，PROXY 使用 relayHub/relay/relayerFee 等
     */
    data class SignatureParams(
        @SerializedName("gasPrice")
        val gasPrice: String? = null,
        
        @SerializedName("operation")
        val operation: String? = null,  // "0" 或 "1"
        
        @SerializedName("safeTxnGas")
        val safeTxnGas: String? = null,
        
        @SerializedName("baseGas")
        val baseGas: String? = null,
        
        @SerializedName("gasToken")
        val gasToken: String? = null,
        
        @SerializedName("refundReceiver")
        val refundReceiver: String? = null,
        
        @SerializedName("relayerFee")
        val relayerFee: String? = null,
        
        @SerializedName("gasLimit")
        val gasLimit: String? = null,
        
        @SerializedName("relayHub")
        val relayHub: String? = null,
        
        @SerializedName("relay")
        val relay: String? = null
    )
    
    /**
     * Relayer 交易响应
     * 参考: builder-relayer-client/src/types.ts 的 RelayerTransactionResponse
     */
    data class RelayerTransactionResponse(
        @SerializedName("transactionID")
        val transactionID: String,
        
        @SerializedName("state")
        val state: String,  // STATE_NEW, STATE_EXECUTED, STATE_MINED, STATE_CONFIRMED, STATE_FAILED, STATE_INVALID
        
        @SerializedName("transactionHash")
        val transactionHash: String?,
        
        @SerializedName("hash")
        val hash: String?  // 同 transactionHash
    )
    
    /**
     * Nonce 响应
     */
    data class NoncePayload(
        @SerializedName("nonce")
        val nonce: String
    )

    /**
     * Relay Payload（PROXY 执行时获取 relay 地址与 nonce）
     * 参考: builder-relayer-client types RelayPayload
     */
    data class RelayPayload(
        @SerializedName("address")
        val address: String,
        @SerializedName("nonce")
        val nonce: String
    )
    
    /**
     * Relayer 交易详情
     */
    data class RelayerTransaction(
        @SerializedName("transactionID")
        val transactionID: String,
        
        @SerializedName("transactionHash")
        val transactionHash: String,
        
        @SerializedName("from")
        val from: String,
        
        @SerializedName("to")
        val to: String,
        
        @SerializedName("proxyAddress")
        val proxyAddress: String,
        
        @SerializedName("data")
        val data: String,
        
        @SerializedName("nonce")
        val nonce: String,
        
        @SerializedName("value")
        val value: String,
        
        @SerializedName("state")
        val state: String,
        
        @SerializedName("type")
        val type: String,
        
        @SerializedName("metadata")
        val metadata: String,
        
        @SerializedName("createdAt")
        val createdAt: String,
        
        @SerializedName("updatedAt")
        val updatedAt: String
    )
    
    /**
     * 部署状态响应
     */
    data class GetDeployedResponse(
        @SerializedName("deployed")
        val deployed: Boolean
    )
}

