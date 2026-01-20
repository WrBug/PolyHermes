# Docker 版本号确定流程

## 概述

Docker 镜像的版本号从 **GitHub Release Tag** 获取，通过 GitHub Actions 自动传递到 Dockerfile，最终存储在容器内的 `/app/version.json` 文件中。

## 完整流程

```
1. GitHub Release Tag (v1.0.0)
   ↓
2. GitHub Actions 触发
   ↓
3. 从 Tag 提取版本号
   ↓
4. 作为 build-args 传递给 Dockerfile
   ↓
5. Dockerfile 写入 /app/version.json
   ↓
6. 容器运行时读取版本号
```

## 详细步骤

### 步骤 1: 创建 GitHub Release

通过 GitHub Releases 页面或 `create-release.sh` 脚本创建 Release：

```bash
# 示例：创建 v1.0.1 版本
./create-release.sh -t v1.0.1 -T "Release v1.0.1" -d "更新内容"
```

**结果**:
- 创建 Git tag: `v1.0.1`
- 创建 GitHub Release: `v1.0.1`
- 触发 GitHub Actions workflow

### 步骤 2: GitHub Actions 触发

GitHub Actions 监听 `release: published` 事件：

```yaml
# .github/workflows/docker-build.yml
on:
  release:
    types:
      - published  # 当创建 release 时触发
```

**事件数据**:
- `github.event.release.tag_name`: `"v1.0.1"`
- `github.event.release.prerelease`: `false` 或 `true`

### 步骤 3: 提取版本号

GitHub Actions 从 Tag 中提取版本号：

```bash
# .github/workflows/docker-build.yml (步骤: Extract version)
TAG_NAME="${{ github.event.release.tag_name }}"  # "v1.0.1"
VERSION=${TAG_NAME#v}                             # "1.0.1" (移除 v 前缀)
```

**提取结果**:
- `VERSION`: `"1.0.1"` (纯版本号，无 v 前缀)
- `TAG`: `"v1.0.1"` (完整 tag，带 v 前缀)
- `IS_PRERELEASE`: `false` 或 `true`

**版本号格式验证**:
- ✅ 正确：`v1.0.0`, `v2.10.102`, `v1.0.0-beta`
- ❌ 错误：`v1.0`, `1.0.0`, `v1.0.0.1`

### 步骤 4: 传递构建参数

版本号作为 Docker build-args 传递给 Dockerfile：

```yaml
# .github/workflows/docker-build.yml
- name: Build and push Docker image
  uses: docker/build-push-action@v5
  with:
    build-args: |
      BUILD_IN_DOCKER=false
      VERSION=${{ steps.extract_version.outputs.VERSION }}      # "1.0.1"
      GIT_TAG=${{ steps.extract_version.outputs.TAG }}          # "v1.0.1"
      GITHUB_REPO_URL=https://github.com/WrBug/PolyHermes
```

### 步骤 5: Dockerfile 接收参数

Dockerfile 使用 ARG 接收构建参数：

```dockerfile
# Dockerfile (第 92-94 行)
ARG VERSION=dev        # 默认值: dev
ARG GIT_TAG=dev        # 默认值: dev

# 写入 version.json
RUN echo "{\"version\":\"${VERSION}\",\"tag\":\"${GIT_TAG}\",\"buildTime\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}" > /app/version.json
```

**生成的文件内容** (`/app/version.json`):
```json
{
  "version": "1.0.1",
  "tag": "v1.0.1",
  "buildTime": "2026-01-20T15:30:00Z"
}
```

### 步骤 6: 容器运行时读取

更新服务通过 `/api/update/version` 接口读取版本号：

```python
# docker/update-service.py
def get_current_version():
    """获取当前版本"""
    if VERSION_FILE.exists():
        with open(VERSION_FILE) as f:
            data = json.load(f)
            return data.get('version', 'unknown')  # 返回: "1.0.1"
```

前端通过 API 获取并显示：

```typescript
// frontend/src/pages/SystemUpdate.tsx
const response = await apiClient.get('/update/version')
const { version } = response.data.data  // "1.0.1"
```

## 不同场景下的版本号

### 场景 1: GitHub Actions 自动构建（正式发布）

**输入**:
- Release Tag: `v1.0.1`
- Release Type: Published (正式版本)

**流程**:
1. GitHub Actions 提取: `VERSION="1.0.1"`, `GIT_TAG="v1.0.1"`
2. 传递给 Dockerfile
3. 生成 `/app/version.json`: `{"version": "1.0.1", "tag": "v1.0.1", ...}`

**Docker 镜像标签**:
- `wrbug/polyhermes:v1.0.1` ✅
- `wrbug/polyhermes:latest` ✅ (因为不是 pre-release)

### 场景 2: Pre-release（测试版本）

**输入**:
- Release Tag: `v1.0.1-beta`
- Release Type: Pre-release

