# Polymarket NBA 赛事列表获取方案

## 一、概述

本文档描述了如何从 Polymarket 获取 NBA 赛事列表，用于量化交易系统中的比赛筛选和市场匹配。

---

## 二、Polymarket API 接口分析

### 2.1 Gamma API 接口

**Base URL**: `https://gamma-api.polymarket.com`

**当前接口**：
- `/markets`: 根据 condition IDs 获取市场信息
- 不支持直接按分类筛选

**接口限制**：
- 只能通过 condition_ids 参数查询特定市场
- 不支持按分类、标签、关键词等筛选

### 2.2 市场数据结构

**MarketResponse 字段**：
- `id`: 市场 ID
- `question`: 市场名称（如 "Will the Lakers win?"）
- `conditionId`: Condition ID（16 进制）
- `slug`: 市场 slug（用于生成链接）
- `category`: 分类（如 "sports"）
- `active`: 是否活跃
- `closed`: 是否已关闭
- `archived`: 是否已归档
- `endDate`: 结束日期
- `startDate`: 开始日期
- `outcomes`: 结果选项（JSON 字符串）
- `volume`: 交易量
- `liquidity`: 流动性

---

## 三、获取 NBA 赛事列表的方案

### 3.1 方案一：通过 Events API 获取（推荐）

**接口信息**：
- **Base URL**: `https://gamma-api.polymarket.com`
- **接口路径**: `/events` 或 `/series`
- **说明**: Polymarket 可能提供 Events API 或 Series API，可以按分类获取事件列表

**实现方式**：
1. 调用 Events API，筛选分类为 "sports" 的事件
2. 进一步筛选包含 "NBA" 关键词的事件
3. 获取每个事件关联的市场列表

**优点**：
- 可以直接按分类筛选
- 数据结构更清晰（事件 -> 市场）
- 可以获取事件级别的信息

**缺点**：
- 需要确认 API 是否支持分类筛选
- 可能需要额外的 API 调用

### 3.2 方案二：通过搜索接口获取

**接口信息**：
- **Base URL**: `https://gamma-api.polymarket.com`
- **接口路径**: `/search` 或 `/markets/search`
- **说明**: 使用搜索接口，通过关键词 "NBA" 搜索市场

**实现方式**：
1. 调用搜索接口，搜索关键词 "NBA"
2. 过滤结果，确保分类为 "sports"
3. 进一步过滤，确保市场名称或描述中包含 NBA 相关信息

**优点**：
- 可以直接搜索 NBA 相关市场
- 实现简单

**缺点**：
- 可能遗漏一些市场（如果名称不包含 "NBA"）
- 搜索结果可能包含不相关的市场

### 3.3 方案三：获取所有市场后过滤（备选）

**实现方式**：
1. 调用 `/markets` 接口，不传 condition_ids（如果支持）
2. 或者定期爬取 Polymarket 网站，获取所有市场
3. 在本地过滤：分类 = "sports" 且包含 NBA 相关信息

**优点**：
- 不依赖特定 API 接口
- 可以获取完整的数据

**缺点**：
- 需要获取大量数据，效率低
- 需要定期更新
- 如果 API 不支持获取所有市场，需要爬取网站

### 3.4 方案四：通过 Subgraph API 获取（如果可用）

**接口信息**：
- **Base URL**: Polymarket Subgraph API
- **说明**: 使用 GraphQL 查询，可以灵活筛选

**实现方式**：
1. 使用 GraphQL 查询，筛选分类为 "sports" 的市场
2. 进一步筛选包含 "NBA" 的市场
3. 可以按时间、状态等条件筛选

**优点**：
- 查询灵活，可以精确筛选
- 可以获取关联数据（如事件、系列等）

**缺点**：
- 需要确认 Subgraph API 是否可用
- 需要学习 GraphQL 查询语法

---

## 四、推荐实现方案

### 4.1 混合方案（推荐）

**策略**：结合多种方式，确保数据完整性

**实现步骤**：

1. **主要方式：Events/Series API**
   - 优先使用 Events API 或 Series API
   - 按分类 "sports" 筛选
   - 按关键词 "NBA" 筛选事件

2. **补充方式：搜索接口**
   - 如果 Events API 不可用，使用搜索接口
   - 搜索关键词 "NBA"
   - 过滤分类为 "sports" 的结果

