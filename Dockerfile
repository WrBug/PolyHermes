# 多阶段构建：前后端一体化部署
# 阶段1：构建前端
FROM node:18-alpine AS frontend-build

WORKDIR /app/frontend

# 定义构建参数（版本号信息）
ARG VERSION=dev
ARG GIT_TAG=
ARG GITHUB_REPO_URL=https://github.com/WrBug/PolyHermes

# 设置环境变量（用于 Vite 构建时注入）
ENV VERSION=${VERSION}
ENV GIT_TAG=${GIT_TAG}
ENV GITHUB_REPO_URL=${GITHUB_REPO_URL}

# 复制前端文件
COPY frontend/package*.json ./
RUN npm ci

COPY frontend/ ./

# 构建前端（使用相对路径，通过 Nginx 代理）
# 版本号会通过环境变量注入到构建产物中
RUN npm run build

# 阶段2：构建后端
FROM gradle:8.5-jdk17 AS backend-build

WORKDIR /app/backend

# 复制 Gradle 配置文件
COPY backend/build.gradle.kts backend/settings.gradle.kts ./
COPY backend/gradle ./gradle

# 下载依赖（利用 Docker 缓存）
RUN gradle dependencies --no-daemon || true

# 复制源代码
COPY backend/src ./src

# 构建应用
RUN gradle bootJar --no-daemon

# 阶段3：运行环境
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# 安装 Nginx 和必要的工具（包含时区数据）
RUN apt-get update && \
    apt-get install -y nginx curl tzdata && \
    rm -rf /var/lib/apt/lists/* && \
    rm -rf /etc/nginx/sites-enabled/default

# 从构建阶段复制文件
COPY --from=frontend-build /app/frontend/dist /usr/share/nginx/html
COPY --from=backend-build /app/backend/build/libs/*.jar app.jar

# 复制 Nginx 配置
COPY docker/nginx.conf /etc/nginx/nginx.conf

# 创建启动脚本
COPY docker/start.sh /app/start.sh
RUN chmod +x /app/start.sh

# 创建非 root 用户（用于运行后端应用）
RUN useradd -m -u 1000 appuser

# 设置目录权限（Nginx 以 root 运行，后端应用以 appuser 运行）
RUN mkdir -p /var/log/nginx /var/lib/nginx /var/cache/nginx /var/run && \
    chown -R appuser:appuser /app && \
    chown -R root:root /usr/share/nginx/html && \
    chown -R root:root /var/log/nginx && \
    chown -R root:root /var/lib/nginx && \
    chown -R root:root /var/cache/nginx && \
    chown -R root:root /etc/nginx && \
    chown -R root:root /var/run

# 保持 root 用户（Nginx 需要 root 权限绑定 80 端口）
# USER appuser

# 暴露端口
EXPOSE 80

# 健康检查
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -f http://localhost/api/health || exit 1

# 启动服务（同时启动 Nginx 和后端）
ENTRYPOINT ["/app/start.sh"]

