package com.wrbug.polymarketbot.service

import com.wrbug.polymarketbot.api.EthereumRpcApi
import com.wrbug.polymarketbot.api.JsonRpcRequest
import com.wrbug.polymarketbot.api.JsonRpcResponse
import com.wrbug.polymarketbot.api.PolymarketDataApi
import com.wrbug.polymarketbot.api.PositionResponse
import com.wrbug.polymarketbot.api.ValueResponse
import com.wrbug.polymarketbot.util.EthereumUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.createClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.math.BigDecimal
import java.math.BigInteger

/**
 * 区块链查询服务
 * 用于查询链上余额和持仓信息
 */
@Service
class BlockchainService(
    @Value("\${polymarket.data-api.base-url:https://data-api.polymarket.com}")
    private val dataApiBaseUrl: String,
    @Value("\${ethereum.rpc.url:}")
    private val ethereumRpcUrl: String,
    private val retrofitFactory: RetrofitFactory
) {
    
    private val logger = LoggerFactory.getLogger(BlockchainService::class.java)
    
    // USDC 合约地址（Polygon 主网，Polymarket 使用 Polygon）
    private val usdcContractAddress = "0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174"
    
    // Polymarket 代理工厂合约地址（Polygon 主网）
    // 合约地址: 0xaacFeEa03eb1561C4e67d661e40682Bd20E3541b
    private val proxyFactoryContractAddress = "0xaacFeEa03eb1561C4e67d661e40682Bd20E3541b"
    
    // 获取代理地址的函数签名
    // 根据 Polygonscan 的 F4 方法，函数签名为: computeProxyAddress(address)
    private val computeProxyAddressFunctionSignature = "computeProxyAddress(address)"
    
    private val dataApi: PolymarketDataApi by lazy {
        val baseUrl = if (dataApiBaseUrl.endsWith("/")) {
            dataApiBaseUrl.dropLast(1)
        } else {
            dataApiBaseUrl
        }
        val okHttpClient = createClient()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        Retrofit.Builder()
            .baseUrl("$baseUrl/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PolymarketDataApi::class.java)
    }
    
    private val ethereumRpcApi: EthereumRpcApi? by lazy {
        if (ethereumRpcUrl.isBlank()) {
            null
        } else {
            retrofitFactory.createEthereumRpcApi(ethereumRpcUrl)
        }
    }
    
    /**
     * 获取 Polymarket 代理钱包地址
     * 通过 RPC 调用代理工厂合约获取用户的代理钱包地址
     * @param walletAddress 用户的钱包地址
     * @return 代理钱包地址
     */
    suspend fun getProxyAddress(walletAddress: String): Result<String> {
        return try {
            // 如果未配置 RPC URL，返回错误
            if (ethereumRpcUrl.isBlank()) {
                logger.warn("未配置 Ethereum RPC URL，无法获取代理地址")
                return Result.failure(IllegalStateException("未配置 Ethereum RPC URL，无法获取代理地址。请在配置文件中设置 ethereum.rpc.url 环境变量"))
            }
            
            val rpcApi = ethereumRpcApi ?: throw IllegalStateException("Ethereum RPC URL 未配置")
            
            // 计算函数选择器
            val functionSelector = EthereumUtils.getFunctionSelector(computeProxyAddressFunctionSignature)
            // 编码地址参数
            val encodedAddress = EthereumUtils.encodeAddress(walletAddress)
            // 构建调用数据
            val data = functionSelector + encodedAddress
            
            // 构建 JSON-RPC 请求
            val rpcRequest = JsonRpcRequest(
                method = "eth_call",
                params = listOf(
                    mapOf(
                        "to" to proxyFactoryContractAddress,
                        "data" to data
                    ),
                    "latest"
                )
            )
            
            // 发送 RPC 请求
            val response = rpcApi.call(rpcRequest)
            
            if (!response.isSuccessful || response.body() == null) {
                throw Exception("RPC 请求失败: ${response.code()} ${response.message()}")
            }
            
            val rpcResponse = response.body()!!
            
            // 检查错误
            if (rpcResponse.error != null) {
                throw Exception("RPC 错误: ${rpcResponse.error.message}")
            }
            
            val hexResult = rpcResponse.result ?: throw Exception("RPC 响应格式错误: result 为空")
            
            // 解析代理地址
            val proxyAddress = EthereumUtils.decodeAddress(hexResult)
            
            logger.debug("获取代理地址成功: 原始地址=$walletAddress, 代理地址=$proxyAddress")
            Result.success(proxyAddress)
        } catch (e: Exception) {
            logger.error("获取代理地址失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 查询账户 USDC 余额
     * 通过 Ethereum RPC 查询 ERC-20 代币余额
     * @param walletAddress 钱包地址（用于日志记录）
     * @param proxyAddress 代理地址（必须提供）
     * 如果 RPC 未配置或代理地址为空，返回失败（不返回 mock 数据）
     */
    suspend fun getUsdcBalance(walletAddress: String, proxyAddress: String): Result<String> {
        return try {
            // 如果未配置 RPC URL，返回错误
            if (ethereumRpcUrl.isBlank()) {
                logger.warn("未配置 Ethereum RPC URL，无法查询 USDC 余额")
                return Result.failure(IllegalStateException("未配置 Ethereum RPC URL，无法查询 USDC 余额。请在配置文件中设置 ethereum.rpc.url 环境变量"))
            }
            
            // 检查代理地址是否为空
            if (proxyAddress.isBlank()) {
                logger.error("代理地址为空，无法查询余额")
                return Result.failure(IllegalArgumentException("代理地址不能为空"))
            }
            
            logger.debug("使用代理地址查询余额: $proxyAddress (原始地址: $walletAddress)")
            
            // 使用 RPC 查询 USDC 余额（使用代理地址）
            val balance = queryUsdcBalanceViaRpc(proxyAddress)
            Result.success(balance)
        } catch (e: Exception) {
            logger.error("查询 USDC 余额失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 通过 RPC 查询 USDC 余额
     */
    private suspend fun queryUsdcBalanceViaRpc(walletAddress: String): String {
        val rpcApi = ethereumRpcApi ?: throw IllegalStateException("Ethereum RPC URL 未配置")
        
        // 构建 ERC-20 balanceOf 函数调用
        // function signature: balanceOf(address) -> bytes4(0x70a08231)
        // 参数编码: address (32 bytes, padded)
        val functionSelector = "0x70a08231" // balanceOf(address)
        val paddedAddress = walletAddress.removePrefix("0x").lowercase().padStart(64, '0')
        val data = functionSelector + paddedAddress
        
        // 构建 JSON-RPC 请求
        val rpcRequest = JsonRpcRequest(
            method = "eth_call",
            params = listOf(
                mapOf(
                    "to" to usdcContractAddress,
                    "data" to data
                ),
                "latest"
            )
        )
        
        // 发送 RPC 请求（使用 Retrofit）
        val response = rpcApi.call(rpcRequest)
        
        if (!response.isSuccessful || response.body() == null) {
            throw Exception("RPC 请求失败: ${response.code()} ${response.message()}")
        }
        
        val rpcResponse = response.body()!!
        
        // 检查错误
        if (rpcResponse.error != null) {
            throw Exception("RPC 错误: ${rpcResponse.error.message}")
        }
        
        val hexBalance = rpcResponse.result ?: throw Exception("RPC 响应格式错误: result 为空")
        
        // 将十六进制转换为 BigDecimal（USDC 有 6 位小数）
        val balanceWei = BigInteger(hexBalance.removePrefix("0x"), 16)
        val balance = BigDecimal(balanceWei).divide(BigDecimal("1000000")) // USDC 有 6 位小数
        
        return balance.toPlainString()
    }
    
    /**
     * 查询账户持仓信息
     * 通过 Polymarket Data API 查询
     * 文档: https://docs.polymarket.com/api-reference/core/get-current-positions-for-a-user
     */
    suspend fun getPositions(proxyWalletAddress: String): Result<List<PositionResponse>> {
        return try {
            // 使用代理钱包地址查询仓位
            val response = dataApi.getPositions(
                user = proxyWalletAddress,
                limit = 500,  // 最大限制
                offset = 0
            )
            
            if (response.isSuccessful && response.body() != null) {
                val positions = response.body()!!
                logger.debug("查询到 ${positions.size} 个仓位")
                Result.success(positions)
            } else {
                val errorMsg = "Data API 请求失败: ${response.code()} ${response.message()}"
                logger.error(errorMsg)
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            logger.error("查询持仓信息失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 获取用户仓位总价值
     * 通过 Polymarket Data API 查询
     * 文档: https://docs.polymarket.com/api-reference/core/get-total-value-of-a-users-positions
     */
    suspend fun getTotalValue(proxyWalletAddress: String): Result<String> {
        return try {
            // 使用代理钱包地址查询仓位总价值
            val response = dataApi.getTotalValue(
                user = proxyWalletAddress,
                market = null
            )
            
            if (response.isSuccessful && response.body() != null) {
                val values = response.body()!!
                // 根据文档，返回的是数组，通常只有一个元素
                val totalValue = if (values.isNotEmpty()) {
                    values.first().value
                } else {
                    0.0
                }
                logger.debug("查询到仓位总价值: $totalValue")
                Result.success(totalValue.toString())
            } else {
                val errorMsg = "Data API 请求失败: ${response.code()} ${response.message()}"
                logger.error(errorMsg)
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            logger.error("查询仓位总价值失败: ${e.message}", e)
            Result.failure(e)
        }
    }
}