3. **数据缓存和更新**
   - 将获取的 NBA 赛事列表缓存到数据库
   - 定期更新（如每天更新一次）
   - 实时监控新市场（通过 WebSocket 或轮询）

### 4.2 数据存储设计

**NBA 赛事表 (nba_games)**：
```sql
CREATE TABLE nba_games (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    polymarket_market_id VARCHAR(100) UNIQUE NOT NULL COMMENT 'Polymarket 市场 ID',
    condition_id VARCHAR(100) UNIQUE NOT NULL COMMENT 'Condition ID',
    market_slug VARCHAR(255) COMMENT '市场 slug',
    market_question TEXT COMMENT '市场名称/问题',
    market_description TEXT COMMENT '市场描述',
    home_team VARCHAR(100) COMMENT '主队名称',
    away_team VARCHAR(100) COMMENT '客队名称',
    game_date DATE COMMENT '比赛日期',
    game_time BIGINT COMMENT '比赛时间（时间戳）',
    category VARCHAR(50) DEFAULT 'sports' COMMENT '分类',
    active BOOLEAN DEFAULT true COMMENT '是否活跃',
    closed BOOLEAN DEFAULT false COMMENT '是否已关闭',
    volume VARCHAR(50) COMMENT '交易量',
    liquidity VARCHAR(50) COMMENT '流动性',
    outcomes TEXT COMMENT '结果选项（JSON）',
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    INDEX idx_game_date (game_date),
    INDEX idx_active (active),
    INDEX idx_closed (closed),
    INDEX idx_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='NBA赛事表（Polymarket市场）';
```

### 4.3 数据获取服务实现

**服务接口设计**：

```kotlin
interface NbaMarketService {
    /**
     * 获取 NBA 赛事列表
     * @param date 比赛日期（可选，不传则获取所有）
     * @param activeOnly 是否只获取活跃的市场
     * @return NBA 赛事列表
     */
    suspend fun getNbaMarkets(
        date: LocalDate? = null,
        activeOnly: Boolean = true
    ): Result<List<NbaMarketDto>>
    
    /**
     * 同步 NBA 赛事列表（从 Polymarket API 获取并更新数据库）
     * @return 同步结果
     */
    suspend fun syncNbaMarkets(): Result<SyncResult>
    
    /**
     * 根据比赛信息匹配 Polymarket 市场
     * @param gameId NBA 比赛 ID
     * @param homeTeam 主队名称
     * @param awayTeam 客队名称
     * @param gameDate 比赛日期
     * @return 匹配的市场列表
     */
    suspend fun matchMarketsByGame(
        gameId: String,
        homeTeam: String,
        awayTeam: String,
        gameDate: LocalDate
    ): Result<List<NbaMarketDto>>
}
```

**实现逻辑**：

1. **从 Polymarket 获取市场列表**
   - 调用 Events API 或搜索接口
   - 筛选分类为 "sports" 且包含 "NBA" 的市场
   - 解析市场名称，提取比赛信息（主队、客队、日期等）

2. **数据解析和匹配**
   - 解析市场名称（question），提取球队名称和比赛日期
   - 匹配 NBA 比赛数据（通过球队名称和日期）
   - 建立 NBA 比赛和 Polymarket 市场的关联关系

3. **数据存储**
   - 保存到数据库
   - 建立索引，提高查询效率
   - 定期更新，确保数据最新

---

## 五、市场名称解析规则

### 5.1 市场名称格式

Polymarket 的 NBA 市场名称通常包含以下信息：
- 球队名称（主队 vs 客队）
- 比赛日期或时间
- 比赛结果预测（如 "Will the Lakers win?"）

**常见格式示例**：
- "Will the Lakers beat the Warriors on Dec 15?"
- "Lakers vs Warriors - Dec 15, 2024"
- "NBA: Lakers @ Warriors - Dec 15"

### 5.2 解析算法

**解析步骤**：
1. 提取球队名称：使用正则表达式或关键词匹配
2. 提取比赛日期：解析日期格式（如 "Dec 15, 2024"）
3. 提取比赛结果：判断是 "win" 还是 "lose"
4. 匹配 NBA 比赛：通过球队名称和日期匹配

**球队名称映射**：
- 建立球队名称映射表（Polymarket 名称 -> 标准名称）
- 处理缩写（如 "LAL" -> "Lakers"）
- 处理别名（如 "Lakers" -> "Los Angeles Lakers"）

