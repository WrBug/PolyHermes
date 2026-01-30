# 回测功能设计审查清单

## 一、设计审查要点

### 1.1 产品需求完整性 ✅

**已覆盖的核心功能**:
- ✅ 回测任务的创建、查询、删除
- ✅ 按Leader筛选和排序功能
- ✅ 回测配置参数复用现有跟单配置
- ✅ 回测详情展示 (交易记录、资金曲线图、统计数据)
- ✅ 资金不足时自动停止机制
- ✅ 回测天数限制 (1-30天)

**潜在遗漏点**:
> [!WARNING]
> **需要确认的问题**:
> 1. **回测结果的可见性**: 是否需要支持多用户? 当前设计未涉及权限控制
> 2. **回测任务的生命周期管理**: 是否需要自动清理过期的回测记录?
> 3. **回测进度的实时展示**: 前端如何获取运行中任务的进度? (考虑WebSocket或轮询)

### 1.2 技术设计合理性 ✅

**优点**:
- ✅ 数据库设计规范,索引合理
- ✅ API设计符合RESTful规范
- ✅ 复用现有的 `CopyTradingFilterService`,减少代码冗余
- ✅ 使用 BigDecimal 保证计算精度
- ✅ 异步执行回测任务,不阻塞主线程

**可能的改进点**:
> [!NOTE]
> **建议优化的地方**:
> 1. **历史数据获取**: 当前设计依赖Polymarket API,需要考虑API限流和数据缺失的情况
> 2. **缓存策略**: 建议对Leader历史交易数据使用分层缓存 (内存 + Redis)
> 3. **回测结果的序列化**: 考虑将详细交易记录存储为JSON,减少表的大小

### 1.3 业务逻辑准确性 ✅

**已完善的关键逻辑**:

#### 1.3.1 历史数据获取 ⭐ (已修正)
> [!NOTE]
> **问题**: 现有 `ProcessedTrade` 表字段有限，无法满足回测需求。
>
> **解决方案**: 创建独立的 `backtest_historical_trades` 表
> - ✅ 存储完整的交易信息（marketId, price, quantity, outcomeIndex 等）
> - ✅ 支持实时数据同步（跟单时同时写入）
> - ✅ 支持通过 API 补充历史数据
> - ✅ 不影响现有跟单功能

#### 1.3.2 卖出匹配逻辑 ⭐ (已修正)
> [!NOTE]
> **改进**: 使用 `outcomeIndex` 支持多元市场
>
> **实现方案**:
> - 持仓键: `marketId + outcomeIndex`（支持多元市场）
> - 比例模式: 按 Leader 卖出比例计算
> - 固定金额模式: 全部卖出
> - 参考 `CopyOrderTracking` 的逻辑

**伪代码**:
```kotlin
val positionKey = "${leaderTrade.marketId}:${leaderTrade.outcomeIndex ?: 0}"
val position = positions[positionKey] ?: continue

val sellQuantity = if (task.copyMode == "RATIO") {
    if (position.leaderBuyQuantity != null && position.leaderBuyQuantity > BigDecimal.ZERO) {
        position.quantity * (leaderTrade.quantity / position.leaderBuyQuantity)
    } else {
        position.quantity  // 全部卖出
    }
} else {
    position.quantity  // 固定金额模式全部卖出
}
```

#### 1.3.3 价格滑点模拟 ✅ (已决策)
> [!NOTE]
> **用户决策**: 暂不模拟价格滑点
>
> **理由**:
> - 简化回测逻辑
> - 减少复杂度
> - 后续可以作为可选项添加
>
> **实现**: 使用 Leader 的成交价，不进行滑点调整

#### 1.3.2 价格滑点模拟
> [!NOTE]
> **关键问题**: 是否需要模拟价格滑点？
> 
> **当前设计**: 不模拟价格滑点，直接使用Leader的成交价
> - 优点: 简化逻辑，回测速度快
> - 缺点: 可能高估收益（实际跟单可能有滑点）
> 
> **可选方案**: 增加可配置的滑点参数
> - 买入时: 价格 × (1 + 滑点%)
> - 卖出时: 价格 × (1 - 滑点%)
> - 增加可选的滑点模拟参数 (例如: ±0.5%)
> - 在PRD中补充此配置项

