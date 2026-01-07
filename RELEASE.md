# v1.1.7

## 🚀 主要功能

### 💰 Polymarket Maker Rebates Program 费率支持

- **新增费率查询 API 接口** (`getFeeRate`)
  - 支持动态查询 Maker Rebates Program 费率
  - 修正 API 返回字段名：使用 `base_fee` 而非 `fee_rate_bps`（与 TypeScript clob-client 一致）
  
- **动态费率获取**
  - 在所有订单创建处动态获取费率：
    * 跟单买入订单 (`processBuyTrade`)
    * 跟单卖出订单 (`matchSellOrder`)
    * 账户卖出订单 (`sellPosition`)
  - 费率获取失败时降级到默认值 "0"，确保系统可用性
  - 添加详细的日志记录，便于监控和调试

- **参考文档**: https://docs.polymarket.com/developers/market-makers/maker-rebates-program

### 🔧 Docker 部署优化

- **日志级别环境变量支持**
  - 在 `application.properties` 中支持通过 `LOG_LEVEL_ROOT` 和 `LOG_LEVEL_APP` 环境变量配置日志级别
  - 在 `docker-compose.yml` 和 `docker-compose.prod.yml` 中添加日志级别环境变量配置
  - 在 `deploy.sh` 的 `.env` 模板中添加日志级别配置说明
  - 支持通过环境变量动态配置日志级别，无需修改配置文件
  - 默认值：`root=INFO`, `app=DEBUG`

## 🐛 Bug 修复

### 修复市场条件查询的 RPC 调用错误

- **问题**：使用错误的函数签名 `conditions(bytes32)` 导致 RPC 调用失败（execution reverted）
- **修复**：
  - 将错误的 `conditions(bytes32)` 函数调用改为正确的 `getOutcomeSlotCount(bytes32)` 和 `payoutDenominator(bytes32)` 函数调用
  - 修复 `BlockchainService.getCondition` 方法，使用正确的 ConditionalTokens 合约函数签名
  - 改进 `MarketPriceService` 的错误处理：当链上查询出现 RPC 错误时，降级到 CLOB API 或 Gamma API 查询，而不是直接抛出异常，提高容错性

### 修复 RPC 错误时误创建自动卖出记录的问题

- **问题**：当链上查询市场条件出现 RPC 错误（execution reverted）时，系统会误判为市场已卖出，创建错误的自动卖出记录
- **修复**：
  - 修改 `getPriceFromChainCondition` 返回 `Pair<BigDecimal?, Boolean>`，第二个值表示是否发生 RPC 错误
  - 在 `getCurrentMarketPrice` 中检测到 RPC 错误时抛出异常，`PositionCheckService` 会捕获并跳过该市场的处理
  - 避免在市场不存在或尚未创建时误判为已卖出

## 📝 文档更新

### 更新 Telegram 群链接

- 将所有 Telegram 群链接统一更新为 `t.me/polyhermes`
- 更新了以下文件：
  - `frontend/src/components/Layout.tsx` - 桌面端和移动端导航链接
  - `RELEASE.md` - 相关链接
  - `README.md` 和 `README_EN.md` - 相关链接部分

### 添加 Docker 版本徽章

- 在 README 和 README_EN.md 中添加动态 Docker 版本徽章
- 使用 shields.io 自动显示 Docker Hub 上 `wrbug/polyhermes` 镜像的最新版本
- 版本信息自动更新，无需手动维护

## 📊 变更统计

- **提交数量**：5 个提交
- **文件变更**：16 个文件
- **代码变更**：+205 行 / -886 行（净减少 681 行）

### 详细文件变更

**后端变更**：
- `PolymarketClobApi.kt` - 添加费率查询接口（+25 行）
- `AccountService.kt` - 在订单创建处添加动态费率获取（+11 行）
- `BlockchainService.kt` - 修复市场条件查询的 RPC 调用错误（+84 行）
- `MarketPriceService.kt` - 改进错误处理，支持降级到其他数据源（+36 行）
- `PolymarketClobService.kt` - 添加费率查询服务（+32 行）
- `CopyOrderTrackingService.kt` - 在跟单订单创建处添加费率获取（+34 行）
- `PositionCheckService.kt` - 修复 RPC 错误处理逻辑（+2 行）
- `application.properties` - 添加日志级别环境变量支持（+6 行）

