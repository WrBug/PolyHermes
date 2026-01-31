-- Drop unused columns from backtest_task table
-- These fields are not needed for backtest scenarios as they use historical data
-- Note: Using standard SQL syntax compatible with MySQL 5.7+

-- Check if columns exist before dropping (using standard approach)
ALTER TABLE backtest_task DROP COLUMN price_tolerance;
ALTER TABLE backtest_task DROP COLUMN delay_seconds;
ALTER TABLE backtest_task DROP COLUMN min_order_depth;
ALTER TABLE backtest_task DROP COLUMN max_spread;
ALTER TABLE backtest_task DROP COLUMN min_price;
ALTER TABLE backtest_task DROP COLUMN max_price;
ALTER TABLE backtest_task DROP COLUMN max_position_value;
ALTER TABLE backtest_task DROP COLUMN max_market_end_date;
