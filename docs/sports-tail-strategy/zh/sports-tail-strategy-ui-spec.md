# 体育尾盘策略 - UI 规格

## 一、策略列表页

### 1.1 页面布局

**路由**：`/sports-tail-strategy`

**桌面端**：

```
┌─────────────────────────────────────────────────────────────────────┐
│  体育尾盘策略                                    [+ 新增策略]          │
├─────────────────────────────────────────────────────────────────────┤
│  筛选: [账户选择▼]  [类别: 全部▼]                                    │
├─────────────────────────────────────────────────────────────────────┤
│  ┌───────────────────────────────────────────────────────────────┐ │
│  │ Lakers vs Bulls - Who will win?                               │ │
│  │ 账户: Account 1                                                │ │
│  │ 触发价: >=0.90 | 金额: 10 USDC                                │ │
│  │ 止盈: 0.98 | 止损: 0.85                                       │ │
│  │ ───────────────────────────────────────────────────────────    │ │
│  │ 成交价: 0.91 YES | 数量: 10.99 份 | 盈亏: 待结算               │ │
│  │                                          [查看记录] [删除]      │ │
│  └───────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
```

**移动端**：使用卡片布局，信息折叠展示

### 1.2 列表字段

| 字段 | 说明 |
|------|------|
| 市场标题 | `marketTitle`，可点击跳转 Polymarket |
| 账户 | 关联的账户名称 |
| 触发价 | `>= {triggerPrice}` |
| 金额 | 固定金额 USDC 或 余额比例 % |
| 止盈/止损 | 配置的止盈止损价格，未配置显示 `-` |
| 成交信息 | 已成交：`{filledPrice} {YES/NO} \| {filledShares} 份`<br>未成交：`实时价格: YES {price} \| NO {price}` |
| 盈亏 | 已卖出：`+{realizedPnl} USDC`<br>已成交未卖出：`待结算`<br>未成交：`-` |

### 1.3 操作按钮

| 按钮 | 说明 |
|------|------|
| 查看记录 | 打开该策略的触发记录详情 |
| 删除 | 删除策略（需二次确认） |

**注意**：不支持启用/禁用功能，无状态列

---

## 二、新增策略表单

### 2.1 表单字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| 账户选择 | 下拉 | 是 | 选择账户 |
| 选择市场 | 搜索选择 | 是 | 从市场列表中选择（支持筛选） |
| 触发条件 | 复合输入 | 是 | `当价格 [>=] [0.90] 时触发买入` |
| 下注金额 | 单选+输入 | 是 | 固定金额 USDC 或 余额比例 % |
| 启用自动卖出 | 开关 | 否 | 开启后显示止盈止损 |
| 止盈价格 | 输入 | 否 | 价格上涨到此值时自动卖出 |
| 止损价格 | 输入 | 否 | 价格下跌到此值时自动卖出 |

**注意**：
- 不选择方向（YES/NO），只设置触发价格
- 系统自动监控两个方向，任意方向达到触发价即买入
- 默认只触发一次

### 2.2 市场选择器

**筛选条件**：

