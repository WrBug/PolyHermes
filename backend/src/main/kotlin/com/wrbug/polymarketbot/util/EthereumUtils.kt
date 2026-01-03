package com.wrbug.polymarketbot.util

import org.bouncycastle.crypto.digests.KeccakDigest
import java.math.BigInteger

/**
 * Ethereum 工具类
 * 用于计算函数签名、编码参数等
 */
object EthereumUtils {
    
    // Polymarket 合约地址（Polygon 主网）
    private val COLLATERAL_TOKEN_ADDRESS = "0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174" // USDC
    private val CONDITIONAL_TOKENS_ADDRESS = "0x4D97DCd97eC945f40cF65F87097ACe5EA0476045" // ConditionalTokens
    
    /**
     * 计算函数选择器（前4个字节）
     * @param functionSignature 函数签名，例如 "computeProxyAddress(address)"
     * @return 函数选择器，例如 "0x12345678"
     */
    fun getFunctionSelector(functionSignature: String): String {
        val hash = keccak256Hex(functionSignature.toByteArray())
        return "0x" + hash.substring(0, 8)
    }
    
    /**
     * 编码地址参数（32字节，左对齐）
     * @param address 地址，例如 "0x1234..."
     * @return 编码后的地址，64个十六进制字符
     */
    fun encodeAddress(address: String): String {
        val cleanAddress = address.removePrefix("0x").lowercase()
        return cleanAddress.padStart(64, '0')
    }
    
    /**
     * 编码 uint256 参数
     * @param value 数值
     * @return 编码后的值，64个十六进制字符
     */
    fun encodeUint256(value: BigInteger): String {
        return value.toString(16).padStart(64, '0')
    }
    
    /**
     * 编码 bytes32 参数
     * @param value 32字节的十六进制字符串（带或不带0x前缀）
     * @return 编码后的值，64个十六进制字符
     */
    fun encodeBytes32(value: String): String {
        val cleanValue = value.removePrefix("0x")
        if (cleanValue.length != 64) {
            throw IllegalArgumentException("bytes32 值必须是64个十六进制字符")
        }
        return cleanValue.lowercase()
    }
    
    /**
     * 从合约调用结果中解析地址
     * @param hexResult 十六进制结果
     * @return 地址字符串
     */
    fun decodeAddress(hexResult: String): String {
        val cleanHex = hexResult.removePrefix("0x")
        // 地址是最后20字节（40个十六进制字符）
        val addressHex = cleanHex.takeLast(40)
        return "0x$addressHex"
    }
    
    /**
     * 从合约调用结果中解析 uint256
     * @param hexResult 十六进制结果
     * @return BigInteger 值
     */
    fun decodeUint256(hexResult: String): BigInteger {
        val cleanHex = hexResult.removePrefix("0x")
        return BigInteger(cleanHex, 16)
    }
    
    /**
     * 从 ABI 编码的响应中解析 uint256 数组
     * ABI 编码格式：
     * - offset (32 bytes): 数组数据的位置偏移量
     * - length (32 bytes): 数组长度
     * - data: 每个元素 32 字节
     * @param hexResult 十六进制结果（完整的 ABI 编码响应）
     * @param offset 数组数据的偏移位置（字节数，从 offset 位置开始读取）
     * @return BigInteger 数组
     */
    fun decodeUint256Array(hexResult: String, offset: Int = 0): List<BigInteger> {
        val cleanHex = hexResult.removePrefix("0x")
        if (cleanHex.length < (offset + 1) * 64) {
            return emptyList()
        }
        
        // 从 offset 位置开始读取
        val startPos = offset * 64  // 每个 uint256 是 64 个十六进制字符
        val lengthHex = cleanHex.substring(startPos, startPos + 64)
        val length = BigInteger(lengthHex, 16).toInt()
        
        if (length <= 0 || length > 100) {  // 防止异常数据
            return emptyList()
        }
        
        val result = mutableListOf<BigInteger>()
        for (i in 0 until length) {
            val elementStart = startPos + 64 + (i * 64)  // 跳过长度字段
            if (elementStart + 64 > cleanHex.length) {
                break
            }
            val elementHex = cleanHex.substring(elementStart, elementStart + 64)
            result.add(BigInteger(elementHex, 16))
        }
        
        return result
    }
    
    /**
     * 从 ABI 编码的元组响应中解析数据
     * 用于解析 getCondition 返回的 (uint256 payoutDenominator, uint256[] payouts)
     * @param hexResult 十六进制结果
     * @return Pair<payoutDenominator, payouts>
     */
    fun decodeConditionResult(hexResult: String): Pair<BigInteger, List<BigInteger>> {
        val cleanHex = hexResult.removePrefix("0x")
        
        // 第一个 32 字节：payoutDenominator
        val payoutDenominatorHex = cleanHex.substring(0, 64)
        val payoutDenominator = BigInteger(payoutDenominatorHex, 16)
        
        // 第二个 32 字节：payouts 数组的偏移量（通常是 0x40 = 64 字节）
        val offsetHex = cleanHex.substring(64, 128)
        val offset = BigInteger(offsetHex, 16).toInt() / 32  // 转换为 32 字节单位
        
        // 从 offset 位置解析数组
        val payouts = decodeUint256Array(hexResult, offset)
        
        return Pair(payoutDenominator, payouts)
    }
    
    /**
     * 将十六进制字符串转换为字节数组
     * @param hex 十六进制字符串（带或不带 0x 前缀）
     * @return 字节数组
     */
    fun hexToBytes(hex: String): ByteArray {
        val cleanHex = hex.removePrefix("0x")
        return ByteArray(cleanHex.length / 2) { i ->
            cleanHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /**
     * 将字节数组转换为十六进制字符串
     * @param bytes 字节数组
     * @return 十六进制字符串（带 0x 前缀）
     */
    fun bytesToHex(bytes: ByteArray): String {
        return "0x" + bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * 计算 Keccak-256 哈希（Ethereum 标准）
     * 使用 BouncyCastle 库实现真正的 Keccak-256
     * @param data 输入数据
     * @return 32 字节的哈希值
     */
    fun keccak256(data: ByteArray): ByteArray {
        val digest = KeccakDigest(256)
        digest.update(data, 0, data.size)
        val hash = ByteArray(digest.digestSize)
        digest.doFinal(hash, 0)
        return hash
    }

    /**
     * 计算 Keccak-256 哈希并返回十六进制字符串
     * @param data 输入数据
     * @return 十六进制哈希字符串
     */
    fun keccak256Hex(data: ByteArray): String {
        return keccak256(data).joinToString("") { "%02x".format(it) }
    }
}