**前端变更**：
- `Layout.tsx` - 更新 Telegram 群链接（+4 行）

**配置文件变更**：
- `docker-compose.yml` - 添加日志级别环境变量（+4 行）
- `docker-compose.prod.yml` - 添加日志级别环境变量（+4 行）
- `deploy.sh` - 添加日志级别配置说明（+5 行）

**文档变更**：
- `README.md` - 更新 Telegram 链接，添加 Docker 版本徽章（+2 行）
- `README_EN.md` - 更新 Telegram 链接，添加 Docker 版本徽章（+2 行）
- `RELEASE.md` - 更新 Telegram 链接（+4 行）
- `docs/zh/smart-money-analysis.md` - 删除文档（-836 行）

## 🔧 技术细节

### API 变更

- **新增接口**：
  - `POST /api/clob/fee-rate` - 获取 Maker Rebates Program 费率（内部使用）
- **无移除接口**

### 环境变量变更

- **新增环境变量**：
  - `LOG_LEVEL_ROOT` - Root 日志级别（默认：INFO）
  - `LOG_LEVEL_APP` - 应用日志级别（默认：DEBUG）

### 合约调用修复

- **修复的函数调用**：
  - 从 `conditions(bytes32)` 改为 `getOutcomeSlotCount(bytes32)` 和 `payoutDenominator(bytes32)`
  - 使用正确的 ConditionalTokens 合约函数签名
  - 参考：https://polygonscan.com/address/0x4d97dcd97ec945f40cf65f87097ace5ea0476045#code

## 📝 升级说明

### 数据库升级

- **无需数据库迁移**：本次更新不涉及数据库结构变更

### 配置更新

- **可选配置**：新增日志级别环境变量，如不配置将使用默认值
  - `LOG_LEVEL_ROOT=INFO`（默认）
  - `LOG_LEVEL_APP=DEBUG`（默认）

### Docker 部署

- **推荐更新**：使用 Docker Hub 镜像部署的用户，建议更新到最新版本
  ```bash
  docker pull wrbug/polyhermes:latest
  docker-compose -f docker-compose.prod.yml up -d
  ```

## 🔗 相关链接

- **GitHub 仓库**：https://github.com/WrBug/PolyHermes
- **Twitter**：@polyhermes
- **Telegram 群组**：https://t.me/polyhermes

---

**发布日期**：2026-01-07

---

# v1.1.5

## 🔧 功能优化与改进

### 前端优化

#### 优化 InputNumber 输入框格式化
- 优化数值输入框的格式化逻辑，修正正则表达式以正确处理整数显示
- 更新所有相关 InputNumber 组件的 formatter 函数，确保显示准确性
- 影响的组件：CopyTradingAdd、CopyTradingEdit、EditModal、TemplateAdd、TemplateEdit、TemplateList
- 影响范围：跟单配置、模板配置中的所有数值输入框

#### 优化数字显示格式
- 添加 `formatNumber` 工具函数，自动去除小数尾随零（如 100.00 → 100）
- 统一所有数值输入框的显示格式，提升用户体验

### 后端优化

#### 优化按比例跟单金额计算逻辑
- 优化按比例计算的订单金额处理，使用向上取整确保满足最小限制要求
- 对订单金额进行向上取整处理（保留 2 位小数精度）
- 自动调整订单数量以满足最小限制要求
- 使用 `RoundingMode.CEILING` 确保金额满足最小限制
- 影响范围：按比例跟单的订单创建逻辑
- 技术细节：
  - 扩展 `BigDecimal.div()` 扩展函数，支持指定精度和舍入模式
  - 在 `CopyOrderTrackingService` 中优化金额计算和验证逻辑

#### 增强 copyRatio 精度支持
- 将 copyRatio 字段精度从 DECIMAL(10,2) 增加到 DECIMAL(20,8)
- 支持更精确的跟单比例设置（最小 0.01%，最大 10000%）
- 影响的实体：CopyTrading、CopyTradingTemplate
- 数据库迁移：新增 V18 迁移脚本，自动升级数据库字段精度

## 🔧 功能优化

