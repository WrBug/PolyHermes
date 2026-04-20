# Polymarket CLOB V2 迁移指南

## 📋 迁移概述

Polymarket正在升级其整个交易基础设施，包括新的Exchange合约、重写的CLOB后端和新的抵押品代币（pUSD）。本文档详细说明了如何将backend代码从CLOB V1迁移到V2。

**迁移截止日期：** 2026年4月28日 ~11:00 UTC  
**预计停机时间：** 约1小时  
**重要提示：** V2发布后，V1 SDK将立即停止工作，无向后兼容性。

---

## 🔄 主要变更概览

| 变更项 | V1 | V2 |
|--------|-----|-----|
| SDK包名 | `@polymarket/clob-client` | `@polymarket/clob-client-v2` |
| 构造函数 | 位置参数 | 选项对象 (`chainId` → `chain`) |
| 订单字段（移除） | `nonce`, `feeRateBps`, `taker`, `expiration` | 已移除，不再使用 |
| 订单字段（新增） | - | `timestamp`(毫秒), `metadata`(bytes32), `builder`(bytes32) |
| 费用设置 | 订单中嵌入 (`feeRateBps`) | 匹配时由协议设定，通过 `getClobMarketInfo()` 查询 |
| 抵押品代币 | USDC.e | pUSD (Polymarket USD) |
| Builder认证 | HMAC headers | 单个 `builderCode` 字段 |
| EIP-712版本 | `"1"` | `"2"` |
| Exchange合约地址 | V1地址 | V2地址 (见下文) |
| 订单取消 | 链上 `cancel()` | 运营商控制的 `pauseUser` / `unpauseUser` |
| CLOB URL | `clob.polymarket.com` | `clob-v2.polymarket.com` |

---

## 🏗️ 合约地址变更

### 标准CTF Exchange
```kotlin
// V1 (旧)
private val EXCHANGE_CONTRACT = "0x4bFb41d5B3570DeFd03C39a9A4D8dE6Bd8B8982E"

// V2 (新)
private val EXCHANGE_CONTRACT_V2 = "0xE111180000d2663C0091e4f400237545B87B996B"
```

### Neg Risk CTF Exchange
```kotlin
// V1 (旧)
private val NEG_RISK_EXCHANGE_CONTRACT = "0xC5d563A36AE78145C45a50134d48A1215220f80a"

// V2 (新)
private val NEG_RISK_EXCHANGE_CONTRACT_V2 = "0xe2222d279d744050d28e00520010520000310F59"
```

---

## 📝 详细代码改动

### 1. EIP-712编码器更新 (`Eip712Encoder.kt`)

#### 1.1 更新域分隔符编码方法

**位置：** `Eip712Encoder.kt:174-201`

**直接替换现有方法：**

```kotlin
/**
 * 编码 ExchangeOrder V2 域分隔符
 * Domain: { name: "Polymarket CTF Exchange", version: "2", chainId: chainId, verifyingContract: exchangeContract }
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
    val versionHash = encodeString("2")  // V2：版本从 "1" 改为 "2"
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
```

#### 1.2 替换订单编码方法

**位置：** `Eip712Encoder.kt:208-280`

**直接替换现有方法：**

