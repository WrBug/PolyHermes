package com.wrbug.polymarketbot.service.copytrading.orders

import com.wrbug.polymarketbot.api.SignedOrderObject
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.web3j.crypto.Credentials
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * 订单签名服务
 * 用于创建和签名 Polymarket CLOB API 订单
 * 
 * 参考:
 * - clob-client/src/order-builder/helpers.ts
 * - @polymarket/order-utils 的 ExchangeOrderBuilder
 */
@Service
class OrderSigningService {
    
    private val logger = LoggerFactory.getLogger(OrderSigningService::class.java)
    
    // Polygon 主网合约地址
    private val EXCHANGE_CONTRACT = "0x4bFb41d5B3570DeFd03C39a9A4D8dE6Bd8B8982E"
    private val CHAIN_ID = 137L
    
    // USDC 有 6 位小数
    private val COLLATERAL_TOKEN_DECIMALS = 6
    
    // 默认 tickSize 配置（0.01，对应 2 位小数）
    private val DEFAULT_TICK_SIZE = "0.01"
    private val DEFAULT_ROUND_CONFIG = RoundConfig(
        price = 2,
        size = 2,
        amount = 4
    )
    
    // 金额精度限制（根据 Polymarket API 要求）
    // makerAmount (USDC) 最多 2 位小数
    // takerAmount (shares) 最多 4 位小数
    private val MAKER_AMOUNT_DECIMALS = 2  // USDC 金额精度
    private val TAKER_AMOUNT_DECIMALS = 4  // shares 数量精度
    
    /**
     * 订单金额计算结果
     */
    data class OrderAmounts(
        val makerAmount: String,  // 以 wei 为单位（6 位小数）
        val takerAmount: String   // 以 wei 为单位（6 位小数）
    )
    
    /**
     * 舍入配置
     */
    data class RoundConfig(
        val price: Int,   // 价格小数位数
        val size: Int,    // 数量小数位数
        val amount: Int   // 金额小数位数
    )
    
    /**
     * 计算订单金额（makerAmount 和 takerAmount）
     * 
     * @param side BUY 或 SELL
     * @param size 数量（shares）
     * @param price 价格（0-1 之间）
     * @param roundConfig 舍入配置
     * @return 订单金额
     */
    fun calculateOrderAmounts(
        side: String,
        size: String,
        price: String,
        roundConfig: RoundConfig = DEFAULT_ROUND_CONFIG
    ): OrderAmounts {
        val sizeDecimal = size.toSafeBigDecimal()
        val priceDecimal = price.toSafeBigDecimal()
        if (side.uppercase() == "BUY") {
            // BUY: makerAmount = price * size (USDC), takerAmount = size (shares)
            // makerAmount 是 USDC 金额，最多 2 位小数
            // takerAmount 是 shares 数量，最多 4 位小数
            val rawTakerAmt = roundDown(sizeDecimal, roundConfig.size)
            
            // makerAmount = price * size，使用原始价格计算（与SDK保持一致）
            // 先使用原始价格计算，然后再进行舍入，确保精度一致
            var rawMakerAmt = rawTakerAmt.multiply(priceDecimal)
            
            // 确保 makerAmount 精度（USDC，最多 2 位小数）
            rawMakerAmt = roundDown(rawMakerAmt, MAKER_AMOUNT_DECIMALS)
            
            // 确保 takerAmount 精度（shares，最多 4 位小数）
            val finalTakerAmt = roundDown(rawTakerAmt, TAKER_AMOUNT_DECIMALS)
            
            // 转换为 wei（6 位小数）
            val makerAmount = parseUnits(rawMakerAmt, COLLATERAL_TOKEN_DECIMALS)
            val takerAmount = parseUnits(finalTakerAmt, COLLATERAL_TOKEN_DECIMALS)
            
            return OrderAmounts(makerAmount.toString(), takerAmount.toString())
        } else {
            // SELL: makerAmount = size (shares), takerAmount = price * size (USDC)
            // 根据 Polymarket API 要求：
            // - makerAmount (shares) 最多 2 位小数
            // - takerAmount (USDC) 最多 4 位小数
            val rawMakerAmt = roundDown(sizeDecimal, roundConfig.size)
            
            // takerAmount = price * size，使用原始价格计算（不使用舍入后的价格）
            // SDK期望使用原始价格进行计算，以保留足够的精度
            // 例如：0.9596 * 16.09 = 15.439964，而不是 0.96 * 16.09 = 15.4464
            val rawTakerAmt = rawMakerAmt.multiply(priceDecimal)
            
            // 确保 makerAmount 精度（shares，最多 2 位小数，符合 API 要求）
            val finalMakerAmt = roundDown(rawMakerAmt, MAKER_AMOUNT_DECIMALS)
            
            // 确保 takerAmount 精度（USDC，最多 4 位小数，符合 API 要求）
            val finalTakerAmt = roundDown(rawTakerAmt, TAKER_AMOUNT_DECIMALS)
            
            // 转换为 wei（6 位小数）
            val makerAmount = parseUnits(finalMakerAmt, COLLATERAL_TOKEN_DECIMALS)
            val takerAmount = parseUnits(finalTakerAmt, COLLATERAL_TOKEN_DECIMALS)
            
            return OrderAmounts(makerAmount.toString(), takerAmount.toString())
        }
    }
    
