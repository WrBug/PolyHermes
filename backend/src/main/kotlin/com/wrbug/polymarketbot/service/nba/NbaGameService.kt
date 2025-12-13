package com.wrbug.polymarketbot.service.nba

import com.wrbug.polymarketbot.api.PolymarketGammaApi
import com.wrbug.polymarketbot.dto.NbaGameDto
import com.wrbug.polymarketbot.dto.NbaGameListRequest
import com.wrbug.polymarketbot.dto.NbaGameListResponse
import com.wrbug.polymarketbot.entity.NbaGame
import com.wrbug.polymarketbot.enums.SportsTagId
import com.wrbug.polymarketbot.repository.NbaGameRepository
import com.wrbug.polymarketbot.util.RetrofitFactory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * NBA 比赛服务
 * 从数据库和 Polymarket API 获取比赛数据
 * 优先从数据库获取，如果数据不足则增量拉取 API 数据
 */
@Service
class NbaGameService(
    private val retrofitFactory: RetrofitFactory,
    private val nbaGameRepository: NbaGameRepository
) {
    private val logger = LoggerFactory.getLogger(NbaGameService::class.java)


    /**
     * 获取 NBA 比赛列表
     * 优先从数据库获取，如果数据不足则增量拉取 API 数据
     * 前端传递时间戳，后端转换为西8区时间用于过滤
     */
    suspend fun getNbaGames(request: NbaGameListRequest): Result<NbaGameListResponse> {
        return try {
            // 将时间戳转换为西8区（PST/PDT）的日期范围
            val pstZone = ZoneId.of("America/Los_Angeles")

            val startTimestamp = request.startTimestamp ?: ZonedDateTime.now(pstZone).toInstant().toEpochMilli()
            val endTimestamp = request.endTimestamp ?: ZonedDateTime.now(pstZone).plusDays(7).toInstant().toEpochMilli()

            val startDate = Instant.ofEpochMilli(startTimestamp).atZone(pstZone).toLocalDate()
            val endDate = Instant.ofEpochMilli(endTimestamp).atZone(pstZone).toLocalDate()

            // 1. 先从数据库获取数据
            val dbGames = nbaGameRepository.findByGameDateBetween(startDate, endDate)
            logger.info("从数据库获取到 ${dbGames.size} 个比赛（日期范围：$startDate 到 $endDate）")

            // 2. 检查是否需要增量拉取
            val needFetch = shouldFetchFromApi(dbGames)

            if (needFetch) {
                logger.info("数据库数据不足，开始增量拉取 API 数据")

                // 3. 获取数据库最新的 createdAt，用于增量拉取
                val latestGame = nbaGameRepository.findFirstByOrderByCreatedAtDesc()
                val incrementalStartDateMin = latestGame?.createdAt?.let {
                    // 将数据库的 createdAt（时间戳）转换为 UTC ISO 8601 格式
                    Instant.ofEpochMilli(it)
                        .atZone(java.time.ZoneOffset.UTC)
                        .format(DateTimeFormatter.ISO_INSTANT)
                } ?: run {
                    // 如果没有数据库数据，使用一周前的时间
                    Instant.now()
                        .minusSeconds(7 * 24 * 60 * 60)
                        .atZone(java.time.ZoneOffset.UTC)
                        .format(DateTimeFormatter.ISO_INSTANT)
                }

                logger.info("使用增量拉取起始时间: $incrementalStartDateMin")

                // 4. 增量拉取 API 数据
                val apiGames = fetchGamesFromApi(startDate, endDate, incrementalStartDateMin)

                // 5. 保存新数据到数据库
                if (apiGames.isNotEmpty()) {
                    saveGamesToDatabase(apiGames)
                }

                // 6. 合并数据库数据和 API 数据
                val allGames = (dbGames + apiGames.map { dtoToEntity(it) }).distinctBy {
                    "${it.homeTeam}_${it.awayTeam}_${it.gameDate}"
                }

                // 转换为 DTO
                val gameDtos = allGames.map { entityToDto(it) }

                // 根据状态过滤
                val filteredGames = if (request.gameStatus != null) {
                    gameDtos.filter { it.gameStatus == request.gameStatus }
                } else {
                    gameDtos
                }

                Result.success(
                    NbaGameListResponse(
                        list = filteredGames,
                        total = filteredGames.size.toLong()
                    )
                )
            } else {
                // 数据库数据充足，直接返回
                val gameDtos = dbGames.map { entityToDto(it) }

                // 根据状态过滤
                val filteredGames = if (request.gameStatus != null) {
                    gameDtos.filter { it.gameStatus == request.gameStatus }
                } else {
                    gameDtos
                }

                Result.success(
                    NbaGameListResponse(
                        list = filteredGames,
                        total = filteredGames.size.toLong()
                    )
                )
            }
        } catch (e: Exception) {
            logger.error("获取 NBA 比赛列表失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 判断是否需要从 API 拉取数据
     * 逻辑：
     * 1. 如果数据库没有数据，需要拉取
     * 2. 如果数据库最新数据的 gameTime 在未来 3 天内（0-3 天），不需要拉取
     * 3. 如果数据库最新数据的 gameTime 超过 3 天（>3 天），不需要拉取（数据太远）
     * 4. 如果数据库最新数据的 gameTime 已经过去（<0），需要拉取（数据过期）
     */
    private fun shouldFetchFromApi(dbGames: List<NbaGame>): Boolean {
        if (dbGames.isEmpty()) {
            logger.info("数据库没有数据，需要从 API 拉取")
            return true
        }

        // 检查最新数据的 gameTime（未来最远的比赛）
        val latestGame = dbGames.maxByOrNull { it.gameTime ?: 0L }
        if (latestGame?.gameTime == null) {
            logger.info("数据库最新数据没有 gameTime，需要从 API 拉取")
            return true
        }

        // 计算最新数据的 gameTime 距离现在的时间（以天为单位）
        val now = Instant.now().toEpochMilli()
        val gameTime = latestGame.gameTime
        val daysDiff = (gameTime - now) / (24 * 60 * 60 * 1000)

        // 如果数据已经过去（daysDiff < 0），需要拉取
        if (daysDiff < 0) {
            logger.info("数据库最新数据的 gameTime 已经过去（${daysDiff} 天前），需要从 API 拉取")
            return true
        }

        // 如果数据在未来 3 天内（0 <= daysDiff <= 3），不需要拉取
        if (daysDiff >= 0 && daysDiff <= 3) {
            logger.info("数据库数据充足（最新数据 ${daysDiff} 天后，在未来 3 天内），无需从 API 拉取")
            return false
        }

        // 如果数据超过 3 天（daysDiff > 3），不需要拉取（数据太远）
        logger.info("数据库最新数据的 gameTime 超过 3 天（${daysDiff} 天后），数据太远，不需要从 API 拉取")
        return false
    }

    /**
     * 从 API 拉取比赛数据
     */
    private suspend fun fetchGamesFromApi(
        startDate: LocalDate,
        endDate: LocalDate,
        startDateMin: String
    ): List<NbaGameDto> {
        // 从 Polymarket API 获取 NBA 市场（分页拉取）
        val gammaApi = retrofitFactory.createGammaApi()
        val nbaTagId = SportsTagId.NBA.tagId

        // 计算未来3天的时间点（UTC）
        val threeDaysLater = Instant.now()
            .plusSeconds(3 * 24 * 60 * 60)  // 加上3天（秒数）

        val allMarkets = mutableListOf<com.wrbug.polymarketbot.api.MarketResponse>()
        var hasMore = true
        var pageCount = 0
        var currentStartDateMin = startDateMin

        while (hasMore) {
            pageCount++
            logger.debug("分页拉取第 $pageCount 页，start_date_min: $currentStartDateMin")

            val response = gammaApi.listMarkets(
                conditionIds = null,
                includeTag = true,
                tags = listOf(nbaTagId),
                active = true,  // 只获取活跃的市场
                closed = false,
                archived = false,
                limit = 500,  // 使用 500 作为 limit
                startDateMin = currentStartDateMin,
                sportsMarketTypes = listOf("moneyline")  // 直接通过 API 筛选 moneyline 类型
            )

            if (!response.isSuccessful || response.body() == null) {
                logger.error("获取 NBA 市场失败: ${response.code()} ${response.message()}")
                break
            }

            val markets = response.body()!!
            logger.info("第 $pageCount 页获取到 ${markets.size} 个市场")

            if (markets.isEmpty()) {
                // 没有更多数据了
                hasMore = false
                break
            }

            // 先记录最后一项的 createdAt（用于下一次分页）
            val lastMarket = markets.last()
            val lastCreatedAt = lastMarket.createdAt

            if (lastCreatedAt == null) {
                // 如果最后一个元素没有 createdAt，停止分页
                hasMore = false
                logger.warn("数组最后一个元素缺少 createdAt，停止分页")
                break
            }

            // 移除非 NBA 项（根据 resolutionSource 判断）
            val nbaMarkets = markets.filter { market ->
                !market.resolutionSource.isNullOrBlank() &&
                        market.resolutionSource!!.lowercase().contains("nba")
            }
            logger.info("第 $pageCount 页过滤后剩余 ${nbaMarkets.size} 个 NBA 市场")

            // 添加到总列表（只添加 NBA 市场）
            allMarkets.addAll(nbaMarkets)

            // 从后往前遍历，找到第一个有 gameStartTime 字段的数据（在 NBA 市场中查找）
            var foundGameStartTime: String? = null
            for (i in nbaMarkets.size - 1 downTo 0) {
                val market = nbaMarkets[i]
                if (!market.gameStartTime.isNullOrBlank()) {
                    foundGameStartTime = market.gameStartTime
                    logger.debug("从后往前找到第 ${i + 1} 个有 gameStartTime 的 NBA 市场: $foundGameStartTime")
                    break
                }
            }

            if (foundGameStartTime == null) {
                // 如果整页都没有 gameStartTime，使用 createdAt 继续分页
                currentStartDateMin = lastCreatedAt
                logger.debug("本页没有找到 gameStartTime，使用最后一个元素的 createdAt 继续分页")
                continue
            }

            // 解析 gameStartTime（格式：2025-12-13 00:00:00+00）
            val gameStartDate = try {
                // 尝试解析格式 "2025-12-13 00:00:00+00"
                val dateTimeStr = foundGameStartTime.replace(" ", "T")
                // 如果时区是 +00，转换为 Z
                val normalizedStr = if (dateTimeStr.endsWith("+00")) {
                    dateTimeStr.replace("+00", "Z")
                } else if (dateTimeStr.endsWith("-00")) {
                    dateTimeStr.replace("-00", "Z")
                } else {
                    dateTimeStr
                }
                val instant = Instant.parse(normalizedStr)
                // 转换为日期（以天为单位，不考虑时间）
                instant.atZone(java.time.ZoneOffset.UTC).toLocalDate()
            } catch (e: Exception) {
                logger.warn("解析 gameStartTime 失败: $foundGameStartTime, error: ${e.message}")
                null
            }

            if (gameStartDate == null) {
                // 无法解析 gameStartTime，使用 createdAt 继续分页
                currentStartDateMin = lastCreatedAt
                logger.debug("无法解析 gameStartTime，使用最后一个元素的 createdAt 继续分页")
                continue
            }

            // 计算未来 3 天的日期（以天为单位，不考虑时间）
            val threeDaysLaterDate = Instant.now()
                .plusSeconds(3 * 24 * 60 * 60)  // 加上3天（秒数）
                .atZone(java.time.ZoneOffset.UTC)
                .toLocalDate()

            // 判断 gameStartDate 是否在未来 3 天以内（包括第 3 天）
            val daysBetween = java.time.temporal.ChronoUnit.DAYS.between(
                Instant.now().atZone(java.time.ZoneOffset.UTC).toLocalDate(),
                gameStartDate
            )

            if (daysBetween <= 3 && daysBetween >= 0) {
                // 如果在 3 天内（包括第 3 天），使用数组最后一个元素的 createdAt 继续分页
                currentStartDateMin = lastCreatedAt
                logger.info("找到的 gameStartTime ($foundGameStartTime, 日期: $gameStartDate) 在未来 ${daysBetween} 天内，继续分页")
            } else {
                // 如果不在 3 天内，停止分页
                hasMore = false
                logger.info("找到的 gameStartTime ($foundGameStartTime, 日期: $gameStartDate) 不在未来 3 天内（相差 ${daysBetween} 天），停止分页")
            }
        }

        logger.info("分页拉取完成，共获取 ${allMarkets.size} 个 NBA moneyline 市场（${pageCount} 页）")

        // 注意：allMarkets 已经通过 API 的 sports_market_types 参数过滤了 moneyline 类型
        // 并且已经过滤了非 NBA 项（根据 resolutionSource），这里直接使用即可

        // 将市场转换为比赛数据
        val games = allMarkets.mapNotNull { market ->
            convertMarketToGame(market, startDate, endDate)
        }

        // 去重：相同的主队、客队和日期只保留一个
        val uniqueGames = games.groupBy { "${it.homeTeam}_${it.awayTeam}_${it.gameDate}" }
            .map { it.value.first() }

        return uniqueGames
    }

    /**
     * 保存比赛数据到数据库
     */
    @Transactional
    private fun saveGamesToDatabase(games: List<NbaGameDto>) {
        if (games.isEmpty()) {
            return
        }

        var savedCount = 0
        var updatedCount = 0

        games.forEach { dto ->
            try {
                // 尝试根据 nbaGameId 或 polymarketMarketId 查找现有记录
                val existing = dto.nbaGameId?.let {
                    nbaGameRepository.findByNbaGameId(it)
                } ?: dto.polymarketMarketId?.let {
                    nbaGameRepository.findByPolymarketMarketId(it)
                }

                if (existing != null) {
                    // 更新现有记录（data class 的 copy 方法）
                    val updated = NbaGame(
                        id = existing.id,
                        nbaGameId = existing.nbaGameId,
                        homeTeam = dto.homeTeam,
                        awayTeam = dto.awayTeam,
                        gameDate = dto.gameDate,
                        gameTime = dto.gameTime,
                        gameStatus = dto.gameStatus,
                        homeScore = dto.homeScore,
                        awayScore = dto.awayScore,
                        period = dto.period,
                        timeRemaining = dto.timeRemaining,
                        polymarketMarketId = dto.polymarketMarketId,
                        createdAt = existing.createdAt,
                        updatedAt = System.currentTimeMillis()
                    )
                    nbaGameRepository.save(updated)
                    updatedCount++
                } else {
                    // 创建新记录
                    val entity = dtoToEntity(dto)
                    nbaGameRepository.save(entity)
                    savedCount++
                }
            } catch (e: Exception) {
                logger.error("保存比赛数据失败: ${dto.nbaGameId}, error: ${e.message}", e)
            }
        }

        logger.info("保存比赛数据完成：新增 $savedCount 条，更新 $updatedCount 条")
    }

    /**
     * DTO 转实体
     */
    private fun dtoToEntity(dto: NbaGameDto): NbaGame {
        return NbaGame(
            id = null,
            nbaGameId = dto.nbaGameId,
            homeTeam = dto.homeTeam,
            awayTeam = dto.awayTeam,
            gameDate = dto.gameDate,
            gameTime = dto.gameTime,
            gameStatus = dto.gameStatus,
            homeScore = dto.homeScore,
            awayScore = dto.awayScore,
            period = dto.period,
            timeRemaining = dto.timeRemaining,
            polymarketMarketId = dto.polymarketMarketId,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * 实体转 DTO
     */
    private fun entityToDto(entity: NbaGame): NbaGameDto {
        return NbaGameDto(
            id = entity.id,
            nbaGameId = entity.nbaGameId,
            homeTeam = entity.homeTeam,
            awayTeam = entity.awayTeam,
            gameDate = entity.gameDate,
            gameTime = entity.gameTime,
            gameStatus = entity.gameStatus,
            homeScore = entity.homeScore,
            awayScore = entity.awayScore,
            period = entity.period,
            timeRemaining = entity.timeRemaining,
            polymarketMarketId = entity.polymarketMarketId
        )
    }

    /**
     * 获取 7 天内的所有球队（去重）
     */
    suspend fun getTeamsInNext7Days(): Result<List<String>> {
        return try {
            // 使用当前西8区时间计算7天范围
            val pstZone = ZoneId.of("America/Los_Angeles")
            val now = ZonedDateTime.now(pstZone)
            val startTimestamp = now.toInstant().toEpochMilli()
            val endTimestamp = now.plusDays(7).toInstant().toEpochMilli()

            val gamesResult = getNbaGames(
                NbaGameListRequest(
                    startTimestamp = startTimestamp,
                    endTimestamp = endTimestamp
                )
            )

            gamesResult.fold(
                onSuccess = { response ->
                    val teams = mutableSetOf<String>()
                    response.list.forEach { game ->
                        teams.add(game.homeTeam)
                        teams.add(game.awayTeam)
                    }
                    Result.success(teams.sorted())
                },
                onFailure = { exception -> Result.failure(exception) }
            )
        } catch (e: Exception) {
            logger.error("获取球队列表失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 将 Polymarket 市场转换为比赛数据
     */
    private fun convertMarketToGame(
        market: com.wrbug.polymarketbot.api.MarketResponse,
        startDate: LocalDate,
        endDate: LocalDate
    ): NbaGameDto? {
        if (market.question.isNullOrBlank()) {
            return null
        }

        // 解析市场名称，提取球队和日期信息
        val parsed = NbaMarketNameParser.parse(market.question)

        if (parsed.homeTeam == null || parsed.awayTeam == null) {
            // 无法解析出两个球队，跳过
            return null
        }

        // 确定比赛日期
        val gameDate = parsed.gameDate ?: run {
            // 如果没有解析出日期，尝试从 startDate 或 endDate 中提取
            parseDateFromMarketDates(market.startDate, market.endDate) ?: return null
        }

        // 检查日期是否在请求范围内
        if (gameDate.isBefore(startDate) || gameDate.isAfter(endDate)) {
            return null
        }

        // 解析比赛时间（从 endDate 或 startDate 中提取，转换为西8区时间戳）
        val gameTime = parseGameTimeFromMarket(market.startDate, market.endDate, gameDate)

        // 确定比赛状态
        val gameStatus = when {
            market.closed == true -> "finished"
            market.archived == true -> "finished"
            market.active == true -> "scheduled"
            else -> "scheduled"
        }

        return NbaGameDto(
            id = null,
            nbaGameId = market.conditionId ?: market.id,  // 使用 conditionId 或 id 作为 gameId
            homeTeam = parsed.homeTeam,
            awayTeam = parsed.awayTeam,
            gameDate = gameDate,
            gameTime = gameTime,  // 西8区时间戳（毫秒）
            gameStatus = gameStatus,
            homeScore = 0,  // Polymarket 不提供比分
            awayScore = 0,
            period = 0,
            timeRemaining = null,
            polymarketMarketId = market.id
        )
    }

    /**
     * 从市场的 startDate 或 endDate 中解析日期
     */
    private fun parseDateFromMarketDates(startDate: String?, endDate: String?): LocalDate? {
        val dateStr = endDate ?: startDate ?: return null

        return try {
            // 尝试解析 ISO 8601 格式
            if (dateStr.contains("T")) {
                val instant = Instant.parse(dateStr)
                val pstZone = ZoneId.of("America/Los_Angeles")
                instant.atZone(pstZone).toLocalDate()
            } else {
                // 尝试解析日期字符串
                LocalDate.parse(dateStr, DateTimeFormatter.ISO_DATE)
            }
        } catch (e: Exception) {
            logger.debug("解析市场日期失败: $dateStr, error: ${e.message}")
            null
        }
    }

    /**
     * 从市场的日期时间中解析比赛时间，转换为西8区时间戳
     */
    private fun parseGameTimeFromMarket(
        startDate: String?,
        endDate: String?,
        gameDate: LocalDate
    ): Long? {
        val dateTimeStr = endDate ?: startDate ?: return null

        return try {
            val pstZone = ZoneId.of("America/Los_Angeles")

            // 尝试解析 ISO 8601 格式
            val instant = if (dateTimeStr.contains("T")) {
                Instant.parse(dateTimeStr)
            } else {
                // 如果没有时间部分，使用默认时间（晚上8点）
                val defaultTime = gameDate.atTime(20, 0)
                defaultTime.atZone(pstZone).toInstant()
            }

            // 转换为西8区时间戳
            instant.atZone(pstZone).toInstant().toEpochMilli()
        } catch (e: Exception) {
            logger.debug("解析比赛时间失败: $dateTimeStr, error: ${e.message}")
            // 解析失败时，使用默认时间（晚上8点 PST）
            try {
                val defaultTime = gameDate.atTime(20, 0)
                val pstZone = ZoneId.of("America/Los_Angeles")
                defaultTime.atZone(pstZone).toInstant().toEpochMilli()
            } catch (e2: Exception) {
                null
            }
        }
    }

}