### 移除刷新代理钱包接口
- **移除接口**：
  - `POST /api/accounts/refresh-proxy` - 刷新单个账户的代理地址
  - `POST /api/accounts/refresh-all-proxies` - 刷新所有账户的代理地址
- **原因**：代理地址应在账户导入时自动计算，无需手动刷新
- **影响范围**：AccountController、AccountService
- **向后兼容性**：这些接口已不再使用，移除不影响现有功能

### 前端跟单比例配置优化
- **最小比例**：从 10% 降低到 0.01%，支持更灵活的跟单比例设置
- **最大比例**：增加到 10000%，满足大比例跟单需求
- **显示格式**：比例模式显示为百分比（如 "100%" 而不是 "1x"）
- **输入验证**：增强输入验证，确保比例在合理范围内

## 📊 变更统计

- **提交数量**：3 个提交
- **文件变更**：15 个文件
- **代码变更**：+575 行 / -194 行（净增加 381 行）

### 详细文件变更

**后端变更**：
- `AccountController.kt` - 移除刷新代理钱包接口（-59 行）
- `AccountService.kt` - 移除刷新代理钱包方法（-79 行）
- `CopyTrading.kt` - 增加 copyRatio 精度
- `CopyTradingTemplate.kt` - 增加 copyRatio 精度
- `CopyOrderTrackingService.kt` - 优化按比例跟单金额计算逻辑（+45 行）
- `MathExt.kt` - 扩展 div 函数支持精度和舍入模式（+20 行）
- `V18__increase_copy_ratio_precision.sql` - 数据库迁移脚本（+14 行）

**前端变更**：
- `CopyTradingAdd.tsx` - 优化 formatter、优化比例配置（+106 行）
- `CopyTradingEdit.tsx` - 优化 formatter、优化比例配置（+106 行）
- `CopyTradingList.tsx` - 优化比例显示格式
- `CopyTradingOrders/EditModal.tsx` - 优化 formatter、优化比例配置（+106 行）
- `TemplateAdd.tsx` - 优化 formatter、优化比例配置（+65 行）
- `TemplateEdit.tsx` - 优化 formatter、优化比例配置（+65 行）
- `TemplateList.tsx` - 优化 formatter、优化比例配置（+65 行）
- `utils/index.ts` - 添加 formatNumber 工具函数（+31 行）

## 🔧 技术细节

### 数据库变更
- **迁移脚本**：`V18__increase_copy_ratio_precision.sql`
- **变更内容**：
  - `copy_trading.copy_ratio`: DECIMAL(10,2) → DECIMAL(20,8)
  - `copy_trading_templates.copy_ratio`: DECIMAL(10,2) → DECIMAL(20,8)
- **自动执行**：升级时会自动执行迁移脚本

### API 变更
- **移除接口**：
  - `POST /api/accounts/refresh-proxy`
  - `POST /api/accounts/refresh-all-proxies`
- **无新增接口**

### 前端变更
- **工具函数**：新增 `formatNumber()` 函数，用于格式化数字显示
- **组件更新**：所有数值输入框统一使用新的 formatter 函数
- **显示优化**：跟单模式的比例显示为百分比格式

## 📝 升级说明

### 数据库升级
本次版本包含数据库迁移脚本，升级时会自动执行：
- 自动增加 `copy_ratio` 字段的精度
- 现有数据不受影响，精度升级是向后兼容的

### 配置变更
无需额外配置变更。

### 兼容性
- **向后兼容**：所有变更都是向后兼容的
- **API 兼容**：移除的接口不影响现有功能（这些接口已不再使用）
- **数据兼容**：数据库字段精度升级不会影响现有数据

## 🎯 主要改进

1. **优化输入框格式化**：优化数值输入框的显示逻辑
2. **优化跟单金额计算**：确保按比例跟单的金额满足最小限制要求
3. **提升精度支持**：支持更精确的跟单比例设置（0.01% - 10000%）
4. **代码清理**：移除不再使用的刷新代理钱包接口

## 🔗 相关链接

