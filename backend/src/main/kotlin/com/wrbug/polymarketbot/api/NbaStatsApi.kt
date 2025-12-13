package com.wrbug.polymarketbot.api

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * NBA Stats API 接口
 * Base URL: https://stats.nba.com/stats/
 * 
 * 注意：NBA Stats API 需要设置正确的请求头：
 * - User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36
 * - Referer: https://www.nba.com/
 * - Accept: application/json
 */
interface NbaStatsApi {
    
    /**
     * 获取赛程和比分
     * @param GameDate 比赛日期，格式：YYYY-MM-DD，不传则获取今天的比赛
     * @param LeagueID 联盟ID，默认：00 (NBA)
     * @param DayOffset 日期偏移，默认：0
     * @return ScoreboardResponse
     */
    @GET("Scoreboard")
    suspend fun getScoreboard(
        @Query("GameDate") gameDate: String? = null,
        @Query("LeagueID") leagueId: String = "00",
        @Query("DayOffset") dayOffset: Int = 0
    ): Response<ScoreboardResponse>
}

/**
 * NBA Stats API Scoreboard 响应
 */
data class ScoreboardResponse(
    val resultSets: List<ResultSet>
)

/**
 * Result Set
 */
data class ResultSet(
    val name: String,
    val headers: List<String>,
    val rowSet: List<List<Any?>>
)

/**
 * Game Header (从 Scoreboard 的 resultSets[0] 获取)
 * Headers: ["GAME_DATE_EST", "GAME_SEQUENCE", "GAME_ID", "GAME_STATUS_ID", "GAME_STATUS_TEXT", 
 *           "GAMECODE", "HOME_TEAM_ID", "VISITOR_TEAM_ID", "SEASON", "LIVE_PERIOD", 
 *           "LIVE_PC_TIME", "NATL_TV_BROADCASTER_ABBREV", "LIVE_PERIOD_TIME_BCAST", "WH_STATUS"]
 */
data class GameHeader(
    val gameDateEst: String,
    val gameSequence: Int,
    val gameId: String,
    val gameStatusId: Int,
    val gameStatusText: String,
    val gameCode: String,
    val homeTeamId: Int,
    val visitorTeamId: Int,
    val season: String,
    val livePeriod: Int?,
    val livePcTime: String?,
    val natlTvBroadcasterAbbrev: String?,
    val livePeriodTimeBcast: String?,
    val whStatus: Int?
)

/**
 * Line Score (从 Scoreboard 的 resultSets[1] 获取)
 * Headers: ["GAME_DATE_EST", "GAME_SEQUENCE", "GAME_ID", "TEAM_ID", "TEAM_ABBREVIATION", 
 *           "TEAM_NAME", "PTS_QTR1", "PTS_QTR2", "PTS_QTR3", "PTS_QTR4", "PTS_OT1", 
 *           "PTS_OT2", "PTS_OT3", "PTS_OT4", "PTS", "FG_PCT", "FT_PCT", "FG3_PCT", 
 *           "AST", "REB", "TOV"]
 */
data class LineScore(
    val gameDateEst: String,
    val gameSequence: Int,
    val gameId: String,
    val teamId: Int,
    val teamAbbreviation: String,
    val teamName: String,
    val ptsQtr1: Int?,
    val ptsQtr2: Int?,
    val ptsQtr3: Int?,
    val ptsQtr4: Int?,
    val ptsOt1: Int?,
    val ptsOt2: Int?,
    val ptsOt3: Int?,
    val ptsOt4: Int?,
    val pts: Int,
    val fgPct: Double?,
    val ftPct: Double?,
    val fg3Pct: Double?,
    val ast: Int?,
    val reb: Int?,
    val tov: Int?
)

