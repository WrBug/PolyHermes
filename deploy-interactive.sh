#!/bin/bash

# ========================================
# PolyHermes Interactive Deploy Script
# PolyHermes 交互式一键部署脚本
# ========================================
# Features / 功能：
# - Interactive env config / 交互式配置环境变量
# - Auto-generate secrets / 自动生成安全密钥
# - Deploy via Docker Hub images / 使用 Docker Hub 线上镜像部署
# - Config check and rollback / 支持配置预检和回滚
# ========================================

set -e

# Colors / 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Language: LANG=zh* → prompts in Chinese only; else show "中文 / English"
# 语言：LANG 为 zh* 时仅中文，否则显示「中文 / English」
USE_ZH_ONLY=false
case "${LANG:-}" in
    zh*) USE_ZH_ONLY=true ;;
esac

# Print functions / 打印函数
info() {
    echo -e "${GREEN}[✓]${NC} $1"
}

warn() {
    echo -e "${YELLOW}[⚠]${NC} $1"
}

error() {
    echo -e "${RED}[✗]${NC} $1"
}

title() {
    echo -e "${CYAN}${1}${NC}"
}

# Bilingual: 中文 / English (or Chinese only when LANG=zh*)
bilingual() {
    local zh="$1"
    local en="$2"
    if [ "$USE_ZH_ONLY" = true ]; then
        echo "$zh"
    else
        echo "$zh / $en"
    fi
}

# 生成随机密钥
generate_secret() {
    local length=${1:-32}
    if command -v openssl &> /dev/null; then
        openssl rand -hex $length
    else
        cat /dev/urandom | tr -dc 'a-zA-Z0-9' | fold -w $((length * 2)) | head -n 1
    fi
}

# 生成随机端口号（10000-60000之间）/ Generate random port (10000-60000)
generate_random_port() {
    echo $((10000 + RANDOM % 50001))
}

# 读取用户输入（支持默认值）/ Read user input (with default)
read_input() {
    local prompt="$1"
    local default="$2"
    local is_secret="$3"
    local value=""
    
    local prompt_text=""
    if [ -n "$default" ]; then
        if [ "$is_secret" = "secret" ]; then
            if [ "$USE_ZH_ONLY" = true ]; then
                prompt_text="${prompt} [回车自动生成]: "
            else
                prompt_text="${prompt} [Enter to auto-generate]: "
            fi
        else
            if [ "$USE_ZH_ONLY" = true ]; then
                prompt_text="${prompt} [默认: ${default}]: "
            else
                prompt_text="${prompt} [Default: ${default}]: "
            fi
        fi
    else
        prompt_text="${prompt}: "
    fi
    
    read -r -p "$prompt_text" value
    
    if [ -z "$value" ]; then
        if [ "$is_secret" = "secret" ] && [ -z "$default" ]; then
            case "$prompt" in
                *JWT*|*jwt*)
                    value=$(generate_secret 64)
                    info "$(bilingual "已自动生成 JWT 密钥（128字符）" "JWT secret auto-generated (128 chars)")" >&2
                    ;;
                *管理员*|*ADMIN*|*admin*|*reset*|*Reset*)
                    value=$(generate_secret 32)
                    info "$(bilingual "已自动生成管理员重置密钥（64字符）" "Admin reset key auto-generated (64 chars)")" >&2
                    ;;
                *加密*|*CRYPTO*|*crypto*|*Encryption*)
                    value=$(generate_secret 32)
                    info "$(bilingual "已自动生成加密密钥（64字符）" "Encryption key auto-generated (64 chars)")" >&2
                    ;;
                *数据库密码*|*DB_PASSWORD*|*database*|*Database*)
                    value=$(generate_secret 16)
                    info "$(bilingual "已自动生成数据库密码（32字符）" "Database password auto-generated (32 chars)")" >&2
                    ;;
                *)
                    value="$default"
                    ;;
            esac
        else
            value="$default"
        fi
    fi
    
    echo "$value"
}

