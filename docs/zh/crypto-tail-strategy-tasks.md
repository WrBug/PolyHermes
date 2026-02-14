# 加密市场尾盘策略 - 任务梳理

> 需求与 UI 见 `crypto-tail-strategy-ui-spec.md`，市场数据与执行规则见 `crypto-tail-strategy-market-data.md`。

以下按**文档 / 数据库 / 后端 / 前端**拆分为可执行任务，便于排期与验收。

---

## 一、文档（已完成）

| 任务 | 状态 | 说明 |
|------|------|------|
| PRD 与需求 | ✅ | 周期、价格区间、每周期最多触发一次、重试 2 次等 |
| 市场数据文档 | ✅ | `crypto-tail-strategy-market-data.md`：Gamma slug、周期、时间区间、价格判断 |
| UI 规格 | ✅ | `crypto-tail-strategy-ui-spec.md`：列表、表单、时间区间、触发记录、赎回前置检查 |

---

## 二、数据库

| 序号 | 任务 | 说明 |
|------|------|------|
| D1 | 策略表 migration | 新建表，字段建议：id, account_id, name, market_slug_prefix(如 btc-updown-5m), interval_seconds(300/900), window_start_seconds, window_end_seconds, min_price, max_price, amount_mode(ratio/fixed), amount_value(比例或 USDC 字符串), enabled, created_at, updated_at。唯一/外键按现有规范。 |
| D2 | 触发记录表 migration | 新建表，字段建议：id, strategy_id, period_start_unix, market_title, outcome_index(0=Up/1=Down), trigger_price, amount_usdc, order_id(可空), status(success/fail), fail_reason(可空), created_at。便于列表与筛选。 |

---

## 三、后端（Kotlin）

### 3.1 实体与 Repository

| 序号 | 任务 | 说明 |
|------|------|------|
| B1 | 策略实体 Entity | 对应策略表；ID 用 Long?；时间 Long 时间戳；金额 BigDecimal；遵守 backend.mdc 实体规范。 |
| B2 | 触发记录实体 Entity | 对应触发记录表。 |
| B3 | JpaRepository | 策略、触发记录的 Repository；按 strategyId、时间等查记录。 |

### 3.2 外部依赖与领域

| 序号 | 任务 | 说明 |
|------|------|------|
| B4 | Gamma API 按 slug 拉市场 | 已有或扩展 PolymarketGammaApi：GET /events/slug/{slug}，返回 conditionId、endDate、clobTokenIds 等；与 market-data 文档 3、4 节一致。 |
| B5 | 周期与 slug 推导 | 工具或 Service：根据 interval(300/900)、当前时间算 periodStartUnix；拼 slug(如 btc-updown-5m-{ts})；解析 endDate 得 endDateUnix。 |
| B6 | 订单簿价格 | 使用现有 CLOB/订单簿能力，按 conditionId、clobTokenIds 取各 outcome 的 bestBid；与 market-data 7.1 一致。 |
| B7 | 市价单与重试 | 按策略的 amount 计算下单金额；市价买入指定 outcome；失败时最多重试 2 次（共 3 次），仍失败则写触发记录状态为失败并记原因。 |

### 3.3 策略执行核心逻辑（按 market-data 第 6、7 节）

| 序号 | 任务 | 说明 |
|------|------|------|
| B8 | 周期内时间窗口判断 | 仅当 `periodStartUnix + windowStartSeconds <= nowUnix < periodStartUnix + windowEndSeconds` 时，才做价格区间判断与下单；区间外不处理。 |
| B9 | 价格区间与「先满足先买」 | 对两个 outcome 取价，若某 outcome 价格 ∈ [minPrice, maxPrice]，则触发买该 outcome；另一 outcome 本周期不再触发（7.1）。 |
| B10 | 每周期只触发一次 | 以 (strategyId, periodStartUnix) 去重；周期切换时重置「本周期已触发」状态；结合 B8、B9 实现。 |
| B11 | 周期切换与 404 | 当 now >= endDateUnix 或新 periodStartUnix 时，用新 periodStartUnix 拉新 slug；404 时同 periodStartUnix 短间隔重试，长时间 404 可跳过本周期并打日志。 |

### 3.4 API 与 DTO

| 序号 | 任务 | 说明 |
|------|------|------|
| B12 | 策略 CRUD API | 列表(分页/筛选)、创建、更新、删除、启用/停用；请求/响应为 DTO，不用 Map；统一 ApiResponse；错误码与 MessageSource。 |
| B13 | 策略 DTO | 创建/更新包含：accountId, name, marketSlugPrefix, intervalSeconds, windowStartSeconds, windowEndSeconds, minPrice, maxPrice(可选默认 1), amountMode, amountValue；校验 windowStart <= windowEnd，且不超过周期长度。 |
| B14 | 触发记录 API | 按 strategyId 分页查询触发记录；返回列表 DTO（时间、市场、方向、价格、金额、订单 ID、状态）。 |
| B15 | 5/15 分钟市场列表 API（可选） | 若前端需要「可选市场」列表：可按当前/下一周期拼 slug 调 Gamma 返回市场信息，供前端选择；或前端直接按 slug 规则+周期展示。 |

