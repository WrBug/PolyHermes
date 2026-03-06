# 体育尾盘策略 - 流程说明

## 一、策略状态流转

```
┌─────────────┐     价格>=触发价      ┌─────────────┐
│   待触发    │ ──────────────────▶ │   已成交    │
│  (filled=F) │                      │  (filled=T) │
└─────────────┘                      └─────────────┘
       │                                    │
       │        ┌─────────────────┼─────────────────┐
       │        │                 │                 │
       ▼        │  有止盈止损      │  无止盈止损       │
                │                 │                 │
                ▼                 ▼                 ▼
         ┌─────────────┐      ┌─────────────────────────────────────┐
         │  保持订阅   │      │  检查同市场是否有其他未完成策略  │
         │  监控卖出   │      │  - 有: 保持订阅                               │
         └─────────────┘      │  - 无: 取消订阅                             │
                │      └─────────────────────────────────────┘
                │
                ▼
         ┌──────────────────────────────────────────────┐
         │              价格 >= 止盈价                  │
         │              或 价格 <= 止损价                  │
         └──────────────────────────────────────────────┘
                │
                ▼
         ┌─────────────┐
         │   自动卖出   │
         │  (sold=T)   │
         └─────────────┘
```

---

## 二、订阅生命周期

### 2.1 策略创建时

