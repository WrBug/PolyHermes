package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.util.DepositWalletVectors
import com.wrbug.polymarketbot.util.Eip712Encoder
import com.wrbug.polymarketbot.util.RetrofitFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.web3j.crypto.ECKeyPair
import java.math.BigInteger

/**
 * RelayClientService 中 Deposit Wallet 批量签名与 Relayer 错误解析
 */
class RelayClientServiceDepositWalletTest {

    private val service = RelayClientService(
        retrofitFactory = Mockito.mock(RetrofitFactory::class.java),
        systemConfigService = Mockito.mock(SystemConfigService::class.java),
        rpcNodeService = Mockito.mock(RpcNodeService::class.java)
    )
    private val keyPair: ECKeyPair =
        ECKeyPair.create(BigInteger(DepositWalletVectors.testPrivateKey.removePrefix("0x"), 16))

    @Test
    fun `signDepositWalletBatch reproduces oracle signatures`() {
        for (name in listOf("single", "triple")) {
            val b = DepositWalletVectors.batch(name)
            val calls = b["calls"].asJsonArray.map { it.asJsonObject }.map {
                Eip712Encoder.DepositWalletCall(
                    target = it["target"].asString,
                    value = BigInteger(it["value"].asString),
                    data = it["data"].asString
                )
            }
            val signature = service.signDepositWalletBatch(
                ecKeyPair = keyPair,
                depositWallet = b["wallet"].asString,
                nonce = BigInteger(b["nonce"].asString),
                deadline = BigInteger(b["deadline"].asString),
                calls = calls
            )
            assertEquals(b["signature"].asString, signature, name)
        }
    }

    @Test
    fun `extractOnChainNonceFromError parses relayer nonce mismatch message`() {
        assertEquals(
            BigInteger.valueOf(5),
            service.extractOnChainNonceFromError("""{"error":"batch nonce 3 does not match on-chain nonce 5"}""")
        )
        assertEquals(
            BigInteger.valueOf(12),
            service.extractOnChainNonceFromError("Batch Nonce 11 Does Not Match On-Chain Nonce 12")
        )
        assertNull(service.extractOnChainNonceFromError("wallet busy: active action"))
        assertNull(service.extractOnChainNonceFromError(""))
    }
}