#### 1.3.3 手续费计算 ✅ (已移除)
> [!NOTE]
> **用户决策**: 回测不计算手续费
> 
> **理由**:
> - 简化计算逻辑
> - 避免精度问题
> - 降低复杂度
> 
> **实现**:
> - 所有交易的 `fee` 字段均为 `0`
> - 买入成本 = 数量 × 价格
> - 卖出收入 = 数量 × 价格
> - 结算收入 = 数量 × 结算价

#### 1.3.4 市场结算处理 ⭐ (已优化)
> [!NOTE]
> **问题**: 市场结束时,未平仓位如何自动结算?
>
> **优化方案** (采纳用户建议):
> - ✅ **实时检查**: 在回测循环中,每处理一笔Leader交易前,检查所有持仓的市场`endDate`
> - ✅ **到期即结算**: 如果 `marketEndDate <= currentTradeTime`,立即结算该持仓
> - ✅ **资金可用**: 结算后的资金立即计入余额,可以用于后续交易
> - ✅ **兜底处理**: 回测结束时,结算所有剩余未到期持仓
>
> **结算价格判断** (通过市场价格):
> - 价格 >= 0.95: 胜出 (按 1.0 结算)
> - 价格 <= 0.05: 失败 (按 0.0 结算)
> - 其他情况: 按成本价保守估计
>
> **实现要点**:
> ```kotlin
> // 在交易循环中实时检查市场到期
> for (leaderTrade in leaderTrades.sortedBy { it.timestamp }) {
>
>     // 1. 检查并结算已到期的市场
>     val expiredPositions = positions.filter { (_, position) ->
>         val marketInfo = getMarketInfo(position.marketId)
>         marketInfo.endDate <= leaderTrade.timestamp
>     }
>
>     for ((positionKey, position) in expiredPositions) {
>         val marketPrice = marketPriceService.getCurrentMarketPrice(
>             marketId = position.marketId,
>             outcomeIndex = position.outcomeIndex ?: 0
>         )
>
>         val settlementPrice = when {
>             marketPrice >= BigDecimal("0.95") -> BigDecimal.ONE  // 胜出
>             marketPrice <= BigDecimal("0.05") -> BigDecimal.ZERO  // 失败
>             else -> position.avgPrice  // 未结算，按成本价
>         }
>
>         val settlementValue = position.quantity * settlementPrice
>         currentBalance += settlementValue
>         positions.remove(positionKey)
>     }
>
>     // 2. 处理当前Leader交易
>     // ...
> }
> ```
>
> **优势**:
> - 更符合真实场景 (市场结束时自动返还资金)
> - 提高资金利用率 (结算资金可参与后续交易)
> - 更准确的收益计算
> - 通过市场价格判断，无需依赖可能不存在的 `winner` 字段

#### 1.3.5 余额不足与持仓处理 ⚠️ (边缘场景) - 已修正
> [!WARNING]
> **关键问题**: 当余额不足但有未卖出持仓时，如何处理？
> 
> **场景示例**:
> - 初始余额: $1000
> - 已买入持仓市值: $800（未卖出）
> - 当前余额: $200
> - 新买入订单需要: $300
> - **问题**: 是否允许买入？虽然持仓市值足够，但资金被占用
> 
> **推荐方案: 严格模式**
> - ❌ 不允许买入（余额不足）
> - ✅ 仅使用 `currentBalance` 判断
> - ✅ 不计入持仓市值（因为持仓未实现）
> - ✅ 理由: 更真实，避免过于乐观的回测结果
> 
> **替代方案: 宽松模式**
> - ✅ 计算"虚拟可用资金" = `currentBalance + 持仓估值`
> - ⚠️ 允许"透支"买入
> - ❌ 风险: 可能产生不切实际的收益
> 
> **实际影响**:
> - 严格模式下，资金周转率是限制因素
> - 鼓励快进快出的策略
> - 长期持仓策略会因资金占用而错过后续机会
> 
> **已在文档中采用**: 严格模式

