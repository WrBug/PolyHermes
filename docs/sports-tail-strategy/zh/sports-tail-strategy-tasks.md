# 体育尾盘策略 - 任务梳理

> 需求与 UI 见 `sports-tail-strategy-ui-spec.md`，市场数据与订阅见 `sports-tail-strategy-market-data.md`。

以下按**数据库 / 后端 / 前端**拆分为可执行任务，便于排期与验收。

---

## 一、数据库

| 序号 | 任务 | 说明 |
|------|------|------|
| D1 | 策略表 migration | 新建表 `sports_tail_strategy`，字段：id, account_id, condition_id, market_title, event_slug, trigger_price, amount_mode(FIXED/RATIO), amount_value, take_profit_price, stop_loss_price, filled(BOOL), filled_price, filled_outcome_index, filled_amount, filled_shares, filled_at, sold(BOOL), sell_price, sell_type, sell_amount, realized_pnl, sold_at, created_at, updated_at |
| D2 | 触发记录表 migration | 新建表 `sports_tail_strategy_trigger`，字段：id, strategy_id, market_title, condition_id, account_id, buy_order_id, buy_price, outcome_index, outcome_name, buy_amount, buy_shares, buy_status(PENDING/SUCCESS/FAIL), sell_order_id, sell_price, sell_type(TAKE_PROFIT/STOP_LOSS/MANUAL), sell_amount, sell_status, realized_pnl, triggered_at, sold_at |

---

## 二、后端（Kotlin）

### 2.1 实体与 Repository

| 序号 | 任务 | 说明 |
|------|------|------|
| B1 | 策略实体 Entity | 对应 `sports_tail_strategy` 表；ID 用 `Long?`；时间用 `Long` 时间戳；金额用 `BigDecimal`；遵守 backend.mdc 实体规范 |
| B2 | 触发记录实体 Entity | 对应 `sports_tail_strategy_trigger` 表 |
| B3 | JpaRepository | 策略、触发记录的 Repository；按 conditionId、accountId、filled、sold 等查询 |

### 2.2 Gamma API 扩展

| 序号 | 任务 | 说明 |
|------|------|------|
| B4 | 体育类别 API | `GET /sports` 获取体育元数据（sport, tags, image） |
| B5 | 市场搜索 API | `GET /markets` 支持 tag_id、sports_market_types、end_date_min/max、liquidity_num_min 等筛选参数 |
| B6 | 事件列表 API | `GET /events` 支持 tag_slug、active、live 等参数，获取比赛状态 |

### 2.3 订阅管理

| 序号 | 任务 | 说明 |
|------|------|------|
| B7 | 订阅管理器 SubscriptionManager | 维护市场订阅计数，同一市场多策略共享订阅；无策略时自动取消订阅 |
| B8 | WebSocket 订单簿订阅 | 订阅订单簿 `channel: "book:<token_id>"`，接收实时价格更新 |
| B9 | 订阅生命周期管理 | 策略创建时订阅，成交后检查是否需要保持（有止盈止损则保持），卖出后检查是否需要取消 |

### 2.4 策略执行核心逻辑

| 序号 | 任务 | 说明 |
|------|------|------|
| B10 | 买入触发判断 | 接收订单簿更新，检查未成交策略；当任意方向价格 >= triggerPrice 时买入该方向 |
| B11 | 买入执行 | 调用 CLOB API 创建市价买单；记录成交价格、数量、方向；更新策略状态为已成交 |
| B12 | 止盈判断 | 已成交策略，当前价格 >= takeProfitPrice 时执行卖出 |
| B13 | 止损判断 | 已成交策略，当前价格 <= stopLossPrice 时执行卖出 |
| B14 | 卖出执行 | 调用 CLOB API 创建市价卖单；计算盈亏；更新策略状态为已卖出 |

### 2.5 API 与 DTO

