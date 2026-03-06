# 体育尾盘策略 - API 设计

## 一、后端 API

### 1.1 策略管理

#### 列表
```
POST /api/sports-tail-strategy/list
```

**请求**：
```typescript
interface StrategyListRequest {
  accountId?: number;    // 筛选账户
  sport?: string;        // 筛选类别
}
```

**响应**：
```typescript
interface StrategyListResponse {
  list: StrategyDto[];
}

interface StrategyDto {
  id: number;
  accountId: number;
  accountName: string;
  conditionId: string;
  marketTitle: string;
  eventSlug: string;
  triggerPrice: string;
  amountMode: "FIXED" | "RATIO";
  amountValue: string;
  takeProfitPrice: string | null;
  stopLossPrice: string | null;
  
  // 成交信息
  filled: boolean;
  filledPrice: string | null;
  filledOutcomeIndex: number | null;
  filledOutcomeName: string | null;
  filledAmount: string | null;
  filledShares: string | null;
  filledAt: number | null;
  
  // 卖出信息
  sold: boolean;
  sellPrice: string | null;
  sellType: string | null;
  sellAmount: string | null;
  realizedPnl: string | null;
  soldAt: number | null;
  
  // 实时价格（未成交时返回）
  realtimeYesPrice: string | null;
  realtimeNoPrice: string | null;
  
  createdAt: number;
  updatedAt: number;
}
```

#### 创建
```
POST /api/sports-tail-strategy/create
```

**请求**：
```typescript
interface StrategyCreateRequest {
  accountId: number;           // 账户ID
  conditionId: string;         // 市场ID
  marketTitle: string;         // 市场标题
  eventSlug?: string;          // 事件slug
  triggerPrice: string;        // 触发价格
  amountMode: "FIXED" | "RATIO";
  amountValue: string;         // 金额值
  takeProfitPrice?: string;    // 止盈价格
  stopLossPrice?: string;      // 止损价格
}
```

**响应**：
```typescript
interface StrategyCreateResponse {
  id: number;
}
```

#### 删除
```
POST /api/sports-tail-strategy/delete
```

**请求**：
```typescript
interface StrategyDeleteRequest {
  id: number;
}
```

**响应**：
```typescript
interface StrategyDeleteResponse {
  success: boolean;
}
```

---

### 1.2 市场数据

#### 体育类别列表
```
POST /api/sports-tail-strategy/sports-list
```

**响应**：
```typescript
interface SportsListResponse {
  list: SportDto[];
}

interface SportDto {
  sport: string;      // 类别标识：nba, nfl, epl...
  image: string;      // 图标URL
  tagId: number;      // 主Tag ID
  name: string;       // 显示名称（多语言）
}
```

#### 市场搜索
```
POST /api/sports-tail-strategy/market-search
```

**请求**：
```typescript
interface MarketSearchRequest {
  sport?: string;              // 体育类别
  endDateMin?: string;         // 最小结束时间 ISO 8601
  endDateMax?: string;         // 最大结束时间 ISO 8601
  minLiquidity?: string;       // 最小流动性
  keyword?: string;            // 搜索关键词
  limit?: number;              // 返回数量，默认50
}
```

**响应**：
```typescript
interface MarketSearchResponse {
  list: MarketDto[];
}

interface MarketDto {
  conditionId: string;
  question: string;
  outcomes: string[];           // ["Yes", "No"] 或 ["Over", "Under"]
  outcomePrices: string[];     // 当前价格
  endDate: string;             // 结束时间 ISO 8601
  liquidity: string;           // 流动性
  bestBid: number | null;
  bestAsk: number | null;
  yesTokenId: string;
  noTokenId: string;
}
```

#### 市场详情
```
POST /api/sports-tail-strategy/market-detail
```

**请求**：
```typescript
interface MarketDetailRequest {
  conditionId: string;
}
```

**响应**：
```typescript
interface MarketDetailResponse {
  conditionId: string;
  question: string;
  outcomes: string[];
  outcomePrices: string[];
  endDate: string;
  liquidity: string;
  bestBid: number | null;
  bestAsk: number | null;
  yesTokenId: string;
  noTokenId: string;
  eventSlug: string | null;
}
```

---

### 1.3 触发记录

#### 全局记录列表
```
POST /api/sports-tail-strategy/triggers
```

**请求**：
```typescript
interface TriggerListRequest {
  accountId?: number;       // 筛选账户
  status?: string;          // 筛选状态: SUCCESS/FAIL
  startTime?: number;       // 开始时间戳
  endTime?: number;         // 结束时间戳
  page?: number;            // 页码，默认1
  pageSize?: number;        // 每页数量，默认20
}
```

