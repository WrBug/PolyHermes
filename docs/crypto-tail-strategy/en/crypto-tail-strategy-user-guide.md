# Crypto Tail Strategy Configuration Guide

## Part 1: What is Crypto Tail Strategy?

Crypto Tail Strategy is an automated trading strategy designed specifically for Polymarket crypto markets' **5-minute** or **15-minute** "Up or Down" markets.

**Core Logic**: Within a specified time window, when the market price enters your set price range, the system will automatically buy at a fixed price (0.99) without manual operation.

**Use Cases**:
- You want to capture price fluctuations at the end of market cycles
- You want to automate trading execution and avoid manual monitoring
- You have some judgment about market trends and want to set conditions for automatic triggering

---

## Part 2: How the Strategy Works

### 2.1 Basic Flow

```
Cycle Start → Within Time Window → Price Enters Range → Auto Order
```

1. **Cycle**: Each market runs on fixed cycles (5 minutes or 15 minutes)
   - 5-minute market: Every 5 minutes is a cycle (e.g., 10:00, 10:05, 10:10...)
   - 15-minute market: Every 15 minutes is a cycle (e.g., 10:00, 10:15, 10:30...)

2. **Time Window**: You can set a time period within the cycle
   - Example: 15-minute market, set window to "3 minutes ~ 12 minutes"
   - Meaning: Only triggers between the 3rd and 12th minute after cycle start

3. **Price Range**: Set the trigger price range
   - Example: Minimum price 0.50, Maximum price 0.80
   - Meaning: Only triggers when market price is between 0.50 ~ 0.80

4. **Auto Order**: After conditions are met, the system automatically buys at price 0.99

### 2.2 Important Limitations

- **Maximum one trigger per cycle**: Within the same cycle, even if conditions are met multiple times, only one order is placed
- **Fixed order price**: All orders are submitted at price 0.99
- **Requires separate wallet**: It's recommended to use a dedicated wallet for tail strategies to avoid conflicts with other operations (manual trading, copy trading, etc.)

---

## Part 3: Parameter Details

### 3.1 Basic Parameters

| Parameter | Description | Required | Example |
|-----------|-------------|----------|---------|
| **Account** | Select the wallet account for trading | ✅ | Account A |
| **Strategy Name** | Name your strategy for easy identification | ❌ | "BTC 15min Tail Strategy" |
| **Market** | Select the market to trade (5-minute or 15-minute) | ✅ | btc-updown-15m |

### 3.2 Cycle Settings

| Parameter | Description | Required | Example |
|-----------|-------------|----------|---------|
| **Cycle Length** | Automatically determined by selected market | ✅ | 15 minutes (900 seconds) |
| **Time Window Start** | Minutes after cycle start to begin monitoring | ✅ | 3 min 0 sec |
| **Time Window End** | Minutes after cycle start to stop monitoring | ✅ | 12 min 0 sec |

**Time Window Explanation**:
- 5-minute market: Can choose any time period within 0 ~ 5 minutes
- 15-minute market: Can choose any time period within 0 ~ 15 minutes
- **Start time must ≤ End time**
- Times outside the window won't trigger even if price conditions are met

**Example**:
- 15-minute market, window "3 min 0 sec ~ 12 min 0 sec"
  - 0 ~ 3 minutes after cycle start: Not monitoring
  - 3 ~ 12 minutes after cycle start: Monitoring price, triggers when conditions met
  - 12 ~ 15 minutes after cycle start: Not monitoring

### 3.3 Price Range

| Parameter | Description | Required | Range | Example |
|-----------|-------------|----------|-------|---------|
| **Minimum Price (minPrice)** | Minimum trigger price | ✅ | 0 ~ 1 | 0.50 |
| **Maximum Price (maxPrice)** | Maximum trigger price | ❌ | 0 ~ 1, default 1 | 0.80 |

**Price Range Explanation**:
- Price range is a decimal between 0 ~ 1
- Only triggers when market price is within [Minimum Price, Maximum Price]
- If maximum price is not filled, defaults to 1.0 (triggers as long as price ≥ minimum price)

**Example**:
- Minimum price 0.50, Maximum price 0.80
  - Price 0.45: Not triggered (below minimum)
  - Price 0.60: Triggered ✅ (within range)
  - Price 0.85: Not triggered (above maximum)

### 3.4 Investment Amount

| Parameter | Description | Required | Example |
|-----------|-------------|----------|---------|
| **Investment Mode** | Choose ratio or fixed amount | ✅ | Ratio / Fixed Amount |
| **Ratio (%)** | Percentage of account balance to invest | Conditionally required | 10% (Account has 100 USDC, invest 10 USDC) |
| **Fixed Amount (USDC)** | Fixed amount to invest each time | Conditionally required | 50 USDC |

