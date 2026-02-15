# Neg Risk 赎回与对应 JS/TS 代码说明

## Neg Risk 特殊逻辑

### 1. 赎回（Redeem）

- **普通市场**：仓位由 **USDC** 抵押，调用 CTF 的 `redeemPositions(collateralToken, parentCollectionId, conditionId, indexSets)` 时，`collateralToken` 为 USDC 地址（Polygon: `0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174`）。
- **Neg Risk 市场**：仓位由 **WrappedCollateral** 抵押（neg-risk-ctf-adapter 设计），同一笔赎回必须使用 WrappedCollateral 地址（Polygon: `0x3A3BD7bb9528E159577F7C2e685CC81A765002E2`），否则链上找不到对应仓位，会得到 payout 0。

本项目中：通过 Gamma API 的 `negRisk` / `negRiskOther` 判断市场类型，赎回时对 Neg Risk 市场传 `isNegRisk=true`，在 `createRedeemTx` 中选用 WrappedCollateral 作为 `collateralToken`。

### 2. 下单/签约（Order Signing）

- **普通市场**：使用标准 CTF Exchange 合约签约。
- **Neg Risk 市场**：必须使用 **Neg Risk CTF Exchange** 合约签约，否则 CLOB 返回 invalid signature。

见 `OrderSigningService.getExchangeContract(negRisk)`、`CopyOrderTrackingService` 中按 `getNegRiskByConditionId` 选择 exchange。

---

## 对应的 JS/TS 代码位置

| 功能 | 仓库/来源 | 路径或说明 |
|------|-----------|------------|
| Relayer 执行、Safe 交易提交 | [Polymarket/builder-relayer-client](https://github.com/Polymarket/builder-relayer-client) | `src/client.ts`（`execute`）、`src/encode/safe.ts`（MultiSend `createSafeMultisendTransaction`） |
| 链与合约配置 | builder-relayer-client | `src/config/index.ts`（Polygon/Amoy 的 SafeMultisend 等） |
| 赎回 calldata 构建 | **官方仓库无** | 官方库只负责执行传入的 `Transaction[]`，不包含 `createRedeemTx` 或 redeem 工具函数 |
| 社区赎回示例（单一 collateral） | [Gist: redeem-positions](https://gist.github.com/Waawzer/5cdff342767265c2637e21607d03f6eb) | 使用 `collateralToken` 调用 `redeemPositions`，**未区分 Neg Risk**（全部用同一 collateral，如 USDC） |
| Neg Risk 合约与 WrappedCollateral | [Polymarket/neg-risk-ctf-adapter](https://github.com/Polymarket/neg-risk-ctf-adapter) | README、`addresses.json`（137 链上 negRiskWrappedCollateral 等地址） |
| 市场是否 Neg Risk | Gamma API | 市场/事件的 `negRisk`、`negRiskOther` 字段，本项目中通过 `MarketService.getNegRiskByConditionId` 查询 |

---

## 小结

- **Neg Risk 特殊逻辑**：赎回用 WrappedCollateral、下单用 Neg Risk Exchange；均由「是否为 Neg Risk 市场」分支处理。
- **对应 JS 代码**：执行与 MultiSend 在 **builder-relayer-client**；赎回参数与 calldata 在**应用层**构建，官方无现成 redeem 工具；Neg Risk 的抵押品与合约见 **neg-risk-ctf-adapter** 与 **Gamma API**。