    /**
     * 创建并签名订单
     * 
     * @param privateKey 私钥（十六进制字符串）
     * @param makerAddress maker 地址（funder，通常是 proxyAddress）
     * @param tokenId token ID
     * @param side BUY 或 SELL
     * @param price 价格
     * @param size 数量
     * @param signatureType 签名类型（1: Email/Magic, 2: Browser Wallet, 0: EOA）
     * @param nonce nonce（默认 "0"）
     * @param feeRateBps 费率基点（默认 "0"）
     * @param expiration 过期时间戳（秒，0 表示永不过期）
     * @return 签名的订单对象
     */
    fun createAndSignOrder(
        privateKey: String,
        makerAddress: String,
        tokenId: String,
        side: String,
        price: String,
        size: String,
        signatureType: Int = 2,  // 默认使用 Browser Wallet（与正确订单数据一致）
        nonce: String = "0",
        feeRateBps: String = "0",
        expiration: String = "0"
    ): SignedOrderObject {
        try {
            // 1. 从私钥获取签名地址
            val cleanPrivateKey = privateKey.removePrefix("0x")
            val privateKeyBigInt = BigInteger(cleanPrivateKey, 16)
            val credentials = Credentials.create(privateKeyBigInt.toString(16))
            // 统一转换为小写，确保与 EIP-712 编码时使用的地址格式一致
            // EIP-712 编码时地址会被转换为小写，所以订单对象中的地址也应该是小写
            val signerAddress = credentials.address.lowercase()
            
            // 2. 计算订单金额
            val amounts = calculateOrderAmounts(side, size, price)
            
            // 3. 生成 salt（使用时间戳，毫秒）
            val salt = generateSalt()
            
            // 4. taker 地址（默认使用零地址）
            val taker = "0x0000000000000000000000000000000000000000"
            
            // 5. 确保 maker 地址也是小写格式
            val makerAddressLower = makerAddress.lowercase()
            
            // 打印签名前的订单参数（DEBUG 级别，避免敏感信息泄露）
            logger.debug("========== 订单签名前参数 ==========")
            logger.debug("订单方向: $side, 价格: $price, 数量: $size")
            logger.debug("Token ID: $tokenId")
            logger.debug("Maker: ${makerAddressLower.take(10)}...${makerAddressLower.takeLast(6)}")
            logger.debug("Signer: ${signerAddress.take(10)}...${signerAddress.takeLast(6)}")
            logger.debug("Amounts - Maker: ${amounts.makerAmount}, Taker: ${amounts.takerAmount}")
            logger.debug("Salt: $salt, Expiration: $expiration, Nonce: $nonce, FeeRateBPS: $feeRateBps")
            logger.debug("Signature Type: $signatureType, Chain ID: $CHAIN_ID")
            
            // 6. 构建订单数据并签名
            val signature = signOrder(
                privateKey = privateKey,
                exchangeContract = EXCHANGE_CONTRACT,
                chainId = CHAIN_ID,
                salt = salt,
                maker = makerAddressLower,
                signer = signerAddress,
                taker = taker,
                tokenId = tokenId,
                makerAmount = amounts.makerAmount,
                takerAmount = amounts.takerAmount,
                expiration = expiration,
                nonce = nonce,
                feeRateBps = feeRateBps,
                side = side.uppercase(),
                signatureType = signatureType
            )
            
            // 7. 创建签名的订单对象
            // 注意：所有地址字段都使用小写格式，确保与签名时使用的地址一致
            return SignedOrderObject(
                salt = salt,
                maker = makerAddressLower,
                signer = signerAddress,
                taker = taker,
                tokenId = tokenId,
                makerAmount = amounts.makerAmount,
                takerAmount = amounts.takerAmount,
                expiration = expiration,
                nonce = nonce,
                feeRateBps = feeRateBps,
                side = side.uppercase(),
                signatureType = signatureType,
                signature = signature
            )
        } catch (e: Exception) {
            logger.error("创建并签名订单失败", e)
            throw RuntimeException("创建并签名订单失败: ${e.message}", e)
        }
    }
    
