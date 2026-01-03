package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.api.BuilderRelayerApi
import com.wrbug.polymarketbot.api.EthereumRpcApi
import com.wrbug.polymarketbot.api.JsonRpcRequest
import com.wrbug.polymarketbot.util.EthereumUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.createClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
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
 * - TypeScript: @polymarket/builder-relayer-client
 * - TypeScript: utils/redeem.ts
 */
@Service
class RelayClientService(
    @Value("\${polymarket.builder.relayer-url:}")
    private val builderRelayerUrl: String,
    private val retrofitFactory: RetrofitFactory,
    private val systemConfigService: SystemConfigService,
    private val rpcNodeService: RpcNodeService
) {

    private val logger = LoggerFactory.getLogger(RelayClientService::class.java)

    // ConditionalTokens 合约地址
    private val conditionalTokensAddress = "0x4D97DCd97eC945f40cF65F87097ACe5EA0476045"

    // USDC.e 合约地址
    private val usdcContractAddress = "0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174"

    // 空集合ID
    private val EMPTY_SET = "0x0000000000000000000000000000000000000000000000000000000000000000"

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
                relayerUrl = builderRelayerUrl,
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
        return builderRelayerUrl.isNotBlank() &&
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
                relayerUrl = builderRelayerUrl,
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
     *
     * @param conditionId 市场条件ID
     * @param indexSets 索引集合列表（每个元素是 2^outcomeIndex）
     * @return Safe 交易对象
     */
    fun createRedeemTx(conditionId: String, indexSets: List<BigInteger>): SafeTransaction {
        // 编码 redeemPositions 函数调用
        val functionSelector = EthereumUtils.getFunctionSelector(
            "redeemPositions(address,bytes32,bytes32,uint256[])"
        )

        // 编码参数
        val encodedCollateral = EthereumUtils.encodeAddress(usdcContractAddress)
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
     * 执行 Safe 交易（通过 Proxy.execTransaction）
     * 参考 TypeScript: RelayClient.execute()
     *
     * 优先使用 Builder Relayer（Gasless），如果未配置则回退到手动发送交易
     *
     * @param privateKey 私钥
     * @param proxyAddress 代理钱包地址
     * @param safeTx Safe 交易对象
     * @return 交易哈希
     */
    suspend fun execute(
        privateKey: String,
        proxyAddress: String,
        safeTx: SafeTransaction
    ): Result<String> {
        return try {
            // 验证参数
            if (proxyAddress.isBlank() || !proxyAddress.startsWith("0x") || proxyAddress.length != 42) {
                return Result.failure(IllegalArgumentException("proxyAddress 格式错误，必须是有效的以太坊地址"))
            }

            // 检查 Builder API Key 是否已配置
            val builderApiKey = systemConfigService.getBuilderApiKey()
            val builderSecret = systemConfigService.getBuilderSecret()
            val builderPassphrase = systemConfigService.getBuilderPassphrase()
            
            // 优先使用 Builder Relayer（Gasless）
            if (isBuilderRelayerEnabled(builderApiKey, builderSecret, builderPassphrase)) {
                logger.info("使用 Builder Relayer 执行 Gasless 交易")
                return executeViaBuilderRelayer(privateKey, proxyAddress, safeTx, builderApiKey!!, builderSecret!!, builderPassphrase!!)
            }

            // 回退到手动发送交易（需要用户支付 gas）
            logger.info("Builder Relayer 未配置，使用手动发送交易（需要用户支付 gas）")
            return executeManually(privateKey, proxyAddress, safeTx)
        } catch (e: Exception) {
            logger.error("执行 Safe 交易失败: ${e.message}", e)
            Result.failure(e)
        }
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
        val rpcApi = polygonRpcApi
        val relayerApi = retrofitFactory.createBuilderRelayerApi(
            relayerUrl = builderRelayerUrl,
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
        val nonceResponse = relayerApi.getNonce(fromAddress, "SAFE")
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
            type = "SAFE",
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
            metadata = "Redeem positions via Builder Relayer"
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
        val execFunctionSelector = EthereumUtils.getFunctionSelector("execTransaction(address,uint256,bytes,uint8,uint256,uint256,uint256,address,address,bytes)")

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

