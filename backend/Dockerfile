# 多阶段构建 Dockerfile
# 阶段1：构建应用
FROM gradle:8.5-jdk17 AS build

WORKDIR /app

# 复制 Gradle 配置文件
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle

# 下载依赖（利用 Docker 缓存）
RUN gradle dependencies --no-daemon || true

# 复制源代码
COPY src ./src

# 构建应用
RUN gradle bootJar --no-daemon

# 阶段2：运行应用
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# 安装必要的工具
RUN apt-get update && \
    apt-get install -y curl && \
    rm -rf /var/lib/apt/lists/*

# 从构建阶段复制 JAR 文件
COPY --from=build /app/build/libs/*.jar app.jar

# 创建非 root 用户
RUN useradd -m -u 1000 appuser && \
    chown -R appuser:appuser /app

USER appuser

# 暴露端口
EXPOSE 8000

# 健康检查
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -f http://localhost:8000/api/health || exit 1

# 启动应用
ENTRYPOINT ["java", "-jar", "app.jar"]

