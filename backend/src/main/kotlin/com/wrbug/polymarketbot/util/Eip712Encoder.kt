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
    
    /**
     * 编码 ExchangeOrder 域分隔符
     * 参考: @polymarket/order-utils 的 ExchangeOrderBuilder
     * Domain: { name: "Polymarket CTF Exchange", version: "1", chainId: chainId, verifyingContract: exchangeContract }
     */
    fun encodeExchangeDomain(
        chainId: Long,
        verifyingContract: String
    ): ByteArray {
        val domainTypeHash = encodeType(
            "EIP712Domain",
            listOf(
                "name" to "string",
                "version" to "string",
                "chainId" to "uint256",
                "verifyingContract" to "address"
            )
        )
        
        val nameHash = encodeString("Polymarket CTF Exchange")
        val versionHash = encodeString("1")
        val chainIdBytes = encodeUint256(BigInteger.valueOf(chainId))
        val contractBytes = encodeAddress(verifyingContract)
        
        val encoded = ByteArray(32 + 32 + 32 + 32 + 32)
        System.arraycopy(domainTypeHash, 0, encoded, 0, 32)
        System.arraycopy(nameHash, 0, encoded, 32, 32)
        System.arraycopy(versionHash, 0, encoded, 64, 32)
        System.arraycopy(chainIdBytes, 0, encoded, 96, 32)
        System.arraycopy(contractBytes, 0, encoded, 128, 32)
        
        return keccak256(encoded)
    }
    
    /**
     * 编码 ExchangeOrder 消息哈希
     * 参考: @polymarket/order-utils 的 ExchangeOrderBuilder
     * Order: { salt, maker, signer, taker, tokenId, makerAmount, takerAmount, expiration, nonce, feeRateBps, side, signatureType }
     */
    fun encodeExchangeOrder(
        salt: Long,
        maker: String,
        signer: String,
        taker: String,
        tokenId: String,
        makerAmount: String,
        takerAmount: String,
        expiration: String,
        nonce: String,
        feeRateBps: String,
        side: String,
        signatureType: Int
    ): ByteArray {
        val orderTypeHash = encodeType(
            "Order",
            listOf(
                "salt" to "uint256",
                "maker" to "address",
                "signer" to "address",
                "taker" to "address",
                "tokenId" to "uint256",
                "makerAmount" to "uint256",
                "takerAmount" to "uint256",
                "expiration" to "uint256",
                "nonce" to "uint256",
                "feeRateBps" to "uint256",
                "side" to "uint8",
                "signatureType" to "uint8"
            )
        )
        
        // 编码订单字段
        val saltBytes = encodeUint256(BigInteger.valueOf(salt))
        val makerBytes = encodeAddress(maker)
        val signerBytes = encodeAddress(signer)
        val takerBytes = encodeAddress(taker)
        val tokenIdBytes = encodeUint256(BigInteger(tokenId))
        val makerAmountBytes = encodeUint256(BigInteger(makerAmount))
        val takerAmountBytes = encodeUint256(BigInteger(takerAmount))
        val expirationBytes = encodeUint256(BigInteger(expiration))
        val nonceBytes = encodeUint256(BigInteger(nonce))
        val feeRateBpsBytes = encodeUint256(BigInteger(feeRateBps))
        
        // side: BUY = 0, SELL = 1 (uint8，但需要编码为 32 字节)
        val sideValue = when (side.uppercase()) {
            "BUY" -> 0
            "SELL" -> 1
            else -> throw IllegalArgumentException("side 必须是 BUY 或 SELL")
        }
        // uint8 类型，但 EIP-712 编码时仍需要 32 字节
        val sideBytes = encodeUint256(BigInteger.valueOf(sideValue.toLong()))
        val signatureTypeBytes = encodeUint256(BigInteger.valueOf(signatureType.toLong()))
        
        // 组合所有字段
        val encoded = ByteArray(32 * 13)  // 13 个字段，每个 32 字节
        var offset = 0
        System.arraycopy(orderTypeHash, 0, encoded, offset, 32); offset += 32
        System.arraycopy(saltBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(makerBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(signerBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(takerBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(tokenIdBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(makerAmountBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(takerAmountBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(expirationBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(nonceBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(feeRateBpsBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(sideBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(signatureTypeBytes, 0, encoded, offset, 32)
        
        return keccak256(encoded)
    }
    
    /**
     * 编码 Gnosis Safe 域分隔符
     * Domain: { chainId: uint256, verifyingContract: address }
     * 参考: builder-relayer-client/src/builder/safe.ts 的 createStructHash
     * 注意：TypeScript 的 domain 包含 chainId 和 verifyingContract
     */
    fun encodeSafeDomain(
        chainId: Long,
        verifyingContract: String
    ): ByteArray {
        val domainTypeHash = encodeType(
            "EIP712Domain",
            listOf(
                "chainId" to "uint256",
                "verifyingContract" to "address"
            )
        )
        
        val chainIdBytes = encodeUint256(BigInteger.valueOf(chainId))
        val contractBytes = encodeAddress(verifyingContract)
        
        val encoded = ByteArray(32 + 32 + 32)
        System.arraycopy(domainTypeHash, 0, encoded, 0, 32)
        System.arraycopy(chainIdBytes, 0, encoded, 32, 32)
        System.arraycopy(contractBytes, 0, encoded, 64, 32)
        
        return keccak256(encoded)
    }
    
    /**
     * 编码 Gnosis Safe SafeTx 消息哈希
     * SafeTx: { to, value, data, operation, safeTxGas, baseGas, gasPrice, gasToken, refundReceiver, nonce }
     * 参考: Gnosis Safe 合约的 SafeTx 结构
     */
    fun encodeSafeTx(
        to: String,
        value: BigInteger,
        data: String,
        operation: Int, // 0 = CALL, 1 = DELEGATECALL
        safeTxGas: BigInteger,
        baseGas: BigInteger,
        gasPrice: BigInteger,
        gasToken: String,
        refundReceiver: String,
        nonce: BigInteger
    ): ByteArray {
        val safeTxTypeHash = encodeType(
            "SafeTx",
            listOf(
                "to" to "address",
                "value" to "uint256",
                "data" to "bytes",
                "operation" to "uint8",
                "safeTxGas" to "uint256",
                "baseGas" to "uint256",
                "gasPrice" to "uint256",
                "gasToken" to "address",
                "refundReceiver" to "address",
                "nonce" to "uint256"
            )
        )
        
        // 编码字段
        val toBytes = encodeAddress(to)
        val valueBytes = encodeUint256(value)
        // data 是 bytes 类型，需要先计算 keccak256 哈希
        val dataBytes = if (data.isBlank() || data == "0x") {
            ByteArray(32) // 空 bytes 的哈希
        } else {
            val cleanData = data.removePrefix("0x")
            val dataByteArray = Numeric.hexStringToByteArray("0x$cleanData")
            keccak256(dataByteArray)
        }
        val operationBytes = encodeUint256(BigInteger.valueOf(operation.toLong()))
        val safeTxGasBytes = encodeUint256(safeTxGas)
        val baseGasBytes = encodeUint256(baseGas)
        val gasPriceBytes = encodeUint256(gasPrice)
        val gasTokenBytes = encodeAddress(gasToken)
        val refundReceiverBytes = encodeAddress(refundReceiver)
        val nonceBytes = encodeUint256(nonce)
        
        // 组合所有字段
        val encoded = ByteArray(32 * 11)  // 11 个字段，每个 32 字节
        var offset = 0
        System.arraycopy(safeTxTypeHash, 0, encoded, offset, 32); offset += 32
        System.arraycopy(toBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(valueBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(dataBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(operationBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(safeTxGasBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(baseGasBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(gasPriceBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(gasTokenBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(refundReceiverBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(nonceBytes, 0, encoded, offset, 32)
        
        return keccak256(encoded)
    }

    /**
     * SafeCreate 用 EIP712 域（Polymarket Contract Proxy Factory）
     * Domain: EIP712Domain(string name, uint256 chainId, address verifyingContract)
     * 参考: builder-relayer-client/src/builder/create.ts createSafeCreateSignature
     */
    fun encodeSafeCreateDomain(
        name: String,
        chainId: Long,
        verifyingContract: String
    ): ByteArray {
        val domainTypeHash = encodeType(
            "EIP712Domain",
            listOf(
                "name" to "string",
                "chainId" to "uint256",
                "verifyingContract" to "address"
            )
        )
        val nameHash = encodeString(name)
        val chainIdBytes = encodeUint256(BigInteger.valueOf(chainId))
        val contractBytes = encodeAddress(verifyingContract)
        val encoded = ByteArray(32 + 32 + 32 + 32)
        System.arraycopy(domainTypeHash, 0, encoded, 0, 32)
        System.arraycopy(nameHash, 0, encoded, 32, 32)
        System.arraycopy(chainIdBytes, 0, encoded, 64, 32)
        System.arraycopy(contractBytes, 0, encoded, 96, 32)
        return keccak256(encoded)
    }

    /**
     * CreateProxy 消息哈希（SafeCreate 签名用）
     * CreateProxy(address paymentToken, uint256 payment, address paymentReceiver)
     */
    fun encodeCreateProxyMessage(
        paymentToken: String,
        payment: BigInteger,
        paymentReceiver: String
    ): ByteArray {
        val typeHash = encodeType(
            "CreateProxy",
            listOf(
                "paymentToken" to "address",
                "payment" to "uint256",
                "paymentReceiver" to "address"
            )
        )
        val tokenBytes = encodeAddress(paymentToken)
        val paymentBytes = encodeUint256(payment)
        val receiverBytes = encodeAddress(paymentReceiver)
        val encoded = ByteArray(32 + 32 + 32 + 32)
        System.arraycopy(typeHash, 0, encoded, 0, 32)
        System.arraycopy(tokenBytes, 0, encoded, 32, 32)
        System.arraycopy(paymentBytes, 0, encoded, 64, 32)
        System.arraycopy(receiverBytes, 0, encoded, 96, 32)
        return keccak256(encoded)
    }
}