```kotlin
/**
 * 编码 ExchangeOrder V2 消息哈希
 * 参考: CLOB V2 迁移指南
 * Order V2: { salt, maker, signer, tokenId, makerAmount, takerAmount, side, signatureType, timestamp, metadata, builder }
 */
fun encodeExchangeOrder(
    salt: Long,
    maker: String,
    signer: String,
    tokenId: String,
    makerAmount: String,
    takerAmount: String,
    side: String,
    signatureType: Int,
    timestamp: String,      // V2：订单创建时间（毫秒）
    metadata: String,        // V2：bytes32 元数据
    builder: String          // V2：bytes32 builder代码
): ByteArray {
    val orderTypeHash = encodeType(
        "Order",
        listOf(
            "salt" to "uint256",
            "maker" to "address",
            "signer" to "address",
            "tokenId" to "uint256",
            "makerAmount" to "uint256",
            "takerAmount" to "uint256",
            "side" to "uint8",
            "signatureType" to "uint8",
            "timestamp" to "uint256",    // V2字段
            "metadata" to "bytes32",      // V2字段
            "builder" to "bytes32"        // V2字段
        )
    )
    
    // 编码订单字段
    val saltBytes = encodeUint256(BigInteger.valueOf(salt))
    val makerBytes = encodeAddress(maker)
    val signerBytes = encodeAddress(signer)
    val tokenIdBytes = encodeUint256(BigInteger(tokenId))
    val makerAmountBytes = encodeUint256(BigInteger(makerAmount))
    val takerAmountBytes = encodeUint256(BigInteger(takerAmount))
    
    // side: BUY = 0, SELL = 1
    val sideValue = when (side.uppercase()) {
        "BUY" -> 0
        "SELL" -> 1
        else -> throw IllegalArgumentException("side 必须是 BUY 或 SELL")
    }
    val sideBytes = encodeUint256(BigInteger.valueOf(sideValue.toLong()))
    val signatureTypeBytes = encodeUint256(BigInteger.valueOf(signatureType.toLong()))
    
    // V2 字段编码
    val timestampBytes = encodeUint256(BigInteger(timestamp))
    val metadataBytes = Numeric.hexStringToByteArray(metadata.removePrefix("0x").padStart(64, '0'))
    val builderBytes = Numeric.hexStringToByteArray(builder.removePrefix("0x").padStart(64, '0'))
    
    // 组合所有字段 (typeHash + 11个字段 = 12个slot)
    val encoded = ByteArray(32 * 12)
    var offset = 0
    
    System.arraycopy(orderTypeHash, 0, encoded, offset, 32); offset += 32
    System.arraycopy(saltBytes, 0, encoded, offset, 32); offset += 32
    System.arraycopy(makerBytes, 0, encoded, offset, 32); offset += 32
    System.arraycopy(signerBytes, 0, encoded, offset, 32); offset += 32
    System.arraycopy(tokenIdBytes, 0, encoded, offset, 32); offset += 32
    System.arraycopy(makerAmountBytes, 0, encoded, offset, 32); offset += 32
    System.arraycopy(takerAmountBytes, 0, encoded, offset, 32); offset += 32
    System.arraycopy(sideBytes, 0, encoded, offset, 32); offset += 32
    System.arraycopy(signatureTypeBytes, 0, encoded, offset, 32); offset += 32
    System.arraycopy(timestampBytes, 0, encoded, offset, 32); offset += 32      // V2 新增
    System.arraycopy(metadataBytes, 0, encoded, offset, 32); offset += 32       // V2 新增
    System.arraycopy(builderBytes, 0, encoded, offset, 32)                     // V2 新增
    
    return keccak256(encoded)
}
```

---



### 2. 订单对象结构更新 (`PolymarketClobApi.kt`)

#### 2.1 创建新的V2订单对象

**位置：** `PolymarketClobApi.kt`

```kotlin
/**
 * 签名的订单对象 V2
 * 参考: https://docs.polymarket.com/v2-migration
 *
 * V2 移除了以下 V1 字段: taker, expiration, nonce, feeRateBps
 * V2 新增了以下字段: timestamp, metadata, builder
 */
data class SignedOrderObject(
    val salt: Long,                    // random salt
    val maker: String,                 // maker address (funder)
    val signer: String,                // signing address
    val tokenId: String,               // ERC1155 token ID
    val makerAmount: String,           // maximum amount maker is willing to spend
    val takerAmount: String,           // minimum amount taker will pay
    val side: String,                  // "BUY" or "SELL"
    val signatureType: Int,            // signature type enum index
    val timestamp: String,             // 订单创建时间戳（毫秒）V2新增
    val metadata: String,              // bytes32 元数据 V2新增
    val builder: String,               // bytes32 builder代码 V2新增
    val signature: String              // hex encoded signature
)
```

#### 2.2 更新API请求对象

**位置：** `PolymarketClobApi.kt:200-205`

**直接替换现有类：**

```kotlin
/**
 * 创建订单请求 (V2)
 */
data class NewOrderRequest(
    val order: SignedOrderObject,     // V2 signed object
    val owner: String,                 // api key of order owner
    val orderType: String              // "FOK", "GTC", "GTD", "FAK"
)
```

---

### 3. 订单签名服务更新 (`OrderSigningService.kt`)

#### 3.1 替换合约地址常量

**位置：** `OrderSigningService.kt:45-47`

**直接替换现有常量：**

```kotlin
// V2 合约地址
private val EXCHANGE_CONTRACT = "0xE111180000d2663C0091e4f400237545B87B996B"
private val NEG_RISK_EXCHANGE_CONTRACT = "0xe2222d279d744050d28e00520010520000310F59"
```

#### 3.2 简化getExchangeContract方法

**位置：** `OrderSigningService.kt:30-32`

**直接替换现有方法：**

