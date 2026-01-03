package com.wrbug.polymarketbot.api

import com.google.gson.JsonElement
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Ethereum RPC API 接口定义
 * 用于调用 Ethereum JSON-RPC 接口
 */
interface EthereumRpcApi {
    
    /**
     * 调用 Ethereum JSON-RPC 方法
     */
    @POST("/")
    suspend fun call(@Body request: JsonRpcRequest): Response<JsonRpcResponse>
}

/**
 * JSON-RPC 请求
 */
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: List<Any>,
    val id: Int = 1
)

/**
 * JSON-RPC 响应
 * 使用 JsonElement 类型处理 result 字段，可以灵活处理字符串、对象、数组等类型
 */
data class JsonRpcResponse(
    val jsonrpc: String? = null,
    val result: JsonElement? = null,  // 使用 JsonElement 类型，可以处理任意 JSON 类型
    val error: JsonRpcError? = null,
    val id: Int? = null
)

/**
 * JSON-RPC 错误
 */
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: Any? = null
)