---

## 六、数据同步策略

### 6.1 同步频率

**定期同步**：
- 每天同步一次：获取所有 NBA 相关市场
- 每小时增量同步：检查新市场
- 实时监控：通过 WebSocket 或轮询监控新市场

### 6.2 同步流程

1. **全量同步**（每天一次）
   - 从 Polymarket API 获取所有 sports 分类的市场
   - 筛选包含 NBA 相关信息的市场
   - 更新数据库

2. **增量同步**（每小时一次）
   - 获取最近 24 小时的新市场
   - 只更新新增和变化的市场
   - 标记已关闭的市场

3. **实时监控**（可选）
   - 通过 WebSocket 订阅新市场
   - 或每 5 分钟轮询一次新市场
   - 实时更新数据库

### 6.3 数据去重和更新

**去重策略**：
- 使用 condition_id 作为唯一标识
- 如果市场已存在，更新信息
- 如果市场不存在，插入新记录

**更新策略**：
- 更新活跃状态（active、closed）
- 更新交易量和流动性
- 更新结束日期（如果变化）

---

## 七、API 接口扩展

### 7.1 扩展 Gamma API 接口

**添加新的接口方法**：

```kotlin
interface PolymarketGammaApi {
    // ... 现有方法
    
    /**
     * 搜索市场（如果 API 支持）
     * @param query 搜索关键词
     * @param category 分类筛选
     * @param limit 返回数量限制
     * @param offset 偏移量
     * @return 市场列表
     */
    @GET("/markets/search")
    suspend fun searchMarkets(
        @Query("query") query: String? = null,
        @Query("category") category: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): Response<List<MarketResponse>>
    
    /**
     * 获取事件列表（如果 API 支持）
     * @param category 分类筛选
     * @param limit 返回数量限制
     * @param offset 偏移量
     * @return 事件列表
     */
    @GET("/events")
    suspend fun listEvents(
        @Query("category") category: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): Response<List<EventResponse>>
    
    /**
     * 获取系列列表（如果 API 支持）
     * @param category 分类筛选
     * @param limit 返回数量限制
     * @param offset 偏移量
     * @return 系列列表
     */
    @GET("/series")
    suspend fun listSeries(
        @Query("category") category: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): Response<List<SeriesResponse>>
}
```

### 7.2 后端服务实现

**创建 NBA 市场服务**：

```kotlin
@Service
class NbaMarketService(
    private val gammaApi: PolymarketGammaApi,
    private val nbaGameRepository: NbaGameRepository
) {
    /**
     * 获取 NBA 赛事列表
     */
    suspend fun getNbaMarkets(
        date: LocalDate? = null,
        activeOnly: Boolean = true
    ): Result<List<NbaMarketDto>> {
        // 实现逻辑
    }
    
    /**
     * 同步 NBA 赛事列表
     */
    suspend fun syncNbaMarkets(): Result<SyncResult> {
        // 实现逻辑
    }
    
    /**
     * 匹配市场
     */
    suspend fun matchMarketsByGame(
        gameId: String,
        homeTeam: String,
        awayTeam: String,
        gameDate: LocalDate
    ): Result<List<NbaMarketDto>> {
        // 实现逻辑
    }
}
```

### 7.3 前端 API 接口

**添加市场列表接口**：

```kotlin
@RestController
@RequestMapping("/api/nba/markets")
class NbaMarketController(
    private val nbaMarketService: NbaMarketService
) {
    /**
     * 获取 NBA 赛事列表
     */
    @PostMapping("/list")
    fun getNbaMarkets(@RequestBody request: NbaMarketListRequest): ResponseEntity<ApiResponse<List<NbaMarketDto>>> {
        // 实现逻辑
    }
    
    /**
     * 同步 NBA 赛事列表
     */
    @PostMapping("/sync")
    fun syncNbaMarkets(): ResponseEntity<ApiResponse<SyncResult>> {
        // 实现逻辑
    }
    
    /**
     * 根据比赛匹配市场
     */
    @PostMapping("/match")
    fun matchMarkets(@RequestBody request: MatchMarketRequest): ResponseEntity<ApiResponse<List<NbaMarketDto>>> {
        // 实现逻辑
    }
}
```

---

## 八、实施步骤