```
策略创建
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│  SubscriptionManager.subscribeStrategy(strategy)            │
│  1. 检查市场是否已有订阅（marketSubscriptions 计数）         │
│  2. 如果市场已有订阅：仅增加计数，不新建连接                    │
│  3. 如果市场无订阅：建立 WebSocket 连接，订阅订单簿频道   │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 实时监控

```
WebSocket 订单簿推送
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│  SportsTailWebSocketHandler.onOrderbookUpdate()             │
│  1. 解析消息，获取当前价格                                 │
│  2. 查找该 Token ID 对应的所有未完成策略                       │
│  3. 遍历策略，检查触发条件：                                  │
│     - 未成交：检查买入条件（价格 >= 触发价）                   │
│     - 已成交未卖出：检查卖出条件（止盈/止损）                │
└─────────────────────────────────────────────────────────────┘
```

### 2.3 成交后处理

```
策略成交
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│  SubscriptionManager.onStrategyFilled(strategy)             │
│  1. 更新策略状态为已成交                                    │
│  2. 检查是否需要保持订阅：                                   │
│     - 有止盈止损：保持订阅，继续监控卖出条件                 │
│     - 无止盈止损：检查同市场是否有其他未完成策略             │
│       - 有：保持订阅                                          │
│       - 无：取消订阅，关闭 WebSocket 连接                     │
└─────────────────────────────────────────────────────────────┘
```

### 2.4 卖出后处理

```
策略卖出
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│  SubscriptionManager.onStrategySold(strategy)               │
│  1. 更新策略状态为已卖出                                    │
│  2. 计算并记录盈亏                                          │
│  3. 检查同市场是否有其他未完成策略：                         │
│     - 有：保持订阅                                          │
│     - 无：取消订阅，关闭 WebSocket 连接                      │
└─────────────────────────────────────────────────────────────┘
```

### 2.5 策略删除时

```
策略删除
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│  SubscriptionManager.unsubscribeStrategy(strategy)          │
│  1. 减少市场的订阅计数                                      │
│  2. 如果计数归零：取消订阅，关闭 WebSocket 连接             │
└─────────────────────────────────────────────────────────────┘
```

---

## 三、买入逻辑（不区分方向）

### 3.1 触发条件检查

```kotlin
fun checkBuyTrigger(strategy: SportsTailStrategy, yesPrice: BigDecimal, noPrice: BigDecimal): BuyTrigger? {
    // 如果已成交，不检查
    if (strategy.filled) return null
    
    // 检查 YES 方向
    if (yesPrice >= strategy.triggerPrice) {
        return BuyTrigger(outcomeIndex = 0, price = yesPrice)
    }
    
    // 检查 NO 方向
    if (noPrice >= strategy.triggerPrice) {
        return BuyTrigger(outcomeIndex = 1, price = noPrice)
    }
    
    // 都不满足
    return null
}
```

### 3.2 执行买入

```kotlin
suspend fun executeBuy(strategy: SportsTailStrategy, trigger: BuyTrigger) {
    // 1. 创建市价单
    val order = createMarketOrder(
        tokenId = getTokenId(strategy.conditionId, trigger.outcomeIndex),
        side = "BUY",
        amount = calculateAmount(strategy)
    )
    
    // 2. 提交订单
    val result = clobApi.createOrder(order)
    
    // 3. 更新策略状态
    strategy.filled = true
    strategy.filledPrice = trigger.price
    strategy.filledOutcomeIndex = trigger.outcomeIndex
    strategy.filledAmount = order.amount
    strategy.filledShares = calculateShares(order.amount, trigger.price)
    strategy.filledAt = System.currentTimeMillis()
    strategyRepository.save(strategy)
    
    // 4. 记录触发记录
    createTriggerRecord(strategy, trigger, result)
    
    // 5. 通知订阅管理器
    subscriptionManager.onStrategyFilled(strategy)
}
```

---

## 四、卖出逻辑（止盈止损）

### 4.1 止盈止损检查

```kotlin
fun checkSellTrigger(strategy: SportsTailStrategy, currentPrice: BigDecimal): SellTrigger? {
    // 如果已卖出或未成交，不检查
    if (strategy.sold || !strategy.filled) return null
    
    // 只检查已买入方向的价格
    val filledTokenId = getTokenId(strategy.conditionId, strategy.filledOutcomeIndex)
    if (currentTokenId != filledTokenId) return null
    
    // 检查止盈
    if (strategy.takeProfitPrice != null && currentPrice >= strategy.takeProfitPrice) {
        return SellTrigger(type = "TAKE_PROFIT", price = currentPrice)
    }
    
    // 检查止损
    if (strategy.stopLossPrice != null && currentPrice <= strategy.stopLossPrice) {
        return SellTrigger(type = "STOP_LOSS", price = currentPrice)
    }
    
    return null
}
```

### 4.2 执行卖出

```kotlin
suspend fun executeSell(strategy: SportsTailStrategy, trigger: SellTrigger) {
    // 1. 创建市价单
    val order = createMarketOrder(
        tokenId = getTokenId(strategy.conditionId, strategy.filledOutcomeIndex),
        side = "SELL",
        shares = strategy.filledShares
    )
    
    // 2. 提交订单
    val result = clobApi.createOrder(order)
    
    // 3. 计算盈亏
    val sellAmount = calculateSellAmount(trigger.price, strategy.filledShares)
    val pnl = sellAmount - strategy.filledAmount
    
    // 4. 更新策略状态
    strategy.sold = true
    strategy.sellPrice = trigger.price
    strategy.sellType = trigger.type
    strategy.sellAmount = sellAmount
    strategy.realizedPnl = pnl
    strategy.soldAt = System.currentTimeMillis()
    strategyRepository.save(strategy)
    
    // 5. 更新触发记录
    updateTriggerRecord(strategy, trigger, result, pnl)
    
    // 6. 通知订阅管理器
    subscriptionManager.onStrategySold(strategy)
}
```

---

## 五、关键设计要点

### 5.1 订阅共享

- 同一市场（conditionId）多个策略共享一个 WebSocket 订阅
- 使用计数器管理订阅生命周期
- 避免重复连接和资源浪费

### 5.2 不区分方向

- 只设置触发价格，不选择 YES/NO
- 系统自动监控两个方向的价格
- 任意方向满足条件即买入该方向

### 5.3 实时订阅

- 使用 WebSocket 订阅订单簿，实时接收价格变化
- 不使用轮询方式
- 响应速度快，延迟低

### 5.4 智能取消

- 无策略时取消订阅
- 策略完成且无止盈止损时检查是否需要取消
- 有止盈止损时保持订阅直到卖出
