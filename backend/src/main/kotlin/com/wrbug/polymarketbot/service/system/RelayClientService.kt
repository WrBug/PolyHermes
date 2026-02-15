package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.api.BuilderRelayerApi
import com.wrbug.polymarketbot.api.EthereumRpcApi
import com.wrbug.polymarketbot.api.JsonRpcRequest
import com.wrbug.polymarketbot.constants.PolymarketConstants
import com.wrbug.polymarketbot.enums.WalletType
import com.wrbug.polymarketbot.util.EthereumUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.createClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigInteger

/**
 * RelayClient 服务
 * 参考 TypeScript 项目的实现方式，提供 Gasless 交易支持
 *
 * 注意：当前实现使用手动构建交易的方式（需要支付 gas）
 * 如果需要真正的 Gasless 功能，需要集成 Builder Relayer API
 *
 * 参考：
 * - TypeScript: https://github.com/Polymarket/builder-relayer-client（client.execute、src/encode/safe.ts MultiSend）
 * - 赎回 calldata 由本服务构建，官方仓库无 redeem 工具；Neg Risk 逻辑见 docs/neg-risk-redeem.md
 */
@Service
class RelayClientService(
    private val retrofitFactory: RetrofitFactory,
    private val systemConfigService: SystemConfigService,
    private val rpcNodeService: RpcNodeService
) {

    private val logger = LoggerFactory.getLogger(RelayClientService::class.java)

    // ConditionalTokens 合约地址
    private val conditionalTokensAddress = "0x4D97DCd97eC945f40cF65F87097ACe5EA0476045"

    // USDC.e 合约地址（普通市场抵押品）
    private val usdcContractAddress = "0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174"

    // Neg Risk 市场使用的 WrappedCollateral 合约地址（Polygon，neg-risk-ctf-adapter）
    private val negRiskWrappedCollateralAddress = "0x3A3BD7bb9528E159577F7C2e685CC81A765002E2"

    // 空集合ID
    private val EMPTY_SET = "0x0000000000000000000000000000000000000000000000000000000000000000"

    // Polygon PROXY（Magic）合约地址，参考 builder-relayer-client config
    private val proxyFactoryAddress = "0xaB45c5A4B0c941a2F231C04C3f49182e1A254052"
    private val relayHubAddress = "0xD216153c06E857cD7f72665E0aF1d7D82172F494"
    private val defaultProxyGasLimit = "10000000"

    // Safe MultiSend 合约地址（Polygon 主网）
    private val safeMultisendAddress = "0xA238CBeb142c10Ef7Ad8442C6D1f9E89e07e7761"
    
    // Builder Relayer API 交易类型常量
    private val RELAYER_TYPE_PROXY = "PROXY"
    private val RELAYER_TYPE_SAFE = "SAFE"

    private val polygonRpcApi: EthereumRpcApi by lazy {
        val rpcUrl = rpcNodeService.getHttpUrl()
        retrofitFactory.createEthereumRpcApi(rpcUrl)
    }

    /**
     * 获取 Builder Relayer API 客户端（动态获取，因为配置可能更新）
     */
    private fun getBuilderRelayerApi(): BuilderRelayerApi? {
        val builderApiKey = systemConfigService.getBuilderApiKey()
        val builderSecret = systemConfigService.getBuilderSecret()
        val builderPassphrase = systemConfigService.getBuilderPassphrase()

        if (isBuilderRelayerEnabled(builderApiKey, builderSecret, builderPassphrase)) {
            return retrofitFactory.createBuilderRelayerApi(
                relayerUrl = PolymarketConstants.BUILDER_RELAYER_URL,
                apiKey = builderApiKey!!,
                secret = builderSecret!!,
                passphrase = builderPassphrase!!
            )
        }
        return null
    }

    /**
     * 检查是否启用了 Builder Relayer（Gasless 交易）
     */
    private fun isBuilderRelayerEnabled(
        builderApiKey: String?,
        builderSecret: String?,
        builderPassphrase: String?
    ): Boolean {
        return PolymarketConstants.BUILDER_RELAYER_URL.isNotBlank() &&
                builderApiKey != null && builderApiKey.isNotBlank() &&
                builderSecret != null && builderSecret.isNotBlank() &&
                builderPassphrase != null && builderPassphrase.isNotBlank()
    }

    /**
     * 检查 Builder API Key 是否已配置
     */
    fun isBuilderApiKeyConfigured(): Boolean {
        return systemConfigService.isBuilderApiKeyConfigured()
    }

    /**
     * 检查 Builder Relayer API 健康状态（用于 API 健康检查）
     */
    suspend fun checkBuilderRelayerApiHealth(): Result<Long> {
        return try {
            val builderApiKey = systemConfigService.getBuilderApiKey()
            val builderSecret = systemConfigService.getBuilderSecret()
            val builderPassphrase = systemConfigService.getBuilderPassphrase()

            if (builderApiKey == null || builderSecret == null || builderPassphrase == null) {
                return Result.failure(IllegalStateException("Builder API Key 未配置"))
            }

            val relayerApi = retrofitFactory.createBuilderRelayerApi(
                relayerUrl = PolymarketConstants.BUILDER_RELAYER_URL,
                apiKey = builderApiKey,
                secret = builderSecret,
                passphrase = builderPassphrase
            )

            // 使用一个测试地址来检查 API 是否可用（使用一个已知的地址，如零地址）
            val testAddress = "0x0000000000000000000000000000000000000000"
            val startTime = System.currentTimeMillis()
            val response = relayerApi.getDeployed(testAddress)
            val responseTime = System.currentTimeMillis() - startTime

            if (response.isSuccessful) {
                Result.success(responseTime)
            } else {
                val errorBody = response.errorBody()?.string() ?: "未知错误"
                Result.failure(Exception("Builder Relayer API 调用失败: ${response.code()} - $errorBody"))
            }
        } catch (e: Exception) {
            logger.error("检查 Builder Relayer API 健康状态失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 赎回仓位参数
     */
    data class RedeemParams(
        val conditionId: String,      // 市场条件ID
        val outcomeIndex: Int         // 结果索引（0, 1, 2...）
    )

    /**
     * Safe 交易结构
     * 参考 TypeScript: @polymarket/builder-relayer-client 的 SafeTransaction
     */
    data class SafeTransaction(
        val to: String,               // 目标合约地址
        val operation: Int = 0,       // 0 = CALL, 1 = DELEGATE_CALL
        val data: String,            // 调用数据
        val value: String = "0"      // 发送的 ETH 数量
    )

    /**
     * 创建赎回交易（单个 outcomeIndex）
     * 参考 TypeScript: utils/redeem.ts 的 createRedeemTx
     *
     * @param params 赎回参数
     * @return Safe 交易对象
     */
    fun createRedeemTx(params: RedeemParams): SafeTransaction {
        val (conditionId, outcomeIndex) = params

        // 计算 indexSet = 2^outcomeIndex
        val indexSet = BigInteger.TWO.pow(outcomeIndex)
        return createRedeemTx(conditionId, listOf(indexSet))
    }

    /**
     * 创建赎回交易（支持多个 indexSets，用于批量赎回）
     * 参考 TypeScript: utils/redeem.ts 的 createRedeemTx
     * Neg Risk 市场使用 WrappedCollateral 作为抵押品，需传 isNegRisk=true
     *
     * @param conditionId 市场条件ID
     * @param indexSets 索引集合列表（每个元素是 2^outcomeIndex）
     * @param isNegRisk 是否为 Neg Risk 市场（true 时使用 WrappedCollateral 地址）
     * @return Safe 交易对象
     */
    fun createRedeemTx(conditionId: String, indexSets: List<BigInteger>, isNegRisk: Boolean = false): SafeTransaction {
        // 编码 redeemPositions 函数调用
        val functionSelector = EthereumUtils.getFunctionSelector(
            "redeemPositions(address,bytes32,bytes32,uint256[])"
        )

        // Neg Risk 市场仓位由 WrappedCollateral 抵押，普通市场由 USDC 抵押
        val collateralAddress = if (isNegRisk) negRiskWrappedCollateralAddress else usdcContractAddress
        val encodedCollateral = EthereumUtils.encodeAddress(collateralAddress)
        val encodedParentCollection = EthereumUtils.encodeBytes32(EMPTY_SET)
        val encodedConditionId = EthereumUtils.encodeBytes32(conditionId)

        // 编码数组：offset (32字节) + length (32字节) + 每个元素 (32字节)
        val arrayOffset = BigInteger.valueOf(128)
        val arrayLength = BigInteger.valueOf(indexSets.size.toLong())
        val encodedArrayOffset = EthereumUtils.encodeUint256(arrayOffset)
        val encodedArrayLength = EthereumUtils.encodeUint256(arrayLength)
        val encodedArrayElements = indexSets.joinToString("") { EthereumUtils.encodeUint256(it) }

        // 组合调用数据
        val callData = "0x" + functionSelector.removePrefix("0x") +
                encodedCollateral +
                encodedParentCollection +
                encodedConditionId +
                encodedArrayOffset +
                encodedArrayLength +
                encodedArrayElements

        return SafeTransaction(
            to = conditionalTokensAddress,
            operation = 0,  // CALL
            data = callData,
            value = "0"
        )
    }

    /**
     * 创建 MultiSend 交易（合并多个 SafeTransaction 为一笔交易）
     * 参考 TypeScript: builder-relayer-client/src/encode/safe.ts createSafeMultisendTransaction
     *
     * 使用 Gnosis Safe 的 MultiSend 合约将多个交易合并为一笔 DelegateCall 交易
     *
     * @param safeTxs 多个 Safe 交易
     * @return 合并后的 MultiSend 交易（operation = 1 = DelegateCall）
     */
    fun createMultiSendTx(safeTxs: List<SafeTransaction>): SafeTransaction {
        if (safeTxs.isEmpty()) {
            throw IllegalArgumentException("safeTxs 不能为空")
        }

        // 单个交易直接返回，不需要 MultiSend
        if (safeTxs.size == 1) {
            logger.debug("单个交易，不使用 MultiSend")
            return safeTxs.first()
        }

        logger.debug("创建 MultiSend 交易: ${safeTxs.size} 个交易待合并")

        // MultiSend 函数选择器：multiSend(bytes)
        val multiSendSelector = EthereumUtils.getFunctionSelector("multiSend(bytes)")

        // 编码每个交易：encodePacked([uint8 operation, address to, uint256 value, uint256 dataLength, bytes data])
        // 与 builder-relayer-client encode/safe.ts 完全一致
        val encodedTransactions = safeTxs.map { tx ->
            val operation = tx.operation.toByte()
            // address: 20 字节，右对齐（取最后 40 个十六进制字符）
            val toHex = tx.to.removePrefix("0x").lowercase().padStart(40, '0').takeLast(40)
            val to = EthereumUtils.hexToBytes(toHex)
            // value: 32 字节大端
            val valueHex = BigInteger(tx.value).toString(16).padStart(64, '0')
            val value = EthereumUtils.hexToBytes(valueHex)

            val dataBytes = EthereumUtils.hexToBytes(tx.data.removePrefix("0x"))
            // dataLength: 32 字节大端，表示 data 的字节数
            val dataLengthHex = BigInteger.valueOf(dataBytes.size.toLong()).toString(16).padStart(64, '0')
            val dataLength = EthereumUtils.hexToBytes(dataLengthHex)

            // encodePacked: operation(1) + to(20) + value(32) + dataLength(32) + data(variable)
            byteArrayOf(operation) + to + value + dataLength + dataBytes
        }

        // 拼接所有交易（无 padding，与 viem concatHex 一致）
        val concatenatedTransactions = encodedTransactions.reduce { acc, bytes -> acc + bytes }
        val totalDataLength = concatenatedTransactions.size

        // multiSend(bytes) 的 ABI 编码：offset(32) + length(32) + data(按 32 字节对齐 padding)
        val paddedLength = ((totalDataLength + 31) / 32) * 32
        val paddedData = concatenatedTransactions + ByteArray(paddedLength - totalDataLength)

        val encodedOffset = EthereumUtils.encodeUint256(BigInteger.valueOf(32))
        val encodedLength = EthereumUtils.encodeUint256(BigInteger.valueOf(totalDataLength.toLong()))
        val encodedData = paddedData.joinToString("") { "%02x".format(it) }

        val callData = "0x" + multiSendSelector.removePrefix("0x") + encodedOffset + encodedLength + encodedData

        return SafeTransaction(
            to = safeMultisendAddress,
            operation = 1,  // DelegateCall
            data = callData,
            value = "0"
        )
    }

    /**
     * 执行代理交易（Safe 或 Magic PROXY）
     * 参考 TypeScript: RelayClient.execute()
     *
     * @param privateKey 私钥
     * @param proxyAddress 代理钱包地址
     * @param safeTx 交易对象（to/data/value）
     * @param walletType 钱包类型：MAGIC 使用 PROXY Gasless，SAFE 使用 Safe 流程
     * @return 交易哈希
     */
    suspend fun execute(
        privateKey: String,
        proxyAddress: String,
        safeTx: SafeTransaction,
        walletType: WalletType = WalletType.SAFE
    ): Result<String> {
        return try {
            if (proxyAddress.isBlank() || !proxyAddress.startsWith("0x") || proxyAddress.length != 42) {
                return Result.failure(IllegalArgumentException("proxyAddress 格式错误，必须是有效的以太坊地址"))
            }

            val builderApiKey = systemConfigService.getBuilderApiKey()
            val builderSecret = systemConfigService.getBuilderSecret()
            val builderPassphrase = systemConfigService.getBuilderPassphrase()

            if (walletType == WalletType.MAGIC) {
                if (!isBuilderRelayerEnabled(builderApiKey, builderSecret, builderPassphrase)) {
                    return Result.failure(IllegalStateException("Magic 账户赎回必须配置 Builder API Key（Gasless）"))
                }
                logger.info("使用 Builder Relayer PROXY 执行 Magic 赎回")
                return executeViaBuilderRelayerProxy(
                    privateKey,
                    proxyAddress,
                    safeTx,
                    builderApiKey!!,
                    builderSecret!!,
                    builderPassphrase!!
                )
            }

            if (isBuilderRelayerEnabled(builderApiKey, builderSecret, builderPassphrase)) {
                logger.info("使用 Builder Relayer 执行 Gasless 交易")
                return executeViaBuilderRelayer(
                    privateKey,
                    proxyAddress,
                    safeTx,
                    builderApiKey!!,
                    builderSecret!!,
                    builderPassphrase!!
                )
            }

            logger.info("Builder Relayer 未配置，使用手动发送交易（需要用户支付 gas）")
            return executeManually(privateKey, proxyAddress, safeTx)
        } catch (e: Exception) {
            logger.error("执行交易失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 通过 Builder Relayer 执行 PROXY（Magic）交易（Gasless）
     * 参考: builder-relayer-client client.ts executeProxyTransactions, builder/proxy.ts
     */
    private suspend fun executeViaBuilderRelayerProxy(
        privateKey: String,
        proxyAddress: String,
        safeTx: SafeTransaction,
        builderApiKey: String,
        builderSecret: String,
        builderPassphrase: String
    ): Result<String> {
        val relayerApi = retrofitFactory.createBuilderRelayerApi(
            relayerUrl = PolymarketConstants.BUILDER_RELAYER_URL,
            apiKey = builderApiKey,
            secret = builderSecret,
            passphrase = builderPassphrase
        )

        val cleanPrivateKey = privateKey.removePrefix("0x")
        val privateKeyBigInt = BigInteger(cleanPrivateKey, 16)
        val credentials = org.web3j.crypto.Credentials.create(privateKeyBigInt.toString(16))
        val fromAddress = credentials.address

        val relayPayloadResponse = relayerApi.getRelayPayload(fromAddress, RELAYER_TYPE_PROXY)
        if (!relayPayloadResponse.isSuccessful || relayPayloadResponse.body() == null) {
            val errorBody = relayPayloadResponse.errorBody()?.string() ?: "未知错误"
            logger.error("获取 Relay Payload 失败: code=${relayPayloadResponse.code()}, body=$errorBody")
            return Result.failure(Exception("获取 Relay Payload 失败: ${relayPayloadResponse.code()} - $errorBody"))
        }
        val relayPayload = relayPayloadResponse.body()!!
        val relayAddress = relayPayload.address
        val nonce = relayPayload.nonce

        val proxyCallData = encodeProxyTransactionData(safeTx)
        
        // 估算 gas limit（参考 builder-relayer-client builder/proxy.ts getGasLimit）
        val gasLimit = try {
            estimateProxyGasLimit(fromAddress, proxyFactoryAddress, proxyCallData)
        } catch (e: Exception) {
            logger.warn("估算 PROXY gas limit 失败，使用默认值: ${e.message}", e)
            defaultProxyGasLimit
        }

        val structHash = createProxyStructHash(
            from = fromAddress,
            to = proxyFactoryAddress,
            data = proxyCallData,
            txFee = "0",
            gasPrice = "0",
            gasLimit = gasLimit,
            nonce = nonce,
            relayHubAddress = relayHubAddress,
            relayAddress = relayAddress
        )

        val prefix = "\u0019Ethereum Signed Message:\n32".toByteArray(Charsets.UTF_8)
        val messageWithPrefix = ByteArray(prefix.size + structHash.size)
        System.arraycopy(prefix, 0, messageWithPrefix, 0, prefix.size)
        System.arraycopy(structHash, 0, messageWithPrefix, prefix.size, structHash.size)

        val keccak256 = org.bouncycastle.crypto.digests.KeccakDigest(256)
        keccak256.update(messageWithPrefix, 0, messageWithPrefix.size)
        val hashWithPrefix = ByteArray(keccak256.digestSize)
        keccak256.doFinal(hashWithPrefix, 0)

        val ecKeyPair = org.web3j.crypto.ECKeyPair.create(privateKeyBigInt)
        val signature = org.web3j.crypto.Sign.signMessage(hashWithPrefix, ecKeyPair, false)
        val sigHex = "0x" + org.web3j.utils.Numeric.toHexString(signature.r).removePrefix("0x").padStart(64, '0') +
                org.web3j.utils.Numeric.toHexString(signature.s).removePrefix("0x").padStart(64, '0') +
                String.format("%02x", (signature.v as ByteArray).getOrElse(0) { 0 }.toInt() and 0xff)

        val request = BuilderRelayerApi.TransactionRequest(
            type = RELAYER_TYPE_PROXY,
            from = fromAddress,
            to = proxyFactoryAddress,
            proxyWallet = proxyAddress,
            data = proxyCallData,
            nonce = nonce,
            signature = sigHex,
            signatureParams = BuilderRelayerApi.SignatureParams(
                gasPrice = "0",
                gasLimit = gasLimit,
                relayerFee = "0",
                relayHub = relayHubAddress,
                relay = relayAddress
            ),
            metadata = "Redeem positions via Builder Relayer PROXY"
        )

        val response = relayerApi.submitTransaction(request)
        if (!response.isSuccessful || response.body() == null) {
            val errorBody = response.errorBody()?.string() ?: "未知错误"
            logger.error("Builder Relayer PROXY API 调用失败: code=${response.code()}, body=$errorBody")
            return Result.failure(Exception("Builder Relayer PROXY 调用失败: ${response.code()} - $errorBody"))
        }

        val relayerResponse = response.body()!!
        val txHash = relayerResponse.transactionHash ?: relayerResponse.hash
            ?: return Result.failure(Exception("Builder Relayer 返回的交易哈希为空"))
        logger.info("Builder Relayer PROXY 执行成功: transactionID=${relayerResponse.transactionID}, txHash=$txHash")
        return Result.success(txHash)
    }

    /**
     * 编码 ProxyFactory.proxy(calls) 调用数据
     * 参考: builder-relayer-client encode/proxy.ts, abis proxyFactory proxy((uint8,address,uint256,bytes)[])
     * 
     * ABI 编码规则：当 tuple 数组中的 tuple 包含动态类型（bytes）时，需要先存储 tuple offset
     * 结构：
     * - selector (4 bytes)
     * - array offset (32 bytes) = 32
     * - array length (32 bytes) = 1
     * - tuple[0] offset (32 bytes) = 32 (指向 tuple 数据开始，从 array length 之后计算)
     * - tuple[0] 数据：
     *   - typeCode (32 bytes) = 1
     *   - to (32 bytes)
     *   - value (32 bytes) = 0
     *   - data offset (32 bytes) = 128 (从 tuple 数据开始计算)
     *   - data length (32 bytes)
     *   - data (padded to 32-byte boundary)
     */
    private fun encodeProxyTransactionData(safeTx: SafeTransaction): String {
        val selector = EthereumUtils.getFunctionSelector("proxy((uint8,address,uint256,bytes)[])")
        val callData = safeTx.data.removePrefix("0x")
        val dataLen = callData.length / 2
        val dataLenPadded = (dataLen + 31) / 32 * 32 * 2
        val dataPadded = callData.padEnd(dataLenPadded, '0')

        // ABI 编码：tuple 数组，tuple 包含动态类型 bytes
        // 1. array offset: 32 (指向 array length)
        val arrayOffset = EthereumUtils.encodeUint256(BigInteger.valueOf(32))
        // 2. array length: 1
        val arrayLength = EthereumUtils.encodeUint256(BigInteger.ONE)
        // 3. tuple[0] offset: 32 (指向 tuple 数据开始，从 array length 之后计算)
        val tupleOffset = EthereumUtils.encodeUint256(BigInteger.valueOf(32))
        // 4. tuple[0] 数据：
        //    - typeCode: 1
        val typeCode = EthereumUtils.encodeUint256(BigInteger.ONE)
        //    - to: address
        val toEncoded = EthereumUtils.encodeAddress(safeTx.to)
        //    - value: 0
        val valueEncoded = EthereumUtils.encodeUint256(BigInteger.ZERO)
        //    - data offset: 128 (从 tuple 数据开始计算，typeCode+to+value = 3*32 = 96，加上 offset 字段 = 128)
        val dataOffsetInTuple = BigInteger.valueOf(128)
        val dataOffsetEncoded = EthereumUtils.encodeUint256(dataOffsetInTuple)
        //    - data length
        val dataLengthEncoded = EthereumUtils.encodeUint256(BigInteger.valueOf(dataLen.toLong()))
        //    - data (padded)
        
        return "0x" + selector.removePrefix("0x") + arrayOffset + arrayLength +
                tupleOffset + typeCode + toEncoded + valueEncoded + dataOffsetEncoded +
                dataLengthEncoded + dataPadded
    }

    /**
     * 估算 PROXY 交易的 gas limit
     * 参考: builder-relayer-client builder/proxy.ts getGasLimit
     */
    private suspend fun estimateProxyGasLimit(
        from: String,
        to: String,
        data: String
    ): String {
        val rpcApi = polygonRpcApi
        
        val rpcRequest = JsonRpcRequest(
            method = "eth_estimateGas",
            params = listOf(
                mapOf(
                    "from" to from,
                    "to" to to,
                    "data" to data
                )
            )
        )
        
        val response = rpcApi.call(rpcRequest)
        if (!response.isSuccessful || response.body() == null) {
            throw Exception("eth_estimateGas 调用失败: ${response.code()} ${response.message()}")
        }
        
        val rpcResponse = response.body()!!
        if (rpcResponse.error != null) {
            throw Exception("eth_estimateGas 返回错误: ${rpcResponse.error.message}")
        }
        
        val hexGasLimit = rpcResponse.result?.asString
            ?: throw Exception("eth_estimateGas 结果为空")
        
        // 将十六进制转换为十进制字符串
        val gasLimitBigInt = BigInteger(hexGasLimit.removePrefix("0x"), 16)
        return gasLimitBigInt.toString()
    }

    /**
     * 创建 PROXY 结构哈希，参考 builder-relayer-client builder/proxy.ts createStructHash
     * concat: "rlx:" + from + to + data + txFee + gasPrice + gasLimit + nonce + relayHub + relay, then keccak256
     */
    private fun createProxyStructHash(
        from: String,
        to: String,
        data: String,
        txFee: String,
        gasPrice: String,
        gasLimit: String,
        nonce: String,
        relayHubAddress: String,
        relayAddress: String
    ): ByteArray {
        val rlxPrefix = "rlx:".toByteArray(Charsets.UTF_8)
        val fromBytes = EthereumUtils.hexToBytes(from.lowercase().removePrefix("0x").padStart(40, '0'))
        val toBytes = EthereumUtils.hexToBytes(to.lowercase().removePrefix("0x").padStart(40, '0'))
        val dataBytes = EthereumUtils.hexToBytes(data.removePrefix("0x"))
        val txFeeBytes = EthereumUtils.encodeUint256(BigInteger(txFee)).let { EthereumUtils.hexToBytes(it) }
        val gasPriceBytes = EthereumUtils.encodeUint256(BigInteger(gasPrice)).let { EthereumUtils.hexToBytes(it) }
        val gasLimitBytes = EthereumUtils.encodeUint256(BigInteger(gasLimit)).let { EthereumUtils.hexToBytes(it) }
        val nonceBytes = EthereumUtils.encodeUint256(BigInteger(nonce)).let { EthereumUtils.hexToBytes(it) }
        val relayHubBytes = EthereumUtils.hexToBytes(relayHubAddress.lowercase().removePrefix("0x").padStart(40, '0'))
        val relayBytes = EthereumUtils.hexToBytes(relayAddress.lowercase().removePrefix("0x").padStart(40, '0'))

        val concat = rlxPrefix + fromBytes + toBytes + dataBytes + txFeeBytes + gasPriceBytes +
                gasLimitBytes + nonceBytes + relayHubBytes + relayBytes
        return EthereumUtils.keccak256(concat)
    }

    /**
     * 通过 Builder Relayer 执行交易（Gasless）
     * 参考: builder-relayer-client/src/client.ts 的 execute 方法
     */
    private suspend fun executeViaBuilderRelayer(
        privateKey: String,
        proxyAddress: String,
        safeTx: SafeTransaction,
        builderApiKey: String,
        builderSecret: String,
        builderPassphrase: String
    ): Result<String> {
        val relayerApi = retrofitFactory.createBuilderRelayerApi(
            relayerUrl = PolymarketConstants.BUILDER_RELAYER_URL,
            apiKey = builderApiKey,
            secret = builderSecret,
            passphrase = builderPassphrase
        )

        // 从私钥推导实际签名地址（EOA）
        val cleanPrivateKey = privateKey.removePrefix("0x")
        val privateKeyBigInt = BigInteger(cleanPrivateKey, 16)
        val credentials = org.web3j.crypto.Credentials.create(privateKeyBigInt.toString(16))
        val fromAddress = credentials.address

        // safeTx.data 已经是带 0x 前缀的完整调用数据
        val redeemCallData = safeTx.data

        // 获取 Proxy 的 nonce（通过 Builder Relayer API）
        val nonceResponse = relayerApi.getNonce(fromAddress, RELAYER_TYPE_SAFE)
        if (!nonceResponse.isSuccessful || nonceResponse.body() == null) {
            val errorBody = nonceResponse.errorBody()?.string() ?: "未知错误"
            logger.error("获取 nonce 失败: code=${nonceResponse.code()}, body=$errorBody")
            return Result.failure(Exception("获取 nonce 失败: ${nonceResponse.code()} - $errorBody"))
        }
        val proxyNonce = BigInteger(nonceResponse.body()!!.nonce)

        // 构建 Safe 交易哈希并签名
        // 注意：encodeSafeTx 需要 data 带 0x 前缀
        val safeTxGas = BigInteger.ZERO
        val baseGas = BigInteger.ZERO
        val safeGasPrice = BigInteger.ZERO
        val gasToken = "0x0000000000000000000000000000000000000000"
        val refundReceiver = "0x0000000000000000000000000000000000000000"

        val safeDomainSeparator = com.wrbug.polymarketbot.util.Eip712Encoder.encodeSafeDomain(
            chainId = 137L,  // Polygon 主网
            verifyingContract = proxyAddress
        )

        val safeTxHash = com.wrbug.polymarketbot.util.Eip712Encoder.encodeSafeTx(
            to = safeTx.to,
            value = BigInteger.ZERO,
            data = redeemCallData,  // 带 0x 前缀
            operation = safeTx.operation,
            safeTxGas = safeTxGas,
            baseGas = baseGas,
            gasPrice = safeGasPrice,
            gasToken = gasToken,
            refundReceiver = refundReceiver,
            nonce = proxyNonce
        )

        val safeTxStructuredHash = com.wrbug.polymarketbot.util.Eip712Encoder.hashStructuredData(
            domainSeparator = safeDomainSeparator,
            messageHash = safeTxHash
        )

        // 注意：ethers.js 的 signMessage 会添加 EIP-191 前缀
        // 格式：\x19Ethereum Signed Message:\n<length><message>
        // 我们需要模拟这个行为以匹配 TypeScript 实现
        val prefix = "\u0019Ethereum Signed Message:\n${safeTxStructuredHash.size}".toByteArray(Charsets.UTF_8)
        val messageWithPrefix = ByteArray(prefix.size + safeTxStructuredHash.size)
        System.arraycopy(prefix, 0, messageWithPrefix, 0, prefix.size)
        System.arraycopy(safeTxStructuredHash, 0, messageWithPrefix, prefix.size, safeTxStructuredHash.size)

        // 对带前缀的消息进行 keccak256 哈希
        val keccak256 = org.bouncycastle.crypto.digests.KeccakDigest(256)
        keccak256.update(messageWithPrefix, 0, messageWithPrefix.size)
        val hashWithPrefix = ByteArray(keccak256.digestSize)
        keccak256.doFinal(hashWithPrefix, 0)

        val ecKeyPair = org.web3j.crypto.ECKeyPair.create(privateKeyBigInt)
        val safeSignature = org.web3j.crypto.Sign.signMessage(hashWithPrefix, ecKeyPair, false)

        // 打包签名（参考 builder-relayer-client/src/utils/index.ts 的 splitAndPackSig）
        val packedSignature = splitAndPackSig(safeSignature)

        // 调试日志（地址已遮蔽）
        logger.debug("=== Builder Relayer 签名调试 ===")
        logger.debug("Safe: ${proxyAddress.take(10)}..., From: ${fromAddress.take(10)}..., Nonce: $proxyNonce")
        logger.debug("Signature Length: ${packedSignature.length}")

        // 构建 TransactionRequest（参考 builder-relayer-client/src/builder/safe.ts）
        // 注意：根据 TypeScript 实现，data 和 signature 都应该带 0x 前缀
        val request = BuilderRelayerApi.TransactionRequest(
            type = RELAYER_TYPE_SAFE,
            from = fromAddress,
            to = safeTx.to,
            proxyWallet = proxyAddress,
            data = redeemCallData,  // 带 0x 前缀
            nonce = proxyNonce.toString(),
            signature = packedSignature,  // 带 0x 前缀
            signatureParams = BuilderRelayerApi.SignatureParams(
                gasPrice = "0",
                operation = safeTx.operation.toString(),
                safeTxnGas = "0",
                baseGas = "0",
                gasToken = gasToken,
                refundReceiver = refundReceiver
            ),
            metadata = if (safeTx.operation == 1) {
                "MultiSend redeem positions via Builder Relayer"
            } else {
                "Redeem positions via Builder Relayer"
            }
        )

        logger.debug("Request: type=${request.type}, dataLen=${request.data.length}, sigLen=${request.signature.length}, nonce=${request.nonce}")

        // 调用 Builder Relayer API（认证头通过拦截器添加）
        val response = relayerApi.submitTransaction(request)

        if (!response.isSuccessful || response.body() == null) {
            val errorBody = response.errorBody()?.string() ?: "未知错误"
            logger.error("Builder Relayer API 调用失败: code=${response.code()}, body=$errorBody")
            return Result.failure(Exception("Builder Relayer API 调用失败: ${response.code()} - $errorBody"))
        }

        val relayerResponse = response.body()!!
        val txHash = relayerResponse.transactionHash ?: relayerResponse.hash
        ?: return Result.failure(Exception("Builder Relayer 返回的交易哈希为空"))

        logger.info("Builder Relayer 执行成功: transactionID=${relayerResponse.transactionID}, txHash=$txHash")
        return Result.success(txHash)
    }

    /**
     * 打包签名（参考 builder-relayer-client/src/utils/index.ts 的 splitAndPackSig）
     * 将签名打包成 Gnosis Safe 接受的格式：encodePacked(["uint256", "uint256", "uint8"], [r, s, v])
     *
     * TypeScript 实现流程：
     * 1. 从签名字符串中提取 v（最后 2 个字符）
     * 2. 调整 v 值（0,1 -> +31; 27,28 -> +4）
     * 3. 修改签名字符串（替换最后 2 个字符）
     * 4. 从修改后的签名字符串中提取 r, s, v（作为十进制字符串）
     * 5. 使用 encodePacked 打包：uint256(BigInt(r)) + uint256(BigInt(s)) + uint8(parseInt(v))
     *
     * 关键：encodePacked 会将 BigInt 编码为 32 字节（64 个十六进制字符），uint8 编码为 1 字节（2 个十六进制字符）
     */
    private fun splitAndPackSig(signature: org.web3j.crypto.Sign.SignatureData): String {
        // 1. 先将 SignatureData 转换为签名字符串（r + s + v）
        val rHex = org.web3j.utils.Numeric.toHexString(signature.r).removePrefix("0x").padStart(64, '0')
        val sHex = org.web3j.utils.Numeric.toHexString(signature.s).removePrefix("0x").padStart(64, '0')
        val vBytes = signature.v as ByteArray
        val originalV = if (vBytes.isNotEmpty()) {
            vBytes[0].toInt() and 0xff
        } else {
            throw IllegalArgumentException("Signature v is empty")
        }
        val originalVHex = String.format("%02x", originalV)
        val sigString = "0x$rHex$sHex$originalVHex"  // 130 个十六进制字符（65 字节）

        // 2. 从签名字符串中提取 v（最后 2 个字符，作为十六进制）
        val sigV = sigString.substring(sigString.length - 2).toInt(16)

        // 3. 调整 v 值（参考 TypeScript 实现）
        val adjustedV = when (sigV) {
            0, 1 -> sigV + 31
            27, 28 -> sigV + 4
            else -> throw IllegalArgumentException("Invalid signature v value: $sigV")
        }

        // 4. 修改签名字符串（替换最后 2 个字符）
        val modifiedSigString = sigString.substring(0, sigString.length - 2) + String.format("%02x", adjustedV)

        // 5. 从修改后的签名字符串中提取 r, s, v（作为十六进制字符串）
        // modifiedSigString 格式：0x + r(64) + s(64) + v(2) = 132 个字符
        val rHexStr = modifiedSigString.substring(2, 66)  // 64 个字符（十六进制）
        val sHexStr = modifiedSigString.substring(66, 130)  // 64 个字符（十六进制）
        val vHexStr = modifiedSigString.substring(130, 132)  // 2 个字符（十六进制）

        // 6. 转换为 BigInteger 和 Int（模拟 TypeScript 的 BigInt 和 parseInt）
        val rBigInt = BigInteger(rHexStr, 16)
        val sBigInt = BigInteger(sHexStr, 16)
        val vInt = vHexStr.toInt(16)

        // 7. 使用 encodePacked 打包：uint256(r) + uint256(s) + uint8(v)
        // encodePacked 会将 BigInt 编码为 32 字节（64 个十六进制字符），uint8 编码为 1 字节（2 个十六进制字符）
        val rEncoded = EthereumUtils.encodeUint256(rBigInt)  // 64 个十六进制字符
        val sEncoded = EthereumUtils.encodeUint256(sBigInt)  // 64 个十六进制字符
        val vEncoded = String.format("%02x", vInt)  // 2 个十六进制字符

        return "0x$rEncoded$sEncoded$vEncoded"
    }

    /**
     * 手动执行交易（需要用户支付 gas）
     */
    private suspend fun executeManually(
        privateKey: String,
        proxyAddress: String,
        safeTx: SafeTransaction
    ): Result<String> {
        return try {
            val rpcApi = polygonRpcApi

            // 从私钥推导实际签名地址（交易真正的 from 地址）
            val cleanPrivateKey = privateKey.removePrefix("0x")
            val privateKeyBigInt = BigInteger(cleanPrivateKey, 16)
            val credentials = org.web3j.crypto.Credentials.create(privateKeyBigInt.toString(16))
            val fromAddress = credentials.address

            val redeemCallData = safeTx.data.removePrefix("0x")  // 移除 0x 前缀，后续编码需要

            // 获取 Proxy 的 nonce（用于构建 Safe 交易哈希）
            val proxyNonceResult = getProxyNonce(proxyAddress, rpcApi)
            val proxyNonce = proxyNonceResult.getOrElse {
                logger.warn("获取 Proxy nonce 失败，使用 0: ${it.message}")
                BigInteger.ZERO
            }

            // 构建 Safe 交易哈希（用于 EIP-712 签名）
            val safeTxGas = BigInteger.ZERO
            val baseGas = BigInteger.ZERO
            val safeGasPrice = BigInteger.ZERO
            val gasToken = "0x0000000000000000000000000000000000000000"
            val refundReceiver = "0x0000000000000000000000000000000000000000"

            // 1. 编码 Safe 域分隔符
            val safeDomainSeparator = com.wrbug.polymarketbot.util.Eip712Encoder.encodeSafeDomain(
                chainId = 137L,  // Polygon 主网
                verifyingContract = proxyAddress
            )

            // 2. 编码 SafeTx 消息哈希
            val safeTxHash = com.wrbug.polymarketbot.util.Eip712Encoder.encodeSafeTx(
                to = safeTx.to,
                value = BigInteger.ZERO,
                data = redeemCallData,
                operation = safeTx.operation,
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
            // 注意：ethers.js 的 signMessage 会添加 EIP-191 前缀
            // 格式：\x19Ethereum Signed Message:\n<length><message>
            // 我们需要模拟这个行为以匹配 TypeScript 实现
            val prefix = "\u0019Ethereum Signed Message:\n${safeTxStructuredHash.size}".toByteArray(Charsets.UTF_8)
            val messageWithPrefix = ByteArray(prefix.size + safeTxStructuredHash.size)
            System.arraycopy(prefix, 0, messageWithPrefix, 0, prefix.size)
            System.arraycopy(safeTxStructuredHash, 0, messageWithPrefix, prefix.size, safeTxStructuredHash.size)

            // 对带前缀的消息进行 keccak256 哈希
            val keccak256 = org.bouncycastle.crypto.digests.KeccakDigest(256)
            keccak256.update(messageWithPrefix, 0, messageWithPrefix.size)
            val hashWithPrefix = ByteArray(keccak256.digestSize)
            keccak256.doFinal(hashWithPrefix, 0)

            val ecKeyPair = org.web3j.crypto.ECKeyPair.create(privateKeyBigInt)
            val safeSignature = org.web3j.crypto.Sign.signMessage(hashWithPrefix, ecKeyPair, false)

            // 5. 编码签名数据（Gnosis Safe 签名格式：r + s + v，每个 32 字节，共 96 字节）
            val vBytes = safeSignature.v as ByteArray
            val vInt = if (vBytes.isNotEmpty()) {
                vBytes[0].toInt() and 0xff
            } else {
                0
            }

            val rHex = org.web3j.utils.Numeric.toHexString(safeSignature.r).removePrefix("0x").padStart(64, '0')
            val sHex = org.web3j.utils.Numeric.toHexString(safeSignature.s).removePrefix("0x").padStart(64, '0')
            val vHex = String.format("%064x", vInt)
            val safeSignatureHex = rHex + sHex + vHex

            // 6. 构建 execTransaction 调用数据
            val execCallData = buildExecTransactionCallData(safeTx, redeemCallData, safeSignatureHex)

            // 7. 获取 EOA 的 nonce（用于发送交易）
            val nonceResult = getTransactionCount(fromAddress, rpcApi)
            val nonce = nonceResult.getOrElse {
                return Result.failure(Exception("获取 nonce 失败: ${it.message}"))
            }

            // 8. 获取 gas price
            val gasPriceResult = getGasPrice(rpcApi)
            val gasPrice = gasPriceResult.getOrElse {
                return Result.failure(Exception("获取 gas price 失败: ${it.message}"))
            }

            // 9. Gas limit（通过 Proxy 执行需要更多 gas，给 240 万，参考实际交易）
            val gasLimit = BigInteger.valueOf(2400000)

            // 10. 构建并签名交易
            val transaction = buildTransaction(
                privateKey = privateKey,
                from = fromAddress,
                to = proxyAddress,
                data = execCallData,
                nonce = nonce,
                gasLimit = gasLimit,
                gasPrice = gasPrice
            )

            // 11. 发送交易
            sendTransaction(rpcApi, transaction)
        } catch (e: Exception) {
            logger.error("手动执行 Safe 交易失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 构建 execTransaction 调用数据
     */
    private fun buildExecTransactionCallData(
        safeTx: SafeTransaction,
        redeemCallData: String,
        safeSignatureHex: String
    ): String {
        val execFunctionSelector =
            EthereumUtils.getFunctionSelector("execTransaction(address,uint256,bytes,uint8,uint256,uint256,uint256,address,address,bytes)")

        val encodedTo = EthereumUtils.encodeAddress(safeTx.to)
        val encodedValue = EthereumUtils.encodeUint256(BigInteger.ZERO)

        val dataOffset = BigInteger.valueOf(320L)
        val redeemCallDataHex = redeemCallData.removePrefix("0x")
        val dataLengthBytes = BigInteger.valueOf((redeemCallDataHex.length / 2).toLong())
        val encodedDataOffset = EthereumUtils.encodeUint256(dataOffset)
        val encodedDataLength = EthereumUtils.encodeUint256(dataLengthBytes)
        val dataPaddedLength = ((dataLengthBytes.toInt() + 31) / 32) * 32 * 2
        val encodedData = redeemCallDataHex.padEnd(dataPaddedLength, '0')

        val encodedOperation = EthereumUtils.encodeUint256(BigInteger.valueOf(safeTx.operation.toLong()))
        val encodedSafeTxGas = EthereumUtils.encodeUint256(BigInteger.ZERO)
        val encodedBaseGas = EthereumUtils.encodeUint256(BigInteger.ZERO)
        val encodedGasPrice = EthereumUtils.encodeUint256(BigInteger.ZERO)
        val encodedGasToken = EthereumUtils.encodeAddress("0x0000000000000000000000000000000000000000")
        val encodedRefundReceiver = EthereumUtils.encodeAddress("0x0000000000000000000000000000000000000000")

        val dataPaddedBytes = dataPaddedLength / 2
        val signaturesOffset = BigInteger.valueOf((320 + dataPaddedBytes).toLong())
        val signaturesLength = BigInteger.valueOf(96L)
        val encodedSignaturesOffset = EthereumUtils.encodeUint256(signaturesOffset)
        val encodedSignaturesLength = EthereumUtils.encodeUint256(signaturesLength)
        val encodedSignatures = safeSignatureHex

        return "0x" + execFunctionSelector.removePrefix("0x") +
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
    }

    /**
     * 获取代理钱包的 nonce（用于构建 Safe 交易）
     */
    private suspend fun getProxyNonce(proxyAddress: String, rpcApi: EthereumRpcApi): Result<BigInteger> {
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
        val nonce = EthereumUtils.decodeUint256(hexNonce.asString)
        return Result.success(nonce)
    }

    /**
     * 获取交易 nonce
     */
    private suspend fun getTransactionCount(address: String, rpcApi: EthereumRpcApi): Result<BigInteger> {
        val rpcRequest = JsonRpcRequest(
            method = "eth_getTransactionCount",
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
        val nonce = EthereumUtils.decodeUint256(hexNonce.asString)
        return Result.success(nonce)
    }

    /**
     * 获取 gas price
     */
    private suspend fun getGasPrice(rpcApi: EthereumRpcApi): Result<BigInteger> {
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
        val gasPrice = EthereumUtils.decodeUint256(hexGasPrice.asString)
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
        val cleanPrivateKey = privateKey.removePrefix("0x")
        val privateKeyBigInt = BigInteger(cleanPrivateKey, 16)
        val credentials = org.web3j.crypto.Credentials.create(privateKeyBigInt.toString(16))

        val rawTransaction = org.web3j.crypto.RawTransaction.createTransaction(
            nonce,
            gasPrice,
            gasLimit,
            to,
            data
        )

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
            "chainId" to "0x89",
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
        return Result.success(txHash.asString)
    }

    /**
     * 批量执行 Safe 交易
     * 参考 TypeScript: RelayClient.execute() 支持批量交易
     *
     * @param privateKey 私钥
     * @param proxyAddress 代理钱包地址
     * @param safeTxs Safe 交易列表
     * @return 交易哈希
     */
    suspend fun executeBatch(
        privateKey: String,
        proxyAddress: String,
        safeTxs: List<SafeTransaction>
    ): Result<String> {
        // 批量执行：将多个交易合并为一个 execTransaction 调用
        // 当前实现：委托给 com.wrbug.polymarketbot.service.common.BlockchainService
        return Result.failure(
            UnsupportedOperationException(
                "批量 Gasless 执行暂未实现。请使用 com.wrbug.polymarketbot.service.common.BlockchainService.redeemPositions() 方法。"
            )
        )
    }
}

