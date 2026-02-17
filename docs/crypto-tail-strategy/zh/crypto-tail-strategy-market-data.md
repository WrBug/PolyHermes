# 加密市场尾盘策略 - 5/15 分钟市场数据获取说明

> 前端 UI 与交互详见 `crypto-tail-strategy-ui-spec.md`。

## 1. 数据源

- **Gamma API**：`https://gamma-api.polymarket.com`
- 用于获取市场元数据：conditionId、开始/结束时间、标题、clobTokenIds 等。
- 无需鉴权。

## 2. 市场类型与 Slug 规则

| 类型 | Event Slug 规则 | 周期长度 | 说明 |
|------|-----------------|----------|------|
| Bitcoin 5 分钟 | `btc-updown-5m-{periodStartUnix}` | 5 min | periodStartUnix 为 5 分钟边界的 Unix 时间戳（秒） |
| Bitcoin 15 分钟 | `btc-updown-15m-{periodStartUnix}` | 15 min | periodStartUnix 为 15 分钟边界：`(now // 900) * 900` |
| Ethereum 5 分钟 | `eth-updown-5m-{ts}` | 5 min | 暂未验证是否在平台上线；如有可按相同规则推导 |
| Ethereum 15 分钟 | `eth-updown-15m-{ts}` | 15 min | 已验证存在 |

- 5 分钟周期：按 **300 秒** 对齐；当前周期起点可用 `(nowUnix // 300) * 300`，下一周期为 `+300`。
- 15 分钟周期：按 **900 秒** 对齐；当前周期起点可用 `(nowUnix // 900) * 900`。slug 中的时间戳即为周期起始 Unix 秒；周期结束以 API 的 endDate 为准。

## 3. 获取单个周期市场（开始时间、结束时间）

### 3.1 请求

```bash
# 5 分钟 - 当前周期（示例时间戳需替换为当前周期起点）
curl -s "https://gamma-api.polymarket.com/events/slug/btc-updown-5m-1771007100"

# 15 分钟 - 需使用实际存在的时间戳（可从前端或历史 slug 得知）
curl -s "https://gamma-api.polymarket.com/events/slug/btc-updown-15m-1770882300"
```

### 3.2 响应结构（与开始/结束时间相关）

- **Event 层**：`startDate`、`endDate`（ISO 8601）。
- **markets[]**：每个市场有 `conditionId`、`question`、`startDate`、`endDate`、`clobTokenIds` 等。

**周期本身**：例如 5 分钟市场 "1:30PM-1:35PM ET"，理应是 **startDate = 1:30 PM**、**endDate = 1:35 PM**。

**API 返回值与周期起止的对应关系（已用脚本验证）**：

| 字段 | 是否等于周期起止 | 说明 |
|------|------------------|------|
| **endDate**（Event / Market） | **是**，等于周期结束时间（如 1:35 PM） | API 的 endDate 即周期终点，可直接用。 |
| **startDate**（Event / Market） | **否**，不等于周期开始时间（1:30 PM） | API 的 startDate 是市场创建/开放时间，不是周期起点，故**不能**当 1:30 PM 用。 |

**正确做法**：周期起点（1:30 PM）用 **slug 中的时间戳** 推导；周期终点（1:35 PM）用 API 的 **endDate**。

- **5 分钟**：周期开始 = `slug_ts`（即 slug 中的 Unix 秒），周期结束 = `endDate`（或 `slug_ts + 300`）。
- **15 分钟**：周期开始 = `slug_ts`，周期结束 = `endDate`（或 `slug_ts + 900`）。

**示例（脚本输出解读）**：若 current 5m slug 为 `btc-updown-5m-1771007400`、title 为 "1:30PM-1:35PM ET"、endDate 为 `2026-02-13T18:35:00Z`，则 1771007400 = 18:30 UTC = 1:30 PM ET，即周期起点；endDate 18:35 UTC = 1:35 PM ET = 周期终点。next 5m slug 为 1771007700 = 1771007400 + 300，即下一周期起点。15m 同理：current slug 1771007400（1:30–1:45 PM ET），next 1771008300 = 1771007400 + 900（1:45–2:00 PM ET）。