#### 1.3.6 每日订单数限制 ✅ (已补充)
> [!NOTE]
> **问题**: 文档提到了 `maxDailyOrders` 参数，但未在算法中实现
>
> **解决方案**: 在回测循环中添加每日订单数统计
>
> **实现**:
> ```kotlin
> // 统计当前交易时间当天已有的订单数
> val dailyOrderCount = trades.count { isSameDay(it.tradeTime, leaderTrade.timestamp) }
>
> if (dailyOrderCount >= task.maxDailyOrders) {
>     logger.info("已达到每日最大订单数限制: $dailyOrderCount / ${task.maxDailyOrders}")
>     continue
> }
> ```
>
> **优势**:
> - 符合实际跟单的风险控制逻辑
> - 避免回测结果过于激进

#### 1.3.7 价格容忍度检查 ✅ (已补充)
> [!NOTE]
> **问题**: 文档提到了 `priceTolerance` 参数，但未在算法中实现
>
> **解决方案**: 在执行交易前检查当前市场价格是否在容忍范围内
>
> **实现**:
> ```kotlin
> if (task.priceTolerance > BigDecimal.ZERO) {
>     val tolerance = task.priceTolerance.divide(BigDecimal("100"))
>     val minPrice = leaderTrade.price.multiply(BigDecimal.ONE.subtract(tolerance))
>     val maxPrice = leaderTrade.price.multiply(BigDecimal.ONE.add(tolerance))
>
>     val currentPrice = marketPriceService.getCurrentMarketPrice(
>         marketId = leaderTrade.marketId,
>         outcomeIndex = leaderTrade.outcomeIndex ?: 0
>     )
>
>     if (currentPrice < minPrice || currentPrice > maxPrice) {
>         logger.info("价格超出容忍度范围: 当前=$currentPrice, 可用范围=[$minPrice, $maxPrice]")
>         continue
>     }
> }
> ```

#### 1.3.8 回测停止条件 ✅ (已修正)
> [!NOTE]
> **修正**: 基于用户反馈，修正了停止逻辑
> 
> **错误设计**:
> ```kotlin
> if (currentBalance < $1) {
>     break  // ❌ 直接停止，忽略持仓
> }
> ```
> 
> **正确设计**:
> ```kotlin
> // 只有"余额不足 且 无持仓"时才停止
> if (currentBalance < $1 && positions.isEmpty()) {
>     break  // ✅ 确保无持仓时才停止
> }
> 
> // 有持仓时继续处理（等待卖出或结算）
> if (currentBalance < $1 && positions.isNotEmpty()) {
>     // 继续处理，跳过买入，但执行卖出和结算
> }
> ```
> 
> **理由**:
> - 持仓存在意味着可能有后续卖出或市场结算
> - 这些操作会释放资金
> - 过早停止会导致资金无法回收，回测不准确

### 1.4 性能和可扩展性 ✅

**已考虑的优化**:
- ✅ 异步执行,线程池限制并发
- ✅ 分页查询
- ✅ 数据库索引优化
- ✅ 前端虚拟滚动

**需要进一步考虑**:
> [!TIP]
> **性能优化建议**:
> 1. **批量插入交易记录**: 使用 `saveAll()` 而非逐条 `save()`
> 2. **进度更新频率**: 避免每笔交易都更新数据库,改为每100笔或每10秒更新一次
> 3. **历史数据预加载**: 在任务开始前一次性加载所有历史交易,避免多次API调用

## 二、数据库设计补充

### 2.1 新增回测历史交易表

**问题**: 现有 `ProcessedTrade` 表字段有限，无法满足回测需求。

**解决方案**: 创建独立的 `backtest_historical_trades` 表，存储完整的 Leader 历史交易数据。

