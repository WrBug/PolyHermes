package com.wrbug.polymarketbot.service.copytrading.orders

import com.wrbug.polymarketbot.util.DepositWalletVectors
import com.wrbug.polymarketbot.util.Eip712Encoder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys
import org.web3j.crypto.Sign
import org.web3j.utils.Numeric
import java.math.BigInteger

/**
 * OrderSigningService 对 Deposit Wallet（signatureType 3）的支持
 */
class OrderSigningServiceDepositWalletTest {

    private val service = OrderSigningService()
    private val privateKey = DepositWalletVectors.testPrivateKey
    private val keyPair: ECKeyPair = ECKeyPair.create(BigInteger(privateKey.removePrefix("0x"), 16))
    private val eoa = "0x" + Keys.getAddress(keyPair)

    @Test
    fun `signature type mapping covers magic, safe and deposit wallets`() {
        assertEquals(1, service.getSignatureTypeForWalletType("magic"))
        assertEquals(2, service.getSignatureTypeForWalletType("safe"))
        assertEquals(3, service.getSignatureTypeForWalletType("deposit"))
        assertEquals(3, service.getSignatureTypeForWalletType("DEPOSIT"))
        assertEquals(2, service.getSignatureTypeForWalletType(null))
        assertEquals(2, service.getSignatureTypeForWalletType("unknown"))
    }

    @Test
    fun `signOrderErc7739 reproduces oracle wrapped signatures byte for byte on both exchanges`() {
        for (name in listOf("standard", "negRisk")) {
            val v = DepositWalletVectors.order(name)
            val p = v["poly1271"].asJsonObject
            val domain = Eip712Encoder.encodeExchangeDomain(DepositWalletVectors.chainId, v["exchange"].asString)
            val wrapped = service.signOrderErc7739(
                ecKeyPair = keyPair,
                exchangeDomainSeparator = domain,
                orderHash = DepositWalletVectors.hex(p["contentsHash"].asString),
                depositWallet = p["maker"].asString,
                chainId = DepositWalletVectors.chainId
            )
            assertEquals(p["wrappedSignature"].asString, wrapped, name)
        }
    }

    @Test
    fun `createAndSignOrder with signatureType 3 sets signer to maker and produces ERC-7739 signature`() {
        val v = DepositWalletVectors.order("standard")
        val depositWallet = v["poly1271"].asJsonObject["maker"].asString
        val checksummed = Keys.toChecksumAddress(depositWallet)
        val order = service.createAndSignOrder(
            privateKey = privateKey,
            makerAddress = checksummed,
            tokenId = v["order"].asJsonObject["tokenId"].asString,
            side = "BUY",
            price = "0.5",
            size = "10",
            signatureType = 3,
            exchangeContract = v["exchange"].asString
        )
        assertEquals(depositWallet, order.maker)
        assertEquals(depositWallet, order.signer)
        assertEquals(3, order.signatureType)
        // 65 + 32 + 32 + 186 + 2 = 317 字节
        assertEquals(317 * 2 + 2, order.signature.length)

        val body = order.signature.removePrefix("0x")
        val exchangeDomain = Eip712Encoder.encodeExchangeDomain(137L, v["exchange"].asString)
        assertEquals(Numeric.toHexStringNoPrefix(exchangeDomain), body.substring(130, 194))
        val contentsHash = Eip712Encoder.encodeExchangeOrder(
            salt = order.salt,
            maker = order.maker,
            signer = order.signer,
            tokenId = order.tokenId,
            makerAmount = order.makerAmount,
            takerAmount = order.takerAmount,
            side = order.side,
            signatureType = order.signatureType,
            timestamp = order.timestamp,
            metadata = order.metadata,
            builder = order.builder
        )
        assertEquals(Numeric.toHexStringNoPrefix(contentsHash), body.substring(194, 258))
        assertEquals("00ba", body.takeLast(4))

        // 内层签名必须由 owner EOA 对 TypedDataSign 摘要签出
        val digest = Eip712Encoder.hashStructuredData(
            exchangeDomain,
            Eip712Encoder.encodeTypedDataSign(contentsHash, 137L, depositWallet)
        )
        val inner = body.substring(0, 130)
        val sig = Sign.SignatureData(
            Numeric.hexStringToByteArray(inner.substring(128, 130)),
            Numeric.hexStringToByteArray(inner.substring(0, 64)),
            Numeric.hexStringToByteArray(inner.substring(64, 128))
        )
        val recovered = "0x" + Keys.getAddress(Sign.signedMessageHashToKey(digest, sig))
        assertEquals(eoa, recovered)
    }

    @Test
    fun `createAndSignOrder with signatureType 2 keeps EOA signer and 65-byte signature (regression)`() {
        val v = DepositWalletVectors.order("standard")
        val safe = v["polyGnosisSafe"].asJsonObject["maker"].asString
        val order = service.createAndSignOrder(
            privateKey = privateKey,
            makerAddress = safe,
            tokenId = v["order"].asJsonObject["tokenId"].asString,
            side = "SELL",
            price = "0.5",
            size = "10",
            signatureType = 2,
            exchangeContract = v["exchange"].asString
        )
        assertEquals(safe, order.maker)
        assertEquals(eoa, order.signer)
        assertNotEquals(order.maker, order.signer)
        assertEquals(65 * 2 + 2, order.signature.length)
    }
}
