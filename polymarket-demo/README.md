# Polymarket JS SDK Demo

这是一个用于测试 Polymarket JS SDK 的演示目录。

## 安装依赖

```bash
npm install
```

或者使用 yarn:

```bash
yarn install
```

## 配置

在使用脚本之前，需要修改 `src/createOrder.ts` 文件中的以下配置：

1. **funder**: 你的 Polymarket 账户地址（在个人资料图片下方显示的地址）
2. **signer**: 你的私钥
   - 如果使用邮箱登录，从 https://reveal.magic.link/polymarket 导出
   - 如果使用 Web3 应用，从你的钱包导出
3. **tokenID**: 要交易的代币 ID
   - 使用 https://docs.polymarket.com/developers/gamma-markets-api/get-markets 获取示例代币
   - 示例代币: `114304586861386186441621124384163963092522056897081085884483958561365015034812` (Xi Jinping out in 2025, YES side)
4. **tickSize** 和 **negRisk**: 根据市场调整这些参数，从 get-markets API 获取

## 运行测试脚本

```bash
npm run test
```

或者直接使用 ts-node:

```bash
npx ts-node src/createOrder.ts
```

## 编译

```bash
npm run build
```

编译后的文件将输出到 `dist` 目录。

## 运行编译后的文件

```bash
npm start
```

## 注意事项

- **不要创建新的 API key**，始终使用 `createOrDerive` 方法
- **signatureType** 说明：
  - `1`: Magic/Email 登录
  - `2`: 浏览器钱包 (Metamask, Coinbase Wallet 等)
  - `0`: EOA (如果你不知道这是什么，说明你不在使用它)
- 确保你的账户有足够的 USDC 余额
- 在生产环境中使用前，请确保妥善保管私钥