**Investment Mode Explanation**:

**Mode 1: By Ratio (RATIO)**
- Each trigger invests a percentage of current available balance
- Example: Account has 100 USDC, set ratio to 10%
  - 1st trigger: Invest 10 USDC
  - 2nd trigger: If balance becomes 90 USDC, invest 9 USDC
- **Advantages**: Automatically adapts to account balance changes
- **Disadvantages**: Investment amount may vary each time

**Mode 2: Fixed Amount (FIXED)**
- Each trigger invests a fixed specified amount
- Example: Set fixed amount to 50 USDC
  - Every trigger invests 50 USDC
- **Advantages**: Stable investment amount, easy to manage
- **Disadvantages**: Need to ensure sufficient account balance

**Notes**:
- Minimum order amount: At least 1 USDC
- If account balance is insufficient, order will fail and record failure reason

### 3.5 Spread Filter (Advanced Feature)

The spread filter controls whether to trigger based on Binance BTC/USDC K-line volatility. It supports two directions: **Minimum spread** and **Maximum spread**.

| Parameter | Description | Required | Example |
|-----------|-------------|----------|---------|
| **Spread Mode** | Choose spread validation method | ✅ | None / Fixed / Auto |
| **Spread Direction** | Min spread (trigger when ≥) or Max spread (trigger when ≤) | ✅ | Min spread / Max spread |
| **Spread Value** | Fill when using Fixed mode (unit: USDC) | Conditionally required | 30 |

**Spread Direction**:

- **Min spread**: Triggers only when Binance K-line spread **≥** the set value  
  - Use when you want to trade only when volatility is "large enough" (avoid entering when volatility is too small).
- **Max spread**: Triggers only when Binance K-line spread **≤** the set value  
  - Use when you want to trade only when volatility is "small enough" (avoid entering when volatility is too high).

**Three Spread Modes**:

**Mode 1: None (NONE)**
- No spread validation
- Triggers as long as time window and price range conditions are met
- **Suitable for**: Not concerned about Binance price volatility, only watching Polymarket price

**Mode 2: Fixed (FIXED)**
- Set a fixed spread value (unit: USDC)
- **Min spread**: Triggers when K-line spread ≥ set value  
  - Example: Set 30, spread ≥ 30 → triggered ✅, spread < 30 → not triggered
- **Max spread**: Triggers when K-line spread ≤ set value  
  - Example: Set 50, spread ≤ 50 → triggered ✅, spread > 50 → not triggered
- **Suitable for**: You have a clear spread threshold in mind

**Mode 3: Auto (AUTO)**
- System automatically calculates an effective spread from the last 20 K-lines
- Calculation logic:
  1. Get recent 20 K-lines (matching strategy cycle)
  2. Filter by direction (Up direction only looks at rising K-lines, Down direction only looks at falling K-lines)
  3. Remove outliers (using IQR method)
  4. Calculate average spread × 0.8 as effective spread
- **Min spread**: Triggers when K-line spread ≥ effective spread  
- **Max spread**: Triggers when K-line spread ≤ effective spread
- **Suitable for**: Want automatic adjustment based on historical data without setting a specific value

**Spread Explanation**:
- Spread = |close price - open price| (Binance BTC/USDC for that K-line)
- Example: Open price 50000, close price 50030, spread = 30
- Larger spread means greater price volatility in that cycle

---

## Part 4: Configuration Examples

### Example 1: Simple Strategy (5-minute Market)

**Scenario**: In the last 2 minutes of a 5-minute market, if price is below 0.60, automatically buy 10 USDC

**Configuration**:
```
Account: Account A
Strategy Name: BTC 5min Simple Strategy
Market: btc-updown-5m
Time Window: 3 min 0 sec ~ 5 min 0 sec
Minimum Price: 0.00
Maximum Price: 0.60
Investment Mode: Fixed Amount
Fixed Amount: 10 USDC
Spread Mode: None
Enabled: On
```

**Explanation**:
- 0 ~ 3 minutes after cycle start: Not monitoring
- 3 ~ 5 minutes after cycle start: If price ≤ 0.60, automatically buy 10 USDC

---

### Example 2: Ratio Investment Strategy (15-minute Market)

**Scenario**: In the middle segment (5 ~ 10 minutes) of a 15-minute market, if price is between 0.40 ~ 0.70, invest 15% of account balance

**Configuration**:
```
Account: Account B
Strategy Name: BTC 15min Ratio Strategy
Market: btc-updown-15m
Time Window: 5 min 0 sec ~ 10 min 0 sec
Minimum Price: 0.40
Maximum Price: 0.70
Investment Mode: By Ratio
Ratio: 15%
Spread Mode: None
Enabled: On
```

