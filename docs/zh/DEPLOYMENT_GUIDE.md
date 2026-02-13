# PolyHermes 一键部署指南

本文档介绍如何使用交互式一键部署脚本快速部署 PolyHermes 应用。

## 📋 前置要求

### 必需软件

- **Docker**: 版本 20.10 或更高
- **Docker Compose**: 版本 2.0 或更高（或 `docker-compose` v1.29+）

### 环境准备

```bash
# macOS 安装 Docker
brew install docker

# Ubuntu/Debian 安装 Docker
curl -fsSL https://get.docker.com | sh

# CentOS/RHEL 安装 Docker  
yum install docker-ce docker-ce-cli containerd.io
```

## 🚀 快速开始

### 1. 克隆项目（如果尚未克隆）

```bash
git clone https://github.com/WrBug/PolyHermes.git
cd PolyHermes
```

### 2. 运行部署脚本

```bash
./deploy-interactive.sh
```

### 3. 按提示配置

脚本会引导你完成以下配置：

#### 基础配置
- **服务器端口**：应用对外暴露的端口（默认：80）
- **MySQL 端口**：数据库外部访问端口（默认：3307，避免与本地 MySQL 冲突）
- **时区**：服务器时区（默认：Asia/Shanghai）

#### 数据库配置
- **数据库用户名**：默认为 `root`
- **数据库密码**：回车自动生成 32 字符随机密码

#### 安全配置（关键）
- **JWT 密钥**：回车自动生成 128 字符随机密钥
- **管理员密码重置密钥**：回车自动生成 64 字符随机密钥
- **加密密钥**：用于加密 API Key，回车自动生成 64 字符随机密钥

#### 日志配置
- **Root 日志级别**：第三方库日志级别（默认：WARN）
  - 可选：`TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, `OFF`
- **应用日志级别**：应用代码日志级别（默认：INFO）

#### 其他配置
- **Spring Profile**：环境配置（默认：prod）
- **允许预发布版本**：是否允许自动更新到预发布版本（默认：false）
- **GitHub 仓库**：项目仓库地址（默认：WrBug/PolyHermes）

### 4. 确认并部署

配置完成后，脚本会：
1. 生成 `.env` 配置文件
2. 显示配置摘要
3. 请求确认部署
4. 拉取 Docker Hub 镜像
5. 启动服务
6. 执行健康检查

## 📝 配置示例

### 使用默认值部署（推荐）

所有配置项直接回车，脚本会自动生成安全的随机密钥：

```
服务器端口 [默认: 80]: ⏎
MySQL 端口（外部访问） [默认: 3307]: ⏎
时区 [默认: Asia/Shanghai]: ⏎
数据库用户名 [默认: root]: ⏎
数据库密码 [回车自动生成]: ⏎
JWT 密钥 [回车自动生成]: ⏎
管理员密码重置密钥 [回车自动生成]: ⏎
加密密钥（用于加密 API Key） [回车自动生成]: ⏎
Root 日志级别（第三方库） [默认: WARN]: ⏎
应用日志级别 [默认: INFO]: ⏎
Spring Profile [默认: prod]: ⏎
允许预发布版本更新 [默认: false]: ⏎
GitHub 仓库 [默认: WrBug/PolyHermes]: ⏎
```

### 自定义端口部署

如果需要使用不同的端口：

```
服务器端口 [默认: 80]: 8080⏎
MySQL 端口（外部访问） [默认: 3307]: 33306⏎
```

### 开发环境部署

启用 DEBUG 日志：

```
Root 日志级别（第三方库） [默认: WARN]: DEBUG⏎
应用日志级别 [默认: INFO]: DEBUG⏎
```

## 🔧 部署后管理

### 访问应用

部署完成后，访问：

```
http://localhost:80
```

（或你配置的自定义端口）

### 常用命令

```bash
# 查看服务状态
docker compose -f docker-compose.prod.yml ps

# 查看实时日志
docker compose -f docker-compose.prod.yml logs -f

# 仅查看应用日志
docker compose -f docker-compose.prod.yml logs -f app

# 停止服务
docker compose -f docker-compose.prod.yml down

# 重启服务
docker compose -f docker-compose.prod.yml restart

