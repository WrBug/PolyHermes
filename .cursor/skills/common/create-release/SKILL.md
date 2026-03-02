---
name: create-release
description: 创建 PolyHermes 项目的 GitHub Release。当用户要求发布版本、创建 release、打 tag 或发布新版本时使用。
---

# Create Release

创建 PolyHermes 项目的 GitHub Release，包括创建 Git tag、推送 tag、创建 GitHub Release（支持 pre-release），并自动在 Issue #1 发布公告。

## 使用时机

- 用户要求「发布版本」「创建 release」「打 tag」「发布新版本」时
- 用户要求「创建 pre-release」「beta 版本」时
- 用户提到「v1.x.x」等版本号相关操作时

## 前置条件

1. **GitHub CLI 已安装**：确保 `gh` 命令可用
2. **已登录 GitHub**：运行 `gh auth status` 确认
3. **工作目录干净**：建议先提交所有更改

## 指令

### 步骤 1：收集发布信息

询问用户以下信息：
- 版本号（格式：vX.Y.Z，如 v1.0.0）
- 是否为 Pre-release（测试版本）
- Release 标题和描述（可选，如未提供则自动生成）

### 步骤 2：生成 Release 内容

**重要**：如果用户未提供描述，需要根据 Git commits 自动生成。

1. **获取上一个版本的 tag**：
   ```bash
   git describe --tags --abbrev=0 HEAD
   ```

2. **获取版本间的 commits**：
   ```bash
   git log <PREVIOUS_TAG>..HEAD --oneline --no-merges
   ```

3. **过滤 commit 规则**：
   - **排除**：版本内新增功能的修复 commit
   - **判断方法**：如果一个 commit 的消息包含「fix」「修复」「bugfix」等关键词，且是针对同一版本内新增代码的修复，则不包含
   - **保留**：新功能、性能优化、重构、文档更新等

4. **生成中英文 Release 内容**：
   - 格式要求：**中文在上，英文在下**
   - 使用分隔线 `---` 分隔中英文部分
   - 按功能类型分组（新功能、改进、修复等）

   示例格式：
   ```markdown
   ## 新功能

   - 添加了 A 功能
   - 支持了 B 操作

   ## 改进

   - 优化了 C 性能

   ---

   ## New Features

   - Added feature A
   - Supported operation B

   ## Improvements

   - Optimized performance C
   ```

### 步骤 3：运行发布脚本

在项目根目录下执行：

```bash
cd .cursor/skills/common/create-release/scripts && \
chmod +x create-release.sh && \
./create-release.sh -t <VERSION> [-T "<TITLE>"] [-d "<DESCRIPTION>"] [-p] [-y]
```

### 步骤 4：发布公告到 Issue #1

**重要**：Release 创建成功后，必须自动在 Issue #1 下发布公告 comment。

1. **生成公告内容**（面向用户，通俗易懂）：

   公告格式模板：
   ```markdown
   # 🎉 PolyHermes vX.X.X 版本发布公告

   ## 📅 发布日期

   YYYY年MM月DD日

   ---

   ## ✨ 本次更新亮点

   ### 🚀 新功能

   **功能名称**
   - 用通俗的语言描述这个功能是什么
   - 用户能从中获得什么好处
   - 如何使用这个功能

   ### 🔧 改进优化

   - 优化了 XXX，现在 XXX 更快/更稳定了
   - 改进了 XXX 体验，操作更简单了

   ### 🐛 问题修复

   - 修复了 XXX 问题，不再出现 XXX 情况

   ---

   ## 📦 如何更新

   ### Docker 部署（推荐）

   ```bash
   # 拉取最新镜像
   docker pull wrbug/polyhermes:vX.X.X

   # 重启服务
   docker-compose -f docker-compose.prod.yml down
   docker-compose -f docker-compose.prod.yml up -d
   ```

   ---

   ## ⚠️ 安全提醒

   **请务必使用官方 Docker 镜像源，避免财产损失！**

   **官方镜像地址**：`wrbug/polyhermes`

   ---

   ## 📚 相关链接

   - **GitHub Release**: https://github.com/WrBug/PolyHermes/releases/tag/vX.X.X
   - **Docker Hub**: https://hub.docker.com/r/wrbug/polyhermes
   ```

2. **公告内容编写原则**：

   - ✅ **通俗易懂**：避免技术术语，用用户能理解的语言
   - ✅ **突出价值**：告诉用户这个更新对他们有什么好处
   - ✅ **简洁明了**：每个功能点用 1-2 句话说明
   - ✅ **包含操作指引**：告诉用户如何使用新功能
   - ✅ **中英文双语**：中文在上，英文在下（可选）
   - ❌ **避免**：commit hash、代码细节、内部实现