    /**
     * 签名订单（EIP-712）
     * 
     * 参考: @polymarket/order-utils 的 ExchangeOrderBuilder
     */
    private fun signOrder(
        privateKey: String,
        exchangeContract: String,
        chainId: Long,
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
    ): String {
        try {
            // 1. 从私钥创建 BigInteger
            val cleanPrivateKey = privateKey.removePrefix("0x")
            val privateKeyBigInt = BigInteger(cleanPrivateKey, 16)
            val ecKeyPair = org.web3j.crypto.ECKeyPair.create(privateKeyBigInt)
            
            // 2. 编码域分隔符
            val domainSeparator = com.wrbug.polymarketbot.util.Eip712Encoder.encodeExchangeDomain(
                chainId = chainId,
                verifyingContract = exchangeContract
            )
            
            // 3. 编码订单消息哈希
            // signatureType 参数：1 = POLY_PROXY (代理钱包), 2 = POLY_GNOSIS_SAFE, 0 = EOA
            // 使用传入的 signatureType 参数，而不是硬编码
            val orderHash = com.wrbug.polymarketbot.util.Eip712Encoder.encodeExchangeOrder(
                salt = salt,
                maker = maker,
                signer = signer,
                taker = taker,
                tokenId = tokenId,
                makerAmount = makerAmount,
                takerAmount = takerAmount,
                expiration = expiration,
                nonce = nonce,
                feeRateBps = feeRateBps,
                side = side,
                signatureType = signatureType  // 使用传入的参数
            )
            
            // 4. 计算完整的结构化数据哈希
            val structuredHash = com.wrbug.polymarketbot.util.Eip712Encoder.hashStructuredData(
                domainSeparator = domainSeparator,
                messageHash = orderHash
            )
            
            // 5. 使用私钥签名
            val signature = org.web3j.crypto.Sign.signMessage(structuredHash, ecKeyPair, false)
            
            // 6. 组合签名（r + s + v）
            val rHex = org.web3j.utils.Numeric.toHexString(signature.r).removePrefix("0x").padStart(64, '0')
            val sHex = org.web3j.utils.Numeric.toHexString(signature.s).removePrefix("0x").padStart(64, '0')
            val vBytes = signature.v as ByteArray
            val vInt = if (vBytes.isNotEmpty()) {
                vBytes[0].toInt() and 0xff
            } else {
                0
            }
            val vHex = String.format("%02x", vInt)
            
            return "0x$rHex$sHex$vHex"
        } catch (e: Exception) {
            logger.error("订单签名失败", e)
            throw RuntimeException("订单签名失败: ${e.message}", e)
        }
    }
    
    /**
     * 生成 salt（使用时间戳，毫秒）
     * 与 TypeScript SDK 保持一致，使用时间戳作为 salt
     */
    private fun generateSalt(): Long {
        return System.currentTimeMillis()
    }
    
    /**
     * 将 BigDecimal 转换为 wei（指定小数位数）
     * 使用精确计算，不进行舍入，直接截断到指定小数位数
     */
    private fun parseUnits(value: BigDecimal, decimals: Int): BigInteger {
        // 先设置精度到指定小数位数（向下截断，不四舍五入）
        val scaledValue = value.setScale(decimals, RoundingMode.DOWN)
        val multiplier = BigInteger.TEN.pow(decimals)
        return scaledValue.multiply(BigDecimal(multiplier)).toBigInteger()
    }
    
    /**
     * 正常舍入（四舍五入）
     */
    private fun roundNormal(value: BigDecimal, decimals: Int): BigDecimal {
        return value.setScale(decimals, RoundingMode.HALF_UP)
    }
    
    /**
     * 向下舍入
     */
    private fun roundDown(value: BigDecimal, decimals: Int): BigDecimal {
        return value.setScale(decimals, RoundingMode.DOWN)
    }
    
    /**
     * 向上舍入
     */
    private fun roundUp(value: BigDecimal, decimals: Int): BigDecimal {
        return value.setScale(decimals, RoundingMode.UP)
    }
}

