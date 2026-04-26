# 多阶段构建：前后端一体化部署（支持混合编译）
# 构建参数：控制是否在 Docker 内编译
# - BUILD_IN_DOCKER=true  (默认): Docker 内部编译（本地开发）
# - BUILD_IN_DOCKER=false: 使用外部产物（GitHub Actions）
ARG BUILD_IN_DOCKER=true

# ==================== 阶段1：构建前端 ====================
FROM node:18-alpine AS frontend-build
ARG BUILD_IN_DOCKER

WORKDIR /app/frontend

# 定义构建参数（版本号信息）
ARG VERSION=dev
ARG GIT_TAG=
ARG GITHUB_REPO_URL=https://github.com/WrBug/PolyHermes

# 设置环境变量（用于 Vite 构建时注入）
ENV VERSION=${VERSION}
ENV GIT_TAG=${GIT_TAG}
ENV GITHUB_REPO_URL=${GITHUB_REPO_URL}
# 复制前端文件（先复制 package.json 以利用 Docker 缓存）
COPY frontend/package*.json ./

# 条件：仅在 Docker 内部编译时安装依赖
RUN if [ "$BUILD_IN_DOCKER" = "true" ]; then \
      npm ci; \
    fi

# 复制所有前端源文件
COPY frontend/ ./

# 条件：仅在 Docker 内部编译时执行构建
# 如果 BUILD_IN_DOCKER=false，需要确保构建上下文中存在 frontend/dist
# 注意：COPY frontend/ ./ 已经复制了整个 frontend 目录（包括 dist，如果存在）
RUN if [ "$BUILD_IN_DOCKER" = "true" ]; then \
      echo "🔨 Docker 内部编译前端..."; \
      npm run build; \
    else \
      echo "⏭️  使用外部产物..."; \
      if [ ! -d "dist" ] || [ -z "$(ls -A dist 2>/dev/null)" ]; then \
        echo "❌ 错误：BUILD_IN_DOCKER=false 但找不到外部产物 frontend/dist"; \
        echo "   请先执行: cd frontend && npm install && npm run build"; \
        exit 1; \
      else \
        echo "✅ 找到外部构建的前端产物"; \
      fi; \
    fi

# ==================== 阶段2：构建后端 ====================
FROM gradle:8.5-jdk17 AS backend-build
ARG BUILD_IN_DOCKER

WORKDIR /app/backend

# 复制 Gradle 配置文件
COPY backend/build.gradle.kts backend/settings.gradle.kts ./
COPY backend/gradle ./gradle

# 条件：仅在 Docker 内部编译时下载依赖
RUN if [ "$BUILD_IN_DOCKER" = "true" ]; then \
      gradle dependencies --no-daemon || true; \
    fi

# 复制源代码
COPY backend/src ./src

# 尝试复制外部构建的 JAR（如果存在）
# 注意：COPY 指令如果源不存在会失败
# GitHub Actions 使用 BUILD_IN_DOCKER=false，会先构建产物，所以 backend/build 应该存在
# 本地开发使用 BUILD_IN_DOCKER=true，会在 Docker 内编译，所以 backend/build 可能不存在
# 解决方案：先复制整个 backend 目录（包括 build，如果存在），然后只使用需要的部分
# 使用 .dockerignore 确保不会复制不需要的文件（如 .gradle、out、bin 等）
COPY backend/build ./build-external

# 处理外部构建的 JAR（如果存在）
RUN if [ -d "build-external/libs" ] && [ -n "$(ls -A build-external/libs/*.jar 2>/dev/null)" ]; then \
      echo "📦 找到外部构建的后端产物，复制到 build/libs..."; \
      mkdir -p build/libs; \
      cp build-external/libs/*.jar build/libs/; \
      rm -rf build-external; \
    else \
      echo "⏭️  未找到外部构建的 JAR，将在 Docker 内编译"; \
      rm -rf build-external; \
      mkdir -p build/libs; \
    fi

# 条件：仅在 Docker 内部编译时执行构建（会覆盖外部产物）
RUN if [ "$BUILD_IN_DOCKER" = "true" ]; then \
      echo "🔨 Docker 内部编译后端..."; \
      gradle bootJar --no-daemon; \
    else \
      echo "⏭️  使用外部产物"; \
      if [ -z "$(ls -A build/libs/*.jar 2>/dev/null)" ]; then \
        echo "❌ 错误：BUILD_IN_DOCKER=false 但找不到外部产物 backend/build/libs/*.jar"; \
        echo "   请先执行: cd backend && ./gradlew bootJar"; \
        exit 1; \
      else \
        echo "✅ 使用外部构建的后端产物"; \
      fi; \
    fi

# 统一选出可执行 JAR，避免 *-plain.jar 和 bootJar 同时存在导致最终 COPY 匹配多个文件
RUN set -e; \
    JAR="$(find build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' | head -n 1)"; \
    if [ -z "$JAR" ]; then \
      echo "❌ 错误：找不到可执行 JAR（已排除 *-plain.jar）"; \
      exit 1; \
    fi; \
    if [ "$JAR" != "build/libs/app.jar" ]; then \
      cp "$JAR" build/libs/app.jar; \
    fi

# ==================== 阶段3：运行环境 ====================
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# 安装 Nginx、Python 和必要的工具
RUN apt-get update && \
    apt-get install -y nginx curl tzdata jq python3 python3-flask python3-requests && \
    rm -rf /var/lib/apt/lists/* && \
    rm -rf /etc/nginx/sites-enabled/default

# 从构建阶段复制文件
# 当 BUILD_IN_DOCKER=false 时，构建阶段已经复制了外部产物
COPY --from=frontend-build /app/frontend/dist /usr/share/nginx/html
COPY --from=backend-build /app/backend/build/libs/app.jar app.jar

# 复制 Nginx 配置
COPY docker/nginx.conf /etc/nginx/nginx.conf

# 创建更新服务相关目录和脚本
RUN mkdir -p /app/updates /app/backups /var/log/polyhermes
COPY docker/update-service.py /app/update-service.py
COPY docker/start.sh /app/start.sh
RUN chmod +x /app/start.sh

# 记录初始版本（从构建参数）
ARG VERSION=dev
ARG GIT_TAG=dev
RUN echo "{\"version\":\"${VERSION}\",\"tag\":\"${GIT_TAG}\",\"buildTime\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}" > /app/version.json

# 创建非 root 用户
RUN useradd -m -u 1000 appuser

# 设置目录权限
RUN mkdir -p /var/log/nginx /var/lib/nginx /var/cache/nginx /var/run && \
    chown -R appuser:appuser /app && \
    chown -R root:root /usr/share/nginx/html /var/log/nginx /var/lib/nginx /var/cache/nginx /etc/nginx /var/run

# 暴露端口
EXPOSE 80

# 健康检查
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -f http://localhost/api/system/health || exit 1

# 启动服务
ENTRYPOINT ["/app/start.sh"]