| 序号 | 任务 | 说明 |
|------|------|------|
| B15 | 策略 CRUD API | 列表(分页/筛选)、创建、删除；统一 ApiResponse；错误码与 MessageSource |
| B16 | 策略 DTO | 创建请求：accountId, conditionId, marketTitle, eventSlug, triggerPrice, amountMode, amountValue, takeProfitPrice(可选), stopLossPrice(可选) |
| B17 | 触发记录 API | 全局触发记录列表；支持 accountId、status、时间筛选；返回市场信息、成交价、数量、盈亏等 |
| B18 | 市场搜索 API | 体育类别列表、市场搜索（支持筛选）、市场详情（含实时价格） |

---

## 三、前端（React + TypeScript）

### 3.1 路由与导航

| 序号 | 任务 | 说明 |
|------|------|------|
| F1 | 路由 | App.tsx 增加 `/sports-tail-strategy` |
| F2 | 菜单 | Layout 中增加「体育尾盘策略」菜单项 |

### 3.2 列表页

| 序号 | 任务 | 说明 |
|------|------|------|
| F3 | 列表页组件 | SportsTailStrategyList.tsx；页面标题、新增按钮、筛选（账户、类别） |
| F4 | 列表展示 | 桌面 Table / 移动 Card：市场标题、账户、触发价、金额、止盈止损、成交价/数量（未成交显示实时价格）、操作（查看记录、删除） |
| F5 | 实时价格显示 | 未成交策略通过 WebSocket 获取实时价格并显示 |

### 3.3 新增/编辑表单

| 序号 | 任务 | 说明 |
|------|------|------|
| F6 | 表单弹窗 | 账户选择、市场搜索（支持筛选）、触发价格、下注金额、止盈止损（可选） |
| F7 | 市场筛选器 | 体育类别、市场类型、结束时间、最小流动性、搜索关键词 |
| F8 | 市场选择器 | 显示搜索结果列表，包含市场标题、当前价格、结束时间、流动性 |
| F9 | 预估收益 | 根据触发价、金额计算预估份额和收益 |
| F10 | 表单校验 | 触发价 0-1，止盈 > 触发价，止损 < 触发价 |

### 3.4 触发记录

| 序号 | 任务 | 说明 |
|------|------|------|
| F11 | 触发记录列表 | 全局记录列表（非单个市场）；支持账户、状态、时间筛选 |
| F12 | 记录详情 | 市场标题、成交价格/方向、数量、卖出价格/类型、盈亏 |

### 3.5 通用

| 序号 | 任务 | 说明 |
|------|------|------|
| F13 | 类型定义 | 策略、触发记录、市场、体育类别等 TypeScript 类型 |
| F14 | API 封装 | apiService 中 sportsTailStrategy.* 方法 |
| F15 | 多语言 | zh-CN、zh-TW、en 的 sportsTailStrategy.* 文案 |

---

## 四、依赖关系简图

```
D1,D2 数据库
  ↓
B1-B3 实体与 Repository
  ↓
B4-B6 Gamma API 扩展
  ↓
B7-B9 订阅管理
  ↓
B10-B14 执行逻辑
  ↓
B15-B18 API 与 DTO
  ↓
F1-F2 路由与菜单
F13-F15 类型与 API 封装
  ↓
F3-F5 列表与实时价格
F6-F10 表单（含市场筛选）
F11-F12 触发记录
```

---

## 五、验收要点

- **不区分方向**：只设置触发价格，系统自动监控两个方向，任意方向达到即买入
- **实时订阅**：通过 WebSocket 订阅订单簿，实时监控价格，不使用轮询
- **订阅管理**：同一市场多策略共享一个订阅；无策略或策略完成且无止盈止损时取消订阅
- **止盈止损**：有止盈止损的策略成交后保持订阅，直到卖出
- **列表显示**：已成交显示成交价和数量，未成交显示实时价格
- **触发记录**：全局记录列表，每条记录包含完整市场信息