**流程**:
1. GitHub Actions 提取: `VERSION="1.0.1-beta"`, `GIT_TAG="v1.0.1-beta"`
2. 传递给 Dockerfile
3. 生成 `/app/version.json`: `{"version": "1.0.1-beta", "tag": "v1.0.1-beta", ...}`

**Docker 镜像标签**:
- `wrbug/polyhermes:v1.0.1-beta` ✅
- `wrbug/polyhermes:latest` ❌ (pre-release 不推送到 latest)

### 场景 3: 本地构建（开发环境）

**命令行**:
```bash
docker build -t polyhermes:local .
```

**流程**:
1. 没有传递 `VERSION` 和 `GIT_TAG` 参数
2. Dockerfile 使用默认值: `VERSION=dev`, `GIT_TAG=dev`
3. 生成 `/app/version.json`: `{"version": "dev", "tag": "dev", ...}`

**显式指定版本号**:
```bash
docker build \
  --build-arg VERSION=1.0.1 \
  --build-arg GIT_TAG=v1.0.1 \
  -t polyhermes:local .
```

### 场景 4: 本地 Docker Compose

**docker-compose.yml**:
```yaml
services:
  app:
    build:
      context: .
      args:
        VERSION: 1.0.1
        GIT_TAG: v1.0.1
```

## 版本号存储位置

### 容器内路径

```
/app/version.json
```

### 文件格式

```json
{
  "version": "1.0.1",           // 纯版本号（无 v 前缀）
  "tag": "v1.0.1",              // 完整 tag（带 v 前缀）
  "buildTime": "2026-01-20T15:30:00Z"  // 构建时间（UTC）
}
```

### 访问方式

**1. 通过 API**:
```bash
curl http://localhost/api/update/version
```

**2. 进入容器查看**:
```bash
docker exec -it <container_id> cat /app/version.json
```

**3. 前端显示**:
- 系统设置 → 系统更新页面
- 显示当前版本: `v1.0.1`

## 版本号的作用

### 1. 显示当前版本

前端和系统更新页面显示当前运行的版本号。

### 2. 检查更新

更新服务通过比较当前版本和 GitHub 最新版本判断是否有更新：

```python
# docker/update-service.py
current_version = get_current_version()  # "1.0.1"
latest_version = fetch_latest_release()  # "1.0.2"

if compare_versions(latest_version, current_version) > 0:
    # 有新版本，提示更新
```

### 3. 版本追踪

记录 Docker 镜像的构建版本，便于追踪和回滚。

## 关键文件

| 文件 | 作用 | 版本号来源 |
|------|------|-----------|
| `.github/workflows/docker-build.yml` | GitHub Actions 工作流 | `github.event.release.tag_name` |
| `Dockerfile` | Docker 构建配置 | 构建参数 `VERSION`, `GIT_TAG` |
| `/app/version.json` | 版本号存储文件 | Dockerfile 生成 |
| `docker/update-service.py` | 更新服务 | 读取 `/app/version.json` |

## 常见问题

### Q1: 为什么版本号是 `dev`？

**A**: 本地构建时没有传递版本号参数，使用了默认值。

**解决**:
```bash
docker build \
  --build-arg VERSION=1.0.1 \
  --build-arg GIT_TAG=v1.0.1 \
  -t polyhermes:local .
```

### Q2: 如何查看当前容器的版本号？

**A**: 
```bash
# 方法1: API 接口
curl http://localhost/api/update/version

# 方法2: 进入容器
docker exec -it <container_id> cat /app/version.json

# 方法3: 前端页面
系统设置 → 系统更新 → 查看"当前版本"
```

### Q3: 版本号格式错误怎么办？

**A**: GitHub Actions 会验证版本号格式：
- ✅ 正确：`v1.0.0`, `v1.0.0-beta`
- ❌ 错误：`v1.0`, `1.0.0`

如果格式错误，构建会失败并提示错误信息。

### Q4: Pre-release 和正式版本的版本号有什么区别？

**A**: 
- **格式**: 都可以使用相同的格式（`v1.0.1-beta` vs `v1.0.1`）
- **存储**: 都存储在 `/app/version.json` 中
- **Docker 标签**: Pre-release 不会推送到 `latest` 标签
- **通知**: Pre-release 不会发送 Telegram 通知

## 总结

Docker 版本号的确定流程：

1. **来源**: GitHub Release Tag
2. **提取**: GitHub Actions 从 tag 中提取版本号
3. **传递**: 通过 Docker build-args 传递
4. **存储**: 写入容器内的 `/app/version.json`
5. **使用**: 用于显示、检查更新、版本追踪

关键点：
- ✅ 版本号来自 **GitHub Release Tag**
- ✅ 格式必须符合：`v数字.数字.数字[-后缀]`
- ✅ 默认值为 `dev`（本地构建时）
- ✅ 支持 Pre-release 标记

