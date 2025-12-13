package com.wrbug.polymarketbot.service.nba

import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern

/**
 * NBA 市场名称解析器
 * 从 Polymarket 市场名称中提取球队和日期信息
 */
object NbaMarketNameParser {
    private val logger = LoggerFactory.getLogger(NbaMarketNameParser::class.java)
    
    // NBA 球队名称映射（支持多种格式）
    private val teamNameMapping = mapOf(
        // 完整名称
        "atlanta hawks" to "Atlanta Hawks",
        "boston celtics" to "Boston Celtics",
        "brooklyn nets" to "Brooklyn Nets",
        "charlotte hornets" to "Charlotte Hornets",
        "chicago bulls" to "Chicago Bulls",
        "cleveland cavaliers" to "Cleveland Cavaliers",
        "dallas mavericks" to "Dallas Mavericks",
        "denver nuggets" to "Denver Nuggets",
        "detroit pistons" to "Detroit Pistons",
        "golden state warriors" to "Golden State Warriors",
        "houston rockets" to "Houston Rockets",
        "indiana pacers" to "Indiana Pacers",
        "la clippers" to "LA Clippers",
        "los angeles lakers" to "Los Angeles Lakers",
        "memphis grizzlies" to "Memphis Grizzlies",
        "miami heat" to "Miami Heat",
        "milwaukee bucks" to "Milwaukee Bucks",
        "minnesota timberwolves" to "Minnesota Timberwolves",
        "new orleans pelicans" to "New Orleans Pelicans",
        "new york knicks" to "New York Knicks",
        "oklahoma city thunder" to "Oklahoma City Thunder",
        "orlando magic" to "Orlando Magic",
        "philadelphia 76ers" to "Philadelphia 76ers",
        "phoenix suns" to "Phoenix Suns",
        "portland trail blazers" to "Portland Trail Blazers",
        "sacramento kings" to "Sacramento Kings",
        "san antonio spurs" to "San Antonio Spurs",
        "toronto raptors" to "Toronto Raptors",
        "utah jazz" to "Utah Jazz",
        "washington wizards" to "Washington Wizards",
        // 常见缩写和别名
        "hawks" to "Atlanta Hawks",
        "celtics" to "Boston Celtics",
        "nets" to "Brooklyn Nets",
        "hornets" to "Charlotte Hornets",
        "bulls" to "Chicago Bulls",
        "cavaliers" to "Cleveland Cavaliers",
        "cavs" to "Cleveland Cavaliers",
        "mavericks" to "Dallas Mavericks",
        "mavs" to "Dallas Mavericks",
        "nuggets" to "Denver Nuggets",
        "pistons" to "Detroit Pistons",
        "warriors" to "Golden State Warriors",
        "rockets" to "Houston Rockets",
        "pacers" to "Indiana Pacers",
        "clippers" to "LA Clippers",
        "lakers" to "Los Angeles Lakers",
        "grizzlies" to "Memphis Grizzlies",
        "heat" to "Miami Heat",
        "bucks" to "Milwaukee Bucks",
        "timberwolves" to "Minnesota Timberwolves",
        "wolves" to "Minnesota Timberwolves",
        "pelicans" to "New Orleans Pelicans",
        "knicks" to "New York Knicks",
        "thunder" to "Oklahoma City Thunder",
        "magic" to "Orlando Magic",
        "76ers" to "Philadelphia 76ers",
        "sixers" to "Philadelphia 76ers",
        "suns" to "Phoenix Suns",
        "trail blazers" to "Portland Trail Blazers",
        "blazers" to "Portland Trail Blazers",
        "kings" to "Sacramento Kings",
        "spurs" to "San Antonio Spurs",
        "raptors" to "Toronto Raptors",
        "jazz" to "Utah Jazz",
        "wizards" to "Washington Wizards",
        "wiz" to "Washington Wizards"
    )
    
    /**
     * 解析结果
     */
    data class ParsedMarketInfo(
        val homeTeam: String?,
        val awayTeam: String?,
        val gameDate: LocalDate?,
        val confidence: Double  // 置信度 0.0-1.0
    )
    
    /**
     * 解析市场名称
     * @param marketName 市场名称
     * @return 解析结果
     */
    fun parse(marketName: String?): ParsedMarketInfo {
        if (marketName.isNullOrBlank()) {
            return ParsedMarketInfo(null, null, null, 0.0)
        }
        
        val normalized = marketName.lowercase()
        var homeTeam: String? = null
        var awayTeam: String? = null
        var gameDate: LocalDate? = null
        var confidence = 0.0
        
        // 尝试提取球队名称
        val teams = extractTeams(normalized)
        if (teams.size >= 2) {
            // 通常第一个是客队，第二个是主队
            awayTeam = teams[0]
            homeTeam = teams[1]
            confidence += 0.5
        } else if (teams.size == 1) {
            // 只有一个球队，无法确定主客场
            awayTeam = teams[0]
            confidence += 0.2
        }
        
        // 尝试提取日期
        val date = extractDate(normalized)
        if (date != null) {
            gameDate = date
            confidence += 0.3
        }
        
        return ParsedMarketInfo(homeTeam, awayTeam, gameDate, confidence.coerceAtMost(1.0))
    }
    