```
┌─────────────────────────────────────────────────────────────┐
│  体育类别                                                   │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ [全部 ▼]                                             │   │
│  │ 选项: 全部 / NBA / NFL / 英超 / 西甲 / 棒球...      │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  市场类型                                                   │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ [全部 ▼]                                             │   │
│  │ 选项: 全部 / 胜负 / 让分 / 大小分                    │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  结束时间                                                   │
│  ○ 全部  ○ 今天  ○ 未来24小时  ○ 未来7天                  │
│                                                             │
│  最小流动性                                                 │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ [     ] USDC（留空表示不限制）                       │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  搜索关键词                                                 │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ [搜索球队、比赛...]                      [搜索]     │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

**市场列表**：

```
┌─────────────────────────────────────────────────────────────┐
│  ○ Lakers vs Bulls - Who will win?                          │
│    YES: 0.92  NO: 0.08  |  流动性: 50,000 USDC              │
│    结束: 2024-03-07 15:30 (剩余2小时30分)                   │
│                                                             │
│  ○ Warriors @ Heat - O/U 220.5                              │
│    Over: 0.55  Under: 0.45  |  流动性: 30,000 USDC          │
│    结束: 2024-03-07 18:00 (剩余5小时)                       │
└─────────────────────────────────────────────────────────────┘
```

### 2.3 预估收益展示

```
┌─────────────────────────────────────────────────────────────┐
│  预估收益                                                   │
│  ────────────────────────────────────────────────────────  │
│  买入价格: 0.90 | 买入金额: 10 USDC                         │
│  预计份额: 11.11 | 预计收益: +1.11 USDC (11.1%)            │
│                                                             │
│  止盈 (0.98): 收益 +8.89 USDC (88.9%)                       │
│  止损 (0.85): 亏损 -1.67 USDC (-16.7%)                      │
└─────────────────────────────────────────────────────────────┘
```

---

## 三、触发记录页（全局）

### 3.1 页面布局

**路由**：`/sports-tail-strategy/records`

**说明**：全局记录列表，不是单个策略的记录

**桌面端表格**：

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│  触发记录                                                                            │
├────────────────────────────────────────────────────────────────────────────────────────┤
│  筛选: [账户选择▼]  [状态: 全部▼]  [时间范围: 最近7天▼]                                │
├────────────────────────────────────────────────────────────────────────────────────────┤
│  时间        │ 市场标题           │ 方向 │ 买入价 │ 金额    │ 卖出价  │ 盈亏        │
│  03-07 15:30 │ Lakers vs Bulls   │ YES  │ 0.91   │ 10.00   │ 0.98    │ +7.89 USDC  │
│  03-07 14:20 │ Warriors @ Heat   │ Over │ 0.55   │ 5.00    │ -       │ 待结算      │
│  03-06 18:45 │ Celtics vs Heat   │ NO   │ 0.92   │ 20.00   │ 0.85    │ -7.00 USDC  │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

**移动端**：使用卡片布局

### 3.2 记录字段

| 字段 | 说明 |
|------|------|
| 时间 | `triggeredAt` 格式化显示 |
| 市场标题 | `marketTitle`，可点击跳转 Polymarket |
| 方向 | `outcomeName` 或 YES/NO/Over/Under |
| 买入价 | `buyPrice` |
| 金额 | `buyAmount` USDC |
| 卖出价 | `sellPrice`，未卖出显示 `-` |
| 盈亏 | `realizedPnl`，未结算显示 `待结算` |

### 3.3 筛选条件

| 条件 | 说明 |
|------|------|
| 账户 | 按账户筛选 |
| 状态 | 全部 / 待结算 / 已止盈 / 已止损 / 已完成 |
| 时间范围 | 最近24小时 / 最近7天 / 最近30天 / 全部 |

---

## 四、响应式适配

### 4.1 断点

- 移动端: < 768px
- 桌面端: >= 768px

### 4.2 移动端适配

1. 列表使用卡片布局，信息可折叠
2. 表单使用分步或滚动布局
3. 触发记录使用卡片列表
4. 按钮最小触摸目标 44x44px

---

## 五、多语言 Key

```
sportsTailStrategy.list.title=体育尾盘策略
sportsTailStrategy.list.addStrategy=新增策略
sportsTailStrategy.list.filter.account=账户
sportsTailStrategy.list.filter.category=类别
sportsTailStrategy.list.filter.allCategory=全部
sportsTailStrategy.list.triggerPrice=触发价
sportsTailStrategy.list.amount=金额
sportsTailStrategy.list.takeProfitStopLoss=止盈/止损
sportsTailStrategy.list.filledPrice=成交价
sportsTailStrategy.list.realtimePrice=实时价格
sportsTailStrategy.list.shares=份
sportsTailStrategy.list.pnl=盈亏
sportsTailStrategy.list.pending=待结算
sportsTailStrategy.list.viewRecords=查看记录
sportsTailStrategy.list.delete=删除
sportsTailStrategy.list.deleteConfirm=确定删除该策略吗？

sportsTailStrategy.form.title=新增体育尾盘策略
sportsTailStrategy.form.account=账户
sportsTailStrategy.form.selectAccount=选择账户
sportsTailStrategy.form.selectMarket=选择市场
sportsTailStrategy.form.triggerCondition=触发条件
sportsTailStrategy.form.triggerPriceHelp=当任意方向价格达到触发价时买入
sportsTailStrategy.form.amount=下注金额
sportsTailStrategy.form.fixedAmount=固定金额
sportsTailStrategy.form.ratio=余额比例
sportsTailStrategy.form.autoSell=启用自动卖出
sportsTailStrategy.form.takeProfitPrice=止盈价格
sportsTailStrategy.form.takeProfitHelp=价格上涨到此值时自动卖出
sportsTailStrategy.form.stopLossPrice=止损价格
sportsTailStrategy.form.stopLossHelp=价格下跌到此值时自动卖出
sportsTailStrategy.form.estimatedReturn=预估收益
sportsTailStrategy.form.buyPrice=买入价格
sportsTailStrategy.form.buyAmount=买入金额
sportsTailStrategy.form.estimatedShares=预计份额
sportsTailStrategy.form.estimatedPnl=预计收益

sportsTailStrategy.marketSearch.sport=体育类别
sportsTailStrategy.marketSearch.marketType=市场类型
sportsTailStrategy.marketSearch.endTime=结束时间
sportsTailStrategy.marketSearch.minLiquidity=最小流动性
sportsTailStrategy.marketSearch.keyword=搜索关键词
sportsTailStrategy.marketSearch.search=搜索
sportsTailStrategy.marketSearch.liquidity=流动性
sportsTailStrategy.marketSearch.remaining=剩余

sportsTailStrategy.records.title=触发记录
sportsTailStrategy.records.time=时间
sportsTailStrategy.records.market=市场
sportsTailStrategy.records.direction=方向
sportsTailStrategy.records.buyPrice=买入价
sportsTailStrategy.records.amount=金额
sportsTailStrategy.records.sellPrice=卖出价
sportsTailStrategy.records.pnl=盈亏
sportsTailStrategy.records.pending=待结算
sportsTailStrategy.records.takeProfit=已止盈
sportsTailStrategy.records.stopLoss=已止损
```