### 8.1 第一阶段：API 调研和测试（1 周）

**任务清单**：
- [ ] 调研 Polymarket API 文档，确认可用的接口
- [ ] 测试 Events API 或 Series API（如果存在）
- [ ] 测试搜索接口（如果存在）
- [ ] 确认最佳的数据获取方式

### 8.2 第二阶段：数据获取实现（1-2 周）

**任务清单**：
- [ ] 扩展 PolymarketGammaApi 接口
- [ ] 实现 NBA 市场数据获取服务
- [ ] 实现市场名称解析算法
- [ ] 实现 NBA 比赛和市场匹配逻辑

### 8.3 第三阶段：数据存储和同步（1 周）

**任务清单**：
- [ ] 创建数据库表（nba_games）
- [ ] 实现数据存储逻辑
- [ ] 实现数据同步服务
- [ ] 实现定时同步任务

### 8.4 第四阶段：API 接口开发（1 周）

**任务清单**：
- [ ] 创建后端 API 接口
- [ ] 实现前端 API 调用
- [ ] 实现前端页面展示
- [ ] 测试和优化

---

## 九、注意事项

### 9.1 API 限制

- **请求频率**：注意 API 的请求频率限制
- **数据量**：NBA 相关市场可能很多，需要分页获取
- **数据更新**：市场状态可能频繁变化，需要定期更新

### 9.2 数据匹配

- **球队名称**：Polymarket 的球队名称可能与标准名称不一致，需要建立映射表
- **比赛日期**：需要处理时区问题
- **市场状态**：需要实时更新市场的活跃状态

### 9.3 错误处理

- **API 失败**：实现重试机制
- **数据解析失败**：记录错误日志，跳过无法解析的市场
- **匹配失败**：如果无法匹配 NBA 比赛，仍然保存市场数据，后续可以手动匹配

---

## 十、实际实现方案（基于当前 API 限制）

### 10.1 当前 API 限制分析

**现状**：
- Polymarket Gamma API 的 `/markets` 接口只支持通过 `condition_ids` 查询
- 不支持按分类、关键词、日期等筛选
- 没有提供搜索接口或 Events API

**解决方案**：
由于 API 限制，需要采用以下策略：
1. **建立 NBA 市场 condition_ids 数据库**：手动或通过其他方式收集 NBA 市场的 condition_ids
2. **定期更新 condition_ids 列表**：通过爬取或监控获取新的 NBA 市场
3. **批量查询市场信息**：使用收集到的 condition_ids 批量查询市场详情

### 10.2 实现步骤

#### 步骤 1：收集 NBA 市场 condition_ids

**方式一：手动收集**
- 访问 Polymarket 网站，搜索 "NBA" 相关市场
- 从市场 URL 中提取 condition_id（如 `https://polymarket.com/event/xxx`）
- 保存到数据库或配置文件

**方式二：通过爬取获取**
- 爬取 Polymarket 网站的市场列表页面
- 解析 HTML，提取 condition_ids
- 筛选分类为 "sports" 且包含 "NBA" 的市场

**方式三：通过监控获取**
- 监控 Polymarket 的新市场创建
- 通过 WebSocket 或轮询获取新市场
- 筛选 NBA 相关市场

#### 步骤 2：批量查询市场信息

**实现代码**：

