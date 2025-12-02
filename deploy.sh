#!/bin/bash

# PolyHermes 一体化部署脚本
# 将前后端一起部署到一个 Docker 容器中

set -e

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 打印信息
info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 检查 Docker 环境
check_docker() {
    if ! command -v docker &> /dev/null; then
        error "Docker 未安装，请先安装 Docker"
        exit 1
    fi
    
    if ! command -v docker-compose &> /dev/null; then
        error "Docker Compose 未安装，请先安装 Docker Compose"
        exit 1
    fi
    
    info "Docker 环境检查通过"
}

# 创建 .env 文件（如果不存在）
create_env_file() {
    if [ ! -f ".env" ]; then
        warn ".env 文件不存在，创建示例文件..."
        cat > .env <<EOF
# 数据库配置
DB_URL=jdbc:mysql://mysql:3306/polyhermes?useSSL=false&serverTimezone=UTC&characterEncoding=utf8&allowPublicKeyRetrieval=true
DB_USERNAME=root
DB_PASSWORD=your_password_here

# Spring Profile
SPRING_PROFILES_ACTIVE=prod

# 服务器端口（对外暴露的端口）
SERVER_PORT=80

# MySQL 端口（可选，用于外部连接，默认 3307 避免与本地 MySQL 冲突）
MYSQL_PORT=3307

# Polygon RPC
POLYGON_RPC_URL=https://polygon-rpc.com

# JWT 密钥（生产环境必须修改）
JWT_SECRET=your-jwt-secret-key-change-in-production

# 管理员密码重置密钥（生产环境必须修改）
ADMIN_RESET_PASSWORD_KEY=your-admin-reset-key-change-in-production
EOF
        error ".env 文件已创建，请先编辑配置相关参数后再运行部署脚本"
        error "特别是以下参数必须修改："
        error "  - DB_PASSWORD: 数据库密码"
        error "  - JWT_SECRET: JWT 密钥（生产环境）"
        error "  - ADMIN_RESET_PASSWORD_KEY: 管理员密码重置密钥（生产环境）"
        exit 1
    fi
}

# 构建并启动
deploy() {
    info "构建 Docker 镜像..."
    docker-compose build
    
    info "启动服务..."
    docker-compose up -d
    
    info "等待服务启动..."
    sleep 5
    
    info "检查服务状态..."
    docker-compose ps
    
    info "查看日志: docker-compose logs -f"
    info "停止服务: docker-compose down"
}

# 主函数
main() {
    echo "=========================================="
    echo "  PolyHermes 一体化部署脚本"
    echo "=========================================="
    echo ""
    
    check_docker
    create_env_file
    deploy
    
    echo ""
    info "部署完成！"
    info "访问地址: http://localhost:${SERVER_PORT:-80}"
}

main "$@"

