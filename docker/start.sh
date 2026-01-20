#!/bin/bash

# 启动脚本：启动更新服务、后端服务和 Nginx

set -e

echo "========================================="
echo "  PolyHermes 容器启动"
echo "========================================="

# 默认值常量
DEFAULT_JWT_SECRET="change-me-in-production"
DEFAULT_ADMIN_RESET_KEY="change-me-in-production"

# 检查安全配置
check_security_config() {
    local errors=0
    
    # 检查 JWT_SECRET
    if [ -z "$JWT_SECRET" ] || [ "$JWT_SECRET" = "$DEFAULT_JWT_SECRET" ]; then
        echo "❌ 错误: JWT_SECRET 不能使用默认值 '${DEFAULT_JWT_SECRET}'"
        echo "   请设置环境变量 JWT_SECRET 为安全的随机字符串"
        errors=$((errors + 1))
    fi
    
    # 检查 ADMIN_RESET_PASSWORD_KEY
    if [ -z "$ADMIN_RESET_PASSWORD_KEY" ] || [ "$ADMIN_RESET_PASSWORD_KEY" = "$DEFAULT_ADMIN_RESET_KEY" ]; then
        echo "❌ 错误: ADMIN_RESET_PASSWORD_KEY 不能使用默认值 '${DEFAULT_ADMIN_RESET_KEY}'"
        echo "   请设置环境变量 ADMIN_RESET_PASSWORD_KEY 为安全的随机字符串"
        errors=$((errors + 1))
    fi
    
    if [ $errors -gt 0 ]; then
        echo ""
        echo "⚠️  安全配置检查失败，容器将不会启动"
        echo "   请在 docker-compose.yml 或 .env 文件中设置正确的值"
        exit 1
    fi
    
    echo "✅ 安全配置检查通过"
}

# 执行安全配置检查
check_security_config

# 函数：清理进程
cleanup() {
    echo "收到退出信号，清理进程..."
    if [ -n "$UPDATE_SERVICE_PID" ]; then
        kill $UPDATE_SERVICE_PID 2>/dev/null || true
    fi
    if [ -n "$BACKEND_PID" ]; then
        kill $BACKEND_PID 2>/dev/null || true
    fi
    nginx -s quit 2>/dev/null || true
    exit 0
}

# 注册信号处理
trap cleanup SIGTERM SIGINT

# 1. 启动更新服务（后台运行，端口 9090）
echo "🚀 启动更新服务..."
python3 /app/update-service.py &
UPDATE_SERVICE_PID=$!
echo "✅ 更新服务已启动 (PID: $UPDATE_SERVICE_PID, Port: 9090)"

# 等待更新服务就绪
sleep 2

# 2. 启动后端服务（后台运行，端口 8000）
echo "🚀 启动后端服务..."
java -jar /app/app.jar --spring.profiles.active=${SPRING_PROFILES_ACTIVE:-prod} &
BACKEND_PID=$!
echo "✅ 后端服务已启动 (PID: $BACKEND_PID, Port: 8000)"

# 3. 等待后端服务启动
echo "⏳ 等待后端服务就绪..."
for i in {1..60}; do
    if curl -f http://localhost:8000/api/system/health > /dev/null 2>&1; then
        echo "✅ 后端服务健康检查通过"
        break
    fi
    if [ $i -eq 60 ]; then
        echo "❌ 后端服务启动超时"
        exit 1
    fi
    sleep 1
done

# 4. 启动 Nginx（前台运行，保持容器存活）
echo "🚀 启动 Nginx..."
echo "========================================="
echo "  容器启动完成"
echo "  - 更新服务: http://localhost:9090"
echo "  - 后端服务: http://localhost:8000"
echo "  - 前端服务: http://localhost:80"
echo "========================================="

exec nginx -g "daemon off;"