**Explanation**:
- Assuming account balance is 100 USDC
- 5 ~ 10 minutes after cycle start: If price is between 0.40 ~ 0.70, automatically buy about 15 USDC (100 × 15%)

---

### Example 3: Strategy with Spread Filter (15-minute Market)

**Scenario**: In the latter segment (10 ~ 14 minutes) of a 15-minute market, if price is between 0.50 ~ 0.80 and Binance spread ≥ 50, invest 20 USDC

**Configuration**:
```
Account: Account C
Strategy Name: BTC 15min Spread Strategy
Market: btc-updown-15m
Time Window: 10 min 0 sec ~ 14 min 0 sec
Minimum Price: 0.50
Maximum Price: 0.80
Investment Mode: Fixed Amount
Fixed Amount: 20 USDC
Spread Mode: Fixed
Spread Direction: Min spread
Spread Value: 50
Enabled: On
```

**Explanation**:
- 10 ~ 14 minutes after cycle start: Only triggers when both conditions are met:
  1. Price is between 0.50 ~ 0.80 ✅
  2. Spread direction is "Min spread" and Binance spread ≥ 50 ✅
- If spread is only 30, won't trigger even if price condition is met

---

### Example 4: Auto Spread Strategy (15-minute Market)

**Scenario**: In the early segment (2 ~ 8 minutes) of a 15-minute market, if price is between 0.30 ~ 0.90, invest 20% of account balance, spread calculated automatically by system

**Configuration**:
```
Account: Account D
Strategy Name: BTC 15min Auto Spread Strategy
Market: btc-updown-15m
Time Window: 2 min 0 sec ~ 8 min 0 sec
Minimum Price: 0.30
Maximum Price: 0.90
Investment Mode: By Ratio
Ratio: 20%
Spread Mode: Auto
Spread Direction: Min spread
Enabled: On
```

**Explanation**:
- System automatically calculates effective spread from the last 20 K-lines
- 2 ~ 8 minutes after cycle start: Only triggers when both conditions are met:
  1. Price is between 0.30 ~ 0.90 ✅
  2. Spread direction is "Min spread" and Binance spread ≥ system-calculated effective spread ✅

---

## Part 5: Frequently Asked Questions

### Q1: When will the strategy trigger?

**A**: All of the following conditions must be met simultaneously:
1. ✅ Current time is within the time window
2. ✅ Market price is within [Minimum Price, Maximum Price] range
3. ✅ This cycle hasn't triggered yet (maximum one trigger per cycle)
4. ✅ If spread filter is set, Binance spread and spread direction must both be satisfied

### Q2: Why didn't my strategy trigger?

**Possible reasons**:
1. **Time window incorrect**: Current time is not within the set time window
2. **Price not in range**: Market price is not within [Minimum Price, Maximum Price] range
3. **Already triggered this cycle**: This cycle has already triggered once, won't trigger again
4. **Spread not met**: If spread filter is set, Binance spread or spread direction requirement is not satisfied
5. **Insufficient account balance**: Account balance is less than the set investment amount
6. **Strategy not enabled**: Check if strategy's enabled status is "On"

### Q3: What does "maximum one trigger per cycle" mean?

**A**: Within each cycle (5 minutes or 15 minutes), even if conditions are met multiple times, only one order is placed.

**Example**:
- 15-minute market, cycle starts at 10:00
- At 10:05, price meets condition, triggers order ✅
- At 10:08, price meets condition again, but won't place another order (already triggered this cycle)
- At 10:15, new cycle starts, can trigger again

### Q4: What's the difference between fixed amount and ratio?

**Fixed Amount**:
- Invests the same amount each trigger
- Example: Set 50 USDC, every trigger is 50 USDC
- Need to ensure sufficient account balance

**By Ratio**:
- Invests a percentage of account balance each trigger
- Example: Set 10%, when account has 100 USDC, invest 10 USDC, after balance becomes 90 USDC, next trigger invests 9 USDC
- Automatically adapts to balance changes

### Q5: What's the use of the spread filter feature?

**A**: The spread filter decides whether to trigger based on Binance BTC/USDC K-line volatility. It supports two directions.

**Min spread** (trigger when spread **≥** set value):
- Avoids triggering when volatility is too small
- Example: Set 30, only triggers when spread ≥ 30

**Max spread** (trigger when spread **≤** set value):
- Avoids triggering when volatility is too high (lower risk)
- Example: Set 50, only triggers when spread ≤ 50

**Three mode selection suggestions**:
- **None**: Not concerned about Binance price volatility, only watching Polymarket price
- **Fixed**: You know the expected spread threshold (use with Min or Max spread direction)
- **Auto**: Want effective spread calculated from historical data without setting a specific value