## 4. 如何列出“当前及未来”5/15 分钟市场

- Gamma 未提供按“5 分钟 / 15 分钟”或“Up or Down”的 tag 筛选；`tag_id=744`（cryptocurrency）未返回这些短期市场。
- **可行方式**：
  1. **按周期时间戳生成 slug 并逐个请求**  
     - 5 分钟：当前周期 `ts = (nowUnix // 300) * 300`，下一周期 `ts + 300`，再下一周期 `ts + 600` …  
     - 15 分钟：`ts = (nowUnix // 900) * 900`，然后 `ts + 900`、`ts + 1800` …  
     - 请求 `GET /events/slug/btc-updown-5m-{ts}` 或 `btc-updown-15m-{ts}`；若返回 404 表示该周期尚未创建或已过期，可跳过。
  2. **用户选择“市场”时**：若前端/后端已知“系列”（如 Bitcoin 5 minute），则只需约定 slug 前缀（`btc-updown-5m`、`btc-updown-15m`）与周期长度（300/900），按当前时间计算周期起点并请求对应 slug 即可得到当前周期的 conditionId、startDate、endDate；下一周期同理。

## 5. 周期边界与“每周期监听”

- **周期开始**：使用 **slug 中的时间戳** `periodStartUnix`（即请求 slug 时的 `btc-updown-5m-{ts}` 里的 `ts`），不要用 API 返回的 startDate。
- **周期结束**：使用 API 返回的 **event.endDate 或 market.endDate**（与 slug_ts + 300/900 一致）。
- 判断“当前是否在该周期内”：`periodStartUnix <= nowUnix < endDateUnix`，其中 `periodStartUnix` 从 slug 得到，`endDateUnix` 由 endDate 解析。
- 策略“每周期开始时开始监听”：当 `now` 跨过当前周期的 endDate（或下一周期的 periodStartUnix）时，视为新周期开始，重置“本周期是否已触发”等状态。

## 6. 如何保证每个周期的市场都能正确处理

### 6.1 用“当前时间”唯一确定当前周期

- 服务端只用**当前 Unix 时间**推导周期，不依赖 API 的 startDate。
- **5 分钟**：`periodStartUnix = (nowUnix / 300) * 300`（整除）。
- **15 分钟**：`periodStartUnix = (nowUnix / 900) * 900`。
- 同一时刻算出的 `periodStartUnix` 唯一，对应唯一 slug（如 `btc-updown-5m-{periodStartUnix}`），从而对应唯一市场（conditionId、tokenIds、endDate）。

### 6.2 按周期拉取市场并切换

- **首次进入或策略启用**：用当前的 `periodStartUnix` 拼 slug，请求 Gamma `GET /events/slug/{slug}`，拿到该周期的 conditionId、endDate、clobTokenIds；用 endDate 解析得到 `endDateUnix`。
- **每次需要判断“是否还在本周期”或“是否该下单”时**：先算当前 `currentPeriodStart = (nowUnix / interval) * interval`（interval 为 300 或 900）。若 `currentPeriodStart` 大于上一笔使用的 `periodStartUnix`，说明已进入**下一周期**：
  - 用新的 `currentPeriodStart` 拼 slug，重新请求 Gamma，拿到**新周期**的 conditionId、endDate、clobTokenIds；
  - 用新周期的 tokenIds 订阅/拉取订单簿，用新 endDate 作为本周期结束时间；
  - 重置本周期“是否已触发”等状态，避免把上一周期的状态带到新周期。
- **周期内**：始终用**本周期**的 conditionId、tokenIds、endDate 做价格监听与下单，不要混用上一周期的数据。

### 6.3 周期切换时机与 404 处理

