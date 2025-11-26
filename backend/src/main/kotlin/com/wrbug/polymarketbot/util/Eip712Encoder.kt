package com.wrbug.polymarketbot.util

import org.bouncycastle.crypto.digests.KeccakDigest
import org.web3j.utils.Numeric
import java.math.BigInteger
import java.nio.charset.StandardCharsets

/**
 * EIP-712 编码工具类
 * 手动实现 EIP-712 编码，避免 web3j StructuredDataEncoder 的 verifyingContract 问题
 * 
 * 参考 EIP-712 标准：https://eips.ethereum.org/EIPS/eip-712
 */
object Eip712Encoder {
    
    /**
     * Keccak-256 哈希
     */
    private fun keccak256(data: ByteArray): ByteArray {
        val digest = KeccakDigest(256)
        digest.update(data, 0, data.size)
        val hash = ByteArray(digest.digestSize)
        digest.doFinal(hash, 0)
        return hash
    }
    
    /**
     * 编码字符串类型
     */
    private fun encodeString(value: String): ByteArray {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        return keccak256(bytes)
    }
    
    /**
     * 编码地址类型（20 字节，左对齐到 32 字节）
     */
    private fun encodeAddress(address: String): ByteArray {
        val cleanAddress = address.removePrefix("0x").lowercase()
        val addressBytes = Numeric.hexStringToByteArray("0x$cleanAddress")
        // 地址是 20 字节，需要左对齐到 32 字节
        return ByteArray(32).apply {
            System.arraycopy(addressBytes, 0, this, 12, addressBytes.size)
        }
    }
    
    /**
     * 编码 uint256 类型（32 字节，大端序）
     */
    private fun encodeUint256(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        val result = ByteArray(32)
        if (bytes.size <= 32) {
            // 左对齐
            System.arraycopy(bytes, 0, result, 32 - bytes.size, bytes.size)
        } else {
            // 如果超过 32 字节，取最后 32 字节
            System.arraycopy(bytes, bytes.size - 32, result, 0, 32)
        }
        return result
    }
    
    /**
     * 编码类型哈希（Type Hash）
     * 例如：encodeType("EIP712Domain", listOf("name", "version", "chainId"))
     */
    private fun encodeType(typeName: String, fields: List<Pair<String, String>>): ByteArray {
        val typeString = buildString {
            append(typeName)
            append("(")
            fields.forEachIndexed { index, (name, type) ->
                if (index > 0) append(",")
                append(type)
                append(" ")
                append(name)
            }
            append(")")
        }
        return keccak256(typeString.toByteArray(StandardCharsets.UTF_8))
    }
    
    /**
     * 编码域分隔符（Domain Separator）
     */
    fun encodeDomain(
        name: String,
        version: String,
        chainId: Long
    ): ByteArray {
        // EIP712Domain 类型定义（不包含 verifyingContract）
        val domainTypeHash = encodeType(
            "EIP712Domain",
            listOf(
                "name" to "string",
                "version" to "string",
                "chainId" to "uint256"
            )
        )
        
        // 编码域字段
        val nameHash = encodeString(name)
        val versionHash = encodeString(version)
        val chainIdBytes = encodeUint256(BigInteger.valueOf(chainId))
        
        // 组合：keccak256(domainTypeHash || nameHash || versionHash || chainIdBytes)
        val encoded = ByteArray(32 + 32 + 32 + 32)
        System.arraycopy(domainTypeHash, 0, encoded, 0, 32)
        System.arraycopy(nameHash, 0, encoded, 32, 32)
        System.arraycopy(versionHash, 0, encoded, 64, 32)
        System.arraycopy(chainIdBytes, 0, encoded, 96, 32)
        
        return keccak256(encoded)
    }
    
    /**
     * 编码消息哈希（Message Hash）
     */
    fun encodeMessage(
        address: String,
        timestamp: String,
        nonce: BigInteger,
        message: String
    ): ByteArray {
        // ClobAuth 类型定义
        val clobAuthTypeHash = encodeType(
            "ClobAuth",
            listOf(
                "address" to "address",
                "timestamp" to "string",
                "nonce" to "uint256",
                "message" to "string"
            )
        )
        
        // 编码消息字段
        val addressBytes = encodeAddress(address)
        val timestampHash = encodeString(timestamp)
        val nonceBytes = encodeUint256(nonce)
        val messageHash = encodeString(message)
        
        // 组合：keccak256(clobAuthTypeHash || addressBytes || timestampHash || nonceBytes || messageHash)
        val encoded = ByteArray(32 + 32 + 32 + 32 + 32)
        System.arraycopy(clobAuthTypeHash, 0, encoded, 0, 32)
        System.arraycopy(addressBytes, 0, encoded, 32, 32)
        System.arraycopy(timestampHash, 0, encoded, 64, 32)
        System.arraycopy(nonceBytes, 0, encoded, 96, 32)
        System.arraycopy(messageHash, 0, encoded, 128, 32)
        
        return keccak256(encoded)
    }
    
    /**
     * 计算完整的结构化数据哈希
     * hash = keccak256("\x19\x01" || domainSeparator || messageHash)
     */
    fun hashStructuredData(
        domainSeparator: ByteArray,
        messageHash: ByteArray
    ): ByteArray {
        val prefix = byteArrayOf(0x19.toByte(), 0x01.toByte())
        val encoded = ByteArray(prefix.size + domainSeparator.size + messageHash.size)
        System.arraycopy(prefix, 0, encoded, 0, prefix.size)
        System.arraycopy(domainSeparator, 0, encoded, prefix.size, domainSeparator.size)
        System.arraycopy(messageHash, 0, encoded, prefix.size + domainSeparator.size, messageHash.size)
        
        return keccak256(encoded)
    }
}

