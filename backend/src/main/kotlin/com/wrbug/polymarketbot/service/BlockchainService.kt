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
    @Value("\${polygon.rpc.url:}")
    private val polygonRpcUrl: String,
    private val retrofitFactory: RetrofitFactory
) {
    
    private val logger = LoggerFactory.getLogger(BlockchainService::class.java)
    
    // USDC 合约地址（Polygon 主网，Polymarket 使用 Polygon）
    private val usdcContractAddress = "0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174"
    
    // Polymarket 代理工厂合约地址（Polygon 主网）
    // 合约地址: 0xaacFeEa03eb1561C4e67d661e40682Bd20E3541b
    private val proxyFactoryContractAddress = "0xaacFeEa03eb1561C4e67d661e40682Bd20E3541b"
    
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
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PolymarketDataApi::class.java)
    }
    
    private val polygonRpcApi: EthereumRpcApi? by lazy {
        if (polygonRpcUrl.isBlank()) {
            null
        } else {
            retrofitFactory.createEthereumRpcApi(polygonRpcUrl)
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
            if (polygonRpcUrl.isBlank()) {
                logger.warn("未配置 Polygon RPC URL，无法获取代理地址")
                return Result.failure(IllegalStateException("未配置 Polygon RPC URL，无法获取代理地址。请在配置文件中设置 polygon.rpc.url 环境变量"))
            }
            
            val rpcApi = polygonRpcApi ?: throw IllegalStateException("Polygon RPC URL 未配置")
            
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
            
            Result.success(proxyAddress)
        } catch (e: Exception) {
            logger.error("获取代理地址失败: ${e.message}", e)
            Result.failure(e)
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
            // 如果未配置 RPC URL，返回错误
            if (polygonRpcUrl.isBlank()) {
                logger.warn("未配置 Polygon RPC URL，无法查询 USDC 余额")
                return Result.failure(IllegalStateException("未配置 Polygon RPC URL，无法查询 USDC 余额。请在配置文件中设置 polygon.rpc.url 环境变量"))
            }
            
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
        val rpcApi = polygonRpcApi ?: throw IllegalStateException("Polygon RPC URL 未配置")
        
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
            // 如果未配置 RPC URL，返回错误
            if (polygonRpcUrl.isBlank()) {
                logger.warn("未配置 Polygon RPC URL，无法计算 tokenId")
                return Result.failure(IllegalStateException("未配置 Polygon RPC URL，无法计算 tokenId"))
            }
            
            val rpcApi = polygonRpcApi ?: throw IllegalStateException("Polygon RPC URL 未配置")
            
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
            
            val collectionId = collectionIdResult.result ?: return Result.failure(Exception("getCollectionId 返回结果为空"))
            
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
            
            val tokenId = positionIdResult.result ?: return Result.failure(Exception("getPositionId 返回结果为空"))
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
     * 重要说明（基于实际链上交易分析）：
     * - 仓位在 proxyAddress 上，需要通过代理钱包的 execTransaction 执行赎回
     * - 交易流程：EOA → Proxy.execTransaction → ConditionalTokens.redeemPositions
     * - execTransaction 是 Gnosis Safe 标准函数，需要构建 Safe 交易并签名
     * 
     * 参考交易: https://polygonscan.com/tx/0xb3b4cbab668c4c764aa2e7ff6f17f56f372921fc4852ef616ce65238d73eca4e
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
            // 如果未配置 RPC URL，返回错误
            if (polygonRpcUrl.isBlank()) {
                logger.warn("未配置 Polygon RPC URL，无法赎回仓位")
                return Result.failure(IllegalStateException("未配置 Polygon RPC URL，无法赎回仓位"))
            }
            
            val rpcApi = polygonRpcApi ?: throw IllegalStateException("Polygon RPC URL 未配置")
            
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

            // 从私钥推导实际签名地址（交易真正的 from 地址）
            val cleanPrivateKey = privateKey.removePrefix("0x")
            val privateKeyBigInt = BigInteger(cleanPrivateKey, 16)
            val credentials = org.web3j.crypto.Credentials.create(privateKeyBigInt.toString(16))
            val fromAddress = credentials.address
            
            
            // 1. 构建 ConditionalTokens.redeemPositions 的调用数据
            val redeemFunctionSelector = EthereumUtils.getFunctionSelector("redeemPositions(address,bytes32,bytes32,uint256[])")
            
            // 编码 redeemPositions 参数
            val encodedCollateral = EthereumUtils.encodeAddress(usdcContractAddress)
            val encodedParentCollection = EthereumUtils.encodeBytes32(EMPTY_SET)  // parentCollectionId 通常为全0
            val encodedConditionId = EthereumUtils.encodeBytes32(conditionId)
            
            // 编码数组：offset (32字节) + length (32字节) + 每个元素 (32字节)
            val arrayOffset = BigInteger.valueOf(128)
            val arrayLength = BigInteger.valueOf(indexSets.size.toLong())
            val encodedArrayOffset = EthereumUtils.encodeUint256(arrayOffset)
            val encodedArrayLength = EthereumUtils.encodeUint256(arrayLength)
            val encodedArrayElements = indexSets.joinToString("") { EthereumUtils.encodeUint256(it) }
            
            // ConditionalTokens.redeemPositions 的调用数据
            val redeemCallData = redeemFunctionSelector + 
                encodedCollateral + 
                encodedParentCollection + 
                encodedConditionId + 
                encodedArrayOffset + 
                encodedArrayLength + 
                encodedArrayElements
            
            // 2. 构建 Proxy.execTransaction 的调用数据
            // 函数签名: execTransaction(address to,uint256 value,bytes data,uint8 operation,uint256 safeTxGas,uint256 baseGas,uint256 gasPrice,address gasToken,address refundReceiver,bytes signatures)
            // 根据实际交易分析，参数如下：
            // - to: ConditionalTokens 合约地址
            // - value: 0
            // - data: redeemPositions 的调用数据
            // - operation: 0 (CALL)
            // - safeTxGas: 0 (使用所有可用 gas)
            // - baseGas: 0
            // - gasPrice: 0 (使用当前 gas price)
            // - gasToken: 0x0000...0000 (使用原生代币)
            // - refundReceiver: 0x0000...0000
            // - signatures: EIP-712 签名的 Safe 交易
            
            // 获取 Proxy 的 nonce（用于构建 Safe 交易哈希）
            val proxyNonceResult = getProxyNonce(proxyAddress)
            val proxyNonce = proxyNonceResult.getOrElse {
                logger.warn("获取 Proxy nonce 失败，使用 0: ${it.message}")
                BigInteger.ZERO
            }
            
            // 构建 Safe 交易哈希（用于 EIP-712 签名）
            // 使用 Gnosis Safe 的 EIP-712 签名标准
            val safeTxGas = BigInteger.ZERO
            val baseGas = BigInteger.ZERO
            val safeGasPrice = BigInteger.ZERO // 使用不同的变量名避免冲突
            val gasToken = "0x0000000000000000000000000000000000000000"
            val refundReceiver = "0x0000000000000000000000000000000000000000"
            
            // 1. 编码 Safe 域分隔符
            val safeDomainSeparator = com.wrbug.polymarketbot.util.Eip712Encoder.encodeSafeDomain(
                verifyingContract = proxyAddress
            )
            
            // 2. 编码 SafeTx 消息哈希
            val safeTxHash = com.wrbug.polymarketbot.util.Eip712Encoder.encodeSafeTx(
                to = conditionalTokensAddress,
                value = BigInteger.ZERO,
                data = redeemCallData,
                operation = 0, // CALL
                safeTxGas = safeTxGas,
                baseGas = baseGas,
                gasPrice = safeGasPrice,
                gasToken = gasToken,
                refundReceiver = refundReceiver,
                nonce = proxyNonce
            )
            
            // 3. 计算完整的结构化数据哈希
            val safeTxStructuredHash = com.wrbug.polymarketbot.util.Eip712Encoder.hashStructuredData(
                domainSeparator = safeDomainSeparator,
                messageHash = safeTxHash
            )
            
            // 4. 使用私钥签名 Safe 交易
            val ecKeyPair = org.web3j.crypto.ECKeyPair.create(privateKeyBigInt)
            val safeSignature = org.web3j.crypto.Sign.signMessage(safeTxStructuredHash, ecKeyPair, false)
            
            // 5. 编码签名数据（Gnosis Safe 签名格式：r + s + v，每个 32 字节，共 96 字节）
            // Safe 签名格式：r (32 bytes) + s (32 bytes) + v (1 byte，但需要编码为 32 字节)
            val vBytes = safeSignature.v as ByteArray
            val vInt = if (vBytes.isNotEmpty()) {
                vBytes[0].toInt() and 0xff
            } else {
                0
            }
            
            // 编码为十六进制字符串（每个字段 32 字节 = 64 个十六进制字符）
            val rHex = org.web3j.utils.Numeric.toHexString(safeSignature.r).removePrefix("0x").padStart(64, '0')
            val sHex = org.web3j.utils.Numeric.toHexString(safeSignature.s).removePrefix("0x").padStart(64, '0')
            val vHex = String.format("%064x", vInt)
            
            // Safe 签名格式：r + s + v（每个 32 字节，共 96 字节 = 192 个十六进制字符）
            val safeSignatureHex = rHex + sHex + vHex
            
            // 3. 构建 execTransaction 的调用数据
            // 函数签名: execTransaction(address,uint256,bytes,uint8,uint256,uint256,uint256,address,address,bytes)
            val execFunctionSelector = EthereumUtils.getFunctionSelector("execTransaction(address,uint256,bytes,uint8,uint256,uint256,uint256,address,address,bytes)")
            
            // 编码 execTransaction 参数
            val encodedTo = EthereumUtils.encodeAddress(conditionalTokensAddress)
            val encodedValue = EthereumUtils.encodeUint256(BigInteger.ZERO)
            
            // data 参数（bytes 类型）：offset (32字节) + length (32字节) + data (按长度，不足32字节的倍数需要padding)
            // 固定参数部分：to(32) + value(32) + data_offset(32) + operation(32) + safeTxGas(32) + baseGas(32) + gasPrice(32) + gasToken(32) + refundReceiver(32) + signatures_offset(32) = 320字节
            val dataOffset = BigInteger.valueOf(320L)
            val redeemCallDataHex = redeemCallData.removePrefix("0x")
            val dataLengthBytes = BigInteger.valueOf((redeemCallDataHex.length / 2).toLong()) // 字节数
            val encodedDataOffset = EthereumUtils.encodeUint256(dataOffset)
            val encodedDataLength = EthereumUtils.encodeUint256(dataLengthBytes)
            // data 需要按 32 字节对齐（不足的补0）
            val dataPaddedLength = ((dataLengthBytes.toInt() + 31) / 32) * 32 * 2 // 十六进制字符数
            val encodedData = redeemCallDataHex.padEnd(dataPaddedLength, '0')
            
            val encodedOperation = EthereumUtils.encodeUint256(BigInteger.ZERO) // 0 = CALL
            val encodedSafeTxGas = EthereumUtils.encodeUint256(BigInteger.ZERO)
            val encodedBaseGas = EthereumUtils.encodeUint256(BigInteger.ZERO)
            val encodedGasPrice = EthereumUtils.encodeUint256(BigInteger.ZERO)
            val encodedGasToken = EthereumUtils.encodeAddress("0x0000000000000000000000000000000000000000")
            val encodedRefundReceiver = EthereumUtils.encodeAddress("0x0000000000000000000000000000000000000000")
            
            // signatures 参数（bytes 类型）：offset + length + signatures
            // signatures 的 offset = dataOffset + dataLength 的 32字节对齐后的位置
            val dataPaddedBytes = dataPaddedLength / 2
            val signaturesOffset = BigInteger.valueOf((320 + dataPaddedBytes).toLong())
            // Safe 签名长度：r (32) + s (32) + v (32) = 96 字节
            val signaturesLength = BigInteger.valueOf(96L)
            val encodedSignaturesOffset = EthereumUtils.encodeUint256(signaturesOffset)
            val encodedSignaturesLength = EthereumUtils.encodeUint256(signaturesLength)
            // signatures 需要按 32 字节对齐（96 字节已经是 32 的倍数，不需要 padding）
            val encodedSignatures = safeSignatureHex
            
            // 组合 execTransaction 调用数据
            val execCallData = "0x" + execFunctionSelector.removePrefix("0x") +
                encodedTo +
                encodedValue +
                encodedDataOffset +
                encodedDataLength +
                encodedData +
                encodedOperation +
                encodedSafeTxGas +
                encodedBaseGas +
                encodedGasPrice +
                encodedGasToken +
                encodedRefundReceiver +
                encodedSignaturesOffset +
                encodedSignaturesLength +
                encodedSignatures
            
            // 4. 获取 EOA 的 nonce（用于发送交易）
            val nonceResult = getTransactionCount(fromAddress)
            val nonce = nonceResult.getOrElse {
                return Result.failure(Exception("获取 nonce 失败: ${it.message}"))
            }
            
            // 5. 获取 gas price
            val gasPriceResult = getGasPrice()
            val gasPrice = gasPriceResult.getOrElse {
                return Result.failure(Exception("获取 gas price 失败: ${it.message}"))
            }
            
            // 6. Gas limit（通过 Proxy 执行需要更多 gas，给 240 万，参考实际交易）
            val gasLimit = BigInteger.valueOf(2400000)
            
            // 7. 构建并签名交易
            // 调用 Proxy 的 execTransaction
            val transaction = buildTransaction(
                privateKey = privateKey,
                from = fromAddress,
                to = proxyAddress,  // 调用代理钱包
                data = execCallData,
                nonce = nonce,
                gasLimit = gasLimit,
                gasPrice = gasPrice
            )
            
            // 8. 发送交易
            val txHashResult = sendTransaction(rpcApi, transaction)
            txHashResult.fold(
                onSuccess = { txHash ->
                    Result.success(txHash)
                },
                onFailure = { e ->
                    logger.error("发送赎回交易失败: ${e.message}", e)
                    Result.failure(e)
                }
            )
        } catch (e: Exception) {
            logger.error("赎回仓位失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 获取代理钱包的 nonce（用于构建 Safe 交易）
     */
    private suspend fun getProxyNonce(proxyAddress: String): Result<BigInteger> {
        val rpcApi = polygonRpcApi ?: throw IllegalStateException("Polygon RPC URL 未配置")
        
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
        
        val hexNonce = rpcResponse.result ?: return Result.failure(Exception("Proxy nonce 结果为空"))
        val nonce = EthereumUtils.decodeUint256(hexNonce)
        return Result.success(nonce)
    }
    
    /**
     * 获取交易 nonce
     */
    private suspend fun getTransactionCount(address: String): Result<BigInteger> {
        val rpcApi = polygonRpcApi ?: throw IllegalStateException("Polygon RPC URL 未配置")
        
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
        
        val hexNonce = rpcResponse.result ?: return Result.failure(Exception("nonce 结果为空"))
        val nonce = EthereumUtils.decodeUint256(hexNonce)
        return Result.success(nonce)
    }
    
    /**
     * 获取 gas price
     */
    private suspend fun getGasPrice(): Result<BigInteger> {
        val rpcApi = polygonRpcApi ?: throw IllegalStateException("Polygon RPC URL 未配置")
        
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
        
        val hexGasPrice = rpcResponse.result ?: return Result.failure(Exception("gas price 结果为空"))
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
        
        val txHash = rpcResponse.result ?: return Result.failure(Exception("交易哈希为空"))
        return Result.success(txHash)
    }
    
    /**
     * 查询交易详情（用于调试和分析）
     * @param txHash 交易哈希
     * @return 交易详情（JSON 字符串）
     */
    suspend fun getTransactionDetails(txHash: String): Result<String> {
        return try {
            if (polygonRpcUrl.isBlank()) {
                return Result.failure(IllegalStateException("未配置 Polygon RPC URL"))
            }
            
            val rpcApi = polygonRpcApi ?: throw IllegalStateException("Polygon RPC URL 未配置")
            
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
            
            val txResult = txRpcResponse.result ?: return Result.failure(Exception("交易结果为空"))
            
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
                receiptRpcResponse.result ?: "交易回执为空（可能还在打包中）"
            }
            
            Result.success("交易信息:\n$txResult\n\n交易回执:\n$receiptResult")
        } catch (e: Exception) {
            logger.error("查询交易详情失败: ${e.message}", e)
            Result.failure(e)
        }
    }
}

