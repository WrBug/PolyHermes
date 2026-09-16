package com.wrbug.polymarketbot.util

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Sign
import org.web3j.utils.Numeric
import java.math.BigInteger

/**
 * Deposit Wallet 相关 EIP-712 编码与官方 ts-sdk 生成的向量逐字节对比：
 * - CTF Exchange 域分隔符、hashStruct(Order)、TypedDataSign、ERC-7739 最终签名
 * - DepositWallet 域分隔符、Batch 结构哈希与签名
 */
class Eip712EncoderDepositWalletTest {

    private val chainId = DepositWalletVectors.chainId
    private val keyPair: ECKeyPair =
        ECKeyPair.create(BigInteger(DepositWalletVectors.testPrivateKey.removePrefix("0x"), 16))

    private fun signHex(digest: ByteArray): String {
        val sig = Sign.signMessage(digest, keyPair, false)
        val r = Numeric.toHexStringNoPrefix(sig.r).padStart(64, '0')
        val s = Numeric.toHexStringNoPrefix(sig.s).padStart(64, '0')
        return "0x$r$s${"%02x".format(sig.v[0].toInt() and 0xff)}"
    }

    private fun orderHash(v: JsonObject, maker: String, signer: String, signatureType: Int): ByteArray {
        val o = v["order"].asJsonObject
        return Eip712Encoder.encodeExchangeOrder(
            salt = o["salt"].asLong,
            maker = maker,
            signer = signer,
            tokenId = o["tokenId"].asString,
            makerAmount = o["makerAmount"].asString,
            takerAmount = o["takerAmount"].asString,
            side = if (o["side"].asInt == 0) "BUY" else "SELL",
            signatureType = signatureType,
            timestamp = o["timestamp"].asString,
            metadata = o["metadata"].asString,
            builder = o["builder"].asString
        )
    }

    @Test
    fun `order type string matches official contentsType and has length 186`() {
        assertEquals(186, Eip712Encoder.EXCHANGE_ORDER_TYPE_STRING.length)
        assertEquals(
            "Order(uint256 salt,address maker,address signer,uint256 tokenId,uint256 makerAmount,uint256 takerAmount,uint8 side,uint8 signatureType,uint256 timestamp,bytes32 metadata,bytes32 builder)",
            Eip712Encoder.EXCHANGE_ORDER_TYPE_STRING
        )
    }

    @Test
    fun `exchange domain separators match oracle vectors`() {
        for (name in listOf("standard", "negRisk")) {
            val v = DepositWalletVectors.order(name)
            val sep = Eip712Encoder.encodeExchangeDomain(chainId, v["exchange"].asString)
            assertEquals(v["domainSeparator"].asString, DepositWalletVectors.toHex(sep), name)
        }
    }

    @Test
    fun `exchange order contentsHash matches oracle vector`() {
        for (name in listOf("standard", "negRisk")) {
            val v = DepositWalletVectors.order(name)
            val p = v["poly1271"].asJsonObject
            val hash = orderHash(v, p["maker"].asString, p["signer"].asString, 3)
            assertEquals(p["contentsHash"].asString, DepositWalletVectors.toHex(hash), name)
        }
    }

    @Test
    fun `typedDataSign hash and digest match oracle vectors for both exchanges`() {
        for (name in listOf("standard", "negRisk")) {
            val v = DepositWalletVectors.order(name)
            val p = v["poly1271"].asJsonObject
            val contentsHash = orderHash(v, p["maker"].asString, p["signer"].asString, 3)
            val typedDataSign = Eip712Encoder.encodeTypedDataSign(contentsHash, chainId, p["maker"].asString)
            assertEquals(p["typedDataSignHash"].asString, DepositWalletVectors.toHex(typedDataSign), "$name typedDataSign")
            val digest = Eip712Encoder.hashStructuredData(
                Eip712Encoder.encodeExchangeDomain(chainId, v["exchange"].asString),
                typedDataSign
            )
            assertEquals(p["digest"].asString, DepositWalletVectors.toHex(digest), "$name digest")
        }
    }

    @Test
    fun `EOA signing of typedDataSign digest reproduces oracle inner signature (deterministic RFC6979)`() {
        for (name in listOf("standard", "negRisk")) {
            val v = DepositWalletVectors.order(name)
            val p = v["poly1271"].asJsonObject
            val inner = signHex(DepositWalletVectors.hex(p["digest"].asString))
            assertEquals(p["innerSignature"].asString, inner, name)
        }
    }

    @Test
    fun `wrapErc7739Signature reproduces oracle wrapped signatures exactly`() {
        for (name in listOf("standard", "negRisk")) {
            val v = DepositWalletVectors.order(name)
            val p = v["poly1271"].asJsonObject
            val wrapped = Eip712Encoder.wrapErc7739Signature(
                innerSignature = p["innerSignature"].asString,
                appDomainSeparator = DepositWalletVectors.hex(v["domainSeparator"].asString),
                contentsHash = DepositWalletVectors.hex(p["contentsHash"].asString)
            )
            assertEquals(p["wrappedSignature"].asString, wrapped, name)
            // 65 + 32 + 32 + 186 + 2 = 317 字节
            assertEquals(317 * 2 + 2, wrapped.length)
        }
    }