# 检查 Docker 环境 / Check Docker environment
check_docker() {
    title "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    title "  $(bilingual "步骤 1: 环境检查" "Step 1: Environment Check")"
    title "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    
    if ! command -v docker &> /dev/null; then
        error "$(bilingual "Docker 未安装" "Docker is not installed")"
        echo ""
        info "$(bilingual "请先安装 Docker：" "Please install Docker first:")"
        info "  macOS: brew install docker"
        info "  Ubuntu/Debian: apt-get install docker.io"
        info "  CentOS/RHEL: yum install docker"
        exit 1
    fi
    info "$(bilingual "Docker 已安装" "Docker installed"): $(docker --version | head -1)"
    
    if docker compose version &> /dev/null 2>&1; then
        info "$(bilingual "Docker Compose 已安装" "Docker Compose installed"): $(docker compose version)"
    elif command -v docker-compose &> /dev/null; then
        info "$(bilingual "Docker Compose 已安装" "Docker Compose installed"): $(docker-compose --version)"
    else
        error "$(bilingual "Docker Compose 未安装" "Docker Compose is not installed")"
        echo ""
        info "$(bilingual "请先安装 Docker Compose：" "Please install Docker Compose:")"
        info "  https://docs.docker.com/compose/install/"
        exit 1
    fi
    
    if ! docker info &> /dev/null; then
        error "$(bilingual "Docker 守护进程未运行" "Docker daemon is not running")"
        info "$(bilingual "请启动 Docker 服务：" "Please start Docker:")"
        info "  $(bilingual "macOS: 打开 Docker Desktop" "macOS: Open Docker Desktop")"
        info "  Linux: systemctl start docker"
        exit 1
    fi
    info "$(bilingual "Docker 守护进程运行正常" "Docker daemon is running")"
    
    echo ""
}

# 交互式配置收集 / Interactive configuration
collect_configuration() {
    title "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    title "  $(bilingual "步骤 2: 配置收集" "Step 2: Configuration")"
    title "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""
    info "$(bilingual "💡 所有配置项均为可选，直接按回车即可使用默认值或自动生成" "💡 All options are optional, press Enter for default or auto-generated values")"
    echo ""
    warn "$(bilingual "密钥配置：回车将自动生成安全的随机密钥" "Secrets: Enter to auto-generate secure random keys")"
    warn "$(bilingual "其他配置：回车将使用括号中的默认值" "Other: Enter to use default value in brackets")"
    echo ""
    
    title "$(bilingual "【基础配置】" "【Basic】")"
    echo -e "${CYAN}$(bilingual "将配置：服务器端口、MySQL端口、时区" "Server port, MySQL port, Timezone")${NC}"
    DEFAULT_PORT=$(generate_random_port)
    SERVER_PORT=$(read_input "$(bilingual "➤ 服务器端口" "➤ Server port")" "$DEFAULT_PORT")
    MYSQL_PORT=$(read_input "$(bilingual "➤ MySQL 端口（外部访问）" "➤ MySQL port (external)")" "3307")
    TZ=$(read_input "$(bilingual "➤ 时区" "➤ Timezone")" "Asia/Shanghai")
    echo ""
    
    title "$(bilingual "【数据库配置】" "【Database】")"
    echo -e "${CYAN}$(bilingual "将配置：数据库用户名、数据库密码" "Database username, password")${NC}"
    echo -e "${YELLOW}$(bilingual "💡 提示：密码留空将自动生成 32 字符的安全随机密码" "💡 Leave password empty to auto-generate 32-char password")${NC}"
    DB_USERNAME=$(read_input "$(bilingual "➤ 数据库用户名" "➤ Database username")" "root")
    DB_PASSWORD=$(read_input "$(bilingual "➤ 数据库密码" "➤ Database password")" "" "secret")
    echo ""
    
    title "$(bilingual "【安全配置】" "【Security】")"
    echo -e "${CYAN}$(bilingual "将配置：JWT密钥、管理员密码重置密钥、数据加密密钥" "JWT secret, Admin reset key, Encryption key")${NC}"
    echo -e "${YELLOW}$(bilingual "💡 提示：留空将自动生成高强度随机密钥（推荐）" "💡 Leave empty to auto-generate strong keys (recommended)")${NC}"
    JWT_SECRET=$(read_input "$(bilingual "➤ JWT 密钥" "➤ JWT secret")" "" "secret")
    ADMIN_RESET_PASSWORD_KEY=$(read_input "$(bilingual "➤ 管理员密码重置密钥" "➤ Admin password reset key")" "" "secret")
    CRYPTO_SECRET_KEY=$(read_input "$(bilingual "➤ 加密密钥（用于加密 API Key）" "➤ Encryption key (for API Key)")" "" "secret")
    echo ""
    
    title "$(bilingual "【日志配置】" "【Logging】")"
    echo -e "${CYAN}$(bilingual "将配置：Root日志级别、应用日志级别" "Root log level, App log level")${NC}"
    echo -e "${YELLOW}$(bilingual "可选级别: TRACE, DEBUG, INFO, WARN, ERROR, OFF" "Levels: TRACE, DEBUG, INFO, WARN, ERROR, OFF")${NC}"
    LOG_LEVEL_ROOT=$(read_input "$(bilingual "➤ Root 日志级别（第三方库）" "➤ Root log level (3rd party)")" "WARN")
    LOG_LEVEL_APP=$(read_input "$(bilingual "➤ 应用日志级别" "➤ App log level")" "INFO")
    echo ""
    
    SPRING_PROFILES_ACTIVE="prod"
    ALLOW_PRERELEASE="false"
    GITHUB_REPO="WrBug/PolyHermes"
}

