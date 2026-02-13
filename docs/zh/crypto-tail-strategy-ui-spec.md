# 加密市场尾盘策略 - 前端 UI 规格

> 周期推导与市场数据获取详见 `crypto-tail-strategy-market-data.md`。

与现有跟单/回测保持同一风格（Ant Design、响应式、多语言），以下为页面结构及所含元素。

---

## 1. 导航与路由

| 项目 | 说明 |
|------|------|
| **菜单** | 在「跟单管理」同级或其下增加一项，如「尾盘策略」，key 建议 `/crypto-tail-strategy`。 |
| **路由** | 列表页 `/crypto-tail-strategy`；可选详情/触发记录 `/crypto-tail-strategy/records/:id`。 |

参考：`Layout.tsx` 中 `/copy-trading`、`/backtest` 的配置；`App.tsx` 中对应 `Route`。

---

## 2. 列表页（主页面）

**路径**：`/crypto-tail-strategy`  
**组件**：如 `CryptoTailStrategyList.tsx`（或 `TailStrategyList.tsx`）。

### 2.1 顶部操作区

| 元素 | 类型 | 说明 |
|------|------|------|
| 页面标题 | 标题文案 | 如「加密尾盘策略」，用 `t('cryptoTailStrategy.list.title')`。 |
| **钱包使用提示** | **Alert（Warning）** | **必须**在页面顶部或标题下方展示：提示用户**使用单独/专用钱包**运行本策略，避免该钱包用于手动交易、跟单等其他操作，否则可能导致余额或仓位变化，进而造成策略执行异常（如余额不足、下单失败等）。文案走多语言 `t('cryptoTailStrategy.list.walletTip')`，可带 `showIcon`。 |
| 新增策略 | Button（Primary） | 点击时**先检查自动赎回相关配置**（见 2.4）；若未配置则弹出「去配置」简易弹窗，若已配置则打开「新增策略」表单弹窗。图标可用 `PlusOutlined`。 |
| 筛选（可选） | Select / 筛选项 | 按账户、启用状态筛选；移动端可收起到抽屉或折叠。 |

### 2.2 列表内容（桌面端：Table，移动端：Card 列表）

| 列/卡片项 | 说明 |
|-----------|------|
| 策略名称 | 用户填的配置名或自动生成名。 |
| 关联市场 | 展示市场标题 + 周期，如「Bitcoin Up or Down - 5 minute」。 |
| 时间区间 | 如「3 分 0 秒 ~ 12 分 0 秒」（与周期类型一致：5min 为 0–5 分，15min 为 0–15 分）。 |
| 价格区间 | 如 `[0.92, 1]` 或「0.92 ~ 1」（maxPrice 为空时显示为 1）。 |
| 投入方式 | 「比例 10%」或「固定 100 USDC」，用 `formatUSDC` 格式化金额。 |
| 状态 | Tag 或 Switch：启用 / 停用。 |
| 最近触发 | 最近一次触发时间（若有）；无则「-」。 |
| 操作 | 编辑、启用/停用、删除、查看触发记录。删除前 Popconfirm 二次确认。 |

### 2.3 与现有风格对齐

- 加载态：`Spin` 包裹列表。
- 空状态：无数据时展示空状态插画 + 引导「新增策略」。
- 响应式：`useMediaQuery({ maxWidth: 768 })`，桌面用 Table，移动用 Card + 操作折叠/抽屉。

参考：`CopyTradingList.tsx` 的 Table 列、Card 布局、筛选与 Modal 打开方式。

### 2.4 创建前检查：自动赎回配置（必须）

策略依赖**自动赎回**（需通过 Relayer/Builder API 提交链上赎回）。用户点击「新增策略」时：

1. **检查**：请求系统配置（如 `apiService.systemConfig.getConfig()` 或已有接口），判断是否已配置 Builder API Key（及可选：自动赎回已开启）。若 `builderApiKeyConfigured === false`（或后端约定之「未配置」状态），视为未配置。
2. **未配置时**：不打开新增策略表单，改为弹出**简易弹窗**（Modal），内容建议：
   - **标题**：如「请先配置自动赎回」，`t('cryptoTailStrategy.redeemRequiredModal.title')`。
   - **正文**：简短说明尾盘策略依赖自动赎回，需要先在「系统设置」中配置 Builder API Key 及自动赎回。文案 `t('cryptoTailStrategy.redeemRequiredModal.description')`。
   - **操作**：
     - **去配置**：主按钮，点击后关闭弹窗并跳转到系统设置页（如 `/system-settings`，该页含 Relayer 配置与自动赎回开关）。
     - **取消**：次按钮或关闭图标，仅关闭弹窗。
3. **已配置时**：正常打开新增策略表单弹窗。

