package com.wrbug.polymarketbot.util

import com.wrbug.polymarketbot.enums.WalletType
import java.math.BigInteger

/**
 * Polymarket 各类代理钱包地址的离线推导（CREATE2）
 *
 * 参考官方 ts-sdk packages/client/src/wallet.ts 与 environments.ts（production）：
 * - Magic Proxy：ProxyFactory CREATE2，salt = keccak256(encodePacked(signer))
 * - Safe：SafeFactory CREATE2，salt = keccak256(abi.encode(signer))
 * - Deposit Wallet：DepositWalletFactory CREATE2，
 *   args = abi.encode(factory, bytes32(signer))，salt = keccak256(args)，
 *   initCode 为 Solady ERC1967 beacon proxy（当前生产）或 ERC1967 UUPS proxy（旧版）加上 args
 *
 * 所有返回的地址均为小写 0x 前缀形式。
 */
object PolymarketWalletDerivation {

    // ---- Polygon 主网合约地址（与 ts-sdk production.walletDerivation 一致）----
    const val DEPOSIT_WALLET_FACTORY = "0x00000000000Fb5C9ADea0298D729A0CB3823Cc07"
    const val DEPOSIT_WALLET_BEACON = "0x7A18EDfe055488A3128f01F563e5B479D92ffc3a"
    const val DEPOSIT_WALLET_IMPLEMENTATION = "0x58CA52ebe0DadfdF531Cde7062e76746de4Db1eB"
    const val PROXY_FACTORY = "0xaB45c5A4B0c941a2F231C04C3f49182e1A254052"
    const val PROXY_IMPLEMENTATION = "0x44e999d5c2F66Ef0861317f9A4805AC2e90aEB4f"
    const val SAFE_FACTORY = "0xaacFeEa03eb1561C4e67d661e40682Bd20E3541b"
    const val SAFE_INIT_CODE_HASH = "0x2bce2127ff07fb632d16c8347c4ebf501f4841168bed00d9e6ef715ddb6fcecf"

    /** DepositWalletFactory.beacon() 选择器，返回非零地址表示工厂使用 beacon 代理 */
    const val FACTORY_BEACON_SELECTOR = "0x49493a4d"

    // Magic Proxy 字节码模板（两个 %s 分别为 factory 与 implementation，去掉 0x）
    private const val PROXY_BYTECODE_TEMPLATE =
        "3d3d606380380380913d393d73%s5af4602a57600080fd5b602d8060366000396000f3363d3d373d3d3d363d73%s5af43d82803e903d91602b57fd5bf352e831dd00000000000000000000000000000000000000000000000000000000000000200000000000000000000000000000000000000000000000000000000000000000"

    // Solady ERC1967 UUPS proxy initCode 片段
    private const val ERC1967_CONST1 = "cc3735a920a3ca505d382bbc545af43d6000803e6038573d6000fd5b3d6000f3"
    private const val ERC1967_CONST2 = "5155f3363d3d373d3d363d7f360894a13ba1a3210667c828492db98dca3e2076"
    private val ERC1967_PREFIX = BigInteger("61003d3d8160233d3973", 16)

    // Solady ERC1967 beacon proxy initCode 片段
    private const val ERC1967_BEACON_CONST1 = "b3582b35133d50545afa5036515af43d6000803e604d573d6000fd5b3d6000f3"
    private const val ERC1967_BEACON_CONST2 = "1b60e01b36527fa3f0ad74e5423aebfd80d3ef4346578335a9a72aeaee59ff6c"
    private const val ERC1967_BEACON_CONST3 = "60195155f3363d3d373d3d363d602036600436635c60da"
    private val ERC1967_BEACON_PREFIX = BigInteger("6100523d8160233d3973", 16)

    /**
     * 通用 CREATE2 地址计算：keccak256(0xff ++ deployer ++ salt ++ initCodeHash)[12:]
     */
    fun computeCreate2Address(deployer: String, salt: ByteArray, initCodeHash: ByteArray): String {
        require(salt.size == 32) { "salt 必须为 32 字节" }
        require(initCodeHash.size == 32) { "initCodeHash 必须为 32 字节" }
        val data = byteArrayOf(0xff.toByte()) + addressBytes(deployer) + salt + initCodeHash
        val hash = EthereumUtils.keccak256(data)
        return "0x" + hash.copyOfRange(12, 32).joinToString("") { "%02x".format(it) }
    }

    /**
     * Magic Proxy 地址（邮箱/OAuth 旧版账户）
     */
    fun deriveMagicProxyAddress(signer: String): String {
        val bytecode = PROXY_BYTECODE_TEMPLATE
            .replaceFirst("%s", PROXY_FACTORY.removePrefix("0x").lowercase())
            .replaceFirst("%s", PROXY_IMPLEMENTATION.removePrefix("0x").lowercase())
        val initCodeHash = EthereumUtils.keccak256(EthereumUtils.hexToBytes(bytecode))
        val salt = EthereumUtils.keccak256(addressBytes(signer))
        return computeCreate2Address(PROXY_FACTORY, salt, initCodeHash)
    }