```sql
CREATE TABLE backtest_historical_trades (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '记录ID',
    leader_id BIGINT NOT NULL COMMENT 'Leader ID',
    trade_id VARCHAR(100) NOT NULL COMMENT 'Leader 交易ID（唯一标识）',
    market_id VARCHAR(100) NOT NULL COMMENT '市场ID',
    market_title VARCHAR(500) DEFAULT NULL COMMENT '市场标题',
    market_slug VARCHAR(200) DEFAULT NULL COMMENT '市场 slug（用于生成链接）',
    side VARCHAR(10) NOT NULL COMMENT '交易方向: BUY/SELL',
    outcome VARCHAR(50) DEFAULT NULL COMMENT '市场方向（如 YES, NO 等）',
    outcome_index INT DEFAULT NULL COMMENT '结果索引（0, 1, 2, ...），支持多元市场',
    price DECIMAL(20, 8) NOT NULL COMMENT '交易价格',
    size DECIMAL(20, 8) NOT NULL COMMENT '交易数量',
    amount DECIMAL(20, 8) NOT NULL COMMENT '交易金额（price × size）',
    trade_timestamp BIGINT NOT NULL COMMENT '交易时间戳（毫秒）',

    -- 元数据
    source VARCHAR(20) NOT NULL DEFAULT 'POLLING' COMMENT '数据来源: WEBSOCKET/POLLING/API',
    fetched_at BIGINT NOT NULL COMMENT '数据获取时间（毫秒）',
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',

    UNIQUE INDEX uk_leader_trade (leader_id, trade_id),
    INDEX idx_leader_id (leader_id),
    INDEX idx_trade_timestamp (trade_timestamp),
    INDEX idx_market_id (market_id)
) COMMENT='回测历史交易表';
```

**优势**:
- ✅ 不影响现有跟单系统的 `ProcessedTrade` 表
- ✅ 存储完整的交易信息，满足回测需求
- ✅ 支持实时数据同步（跟单时同时写入）
- ✅ 支持通过 API 补充历史数据
- ✅ 唯一索引自动去重

### 2.2 移除 max_position_count 字段

**问题**: 文档中包含 `max_position_count` 字段，但 V26 迁移已删除该字段。

**解决方案**: 从 `backtest_task` 表和相关 API 中移除该字段。

### 2.3 其他字段建议

#### `backtest_task` 表
建议新增以下字段:

```sql
-- 用于计算平均持仓时间
avg_holding_time BIGINT DEFAULT NULL COMMENT '平均持仓时间(毫秒)',

-- 用于记录回测使用的数据源
data_source VARCHAR(50) DEFAULT 'MIXED' COMMENT '数据源: INTERNAL/API/MIXED',

-- 用于记录回测执行的详细日志
execution_log TEXT DEFAULT NULL COMMENT '执行日志(JSON格式)'
```

### 2.4 索引优化

建议添加复合索引:
```sql
-- 用于按Leader和收益率查询
CREATE INDEX idx_leader_profit ON backtest_task(leader_id, profit_rate DESC);

-- 用于按状态和创建时间查询
CREATE INDEX idx_status_created ON backtest_task(status, created_at DESC);
```

## 三、API设计补充

### 3.1 API 规范修正

**问题**: 文档中使用 GET/DELETE 方法，违反项目统一使用 POST 的规范。

**修正方案**:

```bash
# ❌ 错误（使用 GET/DELETE）
GET /api/backtest/tasks
GET /api/backtest/tasks/{id}
DELETE /api/backtest/tasks/{id}

# ✅ 正确（统一使用 POST）
POST /api/backtest/tasks/list
POST /api/backtest/tasks/detail
POST /api/backtest/tasks/delete
```

**完整的 API 列表**:

| 功能 | 方法 | 路径 | 说明 |
|-----|------|------|------|
| 创建回测 | POST | /api/backtest/tasks | 创建新的回测任务 |
| 查询列表 | POST | /api/backtest/tasks/list | 分页查询回测任务列表 |
| 查询详情 | POST | /api/backtest/tasks/detail | 查询单个回测任务详情 |
| 查询交易 | POST | /api/backtest/tasks/trades | 查询回测的交易记录 |
| 删除任务 | POST | /api/backtest/tasks/delete | 删除回测任务 |
| 停止任务 | POST | /api/backtest/tasks/stop | 停止运行中的回测 |
| 查询进度 | POST | /api/backtest/tasks/progress | 查询回测执行进度 |