- **切换时机**：以 `nowUnix >= endDateUnix` 或 `(nowUnix / interval) * interval > periodStartUnix` 作为“本周期已结束”，立刻按 6.2 用新 `periodStartUnix` 拉新周期市场。
- **新周期市场尚未创建（404）**：Gamma 可能稍晚才创建下一周期 event。若请求 slug 返回 404，可短间隔重试（如 5–15 秒）或等到下一整点/对齐点再试；重试时仍用**同一** `periodStartUnix`，避免用错周期。若长时间 404，可记录日志并跳过该周期，下一周期再正常拉取。

### 6.4 下单失败重试规则（每周期最多下单一次）

- 市价单提交失败时，**最多重试 2 次**（即 1 次初始 + 2 次重试，共 3 次尝试）。
- 若 3 次均失败：
  - 本周期**不再**对该 outcome 下单；
  - 记录失败原因与状态（便于审计与前端展示触发记录）。
- 周期切换时（6.2）重置为“未下单”，仅对新周期做新的判断与尝试。

### 6.5 去重与幂等（每周期最多触发一次）

- 以「策略 + 周期」唯一标识一次执行，例如 `(strategyId, periodStartUnix)` 或 `(accountId, slugPrefix, periodStartUnix)`。
- 在数据库或内存中记录：本周期是否已触发、是否已下单。若已触发，同一周期内不再根据价格区间下单。
- 周期切换时（6.2）清空或更新为“新周期未触发”，只对新周期的 conditionId/tokenIds 做监听与下单。

### 6.6 时间区间（窗口）内才触发

- 策略可配置**时间区间**：从周期起点起算的「开始秒数」与「结束秒数」，例如 5 分钟市场可选 0～300 秒内的一段，15 分钟市场可选 0～900 秒内的一段（对应前端“分+秒”下拉，如 3 分 0 秒～12 分 0 秒即 180～720 秒）。
- **执行规则**：仅当 `periodStartUnix + windowStartSeconds <= nowUnix < periodStartUnix + windowEndSeconds` 时，才根据 7.1 判断价格是否进入 [minPrice, maxPrice] 并执行下单；**区间外不进行价格判断与下单**。
- 存储：策略表（或配置）中保存 `windowStartSeconds`、`windowEndSeconds`（整数，单位秒）；校验：`windowStartSeconds <= windowEndSeconds`，且不超过周期长度（5min 市场 ≤ 300，15min 市场 ≤ 900）。详见 [UI 规格 - 时间区间](crypto-tail-strategy-ui-spec.md)。

### 6.7 小结

| 要点 | 做法 |
|------|------|
| 周期唯一性 | 用 `(nowUnix / interval) * interval` 得到 periodStartUnix，再拼 slug，不依赖 API startDate。 |
| 周期数据 | 每周期用**该周期**的 slug 请求 Gamma，使用返回的 conditionId、endDate、clobTokenIds。 |
| 切换 | 当 `nowUnix >= endDateUnix` 或当前算出的 periodStartUnix 变化时，拉取新周期并重置状态。 |
| 404 | 同一 periodStartUnix 重试；长时间 404 可跳过该周期并打日志。 |
| 下单失败 | 失败后最多重试 2 次；仍失败则本周期不再下单并记录状态。 |
| 每周期只触发一次 | 用 (策略, periodStartUnix) 做去重，周期切换时重置“已触发”状态。 |
| 时间区间 | 仅当 periodStartUnix + windowStartSeconds ≤ now < periodStartUnix + windowEndSeconds 时做价格判断与下单；区间外不处理。 |

按上述方式，每个周期都会对应到正确的 slug、正确的市场与 endDate，并在周期结束时切换到下一周期；仅在配置的时间窗口内才根据价格触发下单，避免混周期或漏周期。

## 7. 与订单簿 / 价格的关系