```kotlin
/**
 * 根据是否为 Neg Risk 市场返回签约用 exchange 合约地址
 * @param negRisk true 时使用 Neg Risk CTF Exchange，否则使用标准 CTF Exchange
 */
fun getExchangeContract(negRisk: Boolean): String {
    return if (negRisk) NEG_RISK_EXCHANGE_CONTRACT else EXCHANGE_CONTRACT
}
```

#### 3.3 替换createAndSignOrder方法

**位置：** `OrderSigningService.kt:174-259`

**直接替换现有方法：**

```kotlin
/**
 * 创建并签名订单 (V2)
 *
 * @param privateKey 私钥
 * @param makerAddress maker 地址
 * @param tokenId token ID
 * @param side BUY 或 SELL
 * @param price 价格
 * @param size 数量
 * @param signatureType 签名类型（1: Email/Magic, 2: Browser Wallet）
 * @param exchangeContract 签约用 exchange 合约地址；null 时用标准 CTF Exchange
 * @param builderCode builder代码（bytes32，可选）
 * @param metadata 元数据（bytes32，默认为零）
 * @return V2 签名的订单对象
 */
fun createAndSignOrder(
    privateKey: String,
    makerAddress: String,
    tokenId: String,
    side: String,
    price: String,
    size: String,
    signatureType: Int = 2,
    exchangeContract: String? = null,
    builderCode: String? = null,
    metadata: String = "0x0000000000000000000000000000000000000000000000000000000000000000"
): SignedOrderObject {
    try {
        // 1. 从私钥获取签名地址
        val cleanPrivateKey = privateKey.removePrefix("0x")
        val privateKeyBigInt = BigInteger(cleanPrivateKey, 16)
        val credentials = Credentials.create(privateKeyBigInt.toString(16))
        val signerAddress = credentials.address.lowercase()

        // 2. 计算订单金额（复用现有逻辑）
        val amounts = calculateOrderAmounts(side, size, price)

        // 3. 生成 salt 和 timestamp（毫秒）
        //    V2: 使用 timestamp 替代 nonce 保证订单唯一性
        val salt = generateSalt()
        val timestamp = System.currentTimeMillis().toString()

        // 4. 处理 builder 和 metadata
        val builder = builderCode?.takeIf { it.isNotBlank() }
            ?: "0x0000000000000000000000000000000000000000000000000000000000000000"
        val metadataClean = if (metadata.isNotBlank()) metadata
            else "0x0000000000000000000000000000000000000000000000000000000000000000"

        // 5. 确保 maker 地址是小写格式
        val makerAddressLower = makerAddress.lowercase()

        logger.debug("========== 订单签名前参数 (V2) ==========")
        logger.debug("订单方向: $side, 价格: $price, 数量: $size")
        logger.debug("Token ID: $tokenId")
        logger.debug("Maker: ${makerAddressLower.take(10)}...${makerAddressLower.takeLast(6)}")
        logger.debug("Signer: ${signerAddress.take(10)}...${signerAddress.takeLast(6)}")
        logger.debug("Amounts - Maker: ${amounts.makerAmount}, Taker: ${amounts.takerAmount}")
        logger.debug("Salt: $salt, Timestamp: $timestamp")
        logger.debug("Builder: $builder, Metadata: $metadataClean")
        logger.debug("Signature Type: $signatureType")

        // 6. 使用合约地址
        val contract = exchangeContract?.takeIf { it.isNotBlank() } ?: EXCHANGE_CONTRACT

        // 7. 构建V2签名
        val signature = signOrder(
            privateKey = privateKey,
            exchangeContract = contract,
            chainId = CHAIN_ID,
            salt = salt,
            maker = makerAddressLower,
            signer = signerAddress,
            tokenId = tokenId,
            makerAmount = amounts.makerAmount,
            takerAmount = amounts.takerAmount,
            side = side.uppercase(),
            signatureType = signatureType,
            timestamp = timestamp,
            metadata = metadataClean,
            builder = builder
        )

        // 8. 创建V2签名订单对象
        //    注意: V2不再包含 taker, expiration, nonce, feeRateBps 字段
        return SignedOrderObject(
            salt = salt,
            maker = makerAddressLower,
            signer = signerAddress,
            tokenId = tokenId,
            makerAmount = amounts.makerAmount,
            takerAmount = amounts.takerAmount,
            side = side.uppercase(),
            signatureType = signatureType,
            timestamp = timestamp,
            metadata = metadataClean,
            builder = builder,
            signature = signature
        )
    } catch (e: Exception) {
        logger.error("创建并签名订单失败 (V2)", e)
        throw RuntimeException("创建并签名订单失败 (V2): ${e.message}", e)
    }
}
```

#### 3.4 替换signOrder方法