### 3.2 缺失的API

建议新增以下API:

#### 3.2.1 查询回测进度 (实时更新)
```
POST /api/backtest/tasks/progress
```

**Request Body**:
```json
{
  "id": 12345
}
```

**Response**:
```json
{
  "success": true,
  "data": {
    "progress": 65,
    "currentBalance": "1150.00",
    "totalTrades": 30,
    "status": "RUNNING"
  }
}
```

#### 3.2.2 批量删除回测任务
```
POST /api/backtest/tasks/batch-delete
```

**Request Body**:
```json
{
  "taskIds": [12345, 12346, 12347]
}
```

#### 3.2.3 导出回测报告
```
POST /api/backtest/tasks/export
```

**Request Body**:
```json
{
  "id": 12345,
  "format": "csv"  // 或 "pdf"
}
```

### 3.2 API错误码规范

建议统一错误码:

| 错误码 | 说明 |
|-------|------|
| 40001 | 回测任务不存在 |
| 40002 | Leader不存在 |
| 40003 | 回测天数超出限制 |
| 40004 | 初始金额无效 |
| 40005 | 回测任务正在运行,无法删除 |
| 50001 | 历史数据获取失败 |
| 50002 | 回测执行失败 |

## 四、前端实现补充

### 4.1 状态轮询

对于运行中的回测任务,前端需要定时轮询进度:

```typescript
useEffect(() => {
  if (task.status === 'RUNNING') {
    const interval = setInterval(async () => {
      const progress = await backtestService.getProgress(task.id);
      setTask({ ...task, ...progress });
    }, 3000); // 每3秒轮询一次
    
    return () => clearInterval(interval);
  }
}, [task.status]);
```

### 4.2 图表数据压缩

当交易记录过多时,图表数据需要压缩:

```typescript
// 将数据按时间聚合为最多200个点
const compressChartData = (trades: BacktestTrade[], maxPoints: number = 200) => {
  if (trades.length <= maxPoints) return trades;
  
  const interval = Math.floor(trades.length / maxPoints);
  return trades.filter((_, index) => index % interval === 0);
};
```

### 4.3 国际化文案

需要在 `locales/` 目录下补充以下文案:

**zh-CN.json**:
```json
{
  "backtest": {
    "title": "回测管理",
    "createTask": "新增回测",
    "taskName": "回测名称",
    "leader": "Leader",
    "initialBalance": "初始金额",
    "backtestDays": "回测天数",
    "profitAmount": "收益额",
    "profitRate": "收益率",
    "status": {
      "pending": "待执行",
      "running": "运行中",
      "completed": "已完成",
      "stopped": "已停止",
      "failed": "失败"
    }
  }
}
```

## 五、测试计划补充

### 5.1 单元测试

**需要测试的核心方法**:
- `BacktestExecutionService.executeBacktest()` - 回测算法准确性
- `BacktestExecutionService.calculateStatistics()` - 统计数据计算
- `BacktestDataService.getLeaderHistoricalTrades()` - 历史数据获取

**测试用例示例**:
```kotlin
@Test
fun `test backtest with simple buy-sell scenario`() {
    // Given: 初始余额1000, Leader买入100@0.5, 卖出100@0.6
    val task = createTestTask(initialBalance = 1000.toBigDecimal())
    val trades = listOf(
        createBuyTrade(quantity = 100.toBigDecimal(), price = 0.5.toBigDecimal()),
        createSellTrade(quantity = 100.toBigDecimal(), price = 0.6.toBigDecimal())
    )
    
    // When: 执行回测
    val result = executionService.executeBacktest(task)
    
    // Then: 验证收益
    // 买入: 100 * 0.5 = 50, 手续费0.1, 总成本50.1
    // 卖出: 100 * 0.6 = 60, 手续费0.12, 净收入59.88
    // 盈利: 59.88 - 50.1 = 9.78
    // 最终余额: 1000 - 50.1 + 59.88 = 1009.78
    assertEquals(1009.78.toBigDecimal(), result.finalBalance)
    assertEquals(9.78.toBigDecimal(), result.profitAmount)
}
```

