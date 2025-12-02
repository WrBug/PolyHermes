# PolyHermes 部署文档

本文档介绍如何部署 PolyHermes 项目，包括后端和前端的不同部署方式。

## 目录

- [一体化部署（推荐）](#一体化部署推荐)
- [后端部署](#后端部署)
  - [Java 直接部署](#java-直接部署)
  - [Docker 部署](#docker-部署)
- [前端部署](#前端部署)
- [环境配置](#环境配置)
- [常见问题](#常见问题)

## 一体化部署（推荐）

将前后端一起部署到一个 Docker 容器中，使用 Nginx 提供前端静态文件并代理后端 API。

### 前置要求

- Docker 20.10+
- Docker Compose 2.0+

### 部署步骤

1. **使用部署脚本（推荐）**

```bash
# 在项目根目录
./deploy.sh
```

脚本会自动：
- 检查 Docker 环境
- 创建 `.env` 配置文件（如果不存在）
- 构建 Docker 镜像（包含前后端）
- 启动服务（应用 + MySQL）

2. **手动部署**

```bash
# 创建 .env 文件
cat > .env <<EOF
DB_URL=jdbc:mysql://mysql:3306/polyhermes?useSSL=false&serverTimezone=UTC&characterEncoding=utf8&allowPublicKeyRetrieval=true
DB_USERNAME=root
DB_PASSWORD=your_password_here
SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=80
POLYGON_RPC_URL=https://polygon-rpc.com
JWT_SECRET=your-jwt-secret-key-change-in-production
ADMIN_RESET_PASSWORD_KEY=your-admin-reset-key-change-in-production
EOF

# 构建并启动
docker-compose build
docker-compose up -d

# 查看日志
docker-compose logs -f

# 停止服务
docker-compose down
```

3. **访问应用**

- 前端和后端统一访问：`http://localhost:80`
- Nginx 自动处理：
  - `/api/*` → 后端 API（`localhost:8000`）
  - `/ws` → 后端 WebSocket（`localhost:8000`）
  - 其他路径 → 前端静态文件

### 架构说明

```
用户请求
  ↓
Nginx (端口 80)
  ├─ /api/* → 后端服务 (localhost:8000)
  ├─ /ws → 后端 WebSocket (localhost:8000)
  └─ /* → 前端静态文件 (/usr/share/nginx/html)
```

### 优势

- ✅ 单一容器，简化部署
- ✅ 统一端口，无需配置 CORS
- ✅ 自动处理前后端路由
- ✅ 生产环境就绪

## 后端部署

### Java 直接部署

#### 前置要求

- JDK 17+
- MySQL 8.0+
- Gradle 7.5+（或使用 Gradle Wrapper）

#### 部署步骤

1. **构建应用**

```bash
cd backend
./gradlew clean bootJar
```

构建产物位于 `build/libs/polyhermes-backend-1.0.0.jar`

2. **使用部署脚本（推荐）**

```bash
# 构建并创建部署文件
./deploy.sh java

# 或仅构建
./deploy.sh build
```

脚本会自动：
- 检查 Java 环境
- 构建应用
- 创建部署目录和启动脚本
- 生成 systemd 服务文件（可选）

3. **手动启动**

```bash
# 开发环境
java -jar build/libs/polyhermes-backend-1.0.0.jar --spring.profiles.active=dev

# 生产环境
java -jar build/libs/polyhermes-backend-1.0.0.jar --spring.profiles.active=prod
```

4. **使用 systemd 管理（Linux）**

```bash
# 复制服务文件
sudo cp deploy/polyhermes-backend.service /etc/systemd/system/

# 编辑服务文件，修改路径和用户
sudo nano /etc/systemd/system/polyhermes-backend.service

# 启动服务
sudo systemctl daemon-reload
sudo systemctl enable polyhermes-backend
sudo systemctl start polyhermes-backend

# 查看日志
sudo journalctl -u polyhermes-backend -f
```

### Docker 部署

#### 前置要求

- Docker 20.10+
- Docker Compose 2.0+

#### 部署步骤

1. **使用部署脚本（推荐）**

```bash
cd backend
./deploy.sh docker
```

脚本会自动：
- 检查 Docker 环境
- 创建 `.env` 配置文件（如果不存在）
- 构建 Docker 镜像
- 启动服务

2. **手动部署**

```bash
# 创建 .env 文件
cat > .env <<EOF
DB_URL=jdbc:mysql://mysql:3306/polyhermes?useSSL=false&serverTimezone=UTC&characterEncoding=utf8&allowPublicKeyRetrieval=true
DB_USERNAME=root
DB_PASSWORD=your_password_here
SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=8000
POLYGON_RPC_URL=https://polygon-rpc.com
JWT_SECRET=your-jwt-secret-key-change-in-production
ADMIN_RESET_PASSWORD_KEY=your-admin-reset-key-change-in-production
EOF

# 构建并启动
docker-compose up -d

# 查看日志
docker-compose logs -f

# 停止服务
docker-compose down
```

3. **仅构建镜像**

```bash
docker build -t polyhermes-backend:latest .
```

4. **运行容器**

```bash
docker run -d \
  --name polyhermes-backend \
  -p 8000:8000 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e DB_URL=jdbc:mysql://host.docker.internal:3306/polyhermes?useSSL=false&allowPublicKeyRetrieval=true \
  -e DB_USERNAME=root \
  -e DB_PASSWORD=your_password \
  -e JWT_SECRET=your-jwt-secret \
  polyhermes-backend:latest
```

## 前端部署

### 构建步骤

1. **使用构建脚本（推荐）**

```bash
cd frontend

# 使用默认后端地址（http://127.0.0.1:8000）
./build.sh

# 或指定自定义后端地址
./build.sh --api-url http://your-backend-server.com:8000

# 或使用环境变量
VITE_API_URL=http://your-backend-server.com:8000 ./build.sh
```

2. **手动构建**

```bash
cd frontend

# 创建环境配置文件
cat > .env.production <<EOF
VITE_API_URL=http://your-backend-server.com:8000
VITE_WS_URL=ws://your-backend-server.com:8000
EOF

# 安装依赖（首次）
npm install

# 构建
npm run build
```

构建产物位于 `dist/` 目录。

### 部署方式

#### 方式1：Nginx 部署

```nginx
server {
    listen 80;
    server_name your-domain.com;
    
    root /path/to/frontend/dist;
    index index.html;
    
    # API 代理
    location /api {
        proxy_pass http://localhost:8000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
    
    # WebSocket 代理
    location /ws {
        proxy_pass http://localhost:8000;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
    }
    
    # 前端路由（SPA）
    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

#### 方式2：Apache 部署

```apache
<VirtualHost *:80>
    ServerName your-domain.com
    DocumentRoot /path/to/frontend/dist
    
    # API 代理
    ProxyPass /api http://localhost:8000/api
    ProxyPassReverse /api http://localhost:8000/api
    
    # WebSocket 代理
    ProxyPass /ws ws://localhost:8000/ws
    ProxyPassReverse /ws ws://localhost:8000/ws
    
    # 前端路由（SPA）
    <Directory /path/to/frontend/dist>
        Options Indexes FollowSymLinks
        AllowOverride All
        Require all granted
        RewriteEngine On
        RewriteBase /
        RewriteRule ^index\.html$ - [L]
        RewriteCond %{REQUEST_FILENAME} !-f
        RewriteCond %{REQUEST_FILENAME} !-d
        RewriteRule . /index.html [L]
    </Directory>
</VirtualHost>
```

#### 方式3：使用 serve（开发/测试）

```bash
# 安装 serve
npm install -g serve

# 启动服务
serve -s dist -l 3000
```

## 环境配置

### 后端环境变量

| 变量名 | 说明 | 默认值 | 必需 |
|--------|------|--------|------|
| `SPRING_PROFILES_ACTIVE` | Spring Profile | `dev` | 否 |
| `DB_URL` | 数据库连接 URL | - | 是（生产） |
| `DB_USERNAME` | 数据库用户名 | `root` | 是（生产） |
| `DB_PASSWORD` | 数据库密码 | - | 是（生产） |
| `SERVER_PORT` | 服务器端口 | `8000` | 否 |
| `POLYGON_RPC_URL` | Polygon RPC 地址 | `https://polygon-rpc.com` | 否 |
| `JWT_SECRET` | JWT 密钥 | - | 是（生产） |
| `ADMIN_RESET_PASSWORD_KEY` | 管理员密码重置密钥 | - | 是（生产） |

### 前端环境变量

| 变量名 | 说明 | 默认值 |
|--------|------|--------|
| `VITE_API_URL` | 后端 API 地址 | `http://127.0.0.1:8000` |
| `VITE_WS_URL` | WebSocket 地址 | `ws://127.0.0.1:8000` |

### 配置文件说明

#### 后端配置文件

- `application.properties` - 基础配置（所有环境共享）
- `application-dev.properties` - 开发环境配置
- `application-prod.properties` - 生产环境配置

通过 `--spring.profiles.active=prod` 或环境变量 `SPRING_PROFILES_ACTIVE=prod` 切换环境。

#### 前端环境变量

Vite 使用 `.env.production` 文件在构建时注入环境变量。构建脚本会自动创建此文件。

## 常见问题

### 1. 数据库连接失败

**问题**: 后端无法连接数据库

**解决方案**:
- 检查数据库服务是否运行
- 检查数据库连接 URL、用户名、密码是否正确
- 检查防火墙是否允许连接
- 对于 Docker 部署，确保使用正确的数据库地址（`mysql` 而非 `localhost`）

### 2. 前端无法连接后端

**问题**: 前端请求后端 API 失败

**解决方案**:
- 检查后端服务是否运行
- 检查 `VITE_API_URL` 配置是否正确
- 检查 CORS 配置（如果跨域）
- 检查网络连接和防火墙

### 3. WebSocket 连接失败

**问题**: WebSocket 无法建立连接

**解决方案**:
- 检查 `VITE_WS_URL` 配置是否正确
- 检查 WebSocket 代理配置（Nginx/Apache）
- 检查防火墙是否允许 WebSocket 连接
- 检查后端 WebSocket 服务是否正常

### 4. Docker 容器无法访问数据库

**问题**: Docker 容器中的后端无法连接宿主机数据库

**解决方案**:
- 使用 `host.docker.internal` 作为数据库地址（Mac/Windows）
- 使用 Docker 网络连接（推荐使用 docker-compose）
- 检查数据库是否允许远程连接

### 5. 构建失败

**问题**: 前端或后端构建失败

**解决方案**:
- 检查 Node.js 版本（需要 18+）
- 检查 Java 版本（需要 17+）
- 清理缓存后重新构建：
  ```bash
  # 前端
  rm -rf node_modules dist
  npm install
  npm run build
  
  # 后端
  ./gradlew clean build
  ```

## 生产环境检查清单

- [ ] 修改所有默认密码和密钥（JWT_SECRET、ADMIN_RESET_PASSWORD_KEY、数据库密码）
- [ ] 配置正确的数据库连接（使用 SSL）
- [ ] 设置正确的 Spring Profile（`prod`）
- [ ] 配置正确的后端 API 地址（前端）
- [ ] 配置反向代理（Nginx/Apache）
- [ ] 配置 HTTPS（生产环境推荐）
- [ ] 配置防火墙规则
- [ ] 设置日志轮转
- [ ] 配置监控和告警
- [ ] 定期备份数据库

## 性能优化建议

### 后端

- 调整 JVM 参数（堆内存、GC 策略）
- 配置数据库连接池大小
- 启用 HTTP 压缩
- 配置缓存策略

### 前端

- 启用 Gzip 压缩（Nginx）
- 配置静态资源缓存
- 使用 CDN 加速
- 启用 HTTP/2

## 安全建议

- 使用 HTTPS（生产环境必须）
- 配置 CORS 白名单
- 定期更新依赖包
- 使用强密码和密钥
- 限制数据库访问权限
- 配置防火墙规则
- 定期备份数据
- 监控异常访问

## 技术支持

如有问题，请提交 Issue 到 [GitHub](https://github.com/WrBug/PolyHermes) 或联系 [Twitter](https://x.com/quant_tr)。

