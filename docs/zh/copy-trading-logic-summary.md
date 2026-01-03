# 跟单买卖逻辑简易文档

## 一、跟单信号检测

### 1.1 监控方式

系统使用**轮询方式**监控 Leader 的交易活动：

- **数据源**：Polymarket Data API 的 `/activity` 接口
- **轮询间隔**：默认 2 秒（可配置）
- **查询参数**：
  - `user`: Leader 钱包地址
  - `type`: `["TRADE"]`（只查询交易类型）
  - `limit`: 100（每次最多查询 100 条）
  - `sortBy`: `TIMESTAMP`
  - `sortDirection`: `DESC`（按时间戳降序，最新的在前）

### 1.2 增量检测机制

通过 **diff 算法**检测新增交易：

1. **首次轮询**：
   - 查询最近 100 条交易
   - 缓存所有交易 ID（不处理）
   - 标记首次轮询完成

2. **后续轮询**：
   - 查询最近 100 条交易
   - 与缓存的交易 ID 集合进行 diff
   - 找出新增的交易 ID
   - 处理新增交易
   - 更新缓存（添加新增的交易 ID）

3. **去重机制**：
   - 使用 `leaderId + tradeId` 作为唯一标识
   - 在 `processed_trade` 表中记录已处理的交易
   - 避免重复处理同一笔交易

### 1.3 交易数据转换

从 `UserActivityResponse` 转换为 `TradeResponse`：

```kotlin
TradeResponse(
    id = activity.transactionHash,  // 交易ID（用于去重）
    market = activity.conditionId,  // 市场ID
    side = activity.side,  // "BUY" 或 "SELL"
    price = activity.price,  // 交易价格
    size = activity.size,  // 交易数量
    timestamp = activity.timestamp,  // 时间戳（秒）
    user = activity.proxyWallet,  // 用户钱包地址
    outcomeIndex = activity.outcomeIndex,  // 结果索引（0=第一个outcome，1=第二个outcome）
    outcome = activity.outcome  // 结果名称
)
```

### 1.4 信号触发流程

```
轮询任务启动
    ↓
定期轮询所有 Leader（每 2 秒）
    ↓
查询 Leader 活动（/activity 接口）
    ↓
转换为 TradeResponse
    ↓
diff 检测新增交易
    ↓
调用 processTrade() 处理交易
    ↓
根据 side 字段判断：
    - "BUY" → processBuyTrade()
    - "SELL" → processSellTrade()
```

## 二、订单构建

### 2.1 买入订单构建流程

#### 2.1.1 前置检查

1. **查找跟单关系**：
   - 查询所有启用且支持该 Leader 的跟单配置
   - 验证账户 API 凭证是否配置
   - 验证账户是否启用

2. **获取 Token ID**：
   - 使用 `outcomeIndex` 和 `market` 获取 tokenId
   - 支持多元市场（不限于 YES/NO）

3. **计算买入数量**：
   - **RATIO 模式**：`买入数量 = Leader 数量 × 跟单比例`
   - **FIXED 模式**：`买入数量 = 固定金额 / 买入价格`

4. **过滤条件检查**：
   - 价格区间检查
   - 仓位限制检查
   - 市场分类检查
   - 订单簿检查（获取最佳卖单价格）

5. **价格调整**：
   - 应用价格容忍度（`priceTolerance`）
   - 买入价格 = Leader 价格 × (1 + 容忍度)
   - 确保调整后的价格不低于最佳卖单价格

#### 2.1.2 订单签名

使用 `OrderSigningService.createAndSignOrder()` 创建并签名订单：

1. **计算订单金额**：
   - **BUY 订单**：
     - `makerAmount = price × size`（USDC 金额，最多 2 位小数）
     - `takerAmount = size`（shares 数量，最多 4 位小数）
   - 转换为 wei（6 位小数）

2. **生成订单参数**：
   - `salt`: 时间戳（毫秒）
   - `maker`: 代理钱包地址（proxyAddress）
   - `signer`: 从私钥推导的签名地址
   - `taker`: 零地址（0x0000...）
   - `tokenId`: 从 outcomeIndex 获取
   - `makerAmount`: 计算出的 maker 金额（wei）
   - `takerAmount`: 计算出的 taker 金额（wei）
   - `expiration`: "0"（永不过期）
   - `nonce`: "0"
   - `feeRateBps`: "0"
   - `side`: "BUY"
   - `signatureType`: 2（Browser Wallet）

3. **EIP-712 签名**：
   - 编码域分隔符（Exchange Contract + Chain ID）
   - 编码订单消息哈希
   - 计算结构化数据哈希
   - 使用私钥签名（r + s + v）

#### 2.1.3 创建订单请求

构建 `NewOrderRequest`：

