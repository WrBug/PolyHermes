# NBA 比赛数据获取实现

## 一、概述

本系统从 NBA Stats API 实时获取 NBA 比赛数据：
- **数据源**：NBA Stats API（官方数据源，实时、准确、完整）
- **不依赖数据库**：所有数据从 API 实时获取

## 二、实现架构

### 2.1 数据流程

```
1. 从 NBA Stats API 获取比赛列表（实时）
   ↓
2. 解析 API 响应，转换为 DTO
   ↓
3. 返回比赛数据给前端
```

### 2.2 关于 Polymarket 市场匹配

**当前实现**：
- 不进行市场匹配（因为 Polymarket API 限制）
- 比赛数据中的 `polymarketMarketId` 字段为 `null`

**未来扩展**：
- 如果将来有办法获取 condition_ids，可以从 Polymarket API 实时获取市场信息
- 已保留 `NbaMarketNameParser` 工具类，可用于市场名称解析

### 2.2 核心组件

#### 1. NbaGameService
- **功能**：从 NBA Stats API 实时获取比赛数据
- **数据源**：NBA Stats API（官方数据源）
- **特点**：
  - 实时获取，不依赖数据库
  - 支持日期范围查询
  - 支持按状态过滤

#### 2. NbaMarketNameParser（保留，供将来使用）
- **功能**：解析 Polymarket 市场名称，提取球队和日期信息
- **支持格式**：
  - "Team1 vs Team2"
  - "Team1 @ Team2"
  - "Will Team1 beat Team2"
  - "Team1 win"
- **日期格式**：
  - "Dec 15, 2024"
  - "2024-12-15"
  - "12/15/2024"
- **说明**：当前未使用，保留以备将来扩展

## 三、实现细节

### 3.1 比赛数据获取

**获取流程**：
1. 根据日期范围，每天调用一次 NBA Stats API
2. 解析 Scoreboard 响应，提取比赛信息
3. 组合 GameHeader 和 LineScore 数据
4. 转换为 NbaGameDto 返回

**数据字段**：
- 比赛基本信息（球队、日期、时间）
- 实时比分和状态
- 比赛节次和剩余时间
- 球队统计信息

### 3.2 关于市场匹配（未来扩展）

**当前状态**：
- 不进行市场匹配
- `polymarketMarketId` 字段为 `null`

**未来扩展方案**：
如果将来需要匹配市场，可以考虑：
1. 从其他来源获取 condition_ids（如爬取、手动维护等）
2. 使用 Polymarket API 实时查询这些市场
3. 使用 `NbaMarketNameParser` 解析市场名称并匹配

### 3.3 数据返回

**NbaGameDto** 包含：
- 比赛基本信息（从 NBA Stats API）
- `polymarketMarketId`：当前为 `null`（未来可扩展）

## 四、使用方式

### 4.1 API 调用

```kotlin
// 获取比赛列表（自动匹配市场）
val request = NbaGameListRequest(
    startDate = "2024-12-15",
    endDate = "2024-12-22"
)
val result = nbaGameService.getNbaGames(request)
```

### 4.2 返回数据

```kotlin
data class NbaGameDto(
    val nbaGameId: String?,
    val homeTeam: String,
    val awayTeam: String,
    val gameDate: LocalDate,
    val gameStatus: String,
    val homeScore: Int,
    val awayScore: Int,
    val polymarketMarketId: String?  // 匹配的市场 ID
)
```

## 五、优势

1. **数据完整**：从 NBA Stats API 获取完整的比赛数据
2. **实时更新**：比赛数据实时获取，不依赖数据库
3. **简单高效**：直接调用 API，无需维护数据库
4. **官方数据源**：数据准确可靠

## 六、注意事项

1. **API 限制**：
   - NBA Stats API 需要设置正确的请求头
   - 建议控制请求频率（< 10 请求/秒）

2. **市场匹配**：
   - 当前不进行市场匹配
   - 如果需要匹配，需要解决 Polymarket API 的限制（需要知道 condition_ids）

3. **性能考虑**：
   - 日期范围查询会多次调用 API（每天一次）
   - 建议合理设置日期范围，避免查询过长的时间段

## 七、后续优化

1. **缓存机制**：
   - 缓存 API 响应，减少重复请求
   - 设置合理的缓存时间（如 30 秒）

2. **错误处理**：
   - 实现重试机制（指数退避）
   - 处理 API 临时不可用的情况

3. **市场匹配（可选）**：
   - 如果将来有办法获取 condition_ids，可以实现市场匹配
   - 使用 `NbaMarketNameParser` 进行市场名称解析