弹窗保持简易，无需表单，仅提示 + 跳转；多语言键示例：`cryptoTailStrategy.redeemRequiredModal.title`、`cryptoTailStrategy.redeemRequiredModal.description`、`cryptoTailStrategy.redeemRequiredModal.goToSettings`、`cryptoTailStrategy.redeemRequiredModal.cancel`。

---

## 3. 新增 / 编辑策略弹窗（Modal）

**组件**：如 `CryptoTailStrategyFormModal.tsx` 或内嵌在列表页的 Modal。

### 3.1 表单字段

| 表单项 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| **钱包提示（简短）** | **Alert（Warning）** | - | 在「选择账户」上方或表单单列顶部展示简短提示：建议使用**专用钱包**，避免手动操作等导致异常。文案如 `t('cryptoTailStrategy.form.walletTip')`。 |
| 策略名称 | Input | 否 | 用于列表展示，可占位「自动生成」。 |
| 选择账户 | Select | 是 | 下拉已导入账户（与跟单一致，来自 `useAccountStore()` 或接口）。 |
| 选择市场 | 市场选择器 | 是 | 仅展示 5/15 分钟加密市场；支持搜索；展示市场标题 + 周期（5min/15min）；一个策略绑一个市场。 |
| **时间区间** | **开始 / 结束** | 是 | 仅在本周期内的该时间窗口内，价格满足时才下单；区间外不处理。见下方说明。 |
| 区间开始 | 下拉（分 + 秒） | 是 | 从周期起点起算的「开始」偏移。5 分钟市场可选 0～5 分 + 0～59 秒（总不超过 5 分钟）；15 分钟市场可选 0～15 分 + 0～59 秒（总不超过 15 分钟）。 |
| 区间结束 | 下拉（分 + 秒） | 是 | 从周期起点起算的「结束」偏移。范围同上，且**区间开始不得大于区间结束**（前端校验）。 |
| 最低价 minPrice | InputNumber | 是 | 0～1，精度 2～4 位小数；校验 minPrice ≤ 1。 |
| 最高价 maxPrice | InputNumber | 否 | 0～1，占位「不填默认为 1」；若填则校验 minPrice ≤ maxPrice ≤ 1。 |
| 投入方式 | Radio.Group | 是 | 选项：「按比例」「固定金额」。 |
| 比例 % | InputNumber | 条件必填 | 选「按比例」时显示；0～100；可展示当前账户 USDC 余额与预估金额。 |
| 固定金额 (USDC) | InputNumber | 条件必填 | 选「固定金额」时显示；≥ 最小下单额，≤ 账户余额；用 `formatUSDC` 展示。 |
| 启用状态 | Switch | 否 | 新增默认开启；编辑可切换。 |

**时间区间说明**：例如 15 分钟市场配置「3 分 0 秒」～「12 分 0 秒」，表示从周期开始后第 3 分钟到第 12 分钟之间，若价格进入 [minPrice, maxPrice] 才下单；第 0～3 分钟、第 12～15 分钟即使价格满足也不下单。5 分钟市场同理，可选 0～5 分钟内的一段（如 0～2、2～5）。前端用下拉选择「分钟」+「秒」，后端存为相对周期起点的秒数（如 windowStartSeconds、windowEndSeconds）。

### 3.2 校验与提交

- 提交前：市场为 5/15 分钟、**时间区间开始 ≤ 时间区间结束**、时间区间不超出周期长度（5min 市场结束 ≤ 5 分 0 秒，15min 市场结束 ≤ 15 分 0 秒）、minPrice 合法、maxPrice 若填则 ≥ minPrice、余额/比例合法。
- 提交后：关闭弹窗、刷新列表、`message.success`；失败在表单上展示接口错误信息。

参考：`CopyTradingOrders/AddModal.tsx` 的 Form 布局、`Form.Item` + `rules`、条件显示（比例/固定金额）。

---

## 4. 触发记录

**入口**：列表行操作「查看触发记录」或单独 Tab/页。

### 4.1 展示方式（二选一或并存）

- **弹窗**：Modal 内 Table，按策略 ID 拉取该策略的触发记录。
- **独立页**：路由如 `/crypto-tail-strategy/records/:strategyId`，页面内 Table 或 Card 列表。

### 4.2 记录列表字段

| 列/项 | 说明 |
|-------|------|
| 触发时间 | 时间戳格式化为本地时间。 |
| 市场 | 市场标题 + 周期。 |
| 方向 (outcome) | Up / Down。 |
| 触发价格 | 当时进入区间的价格。 |
| 投入金额 | USDC，用 `formatUSDC`。 |
| 订单 ID | 若有；可截断 + Tooltip 全量。 |
| 状态 | 成功 / 失败。 |

支持按时间范围、状态筛选；移动端用 Card 或折叠列表。

---

## 5. 组件与技术要点

