# PolyHermes Deployment Guide

> 📖 **中文版本**: [部署文档（中文）](../zh/DEPLOYMENT.md)

This document describes how to deploy the PolyHermes project, including different deployment methods for backend and frontend.

## Table of Contents

- [All-in-One Deployment (Recommended)](#all-in-one-deployment-recommended)
  - [Using Docker Hub Images](#using-docker-hub-images-recommended-for-production)
  - [Using External Nginx Reverse Proxy](#using-external-nginx-reverse-proxy-recommended-for-production)
- [Backend Deployment](#backend-deployment)
  - [Java Direct Deployment](#java-direct-deployment)
  - [Docker Deployment](#docker-deployment)
- [Frontend Deployment](#frontend-deployment)
- [Environment Configuration](#environment-configuration)
- [FAQ](#faq)

## All-in-One Deployment (Recommended)

Deploy both frontend and backend together in a single Docker container, using Nginx to serve frontend static files and proxy backend API.

### Prerequisites

- Docker 20.10+
- Docker Compose 2.0+

### Deployment Steps

1. **Using Docker Hub Images (Recommended, Production First Choice)**

Use officially built Docker images, no local build required, fast deployment.

**Method 1: Standalone Deployment (No code clone required, Recommended for Production)**

Suitable for production environments, no need to download project code, only configuration files needed for deployment.

```bash
# 1. Create deployment directory
mkdir polyhermes && cd polyhermes

# 2. Download production environment configuration files
# Download docker-compose.prod.yml and docker-compose.prod.env.example from GitHub
curl -O https://raw.githubusercontent.com/WrBug/PolyHermes/main/docker-compose.prod.yml
curl -O https://raw.githubusercontent.com/WrBug/PolyHermes/main/docker-compose.prod.env.example

# 3. Create configuration file
cp docker-compose.prod.env.example .env

# 4. Edit .env file, modify the following required configurations:
#    - DB_PASSWORD: Database password (recommended to use strong password)
#    - JWT_SECRET: JWT secret key (generate using openssl rand -hex 64)
#    - ADMIN_RESET_PASSWORD_KEY: Admin password reset key (generate using openssl rand -hex 32)
#
# Example of generating random keys:
#   openssl rand -hex 64   # For JWT_SECRET
#   openssl rand -hex 32   # For ADMIN_RESET_PASSWORD_KEY

# 5. Start services
docker-compose -f docker-compose.prod.yml up -d

# 6. View logs
docker-compose -f docker-compose.prod.yml logs -f

# 7. Stop services
docker-compose -f docker-compose.prod.yml down
```

**Method 2: Using Deployment Script (Requires code clone)**

```bash
# If you have already cloned the code
./deploy.sh --use-docker-hub
```

**Method 3: Modify Existing docker-compose.yml**

```bash
# 1. Modify docker-compose.yml
#    Uncomment: image: wrbug/polyhermes:latest
#    Comment out build section

# 2. Create .env file (see environment configuration below)

# 3. Start services
docker-compose up -d
```

**Advantages**:
- ✅ No local build required, fast deployment
- ✅ No code clone required, only configuration files needed for deployment
- ✅ Uses officially built images with correct version numbers
- ✅ Supports multiple architectures (amd64, arm64), automatically selects matching architecture
- ✅ Recommended for production environments

**Pull Specific Version**:

```bash
# Modify image tag in docker-compose.prod.yml
# image: wrbug/polyhermes:v1.0.0

# Or use environment variable
export IMAGE_TAG=v1.0.0
# In docker-compose.prod.yml use: image: wrbug/polyhermes:${IMAGE_TAG:-latest}
```

**Update Docker Version**:

When a new version is released, you can update using the following steps:

```bash
# 1. Stop currently running containers
docker-compose -f docker-compose.prod.yml down

# 2. Pull the latest version image (or specific version)
# Update to latest version
docker pull wrbug/polyhermes:latest

# Or update to specific version (e.g., v1.0.1)
docker pull wrbug/polyhermes:v1.0.1

# 3. If using a specific version, modify the image tag in docker-compose.prod.yml
# Edit docker-compose.prod.yml, change image to:
# image: wrbug/polyhermes:v1.0.1

# 4. Restart services
docker-compose -f docker-compose.prod.yml up -d

# 5. Check logs to confirm services started normally
docker-compose -f docker-compose.prod.yml logs -f
```

**Notes**:
- ⚠️ It is recommended to backup the database before updating (if using MySQL in Docker Compose)
- ⚠️ Service will be briefly interrupted during update, recommend updating during off-peak hours
- ✅ Using `docker-compose pull` can automatically pull the latest image and update (if using `latest` tag)
- ✅ View available versions: Visit [Docker Hub](https://hub.docker.com/r/wrbug/polyhermes/tags) or [GitHub Releases](https://github.com/WrBug/PolyHermes/releases)

2. **Local Build Deployment (Development Environment)**

Suitable for development environments or scenarios requiring custom builds.

```bash
# Use deployment script
./deploy.sh
```

The script will automatically:
- Check Docker environment
- Create `.env` configuration file (if it doesn't exist)
- Build Docker image (including frontend and backend)
- Start services (application + MySQL)

**Note**: Locally built version numbers will display as `dev`.

3. **Manual Deployment**

```bash
# Create .env file
cat > .env <<EOF
DB_URL=jdbc:mysql://mysql:3306/polyhermes?useSSL=false&serverTimezone=UTC&characterEncoding=utf8&allowPublicKeyRetrieval=true
DB_USERNAME=root
DB_PASSWORD=your_password_here
SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=80
JWT_SECRET=your-jwt-secret-key-change-in-production
ADMIN_RESET_PASSWORD_KEY=your-admin-reset-key-change-in-production
EOF

# Build and start
docker-compose build
docker-compose up -d

# View logs
docker-compose logs -f

# Stop services
docker-compose down
```

4. **Access Application**

- Frontend and backend unified access: `http://localhost:80`
- Nginx automatically handles:
  - `/api/*` → Backend API (`localhost:8000`)
  - `/ws` → Backend WebSocket (`localhost:8000`)
  - Other paths → Frontend static files

### Architecture Description

```
User Request
  ↓
Nginx (Port 80)
  ├─ /api/* → Backend Service (localhost:8000)
  ├─ /ws → Backend WebSocket (localhost:8000)
  └─ /* → Frontend Static Files (/usr/share/nginx/html)
```

### Advantages

- ✅ Single container, simplified deployment
- ✅ Unified port, no CORS configuration needed
- ✅ Automatic handling of frontend and backend routing
- ✅ Production ready

### Using External Nginx Reverse Proxy (Recommended for Production)

In production environments, it is recommended to deploy Nginx as a reverse proxy outside the Docker container for:

- **SSL/TLS Termination**: Handle HTTPS requests
- **Domain Binding**: Bind custom domain names
- **Load Balancing**: Support multiple backend instances
- **More Flexible Configuration**: More granular control

**Deployment Architecture**:

```
User Request (HTTPS)
  ↓
External Nginx (443) - SSL Termination
  ↓
Docker Container (80) - Internal Nginx + Backend
  ├─ /api/* → Backend Service (localhost:8000)
  ├─ /ws → Backend WebSocket (localhost:8000)
  └─ /* → Frontend Static Files
```

**Deployment Steps**:

1. **Deploy Docker Container**

```bash
# Deploy using docker-compose.prod.yml
docker-compose -f docker-compose.prod.yml up -d
```

2. **Configure External Nginx**

```bash
# 1. Download Nginx configuration example
curl -O https://raw.githubusercontent.com/WrBug/PolyHermes/main/docs/zh/nginx-reverse-proxy.conf

# 2. Copy to Nginx configuration directory
sudo cp nginx-reverse-proxy.conf /etc/nginx/sites-available/polyhermes

# 3. Edit configuration file, modify domain name and SSL certificate paths
sudo nano /etc/nginx/sites-available/polyhermes
# Modify the following:
#   - server_name: Change to your domain name
#   - ssl_certificate: SSL certificate path
#   - ssl_certificate_key: SSL private key path
#   - upstream server: If Docker container port is not 80, need to modify

# 4. Create symbolic link
sudo ln -s /etc/nginx/sites-available/polyhermes /etc/nginx/sites-enabled/

# 5. Test configuration
sudo nginx -t

# 6. Reload configuration
sudo systemctl reload nginx
```

3. **Configure SSL Certificate (Using Let's Encrypt)**

```bash
# Install Certbot
sudo apt-get update
sudo apt-get install certbot python3-certbot-nginx

# Get SSL certificate
sudo certbot --nginx -d your-domain.com -d www.your-domain.com

# Certificate will be automatically configured to Nginx and set up auto-renewal
```

4. **Modify Docker Port Mapping (Optional)**

If using external Nginx, you can change Docker container port to internal port, not exposed externally:

```yaml
# In docker-compose.prod.yml
ports:
  - "127.0.0.1:80:80"  # Only bind to localhost, not exposed externally
```

**Nginx Configuration Description**:

- Configuration file location: `docs/zh/nginx-reverse-proxy.conf`
- Supports HTTPS (SSL/TLS)
- Supports WebSocket proxy
- Includes security headers
- Supports load balancing (can configure multiple backends)

For detailed configuration examples, please refer to: [Nginx Reverse Proxy Configuration](../zh/nginx-reverse-proxy.conf)

## Backend Deployment

### Java Direct Deployment

#### Prerequisites

- JDK 17+
- MySQL 8.0+
- Gradle 7.5+ (or use Gradle Wrapper)

#### Deployment Steps

1. **Build Application**

```bash
cd backend
./gradlew clean bootJar
```

Build artifact located at `build/libs/polyhermes-backend-1.0.0.jar`

2. **Use Deployment Script (Recommended)**

```bash
# Build and create deployment files
./deploy.sh java

# Or build only
./deploy.sh build
```

The script will automatically:
- Check Java environment
- Build application
- Create deployment directory and startup script
- Generate systemd service file (optional)

3. **Manual Start**

```bash
# Development environment
java -jar build/libs/polyhermes-backend-1.0.0.jar --spring.profiles.active=dev

# Production environment
java -jar build/libs/polyhermes-backend-1.0.0.jar --spring.profiles.active=prod
```

4. **Use systemd Management (Linux)**

```bash
# Copy service file
sudo cp deploy/polyhermes-backend.service /etc/systemd/system/

# Edit service file, modify path and user
sudo nano /etc/systemd/system/polyhermes-backend.service

# Start service
sudo systemctl daemon-reload
sudo systemctl enable polyhermes-backend
sudo systemctl start polyhermes-backend

# View logs
sudo journalctl -u polyhermes-backend -f
```

### Docker Deployment

#### Prerequisites

- Docker 20.10+
- Docker Compose 2.0+

#### Deployment Steps

1. **Use Deployment Script (Recommended)**

```bash
cd backend
./deploy.sh docker
```

The script will automatically:
- Check Docker environment
- Create `.env` configuration file (if it doesn't exist)
- Build Docker image
- Start service

2. **Manual Deployment**

```bash
# Create .env file
cat > .env <<EOF
DB_URL=jdbc:mysql://mysql:3306/polyhermes?useSSL=false&serverTimezone=UTC&characterEncoding=utf8&allowPublicKeyRetrieval=true
DB_USERNAME=root
DB_PASSWORD=your_password_here
SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=8000
JWT_SECRET=your-jwt-secret-key-change-in-production
ADMIN_RESET_PASSWORD_KEY=your-admin-reset-key-change-in-production
EOF

# Build and start
docker-compose up -d

# View logs
docker-compose logs -f

# Stop service
docker-compose down
```

3. **Build Image Only**

```bash
docker build -t polyhermes-backend:latest .
```

4. **Run Container**

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

## Frontend Deployment

### Build Steps

1. **Use Build Script (Recommended)**

```bash
cd frontend

# Use default backend address (http://127.0.0.1:8000)
./build.sh

# Or specify custom backend address
./build.sh --api-url http://your-backend-server.com:8000

# Or use environment variable
VITE_API_URL=http://your-backend-server.com:8000 ./build.sh
```

2. **Manual Build**

```bash
cd frontend

# Create environment configuration file
cat > .env.production <<EOF
VITE_API_URL=http://your-backend-server.com:8000
VITE_WS_URL=ws://your-backend-server.com:8000
EOF

# Install dependencies (first time)
npm install

# Build
npm run build
```

Build artifact located in `dist/` directory.

### Deployment Methods

#### Method 1: Nginx Deployment

```nginx
server {
    listen 80;
    server_name your-domain.com;
    
    root /path/to/frontend/dist;
    index index.html;
    
    # API proxy
    location /api {
        proxy_pass http://localhost:8000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
    
    # WebSocket proxy
    location /ws {
        proxy_pass http://localhost:8000;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
    }
    
    # Frontend routing (SPA)
    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

#### Method 2: Apache Deployment

```apache
<VirtualHost *:80>
    ServerName your-domain.com
    DocumentRoot /path/to/frontend/dist
    
    # API proxy
    ProxyPass /api http://localhost:8000/api
    ProxyPassReverse /api http://localhost:8000/api
    
    # WebSocket proxy
    ProxyPass /ws ws://localhost:8000/ws
    ProxyPassReverse /ws ws://localhost:8000/ws
    
    # Frontend routing (SPA)
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

#### Method 3: Using serve (Development/Testing)

```bash
# Install serve
npm install -g serve

# Start service
serve -s dist -l 3000
```

## Environment Configuration

### Backend Environment Variables

| Variable Name | Description | Default Value | Required |
|---------------|-------------|---------------|----------|
| `SPRING_PROFILES_ACTIVE` | Spring Profile | `dev` | No |
| `DB_URL` | Database connection URL | - | Yes (Production) |
| `DB_USERNAME` | Database username | `root` | Yes (Production) |
| `DB_PASSWORD` | Database password | - | Yes (Production) |
| `SERVER_PORT` | Server port | `8000` | No |
| `JWT_SECRET` | JWT secret key | - | Yes (Production) |
| `ADMIN_RESET_PASSWORD_KEY` | Admin password reset key | - | Yes (Production) |

### Frontend Environment Variables

| Variable Name | Description | Default Value |
|---------------|-------------|---------------|
| `VITE_API_URL` | Backend API address | `http://127.0.0.1:8000` |
| `VITE_WS_URL` | WebSocket address | `ws://127.0.0.1:8000` |

### Configuration File Description

#### Backend Configuration Files

- `application.properties` - Base configuration (shared by all environments)
- `application-dev.properties` - Development environment configuration
- `application-prod.properties` - Production environment configuration

Switch environments via `--spring.profiles.active=prod` or environment variable `SPRING_PROFILES_ACTIVE=prod`.

#### Frontend Environment Variables

Vite uses `.env.production` file to inject environment variables during build. The build script will automatically create this file.

## FAQ

### 1. Database Connection Failed

**Problem**: Backend cannot connect to database

**Solution**:
- Check if database service is running
- Check if database connection URL, username, password are correct
- Check if firewall allows connection
- For Docker deployment, ensure using correct database address (`mysql` instead of `localhost`)

### 2. Frontend Cannot Connect to Backend

**Problem**: Frontend requests to backend API fail

**Solution**:
- Check if backend service is running
- Check if `VITE_API_URL` configuration is correct
- Check CORS configuration (if cross-origin)
- Check network connection and firewall

### 3. WebSocket Connection Failed

**Problem**: WebSocket cannot establish connection

**Solution**:
- Check if `VITE_WS_URL` configuration is correct
- Check WebSocket proxy configuration (Nginx/Apache)
- Check if firewall allows WebSocket connection
- Check if backend WebSocket service is normal

### 4. Docker Container Cannot Access Database

**Problem**: Backend in Docker container cannot connect to host database

**Solution**:
- Use `host.docker.internal` as database address (Mac/Windows)
- Use Docker network connection (recommended to use docker-compose)
- Check if database allows remote connection

### 5. Build Failed

**Problem**: Frontend or backend build fails

**Solution**:
- Check Node.js version (requires 18+)
- Check Java version (requires 17+)
- Clean cache and rebuild:
  ```bash
  # Frontend
  rm -rf node_modules dist
  npm install
  npm run build
  
  # Backend
  ./gradlew clean build
  ```

## Production Environment Checklist

- [ ] Modify all default passwords and keys (JWT_SECRET, ADMIN_RESET_PASSWORD_KEY, database password)
- [ ] Configure correct database connection (use SSL)
- [ ] Set correct Spring Profile (`prod`)
- [ ] Configure correct backend API address (frontend)
- [ ] Configure reverse proxy (Nginx/Apache)
- [ ] Configure HTTPS (recommended for production)
- [ ] Configure firewall rules
- [ ] Set up log rotation
- [ ] Configure monitoring and alerts
- [ ] Regular database backups

## Performance Optimization Recommendations

### Backend

- Adjust JVM parameters (heap memory, GC strategy)
- Configure database connection pool size
- Enable HTTP compression
- Configure caching strategy

### Frontend

- Enable Gzip compression (Nginx)
- Configure static resource caching
- Use CDN acceleration
- Enable HTTP/2

## Security Recommendations

- Use HTTPS (required for production)
- Configure CORS whitelist
- Regularly update dependencies
- Use strong passwords and keys
- Limit database access permissions
- Configure firewall rules
- Regular data backups
- Monitor abnormal access

## Technical Support

If you have any questions, please submit an Issue to [GitHub](https://github.com/WrBug/PolyHermes) or contact [Twitter](https://x.com/polyhermes).