```kotlin
NewOrderRequest(
    order = signedOrder,  // 签名的订单对象
    owner = account.apiKey,  // API Key
    orderType = "FAK",  // Fill-And-Kill（允许部分成交，未成交部分立即取消）
    deferExec = false  // 立即执行
)
```

#### 2.1.4 提交订单

1. **创建 CLOB API 客户端**（带认证）：
   - 使用账户的 API Key、Secret、Passphrase
   - 解密 API 凭证

2. **调用 API 创建订单**：
   - `POST /orders`
   - 带重试机制（最多重试 2 次）
   - 每次重试都重新生成 salt 并重新签名

3. **记录订单跟踪**：
   - 保存到 `copy_order_tracking` 表
   - 记录买入订单 ID、数量、价格等信息
   - 状态：`filled`

### 2.2 卖出订单构建流程

#### 2.2.1 前置检查

1. **查找跟单关系**：
   - 查询所有启用且支持该 Leader 的跟单配置
   - 验证是否支持卖出（`supportSell = true`）

2. **计算需要匹配的数量**：
   - `需要匹配数量 = Leader 卖出数量 × 跟单比例`

3. **查找未匹配的买入订单**：
   - 使用 `outcomeIndex` 匹配（支持多元市场）
   - 按 FIFO 顺序（先进先出）
   - 查询 `copy_order_tracking` 表中未匹配的订单

4. **计算实际可卖出数量**：
   - 按 FIFO 顺序匹配
   - 支持部分匹配（一个买入订单可以被多次卖出匹配）

#### 2.2.2 价格计算

1. **优先使用订单簿 bestBid**：
   - 查询订单簿（`getOrderbookByTokenId`）
   - 获取最佳买单价格（bestBid）
   - 使用 bestBid 作为卖出价格

2. **备选方案**：
   - 如果获取订单簿失败，使用 Leader 价格
   - 卖出价格 = Leader 价格 × 0.9（固定按 90% 计算）

#### 2.2.3 订单签名

与买入订单类似，但：
- `side`: "SELL"
- **SELL 订单金额计算**：
  - `makerAmount = size`（shares 数量，最多 4 位小数）
  - `takerAmount = price × size`（USDC 金额，使用原始价格计算）

#### 2.2.4 创建订单请求

与买入订单相同：
- `orderType`: "FAK"
- `deferExec`: false

#### 2.2.5 提交订单

1. **调用 API 创建卖出订单**（带重试机制）

2. **更新买入订单状态**：
   - 更新 `copy_order_tracking` 表中的 `remainingQuantity`
   - 如果完全匹配，状态更新为 `fully_matched`
   - 如果部分匹配，状态更新为 `partially_matched`

3. **记录匹配关系**：
   - 保存到 `sell_match_record` 表（卖出匹配记录）
   - 保存到 `sell_match_detail` 表（匹配明细，包含盈亏计算）

## 三、关键配置

### 3.1 跟单配置参数

- `copyMode`: 跟单模式（"RATIO" 或 "FIXED"）
- `copyRatio`: 跟单比例（RATIO 模式）
- `fixedAmount`: 固定金额（FIXED 模式）
- `priceTolerance`: 价格容忍度（买入时使用）
- `supportSell`: 是否支持卖出
- `enabled`: 是否启用

### 3.2 订单类型

- **FAK (Fill-And-Kill)**：
  - 允许部分成交
  - 未成交部分立即取消
  - 快速响应 Leader 交易，避免订单长期挂单导致价格不匹配

### 3.3 重试机制

- **最多重试次数**：2 次（首次 + 1 次重试）
- **重试延迟**：3 秒
- **重试策略**：每次重试都重新生成 salt 并重新签名，确保签名唯一性

## 四、数据流向

```
Leader 交易（链上）
    ↓
Polymarket Data API (/activity)
    ↓
轮询服务（CopyTradingPollingService）
    ↓
交易处理服务（CopyOrderTrackingService）
    ↓
订单签名服务（OrderSigningService）
    ↓
CLOB API (POST /orders)
    ↓
订单跟踪表（copy_order_tracking）
```

## 五、注意事项

1. **去重机制**：
   - 使用 `leaderId + tradeId` 作为唯一标识
   - 在 `processed_trade` 表中记录已处理的交易
   - 避免重复处理同一笔交易

2. **价格调整**：
   - 买入时应用价格容忍度（提高买入价格）
   - 卖出时优先使用订单簿 bestBid，失败则使用 Leader 价格的 90%

3. **订单簿检查**：
   - 买入前检查订单簿中是否有可匹配的卖单
   - 确保调整后的买入价格不低于最佳卖单价格

4. **FIFO 匹配**：
   - 卖出时按买入时间顺序匹配（先进先出）
   - 支持部分匹配

5. **错误处理**：
   - 订单创建失败时记录到 `failed_trade` 表
   - 支持重试机制（最多 2 次）
   - 发送失败通知（如果配置了 `pushFailedOrders`）

