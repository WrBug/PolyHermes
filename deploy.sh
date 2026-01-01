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

# 生成随机字符串
generate_random_string() {
    local length=${1:-32}
    openssl rand -hex $length 2>/dev/null || \
    cat /dev/urandom | tr -dc 'a-zA-Z0-9' | fold -w $length | head -n 1
}

# 创建 .env 文件（如果不存在）
create_env_file() {
    if [ ! -f ".env" ]; then
        warn ".env 文件不存在，创建示例文件..."
        
        # 生成随机值
        DB_PASSWORD=$(generate_random_string 32)
        JWT_SECRET=$(generate_random_string 64)
        ADMIN_RESET_KEY=$(generate_random_string 32)
        
        cat > .env <<EOF
# 数据库配置
DB_URL=jdbc:mysql://mysql:3306/polyhermes?useSSL=false&serverTimezone=UTC&characterEncoding=utf8&allowPublicKeyRetrieval=true
DB_USERNAME=root
DB_PASSWORD=${DB_PASSWORD}

# Spring Profile
SPRING_PROFILES_ACTIVE=prod

# 服务器端口（对外暴露的端口）
SERVER_PORT=80

# MySQL 端口（可选，用于外部连接，默认 3307 避免与本地 MySQL 冲突）
MYSQL_PORT=3307

# JWT 密钥（已自动生成随机值，生产环境建议修改）
JWT_SECRET=${JWT_SECRET}

# 管理员密码重置密钥（已自动生成随机值，生产环境建议修改）
ADMIN_RESET_PASSWORD_KEY=${ADMIN_RESET_KEY}
EOF
        info ".env 文件已创建，已自动生成随机密码和密钥"
        warn "生产环境建议修改以下参数："
        warn "  - DB_PASSWORD: 数据库密码（当前: ${DB_PASSWORD:0:8}...）"
        warn "  - JWT_SECRET: JWT 密钥（当前: ${JWT_SECRET:0:8}...）"
        warn "  - ADMIN_RESET_PASSWORD_KEY: 管理员密码重置密钥（当前: ${ADMIN_RESET_KEY:0:8}...）"
        exit 1
    fi
}

# 检查安全配置
check_security_config() {
    # 默认值常量
    DEFAULT_JWT_SECRET="change-me-in-production"
    DEFAULT_ADMIN_RESET_KEY="change-me-in-production"
    
    # 从 .env 文件读取配置（如果存在）
    local jwt_secret=""
    local admin_reset_key=""
    
    if [ -f ".env" ]; then
        # 从 .env 文件读取（使用 grep 和 sed 避免 source 可能的问题）
        jwt_secret=$(grep "^JWT_SECRET=" .env 2>/dev/null | cut -d'=' -f2- | sed 's/^"//;s/"$//' || echo "")
        admin_reset_key=$(grep "^ADMIN_RESET_PASSWORD_KEY=" .env 2>/dev/null | cut -d'=' -f2- | sed 's/^"//;s/"$//' || echo "")
    fi
    
    # 如果环境变量已设置，优先使用环境变量
    if [ -n "$JWT_SECRET" ]; then
        jwt_secret="$JWT_SECRET"
    fi
    if [ -n "$ADMIN_RESET_PASSWORD_KEY" ]; then
        admin_reset_key="$ADMIN_RESET_PASSWORD_KEY"
    fi
    
    local errors=0
    
    # 检查 JWT_SECRET
    if [ -z "$jwt_secret" ] || [ "$jwt_secret" = "$DEFAULT_JWT_SECRET" ]; then
        error "JWT_SECRET 不能使用默认值 '${DEFAULT_JWT_SECRET}'"
        error "请在 .env 文件中设置 JWT_SECRET 为安全的随机字符串"
        errors=$((errors + 1))
    fi
    
    # 检查 ADMIN_RESET_PASSWORD_KEY
    if [ -z "$admin_reset_key" ] || [ "$admin_reset_key" = "$DEFAULT_ADMIN_RESET_KEY" ]; then
        error "ADMIN_RESET_PASSWORD_KEY 不能使用默认值 '${DEFAULT_ADMIN_RESET_KEY}'"
        error "请在 .env 文件中设置 ADMIN_RESET_PASSWORD_KEY 为安全的随机字符串"
        errors=$((errors + 1))
    fi
    
    if [ $errors -gt 0 ]; then
        echo ""
        error "安全配置检查失败，部署已取消"
        echo ""
        info "提示：可以使用以下命令生成随机密钥："
        info "  openssl rand -hex 32  # 生成 32 字节的随机字符串（用于 ADMIN_RESET_PASSWORD_KEY）"
        info "  openssl rand -hex 64  # 生成 64 字节的随机字符串（用于 JWT_SECRET）"
        exit 1
    fi
    
    info "安全配置检查通过"
}

# 构建并启动
deploy() {
    # 检查安全配置
    check_security_config
    
    # 检查是否使用 Docker Hub 镜像
    USE_DOCKER_HUB="${USE_DOCKER_HUB:-false}"
    
    if [ "$USE_DOCKER_HUB" = "true" ]; then
        info "使用 Docker Hub 镜像（推荐生产环境）..."
        info "拉取最新镜像..."
        docker pull wrbug/polyhermes:latest || warn "拉取镜像失败，将使用本地构建"
        
        # 修改 docker-compose.yml 使用镜像而不是构建
        # 注意：这里需要手动修改 docker-compose.yml，或者使用环境变量
        warn "请确保 docker-compose.yml 中已配置使用 image: wrbug/polyhermes:latest"
    else
        # 获取当前分支名作为版本号
        CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "dev")
        # 如果分支名包含 /，替换为 -（Docker tag 不支持 /）
        DOCKER_VERSION=$(echo "$CURRENT_BRANCH" | tr '/' '-')
        
        info "构建 Docker 镜像（本地构建，版本号: ${DOCKER_VERSION}）..."
        
        # 设置构建参数（通过环境变量传递给 docker-compose.yml）
        export VERSION=${DOCKER_VERSION}
        export GIT_TAG=${DOCKER_VERSION}
        export GITHUB_REPO_URL=https://github.com/WrBug/PolyHermes
        
        docker-compose build
    fi
    
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
    
    # 解析参数
    if [ "$1" = "--use-docker-hub" ] || [ "$1" = "-d" ]; then
        export USE_DOCKER_HUB=true
        info "将使用 Docker Hub 镜像（生产环境推荐）"
        echo ""
    fi
    
    check_docker
    create_env_file
    deploy
    
    echo ""
    info "部署完成！"
    info "访问地址: http://localhost:${SERVER_PORT:-80}"
    echo ""
    if [ "$USE_DOCKER_HUB" != "true" ]; then
        CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "dev")
        DOCKER_VERSION=$(echo "$CURRENT_BRANCH" | tr '/' '-')
        info "提示：本地构建的版本号为当前分支名: ${DOCKER_VERSION}"
        info "生产环境推荐使用 Docker Hub 镜像："
        info "  ./deploy.sh --use-docker-hub"
        info "  或修改 docker-compose.yml 使用 image: wrbug/polyhermes:latest"
    fi
}

main "$@"

