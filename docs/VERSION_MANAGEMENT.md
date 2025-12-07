# 版本号管理说明

## 概述

本项目支持自动版本号管理和显示。当在 GitHub 创建 release tag 时，会自动触发 GitHub Actions 构建 Docker 镜像并推送到 Docker Hub，同时在前端标题后显示版本号。

## 功能特性

1. **自动构建**：创建 release tag 时自动触发 GitHub Actions
2. **版本号显示**：前端标题后显示版本号（小字号）
3. **点击跳转**：点击版本号跳转到对应的 GitHub tag 页面
4. **Docker 推送**：自动构建并推送到 Docker Hub
5. **自动删除**：删除 release 时自动删除对应的 Docker 镜像标签
6. **版本号验证**：精准匹配版本号格式 `v数字.数字.数字` 或 `v数字.数字.数字-后缀`（例如：`v1.0.0`, `v1.0.0-beta`）
7. **独立脚本**：构建和删除功能分离到不同的 workflow 文件，便于管理和维护

## Workflow 文件说明

项目使用两个独立的 GitHub Actions workflow 文件：

- **`.github/workflows/docker-build.yml`**：负责构建和推送 Docker 镜像
  - 触发条件：`release: published`（创建 release 时）
  - 功能：提取版本号、构建多架构镜像、推送到 Docker Hub

- **`.github/workflows/docker-delete.yml`**：负责删除 Docker 镜像
  - 触发条件：`release: deleted`（删除 release 时）
  - 功能：验证版本号格式、删除对应的 Docker 镜像标签

## 使用方法

### 1. 配置 Docker Hub 凭证

在 GitHub 仓库设置中添加以下 Secrets：

- `DOCKER_USERNAME`: Docker Hub 用户名（例如：`wrbug`）
- `DOCKER_PASSWORD`: Docker Hub 访问令牌（推荐）或密码

**设置步骤**：

1. **创建 Docker Hub Access Token**（推荐）：
   - 访问：https://hub.docker.com/settings/security
   - 点击 "New Access Token"
   - 填写描述（如：`GitHub Actions PolyHermes`）
   - **重要**：勾选以下权限：
     - ✅ `Read & Write`（用于推送镜像）
     - ✅ `Delete repository tags`（用于删除镜像）
   - 点击 "Generate"
   - **立即复制令牌**（只显示一次）

2. **在 GitHub 中添加 Secrets**：
   - 访问 GitHub 仓库 → Settings → Secrets and variables → Actions
   - 点击 "New repository secret"
   - 添加 `DOCKER_USERNAME`：你的 Docker Hub 用户名
   - 添加 `DOCKER_PASSWORD`：刚才创建的 Access Token（不是密码）

**注意**：
- ⚠️ 如果使用密码而不是 Access Token，删除镜像功能可能无法正常工作
- ✅ 推荐使用 Access Token，并确保有 `Delete repository tags` 权限

### 2. 创建 Release（必须通过 GitHub Releases 页面）

