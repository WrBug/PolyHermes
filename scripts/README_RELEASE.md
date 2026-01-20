# Release 创建脚本使用说明

## 简介

`create-release.sh` 脚本用于快速创建 GitHub Release，包括：
- 创建本地和远程 tag
- 创建 GitHub Release 页面
- 支持 Pre-release 标记
- 自动触发 GitHub Actions 构建流程

## 前置要求

### 1. 安装 GitHub CLI

脚本依赖 GitHub CLI (`gh`) 来创建 Release。

**macOS**:
```bash
brew install gh
```

**Linux**:
```bash
# Ubuntu/Debian
sudo apt install gh

# 或从官网下载安装
# https://cli.github.com/
```

**验证安装**:
```bash
gh --version
```

### 2. 登录 GitHub

首次使用需要登录 GitHub：

```bash
gh auth login
```

按照提示完成认证。

**验证登录状态**:
```bash
gh auth status
```

### 3. 确保 Git 仓库配置正确

```bash
# 检查远程仓库
git remote -v

# 确保在正确的分支
git checkout main  # 或 master
```

## 使用方法

### 基本用法

```bash
# 在项目根目录下运行脚本

# 创建正式版本
./create-release.sh -t v1.0.1 -T "Release v1.0.1" -d "## 新功能\n- 功能1\n- 功能2"

# 创建 Pre-release（自动拼接 -beta）
./create-release.sh -t v1.0.1 -T "Release v1.0.1-beta" -d "测试版本" --prerelease
```

### 参数说明

| 参数 | 简写 | 说明 | 必需 | 默认值 |
|------|------|------|------|--------|
| `--tag TAG` | `-t` | 版本号 tag（格式：v1.0.0） | ✅ 是 | - |
| `--title TITLE` | `-T` | Release 标题 | ❌ 否 | 使用 tag 值 |
| `--description DESC` | `-d` | Release 描述内容 | ❌ 否 | "Release {tag}" |
| `--description-file FILE` | `-f` | 从文件读取 Release 描述 | ❌ 否 | - |
| `--prerelease` | `-p` | 标记为 Pre-release | ❌ 否 | false |
| `--help` | `-h` | 显示帮助信息 | ❌ 否 | - |

### 版本号格式

**正式版本**:
- ✅ `v1.0.0`
- ✅ `v2.10.102`
- ✅ `v1.0.1`

**Pre-release**:
- ✅ `v1.0.1-beta`
- ✅ `v1.0.1-rc.1`
- ✅ `v1.0.1-alpha`

**错误格式**:
- ❌ `v1.0`（缺少补丁号）
- ❌ `1.0.0`（缺少 v 前缀）
- ❌ `v1.0.0.1`（版本号过多）

## 使用场景示例

### 场景 1: 创建正式版本 Release

```bash
./create-release.sh \
  -t v1.0.1 \
  -T "Release v1.0.1" \
  -d "## 新功能
- 添加了系统更新功能
- 优化了跟单性能

## 修复
- 修复了订单状态同步问题
- 修复了账户余额显示错误"
```

### 场景 2: 创建 Pre-release（测试版本）

```bash
# 使用 --prerelease 参数，会自动拼接 -beta 后缀
./create-release.sh \
  -t v1.0.1 \
  -T "Release v1.0.1-beta (测试版)" \
  -d "这是 v1.0.1 的测试版本，请勿用于生产环境" \
  --prerelease
# 实际创建的 tag: v1.0.1-beta（自动拼接）
```

### 场景 3: 从文件读取 Release 描述

如果你的 Release 描述内容很长，可以保存在文件中：

```bash
# 创建描述文件
cat > release-notes.txt << 'EOF'
## 新功能
- 添加了系统更新功能
- 优化了跟单性能

## 修复
- 修复了订单状态同步问题
- 修复了账户余额显示错误

## 改进
- 提升了 API 响应速度
- 优化了数据库查询性能
EOF

# 使用文件创建 Release
./create-release.sh \
  -t v1.0.1 \
  -T "Release v1.0.1" \
  -f release-notes.txt
```

### 场景 4: 结合 AI 生成 Release 内容

你可以让 AI 对比代码差异生成 Release 描述，然后使用脚本发布：

```bash
# 1. AI 生成 Release 内容到文件
# 例如：对比 v1.0.0 和最新代码，生成 v1.0.1 的更新内容
# 保存到 CHANGELOG.md

# 2. 使用脚本创建 Release
./create-release.sh \
  -t v1.0.1 \
  -T "Release v1.0.1" \
  -f CHANGELOG.md
```