### Q6: Why is it recommended to use a separate wallet?

**A**: To avoid the following issues:
1. **Balance changes**: If wallet is also used for manual trading, balance changes may affect strategy execution
2. **Position conflicts**: Manual trading and strategy trading may conflict
3. **Management confusion**: Difficult to distinguish which orders are from strategy vs manual

**Recommendation**: Create a dedicated wallet, only for tail strategies.

### Q7: Why is the order price fixed at 0.99?

**A**: This is a design feature of the strategy:
- 0.99 is the highest price in the market (close to 1.0)
- Buying at the highest price ensures orders execute quickly
- Although buying price is higher, the strategy's core is capturing market volatility, not pursuing optimal price

### Q8: Does the strategy depend on auto-redeem functionality?

**A**: Yes, tail strategy depends on auto-redeem functionality.

**Reasons**:
- Strategy orders create positions after execution
- These positions need to be automatically redeemed after market settlement
- If auto-redeem is not configured, positions may not be redeemed in time

**Configuration Requirements**:
- Configure Builder API Key in "System Settings"
- Enable auto-redeem functionality

---

## Part 6: Important Notes

### 6.1 Account Requirements

- ✅ Account must have API Key, API Secret, API Passphrase configured
- ✅ Account must have sufficient USDC balance
- ✅ Recommended to use a dedicated wallet to avoid conflicts with other operations

### 6.2 Time Window Settings

- ⚠️ Start time must ≤ End time
- ⚠️ Time window cannot exceed cycle length (5-minute market ≤ 5 minutes, 15-minute market ≤ 15 minutes)
- ⚠️ Recommended to set reasonable time windows, avoid triggering at cycle start or end

### 6.3 Price Range Settings

- ⚠️ Minimum price must ≤ Maximum price
- ⚠️ Price range is a decimal between 0 ~ 1
- ⚠️ Recommended to set reasonable price ranges based on market conditions

### 6.4 Investment Amount Settings

- ⚠️ Minimum order amount: At least 1 USDC
- ⚠️ Ensure sufficient account balance to avoid order failures
- ⚠️ Ratio mode: Note the impact of account balance changes on investment amount

### 6.5 Spread Filter Settings

- ⚠️ Spread direction: Min spread means "trigger when ≥"; Max spread means "trigger when ≤". Choose according to your need.
- ⚠️ Fixed mode: Need to fill a reasonable spread value (unit: USDC)
- ⚠️ Auto mode: System calculates effective spread within the window, no manual value needed
- ⚠️ Overly strict spread (min spread too high or max spread too low) may make the strategy rarely trigger

### 6.6 Other Notes

- ⚠️ Strategy is enabled by default after creation, can disable "Enabled Status" if need to pause
- ⚠️ Maximum one trigger per cycle, set trigger conditions reasonably
- ⚠️ Strategy depends on auto-redeem functionality, ensure Builder API Key is configured
- ⚠️ Recommended to regularly check trigger records to understand strategy execution

---

## Part 7: Strategy Management

### 7.1 View Strategy List

On the "Crypto Tail Strategy" page, you can view all strategies:
- Strategy name
- Market information
- Time window
- Price range
- Investment mode
- Enabled status
- Last trigger time
- Statistics like total profit, win rate

### 7.2 View Trigger Records

Click on a strategy to view detailed trigger records:
- Trigger time
- Market price
- Investment amount
- Order ID
- Order status (success/fail)
- Settlement information (profit/loss, win rate, etc.)

### 7.3 Edit Strategy

You can modify strategy parameters at any time:
- Time window
- Price range
- Investment mode
- Spread filter (mode, direction, value)
- Enabled status

**Note**: Modified strategies take effect in the next cycle.

### 7.4 Delete Strategy

After deleting a strategy:
- Strategy configuration is deleted
- Historical trigger records are retained
- Already placed orders are not affected

---

## Part 8: Summary

Crypto Tail Strategy is a powerful automated trading tool that can help you:

1. **Automated Trading**: No need for manual monitoring, system executes automatically
2. **Precise Control**: Precisely control trigger conditions through time windows and price ranges
3. **Flexible Configuration**: Supports both ratio and fixed amount investment modes
4. **Risk Filtering**: Control volatility conditions through spread filter (min spread / max spread)

**Usage Recommendations**:
- For first-time users, start with simple strategies (no spread filter)
- After familiarizing, try adding spread filter features
- Regularly check trigger records, adjust strategy parameters based on actual situation
- Use a dedicated wallet to avoid conflicts with other operations

**Happy Trading!** 🚀
