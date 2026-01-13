# 订单推送功能测试指南

## 测试准备

### 1. 确保有测试账户
- 账户必须已导入到系统
- 账户必须有有效的 API Key、Secret 和 Passphrase
- 可以通过 `/api/accounts/import` 接口导入账户

### 2. 启动后端服务
```bash
cd backend
./gradlew bootRun
```

### 3. 检查日志
启动后，应该看到以下日志：
- `订单推送服务已初始化`
- `已为账户 X (账户名) 建立 User Channel 连接并发送订阅消息`

## 测试步骤

### 测试 1: 检查服务初始化
1. 启动后端服务
2. 查看日志，确认 `OrderPushService` 已初始化
3. 确认所有有 API 凭证的账户都已建立连接

**预期结果**:
- 日志显示：`订单推送服务已初始化`
- 对于每个有 API 凭证的账户，日志显示：`已为账户 X 建立 User Channel 连接并发送订阅消息`

### 测试 2: 通过 WebSocket 订阅订单推送

#### 2.1 使用 WebSocket 客户端连接
```bash
# 使用 wscat 或其他 WebSocket 客户端
wscat -c ws://localhost:8000/ws
```

#### 2.2 发送订阅消息
```json
{
  "type": 1,
  "channel": "order",
  "payload": {
    "accountId": 1
  }
}
```

**预期结果**:
- 收到订阅确认消息：
```json
{
  "type": 4,
  "channel": "order",
  "status": 0,
  "message": null
}
```

#### 2.3 等待订单消息
当账户有订单活动时（下单、更新、取消），应该收到推送消息：
```json
{
  "type": 3,
  "channel": "order",
  "payload": {
    "accountId": 1,
    "accountName": "测试账户",
    "order": {
      "assetId": "...",
      "eventType": "order",
      "id": "...",
      "market": "...",
      "type": "PLACEMENT",
      "side": "BUY",
      "price": "0.5",
      "originalSize": "10",
      "sizeMatched": "0",
      ...
    },
    "timestamp": 1234567890
  },
  "timestamp": 1234567890
}
```

### 测试 3: 测试订单类型

#### 3.1 PLACEMENT (下单)
- 在 Polymarket 上创建一个订单
- 应该收到 `type: "PLACEMENT"` 的订单消息

#### 3.2 UPDATE (订单更新)
- 订单部分成交时
- 应该收到 `type: "UPDATE"` 的订单消息，`sizeMatched` 字段会更新

#### 3.3 CANCELLATION (订单取消)
- 取消一个订单
- 应该收到 `type: "CANCELLATION"` 的订单消息

### 测试 4: 多账户支持
1. 导入多个账户（都有 API 凭证）
2. 订阅不同账户的订单推送
3. 验证每个账户的订单消息都能正确推送

### 测试 5: 连接重连
1. 断开网络连接
2. 恢复网络连接
3. 验证连接是否自动重连（需要实现重连逻辑）

### 测试 6: 代理支持
如果配置了代理：
```bash
export ENABLE_PROXY=1
export PROXY_HOST=127.0.0.1
export PROXY_PORT=8888
```

启动服务后，应该看到日志：
- `已配置 WebSocket 代理: 127.0.0.1:8888`

## 常见问题排查

### 问题 1: 连接建立失败
**可能原因**:
- Polymarket RTDS 服务不可用
- 网络连接问题
- 代理配置错误

**排查步骤**:
1. 检查 Polymarket RTDS WebSocket URL（现在使用代码常量 `PolymarketConstants.RTDS_WS_URL`）
2. 检查网络连接
3. 查看详细错误日志

### 问题 2: 订阅消息发送失败
**可能原因**:
- 连接未完全建立就发送消息
- API 凭证无效

**排查步骤**:
1. 检查日志中的连接状态
2. 验证 API 凭证是否正确
3. 增加连接建立的等待时间

### 问题 3: 收不到订单消息
**可能原因**:
- 账户没有订单活动
- 订阅消息格式错误
- API 凭证权限不足

**排查步骤**:
1. 在 Polymarket 上手动创建一个订单
2. 检查订阅消息格式是否正确
3. 验证 API Key 是否有订单查询权限

### 问题 4: 账户 ID 为 null
**可能原因**:
- 账户未正确保存到数据库
- 账户 ID 未正确传递

**排查步骤**:
1. 检查数据库中的账户记录
2. 验证订阅消息中的 accountId 是否正确

## 日志关键字

搜索以下关键字查看相关日志：
- `订单推送服务已初始化`
- `已为账户 X 建立 User Channel 连接`
- `已发送 User Channel 订阅消息`
- `处理订单消息失败`
- `推送订单消息失败`

## 下一步

测试通过后，可以：
1. 实现前端订阅和 Notification 显示
2. 添加连接重连逻辑
3. 优化错误处理
4. 添加单元测试