# 下载 docker-compose.prod.yml（如果不存在）/ Download docker-compose.prod.yml if missing
download_docker_compose_file() {
    title "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    title "  $(bilingual "步骤 3: 获取部署配置" "Step 3: Get Deploy Config")"
    title "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    
    if [ -f "docker-compose.prod.yml" ]; then
        info "$(bilingual "检测到现有 docker-compose.prod.yml，跳过下载" "Existing docker-compose.prod.yml found, skip download")"
        echo ""
        return 0
    fi
    
    info "$(bilingual "正在从 GitHub 下载 docker-compose.prod.yml..." "Downloading docker-compose.prod.yml from GitHub...")"
    
    local compose_url="https://raw.githubusercontent.com/WrBug/PolyHermes/main/docker-compose.prod.yml"
    
    if curl -fsSL "$compose_url" -o docker-compose.prod.yml; then
        info "$(bilingual "docker-compose.prod.yml 下载成功" "docker-compose.prod.yml downloaded")"
    else
        error "$(bilingual "docker-compose.prod.yml 下载失败" "Failed to download docker-compose.prod.yml")"
        warn "$(bilingual "请检查网络连接或手动下载：" "Check network or download manually:")"
        warn "  $compose_url"
        exit 1
    fi
    
    echo ""
}

# 生成 .env 文件 / Generate .env file
generate_env_file() {
    title "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    title "  $(bilingual "步骤 4: 生成环境变量文件" "Step 4: Generate .env")"
    title "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    
    if [ -f ".env" ]; then
        BACKUP_FILE=".env.backup.$(date +%Y%m%d_%H%M%S)"
        cp .env "$BACKUP_FILE"
        warn "$(bilingual "已备份现有配置文件到" "Backed up existing config to"): $BACKUP_FILE"
    fi
    
    cat > .env <<EOF
# ========================================
# PolyHermes Production Config / 生产环境配置
# Generated / 生成时间: $(date '+%Y-%m-%d %H:%M:%S')
# ========================================

# ============================================
# Basic / 基础配置
# ============================================
TZ=${TZ}
SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE}
SERVER_PORT=${SERVER_PORT}
MYSQL_PORT=${MYSQL_PORT}

# ============================================
# Database / 数据库配置
# ============================================
DB_URL=jdbc:mysql://mysql:3306/polyhermes?useSSL=false&serverTimezone=UTC&characterEncoding=utf8&allowPublicKeyRetrieval=true
DB_USERNAME=${DB_USERNAME}
DB_PASSWORD=${DB_PASSWORD}

# ============================================
# Security (keep safe) / 安全配置（请妥善保管）
# ============================================
JWT_SECRET=${JWT_SECRET}
ADMIN_RESET_PASSWORD_KEY=${ADMIN_RESET_PASSWORD_KEY}
CRYPTO_SECRET_KEY=${CRYPTO_SECRET_KEY}

# ============================================
# Logging / 日志配置
# ============================================
LOG_LEVEL_ROOT=${LOG_LEVEL_ROOT}
LOG_LEVEL_APP=${LOG_LEVEL_APP}

# ============================================
# Other / 其他配置
# ============================================
ALLOW_PRERELEASE=${ALLOW_PRERELEASE}
GITHUB_REPO=${GITHUB_REPO}
EOF
    
    info "$(bilingual "配置文件已生成" "Config file generated"): .env"
    echo ""
    
    title "$(bilingual "【配置摘要】" "【Config Summary】")"
    echo "  $(bilingual "服务器端口" "Server port"): ${SERVER_PORT}"
    echo "  $(bilingual "MySQL 端口" "MySQL port"): ${MYSQL_PORT}"
    echo "  $(bilingual "时区" "Timezone"): ${TZ}"
    echo "  $(bilingual "数据库用户" "DB user"): ${DB_USERNAME}"
    echo "  $(bilingual "数据库密码" "DB password"): ${DB_PASSWORD:0:8}... $(bilingual "(已隐藏)" "(hidden)")"
    echo "  $(bilingual "JWT 密钥" "JWT secret"): ${JWT_SECRET:0:16}... $(bilingual "(已隐藏)" "(hidden)")"
    echo "  $(bilingual "管理员重置密钥" "Admin reset key"): ${ADMIN_RESET_PASSWORD_KEY:0:16}... $(bilingual "(已隐藏)" "(hidden)")"
    echo "  $(bilingual "加密密钥" "Encryption key"): ${CRYPTO_SECRET_KEY:0:16}... $(bilingual "(已隐藏)" "(hidden)")"
    echo "  $(bilingual "日志级别" "Log level"): Root=${LOG_LEVEL_ROOT}, App=${LOG_LEVEL_APP}"
    echo ""
}