    /**
     * Gnosis Safe 地址（MetaMask 等浏览器钱包账户），离线计算，与 SafeFactory.computeProxyAddress 一致
     */
    fun deriveSafeAddress(signer: String): String {
        val salt = EthereumUtils.keccak256(abiEncodeAddress(signer))
        return computeCreate2Address(SAFE_FACTORY, salt, EthereumUtils.hexToBytes(SAFE_INIT_CODE_HASH))
    }

    /**
     * Deposit Wallet 的 CREATE2 构造参数：abi.encode(address factory, bytes32 walletId)，walletId = bytes32(signer)
     */
    fun depositWalletArgs(signer: String): ByteArray {
        return abiEncodeAddress(DEPOSIT_WALLET_FACTORY) + abiEncodeAddress(signer)
    }

    /** Deposit Wallet 的 CREATE2 salt = keccak256(args) */
    fun depositWalletSalt(signer: String): ByteArray = EthereumUtils.keccak256(depositWalletArgs(signer))

    /**
     * Deposit Wallet 地址（beacon 代理，当前生产工厂使用）
     */
    fun deriveBeaconDepositWalletAddress(signer: String): String {
        val args = depositWalletArgs(signer)
        val initCodeHash = EthereumUtils.keccak256(beaconDepositWalletInitCode(DEPOSIT_WALLET_BEACON, args))
        return computeCreate2Address(DEPOSIT_WALLET_FACTORY, EthereumUtils.keccak256(args), initCodeHash)
    }

    /**
     * Deposit Wallet 地址（UUPS 代理，旧版工厂使用）
     */
    fun deriveUupsDepositWalletAddress(signer: String): String {
        val args = depositWalletArgs(signer)
        val initCodeHash = EthereumUtils.keccak256(uupsDepositWalletInitCode(DEPOSIT_WALLET_IMPLEMENTATION, args))
        return computeCreate2Address(DEPOSIT_WALLET_FACTORY, EthereumUtils.keccak256(args), initCodeHash)
    }

    /**
     * beacon 代理 initCode：prefix(10 字节，含 args 长度) ++ beacon ++ CONST3 ++ CONST2 ++ CONST1 ++ args
     */
    fun beaconDepositWalletInitCode(beacon: String, args: ByteArray): ByteArray {
        val prefix = ERC1967_BEACON_PREFIX.add(BigInteger.valueOf(args.size.toLong()).shiftLeft(56))
        return toFixedBytes(prefix, 10) +
                addressBytes(beacon) +
                EthereumUtils.hexToBytes(ERC1967_BEACON_CONST3) +
                EthereumUtils.hexToBytes(ERC1967_BEACON_CONST2) +
                EthereumUtils.hexToBytes(ERC1967_BEACON_CONST1) +
                args
    }

    /**
     * UUPS 代理 initCode：prefix(10 字节，含 args 长度) ++ implementation ++ 0x6009 ++ CONST2 ++ CONST1 ++ args
     */
    fun uupsDepositWalletInitCode(implementation: String, args: ByteArray): ByteArray {
        val prefix = ERC1967_PREFIX.add(BigInteger.valueOf(args.size.toLong()).shiftLeft(56))
        return toFixedBytes(prefix, 10) +
                addressBytes(implementation) +
                byteArrayOf(0x60, 0x09) +
                EthereumUtils.hexToBytes(ERC1967_CONST2) +
                EthereumUtils.hexToBytes(ERC1967_CONST1) +
                args
    }

    /**
     * 根据 signer 推导出的各类地址判断 wallet 属于哪种钱包类型；无法匹配返回 null
     */
    fun classifyWallet(signer: String, wallet: String): WalletType? {
        if (!isValidAddress(signer) || !isValidAddress(wallet)) return null
        val target = wallet.lowercase()
        return when (target) {
            deriveBeaconDepositWalletAddress(signer), deriveUupsDepositWalletAddress(signer) -> WalletType.DEPOSIT
            deriveSafeAddress(signer) -> WalletType.SAFE
            deriveMagicProxyAddress(signer) -> WalletType.MAGIC
            else -> null
        }
    }

    fun isValidAddress(address: String?): Boolean {
        return address != null && Regex("^0x[0-9a-fA-F]{40}$").matches(address)
    }

    private fun addressBytes(address: String): ByteArray {
        require(isValidAddress(address)) { "无效的地址: $address" }
        return EthereumUtils.hexToBytes(address.removePrefix("0x").lowercase())
    }

    /** abi.encode(address)：20 字节地址左侧补零到 32 字节 */
    private fun abiEncodeAddress(address: String): ByteArray = ByteArray(12) + addressBytes(address)

    private fun toFixedBytes(value: BigInteger, size: Int): ByteArray {
        val raw = value.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        require(raw.size <= size) { "数值超出 $size 字节" }
        return ByteArray(size - raw.size) + raw
    }
}
