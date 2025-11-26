package com.wrbug.polymarketbot.util

import org.slf4j.LoggerFactory
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Sign
import org.web3j.utils.Numeric
import java.math.BigInteger

/**
 * EIP-712 签名工具类
 * 用于创建 Polymarket CLOB API 的 L1 认证签名
 * 
 * 手动实现 EIP-712 编码，避免 web3j StructuredDataEncoder 的 verifyingContract 问题
 * 参考 clob-client/src/signing/eip712.ts 实现
 */
object Eip712Signer {
    
    private val logger = LoggerFactory.getLogger(Eip712Signer::class.java)
    
    /**
     * ClobAuthDomain 的 EIP-712 域定义
     */
    private const val DOMAIN_NAME = "ClobAuthDomain"
    private const val DOMAIN_VERSION = "1"
    private const val MESSAGE_TO_SIGN = "This message attests that I control the given wallet"  // 根据 clob-client 实现
    
    /**
     * 构建 ClobAuth EIP-712 签名
     * 
     * @param privateKey 私钥（十六进制字符串，带或不带 0x 前缀）
     * @param chainId 链 ID（Polygon 主网是 137）
     * @param timestamp 时间戳（秒）
     * @param nonce 随机数（默认 0）
     * @return EIP-712 签名字符串
     */
    fun buildClobEip712Signature(
        privateKey: String,
        chainId: Long,
        timestamp: Long,
        nonce: Long = 0
    ): String {
        try {
            // 从私钥创建 BigInteger
            val cleanPrivateKey = privateKey.removePrefix("0x")
            val privateKeyBigInt = BigInteger(cleanPrivateKey, 16)
            
            // 从私钥推导地址（用于消息中的 address 字段）
            val credentials = org.web3j.crypto.Credentials.create(privateKeyBigInt.toString(16))
            val address = credentials.address
            
            // 使用手动实现的 EIP-712 编码器
            // 1. 编码域分隔符
            val domainSeparator = Eip712Encoder.encodeDomain(
                name = DOMAIN_NAME,
                version = DOMAIN_VERSION,
                chainId = chainId
            )
            
            // 2. 编码消息哈希
            val messageHash = Eip712Encoder.encodeMessage(
                address = address,
                timestamp = timestamp.toString(),
                nonce = BigInteger.valueOf(nonce),
                message = MESSAGE_TO_SIGN
            )
            
            // 3. 计算完整的结构化数据哈希
            val structuredHash = Eip712Encoder.hashStructuredData(domainSeparator, messageHash)
            
            // 4. 使用私钥签名
            val ecKeyPair = ECKeyPair.create(privateKeyBigInt)
            val signature = Sign.signMessage(structuredHash, ecKeyPair, false)
            
            // 5. 组合签名（r + s + v）
            // 在 web3j 5.0 中，signature.r 和 signature.s 是 BigInteger 类型
            // signature.v 在 web3j 5.0 中仍然是 ByteArray 类型
            val rHex = Numeric.toHexString(signature.r).removePrefix("0x").padStart(64, '0')
            val sHex = Numeric.toHexString(signature.s).removePrefix("0x").padStart(64, '0')
            val vBytes = signature.v as ByteArray
            val vInt = if (vBytes.isNotEmpty()) {
                vBytes[0].toInt() and 0xff
            } else {
                0
            }
            val vHex = String.format("%02x", vInt)
            
            return "0x$rHex$sHex$vHex"
        } catch (e: Exception) {
            logger.error("EIP-712 签名失败", e)
            throw RuntimeException("EIP-712 签名失败: ${e.message}", e)
        }
    }
}