# 拉取镜像 / Pull images
pull_images() {
    title "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    title "  $(bilingual "步骤 5: 拉取 Docker 镜像" "Step 5: Pull Docker Images")"
    title "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    
    info "$(bilingual "正在从 Docker Hub 拉取最新镜像..." "Pulling latest images from Docker Hub...")"
    
    if docker pull wrbug/polyhermes:latest; then
        info "$(bilingual "应用镜像拉取成功" "App image pulled"): wrbug/polyhermes:latest"
    else
        error "$(bilingual "应用镜像拉取失败" "Failed to pull app image")"
        warn "$(bilingual "可能的原因：" "Possible reasons:")"
        warn "  1. $(bilingual "网络连接问题" "Network issue")"
        warn "  2. $(bilingual "Docker Hub 服务异常" "Docker Hub unavailable")"
        warn "  3. $(bilingual "镜像不存在" "Image not found")"
        exit 1
    fi
    
    if docker pull mysql:8.2; then
        info "$(bilingual "MySQL 镜像拉取成功" "MySQL image pulled"): mysql:8.2"
    else
        warn "$(bilingual "MySQL 镜像拉取失败，将在启动时自动下载" "MySQL pull failed, will download on start")"
    fi
    
    echo ""
}

# 部署服务 / Deploy services
deploy_services() {
    title "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    title "  $(bilingual "步骤 6: 部署服务" "Step 6: Deploy Services")"
    title "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    
    if docker compose -f docker-compose.prod.yml ps -q 2>/dev/null | grep -q .; then
        warn "$(bilingual "检测到正在运行的服务，正在停止..." "Stopping existing services...")"
        docker compose -f docker-compose.prod.yml down
        info "$(bilingual "已停止现有服务" "Stopped existing services")"
    fi
    
    info "$(bilingual "正在启动服务..." "Starting services...")"
    if docker compose -f docker-compose.prod.yml up -d; then
        info "$(bilingual "服务启动成功" "Services started")"
    else
        error "$(bilingual "服务启动失败" "Failed to start services")"
        error "$(bilingual "请检查日志" "Check logs"): docker compose -f docker-compose.prod.yml logs"
        exit 1
    fi
    
    echo ""
}

# 健康检查 / Health check
health_check() {
    title "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    title "  $(bilingual "步骤 7: 健康检查" "Step 7: Health Check")"
    title "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    
    info "$(bilingual "等待服务启动（最多等待 60 秒）..." "Waiting for services (up to 60s)...")"
    
    local max_attempts=12
    local attempt=0
    
    while [ $attempt -lt $max_attempts ]; do
        attempt=$((attempt + 1))
        
        if docker compose -f docker-compose.prod.yml ps | grep -q "Up"; then
            info "$(bilingual "容器运行正常" "Containers are up")"
            
            if curl -s -o /dev/null -w "%{http_code}" http://localhost:${SERVER_PORT} | grep -q "200\|302\|401"; then
                info "$(bilingual "应用响应正常" "App is responding")"
                echo ""
                return 0
            fi
        fi
        
        echo -n "."
        sleep 5
    done
    
    echo ""
    warn "$(bilingual "健康检查超时，请手动检查服务状态" "Health check timeout, please check services manually")"
    warn "$(bilingual "查看日志" "View logs"): docker compose -f docker-compose.prod.yml logs -f"
    echo ""
}