**位置：** `OrderSigningService.kt:266-334`

**直接替换现有方法：**

```kotlin
/**
 * 签名订单 V2（EIP-712）
 * V2 Order: salt, maker, signer, tokenId, makerAmount, takerAmount, side, signatureType, timestamp, metadata, builder
 */
private fun signOrder(
    privateKey: String,
    exchangeContract: String,
    chainId: Long,
    salt: Long,
    maker: String,
    signer: String,
    tokenId: String,
    makerAmount: String,
    takerAmount: String,
    side: String,
    signatureType: Int,
    timestamp: String,
    metadata: String,
    builder: String
): String {
    try {
        // 1. 私钥与密钥对
        val cleanPrivateKey = privateKey.removePrefix("0x")
        val privateKeyBigInt = BigInteger(cleanPrivateKey, 16)
        val credentials = Credentials.create(privateKeyBigInt.toString(16))
        val ecKeyPair = credentials.ecKeyPair

        // 2. 编码域分隔符（V2：版本为 "2"）
        val domainSeparator = com.wrbug.polymarketbot.util.Eip712Encoder.encodeExchangeDomain(
            chainId = chainId,
            verifyingContract = exchangeContract.lowercase()
        )

        // 3. 编码V2订单消息哈希（11个字段，不含V1的taker/expiration/nonce/feeRateBps）
        val orderHash = com.wrbug.polymarketbot.util.Eip712Encoder.encodeExchangeOrder(
            salt = salt,
            maker = maker,
            signer = signer,
            tokenId = tokenId,
            makerAmount = makerAmount,
            takerAmount = takerAmount,
            side = side,
            signatureType = signatureType,
            timestamp = timestamp,
            metadata = metadata,
            builder = builder
        )

        // 4. 计算完整 EIP-712 结构化数据哈希
        val structuredHash = com.wrbug.polymarketbot.util.Eip712Encoder.hashStructuredData(
            domainSeparator = domainSeparator,
            messageHash = orderHash
        )

        // 5. 使用私钥签名
        val signature = org.web3j.crypto.Sign.signMessage(structuredHash, ecKeyPair, false)

        // 6. 组合 r + s + v
        val rHex = org.web3j.utils.Numeric.toHexString(signature.r).removePrefix("0x").padStart(64, '0')
        val sHex = org.web3j.utils.Numeric.toHexString(signature.s).removePrefix("0x").padStart(64, '0')
        val vBytes = signature.v
        val vInt = if (vBytes.isNotEmpty()) vBytes[0].toInt() and 0xff else 0
        val vHex = "%02x".format(vInt)

        return "0x$rHex$sHex$vHex"
    } catch (e: Exception) {
        logger.error("订单签名失败 (V2)", e)
        throw RuntimeException("订单签名失败 (V2): ${e.message}", e)
    }
}
```

---

### 4. Builder认证系统清理

#### 4.1 移除BuilderAuthInterceptor

**位置：** `BuilderAuthInterceptor.kt`

**删除整个文件：**
```bash
# V2不再需要HMAC builder认证，直接删除此文件
rm BuilderAuthInterceptor.kt
```

#### 4.2 更新Builder配置

**创建新的Builder配置类：**

```kotlin
/**
 * V2 Builder配置
 */
data class BuilderConfigV2(
    val builderCode: String,  // bytes32格式的builder代码
    val enabled: Boolean = true
)
```

#### 4.3 更新使用Builder认证的代码

**检查并更新所有使用 `BuilderAuthInterceptor` 的地方：**

```kotlin
// V1 (旧)
val builderApi = RetrofitFactory.createRetrofit(
    baseUrl = BUILDER_RELAYER_URL,
    interceptors = listOf(
        BuilderAuthInterceptor(apiKey, secret, passphrase)
    )
).create(BuilderRelayerApi::class.java)

// V2 (新) - 移除Builder认证拦截器
val builderApi = RetrofitFactory.createRetrofit(
    baseUrl = BUILDER_RELAYER_URL,
    interceptors = listOf()  // V2不需要额外的认证拦截器
).create(BuilderRelayerApi::class.java)

// builderCode直接在订单中设置
val order = orderSigningService.createAndSignOrder(
    // ... 其他参数
    builderCode = builderConfig.builderCode
)
```

---

### 5. API端点更新

#### 5.1 移除Builder相关端点

**移除以下可能不再需要的端点：**

```kotlin
// 删除这些端点（V2不再需要）
// @POST("/auth/builder-api-key")
// suspend fun createBuilderApiKey(): Response<BuilderApiKeyResponse>

// @GET("/auth/builder-api-key")  
// suspend fun getBuilderApiKeys(): Response<List<BuilderApiKey>>
```