    /**
     * 提取球队名称
     */
    private fun extractTeams(text: String): List<String> {
        val teams = mutableListOf<String>()
        
        // 常见的球队名称模式
        val patterns = listOf(
            // "Team1 vs Team2" 或 "Team1 @ Team2"
            Pattern.compile("(\\w+(?:\\s+\\w+)*?)\\s+(?:vs|@|v\\.?|versus)\\s+(\\w+(?:\\s+\\w+)*?)", Pattern.CASE_INSENSITIVE),
            // "Will Team1 beat Team2"
            Pattern.compile("will\\s+(\\w+(?:\\s+\\w+)*?)\\s+beat\\s+(\\w+(?:\\s+\\w+)*?)", Pattern.CASE_INSENSITIVE),
            // "Team1 win" 或 "Team1 wins"
            Pattern.compile("(\\w+(?:\\s+\\w+)*?)\\s+win", Pattern.CASE_INSENSITIVE)
        )
        
        for (pattern in patterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                val team1 = normalizeTeamName(matcher.group(1) ?: "")
                val team2 = if (matcher.groupCount() >= 2) {
                    normalizeTeamName(matcher.group(2) ?: "")
                } else null
                
                if (team1 != null) {
                    teams.add(team1)
                }
                if (team2 != null) {
                    teams.add(team2)
                }
                
                if (teams.size >= 2) {
                    break
                }
            }
        }
        
        // 如果模式匹配失败，尝试直接查找球队名称
        if (teams.isEmpty()) {
            for ((key, value) in teamNameMapping) {
                if (text.contains(key, ignoreCase = true)) {
                    if (!teams.contains(value)) {
                        teams.add(value)
                    }
                }
            }
        }
        
        return teams.distinct()
    }
    
    /**
     * 标准化球队名称
     */
    private fun normalizeTeamName(name: String): String? {
        val normalized = name.trim().lowercase()
        return teamNameMapping[normalized] ?: teamNameMapping.entries.firstOrNull { 
            normalized.contains(it.key, ignoreCase = true) 
        }?.value
    }
    
    /**
     * 提取日期
     */
    private fun extractDate(text: String): LocalDate? {
        // 尝试多种日期格式
        try {
            // 格式1: "Dec 15, 2024" 或 "December 15, 2024"
            val pattern1 = Pattern.compile("(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\s+(\\d{1,2}),?\\s+(\\d{4})", Pattern.CASE_INSENSITIVE)
            val matcher1 = pattern1.matcher(text)
            if (matcher1.find()) {
                val monthStr = matcher1.group(1)?.lowercase() ?: return null
                val day = matcher1.group(2)?.toIntOrNull() ?: return null
                val year = matcher1.group(3)?.toIntOrNull() ?: return null
                
                val monthMap = mapOf(
                    "jan" to 1, "january" to 1,
                    "feb" to 2, "february" to 2,
                    "mar" to 3, "march" to 3,
                    "apr" to 4, "april" to 4,
                    "may" to 5,
                    "jun" to 6, "june" to 6,
                    "jul" to 7, "july" to 7,
                    "aug" to 8, "august" to 8,
                    "sep" to 9, "september" to 9,
                    "oct" to 10, "october" to 10,
                    "nov" to 11, "november" to 11,
                    "dec" to 12, "december" to 12
                )
                
                val month = monthMap.entries.firstOrNull { monthStr.startsWith(it.key) }?.value
                if (month != null) {
                    return try {
                        LocalDate.of(year, month, day)
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            
            // 格式2: "2024-12-15"
            val pattern2 = Pattern.compile("(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})")
            val matcher2 = pattern2.matcher(text)
            if (matcher2.find()) {
                val year = matcher2.group(1)?.toIntOrNull() ?: return null
                val month = matcher2.group(2)?.toIntOrNull() ?: return null
                val day = matcher2.group(3)?.toIntOrNull() ?: return null
                return try {
                    LocalDate.of(year, month, day)
                } catch (e: Exception) {
                    null
                }
            }
            
            // 格式3: "12/15/2024" 或 "12/15/24"
            val pattern3 = Pattern.compile("(\\d{1,2})/(\\d{1,2})/(\\d{2,4})")
            val matcher3 = pattern3.matcher(text)
            if (matcher3.find()) {
                val month = matcher3.group(1)?.toIntOrNull() ?: return null
                val day = matcher3.group(2)?.toIntOrNull() ?: return null
                val yearStr = matcher3.group(3) ?: return null
                val year = if (yearStr.length == 2) {
                    // 两位年份，假设是 2000-2099
                    val y = yearStr.toIntOrNull() ?: return null
                    if (y < 50) 2000 + y else 1900 + y
                } else {
                    yearStr.toIntOrNull() ?: return null
                }
                return try {
                    LocalDate.of(year, month, day)
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            logger.debug("解析日期失败: ${e.message}")
        }
        
        return null
    }
    
    /**
     * 匹配比赛和市场
     * @param homeTeam 主队名称
     * @param awayTeam 客队名称
     * @param gameDate 比赛日期
     * @param parsedMarket 解析的市场信息
     * @return 是否匹配
     */
    fun matchGame(
        homeTeam: String,
        awayTeam: String,
        gameDate: LocalDate,
        parsedMarket: ParsedMarketInfo
    ): Boolean {
        // 检查日期是否匹配（允许1天误差）
        val dateMatch = parsedMarket.gameDate?.let { marketDate ->
            val daysDiff = kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(gameDate, marketDate))
            daysDiff <= 1
        } ?: false
        
        if (!dateMatch && parsedMarket.gameDate != null) {
            return false
        }
        
        // 检查球队是否匹配
        val homeMatch = parsedMarket.homeTeam?.let { 
            normalizeTeamName(it)?.equals(normalizeTeamName(homeTeam), ignoreCase = true) 
        } ?: false
        
        val awayMatch = parsedMarket.awayTeam?.let { 
            normalizeTeamName(it)?.equals(normalizeTeamName(awayTeam), ignoreCase = true) 
        } ?: false
        
        // 如果两个球队都匹配，或者至少一个匹配且日期匹配
        return (homeMatch && awayMatch) || ((homeMatch || awayMatch) && dateMatch)
    }
}

