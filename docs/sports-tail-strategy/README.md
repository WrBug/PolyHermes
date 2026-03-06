# 体育尾盘策略文档 (Sports Tail Strategy)

本目录集中存放与 Polymarket 体育市场尾盘策略相关的文档。

## 目录结构

```
sports-tail-strategy/
├── README.md                                    # 本说明
└── zh/                                          # 中文文档
    ├── sports-tail-strategy-tasks.md            # 任务与验收
    ├── sports-tail-strategy-ui-spec.md          # UI 规格
    ├── sports-tail-strategy-flow.md             # 流程说明
    └── sports-tail-strategy-market-data.md      # 市场数据与订阅
```

## 文档说明

| 文档 | 说明 |
|------|------|
| **tasks** (zh) | 开发任务与验收项 |
| **ui-spec** (zh) | 前端列表、表单、触发记录等 UI 规格 |
| **flow** (zh) | 策略整体流程（创建→触发→止盈止损→完成） |
| **market-data** (zh) | Gamma API 数据获取、WebSocket 订阅、价格监控 |

## 功能概述

体育尾盘策略用于在体育市场接近尾盘（胜率 90%+）时自动买入，利用高胜率市场低风险获利。

### 核心特性

1. **不区分方向**：只设置触发价格，系统自动监控两个方向，任意方向达到触发价即买入
2. **实时订阅**：通过 WebSocket 订阅订单簿，实时监控价格变化
3. **止盈止损**：支持设置止盈/止损价格，自动卖出
4. **订阅管理**：同一市场多策略共享订阅，无策略时自动取消订阅

### 适用场景

- 体育比赛接近尾声，一方胜率 90%+ 时买入
- 大小分市场接近尾盘时套利
- 低风险稳定收益场景