#### 5.2 更新费率查询端点（如需要）

**检查是否需要更新费率查询：**

```kotlin
// V2可能使用新的端点（需要根据实际API文档确认）
@GET("/clob-market-info")
suspend fun getClobMarketInfo(@Query("market") market: String): Response<ClobMarketInfoResponse>

// V2 响应结构
data class ClobMarketInfoResponse(
    val feeRate: Int,           // 市场费率
    val feeExponent: Double,    // 费用指数
    val builderFeeRate: Int?    // Builder费率（如果有）
)
```

#### 5.3 更新CLOB API基础URL

**V2使用新的CLOB URL：**

```kotlin
// V1 (旧)
const val CLOB_BASE_URL = "https://clob.polymarket.com"

// V2 (新)
const val CLOB_BASE_URL = "https://clob-v2.polymarket.com"
```

#### 5.4 更新订单取消方式

**V2取消了链上 `cancel()` 操作，改为运营商控制：**

```kotlin
// V1: 链上取消订单
// cancelOrder(orderId) -> DELETE /orders/{orderId}

// V2: 运营商控制的 pauseUser / unpauseUser
// 取消订单的API端点可能保持不变（DELETE /orders/{orderId}）
// 但链上取消机制已移除，改为运营商暂停/恢复用户
```

> **注意：** 需要验证 `DELETE /orders/{orderId}` 和 `DELETE /orders/batch` 端点在 V2 中是否仍可用。

---

### 6. Nonce追踪逻辑移除

V2 使用 `timestamp`（毫秒）替代 `nonce` 保证订单唯一性。需要移除所有 nonce 相关逻辑：

```kotlin
// 移除以下V1代码：
// - nonce 参数传递
// - nonce 追踪/缓存逻辑
// - getNonce() 调用

// V2: 直接使用 timestamp
val timestamp = System.currentTimeMillis().toString()
```

---

### 7. 抵押品代币更新 (USDC.e → pUSD)

V2 使用 pUSD (Polymarket USD) 替代 USDC.e：

```kotlin
// V1 (旧)
const val COLLATERAL_TOKEN = "0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174" // USDC.e

// V2 (新)
const val COLLATERAL_TOKEN_PUSD = "0xC011a7E12a19f7B1f670d46F03B03f3342E82DFB" // pUSD

// USDC.e 仍保留用于 wrap 操作
const val USDCE_CONTRACT = "0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174"

// CollateralOnramp (USDC.e → pUSD)
const val COLLATERAL_ONRAMP = "0x93070a847efEf7F70739046A929D47a521F5B8ee"
// 参考: https://docs.polymarket.com/concepts/pusd
```

#### 7.1 USDC.e → pUSD Wrap 实现

**后端已实现自动 wrap 功能：**

- `RelayClientService.createUsdceApproveForWrapTx()` — 创建 USDC.e approve 交易
- `RelayClientService.createWrapToPusdTx()` — 创建 wrap 交易
- `BlockchainService.wrapUsdcToPusd()` — 组合 approve + wrap 为 MultiSend 原子执行
- `BlockchainService.queryUsdceBalance()` — 查询 USDC.e 余额

**前端已添加迁移按钮：**

- 账户管理列表中每个账户的操作栏增加了 `SwapOutlined` 迁移按钮
- 点击后查询 USDC.e 余额，有余额时弹出确认框
- 确认后自动执行 approve + wrap 原子交易

**后端 API 端点：**
- `POST /api/accounts/wrap-to-pusd` — 执行 wrap
- `POST /api/accounts/usdce-balance` — 查询 USDC.e 余额

---

### 8. 迁移期间重要注意事项

**迁移当天（2026年4月28日 ~11:00 UTC）的关键变化：**

- **所有挂单将被清空。** 迁移完成后必须重新下单。
- V1 SDK/客户端将立即停止工作，无向后兼容性。
- 预计约1小时停机时间，交易暂停。
- 通过 Discord、Telegram 和 status.polymarket.com 获取确切维护窗口开始时间。

```kotlin
// 迁移前：停止自动交易策略
tradingService.pauseAllTrading("CLOB V2迁移准备")

// 迁移后：更新配置并重新启动
configService.updateClobBaseUrl(CLOB_BASE_URL_V2)
configService.updateExchangeContracts(V2_CONTRACTS)
tradingService.resumeAllTrading("CLOB V2迁移完成")

// 重新下挂单
orderRecoveryService.replayOpenOrders()
```

### 1. 单元测试