- 价格由 **CLOB 订单簿**（或 WebSocket）获取，不依赖 Gamma；Gamma 仅提供市场元数据。
- 使用 market.conditionId 与 markets[].clobTokenIds 解析出 tokenId，再订阅或请求该 token 的订单簿即可得到实时价格，用于区间判断与市价下单。

### 7.1 价格区间与「反方向」判断（如 minPrice = 0.92）

二元市场（Up or Down）有两个 outcome：通常 outcomeIndex 0 = Up，1 = Down，各对应一个 tokenId 和订单簿。

- **配置含义**：用户配置 minPrice = 0.92（及可选 maxPrice，默认 1）表示「当**某个 outcome 的价格**落在 [0.92, 1] 时触发市价买入**该** outcome」。
- **不预先选方向**：不需要用户选「买 Up 还是买 Down」；谁的价格先进入区间就买谁。
- **订单簿取价方式（与现有市价单逻辑一致）**：
  - 对每个 outcome，取该 tokenId 订单簿的 **bestBid**（最高买入价）作为当前价格用于区间判断；若取价规则与现有市价买入逻辑不同，请以系统现有规则为准并在实现文档中写明。
- **判断方式**：
  - 同时取**两个 outcome** 的当前价格（按上述取价规则）。
  - 对 **outcome 0**：若 `price0 >= minPrice && price0 <= maxPrice` → 满足触发条件，买入 outcome 0（Up）。
  - 对 **outcome 1**：若 `price1 >= minPrice && price1 <= maxPrice` → 满足触发条件，买入 outcome 1（Down）。
- **反方向**：「反方向」即另一个 outcome。例如若本轮已因 outcome 0 进入 [0.92, 1] 而买入 Up，则本周期内**不再**检查 outcome 1 是否也进入区间、也不再买 Down；反之若先触发的是 outcome 1（Down），则本周期不再买 Up。实现上：一旦本周期已对**任意一个** outcome 触发并下单，即标记本周期已触发，不再对**另一个 outcome（反方向）**做区间判断与下单。
- **同一时刻两边都进区间**：若同一时刻 Up 和 Down 的价格都在 [0.92, 1]（理论上二元市场 Up+Down≈1 时不会同时 ≥0.92，但若出现），可约定按 outcomeIndex 优先（如先判 0 再判 1）或先到先得，只执行一笔买入，本周期不再买反方向。

总结：配置 0.92 时，对**两个方向**都做同一区间判断；先满足区间的那一侧触发买入，另一侧即为反方向，本周期不再触发。

## 8. 验证方式

**startDate/endDate 验证结论**：已用脚本对比 slug 时间戳与 API 返回的 startDate/endDate。**endDate 等于当前周期结束时间**；**startDate 不等于周期起始点**（为市场创建/开放时间），周期起始点应以 slug 中的时间戳为准。详见上文 3.2、5 节。

### 8.1 脚本（推荐）

项目内脚本，会请求当前/下一 5 分钟与 15 分钟 BTC 市场并打印 conditionId、startDate、endDate、clobTokenIds：

```bash
python3 scripts/fetch_crypto_minute_markets.py
```

### 8.2 curl 示例

```bash
# 5 分钟 - 当前或下一周期（时间戳需替换为实际周期起点）
curl -s "https://gamma-api.polymarket.com/events/slug/btc-updown-5m-1771007100"

# 15 分钟 - 当前周期（时间戳需替换为实际周期起点）
curl -s "https://gamma-api.polymarket.com/events/slug/btc-updown-15m-1771006500"

# 15 分钟 - 历史存在的事件
curl -s "https://gamma-api.polymarket.com/events/slug/btc-updown-15m-1770882300"
curl -s "https://gamma-api.polymarket.com/events/slug/eth-updown-15m-1770801300"
```

若返回 403，可加 User-Agent：`curl -s -H "User-Agent: PolymarketBot/1.0" "https://gamma-api.polymarket.com/events/slug/btc-updown-5m-1771007100"`
