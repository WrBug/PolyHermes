# PolyHermes

[![GitHub](https://img.shields.io/badge/GitHub-WrBug%2FPolyHermes-blue?logo=github)](https://github.com/WrBug/PolyHermes)
[![Twitter](https://img.shields.io/badge/Twitter-@polyhermes-blue?logo=twitter)](https://x.com/polyhermes)

> 🌐 **Language**: English | [中文](README.md)

A powerful copy trading system for Polymarket prediction markets, supporting automated copy trading, multi-account management, real-time order push, and statistical analysis.

---

## 📋 Table of Contents

- [Part 1: Product Features](#part-1-product-features)
- [Part 2: How to Deploy](#part-2-how-to-deploy)
- [Part 3: Development Documentation](#part-3-development-documentation)

---

## Part 1: Product Features

### 📸 Interface Preview

#### 🖥️ Desktop
<div align="center">
  <table>
    <tr>
      <td align="center">
        <img src="screenshot/pc/ScreenShot_2025-12-07_172940_894.png" alt="" width="90%" />
      </td>
      <td align="center">
        <img src="screenshot/pc/ScreenShot_2025-12-07_173042_509.png" alt="" width="90%" />
      </td>
    </tr>
    <tr>
      <td align="center">
        <img src="screenshot/pc/ScreenShot_2025-12-07_173105_822.png" alt="" width="90%" />
      </td>
      <td align="center">
        <img src="screenshot/pc/ScreenShot_2025-12-07_173133_527.png" alt="" width="90%" />
      </td>
    </tr>
  </table>
</div>

#### 📱 Mobile
<div align="center">
  <table>
    <tr>
      <td align="center">
        <img src="screenshot/mobile/ScreenShot_2025-12-07_173224_069.png" alt="" width="70%" />
      </td>
      <td align="center">
        <img src="screenshot/mobile/ScreenShot_2025-12-07_173309_995.png" alt="" width="70%" />
      </td>
    </tr>
    <tr>
      <td align="center">
        <img src="screenshot/mobile/ScreenShot_2025-12-07_173330_724.png" alt="" width="70%" />
      </td>
      <td colspan="3" align="center">
        <img src="screenshot/mobile/ScreenShot_2025-12-07_173354_840.png" alt="" width="70%" />
      </td>
    </tr>
  </table>
</div>

### ✨ Core Features

#### 🔐 Account Management
- **Multi-Account Support**: Import multiple wallet accounts via private keys for unified management
- **Secure Storage**: Private keys and API credentials are encrypted and stored securely
- **Account Information**: View detailed account information including balance, positions, and transaction history
- **Account Editing**: Support for modifying account names, setting default accounts, etc.

#### 👥 Leader Management
- **Add Leaders**: Add wallet addresses of traders to copy (Leaders)
- **Category Filtering**: Support filtering by category (sports/crypto)
- **Leader Information**: View trading history and statistics of Leaders
- **Note Management**: Add notes to Leaders for easy identification and management

#### 📊 Copy Trading Templates
- **Flexible Configuration**: Create copy trading templates and configure copy trading parameters
- **Copy Trading Modes**: Support proportional copy trading and fixed amount copy trading
- **Risk Control**: Configure daily loss limits, order count limits, price tolerance, etc.
- **Template Reuse**: One template can be used for multiple copy trading relationships

#### 🔄 Copy Trading Configuration
- **Relationship Management**: Associate accounts, templates, and Leaders to create copy trading relationships
- **Enable/Disable**: Flexibly control the enabled state of copy trading relationships
- **Automatic Copy Trading**: Real-time monitoring of Leader trades, automatically copying orders (supports buy and sell)
- **Order Tracking**: Complete order lifecycle tracking, including buy, sell, and match records

#### 📈 Order Management
- **Buy Orders**: View detailed information of all buy orders
- **Sell Orders**: View detailed information of all sell orders
- **Matched Orders**: View matched order records
- **Order Filtering**: Support filtering by account, Leader, time range, and other conditions

#### 💼 Position Management
- **Real-Time Positions**: View and manage positions of all accounts in real-time
- **Position Push**: Real-time position changes via WebSocket
- **Sell Positions**: Support market and limit price selling of positions
- **Redeem Positions**: Support batch redemption of settled positions

#### 📊 Statistical Analysis
- **Global Statistics**: View aggregated statistics of all copy trading relationships
- **Leader Statistics**: View statistics of specific Leaders
- **Category Statistics**: View statistics by category (sports/crypto)
- **Copy Trading Relationship Statistics**: View detailed statistics of individual copy trading relationships
- **Time Filtering**: Support filtering statistics by time range

#### ⚙️ System Management
- **Proxy Configuration**: Configure HTTP proxy through Web UI without modifying environment variables
- **API Health Check**: Real-time monitoring of Polymarket API health status
- **User Management**: Manage system users, support adding, editing, and deleting users
- **Announcement Management**: View system announcements and update information

### 🚀 Technical Features

- **WebSocket Real-Time Push**: Real-time push of order and position data without manual refresh
- **Secure Storage**: Private keys and API credentials stored using AES encryption
- **Responsive Design**: Perfect support for mobile and desktop, providing a consistent user experience
- **High Performance**: Asynchronous processing, concurrency optimization, support for large-scale order processing
- **Risk Control**: Multiple risk control mechanisms including daily loss limits, order count limits, price tolerance, etc.
- **Multi-Language Support**: Support for Chinese (Simplified/Traditional) and English
- **Version Management**: Automatic version number display and management, support for GitHub Releases auto-build

### 🏗️ Tech Stack

#### Backend
- **Framework**: Spring Boot 3.2.0
- **Language**: Kotlin 1.9.20
- **Database**: MySQL 8.2.0
- **ORM**: Spring Data JPA
- **Database Migration**: Flyway
- **HTTP Client**: Retrofit 2.9.0 + OkHttp 4.12.0
- **WebSocket**: Spring WebSocket

#### Frontend
- **Framework**: React 18 + TypeScript
- **Build Tool**: Vite
- **UI Library**: Ant Design 5.12.0
- **HTTP Client**: axios
- **State Management**: Zustand
- **Routing**: React Router 6
- **Ethereum Library**: ethers.js 6.9.0
- **Internationalization**: react-i18next

---

## Part 2: How to Deploy

### 🚀 Quick Deployment

#### All-in-One Deployment (Recommended)

Deploy both frontend and backend together in a single Docker container, using Nginx to serve frontend static files and proxy backend API.

**Prerequisites**:
- Docker 20.10+
- Docker Compose 2.0+

**Deployment Steps**:

1. **Using Docker Hub Images (Recommended, Production First Choice)**

**Method 1: Standalone Deployment (No code clone required, Recommended)**

Suitable for production environments, no need to download project code, only two files needed for deployment:

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
#    - DB_PASSWORD: Database password
#    - JWT_SECRET: Generate using openssl rand -hex 64
#    - ADMIN_RESET_PASSWORD_KEY: Generate using openssl rand -hex 32

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
# 1. Modify docker-compose.yml, uncomment:
#    image: wrbug/polyhermes:latest
#    and comment out the build section
# 2. Create .env file (see below)
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
# Modify the image tag in docker-compose.prod.yml
# image: wrbug/polyhermes:v1.0.0
```

**Update Docker Version**:

```bash
# 1. Stop current containers
docker-compose -f docker-compose.prod.yml down

# 2. Pull latest image
docker pull wrbug/polyhermes:latest

# 3. Restart services
docker-compose -f docker-compose.prod.yml up -d

# Or update to specific version (e.g., v1.0.1)
# Modify image tag in docker-compose.prod.yml to: image: wrbug/polyhermes:v1.0.1
# Then run: docker-compose -f docker-compose.prod.yml up -d
```

For detailed update instructions, please refer to: [Deployment Guide - Update Docker Version](docs/en/DEPLOYMENT.md#update-docker-version)

2. **Local Build Deployment (Development Environment)**

```bash
# Use deployment script
./deploy.sh
```

The script will automatically:
- Check Docker environment
- Create `.env` configuration file (if it doesn't exist)
- Build Docker image (including frontend and backend)
- Start services (application + MySQL)

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

**Access Application**:
- Frontend and backend unified access: `http://localhost:80`
- Nginx automatically handles:
  - `/api/*` → Backend API (`localhost:8000`)
  - `/ws` → Backend WebSocket (`localhost:8000`)
  - Other paths → Frontend static files

**Using External Nginx Reverse Proxy (Recommended for Production)**:

In production environments, it is recommended to deploy Nginx as a reverse proxy outside the Docker container for SSL/TLS termination, domain binding, etc.

For detailed configuration, please refer to: [Deployment Documentation - Nginx Reverse Proxy](docs/en/DEPLOYMENT.md#using-external-nginx-reverse-proxy-recommended-for-production)

### 📦 Separate Deployment

#### Backend Deployment

**Java Direct Deployment**:

```bash
cd backend
./deploy.sh java
```

**Docker Deployment**:

```bash
cd backend
./deploy.sh docker
```

#### Frontend Deployment

```bash
cd frontend
# Use default backend address (relative path)
./build.sh

# Or specify custom backend address (cross-origin scenarios)
./build.sh --api-url http://your-backend-server.com:8000
```

### ⚙️ Environment Configuration

#### Required Environment Variables

| Variable Name | Description | Default Value |
|---------------|-------------|---------------|
| `DB_USERNAME` | Database username | `root` |
| `DB_PASSWORD` | Database password | - |
| `SERVER_PORT` | Backend service port | `8000` |
| `JWT_SECRET` | JWT secret key | - |
| `ADMIN_RESET_PASSWORD_KEY` | Admin password reset key | - |
| `CRYPTO_SECRET_KEY` | Encryption key (for encrypting stored private keys and API Keys) | - |

#### Proxy Configuration

The system supports configuring HTTP proxy through Web UI without modifying environment variables:

1. Go to "System Management" page
2. Configure proxy host, port, username, and password
3. Enable proxy and test connection
4. Configuration takes effect immediately without service restart

### 📚 Detailed Deployment Documentation

For more deployment options and detailed instructions, please refer to: [Deployment Documentation](docs/en/DEPLOYMENT.md)

Including:
- Detailed steps for all-in-one deployment
- Backend deployment (Java/Docker)
- Frontend deployment
- Environment configuration instructions
- Frequently asked questions

### 🔄 Version Management

The project supports automatic version number management and Docker image building:

- **Auto Build**: Automatically builds Docker images when creating releases via GitHub Releases page
- **Auto Delete**: Automatically deletes corresponding Docker image tags when releases are deleted
- **Version Display**: Frontend automatically displays current version number

For detailed instructions, please refer to: [Version Management Documentation](docs/en/VERSION_MANAGEMENT.md)

---

## Part 3: Development Documentation

For detailed development guides, API documentation, code standards, etc., please refer to:

### 📖 [Development Documentation](docs/en/DEVELOPMENT.md)

The development documentation includes:

- **Project Structure**: Detailed directory structure description
- **Development Environment Setup**: How to set up development environment
- **Code Standards**: Backend and frontend development standards
- **API Documentation**: Detailed description of all API interfaces
- **Database Design**: Database table structure description
- **Frontend Development Guide**: Frontend development best practices
- **Backend Development Guide**: Backend development best practices
- **FAQ**: Answers to common questions during development

### 📚 Other Documentation

- [Deployment Documentation](docs/en/DEPLOYMENT.md) - Detailed deployment guide (Java/Docker)
- [Version Management Documentation](docs/en/VERSION_MANAGEMENT.md) - Version number management and auto-build
- [Development Documentation](docs/en/DEVELOPMENT.md) - Development guide
- [Copy Trading System Requirements](docs/zh/copy-trading-requirements.md) - Backend API documentation (Chinese only)
- [Frontend Requirements](docs/zh/copy-trading-frontend-requirements.md) - Frontend feature documentation (Chinese only)

### 🤝 Contributing

Contributions are welcome! Please follow these steps:

1. Fork this repository
2. Create a feature branch (`git checkout -b feature/AmazingFeature`)
3. Follow code standards (refer to development documentation)
4. Commit your changes (`git commit -m 'feat: Add some AmazingFeature'`)
5. Push to the branch (`git push origin feature/AmazingFeature`)
6. Open a Pull Request

### 📝 Development Standards

- **Backend**: Follow Kotlin coding standards, use Spring Boot best practices
- **Frontend**: Follow TypeScript and React best practices
- **Commit Messages**: Use clear commit messages, follow [Conventional Commits](https://www.conventionalcommits.org/)

For detailed development standards, please refer to:
- [Backend Development Standards](.cursor/rules/backend.mdc)
- [Frontend Development Standards](.cursor/rules/frontend.mdc)

---

## ⚠️ Disclaimer

This software is for learning and research purposes only. Users bear all risks when using this software for trading. The author is not responsible for any trading losses.

## 📄 License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

## 🔗 Related Links

- [GitHub Repository](https://github.com/WrBug/PolyHermes)
- [Twitter](https://x.com/polyhermes)
- [Polymarket Official Website](https://polymarket.com)
- [Polymarket API Documentation](https://docs.polymarket.com)

## 🙏 Acknowledgments

Thanks to all developers and users who have contributed to this project!

---

**⭐ If this project helps you, please give it a Star!**