**重要**：只有通过 [GitHub Releases 页面](https://github.com/WrBug/PolyHermes/releases/new) 创建 release 时才会触发自动构建。

**Workflow 说明**：
- 创建 release 时，会触发 `docker-build.yml` workflow，自动构建并推送镜像
- 删除 release 时，会触发 `docker-delete.yml` workflow，自动删除对应的镜像标签

**创建步骤**：
1. 访问 [GitHub Releases 页面](https://github.com/WrBug/PolyHermes/releases/new)
2. 点击 "Choose a tag" 下拉菜单，输入新的 tag 名称（例如：`v1.0.0` 或 `v1.0.0-beta`）
   - 如果 tag 不存在，GitHub 会自动创建
   - Tag 格式：`v数字.数字.数字` 或 `v数字.数字.数字-后缀`（例如：`v1.0.0`, `v1.0.0-beta`, `v2.10.102-rc.1`）
3. 填写 Release 标题（例如：`v1.0.0` 或 `v1.0.0-beta`）
4. 填写 Release 描述（可选，建议填写更新内容）
5. 点击 "Publish release" 按钮

**注意**：
- ⚠️ 直接通过 `git push` 推送 tag **不会**触发构建
- ✅ 只有通过 Releases 页面点击 "Publish release" 才会触发构建
- 这样可以确保只有正式发布的版本才会构建 Docker 镜像

### 3. 自动构建流程

点击 "Publish release" 后，GitHub Actions 会自动：

1. **提取版本号**：从 tag 中提取版本号（例如：`v1.0.0` → `1.0.0`）
2. **构建 Docker 镜像**：使用版本号作为构建参数
3. **注入版本号**：在构建前端时注入版本号到代码中
4. **推送镜像**：推送到 Docker Hub，标签为：
   - `wrbug/polyhermes:v1.0.0`（具体版本）
   - `wrbug/polyhermes:latest`（最新版本）

### 4. 版本号显示

前端会在标题 "PolyHermes" 后显示版本号，格式为：`PolyHermes v1.0.0`

- **显示位置**：桌面端左侧导航栏标题，移动端顶部标题
- **样式**：小字号，半透明，正常展示（无下划线等特殊样式）
- **点击行为**：点击版本号跳转到对应的 GitHub tag 页面

### 5. 删除 Release 和 Docker 镜像

当在 GitHub Releases 页面删除 release 时，会自动删除对应的 Docker 镜像标签：

1. 访问 [GitHub Releases 页面](https://github.com/WrBug/PolyHermes/releases)
2. 找到要删除的 release
3. 点击 "Delete" 按钮
4. GitHub Actions 会自动触发删除流程
5. 删除对应的 Docker 镜像标签（例如：`wrbug/polyhermes:v1.0.0`）

**注意事项**：
- ⚠️ 只有格式为 `v数字.数字.数字` 或 `v数字.数字.数字-后缀` 的版本号才会被删除（例如：`v1.0.0`, `v1.0.0-beta`, `v2.10.102`）
- ⚠️ 如果镜像标签不存在，会显示警告但不会失败
- ⚠️ `latest` 标签不会被删除（即使删除最新的 release）

## 技术实现

### 版本号注入流程

1. **GitHub Actions** 提取 tag 中的版本号
2. **Dockerfile** 接收构建参数（`VERSION`、`GIT_TAG`、`GITHUB_REPO_URL`）
3. **Vite 构建** 通过环境变量注入版本号到 `window.__VERSION__`
4. **前端代码** 从 `window.__VERSION__` 读取版本号并显示

### 文件说明

- `.github/workflows/docker-build.yml`: GitHub Actions 工作流配置
- `Dockerfile`: 支持版本号构建参数
- `frontend/vite.config.ts`: Vite 配置，注入版本号到全局变量
- `frontend/src/utils/version.ts`: 版本号工具函数
- `frontend/src/components/Layout.tsx`: 显示版本号的组件

### 环境变量

构建时使用的环境变量：

- `VERSION`: 版本号（例如：`1.0.0`）
- `GIT_TAG`: Git tag（例如：`v1.0.0`）
- `GITHUB_REPO_URL`: GitHub 仓库 URL（默认：`https://github.com/WrBug/PolyHermes`）

## 开发环境

在开发环境中，版本号默认为 `dev`，不会显示为链接。

如果需要测试版本号显示，可以在 `.env` 文件中设置：

```env
VITE_APP_VERSION=1.0.0
VITE_APP_GIT_TAG=v1.0.0
VITE_APP_GITHUB_REPO_URL=https://github.com/WrBug/PolyHermes
```

## 常见问题

### Q1: 创建 release 后没有触发构建？

**A:** 检查以下几点：
1. 确认是通过 [GitHub Releases 页面](https://github.com/WrBug/PolyHermes/releases/new) 创建的 release，而不是直接推送 tag
2. 确认点击了 "Publish release" 按钮（不是 "Save draft"）
3. 检查 GitHub Actions 是否已启用
4. 查看 Actions 标签页中的工作流运行情况
5. 确认 release 状态为 "Published"（不是 "Draft" 或 "Prerelease"）

### Q2: Docker 推送失败？

**A:** 检查以下几点：
1. 确认已正确配置 `DOCKER_USERNAME` 和 `DOCKER_PASSWORD` Secrets
2. 确认 Docker Hub 账户有权限推送镜像
3. 检查 Docker Hub 仓库名称是否正确（`wrbug/polyhermes`）

### Q3: 前端没有显示版本号？

**A:** 检查以下几点：
1. 确认构建时传递了版本号环境变量
2. 检查浏览器控制台是否有错误
3. 确认使用的是构建后的镜像，而不是开发环境

### Q4: 版本号点击没有跳转？

**A:** 检查以下几点：
1. 确认 `GIT_TAG` 环境变量已正确设置
2. 确认 GitHub 仓库 URL 正确
3. 检查浏览器是否阻止了弹窗

### Q5: 删除 release 后 Docker 镜像没有被删除？

**A:** 检查以下几点：
1. 确认版本号格式为 `v数字.数字.数字` 或 `v数字.数字.数字-后缀`（例如：`v1.0.0`, `v1.0.0-beta`）
2. 确认 Docker Hub 凭证（`DOCKER_USERNAME` 和 `DOCKER_PASSWORD`）正确配置
3. **确认 Docker Hub 访问令牌有删除镜像的权限**：
   - 如果使用 Access Token，需要确保有 `Delete repository tags` 权限
   - 访问 Docker Hub → Account Settings → Security → Access Tokens
   - 创建或编辑访问令牌，确保勾选 `Delete repository tags` 权限
4. 如果遇到 401 错误，可能是：
   - 访问令牌过期，需要重新生成
   - 访问令牌权限不足，需要添加删除权限
   - 用户名或密码/令牌错误
5. 查看 GitHub Actions 日志，确认删除操作是否执行
6. 如果镜像标签不存在，会显示警告但不会失败（这是正常的）

### Q7: 删除镜像时遇到 401 未授权错误？

**A:** 这通常是因为认证失败，请检查：

1. **如果使用 Access Token**：
   - 确保访问令牌未过期
   - 确保访问令牌有 `Delete repository tags` 权限
   - 在 Docker Hub → Account Settings → Security → Access Tokens 中检查权限

2. **如果使用密码**：
   - 确保用户名和密码正确
   - 如果启用了 2FA，需要使用 Access Token 而不是密码

3. **创建新的 Access Token**：
   - 访问：https://hub.docker.com/settings/security
   - 点击 "New Access Token"
   - 填写描述（如：`GitHub Actions Delete Images`）
   - **重要**：勾选 `Delete repository tags` 权限
   - 复制生成的令牌，更新 GitHub Secrets 中的 `DOCKER_PASSWORD`

### Q6: 版本号格式要求是什么？

**A:** 版本号必须严格匹配格式：`v数字.数字.数字` 或 `v数字.数字.数字-后缀`
- ✅ 正确：`v1.0.0`, `v2.10.102`, `v1.0.0-beta`, `v1.0.0-rc.1`, `v2.10.102-alpha`
- ❌ 错误：`v1.0`, `1.0.0`, `v1.0.0.1`, `v1.0.0_beta`（下划线不支持）

## 示例

### 创建 Release 示例

**步骤 1：访问 Releases 页面**
访问：https://github.com/WrBug/PolyHermes/releases/new

**步骤 2：创建 Release**
1. 在 "Choose a tag" 中输入 `v1.0.0`（如果不存在会自动创建）
2. 填写 Release 标题：`v1.0.0`
3. 填写 Release 描述（可选）
4. 点击 "Publish release"

**步骤 3：自动构建**
- GitHub Actions 会自动触发构建
- 构建完成后，Docker 镜像会自动推送到 Docker Hub
- 前端会显示 "PolyHermes v1.0.0"

**注意**：直接通过 `git push` 推送 tag 不会触发构建，必须通过 Releases 页面创建。

### 使用 Docker 镜像示例

```bash
# 拉取特定版本
docker pull wrbug/polyhermes:v1.0.0

# 拉取最新版本
docker pull wrbug/polyhermes:latest

# 运行容器
docker run -d -p 80:80 wrbug/polyhermes:v1.0.0
```

## 注意事项

1. **Tag 格式**：必须使用 `v*` 格式（例如：`v1.0.0`），否则不会触发构建
2. **版本号格式**：建议使用语义化版本号（Semantic Versioning）
3. **Docker Hub**：确保 Docker Hub 仓库已创建
4. **权限**：确保 GitHub Actions 有权限访问 Docker Hub

