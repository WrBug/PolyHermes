package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.entity.CopyOrderTracking
import com.wrbug.polymarketbot.entity.SellMatchDetail
import com.wrbug.polymarketbot.entity.SellMatchRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CopyTradingPnlCalculatorTest {

    @Test
    fun `marks open positions with current prices and combines realized and unrealized pnl`() {
        val buyOrders = listOf(
            buyOrder(
                id = 1,
                marketId = "market-a",
                outcomeIndex = 0,
                quantity = "10",
                price = "0.60",
                matchedQuantity = "6",
                remainingQuantity = "4"
            ),
            buyOrder(
                id = 2,
                marketId = "market-b",
                outcomeIndex = 1,
                quantity = "5",
                price = "0.20",
                matchedQuantity = "0",
                remainingQuantity = "5"
            )
        )
        val sellRecords = listOf(
            sellRecord(quantity = "6", price = "0.85", pnl = "1.50")
        )
        val matchDetails = listOf(
            matchDetail(trackingId = 1, buyOrderId = "buy-1", quantity = "6", buyPrice = "0.60", sellPrice = "0.85", pnl = "1.50")
        )
        val quotes = listOf(
            PositionValuationQuote(marketId = "market-a", outcomeIndex = 0, side = "0", currentPrice = bd("0.40")),
            PositionValuationQuote(marketId = "market-b", outcomeIndex = 1, side = "1", currentPrice = bd("0.05"))
        )

        val stats = CopyTradingPnlCalculator.calculate(buyOrders, sellRecords, matchDetails, quotes)

        assertEquals("3.40", stats.currentPositionCost.toPlainString())
        assertEquals("1.85", stats.currentPositionValue.toPlainString())
        assertEquals("-1.55", stats.totalUnrealizedPnl.toPlainString())
        assertEquals("1.50", stats.totalRealizedPnl.toPlainString())
        assertEquals("-0.05", stats.totalPnl.toPlainString())
        assertEquals("-0.71", stats.totalPnlPercent.toPlainString())
    }

    @Test
    fun `treats tracked open positions without a quote as zero current value`() {
        val buyOrders = listOf(
            buyOrder(
                id = 1,
                marketId = "expired-market",
                outcomeIndex = 0,
                quantity = "8",
                price = "0.25",
                matchedQuantity = "0",
                remainingQuantity = "8"
            )
        )

        val stats = CopyTradingPnlCalculator.calculate(
            buyOrders = buyOrders,
            sellRecords = emptyList(),
            matchDetails = emptyList(),
            quotes = emptyList()
        )

        assertEquals("2.00", stats.currentPositionCost.toPlainString())
        assertEquals("0", stats.currentPositionValue.toPlainString())
        assertEquals("-2.00", stats.totalUnrealizedPnl.toPlainString())
        assertEquals("-2.00", stats.totalPnl.toPlainString())
        assertEquals("-100.00", stats.totalPnlPercent.toPlainString())
    }

    private fun buyOrder(
        id: Long,
        marketId: String,
        outcomeIndex: Int?,
        quantity: String,
        price: String,
        matchedQuantity: String,
        remainingQuantity: String
    ) = CopyOrderTracking(
        id = id,
        copyTradingId = 1,
        accountId = 1,
        leaderId = 1,
        marketId = marketId,
        side = outcomeIndex?.toString() ?: "YES",
        outcomeIndex = outcomeIndex,
        buyOrderId = "buy-$id",
        leaderBuyTradeId = "leader-buy-$id",
        leaderBuyQuantity = null,
        quantity = bd(quantity),
        price = bd(price),
        matchedQuantity = bd(matchedQuantity),
        remainingQuantity = bd(remainingQuantity),
        status = if (bd(remainingQuantity).signum() == 0) "fully_matched" else "filled",
        source = "test",
        createdAt = id,
        updatedAt = id
    )

    private fun sellRecord(quantity: String, price: String, pnl: String) = SellMatchRecord(
        id = 1,
        copyTradingId = 1,
        sellOrderId = "sell-1",
        leaderSellTradeId = "leader-sell-1",
        marketId = "market-a",
        side = "0",
        outcomeIndex = 0,
        totalMatchedQuantity = bd(quantity),
        sellPrice = bd(price),
        totalRealizedPnl = bd(pnl),
        priceUpdated = true,
        createdAt = 1
    )

    private fun matchDetail(
        trackingId: Long,
        buyOrderId: String,
        quantity: String,
        buyPrice: String,
        sellPrice: String,
        pnl: String
    ) = SellMatchDetail(
        id = trackingId,
        matchRecordId = 1,
        trackingId = trackingId,
        buyOrderId = buyOrderId,
        matchedQuantity = bd(quantity),
        buyPrice = bd(buyPrice),
        sellPrice = bd(sellPrice),
        realizedPnl = bd(pnl),
        createdAt = 1
    )

    private fun bd(value: String) = BigDecimal(value)
}