#### 1.1 EIP-712编码测试

```kotlin
@Test
fun `test V2 EIP-712 encoding matches expected format`() {
    val domain = Eip712Encoder.encodeExchangeDomain(
        chainId = 137L,
        verifyingContract = "0xE111180000d2663C0091e4f400237545B87B996B"
    )

    // 验证域分隔符
    val expectedDomainHash = "0x..." // 从官方文档或TypeScript SDK获取
    assertEquals(expectedDomainHash, Numeric.toHexString(domain))
}

@Test
fun `test V2 order encoding includes new fields`() {
    val orderHash = Eip712Encoder.encodeExchangeOrder(
        salt = 12345L,
        maker = "0x...",
        signer = "0x...",
        tokenId = "12345",
        makerAmount = "1000000",
        takerAmount = "2000000",
        side = "BUY",
        signatureType = 2,
        timestamp = "1713398400000",
        metadata = "0x0000000000000000000000000000000000000000000000000000000000000000",
        builder = "0x0000000000000000000000000000000000000000000000000000000000000000"
    )
    
    // 验证订单哈希格式
    assertNotNull(orderHash)
    assertEquals(32, orderHash.size)
}
```

#### 1.2 订单签名测试

```kotlin
@Test
fun `test order signing produces valid signature`() {
    val testPrivateKey = "0x..." // 测试私钥
    val order = orderSigningService.createAndSignOrder(
        privateKey = testPrivateKey,
        makerAddress = "0x...",
        tokenId = "12345",
        side = "BUY",
        price = "0.5",
        size = "10"
    )
    
    // 验证订单结构
    assertTrue(order.timestamp.isNotBlank())
    assertTrue(order.metadata.startsWith("0x"))
    assertTrue(order.builder.startsWith("0x"))
    assertEquals(132, order.signature.length) // 0x(2) + r(64) + s(64) + v(2)
}
```

### 2. 集成测试

#### 2.1 订单创建测试

```kotlin
@Test
@DisplayName("订单创建和提交测试 (V2)")
fun `test create and post order`() {
    // 1. 创建订单
    val order = orderSigningService.createAndSignOrder(
        privateKey = testConfig.privateKey,
        makerAddress = testConfig.makerAddress,
        tokenId = testTokenId,
        side = "BUY",
        price = "0.5",
        size = "10"
    )
    
    // 2. 提交到API
    val response = polymarketClobApi.createOrder(
        NewOrderRequest(
            order = order,
            owner = testConfig.apiKey,
            orderType = "GTC"
        )
    )
    
    // 3. 验证响应
    assertTrue(response.isSuccessful)
    assertNotNull(response.body()?.orderId)
}
```

#### 2.2 Builder功能测试

```kotlin
@Test
@DisplayName("Builder代码测试 (V2)")
fun `test builder code field`() {
    val builderCode = "0x1234...5678" // 有效的builder代码
    
    val order = orderSigningService.createAndSignOrder(
        privateKey = testConfig.privateKey,
        makerAddress = testConfig.makerAddress,
        tokenId = testTokenId,
        side = "SELL",
        price = "0.6",
        size = "5",
        builderCode = builderCode
    )
    
    // 验证builder字段设置正确
    assertEquals(builderCode.lowercase(), order.builder.lowercase())
}
```

### 3. 测试市场数据

根据官方文档，使用以下市场进行测试：

```kotlin
// 测试市场数据
val TEST_MARKETS = listOf(
    TestMarket(
        name = "US / Iran nuclear deal in 2027?",
        eventId = "73106",
        orderbookTokenId = "102936...7216"
    ),
    TestMarket(
        name = "Highest grossing movie in 2026?",
        eventId = "79831",
        orderbookTokenIds = listOf(
            "81662...2777",
            "17546...1707",
            "28161...2479",
            "89576...4694",
            "21556...6607",
            "51020...2516"
        )
    )
)
```

---

## 📋 迁移检查清单

### 阶段1：准备阶段（完成时间：迁移前2周）

- [ ] 备份现有代码库
- [ ] 创建迁移分支 `feature/clob-v2-migration`
- [ ] 设置测试环境和测试账户
- [ ] 获取V2测试网络的builder code（如需要）
- [ ] 准备测试数据和测试用例

### 阶段2：代码迁移（完成时间：迁移前1周）

