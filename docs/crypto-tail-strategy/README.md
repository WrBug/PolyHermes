# 加密价差策略文档 (Crypto Spread Strategy)

本目录集中存放与 Polymarket 加密市场加密价差策略相关的文档。

## 目录结构

```
crypto-tail-strategy/
├── README.md           # 本说明
├── crypto-tail-auto-spread-dynamic-coefficient.md   # 自动价差动态系数（中英通用）
├── zh/                 # 中文文档
│   ├── crypto-tail-strategy-user-guide.md    # 用户配置指南
│   ├── crypto-tail-strategy-ui-spec.md       # UI 规格
│   ├── crypto-tail-strategy-tasks.md         # 任务与验收
│   ├── crypto-tail-strategy-flow.md          # 流程说明
│   ├── crypto-tail-strategy-min-spread-flow.md  # 最小/最大价差流程
│   └── crypto-tail-strategy-market-data.md   # 市场数据与周期
└── en/                 # 英文文档
    └── crypto-tail-strategy-user-guide.md    # User configuration guide
```

## 文档说明

| 文档 | 说明 |
|------|------|
| **user-guide** (zh/en) | 面向用户的策略配置指南与 FAQ |
| **ui-spec** (zh) | 前端列表、表单、时间窗口、触发记录等 UI 规格 |
| **tasks** (zh) | 开发任务与验收项 |
| **flow** (zh) | 策略整体流程 |
| **min-spread-flow** (zh) | 价差过滤（最小/最大价差）流程 |
| **market-data** (zh) | Gamma slug、周期、时间区间、价格判断等市场数据规则 |
| **auto-spread-dynamic-coefficient** | 自动价差模式下动态系数计算说明 |
