package com.wrbug.polymarketbot.util

import com.wrbug.polymarketbot.enums.WalletType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * 地址推导与官方 ts-sdk wallet.ts 的向量对比。
 * 其中 EOA 0xec61…53f9 的三个地址均已在 Polygon 链上核实：
 * Deposit Wallet 0xc673…8e97 已部署且 owner() == EOA，Safe 0x619d…d829 已部署，Magic Proxy 未部署。
 */
class PolymarketWalletDerivationTest {

    private val userEoa = "0xec61677883418ab16ecc0ce35113635cf5a753f9"

    @Test
    fun `deposit wallet addresses match official vectors for test signer`() {
        val v = DepositWalletVectors.derivation(DepositWalletVectors.signer)
        assertEquals(v["beaconDepositWallet"].asString, PolymarketWalletDerivation.deriveBeaconDepositWalletAddress(DepositWalletVectors.signer))
        assertEquals(v["uupsDepositWallet"].asString, PolymarketWalletDerivation.deriveUupsDepositWalletAddress(DepositWalletVectors.signer))
    }

    @Test
    fun `deposit wallet addresses match on-chain reality for the issue 62 account`() {
        val v = DepositWalletVectors.derivation(userEoa)
        assertEquals("0xc673f11512c3ae2f67f07f953defc0e65b1c8e97", v["beaconDepositWallet"].asString)
        assertEquals(v["beaconDepositWallet"].asString, PolymarketWalletDerivation.deriveBeaconDepositWalletAddress(userEoa))
        assertEquals(v["uupsDepositWallet"].asString, PolymarketWalletDerivation.deriveUupsDepositWalletAddress(userEoa))
    }

    @Test
    fun `safe address matches official vector and on-chain deployment`() {
        assertEquals("0x619d9447990dfdd92133fc8fcfdb1fe799a8d829", PolymarketWalletDerivation.deriveSafeAddress(userEoa))
        val v = DepositWalletVectors.derivation(DepositWalletVectors.signer)
        assertEquals(v["safe"].asString, PolymarketWalletDerivation.deriveSafeAddress(DepositWalletVectors.signer))
    }

    @Test
    fun `magic proxy address matches official vector`() {
        assertEquals("0xea9a50807c989ce908d31603a9bf34d2e504360d", PolymarketWalletDerivation.deriveMagicProxyAddress(userEoa))
        val v = DepositWalletVectors.derivation(DepositWalletVectors.signer)
        assertEquals(v["magicProxy"].asString, PolymarketWalletDerivation.deriveMagicProxyAddress(DepositWalletVectors.signer))
    }

    @Test
    fun `derivation is case insensitive on signer and returns lowercase`() {
        val upper = "0x" + userEoa.removePrefix("0x").uppercase()
        assertEquals(PolymarketWalletDerivation.deriveBeaconDepositWalletAddress(userEoa), PolymarketWalletDerivation.deriveBeaconDepositWalletAddress(upper))
        assertEquals(PolymarketWalletDerivation.deriveSafeAddress(userEoa), PolymarketWalletDerivation.deriveSafeAddress(upper))
        assertEquals(PolymarketWalletDerivation.deriveMagicProxyAddress(userEoa), PolymarketWalletDerivation.deriveMagicProxyAddress(upper))
        val addr = PolymarketWalletDerivation.deriveBeaconDepositWalletAddress(upper)
        assertEquals(addr, addr.lowercase())
    }

    @Test
    fun `classifyWallet recognizes each wallet type`() {
        assertEquals(WalletType.DEPOSIT, PolymarketWalletDerivation.classifyWallet(userEoa, "0xc673f11512c3ae2f67f07f953defc0e65b1c8e97"))
        assertEquals(WalletType.DEPOSIT, PolymarketWalletDerivation.classifyWallet(userEoa, PolymarketWalletDerivation.deriveUupsDepositWalletAddress(userEoa)))
        assertEquals(WalletType.SAFE, PolymarketWalletDerivation.classifyWallet(userEoa, "0x619D9447990DFDD92133FC8FCFDB1FE799A8D829"))
        assertEquals(WalletType.MAGIC, PolymarketWalletDerivation.classifyWallet(userEoa, "0xea9a50807c989ce908d31603a9bf34d2e504360d"))
        assertNull(PolymarketWalletDerivation.classifyWallet(userEoa, userEoa))
        assertNull(PolymarketWalletDerivation.classifyWallet(userEoa, "0x0000000000000000000000000000000000000001"))
        assertNull(PolymarketWalletDerivation.classifyWallet(userEoa, "not-an-address"))
    }

    @Test
    fun `computeCreate2Address validates inputs`() {
        assertThrows(IllegalArgumentException::class.java) {
            PolymarketWalletDerivation.computeCreate2Address(PolymarketWalletDerivation.DEPOSIT_WALLET_FACTORY, ByteArray(31), ByteArray(32))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PolymarketWalletDerivation.computeCreate2Address(PolymarketWalletDerivation.DEPOSIT_WALLET_FACTORY, ByteArray(32), ByteArray(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PolymarketWalletDerivation.deriveBeaconDepositWalletAddress("0x1234")
        }
    }
}