### 5.2 集成测试

**测试场景**:
1. 端到端测试: 创建任务 → 执行回测 → 查询结果
2. 异常场景: 历史数据为空、API调用失败
3. 边界条件: 余额刚好为0、单笔交易耗尽余额

### 5.3 性能测试

**测试指标**:
- 30天历史数据 (假设1000笔交易) 的回测执行时间 < 5分钟
- 并发5个回测任务时的系统资源占用
- 查询包含10000笔交易的回测详情页面加载时间 < 2秒

## 六、风险评估和缓解方案

### 6.1 数据准确性风险

**风险**: 历史数据不完整或API返回数据有误

**缓解方案**:
1. 数据验证: 检查返回数据的完整性 (是否有时间断层)
2. 数据对比: 使用多个数据源交叉验证
3. 错误标记: 回测结果标注数据质量等级

### 6.2 计算精度风险

**风险**: BigDecimal计算中的舍入误差累积

**缓解方案**:
1. 统一舍入模式: 使用 `RoundingMode.HALF_UP`
2. 精度测试: 编写专门的精度测试用例
3. 误差补偿: 最终余额与理论值的误差 < 0.01 USDC

### 6.3 性能风险

**风险**: 大量回测任务导致系统负载过高

**缓解方案**:
1. 任务队列: 使用异步任务队列 (可选: Redis Queue 或 RabbitMQ)
2. 资源限流: 限制单用户最多创建10个待执行任务
3. 自动清理: 定期清理30天前的回测记录

## 七、需要与用户确认的问题

> [!IMPORTANT]
> **关键决策点 - 需要用户反馈**:

### 7.1 卖出匹配策略
**问题**: 当用户多次买入同一市场时,卖出应该匹配哪笔买入?

**选项**:
- **选项A**: FIFO (先进先出) - 先卖出最早的买入
- **选项B**: 加权平均 - 按平均成本价计算盈亏
- **选项C**: 完全跟随Leader - Leader卖多少比例,我们也卖多少比例

**建议**: 选项C (完全跟随),与实际跟单逻辑保持一致

### 7.2 价格滑点模拟
**问题**: 是否需要在回测中模拟价格滑点?

**选项**:
- **选项A**: 不模拟,使用Leader成交价 (乐观估计)
- **选项B**: 固定滑点 (如买入+0.5%, 卖出-0.5%)
- **选项C**: 可配置滑点,用户自定义

**建议**: 选项C,增加灵活性

### 7.3 数据源选择
**问题**: 历史数据来源?

**选项**:
- **选项A**: 仅使用 Polymarket API
- **选项B**: 优先使用系统记录的 `ProcessedTrade` 表,不足时调用API
- **选项C**: 仅使用 `ProcessedTrade` 表 (限制回测范围为系统运行期间)

**建议**: 选项B,兼顾数据完整性和性能

### 7.4 回测结果保留时长
**问题**: 回测记录保留多久?

**选项**:
- **选项A**: 永久保留
- **选项B**: 保留30天,自动清理
- **选项C**: 用户手动删除,无自动清理

**建议**: 选项B,避免数据库膨胀

## 八、文档总结

### 已完成的文档
1. ✅ **BACKTEST_PRD.md** - 产品需求文档
2. ✅ **BACKTEST_TECHNICAL_DESIGN.md** - 技术设计文档
3. ✅ **BACKTEST_REVIEW_CHECKLIST.md** - 设计审查清单 (本文档)

### 建议补充的文档 (可选)
1. **BACKTEST_API_SPEC.md** - API接口规范 (从技术设计文档提取)
2. **BACKTEST_DATABASE_MIGRATION.md** - 数据库迁移脚本
3. **BACKTEST_TEST_PLAN.md** - 详细测试计划

### 下一步行动
1. **用户Review**: 请用户审查以上文档,确认关键设计点
2. **补充遗漏**: 根据用户反馈补充缺失部分
3. **进入执行**: 用户确认后开始实施开发

---

**审查日期**: 2026-01-30  
**审查人**: AI Assistant  
**状态**: 待用户确认