# 更新到最新版本
docker pull wrbug/polyhermes:latest
docker compose -f docker-compose.prod.yml up -d
```

### 数据库连接

使用配置的凭据连接到 MySQL：

```bash
mysql -h 127.0.0.1 -P 3307 -u root -p
# 输入你在部署时设置的数据库密码
```

或使用图形化工具（如 DBeaver、Navicat）：
- **主机**: `localhost`
- **端口**: `3307`（或你配置的端口）
- **数据库**: `polyhermes`
- **用户名**: `root`（或你配置的用户名）
- **密码**: 部署时设置的密码（可在 `.env` 文件中查看）

## 🔐 安全最佳实践

### 保护配置文件

```bash
# 设置 .env 文件权限
chmod 600 .env

# 确保 .env 已添加到 .gitignore
echo ".env" >> .gitignore
```

### 定期更换密钥

生产环境建议定期更换安全密钥：

```bash
# 生成新的 JWT 密钥（128字符）
openssl rand -hex 64

# 生成新的管理员重置密钥（64字符）
openssl rand -hex 32

# 更新 .env 文件后重启服务
docker compose -f docker-compose.prod.yml restart
```

### 备份数据库

```bash
# 备份数据库
docker exec polyhermes-mysql mysqldump -u root -p polyhermes > backup_$(date +%Y%m%d).sql

# 恢复数据库
docker exec -i polyhermes-mysql mysql -u root -p polyhermes < backup_20260201.sql
```

## 🌐 生产环境部署建议

### 1. 使用反向代理

建议使用 Nginx 或 Caddy 作为反向代理：

```nginx
# Nginx 配置示例
server {
    listen 443 ssl http2;
    server_name polyhermes.yourdomain.com;
    
    ssl_certificate /path/to/cert.pem;
    ssl_certificate_key /path/to/key.pem;
    
    location / {
        proxy_pass http://localhost:80;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

### 2. 配置防火墙

```bash
# UFW (Ubuntu)
ufw allow 80/tcp
ufw allow 443/tcp
ufw enable

# Firewalld (CentOS)
firewall-cmd --add-service=http --permanent
firewall-cmd --add-service=https --permanent
firewall-cmd --reload
```

### 3. 启用自动更新

配置定时任务自动检查并更新镜像：

```bash
# 创建更新脚本
cat > /opt/polyhermes-update.sh <<'EOF'
#!/bin/bash
cd /path/to/PolyHermes
docker pull wrbug/polyhermes:latest
docker compose -f docker-compose.prod.yml up -d
EOF

chmod +x /opt/polyhermes-update.sh

# 添加到 crontab（每天凌晨 3 点检查更新）
echo "0 3 * * * /opt/polyhermes-update.sh >> /var/log/polyhermes-update.log 2>&1" | crontab -
```

## 🐛 故障排查

### 服务无法启动

```bash
# 查看详细错误日志
docker compose -f docker-compose.prod.yml logs

# 检查容器状态
docker compose -f docker-compose.prod.yml ps
```

### 数据库连接失败

```bash
# 检查 MySQL 容器状态
docker logs polyhermes-mysql

# 测试数据库连接
docker exec polyhermes-mysql mysql -u root -p -e "SELECT 1"
```

### 端口被占用

```bash
# 查找占用端口的进程
lsof -i :80
# 或
netstat -tulpn | grep :80

# 修改 SERVER_PORT 环境变量
vim .env  # 修改 SERVER_PORT=8080
docker compose -f docker-compose.prod.yml up -d
```

### 镜像拉取失败

如果 Docker Hub 访问受限，可以配置镜像加速器：

```bash
# 配置 Docker 镜像加速器
sudo mkdir -p /etc/docker
sudo tee /etc/docker/daemon.json <<-'EOF'
{
  "registry-mirrors": [
    "https://docker.mirrors.ustc.edu.cn",
    "https://hub-mirror.c.163.com"
  ]
}
EOF

sudo systemctl restart docker
```

## 📚 更多资源

- [项目 README](../../README.md)
- [发布日志](../../RELEASE.md)
- [GitHub 仓库](https://github.com/WrBug/PolyHermes)
- [问题反馈](https://github.com/WrBug/PolyHermes/issues)

## 📞 获取帮助

如遇到问题，请：

1. 查看上方的**故障排查**章节
2. 检查 [GitHub Issues](https://github.com/WrBug/PolyHermes/issues)
3. 提交新的 Issue 并附上日志输出

---

**祝部署顺利！** 🎉