3. **执行发布公告命令**：

   ```bash
   gh issue comment 1 --repo WrBug/PolyHermes --body "$(cat <<'EOF'
   # 🎉 PolyHermes vX.X.X 版本发布公告

   [公告内容...]

   ---
   EOF
   )"
   ```

   或使用文件方式（内容较长时推荐）：

   ```bash
   echo "[公告内容...]" > /tmp/announcement.md
   gh issue comment 1 --repo WrBug/PolyHermes --body-file /tmp/announcement.md
   ```

### 参数说明

| 参数 | 说明 | 示例 |
|------|------|------|
| `-t, --tag` | 版本号（必需） | `-t v1.0.0` |
| `-T, --title` | Release 标题 | `-T "Release v1.0.0"` |
| `-d, --description` | Release 描述 | `-d "## 新功能\n- 功能1"` |
| `-f, --description-file` | 从文件读取描述 | `-f CHANGELOG.md` |
| `-p, --prerelease` | 标记为 Pre-release（自动加 -beta 后缀） | `-p` |
| `-y, --yes` | 无交互模式 | `-y` |

## 版本号格式

- 必须格式：`v数字.数字.数字`（如 v1.0.0, v1.10.2, v1.1.12）
- 如果指定 `--prerelease`，会自动拼接 `-beta` 后缀（如 v1.0.1 → v1.0.1-beta）

## Release 内容生成规则

### 中英文格式

Release 描述**必须**使用中英文双语格式：

```markdown
## 中文标题

- 内容项1
- 内容项2

---

## English Title

- Item 1
- Item 2
```

### Commit 过滤规则

**需要排除的 commit 类型**：

1. **版本内修复**：对同一版本新增功能的后续修复
   - 例如：v1.0.1 新增了功能 A，然后有一个 commit 修复功能 A 的 bug → 不包含
   - 判断依据：commit 消息包含「fix」「修复」「bugfix」且相关功能在本版本新增

2. **琐碎修改**：
   - typo 修正
   - 代码格式调整
   - 注释更新

**需要保留的 commit 类型**：

1. 新功能（feat、feature）
2. 改进/优化（improve、optimize、enhance）
3. 重要 bug 修复（针对旧版本的 bug）
4. 重构（refactor）
5. 文档更新（docs）

### 分组建议

- **新功能 / New Features**
- **改进 / Improvements**
- **修复 / Bug Fixes**（仅包含对旧版本 bug 的修复）
- **其他 / Others**

## 公告内容示例

以下是一个面向用户的公告示例：

```markdown
# 🎉 PolyHermes v1.2.0 版本发布公告

## 📅 发布日期

2026年3月2日

---

## ✨ 本次更新亮点

### 🚀 新功能

**系统自动更新**
- 现在可以在网页上直接更新系统，无需手动重启 Docker
- 更新过程约 30-60 秒，系统会自动处理
- 如果更新失败，系统会自动恢复到旧版本

**RPC 节点管理**
- 可以在系统设置中添加、编辑、删除自定义 RPC 节点
- 可以随时启用或禁用节点
- 系统会自动选择可用的节点

### 🔧 改进优化

- **更快的跟单响应**：通过实时监听链上交易，跟单速度提升到秒级
- **更准确的盈亏统计**：系统会自动追踪实际成交价，统计数据更准确
- **内存占用优化**：修复了内存泄漏问题，系统可以长时间稳定运行

### 🐛 问题修复

- 修复了部分市场无法正确查询价格的问题
- 修复了卖出订单偶发失败的问题

---

## 📦 如何更新

### 方式一：网页更新（推荐）

1. 登录系统，进入 **系统设置** → **系统更新**
2. 点击 **检查更新**
3. 如果有新版本，点击 **立即升级**
4. 等待更新完成即可

### 方式二：Docker 更新

```bash
docker pull wrbug/polyhermes:v1.2.0
docker-compose -f docker-compose.prod.yml down
docker-compose -f docker-compose.prod.yml up -d
```

---

## ⚠️ 安全提醒

**请务必使用官方 Docker 镜像源！**

官方镜像：`wrbug/polyhermes`

---

## 📚 相关链接

- **GitHub Release**: https://github.com/WrBug/PolyHermes/releases/tag/v1.2.0
- **Docker Hub**: https://hub.docker.com/r/wrbug/polyhermes
```

## 发布流程

1. 验证版本号格式
2. 检查 Git 工作目录状态
3. 检查 tag 是否已存在
4. 生成 Release 内容（中英文）
5. 创建本地 tag
6. 推送 tag 到远程
7. 创建 GitHub Release
8. **生成面向用户的公告内容**
9. **发布公告到 Issue #1**
10. 返回 Release URL 和公告链接

## 注意事项

- Pre-release 版本不会触发 Telegram 通知
- GitHub Actions 会自动触发构建流程
- 如果 tag 已存在，会提示是否删除并重新创建
- **必须**在 Release 创建成功后发布公告到 Issue #1

## 可选目录说明

- `scripts/`：包含 `create-release.sh` 发布脚本
