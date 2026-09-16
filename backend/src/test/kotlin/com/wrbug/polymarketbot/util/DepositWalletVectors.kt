package com.wrbug.polymarketbot.util

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.web3j.utils.Numeric

/**
 * 从 test resources 读取 Deposit Wallet 参考向量。
 * 向量由官方 ts-sdk（packages/client/src/exchange.ts、wallet.ts）+ viem 生成，
 * 用于验证 Kotlin 实现与官方 SDK 字节级一致。
 */
object DepositWalletVectors {

    val root: JsonObject by lazy {
        val stream = DepositWalletVectors::class.java.getResourceAsStream("/deposit-wallet-vectors.json")
            ?: error("缺少 deposit-wallet-vectors.json")
        stream.bufferedReader().use { JsonParser.parseString(it.readText()).asJsonObject }
    }

    val testPrivateKey: String get() = root["testKey"].asString
    val signer: String get() = root["signer"].asString
    val chainId: Long get() = root["chainId"].asLong

    fun derivation(signer: String): JsonObject = root["derivation"].asJsonObject[signer.lowercase()].asJsonObject

    fun order(exchangeName: String): JsonObject = root["orders"].asJsonObject[exchangeName].asJsonObject

    fun batch(name: String): JsonObject = root["batches"].asJsonObject[name].asJsonObject

    fun hex(value: String): ByteArray = Numeric.hexStringToByteArray(value)

    fun toHex(bytes: ByteArray): String = Numeric.toHexString(bytes)
}