- [ ] 更新EIP-712编码器（替换为V2方法，版本号 `"1"` → `"2"`）
- [ ] 更新订单对象结构（移除 `taker`, `expiration`, `nonce`, `feeRateBps`，新增 `timestamp`, `metadata`, `builder`）
- [ ] 更新订单签名服务（替换为V2签名，移除 `expiration` 和 `nonce` 参数）
- [ ] 更新合约地址常量（标准 Exchange 和 Neg Risk Exchange）
- [ ] 移除所有 nonce 追踪/缓存逻辑
- [ ] 移除Builder认证相关代码（`BuilderAuthInterceptor` 和 HMAC headers）
- [ ] 更新API端点方法和CLOB基础URL（`clob-v2.polymarket.com`）
- [ ] 更新抵押品代币余额检查逻辑（USDC.e → pUSD）
- [ ] 验证订单取消API端点（`DELETE /orders/{orderId}`）是否仍可用
- [ ] 更新依赖配置（如有需要）

### 阶段3：测试验证（完成时间：迁移前3天）

- [ ] 编写V2单元测试
- [ ] 编写V2集成测试
- [ ] 在测试环境执行完整测试
- [ ] 验证订单签名正确性
- [ ] 验证订单提交成功率
- [ ] 测试builder code功能
- [ ] 压力测试和性能验证

### 阶段4：部署准备（完成时间：迁移前1天）

- [ ] 代码审查
- [ ] 更新API文档
- [ ] 准备回滚方案
- [ ] 通知用户迁移计划
- [ ] 准备监控和告警

### 阶段5：正式迁移（完成时间：2026年4月28日）

- [ ] 在迁移窗口开始时停止交易
- [ ] **备份所有挂单数据**（迁移后所有挂单将被清空）
- [ ] 部署更新后的代码到生产环境
- [ ] 更新CLOB基础URL到 `clob-v2.polymarket.com`
- [ ] 执行冒烟测试
- [ ] **重新下单**（迁移完成后必须重新提交所有挂单）
- [ ] 重新启用交易功能
- [ ] 监控系统运行状态
- [ ] 验证关键功能正常

### 阶段6：迁移后验证（完成时间：迁移后1周）

- [ ] 持续监控系统性能
- [ ] 收集用户反馈
- [ ] 修复发现的问题
- [ ] 优化V2实现
- [ ] 清理临时测试代码

---

## 🚨 风险和注意事项

### 1. 关键风险点

#### 1.1 签名不兼容风险
- **风险：** V2签名与V1不兼容，混用会导致订单被拒绝
- **缓解措施：** 
  - 明确区分V1和V2方法
  - 添加版本检查和验证
  - 在切换时确保所有组件同步更新

#### 1.2 合约地址错误风险
- **风险：** 使用V1地址签名V2订单会被拒绝
- **缓解措施：**
  - 在代码中添加地址版本检查
  - 使用配置文件管理合约地址
  - 添加地址验证测试

#### 1.3 停机时间风险
- **风险：** 迁移期间约1小时的停机
- **缓解措施：**
  - 提前通知用户
  - 暂停自动交易策略
  - 准备快速回滚方案

### 2. 业务逻辑变更

#### 2.1 费用计算变更
- **V1：** 费用嵌入在订单中（`feeRateBps`）
- **V2：** 费用由协议在匹配时决定，公式：`fee = C × feeRate × p × (1 - p)`
- **关键：** Maker 不收取费用，仅 Taker 付费
- **影响：** 无法提前知道确切费用，订单中不再设置 `feeRateBps`
- **应对：** 更新费用预估逻辑，使用 `getClobMarketInfo()` 获取市场费率

#### 2.2 Builder功能变更
- **V1：** 使用HMAC headers认证
- **V2：** 使用订单中的`builderCode`字段
- **影响：** 需要转换现有的builder配置
- **应对：** 联系Polymarket获取builder code，更新配置

#### 2.3 抵押品代币变更
- **V1：** USDC.e
- **V2：** pUSD (Polymarket USD)
- **影响：** 需要处理代币转换
- **应对：** 更新代币余额检查逻辑，支持pUSD

### 3. 监控和告警

#### 3.1 关键监控指标

```kotlin
// 添加V2特定的监控指标
object ClobV2Metrics {
    // 订单创建成功率
    val orderCreationSuccess = Counter.build()
        .name("clob_v2_order_creation_success_total")
        .help("V2订单创建成功次数")
        .register()
    
    // 订单提交成功率
    val orderSubmissionSuccess = Counter.build()
        .name("clob_v2_order_submission_success_total")
        .help("V2订单提交成功次数")
        .register()
    
    // 签名验证失败率
    val signatureValidationFailure = Counter.build()
        .name("clob_v2_signature_validation_failure_total")
        .help("V2签名验证失败次数")
        .register()
    
    // API响应时间
    val apiResponseTime = Histogram.build()
        .name("clob_v2_api_response_time_seconds")
        .help("V2 API响应时间")
        .register()
}
```