    @Test
    fun `wrapErc7739Signature layout is inner, domain, contentsHash, contentsType, uint16 length`() {
        val inner = "0x" + "11".repeat(64) + "1b"
        val domain = ByteArray(32) { 0x22 }
        val contents = ByteArray(32) { 0x33 }
        val wrapped = Eip712Encoder.wrapErc7739Signature(inner, domain, contents, "Foo(uint8 a)")
        val body = wrapped.removePrefix("0x")
        assertEquals(inner.removePrefix("0x"), body.substring(0, 130))
        assertEquals("22".repeat(32), body.substring(130, 194))
        assertEquals("33".repeat(32), body.substring(194, 258))
        assertEquals(Numeric.toHexStringNoPrefix("Foo(uint8 a)".toByteArray()), body.substring(258, body.length - 4))
        assertEquals("000c", body.takeLast(4))
    }

    @Test
    fun `wrapErc7739Signature and typedDataSign reject malformed inputs`() {
        val ok32 = ByteArray(32)
        assertThrows(IllegalArgumentException::class.java) { Eip712Encoder.wrapErc7739Signature("0x1234", ok32, ok32) }
        assertThrows(IllegalArgumentException::class.java) { Eip712Encoder.wrapErc7739Signature("0x" + "00".repeat(65), ByteArray(31), ok32) }
        assertThrows(IllegalArgumentException::class.java) { Eip712Encoder.wrapErc7739Signature("0x" + "00".repeat(65), ok32, ByteArray(33)) }
        assertThrows(IllegalArgumentException::class.java) { Eip712Encoder.encodeTypedDataSign(ByteArray(31), chainId, DepositWalletVectors.signer) }
        assertThrows(IllegalArgumentException::class.java) { Eip712Encoder.encodeTypedDataSign(ok32, chainId, DepositWalletVectors.signer, salt = "0x1234") }
    }

    @Test
    fun `plain order digests match oracle vectors (regression for signatureType 2)`() {
        for (name in listOf("standard", "negRisk")) {
            val v = DepositWalletVectors.order(name)
            val p = v["polyGnosisSafe"].asJsonObject
            val hash = orderHash(v, p["maker"].asString, p["signer"].asString, 2)
            val digest = Eip712Encoder.hashStructuredData(
                Eip712Encoder.encodeExchangeDomain(chainId, v["exchange"].asString),
                hash
            )
            assertEquals(p["digest"].asString, DepositWalletVectors.toHex(digest), name)
            assertEquals(p["signature"].asString, signHex(digest), name)
        }
    }

    @Test
    fun `deposit wallet domain separator matches oracle vector`() {
        for (name in listOf("single", "triple")) {
            val b = DepositWalletVectors.batch(name)
            val sep = Eip712Encoder.encodeDepositWalletDomain(chainId, b["wallet"].asString)
            assertEquals(b["domainSeparator"].asString, DepositWalletVectors.toHex(sep), name)
        }
    }

    private fun calls(b: JsonObject): List<Eip712Encoder.DepositWalletCall> =
        b["calls"].asJsonArray.map { it.asJsonObject }.map {
            Eip712Encoder.DepositWalletCall(
                target = it["target"].asString,
                value = BigInteger(it["value"].asString),
                data = it["data"].asString
            )
        }

    @Test
    fun `batch struct hashes, digests and signatures match oracle vectors`() {
        for (name in listOf("single", "triple")) {
            val b = DepositWalletVectors.batch(name)
            val structHash = Eip712Encoder.encodeDepositWalletBatch(
                wallet = b["wallet"].asString,
                nonce = BigInteger(b["nonce"].asString),
                deadline = BigInteger(b["deadline"].asString),
                calls = calls(b)
            )
            assertEquals(b["structHash"].asString, DepositWalletVectors.toHex(structHash), "$name structHash")
            val digest = Eip712Encoder.hashStructuredData(
                Eip712Encoder.encodeDepositWalletDomain(chainId, b["wallet"].asString),
                structHash
            )
            assertEquals(b["digest"].asString, DepositWalletVectors.toHex(digest), "$name digest")
            assertEquals(b["signature"].asString, signHex(digest), "$name signature")
        }
    }

    @Test
    fun `batch encoding treats empty data variants and address case consistently`() {
        val b = DepositWalletVectors.batch("triple")
        val base = calls(b)
        val variantEmpty = base.mapIndexed { i, c -> if (i == 2) c.copy(data = "") else c }
        val variantCase = base.map { it.copy(target = "0x" + it.target.removePrefix("0x").uppercase()) }
        val nonce = BigInteger(b["nonce"].asString)
        val deadline = BigInteger(b["deadline"].asString)
        val wallet = b["wallet"].asString
        val expected = Eip712Encoder.encodeDepositWalletBatch(wallet, nonce, deadline, base)
        assertArrayEquals(expected, Eip712Encoder.encodeDepositWalletBatch(wallet, nonce, deadline, variantEmpty))
        assertArrayEquals(expected, Eip712Encoder.encodeDepositWalletBatch(wallet, nonce, deadline, variantCase))
        assertThrows(IllegalArgumentException::class.java) { Eip712Encoder.encodeDepositWalletBatch(wallet, nonce, deadline, emptyList()) }
    }
}