- [GitHub Tag](https://github.com/WrBug/PolyHermes/releases/tag/v1.1.5)
- [变更日志](https://github.com/WrBug/PolyHermes/compare/v1.1.4...v1.1.5)

## 🙏 致谢

感谢所有贡献者和测试用户的反馈与支持！

---

# v1.1.2

## 🚀 主要功能

### 🐛 修复内存泄漏问题
- 修复 Retrofit/OkHttpClient 实例重复创建导致的内存泄漏问题
- 为不需要认证的 API 创建共享的 OkHttpClient 实例（Gamma API、Data API、GitHub API 等）
- 带认证的 CLOB API 按钱包地址缓存（每个账户一个客户端）
- RPC API 按 RPC URL 缓存，Builder Relayer API 按 relayerUrl 缓存
- 添加 `@PreDestroy` 方法清理缓存，确保资源正确释放
- **效果**：内存占用从运行几小时后从 400MB 涨到 1GB+ 变为保持稳定，大幅减少内存占用

### 📊 市场价格服务优化
- 移除降级查询逻辑，仅保留链上 RPC 查询和 CLOB 订单簿查询
- 移除 CLOB Trades、Gamma Market Status、Gamma Market Price 查询逻辑
- 如果所有数据源都失败，抛出明确的异常信息
- 价格截位到 4 位小数（向下截断，不四舍五入）
- 简化代码逻辑，提高查询效率和准确性

### 🔧 代码架构优化
- 统一 Gson 使用，改为依赖注入方式
- 在 `GsonConfig` 中统一配置 Gson Bean（lenient 模式）
- 所有 Service 类通过构造函数注入 Gson 实例
- 移除所有 `GsonConverterFactory.create()` 无参调用，统一使用注入的 Gson
- 提高代码一致性和可维护性

### 🗑️ 功能清理
- 移除下单失败存储数据库的功能
- 删除 `FailedTrade` 实体类和 `FailedTradeRepository`
- 从 `CopyOrderTrackingService` 中移除失败交易存储逻辑
- 创建 Flyway migration V16 删除 `failed_trade` 表
- 下单失败时仅记录日志，不再存储到数据库，简化数据模型

### 🚀 部署优化
- 自动使用当前分支名作为 Docker 版本号
- 分支名中的 `/` 自动替换为 `-`（Docker tag 不支持 `/）
- `docker-compose.yml` 启用 build args，从环境变量读取版本号
- 前端页面将显示当前分支名作为版本号
- 如果没有 Git 仓库或获取失败，使用默认值 `dev`

## 🐛 Bug 修复

### 修复 Flyway Migration 问题
- 恢复 V1 migration 文件，避免 checksum 不匹配
- 保持 `V1__init_database.sql` 的原有内容不变
- `failed_trade` 表的删除通过 V16 migration 处理
- 确保已有数据库的 migration checksum 保持一致

### 修复前端编译错误
- 修复 `PositionList.tsx` 中引用不存在的 `bestBid` 属性导致的编译错误
- 使用 `currentPrice` 替代 `bestBid`，确保前端代码可以正常编译

## 📚 文档更新

- 新增智能资金分析文档（`docs/zh/smart-money-analysis.md`）
- 详细说明智能资金分析功能的使用方法和策略

## 🔧 技术改进

- 优化 `RetrofitFactory`，实现客户端实例缓存和复用
- 优化 `CopyOrderTrackingService`，移除失败交易相关逻辑
- 优化 `OrderStatusUpdateService`，增强订单状态更新功能
- 优化 `TelegramNotificationService`，改进通知逻辑
- 优化 `PositionCheckService`，简化代码结构
- 优化 `PolymarketClobService`，改进 API 调用逻辑

## 📦 数据库变更

- 删除 `failed_trade` 表（Migration: V16）

## 🔗 相关链接

- **GitHub Release**: https://github.com/WrBug/PolyHermes/releases/tag/v1.1.2
- **完整更新日志**: https://github.com/WrBug/PolyHermes/compare/v1.1.1...v1.1.2
- **Docker Hub**: https://hub.docker.com/r/wrbug/polyhermes

## 📊 统计信息

- **文件变更**: 29 个文件
- **代码变更**: +1597 行 / -678 行
- **主要提交**: 8 个提交

## ⚠️ 重要提醒

**请务必使用官方 Docker 镜像源，避免财产损失！**

### ✅ 官方 Docker Hub 镜像

**官方镜像地址**：`wrbug/polyhermes`

```bash
# ✅ 正确：使用官方镜像
docker pull wrbug/polyhermes:v1.1.2

# ❌ 错误：不要使用其他来源的镜像
# 任何非官方来源的镜像都可能包含恶意代码，导致您的私钥和资产被盗
```

### 🔗 官方渠道

请通过以下**唯一官方渠道**获取 PolyHermes：

* **GitHub 仓库**：https://github.com/WrBug/PolyHermes
* **Twitter**：@polyhermes
* **Telegram 群组**：https://t.me/polyhermes

---

**⭐ 如果这个项目对您有帮助，请给个 Star 支持一下！**

---

# v1.1.1

## 🚀 主要功能

### 🔗 链上 WebSocket 监听优化
- 创建 `UnifiedOnChainWsService` 统一管理 WebSocket 连接，所有服务共享同一个连接
- 创建 `OnChainWsUtils` 工具类，提取公共的链上 WebSocket 相关功能
- 创建 `AccountOnChainMonitorService` 监听账户链上卖出和赎回事件
- 优化 `OnChainWsService`，复用公共代码，减少代码重复
- 支持通过链上 WebSocket 实时监听账户的卖出和赎回交易，自动更新订单状态

### 📊 市场状态查询优化
- 优化市场结算状态查询，优先使用链上查询 `ConditionalTokens.getCondition`
- 如果链上查询失败，自动降级到 Gamma API 查询
- 提供更实时和准确的市场结算结果

### 🔕 自动订单通知优化
- 自动生成的订单（AUTO_、AUTO_FIFO_、AUTO_WS_ 前缀）不再发送 Telegram 通知
- 优化 `OrderStatusUpdateService`，跳过自动生成订单的通知处理
- 减少不必要的通知，提升用户体验

## 🐛 Bug 修复

### 修复移动端 API 健康页面缺少数据显示
- 移动端添加 URL 地址显示
- 移动端添加状态文本显示（正常/异常/未配置）
- 移动端添加消息/状态信息显示
- 移动端和桌面端显示信息保持一致

## 🔧 功能优化

### 优化 Telegram 推送消息格式
- 添加价格和数量截位处理：
  * 价格保留最多4位小数（截断，不四舍五入）
  * 数量保留最多2位小数（截断，不四舍五入）
- 优化账户信息显示格式：
  * 有账户名和钱包地址时显示：账户名(0x123...123)
  * 只有账户名时显示账户名
  * 只有钱包地址时显示脱敏后的地址
  * 都没有时显示未知账户

### 配置优化
- 移除 `polygon.rpc.url` 配置，使用 RpcNodeService 统一管理 RPC 节点
- 删除无用的 `position.push.polling-interval` 和 `position.push.heartbeat-timeout` 配置项
- 修正日志配置中的包名（polyhermes -> polymarketbot）
- 更新 `ApiHealthCheckService` 直接使用 `RpcNodeService.getHttpUrl()`

## 📚 文档更新

- 统一发布说明文件，使用 RELEASE.md 替代版本化文件（RELEASE_v1.0.1.md、RELEASE_v1.1.0.md）
- 更新所有部署文档，移除 POLYGON_RPC_URL 相关说明
- 更新所有 Docker Compose 配置文件，移除 POLYGON_RPC_URL 环境变量
- 更新所有部署脚本，移除 POLYGON_RPC_URL 环境变量定义

## 🔧 技术改进

- 重构链上 WebSocket 服务，提取公共代码到 `OnChainWsUtils`
- 创建统一的 WebSocket 连接管理服务 `UnifiedOnChainWsService`
- 添加链上查询市场结算结果的功能（`BlockchainService.getCondition`）
- 添加 ABI 编码/解码工具方法（`EthereumUtils.decodeConditionResult`）
- 优化代码结构，减少代码重复，提高可维护性

---

# v1.1.0

## 🚀 主要功能

### 🔗 链上 WebSocket 实时监听
- 实现通过 Polygon RPC `eth_subscribe` 实时监听链上交易
- 支持监听 USDC Transfer 和 ERC1155 Transfer 事件
- 实现并行监控策略：链上 WebSocket 和轮询同时运行，哪个数据先返回用哪个
- 支持通过 `eth_unsubscribe` 取消单个 Leader 的订阅，无需重新连接
- 优化 WebSocket 连接管理：只创建一个连接，没有跟单配置时自动取消
- 跟单配置生效/失效时及时更新 WebSocket 订阅
- 使用 Gson 替换所有 JSON 解析，提高解析稳定性
- 添加 Mutex 保证线程安全，防止并发处理导致的数据重复

### 📊 RPC 节点管理
- 实现 RPC 节点管理功能，支持添加、编辑、删除自定义 RPC 节点
- 支持 RPC 节点启用/禁用功能，禁用的节点会被自动忽略
- 前端添加启用/禁用开关，支持实时切换节点状态
- 健康检查只检查启用的节点，提高检查效率
- 节点选择时自动过滤禁用的节点

### 💰 卖出订单价格轮询更新
- 添加 `price_updated` 字段到 `sell_match_record` 表，用于标记价格是否已更新
- 创建 `OrderStatusUpdateService` 定时任务服务，每 5 秒轮询一次：
  - 更新卖出订单的实际成交价（通过 orderId 查询订单详情）
  - 清理已删除账户的订单记录
- 支持加权平均价格计算，处理部分成交的订单
- 添加 orderId 格式验证：非 0x 开头的直接标记为已更新，0x 开头的等待定时任务更新
- 下单完成后不再立即查询价格，直接保存，等待定时任务更新

## 🐛 Bug 修复

### 修复跟单卖出订单的 API 凭证解密问题
- 修复 `processSellTrade` 中 API 凭证未解密的问题，与 `processBuyTrade` 保持一致
- 确保卖出订单能够正常使用 API 凭证进行认证

### 修复 SELL 订单精度问题
- 修复 SELL 订单的 `makerAmount` 和 `takerAmount` 精度问题：
  - `makerAmount` (shares) 最多 2 位小数（符合 API 要求）
  - `takerAmount` (USDC) 最多 4 位小数（符合 API 要求）
- 确保订单能够正常提交到 Polymarket API

## 📚 文档更新

- 添加 Docker 版本更新说明（中英文）
- 添加链上 WebSocket 监听策略文档
- 添加跟单逻辑总结文档
- 更新部署文档，包含详细的版本更新步骤

## 🔧 技术改进

- 使用 Gson 替换 ObjectMapper，提高 JSON 解析稳定性
- `JsonRpcResponse.result` 使用 `JsonElement` 类型，支持灵活的 JSON 结构
- 优化 WebSocket 连接管理，减少不必要的连接
- 添加线程安全机制，使用 Kotlin Coroutines Mutex
- 启用 Spring 定时任务功能（`@EnableScheduling`）

## 📦 数据库变更

- 新增 `price_updated` 字段到 `sell_match_record` 表（Migration: V13）

## 🔗 相关链接

- **GitHub Release**: https://github.com/WrBug/PolyHermes/releases/tag/v1.1.1
- **完整更新日志**: https://github.com/WrBug/PolyHermes/compare/v1.1.0...v1.1.1
- **Docker Hub**: https://hub.docker.com/r/wrbug/polyhermes

## 📊 统计信息

- **文件变更**: 32 个文件
- **代码变更**: +1872 行 / -1503 行
- **主要提交**: 7 个提交

## ⚠️ 重要提醒

**请务必使用官方 Docker 镜像源，避免财产损失！**

### ✅ 官方 Docker Hub 镜像

**官方镜像地址**：`wrbug/polyhermes`

```bash
# ✅ 正确：使用官方镜像
docker pull wrbug/polyhermes:v1.1.1

# ❌ 错误：不要使用其他来源的镜像
# 任何非官方来源的镜像都可能包含恶意代码，导致您的私钥和资产被盗
```

### 🔗 官方渠道

请通过以下**唯一官方渠道**获取 PolyHermes：

* **GitHub 仓库**：https://github.com/WrBug/PolyHermes
* **Twitter**：@polyhermes
* **Telegram 群组**：https://t.me/polyhermes

---

**⭐ 如果这个项目对您有帮助，请给个 Star 支持一下！**