| 要点 | 说明 |
|------|------|
| **钱包提示** | 列表页与新增/编辑表单**必须**包含「使用单独钱包」的 Alert 提示，避免用户用混用钱包导致异常；文案走多语言。 |
| **创建前检查** | 点击「新增策略」时先检查自动赎回/Builder API 是否已配置；未配置则弹出简易「去配置」弹窗，引导用户到系统设置配置 API Key 与自动赎回，不打开策略表单。 |
| 多语言 | 所有文案 `t('cryptoTailStrategy.xxx')`，在 `locales/zh-CN`、`zh-TW`、`en` 的 `common.json` 中增加键。需包含：`cryptoTailStrategy.list.walletTip`、`cryptoTailStrategy.form.walletTip`，以及 `cryptoTailStrategy.redeemRequiredModal.title`、`cryptoTailStrategy.redeemRequiredModal.description`、`cryptoTailStrategy.redeemRequiredModal.goToSettings`、`cryptoTailStrategy.redeemRequiredModal.cancel`。文案示例：列表页 `walletTip`：「请使用单独的钱包运行尾盘策略，避免该钱包用于手动交易、跟单等其他操作，否则可能导致余额或仓位变化，造成策略执行异常。」表单内 `walletTip`：「建议使用专用钱包，避免手动操作等导致余额或下单异常。」未配置赎回弹窗 `title`：「请先配置自动赎回」；`description`：「尾盘策略依赖自动赎回功能，请先在系统设置中配置 Builder API Key 并开启自动赎回。」；`goToSettings`：「去配置」；`cancel`：「取消」。 |
| 金额 | 统一 `formatUSDC`（见 frontend.mdc）。 |
| 响应式 | `useMediaQuery`；按钮触摸目标 ≥ 44px；移动端主操作突出。 |
| 类型 | 不用 `any`；为策略、触发记录定义 TypeScript 类型。 |
| API | 通过 `apiService` 封装（如 `apiService.cryptoTailStrategy.list/create/update/delete/records`）。 |

---

## 6. 页面与文件建议对应

| 功能 | 建议路径/文件 |
|------|----------------|
| 列表页 | `frontend/src/pages/CryptoTailStrategyList.tsx` |
| 未配置赎回时的简易弹窗 | 内嵌在列表页的 Modal，或 `CryptoTailStrategyList/RedeemRequiredModal.tsx` |
| 新增/编辑弹窗 | `frontend/src/pages/CryptoTailStrategyList/FormModal.tsx` 或内嵌 Modal |
| 触发记录 | `frontend/src/pages/CryptoTailStrategyList/TriggerRecordsModal.tsx` 或 `CryptoTailStrategyRecords.tsx` |
| 路由 | `App.tsx` 中 `/crypto-tail-strategy`、可选 `/crypto-tail-strategy/records/:id` |
| 菜单 | `Layout.tsx` 中增加「尾盘策略」菜单项 |
| 类型 | `frontend/src/types/index.ts` 或 `types/cryptoTailStrategy.ts` 中增加策略与触发记录类型 |
| 多语言 | `frontend/src/locales/{zh-CN,zh-TW,en}/common.json` 中增加 `cryptoTailStrategy.*` |

---

## 7. 小结：UI 包含的主要元素

- **导航**：主导航中「尾盘策略」入口。
- **列表页**：标题、钱包提示 Alert、新增按钮（点击前先检查赎回配置，未配置则弹「去配置」简易弹窗）、筛选、表格/卡片（策略名、市场、价格区间、投入方式、状态、最近触发、操作）、加载与空状态。
- **未配置赎回弹窗**：简易 Modal，提示依赖自动赎回、需先配置 Builder API Key 与自动赎回；按钮「去配置」（跳转 `/system-settings`）、「取消」。
- **表单弹窗**：策略名、账户、市场选择、minPrice/maxPrice、投入方式（比例/固定）、启用开关、提交/取消。
- **触发记录**：时间、市场、outcome、触发价格、金额、订单 ID、状态；支持弹窗或独立页。
- **通用**：Ant Design 组件、响应式、多语言、formatUSDC、TypeScript 类型。

---

## 附录 A 后端/产品要求：自动赎回须支持本策略仓位

自动赎回逻辑**必须支持赎回由尾盘策略产生的订单所对应的仓位**。即：本策略触发的市价买入会形成仓位，这些仓位在满足「可赎回」条件时，应被纳入现有自动赎回流程并正常发起赎回，不得因来源为「尾盘策略」而被排除。后端实现时需保证：

- 尾盘策略下单产生的仓位，与跟单/手动下单等来源的仓位一视同仁，参与可赎回查询与批量赎回；
- 若当前自动赎回按账户或仓位类型过滤，需将「尾盘策略订单产生的仓位」包含在内。

这样前端所依赖的「自动赎回」对该策略才完整有效。