# 显示部署信息 / Show deployment info
show_deployment_info() {
    title "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    title "  $(bilingual "部署完成！" "Deployment Complete!")"
    title "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""
    
    info "$(bilingual "访问地址" "Access URL"): ${GREEN}http://localhost:${SERVER_PORT}${NC}"
    echo ""
    
    title "$(bilingual "【常用命令】" "【Common Commands】")"
    echo -e "  $(bilingual "查看服务状态" "Status"): ${CYAN}docker compose -f docker-compose.prod.yml ps${NC}"
    echo -e "  $(bilingual "查看日志" "Logs"): ${CYAN}docker compose -f docker-compose.prod.yml logs -f${NC}"
    echo -e "  $(bilingual "停止服务" "Stop"): ${CYAN}docker compose -f docker-compose.prod.yml down${NC}"
    echo -e "  $(bilingual "重启服务" "Restart"): ${CYAN}docker compose -f docker-compose.prod.yml restart${NC}"
    echo -e "  $(bilingual "更新镜像" "Update"): ${CYAN}docker pull wrbug/polyhermes:latest && docker compose -f docker-compose.prod.yml up -d${NC}"
    echo ""
    
    title "$(bilingual "【数据库连接信息】" "【Database Connection】")"
    echo -e "  $(bilingual "主机" "Host"): ${CYAN}localhost${NC}"
    echo -e "  $(bilingual "端口" "Port"): ${CYAN}${MYSQL_PORT}${NC}"
    echo -e "  $(bilingual "数据库" "Database"): ${CYAN}polyhermes${NC}"
    echo -e "  $(bilingual "用户名" "Username"): ${CYAN}${DB_USERNAME}${NC}"
    echo -e "  $(bilingual "密码" "Password"): ${CYAN}${DB_PASSWORD}${NC}"
    echo ""
    
    title "$(bilingual "【管理员重置密钥】" "【Admin Reset Key】")"
    echo -e "  $(bilingual "重置密钥" "Reset key"): ${CYAN}${ADMIN_RESET_PASSWORD_KEY}${NC}"
    echo -e "  ${YELLOW}$(bilingual "💡 此密钥用于重置管理员密码，请妥善保管" "💡 Keep this key safe; it is used to reset admin password")${NC}"
    echo ""
    
    warn "$(bilingual "重要提示：" "Important:")"
    warn "  1. $(bilingual "请妥善保管 .env 文件，勿提交到版本控制系统" "Keep .env secure; do not commit to version control")"
    warn "  2. $(bilingual "定期备份数据库数据（位于 Docker volume: polyhermes_mysql-data）" "Back up DB regularly (Docker volume: polyhermes_mysql-data)")"
    warn "  3. $(bilingual "生产环境建议配置反向代理（如 Nginx）并启用 HTTPS" "Use a reverse proxy (e.g. Nginx) and HTTPS in production")"
    echo ""
}

# 主函数 / Main
main() {
    clear
    
    echo ""
    title "========================================="
    title "   $(bilingual "PolyHermes 交互式一键部署脚本" "PolyHermes Interactive Deploy")   "
    title "========================================="
    echo ""
    
    check_docker
    
    if [ -f ".env" ]; then
        echo ""
        title "$(bilingual "【检测到现有配置】" "【Existing Config Found】")"
        info "$(bilingual "发现已存在的 .env 配置文件" "Found existing .env file")"
        echo ""
        echo -ne "${YELLOW}$(bilingual "是否使用现有配置直接更新镜像？[Y/n]" "Use existing config to update images? [Y/n]"): ${NC}"
        read -r use_existing
        use_existing=${use_existing:-Y}
        
        if [[ "$use_existing" =~ ^[Yy]$ ]]; then
            info "$(bilingual "将使用现有配置，跳过配置步骤" "Using existing config, skipping configuration")"
            echo ""
            source .env 2>/dev/null || true
        else
            warn "$(bilingual "将重新配置，现有配置将被备份" "Will reconfigure; existing config will be backed up")"
            echo ""
            collect_configuration
        fi
    else
        collect_configuration
    fi
    
    download_docker_compose_file
    
    if [[ ! "$use_existing" =~ ^[Yy]$ ]] || [ ! -f ".env" ]; then
        generate_env_file
    fi
    
    echo ""
    title "$(bilingual "【确认部署】" "【Confirm Deploy】")"
    echo -ne "${YELLOW}$(bilingual "是否开始部署？[Y/n]（回车默认为是）" "Start deployment? [Y/n] (Enter = Yes)"): ${NC}"
    read -r confirm
    
    confirm=${confirm:-Y}
    if [[ "$confirm" =~ ^[Nn]$ ]]; then
        warn "$(bilingual "部署已取消" "Deployment cancelled")"
        exit 0
    fi
    
    echo ""
    pull_images
    deploy_services
    health_check
    show_deployment_info
    
    info "$(bilingual "部署流程已完成！" "Deployment finished!")"
}

# 捕获 Ctrl+C / Handle Ctrl+C
trap 'echo ""; warn "$(bilingual "部署已中断" "Deployment interrupted")"; exit 1' INT

# 运行主函数 / Run main
main "$@"
