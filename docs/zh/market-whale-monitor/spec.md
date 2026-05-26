# 市场大单监听策略（10 秒聚合）

## 一、需求概述

目标：对用户配置的**自选市场列表**做实时成交监听。当某个市场的某个 outcome（`tokenId`）在短时间窗口内出现显著的买入成交额（可由多个成交组成），认为存在“聪明钱/趋势”，系统按策略配置自动下单。

本策略的关键特性：
- **监听数据源**：Polymarket Activity WebSocket（`topic=activity,type=trades`）
- **聚合粒度**：按 **`tokenId + side`** 聚合（每个 outcome 单独统计）
- **触发方向**：默认只做 **BUY**（可扩展）
- **触发窗口**：默认 10 秒（可配置）
- **触发阈值**：窗口内累计成交额（金额）达到阈值
- **执行方式**：触发后**自动下单**，并且 **跟随触发成交的 `tokenId`**
- **价格区间**：只在 **下单价** 落入配置区间时才下单（0~1，最多两位小数）

## 二、数据与名词

### 2.1 Activity Trade 关键字段

从 Activity WS trade 消息中我们关注：
- `payload.conditionId`：市场 ID（conditionId）
- `payload.asset`：outcome 的 **tokenId**（下单必须用）
- `payload.side`：`BUY` / `SELL`
- `payload.price`：成交价（0~1 的概率价）
- `payload.size`：成交数量（shares）
- `payload.timestamp`：成交时间
- `payload.transactionHash`：去重用交易哈希（同一笔可能在不同类型推送里出现）

### 2.2 成交额（金额）定义

每条成交的金额计算：

\[
notional = price \times size
\]

其中：
- `price` 取 BigDecimal（字符串化后再解析）
- `size` 取 BigDecimal
- `notional` 作为本策略的累计指标与阈值比较对象

说明：
- 前端展示可以使用 `$` 符号，但后端计算统一使用 `String + BigDecimal`，不要在代码里绑定具体稳定币名称。

## 三、产品流程

### 3.1 策略创建/编辑（前端）

用户创建一条“大单监听策略”时需要配置：

- **基础信息**
  - 策略名称
  - 绑定账户（下单账户）
  - 是否启用

- **监听范围**
  - 自选市场列表（按 `conditionId`，可多选）

- **触发条件**
  - 窗口秒数 `windowSeconds`（默认 10）
  - 触发阈值 `thresholdAmount`（金额）
  - 冷却时间 `cooldownSeconds`（默认例如 60）

- **下单参数**
  - 固定下单金额 `orderAmount`（金额）
  - `priceTolerance`（可选，用于提高成交概率的容忍度）

- **价格区间过滤（按下单价）**
  - `minPrice` / `maxPrice`
  - 取值范围：0~1
  - 精度：最多两位小数
  - 含义：仅当“最终下单价（orderPrice）”满足 `minPrice <= orderPrice <= maxPrice` 才下单；否则视为被过滤（记录过滤原因）。

### 3.2 运行时触发与执行

简化流程：

```
收到 trade 消息
  ↓
按 tokenId+BUY 入 10 秒滑动窗口，累计 notional
  ↓
累计 notional ≥ thresholdAmount ?
  ├─ 否：继续
  └─ 是：
      ↓
   cooldown 内已触发过 ?
      ├─ 是：记录/忽略，不下单
      └─ 否：
          ↓
   读取订单簿 bestAsk 计算最终下单价 orderPrice
          ↓
   orderPrice 是否在 [minPrice,maxPrice] ?
          ├─ 否：记录过滤原因，不下单
          └─ 是：
              ↓
   用固定金额 orderAmount 计算 sizeShares
              ↓
   深度/滑点风控通过？
              ↓
   FAK 下单（tokenId=触发tokenId）
              ↓
   记录触发与订单结果、推送通知
```

## 四、技术方案（后端）

### 4.1 监听与过滤

推荐新增独立服务（与跟单监听解耦），但复用现有基础设施：
- WebSocket 客户端：`backend/src/main/kotlin/com/wrbug/polymarketbot/websocket/PolymarketWebSocketClient.kt`
- Activity WS 协议：`docs/zh/polymarket-activity-websocket-api.md`

性能要点：
- 订阅全局 trades 可能消息频率较高，必须先做**快速字符串过滤**（按 `conditionId` 集合）再 JSON 解析。

### 4.2 10 秒滑动窗口聚合（按 tokenId+BUY）

聚合 key：
- `AggKey = (conditionId, tokenId, side)`，本需求默认只统计 `side=BUY`

每个 key 维护：
- `ArrayDeque<TradePoint(tsMillis, notional)>`
- `runningSum`：当前窗口累计 notional

每条新事件：
- 入队并 `runningSum += notional`
- 清理过期事件并 `runningSum -= expiredNotional`
- 若 `runningSum >= thresholdAmount` 且不在 cooldown 内，则触发执行

去重：
- 使用 `txHash` TTL 去重，避免重复触发/重复入窗

### 4.3 冷却与幂等

冷却：
- 对同一 `strategyId + tokenId + side` 记录最近触发时间，`cooldownSeconds` 内不重复触发下单。

幂等落库：
- 触发记录表建议包含：`strategyId, conditionId, tokenId, side, windowStartTs, windowEndTs, windowSumNotional, status, failReason, createdAt`
- 唯一键建议：`strategyId + tokenId + side + windowStartTs`（窗口起点按 `floor(ts/windowMs)*windowMs`）

### 4.4 下单执行（FAK，跟随 tokenId）

关键步骤：
- 读取订单簿 `bestAsk`
- 生成最终下单价 `orderPrice`
  - 若使用 `priceTolerance`：可按 `orderPrice = bestAsk * (1 + priceTolerance)`，并根据业务限制做截位
- **价格区间校验**（必须）
  - `minPrice/maxPrice` 均为字符串配置，解析为 BigDecimal
  - 必须满足：0~1 范围、最多两位小数（配置校验与下单前双重校验）
  - 最终判断：`minPrice <= orderPrice <= maxPrice`
- 计算下单数量：
  - `sizeShares = orderAmount / orderPrice`
  - 使用你们统一的 BigDecimal 扩展函数做除法精度与四舍五入策略
- 风控建议：
  - 订单簿深度能否覆盖 `orderAmount`（必要）
  - 单次最大金额 / 日累计金额（建议）
  - 最大允许价差/滑点（建议）
- 提交订单：
  - 复用 `OrderSigningService.createAndSignOrder`
  - `orderType = FAK`（允许部分成交，未成交部分取消）

## 五、配置校验规则

### 5.1 价格区间（minPrice/maxPrice）

规则：
- 范围：`0 <= price <= 1`
- 精度：最多两位小数

建议校验点：
- 策略创建/更新时校验（后端）
- 下单前再次校验（避免异常数据导致下单）

### 5.2 价格与金额数据类型

- 后端 DTO/Entity 使用 `String` 存储金额与价格（保持统一，与现有跟单/策略一致）
- 计算时转换为 BigDecimal
- 禁止使用 `Double` 做金额/价格运算

## 六、可观测性与通知

建议输出与推送：
- 触发事件：包含市场、tokenId、窗口累计金额、下单价、是否通过区间、是否下单
- 下单结果：订单 ID、成交数量/均价（如可获取）、失败原因

## 七、后续扩展（可选）

- 支持 SELL 方向（做反向策略或止损策略）
- 触发条件增加“成交笔数”“大额单笔阈值”等组合条件
- 支持“市场整体聚合”（按 conditionId 聚合，而不是 tokenId）作为另一种策略类型