**响应**：
```typescript
interface TriggerListResponse {
  total: number;
  list: TriggerDto[];
}

interface TriggerDto {
  id: number;
  strategyId: number;
  
  // 市场信息
  marketTitle: string;
  conditionId: string;
  
  // 买入信息
  buyPrice: string;
  outcomeIndex: number;
  outcomeName: string | null;
  buyAmount: string;
  buyShares: string | null;
  buyStatus: "PENDING" | "SUCCESS" | "FAIL";
  
  // 卖出信息
  sellPrice: string | null;
  sellType: string | null;      // TAKE_PROFIT/STOP_LOSS/MANUAL
  sellAmount: string | null;
  sellStatus: string | null;
  
  // 盈亏
  realizedPnl: string | null;
  
  // 时间
  triggeredAt: number;
  soldAt: number | null;
}
```

---

## 二、前端 API 封装

### 2.1 apiService 方法

```typescript
// 策略管理
sportsTailStrategyList(params: StrategyListRequest): Promise<StrategyListResponse>
sportsTailStrategyCreate(data: StrategyCreateRequest): Promise<StrategyCreateResponse>
sportsTailStrategyDelete(id: number): Promise<StrategyDeleteResponse>

// 市场数据
sportsTailStrategySportsList(): Promise<SportsListResponse>
sportsTailStrategyMarketSearch(params: MarketSearchRequest): Promise<MarketSearchResponse>
sportsTailStrategyMarketDetail(conditionId: string): Promise<MarketDetailResponse>

// 触发记录
sportsTailStrategyTriggers(params: TriggerListRequest): Promise<TriggerListResponse>
```

---

## 三、多语言 Key

### 3.1 页面标题
```
sportsTailStrategy.list.title=体育尾盘策略
sportsTailStrategy.list.addStrategy=新增策略
sportsTailStrategy.list.filter.account=账户
sportsTailStrategy.list.filter.sport=类别
sportsTailStrategy.list.filter.all=全部
```

### 3.2 表单字段
```
sportsTailStrategy.form.account=账户
sportsTailStrategy.form.market=市场
sportsTailStrategy.form.triggerPrice=触发价格
sportsTailStrategy.form.amount=金额
sportsTailStrategy.form.amountMode=金额模式
sportsTailStrategy.form.fixed=固定金额
sportsTailStrategy.form.ratio=余额比例
sportsTailStrategy.form.takeProfit=止盈价格
sportsTailStrategy.form.stopLoss=止损价格
sportsTailStrategy.form.autoSell=自动卖出
```

### 3.3 列表字段
```
sportsTailStrategy.list.triggerPrice=触发价
sportsTailStrategy.list.amount=金额
sportsTailStrategy.list.takeProfitStopLoss=止盈/止损
sportsTailStrategy.list.filledPrice=成交价
sportsTailStrategy.list.shares=份
sportsTailStrategy.list.pnl=盈亏
sportsTailStrategy.list.realtimePrice=实时价格
sportsTailStrategy.list.pending=待结算
sportsTailStrategy.list.viewRecords=查看记录
sportsTailStrategy.list.delete=删除
```

### 3.4 市场筛选
```
sportsTailStrategy.market.filter.sport=类别
sportsTailStrategy.market.filter.allSports=全部类别
sportsTailStrategy.market.filter.endTime=结束时间
sportsTailStrategy.market.filter.today=今天
sportsTailStrategy.market.filter.next24h=未来24小时
sportsTailStrategy.market.filter.next7days=未来7天
sportsTailStrategy.market.filter.minLiquidity=最小流动性
sportsTailStrategy.market.filter.keyword=关键词
sportsTailStrategy.market.filter.search=搜索
sportsTailStrategy.market.select=选择市场
```

### 3.5 触发记录
```
sportsTailStrategy.records.title=触发记录
sportsTailStrategy.records.market=市场
sportsTailStrategy.records.direction=方向
sportsTailStrategy.records.buyPrice=买入价
sportsTailStrategy.records.buyAmount=买入金额
sportsTailStrategy.records.sellPrice=卖出价
sportsTailStrategy.records.sellType=卖出类型
sportsTailStrategy.records.pnl=盈亏
sportsTailStrategy.records.time=时间
sportsTailStrategy.records.status=状态
```

### 3.6 消息提示
```
sportsTailStrategy.message.createSuccess=策略创建成功
sportsTailStrategy.message.deleteSuccess=策略删除成功
sportsTailStrategy.message.deleteConfirm=确定删除该策略吗？
sportsTailStrategy.message.noMarketSelected=请选择市场
sportsTailStrategy.message.invalidPrice=价格格式无效
```