```kotlin
@Service
class NbaMarketService(
    private val gammaApi: PolymarketGammaApi,
    private val nbaGameRepository: NbaGameRepository
) {
    private val logger = LoggerFactory.getLogger(NbaMarketService::class.java)
    
    /**
     * 批量获取 NBA 市场信息
     * @param conditionIds condition ID 列表
     * @return 市场列表
     */
    suspend fun batchGetMarkets(conditionIds: List<String>): Result<List<MarketResponse>> {
        return try {
            // Polymarket API 可能对批量查询有限制，需要分批查询
            val batchSize = 50  // 每批查询 50 个
            val allMarkets = mutableListOf<MarketResponse>()
            
            conditionIds.chunked(batchSize).forEach { batch ->
                val response = gammaApi.listMarkets(
                    conditionIds = batch,
                    includeTag = true
                )
                
                if (response.isSuccessful && response.body() != null) {
                    val markets = response.body()!!
                    // 过滤 NBA 相关市场
                    val nbaMarkets = markets.filter { market ->
                        isNbaMarket(market)
                    }
                    allMarkets.addAll(nbaMarkets)
                } else {
                    logger.warn("批量查询市场失败: ${response.code()} ${response.message()}")
                }
                
                // 避免请求过快，添加延迟
                kotlinx.coroutines.delay(100)
            }
            
            Result.success(allMarkets)
        } catch (e: Exception) {
            logger.error("批量获取市场信息异常: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 判断是否为 NBA 市场
     */
    private fun isNbaMarket(market: MarketResponse): Boolean {
        // 检查分类
        if (market.category?.lowercase() != "sports") {
            return false
        }
        
        // 检查市场名称或描述中是否包含 NBA 相关关键词
        val question = market.question?.lowercase() ?: ""
        val description = market.description?.lowercase() ?: ""
        
        val nbaKeywords = listOf(
            "nba", "basketball", "lakers", "warriors", "celtics", "heat",
            "bulls", "knicks", "nets", "76ers", "bucks", "suns", "nuggets"
        )
        
        return nbaKeywords.any { keyword ->
            question.contains(keyword) || description.contains(keyword)
        }
    }
    
    /**
     * 同步 NBA 市场列表
     */
    suspend fun syncNbaMarkets(): Result<SyncResult> {
        return try {
            // 1. 从数据库获取所有已知的 condition_ids
            val knownConditionIds = nbaGameRepository.findAllConditionIds()
            
            // 2. 批量查询市场信息
            val marketsResult = batchGetMarkets(knownConditionIds)
            
            if (!marketsResult.isSuccess) {
                return Result.failure(marketsResult.exceptionOrNull() ?: Exception("获取市场失败"))
            }
            
            val markets = marketsResult.getOrNull() ?: emptyList()
            
            // 3. 解析市场信息，提取比赛数据
            val nbaGames = markets.mapNotNull { market ->
                parseMarketToNbaGame(market)
            }
            
            // 4. 保存到数据库
            nbaGameRepository.saveAll(nbaGames)
            
            Result.success(
                SyncResult(
                    total = markets.size,
                    success = nbaGames.size,
                    failed = markets.size - nbaGames.size
                )
            )
        } catch (e: Exception) {
            logger.error("同步 NBA 市场列表异常: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 解析市场信息为 NBA 比赛数据
     */
    private fun parseMarketToNbaGame(market: MarketResponse): NbaGame? {
        return try {
            // 解析市场名称，提取球队名称和日期
            val (homeTeam, awayTeam, gameDate) = parseMarketQuestion(market.question ?: "")
                ?: return null
            
            NbaGame(
                polymarketMarketId = market.id,
                conditionId = market.conditionId ?: return null,
                marketSlug = market.slug,
                marketQuestion = market.question,
                marketDescription = market.description,
                homeTeam = homeTeam,
                awayTeam = awayTeam,
                gameDate = gameDate,
                gameTime = parseGameTime(market.startDate),
                category = market.category ?: "sports",
                active = market.active ?: true,
                closed = market.closed ?: false,
                volume = market.volume,
                liquidity = market.liquidity,
                outcomes = market.outcomes,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            logger.warn("解析市场信息失败: ${market.question}, error: ${e.message}")
            null
        }
    }
    
    /**
     * 解析市场名称，提取球队名称和日期
     * 示例: "Will the Lakers beat the Warriors on Dec 15?" -> ("Lakers", "Warriors", 2024-12-15)
     */
    private fun parseMarketQuestion(question: String): Triple<String, String, LocalDate>? {
        // 实现解析逻辑
        // 使用正则表达式或 NLP 方法提取信息
        // 这里简化处理，实际需要更复杂的解析逻辑
        return null
    }
}
```

#### 步骤 3：定时同步任务

**实现代码**：

