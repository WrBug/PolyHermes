# NBA Stats API 验证检查清单

## 一、API 接口定义检查

### 1.1 接口路径
- ✅ **路径**: `/Scoreboard`
- ✅ **Base URL**: `https://stats.nba.com/stats/`
- ✅ **完整 URL**: `https://stats.nba.com/stats/Scoreboard`

### 1.2 请求参数
- ✅ **GameDate**: 日期格式 `YYYY-MM-DD`（如 `2024-12-15`）
- ✅ **LeagueID**: 联盟ID，默认 `"00"` (NBA)
- ✅ **DayOffset**: 日期偏移，默认 `0`

**注意**: NBA Stats API 的参数名称是**大小写敏感**的：
- ✅ `GameDate` (正确)
- ❌ `gameDate` (错误)
- ✅ `LeagueID` (正确)
- ❌ `leagueId` (错误)

### 1.3 请求头设置
- ✅ **User-Agent**: `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36`
- ✅ **Referer**: `https://www.nba.com/`
- ✅ **Accept**: `application/json`
- ✅ **Accept-Language**: `en-US,en;q=0.9`
- ✅ **Origin**: `https://www.nba.com`

## 二、响应格式检查

### 2.1 响应结构
NBA Stats API 返回的 JSON 结构：
```json
{
  "resultSets": [
    {
      "name": "GameHeader",
      "headers": ["GAME_DATE_EST", "GAME_SEQUENCE", "GAME_ID", ...],
      "rowSet": [[...], [...]]
    },
    {
      "name": "LineScore",
      "headers": ["GAME_DATE_EST", "GAME_SEQUENCE", "GAME_ID", "TEAM_ID", ...],
      "rowSet": [[...], [...]]
    }
  ]
}
```

### 2.2 ResultSet 名称
- ✅ **GameHeader**: 比赛基本信息
- ✅ **LineScore**: 比分信息（每支球队一行）

### 2.3 GameHeader 字段顺序
根据注释，字段顺序应该是：
```
[0] GAME_DATE_EST
[1] GAME_SEQUENCE
[2] GAME_ID
[3] GAME_STATUS_ID
[4] GAME_STATUS_TEXT
[5] GAMECODE
[6] HOME_TEAM_ID
[7] VISITOR_TEAM_ID
[8] SEASON
[9] LIVE_PERIOD
[10] LIVE_PC_TIME
[11] NATL_TV_BROADCASTER_ABBREV
[12] LIVE_PERIOD_TIME_BCAST
[13] WH_STATUS
```

### 2.4 LineScore 字段顺序
根据注释，字段顺序应该是：
```
[0] GAME_DATE_EST
[1] GAME_SEQUENCE
[2] GAME_ID
[3] TEAM_ID
[4] TEAM_ABBREVIATION
[5] TEAM_NAME
[6] PTS_QTR1
[7] PTS_QTR2
[8] PTS_QTR3
[9] PTS_QTR4
[10] PTS_OT1
[11] PTS_OT2
[12] PTS_OT3
[13] PTS_OT4
[14] PTS
[15] FG_PCT
[16] FT_PCT
[17] FG3_PCT
[18] AST
[19] REB
[20] TOV
```

## 三、代码实现检查

### 3.1 接口定义 ✅
```kotlin
@GET("Scoreboard")
suspend fun getScoreboard(
    @Query("GameDate") gameDate: String? = null,
    @Query("LeagueID") leagueId: String = "00",
    @Query("DayOffset") dayOffset: Int = 0
): Response<ScoreboardResponse>
```
- ✅ 参数名称大小写正确
- ✅ 参数类型正确

### 3.2 请求头设置 ✅
- ✅ 已设置所有必需的请求头
- ✅ User-Agent 格式正确

### 3.3 响应解析 ⚠️
**潜在问题**：
1. **ResultSet 查找方式**: 使用 `firstOrNull { it.name == "GameHeader" }` 可能不够准确
   - 建议：使用索引 `resultSets[0]` 或 `resultSets.getOrNull(0)`
   - 或者：先检查 `resultSets.size >= 2`

2. **字段索引**: 当前代码假设字段顺序固定
   - 建议：根据 `headers` 数组动态查找字段索引，而不是硬编码索引

3. **错误处理**: 当前有基本的错误处理，但可以更详细

## 四、建议的改进

### 4.1 使用 headers 动态查找字段
```kotlin
// 根据 headers 查找字段索引，而不是硬编码
val gameDateIndex = headers.indexOf("GAME_DATE_EST")
val gameIdIndex = headers.indexOf("GAME_ID")
// ...
```

### 4.2 增强错误处理
- 记录完整的响应内容（用于调试）
- 验证 headers 数量是否匹配
- 验证 rowSet 数据是否完整

### 4.3 添加响应验证
- 验证 resultSets 数量
- 验证每个 resultSet 的 name
- 验证 headers 和 rowSet 的对应关系

## 五、测试建议

1. **单元测试**: 测试 API 调用和响应解析
2. **集成测试**: 测试完整的获取流程
3. **错误场景测试**: 测试 API 失败、数据不完整等情况

## 六、已知问题

1. **API 限制**: NBA Stats API 可能需要特定的请求头，否则可能返回 403 或空数据
2. **数据格式**: 响应格式可能因日期而异（有比赛 vs 无比赛）
3. **时区问题**: `GAME_DATE_EST` 是 EST 时区，需要注意时区转换

