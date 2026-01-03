#!/bin/bash

# PolyHermes 后端部署脚本
# 支持 Java 直接部署和 Docker 部署

set -e

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 配置
APP_NAME="polyhermes-backend"
JAR_NAME="polyhermes-backend-1.0.0.jar"
DEPLOY_DIR="./deploy"
PROFILE="${SPRING_PROFILES_ACTIVE:-prod}"

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

# 检查 Java 环境
check_java() {
    if ! command -v java &> /dev/null; then
        error "Java 未安装，请先安装 Java 17+"
        exit 1
    fi
    
    JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
    if [ "$JAVA_VERSION" -lt 17 ]; then
        error "Java 版本过低，需要 Java 17+，当前版本: $JAVA_VERSION"
        exit 1
    fi
    
    info "Java 环境检查通过: $(java -version 2>&1 | head -n 1)"
}

# 构建应用
build_app() {
    info "开始构建应用..."
    
    if ! command -v gradle &> /dev/null; then
        error "Gradle 未安装，请先安装 Gradle 或使用 Gradle Wrapper"
        exit 1
    fi
    
    ./gradlew clean bootJar
    
    if [ ! -f "build/libs/$JAR_NAME" ]; then
        error "构建失败，JAR 文件不存在"
        exit 1
    fi
    
    info "构建完成: build/libs/$JAR_NAME"
}

# Java 部署
deploy_java() {
    info "使用 Java 方式部署..."
    
    # 创建部署目录
    mkdir -p "$DEPLOY_DIR"
    
    # 复制 JAR 文件
    cp "build/libs/$JAR_NAME" "$DEPLOY_DIR/"
    
    # 创建启动脚本
    cat > "$DEPLOY_DIR/start.sh" <<EOF
#!/bin/bash
cd \$(dirname \$0)

# 设置 JVM 参数
JVM_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

# 启动应用
java \$JVM_OPTS -jar $JAR_NAME --spring.profiles.active=$PROFILE
EOF
    
    chmod +x "$DEPLOY_DIR/start.sh"
    
    # 创建 systemd 服务文件（可选）
    cat > "$DEPLOY_DIR/${APP_NAME}.service" <<EOF
[Unit]
Description=PolyHermes Backend Service
After=network.target mysql.service

[Service]
Type=simple
User=\${USER}
WorkingDirectory=$DEPLOY_DIR
ExecStart=/usr/bin/java -Xms512m -Xmx1024m -XX:+UseG1GC -jar $JAR_NAME --spring.profiles.active=$PROFILE
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF
    
    info "Java 部署文件已创建在: $DEPLOY_DIR"
    info "启动方式: cd $DEPLOY_DIR && ./start.sh"
    info "或使用 systemd: sudo cp ${APP_NAME}.service /etc/systemd/system/ && sudo systemctl start ${APP_NAME}"
}

# Docker 部署
deploy_docker() {
    info "使用 Docker 方式部署..."
    
    if ! command -v docker &> /dev/null; then
        error "Docker 未安装，请先安装 Docker"
        exit 1
    fi
    
    if ! command -v docker-compose &> /dev/null; then
        error "Docker Compose 未安装，请先安装 Docker Compose"
        exit 1
    fi
    
    # 生成随机字符串
    generate_random_string() {
        local length=${1:-32}
        openssl rand -hex $length 2>/dev/null || \
        cat /dev/urandom | tr -dc 'a-zA-Z0-9' | fold -w $length | head -n 1
    }
    
    # 创建 .env 文件（如果不存在）
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

# 服务器端口
SERVER_PORT=8000

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
    
    # 构建并启动
    info "构建 Docker 镜像..."
    docker-compose build
    
    info "启动服务..."
    docker-compose up -d
    
    info "Docker 部署完成"
    info "查看日志: docker-compose logs -f"
    info "停止服务: docker-compose down"
}

# 主函数
main() {
    echo "=========================================="
    echo "  PolyHermes 后端部署脚本"
    echo "=========================================="
    echo ""
    
    # 解析参数
    DEPLOY_MODE="${1:-java}"
    
    case "$DEPLOY_MODE" in
        java)
            check_java
            build_app
            deploy_java
            ;;
        docker)
            deploy_docker
            ;;
        build)
            check_java
            build_app
            ;;
        *)
            echo "用法: $0 [java|docker|build]"
            echo ""
            echo "  java   - Java 直接部署（默认）"
            echo "  docker - Docker 部署"
            echo "  build  - 仅构建应用"
            exit 1
            ;;
    esac
}

main "$@"

