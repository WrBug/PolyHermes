---
name: check-i18n-keys
description: 检查前端多语言 key 完整性。当用户要求检查 i18n、多语言缺失、翻译 key 或运行 check-i18n 时使用。
---

# Check I18n Keys

检查前端代码中使用的 i18n key 是否在所有语言文件（zh-CN、zh-TW、en）中存在，并报告缺失或语言间不一致的 key。

## 使用时机

- 用户要求「检查多语言」「检查 i18n」「扫一下 key」「多语言缺失」时
- 用户要求运行多语言检查或执行 check-i18n 时
- 在修改或新增前端文案后，需要确认三语言 key 一致时

## 指令

1. **运行检查脚本**（在项目仓库根目录下执行）：
   ```bash
   cd .cursor/skills/frontend/check-i18n-keys/scripts && npm install && npm run check-i18n
   ```
   首次运行需先 `npm install`，之后可直接 `npm run check-i18n`。

2. **脚本行为**：
   - 扫描 `frontend/` 下所有 `.ts`、`.tsx`、`.js`、`.jsx`（排除 node_modules、dist、build、*.d.ts）
   - 提取代码中 `t('key')` / `t("key")` 的 key
   - 与 `frontend/src/locales/{zh-CN,zh-TW,en}/common.json` 对比
   - 报告：代码中使用但某语言 JSON 缺失的 key；某语言有而另一语言没有的 key（不一致）

3. **结果处理**：
   - 若有缺失或不一致，退出码为 1；可根据报告在对应 `common.json` 中补全 key
   - 脚本路径：`scripts/check-i18n-keys.ts`（相对本 skill 根目录）

## 可选目录说明

- `scripts/`：可执行检查脚本，Agent 按上述命令调用。
