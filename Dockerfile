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
# 如果 BUILD_IN_DOCKER=false，需要从构建上下文复制外部编译的 dist
RUN if [ "$BUILD_IN_DOCKER" = "true" ]; then \
      echo "🔨 Docker 内部编译前端..."; \
      npm run build; \
    else \
      echo "⏭️  使用外部产物，将在下一步复制"; \
      mkdir -p dist; \
    fi

# 仅在使用外部产物时复制 dist 目录到构建上下文
# 在 BUILD_IN_DOCKER=true 时，上面的 npm run build 已经创建了 dist
# 在 BUILD_IN_DOCKER=false 时，需要从构建上下文复制外部编译的 dist
COPY frontend/dist ./dist

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

# 如果使用外部产物，先从构建上下文复制外部编译的 JAR
# 注意：如果 BUILD_IN_DOCKER=true 且本地没有 JAR，这个 COPY 会失败，但会在下面编译生成
COPY backend/build/libs/*.jar build/libs/

# 条件：仅在 Docker 内部编译时执行构建（会覆盖外部产物）
RUN if [ "$BUILD_IN_DOCKER" = "true" ]; then \
      echo "🔨 Docker 内部编译后端..."; \
      gradle bootJar --no-daemon; \
    else \
      echo "⏭️  使用外部产物"; \
      mkdir -p build/libs; \
      if [ -z "$(ls -A build/libs/*.jar 2>/dev/null)" ]; then \
        echo "❌ 错误：BUILD_IN_DOCKER=false 但找不到外部产物 backend/build/libs/*.jar"; \
        exit 1; \
      fi; \
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
COPY --from=backend-build /app/backend/build/libs/*.jar app.jar

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