```kotlin
@Component
class NbaMarketSyncScheduler(
    private val nbaMarketService: NbaMarketService
) {
    private val logger = LoggerFactory.getLogger(NbaMarketSyncScheduler::class.java)
    
    /**
     * 每天凌晨 2 点同步一次
     */
    @Scheduled(cron = "0 0 2 * * ?")
    fun syncNbaMarketsDaily() {
        logger.info("开始同步 NBA 市场列表...")
        runBlocking {
            val result = nbaMarketService.syncNbaMarkets()
            result.fold(
                onSuccess = { syncResult ->
                    logger.info("同步 NBA 市场列表成功: total=${syncResult.total}, success=${syncResult.success}, failed=${syncResult.failed}")
                },
                onFailure = { e ->
                    logger.error("同步 NBA 市场列表失败: ${e.message}", e)
                }
            )
        }
    }
    
    /**
     * 每小时增量同步一次
     */
    @Scheduled(cron = "0 0 * * * ?")
    fun syncNbaMarketsHourly() {
        logger.info("开始增量同步 NBA 市场列表...")
        runBlocking {
            // 只同步最近 24 小时的新市场
            val result = nbaMarketService.syncNbaMarketsIncremental()
            result.fold(
                onSuccess = { syncResult ->
                    logger.info("增量同步 NBA 市场列表成功: total=${syncResult.total}, success=${syncResult.success}")
                },
                onFailure = { e ->
                    logger.error("增量同步 NBA 市场列表失败: ${e.message}", e)
                }
            )
        }
    }
}
```

### 10.3 数据模型定义

**实体类**：

```kotlin
@Entity
@Table(name = "nba_games")
data class NbaGame(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "polymarket_market_id", unique = true, nullable = false, length = 100)
    val polymarketMarketId: String,
    
    @Column(name = "condition_id", unique = true, nullable = false, length = 100)
    val conditionId: String,
    
    @Column(name = "market_slug", length = 255)
    val marketSlug: String? = null,
    
    @Column(name = "market_question", columnDefinition = "TEXT")
    val marketQuestion: String? = null,
    
    @Column(name = "market_description", columnDefinition = "TEXT")
    val marketDescription: String? = null,
    
    @Column(name = "home_team", length = 100)
    val homeTeam: String? = null,
    
    @Column(name = "away_team", length = 100)
    val awayTeam: String? = null,
    
    @Column(name = "game_date")
    val gameDate: LocalDate? = null,
    
    @Column(name = "game_time")
    val gameTime: Long? = null,
    
    @Column(name = "category", length = 50)
    val category: String = "sports",
    
    @Column(name = "active")
    val active: Boolean = true,
    
    @Column(name = "closed")
    val closed: Boolean = false,
    
    @Column(name = "volume", length = 50)
    val volume: String? = null,
    
    @Column(name = "liquidity", length = 50)
    val liquidity: String? = null,
    
    @Column(name = "outcomes", columnDefinition = "TEXT")
    val outcomes: String? = null,
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)
```

### 10.4 API 接口实现

**Controller**：

```kotlin
@RestController
@RequestMapping("/api/nba/markets")
class NbaMarketController(
    private val nbaMarketService: NbaMarketService,
    private val messageSource: MessageSource
) {
    private val logger = LoggerFactory.getLogger(NbaMarketController::class.java)
    
    /**
     * 获取 NBA 赛事列表
     */
    @PostMapping("/list")
    fun getNbaMarkets(@RequestBody request: NbaMarketListRequest): ResponseEntity<ApiResponse<List<NbaMarketDto>>> {
        return try {
            val result = runBlocking {
                nbaMarketService.getNbaMarkets(
                    date = request.date,
                    activeOnly = request.activeOnly ?: true
                )
            }
            
            result.fold(
                onSuccess = { markets ->
                    ResponseEntity.ok(ApiResponse.success(markets))
                },
                onFailure = { e ->
                    logger.error("获取 NBA 赛事列表失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("获取 NBA 赛事列表异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }
    
    /**
     * 同步 NBA 赛事列表
     */
    @PostMapping("/sync")
    fun syncNbaMarkets(): ResponseEntity<ApiResponse<SyncResult>> {
        return try {
            val result = runBlocking {
                nbaMarketService.syncNbaMarkets()
            }
            
            result.fold(
                onSuccess = { syncResult ->
                    ResponseEntity.ok(ApiResponse.success(syncResult))
                },
                onFailure = { e ->
                    logger.error("同步 NBA 赛事列表失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("同步 NBA 赛事列表异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }
}
```

---

## 十一、参考资源

- [Polymarket API 文档](https://docs.polymarket.com/)
- [Polymarket Gamma API 文档](https://docs.polymarket.com/api-reference/markets/list-markets)
- [NBA 球队名称标准](https://www.nba.com/teams)
- [Polymarket 网站](https://polymarket.com/)（用于手动收集 condition_ids）

---

**文档结束**

