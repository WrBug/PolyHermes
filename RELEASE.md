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
* **Telegram 群组**：加入群组

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
* **Telegram 群组**：加入群组

---

**⭐ 如果这个项目对您有帮助，请给个 Star 支持一下！**