## 工作流程

脚本执行时会按以下步骤进行：

1. **验证参数**
   - 检查版本号格式
   - 检查必需参数

2. **检查环境**
   - 检查 git 命令
   - 检查 GitHub CLI
   - 检查 GitHub 登录状态
   - 检查未提交的更改

3. **检查 Tag**
   - 检查本地是否已存在 tag
   - 检查远程是否已存在 tag
   - 如果存在，询问是否删除重建

4. **确认操作**
   - 显示即将执行的操作信息
   - 等待用户确认

5. **创建 Tag**
   - 基于当前 HEAD 创建 tag
   - 推送 tag 到远程

6. **创建 Release**
   - 使用 GitHub CLI 创建 Release
   - 设置标题和描述
   - 标记是否为 Pre-release

7. **触发构建**
   - GitHub Actions 自动检测到新 Release
   - 开始构建 Docker 镜像和更新包

## 注意事项

### 1. Tag 和 Release 的关系

- 脚本会**先创建 tag**，然后**创建 Release**
- GitHub Release **必须关联一个 tag**
- 如果 tag 已存在，会询问是否删除重建

### 2. Pre-release 的影响

- Pre-release **不会**触发 Telegram 通知
- Pre-release **不会**推送到 `latest` Docker 标签
- Pre-release **不会**被更新服务检测（除非设置 `ALLOW_PRERELEASE=true`）

### 3. GitHub Actions 触发

- GitHub Actions 监听 `release: published` 事件
- 只有通过脚本或 GitHub 页面创建 Release 才会触发
- 直接 `git push` tag **不会**触发构建

### 4. 发布前检查清单

在创建 Release 前，建议检查：

- ✅ 代码已提交并推送
- ✅ 所有测试通过
- ✅ 版本号遵循语义化版本规范
- ✅ Release 描述内容准确完整
- ✅ 确认是否为 Pre-release

## 故障排除

### 问题 1: GitHub CLI 未安装

**错误信息**:
```
未找到 GitHub CLI (gh) 命令
```

**解决方案**:
```bash
# macOS
brew install gh

# 然后登录
gh auth login
```

### 问题 2: GitHub 未登录

**错误信息**:
```
未登录 GitHub，请先运行: gh auth login
```

**解决方案**:
```bash
gh auth login
```

### 问题 3: Tag 已存在

**错误信息**:
```
Tag v1.0.1 已存在（本地）
```

**解决方案**:
- 脚本会询问是否删除重建
- 回答 `y` 继续，或回答 `n` 取消

### 问题 4: 版本号格式错误

**错误信息**:
```
版本号格式不正确：v1.0
```

**解决方案**:
- 确保版本号格式为 `v数字.数字.数字` 或 `v数字.数字.数字-后缀`
- 例如：`v1.0.0`, `v1.0.1-beta`

### 问题 5: 未提交的更改

**错误信息**:
```
检测到未提交的更改，建议先提交或暂存
```

**解决方案**:
```bash
# 提交更改
git add .
git commit -m "准备发布 v1.0.1"

# 或暂存更改
git stash
```

## 完整示例

假设当前线上版本是 `v1.0.0`，要发布 `v1.0.1`：

```bash
# 1. 确保代码已提交
git add .
git commit -m "准备发布 v1.0.1"
git push

# 2. 使用脚本创建 Release（正式版本）
./create-release.sh \
  -t v1.0.1 \
  -T "Release v1.0.1" \
  -d "## 新功能
- 添加了动态更新功能
- 优化了跟单统计性能

## 修复
- 修复了订单状态同步延迟问题
- 修复了账户余额显示错误

## 改进
- 提升了 API 响应速度 30%
- 优化了数据库查询性能"

# 3. 等待 GitHub Actions 构建完成
# 可以在 GitHub Actions 页面查看构建进度
```

**创建 Pre-release**:

```bash
# 使用 --prerelease 参数，会自动拼接 -beta 后缀
./create-release.sh \
  -t v1.0.1 \
  -T "Release v1.0.1-beta (测试版)" \
  -d "这是 v1.0.1 的测试版本，包含以下更新：
- 添加了动态更新功能
- 修复了订单状态同步问题

请勿用于生产环境。" \
  --prerelease
# 实际创建的 tag: v1.0.1-beta
```

## 相关文档

- [版本号管理说明](../docs/zh/VERSION_MANAGEMENT.md)
- [动态更新技术方案](../docs/zh/DYNAMIC_UPDATE.md)
- [GitHub Actions 配置](../.github/workflows/docker-build.yml)