### 3.5 自动赎回与调度

| 序号 | 任务 | 说明 |
|------|------|------|
| B16 | 自动赎回包含尾盘策略仓位 | 尾盘策略产生的仓位与跟单/手动一视同仁，纳入现有自动赎回逻辑，不排除（见 UI 规格附录 A）。 |
| B17 | 调度/定时或常驻 | 对已启用策略按周期（如每 10–30 秒）检查：当前周期、是否在时间窗口内、是否已触发、价格是否进区间；满足则执行下单并写触发记录。 |

---

## 四、前端（React + TypeScript）

### 4.1 路由与导航

| 序号 | 任务 | 说明 |
|------|------|------|
| F1 | 路由 | App.tsx 增加 `/crypto-tail-strategy`、可选 `/crypto-tail-strategy/records/:id`。 |
| F2 | 菜单 | Layout 中增加「尾盘策略」菜单项，与跟单同级或在其下；key 与路由一致。 |

### 4.2 列表页

| 序号 | 任务 | 说明 |
|------|------|------|
| F3 | 列表页组件 | 如 CryptoTailStrategyList.tsx；页面标题、钱包提示 Alert、新增按钮、筛选（账户、状态）。 |
| F4 | 列表展示 | 桌面 Table / 移动 Card：策略名、关联市场、时间区间、价格区间、投入方式、状态、最近触发、操作（编辑、启用/停用、删除、查看触发记录）；删除 Popconfirm。 |
| F5 | 创建前检查 | 点击「新增策略」先调接口判断是否已配置自动赎回（如 builderApiKeyConfigured）；未配置则弹出「请先配置自动赎回」Modal（去配置 → /system-settings，取消），不打开表单。 |

### 4.3 新增/编辑表单

| 序号 | 任务 | 说明 |
|------|------|------|
| F6 | 表单弹窗 | 策略名、选择账户、选择市场、时间区间、minPrice、maxPrice、投入方式（比例/固定）、启用状态。 |
| F7 | 时间区间控件 | 区间开始/结束：下拉选「分钟」+「秒」；5min 市场 0–5 分+0–59 秒（总≤5min），15min 市场 0–15 分+0–59 秒（总≤15min）；校验**开始 ≤ 结束**；提交时转为 windowStartSeconds、windowEndSeconds。 |
| F8 | 市场选择器 | 仅展示 5/15 分钟加密市场；支持搜索；展示市场标题+周期；选后用于校验时间区间上界（5min 结束≤300s，15min≤900s）。 |
| F9 | 表单校验与提交 | 市场类型、时间区间 start≤end 且不超周期、minPrice/maxPrice、比例或固定金额合法；提交后刷新列表、成功提示。 |

### 4.4 触发记录

| 序号 | 任务 | 说明 |
|------|------|------|
| F10 | 触发记录展示 | 弹窗或独立页：触发时间、市场、方向(Up/Down)、触发价格、投入金额、订单 ID、状态；支持按时间、状态筛选；formatUSDC；移动端 Card/折叠。 |

### 4.5 通用

| 序号 | 任务 | 说明 |
|------|------|------|
| F11 | 类型定义 | 策略、触发记录等 TypeScript 类型；无 any。 |
| F12 | API 封装 | apiService 中 cryptoTailStrategy.list/create/update/delete/toggle、records(strategyId) 等。 |
| F13 | 多语言 | locales 中 zh-CN、zh-TW、en 的 cryptoTailStrategy.*：list.title、list.walletTip、form.walletTip、redeemRequiredModal.*、时间区间/价格区间等文案。 |

---

## 五、依赖关系简图

```
文档 ✅
  ↓
D1,D2 数据库
  ↓
B1–B3 实体与 Repository
  ↓
B4–B7 外部 API、周期、价格、下单
  ↓
B8–B11 执行逻辑（时间窗口+价格+去重+周期切换）
  ↓
B12–B15 API 与 DTO
B16 自动赎回
B17 调度
  ↓
F1–F2 路由与菜单
F11–F12 类型与 API 封装
F13 多语言
  ↓
F3–F5 列表与创建前检查
F6–F9 表单（含时间区间）
F10 触发记录
```

---

## 六、验收要点

- **时间区间**：仅当周期内当前时间落在 [windowStartSeconds, windowEndSeconds] 时才判断价格并下单；前端区间开始 ≤ 结束，且不超出 5min/15min。
- **每周期一次**：同一策略同一周期只触发一次（先满足价格的 outcome 买入，反方向不买）。
- **重试**：下单失败最多重试 2 次，共 3 次；仍失败记入触发记录为失败。
- **自动赎回**：尾盘策略产生的仓位可被自动赎回，无排除逻辑。
- **创建前检查**：未配置自动赎回时点击新增策略弹出「去配置」弹窗，不打开表单。
