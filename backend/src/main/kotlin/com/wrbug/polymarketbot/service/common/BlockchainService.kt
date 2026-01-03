package com.wrbug.polymarketbot.service.common

import com.google.gson.Gson
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
import com.wrbug.polymarketbot.service.system.RelayClientService
import com.wrbug.polymarketbot.service.system.RpcNodeService
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
    private val retrofitFactory: RetrofitFactory,
    private val relayClientService: RelayClientService,
    private val rpcNodeService: RpcNodeService,
    private val gson: Gson
) {
    
    private val logger = LoggerFactory.getLogger(BlockchainService::class.java)
    
    // USDC 合约地址（Polygon 主网，Polymarket 使用 Polygon）
    private val usdcContractAddress = "0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174"
    
    // Polymarket Safe 代理工厂合约地址（Polygon 主网，用于 MetaMask 用户）
    // 合约地址: 0xaacFeEa03eb1561C4e67d661e40682Bd20E3541b
    private val safeProxyFactoryAddress = "0xaacFeEa03eb1561C4e67d661e40682Bd20E3541b"

    // Polymarket Magic 代理工厂合约地址（Polygon 主网，用于邮箱/OAuth 登录用户）
    // 合约地址: 0xaB45c5A4B0c941a2F231C04C3f49182e1A254052
    private val magicProxyFactoryAddress = "0xaB45c5A4B0c941a2F231C04C3f49182e1A254052"

    // Magic Proxy 的 init code hash（用于 CREATE2 计算）
    private val magicProxyInitCodeHash = "0xd21df8dc65880a8606f09fe0ce3df9b8869287ab0b058be05aa9e8af6330a00b"
    
    // ConditionalTokens 合约地址（Polygon 主网）
    private val conditionalTokensAddress = "0x4D97DCd97eC945f40cF65F87097ACe5EA0476045"
    
    // 空集合ID（用于计算collectionId）
    private val EMPTY_SET = "0x0000000000000000000000000000000000000000000000000000000000000000"
    
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
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(PolymarketDataApi::class.java)
    }
    
    private val polygonRpcApi: EthereumRpcApi by lazy {
        val rpcUrl = rpcNodeService.getHttpUrl()
        retrofitFactory.createEthereumRpcApi(rpcUrl)
    }
    
    /**
     * 获取 Polymarket 代理钱包地址
     * 根据指定的钱包类型返回对应的代理地址
     *
     * Polymarket 有两种代理钱包类型：
     * 1. Magic Proxy（邮箱/OAuth 登录用户）- 使用 CREATE2 计算地址
     * 2. Safe Proxy（MetaMask 钱包用户）- 通过合约调用获取地址
     *
     * @param walletAddress 用户的钱包地址（EOA）
     * @param walletType 钱包类型："magic"（默认）或 "safe"
     * @return 代理钱包地址
     */
    suspend fun getProxyAddress(walletAddress: String, walletType: String = "magic"): Result<String> {
        return try {
            when (walletType.lowercase()) {
                "safe" -> {
                    // Safe Proxy（MetaMask 用户）
                    val safeProxyResult = getSafeProxyAddress(walletAddress)
                    if (safeProxyResult.isSuccess) {
                        val safeProxyAddress = safeProxyResult.getOrNull()!!
                        logger.debug("使用 Safe Proxy 地址: $safeProxyAddress")
                        Result.success(safeProxyAddress)
                    } else {
                        Result.failure(safeProxyResult.exceptionOrNull() ?: Exception("获取 Safe Proxy 地址失败"))
                    }
                }
                else -> {
                    // Magic Proxy（邮箱/OAuth 登录用户）- 默认
                    val magicProxyAddress = calculateMagicProxyAddress(walletAddress)
                    logger.debug("使用 Magic Proxy 地址: $magicProxyAddress")
                    Result.success(magicProxyAddress)
                }
            }
        } catch (e: Exception) {
            logger.error("获取代理地址失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 计算 Magic Proxy 地址（使用 CREATE2）
     * 用于邮箱/OAuth 登录的用户
     *
     * CREATE2 地址计算公式：
     * address = keccak256(0xff ++ factory ++ salt ++ initCodeHash)[12:]
     * salt = keccak256(eoaAddress)
     *
     * @param walletAddress 用户的钱包地址（EOA）
     * @return Magic 代理钱包地址
     */
    fun calculateMagicProxyAddress(walletAddress: String): String {
        // 计算 salt = keccak256(eoaAddress)
        val eoaBytes = EthereumUtils.hexToBytes(walletAddress.lowercase())
        val salt = EthereumUtils.keccak256(eoaBytes)

        // 计算 CREATE2 地址
        // data = 0xff ++ factory ++ salt ++ initCodeHash
        val prefix = byteArrayOf(0xff.toByte())
        val factoryBytes = EthereumUtils.hexToBytes(magicProxyFactoryAddress)
        val initCodeHashBytes = EthereumUtils.hexToBytes(magicProxyInitCodeHash)

        val data = prefix + factoryBytes + salt + initCodeHashBytes
        val hash = EthereumUtils.keccak256(data)

        // 取后 20 字节作为地址
        return "0x" + hash.copyOfRange(12, 32).joinToString("") { "%02x".format(it) }
    }

    /**
     * 获取 Safe Proxy 地址
     * 通过 RPC 调用 Safe 代理工厂合约获取用户的代理钱包地址
     * 用于 MetaMask 钱包用户
     *
     * @param walletAddress 用户的钱包地址
     * @return 代理钱包地址
     */
    private suspend fun getSafeProxyAddress(walletAddress: String): Result<String> {
        return try {
            val rpcApi = polygonRpcApi

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
                        "to" to safeProxyFactoryAddress,
                        "data" to data
                    ),
                    "latest"
                )
            )

            // 发送 RPC 请求
            val response = rpcApi.call(rpcRequest)

            if (!response.isSuccessful || response.body() == null) {
                return Result.failure(Exception("RPC 请求失败: ${response.code()} ${response.message()}"))
            }

            val rpcResponse = response.body()!!

            // 检查错误
            if (rpcResponse.error != null) {
                return Result.failure(Exception("RPC 错误: ${rpcResponse.error.message}"))
            }

            // 使用 Gson 解析 result（JsonElement）
            val hexResult = rpcResponse.result?.asString
                ?: return Result.failure(Exception("RPC 响应格式错误: result 为空"))

            // 解析代理地址
            val proxyAddress = EthereumUtils.decodeAddress(hexResult)

            Result.success(proxyAddress)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 检查地址是否是合约
     * @param address 地址
     * @return 如果地址有代码（是合约）返回 true
     */
    private suspend fun isContract(address: String): Boolean {
        return try {
            val rpcApi = polygonRpcApi

            val rpcRequest = JsonRpcRequest(
                method = "eth_getCode",
                params = listOf(address, "latest")
            )

            val response = rpcApi.call(rpcRequest)
            if (!response.isSuccessful || response.body() == null) {
                return false
            }

            val rpcResponse = response.body()!!
            if (rpcResponse.error != null) {
                return false
            }

            val code = rpcResponse.result?.asString ?: "0x"
            // 如果代码不是 "0x" 或 "0x0"，则是合约
            code != "0x" && code != "0x0"
        } catch (e: Exception) {
            logger.warn("检查合约地址失败: ${e.message}")
            false
        }
    }
    
    /**
     * 查询账户 USDC 余额
     * 通过 Polygon RPC 查询 ERC-20 代币余额
     * @param walletAddress 钱包地址（用于日志记录）
     * @param proxyAddress 代理地址（必须提供）
     * 如果 RPC 未配置或代理地址为空，返回失败（不返回 mock 数据）
     */
    suspend fun getUsdcBalance(walletAddress: String, proxyAddress: String): Result<String> {
        return try {
            // 检查代理地址是否为空
            if (proxyAddress.isBlank()) {
                logger.error("代理地址为空，无法查询余额")
                return Result.failure(IllegalArgumentException("代理地址不能为空"))
            }
            
            
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
        val rpcApi = polygonRpcApi
        
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
        
        // 使用 Gson 解析 result（JsonElement）
        val hexBalance = rpcResponse.result?.asString 
            ?: throw Exception("RPC 响应格式错误: result 为空")
        
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
    suspend fun getPositions(proxyWalletAddress: String, sortBy: String? = "CURRENT"): Result<List<PositionResponse>> {
        return try {
            // 使用代理钱包地址查询仓位
            // sortBy=CURRENT 表示只返回当前仓位
            val response = dataApi.getPositions(
                user = proxyWalletAddress,
                limit = 500,  // 最大限制
                offset = 0,
                sortBy = sortBy
            )
            
            if (response.isSuccessful && response.body() != null) {
                val positions = response.body()!!
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
     * 从 condition ID 和 outcomeIndex 计算 tokenId
     * 使用链上合约调用计算：
     * 1. getCollectionId(EMPTY_SET, conditionId, indexSet) -> collectionId
     * 2. getPositionId(collateralToken, collectionId) -> tokenId
     * 
     * indexSet 的计算：indexSet = 2^outcomeIndex
     * - outcomeIndex = 0 -> indexSet = 1 (2^0)
     * - outcomeIndex = 1 -> indexSet = 2 (2^1)
     * - outcomeIndex = 2 -> indexSet = 4 (2^2)
     * 
     * @param conditionId condition ID（16进制字符串，如 "0x..."）
     * @param outcomeIndex 结果索引（0, 1, 2...）
     * @return tokenId（BigInteger 的字符串表示）
     */
    suspend fun getTokenId(conditionId: String, outcomeIndex: Int): Result<String> {
        return try {
            val rpcApi = polygonRpcApi
            
            // 验证 outcomeIndex
            if (outcomeIndex < 0) {
                return Result.failure(IllegalArgumentException("outcomeIndex 必须 >= 0"))
            }
            
            // 计算 indexSet：indexSet = 2^outcomeIndex
            val indexSet = BigInteger.TWO.pow(outcomeIndex)
            
            // 1. 调用 getCollectionId(EMPTY_SET, conditionId, indexSet)
            val getCollectionIdSelector = EthereumUtils.getFunctionSelector("getCollectionId(bytes32,bytes32,uint256)")
            val encodedEmptySet = EthereumUtils.encodeBytes32(EMPTY_SET)
            val encodedConditionId = EthereumUtils.encodeBytes32(conditionId)
            val encodedIndexSet = EthereumUtils.encodeUint256(indexSet)
            // getFunctionSelector 已经返回带 0x 前缀的字符串，所以直接拼接即可
            val collectionIdData = getCollectionIdSelector + encodedEmptySet + encodedConditionId + encodedIndexSet
            
            val collectionIdRequest = JsonRpcRequest(
                method = "eth_call",
                params = listOf(
                    mapOf(
                        "to" to conditionalTokensAddress,
                        "data" to collectionIdData  // 移除多余的 0x 前缀
                    ),
                    "latest"
                )
            )
            
            val collectionIdResponse = rpcApi.call(collectionIdRequest)
            if (!collectionIdResponse.isSuccessful || collectionIdResponse.body() == null) {
                return Result.failure(Exception("调用 getCollectionId 失败: ${collectionIdResponse.code()} ${collectionIdResponse.message()}"))
            }
            
            val collectionIdResult = collectionIdResponse.body()!!
            if (collectionIdResult.error != null) {
                return Result.failure(Exception("调用 getCollectionId 失败: ${collectionIdResult.error}"))
            }
            
            // 使用 Gson 解析 result（JsonElement）
            val collectionId = collectionIdResult.result?.asString 
                ?: return Result.failure(Exception("getCollectionId 返回结果为空"))
            
            // 2. 调用 getPositionId(collateralToken, collectionId)
            val getPositionIdSelector = EthereumUtils.getFunctionSelector("getPositionId(address,bytes32)")
            val encodedCollateral = EthereumUtils.encodeAddress(usdcContractAddress)
            val encodedCollectionId = EthereumUtils.encodeBytes32(collectionId)
            // getFunctionSelector 已经返回带 0x 前缀的字符串，所以直接拼接即可
            val positionIdData = getPositionIdSelector + encodedCollateral + encodedCollectionId
            
            val positionIdRequest = JsonRpcRequest(
                method = "eth_call",
                params = listOf(
                    mapOf(
                        "to" to conditionalTokensAddress,
                        "data" to positionIdData  // 移除多余的 0x 前缀
                    ),
                    "latest"
                )
            )
            
            val positionIdResponse = rpcApi.call(positionIdRequest)
            if (!positionIdResponse.isSuccessful || positionIdResponse.body() == null) {
                return Result.failure(Exception("调用 getPositionId 失败: ${positionIdResponse.code()} ${positionIdResponse.message()}"))
            }
            
            val positionIdResult = positionIdResponse.body()!!
            if (positionIdResult.error != null) {
                return Result.failure(Exception("调用 getPositionId 失败: ${positionIdResult.error}"))
            }
            
            // 使用 Gson 解析 result（JsonElement）
            val tokenId = positionIdResult.result?.asString 
                ?: return Result.failure(Exception("getPositionId 返回结果为空"))
            val tokenIdBigInt = EthereumUtils.decodeUint256(tokenId)
            
            Result.success(tokenIdBigInt.toString())
        } catch (e: Exception) {
            logger.error("计算 tokenId 失败: conditionId=$conditionId, outcomeIndex=$outcomeIndex, ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 从 condition ID 和 side (YES/NO) 计算 tokenId（已废弃，不推荐使用）
     * 仅支持二元市场（YES/NO）
     * 
     * @deprecated 禁止使用 "YES"/"NO" 字符串判断 side，请使用 getTokenId(conditionId, outcomeIndex) 方法
     * @param conditionId condition ID（16进制字符串，如 "0x..."）
     * @param side YES 或 NO（不推荐使用，应使用 outcomeIndex）
     * @return tokenId（BigInteger 的字符串表示）
     */
    @Deprecated("禁止使用 YES/NO 字符串判断 side，请使用 getTokenId(conditionId, outcomeIndex) 方法", ReplaceWith("getTokenId(conditionId, outcomeIndex)"))
    suspend fun getTokenIdBySide(conditionId: String, side: String): Result<String> {
        // 注意：此方法违反了规范，禁止使用 "YES"/"NO" 字符串判断
        // 为了向后兼容，暂时保留，但应该尽快迁移到使用 outcomeIndex 的方法
        logger.warn("使用已废弃的方法 getTokenIdBySide，建议使用 getTokenId(conditionId, outcomeIndex): conditionId=$conditionId, side=$side")
        val outcomeIndex = when (side.uppercase()) {
            "YES" -> 0
            "NO" -> 1
            else -> return Result.failure(IllegalArgumentException("side 必须是 YES 或 NO（仅支持二元市场）。建议使用 getTokenId(conditionId, outcomeIndex) 方法"))
        }
        return getTokenId(conditionId, outcomeIndex)
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
    
    /**
     * 赎回仓位
     * 通过代理钱包的 execTransaction 调用 ConditionalTokens 合约的 redeemPositions 函数
     * 
     * 使用 RelayClientService 实现，完全参考 TypeScript 项目的实现方式
     * 
     * @param privateKey 私钥（原始钱包的私钥，用于签名交易）
     * @param proxyAddress 代理地址（Gnosis Safe 代理钱包地址）
     * @param conditionId 市场条件ID（bytes32，必须是 0x 开头的 66 位十六进制字符串）
     * @param indexSets 要赎回的索引集合列表（每个元素是 2^outcomeIndex，例如 [1] 表示 outcome 0，[2] 表示 outcome 1）
     * @return 交易哈希
     */
    suspend fun redeemPositions(
        privateKey: String,
        proxyAddress: String,
        conditionId: String,
        indexSets: List<BigInteger>
    ): Result<String> {
        return try {
            // 验证参数
            if (indexSets.isEmpty()) {
                return Result.failure(IllegalArgumentException("indexSets 不能为空"))
            }
            
            if (conditionId.isBlank() || !conditionId.startsWith("0x") || conditionId.length != 66) {
                return Result.failure(IllegalArgumentException("conditionId 格式错误，必须是 0x 开头的 66 位十六进制字符串"))
            }
            
            if (proxyAddress.isBlank() || !proxyAddress.startsWith("0x") || proxyAddress.length != 42) {
                return Result.failure(IllegalArgumentException("proxyAddress 格式错误，必须是有效的以太坊地址"))
            }

            // 使用 RelayClientService 创建赎回交易并执行
            val redeemTx = relayClientService.createRedeemTx(conditionId, indexSets)
            relayClientService.execute(privateKey, proxyAddress, redeemTx)
        } catch (e: Exception) {
            logger.error("赎回仓位失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 获取代理钱包的 nonce（用于构建 Safe 交易）
     */
    private suspend fun getProxyNonce(proxyAddress: String): Result<BigInteger> {
        val rpcApi = polygonRpcApi
        
        // Gnosis Safe 的 nonce 通过调用合约的 nonce() 函数获取
        val nonceFunctionSelector = EthereumUtils.getFunctionSelector("nonce()")
        
        val rpcRequest = JsonRpcRequest(
            method = "eth_call",
            params = listOf(
                mapOf(
                    "to" to proxyAddress,
                    "data" to nonceFunctionSelector
                ),
                "latest"
            )
        )
        
        val response = rpcApi.call(rpcRequest)
        if (!response.isSuccessful || response.body() == null) {
            return Result.failure(Exception("获取 Proxy nonce 失败: ${response.code()} ${response.message()}"))
        }
        
        val rpcResponse = response.body()!!
        if (rpcResponse.error != null) {
            return Result.failure(Exception("获取 Proxy nonce 失败: ${rpcResponse.error.message}"))
        }
        
        // 使用 Gson 解析 result（JsonElement）
        val hexNonce = rpcResponse.result?.asString 
            ?: return Result.failure(Exception("Proxy nonce 结果为空"))
        val nonce = EthereumUtils.decodeUint256(hexNonce)
        return Result.success(nonce)
    }
    
    /**
     * 获取交易 nonce
     */
    private suspend fun getTransactionCount(address: String): Result<BigInteger> {
        val rpcApi = polygonRpcApi
        
        val rpcRequest = JsonRpcRequest(
            method = "eth_getTransactionCount",
            // 使用 "pending"，将未打包的待处理交易也计入 nonce，
            // 避免在有挂起交易时出现 "nonce too low" 错误
            params = listOf(address, "pending")
        )
        
        val response = rpcApi.call(rpcRequest)
        if (!response.isSuccessful || response.body() == null) {
            return Result.failure(Exception("获取 nonce 失败: ${response.code()} ${response.message()}"))
        }
        
        val rpcResponse = response.body()!!
        if (rpcResponse.error != null) {
            return Result.failure(Exception("获取 nonce 失败: ${rpcResponse.error.message}"))
        }
        
        // 使用 Gson 解析 result（JsonElement）
        val hexNonce = rpcResponse.result?.asString 
            ?: return Result.failure(Exception("nonce 结果为空"))
        val nonce = EthereumUtils.decodeUint256(hexNonce)
        return Result.success(nonce)
    }
    
    /**
     * 获取 gas price
     */
    private suspend fun getGasPrice(): Result<BigInteger> {
        val rpcApi = polygonRpcApi
        
        val rpcRequest = JsonRpcRequest(
            method = "eth_gasPrice",
            params = emptyList()
        )
        
        val response = rpcApi.call(rpcRequest)
        if (!response.isSuccessful || response.body() == null) {
            return Result.failure(Exception("获取 gas price 失败: ${response.code()} ${response.message()}"))
        }
        
        val rpcResponse = response.body()!!
        if (rpcResponse.error != null) {
            return Result.failure(Exception("获取 gas price 失败: ${rpcResponse.error.message}"))
        }
        
        // 使用 Gson 解析 result（JsonElement）
        val hexGasPrice = rpcResponse.result?.asString 
            ?: return Result.failure(Exception("gas price 结果为空"))
        val gasPrice = EthereumUtils.decodeUint256(hexGasPrice)
        return Result.success(gasPrice)
    }
    
    /**
     * 构建并签名交易
     */
    private fun buildTransaction(
        privateKey: String,
        from: String,
        to: String,
        data: String,
        nonce: BigInteger,
        gasLimit: BigInteger,
        gasPrice: BigInteger
    ): Map<String, Any> {
        // 从私钥创建凭证
        val cleanPrivateKey = privateKey.removePrefix("0x")
        val privateKeyBigInt = BigInteger(cleanPrivateKey, 16)
        val credentials = org.web3j.crypto.Credentials.create(privateKeyBigInt.toString(16))
        
        // 构建原始交易
        val rawTransaction = org.web3j.crypto.RawTransaction.createTransaction(
            nonce,
            gasPrice,
            gasLimit,
            to,
            data
        )
        
        // 签名交易（Polygon 主网 chainId = 137）
        val chainId: Long = 137L
        val signedTransaction = org.web3j.crypto.TransactionEncoder.signMessage(rawTransaction, chainId, credentials)
        val hexValue = org.web3j.utils.Numeric.toHexString(signedTransaction)
        
        return mapOf(
            "from" to from,
            "to" to to,
            "data" to data,
            "nonce" to "0x${nonce.toString(16)}",
            "gas" to "0x${gasLimit.toString(16)}",
            "gasPrice" to "0x${gasPrice.toString(16)}",
            "value" to "0x0",
            "chainId" to "0x89", // Polygon 主网 chainId = 137 = 0x89
            "rawTransaction" to hexValue
        )
    }
    
    /**
     * 发送交易
     */
    private suspend fun sendTransaction(
        rpcApi: EthereumRpcApi,
        transaction: Map<String, Any>
    ): Result<String> {
        val rawTransaction = transaction["rawTransaction"] as? String
            ?: return Result.failure(IllegalArgumentException("rawTransaction 不能为空"))
        
        val rpcRequest = JsonRpcRequest(
            method = "eth_sendRawTransaction",
            params = listOf(rawTransaction)
        )
        
        val response = rpcApi.call(rpcRequest)
        if (!response.isSuccessful || response.body() == null) {
            return Result.failure(Exception("发送交易失败: ${response.code()} ${response.message()}"))
        }
        
        val rpcResponse = response.body()!!
        if (rpcResponse.error != null) {
            return Result.failure(Exception("发送交易失败: ${rpcResponse.error.message}"))
        }
        
        // 使用 Gson 解析 result（JsonElement）
        val txHash = rpcResponse.result?.asString 
            ?: return Result.failure(Exception("交易哈希为空"))
        return Result.success(txHash)
    }
    
    /**
     * 从链上查询市场条件（Condition）的结算结果
     * 通过调用 ConditionalTokens 合约的 getCondition 函数获取 payouts
     * 
     * @param conditionId 市场条件ID（bytes32，必须是 0x 开头的 66 位十六进制字符串）
     * @return Result<Pair<payoutDenominator, payouts>>
     *   - payoutDenominator: 支付分母（通常为 1）
     *   - payouts: 每个 outcome 的支付金额数组（0 或 1）
     *   - 如果 payouts[outcomeIndex] == 1，表示该 outcome 赢了
     *   - 如果 payouts[outcomeIndex] == 0，表示该 outcome 输了
     *   - 如果 payouts 为空，表示市场尚未结算
     */
    suspend fun getCondition(conditionId: String): Result<Pair<BigInteger, List<BigInteger>>> {
        return try {
            // 验证 conditionId 格式
            if (conditionId.isBlank() || !conditionId.startsWith("0x") || conditionId.length != 66) {
                return Result.failure(IllegalArgumentException("conditionId 格式错误，必须是 0x 开头的 66 位十六进制字符串"))
            }
            
            val rpcApi = polygonRpcApi
            
            // 构建 getCondition(bytes32) 函数调用
            // 函数签名: getCondition(bytes32)
            val functionSelector = EthereumUtils.getFunctionSelector("getCondition(bytes32)")
            val encodedConditionId = EthereumUtils.encodeBytes32(conditionId)
            val data = functionSelector + encodedConditionId
            
            // 构建 JSON-RPC 请求
            val rpcRequest = JsonRpcRequest(
                method = "eth_call",
                params = listOf(
                    mapOf(
                        "to" to conditionalTokensAddress,
                        "data" to data
                    ),
                    "latest"
                )
            )
            
            // 发送 RPC 请求
            val response = rpcApi.call(rpcRequest)
            
            if (!response.isSuccessful || response.body() == null) {
                return Result.failure(Exception("RPC 请求失败: ${response.code()} ${response.message()}"))
            }
            
            val rpcResponse = response.body()!!
            
            // 检查错误
            if (rpcResponse.error != null) {
                return Result.failure(Exception("RPC 错误: ${rpcResponse.error.message}"))
            }
            
            // 使用 Gson 解析 result（JsonElement）
            val hexResult = rpcResponse.result?.asString 
                ?: return Result.failure(Exception("RPC 响应格式错误: result 为空"))
            
            // 解析 ABI 编码的返回结果
            val (payoutDenominator, payouts) = EthereumUtils.decodeConditionResult(hexResult)
            
            Result.success(Pair(payoutDenominator, payouts))
        } catch (e: Exception) {
            logger.error("查询市场条件失败: conditionId=$conditionId, ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 查询交易详情（用于调试和分析）
     * @param txHash 交易哈希
     * @return 交易详情（JSON 字符串）
     */
    suspend fun getTransactionDetails(txHash: String): Result<String> {
        return try {
            val rpcApi = polygonRpcApi
            
            // 查询交易
            val txRequest = JsonRpcRequest(
                method = "eth_getTransactionByHash",
                params = listOf(txHash)
            )
            
            val txResponse = rpcApi.call(txRequest)
            if (!txResponse.isSuccessful || txResponse.body() == null) {
                return Result.failure(Exception("查询交易失败: ${txResponse.code()} ${txResponse.message()}"))
            }
            
            val txRpcResponse = txResponse.body()!!
            if (txRpcResponse.error != null) {
                return Result.failure(Exception("查询交易失败: ${txRpcResponse.error.message}"))
            }
            
            // 使用 Gson 解析 result（JsonElement）
            val txResult = txRpcResponse.result?.toString() 
                ?: return Result.failure(Exception("交易结果为空"))
            
            // 查询交易回执（包含内部调用和事件日志）
            val receiptRequest = JsonRpcRequest(
                method = "eth_getTransactionReceipt",
                params = listOf(txHash)
            )
            
            val receiptResponse = rpcApi.call(receiptRequest)
            if (!receiptResponse.isSuccessful || receiptResponse.body() == null) {
                return Result.success("交易信息: $txResult\n\n注意: 无法获取交易回执")
            }
            
            val receiptRpcResponse = receiptResponse.body()!!
            val receiptResult = if (receiptRpcResponse.error != null) {
                "交易回执查询失败: ${receiptRpcResponse.error.message}"
            } else {
                receiptRpcResponse.result?.toString() ?: "交易回执为空（可能还在打包中）"
            }
            
            Result.success("交易信息:\n$txResult\n\n交易回执:\n$receiptResult")
        } catch (e: Exception) {
            logger.error("查询交易详情失败: ${e.message}", e)
            Result.failure(e)
        }
    }
}