#### 3.2 告警配置

```yaml
# Prometheus告警规则示例
groups:
  - name: clob_v2_alerts
    rules:
      - alert: HighV2OrderFailureRate
        expr: rate(clob_v2_order_submission_failure_total[5m]) > 0.1
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "V2订单失败率过高"
          description: "过去5分钟内V2订单失败率超过10%"
      
      - alert: V2SignatureValidationFailure
        expr: rate(clob_v2_signature_validation_failure_total[5m]) > 0.05
        for: 1m
        labels:
          severity: warning
        annotations:
          summary: "V2签名验证失败"
          description: "检查EIP-712编码和签名逻辑"
```

---

## 🔄 回滚方案

### 1. 回滚触发条件

- V2订单失败率超过20%
- 系统出现严重错误影响交易
- 签名验证大规模失败
- API响应时间超过阈值（如10秒）

### 2. 回滚步骤

1. **立即停止交易**
   ```kotlin
   tradingService.pauseAllTrading("执行V2回滚")
   ```

2. **切换回V1代码**
   ```bash
   git checkout main  # 回到迁移前的分支
   ./deploy.sh       # 重新部署
   ```

3. **验证V1功能**
   ```bash
   curl -X POST https://api.example.com/health/check
   ```

4. **恢复交易**
   ```kotlin
   tradingService.resumeAllTrading("回滚完成，恢复V1交易")
   ```

### 3. 回滚后验证

- [ ] 检查系统日志
- [ ] 验证V1订单功能正常
- [ ] 检查数据库一致性
- [ ] 通知用户回滚完成
- [ ] 分析失败原因

---

## 📚 参考资源

### 官方文档
- [Polymarket CLOB V2迁移指南](https://docs.polymarket.com/v2-migration)
- [CLOB API文档](https://docs.polymarket.com/developers/CLOB)
- [EIP-712标准](https://eips.ethereum.org/EIPS/eip-712)

### TypeScript SDK参考
- [clob-client-v2 GitHub](https://github.com/Polymarket/clob-client-v2)
- [V2订单类型定义](https://github.com/Polymarket/clob-client-v2/blob/main/src/types/ordersV2.ts)

### 合约地址
- [V2合约地址列表](https://docs.polymarket.com/contracts)
- [pUSD合约信息](https://docs.polymarket.com/pusd)

### 测试资源
- [测试市场列表](https://docs.polymarket.com/v2-migration#test-markets)
- [Polymarket Discord](https://discord.gg/polymarket)
- [状态页面](https://status.polymarket.com)

---

## 🆘 支持和联系

### 技术支持
- **Discord:** https://discord.gg/polymarket
- **Telegram:** Polymarket官方频道
- **Email:** support@polymarket.com

### 问题报告
- **GitHub Issues:** https://github.com/Polymarket/clob-client-v2/issues
- **Bug报告:** 在Discord技术支持频道报告

### 更新通知
- **Twitter:** @Polymarket
- **Blog:** https://polymarket.com/blog
- **状态页:** https://status.polymarket.com

---

## 📝 变更日志

### 文档版本
- **v1.0** (2025-01-20): 初始版本，包含完整的迁移指南
- **v1.1** (2025-01-XX): 添加测试市场信息和监控配置示例

### 重大更新
- 2025-01-20: 创建迁移文档，基于Polymarket官方V2迁移指南

---

## ⚠️ 最终检查清单

在执行迁移前，请确认：

- [ ] 已仔细阅读官方迁移文档
- [ ] 已在测试环境完成所有测试（使用 `clob-v2.polymarket.com` 测试市场）
- [ ] 已备份生产数据库
- [ ] 已备份所有挂单数据（**迁移后挂单将被清空，必须重新下单**）
- [ ] 已移除所有 V1 字段（`taker`, `expiration`, `nonce`, `feeRateBps`）
- [ ] 已移除 nonce 追踪逻辑
- [ ] 已移除 Builder HMAC 认证相关代码
- [ ] 已更新合约地址和 EIP-712 domain version
- [ ] 已通知所有相关用户
- [ ] 已准备回滚方案
- [ ] 已设置监控和告警
- [ ] 团队成员已了解迁移流程
- [ ] 已准备迁移期间的客服支持

**记住：一旦迁移开始，无法中止。V1将立即停止工作，无向后兼容性。确保所有准备工作完成后再开始！**

---

*本文档基于Polymarket官方V2迁移指南编写，如有疑问请参考官方文档或联系技术支持。*
