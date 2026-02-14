# AUTO 最小价差：100%→50% 动态系数方案

## 现状

- **BinanceKlineAutoSpreadService**：拉取历史 K 线 → IQR 剔除异常值 → 求平均得到「基础价差」→ **固定 ×0.7** 后缓存。
- 预加载（周期开始时）：`computeAndCache()` 计算并缓存的是 **已乘 0.7** 的值。
- 触发时：`getAutoMinSpread()` 直接返回缓存值，等价于始终用 **70%** 的系数。

问题：70% 固定，无法随周期内时间变化放宽或收紧。

---

## 目标

1. **预加载提供 100% 数值**：缓存里存「基础价差」（IQR 平均），不再乘 0.7，即预加载 = 100% 基准。
2. **系数随区间时间点动态递减**：从 **100%** 线性递减到 **50%**，根据「当前时间在区间内的进度」计算。

---

## 方案一：按「触发窗口」进度（推荐）

**区间**：策略的触发窗口 `[periodStartUnix + windowStartSeconds, periodStartUnix + windowEndSeconds]`。

- 窗口起始：系数 = **100%**（最严，价差要求最高）。
- 窗口内时间越靠后，系数越小；窗口结束：系数 = **50%**（最松，更容易触发）。

公式（**progress 按毫秒计算**，保证精度）：

```
windowStartMs = (periodStartUnix + windowStartSeconds) * 1000
windowEndMs   = (periodStartUnix + windowEndSeconds) * 1000
windowLenMs   = windowEndMs - windowStartMs
nowMs         = System.currentTimeMillis()

progress = (nowMs - windowStartMs) / windowLenMs
progress = clamp(progress, 0, 1)

// 比例系数 = progress × (100% - 50%)，即已「消耗」的系数降幅
// 真正系数 = 100% - 比例系数
coefficient = 1.0 - progress × (1.0 - 0.5) = 1.0 - 0.5 × progress

effectiveMinSpread = baseSpread × coefficient
```

**计算示例**（时间区间 14分0秒～15分0秒，窗口 60 秒 = 60000 ms）：

| 时刻       | 进入窗口的毫秒数 | progress（按毫秒） | 比例系数           | 真正系数   |
|------------|------------------|--------------------|--------------------|------------|
| 14:00      | 0                | 0/60000 = 0%       | 0% × 50% = 0%      | 100%       |
| 14:15      | 15000            | 15000/60000 = 25%  | 25% × 50% = 12.5%  | **87.5%**  |
| 14:30      | 30000            | 30000/60000 = 50%   | 50% × 50% = 25%    | 75%        |
| 15:00      | 60000            | 60000/60000 = 100%  | 100% × 50% = 50%   | 50%        |

即：在 14分15秒 时，progress = 15000ms / 60000ms = 25%，比例系数 = 12.5%，真正系数 = **87.5%**。实现时统一用毫秒计算 progress，避免秒级舍入误差。

- 需要策略的 `windowStartSeconds`、`windowEndSeconds` 传入计算处；若窗口长度为 0，可退化为系数 = 1.0 或 0.5（需约定）。

**优点**：与「尾盘只在窗口内触发」一致，时间语义清晰；毫秒级 progress 更精确。  
**缺点**：`getAutoMinSpread` 需要增加当前时间（毫秒）和窗口参数（或传整个 strategy）。

---

## 方案二：按「整周期」进度

**区间**：整个周期 `[periodStartUnix, periodStartUnix + intervalSeconds]`。**progress 按毫秒计算**。

```
periodStartMs = periodStartUnix * 1000
periodEndMs   = (periodStartUnix + intervalSeconds) * 1000
periodLenMs   = intervalSeconds * 1000L
nowMs         = System.currentTimeMillis()

progress = (nowMs - periodStartMs) / periodLenMs
progress = clamp(progress, 0, 1)

coefficient = 1.0 - 0.5 * progress
effectiveMinSpread = baseSpread × coefficient
```

**优点**：只依赖 `intervalSeconds`、`periodStartUnix`、`nowSeconds`，不依赖窗口配置。  
**缺点**：若窗口只占周期后半段，周期前半段也会在算系数，语义上不如按窗口精确。

---

## 实现要点

### 1. 缓存 100% 基准值

- **BinanceKlineAutoSpreadService**：
  - `computeAndCache()`：缓存 **不乘 0.7** 的 (avgUp, avgDown)，即 IQR 平均后的原始值（100% 基准）。
  - 可保留方法名与入参不变，仅去掉 `autoSpreadCoefficient` 的乘法；或新增 `getBaseSpread()` 语义，内部仍用同一缓存。

### 2. 动态系数计算位置

- 系数依赖「当前时间」和「区间定义」，适合在 **触发校验处** 算，而不是在 AutoSpread 服务里写死。
- **CryptoTailStrategyExecutionService.passMinSpreadCheck()**：
  - 当前：`getAutoMinSpread(intervalSeconds, periodStartUnix, outcomeIndex)` 得到已乘系数的值。
  - 改为：  
    - 取「基础价差」：`getAutoMinSpreadBase(intervalSeconds, periodStartUnix, outcomeIndex)` 或由现有缓存返回 100% 值。  
    - 在 `passMinSpreadCheck` 内根据 `strategy.windowStartSeconds/windowEndSeconds` 和 `System.currentTimeMillis()`（毫秒）算 `progress`（按毫秒）→ `coefficient` → `effectiveMinSpread = baseSpread × coefficient`。

### 3. 接口形态建议

- **BinanceKlineAutoSpreadService**：
  - `computeAndCache(interval, periodStartUnix)`：只缓存 100% 基准 (baseUp, baseDown)，不再乘 0.7。
  - `getAutoMinSpreadBase(interval, periodStartUnix, outcomeIndex): BigDecimal?`：仅返回缓存的基础价差；若需兼容旧名，可保留 `getAutoMinSpread` 但增加可选参数 `coefficient`，默认 1.0。
- **CryptoTailStrategyExecutionService**：
  - 在 `passMinSpreadCheck(strategy, periodStartUnix, outcomeIndex)` 内：
    - 取 `baseSpread = getAutoMinSpreadBase(...)`。
    - 计算 `progress`（按方案一用 windowStart/End，或方案二用 interval）。
    - `coefficient = 1.0 - 0.5 * progress`，再 `effectiveMinSpread = baseSpread * coefficient` 做比较。

### 4. 边界与兼容

- 窗口长度为 0：可约定 `coefficient = 0.5` 或 1.0，避免除零。
- 已有策略未配置窗口（全 0）：若用方案一，可退化为「整周期」或固定 0.5/1.0」。
- 预加载逻辑（如 CryptoTailOrderbookWsService 的 `precomputeAutoMinSpreadForCurrentPeriods`）无需改，仍调用 `computeAndCache`，只是缓存内容变为 100% 基准。

---

## 小结

| 项目       | 内容 |
|------------|------|
| 预加载     | 缓存 100% 基础价差（去掉固定 0.7） |
| 系数范围   | 100% → 50% 线性递减 |
| 推荐区间   | 按触发窗口 `windowStartSeconds`～`windowEndSeconds` 计算进度（方案一） |
| progress   | **按毫秒计算**：`(nowMs - windowStartMs) / windowLenMs`，避免秒级舍入误差 |
| 计算位置   | 触发时在 `passMinSpreadCheck` 中算 progress → coefficient → effectiveMinSpread |

按上述实现后，AUTO 模式即为「预加载提供 100% 数值 + 随区间时间点从 100% 递减到 50%」的动态方案。
