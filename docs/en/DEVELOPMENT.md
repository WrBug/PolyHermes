# PolyHermes Development Guide

> 📖 **中文版本**: [开发文档（中文）](../zh/DEVELOPMENT.md)

This document describes the development guide for the PolyHermes project, including project structure, development environment setup, code standards, API interfaces, etc.

## 📋 Table of Contents

- [Project Structure](#project-structure)
- [Development Environment Setup](#development-environment-setup)
- [Code Standards](#code-standards)
- [API Documentation](#api-documentation)
- [Database Design](#database-design)
- [Frontend Development Guide](#frontend-development-guide)
- [Backend Development Guide](#backend-development-guide)
- [FAQ](#faq)

## 📦 Project Structure

```
polyhermes/
├── backend/                    # Backend service
│   ├── src/main/kotlin/
│   │   └── com/wrbug/polymarketbot/
│   │       ├── api/            # API interface definitions (Retrofit)
│   │       ├── config/         # Configuration classes
│   │       ├── controller/     # REST controllers
│   │       ├── dto/            # Data Transfer Objects
│   │       ├── entity/         # Database entities
│   │       ├── repository/     # Data access layer
│   │       ├── service/        # Business logic services
│   │       ├── util/           # Utility classes
│   │       └── websocket/       # WebSocket handling
│   └── src/main/resources/
│       ├── application.properties
│       └── db/migration/       # Flyway database migration scripts
├── frontend/                   # Frontend application
│   ├── src/
│   │   ├── components/        # Common components
│   │   ├── pages/              # Page components
│   │   ├── services/           # API services
│   │   ├── store/              # State management (Zustand)
│   │   ├── types/              # TypeScript type definitions
│   │   ├── utils/              # Utility functions
│   │   ├── hooks/              # React Hooks
│   │   ├── locales/            # Internationalization resources
│   │   └── styles/             # Style files
│   └── public/                 # Static resources
├── docs/                       # Documentation
│   ├── zh/                     # Chinese documentation
│   │   ├── DEPLOYMENT.md       # Deployment documentation
│   │   ├── VERSION_MANAGEMENT.md  # Version management documentation
│   │   └── ...
│   ├── en/                     # English documentation
│   │   ├── DEPLOYMENT.md       # Deployment documentation
│   │   ├── VERSION_MANAGEMENT.md  # Version management documentation
│   │   └── ...
│   └── copy-trading-requirements.md  # Copy trading system requirements
├── .github/workflows/          # GitHub Actions workflows
└── README.md                   # Project description
```

## 🛠️ Development Environment Setup

### Prerequisites

- **JDK**: 17+
- **Node.js**: 18+
- **MySQL**: 8.0+
- **Gradle**: 7.5+ (or use Gradle Wrapper)
- **Docker**: 20.10+ (optional, for containerized deployment)

### Backend Development Environment

1. **Clone Repository**

```bash
git clone https://github.com/WrBug/PolyHermes.git
cd PolyHermes
```

2. **Configure Database**

Create MySQL database:

```sql
CREATE DATABASE polyhermes CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

3. **Configure Environment Variables**

Edit `backend/src/main/resources/application.properties` or use environment variables:

```properties
# Database configuration
spring.datasource.url=jdbc:mysql://localhost:3306/polyhermes?useSSL=false&serverTimezone=UTC&characterEncoding=utf8mb4
spring.datasource.username=${DB_USERNAME:root}
spring.datasource.password=${DB_PASSWORD:password}

# Server port
server.port=${SERVER_PORT:8000}

# JWT secret
jwt.secret=${JWT_SECRET:change-me-in-production}

# Encryption key (for encrypting stored private keys and API Keys)
crypto.secret.key=${CRYPTO_SECRET_KEY:change-me-in-production}
```

4. **Start Backend Service**

```bash
cd backend
./gradlew bootRun
```

Backend service will start at `http://localhost:8000`.

### Frontend Development Environment

1. **Install Dependencies**

```bash
cd frontend
npm install
```

2. **Configure Environment Variables (Optional)**

Create `.env` file:

```env
VITE_API_URL=http://localhost:8000
VITE_WS_URL=ws://localhost:8000
```

3. **Start Development Server**

```bash
npm run dev
```

Frontend application will start at `http://localhost:3000`.

## 📝 Code Standards

### Backend Development Standards

For detailed standards, please refer to: [Backend Development Standards](.cursor/rules/backend.mdc)

**Core Standards**:
- Follow Kotlin coding standards
- Controller methods **must not** use `suspend`
- Entity ID fields use `Long? = null`
- All time fields use `Long` timestamps (milliseconds)
- Use `BigDecimal` for numerical calculations
- Use `ErrorCode` enum to define error codes and messages
- **Do not** add TODO comments in code
- **Do not** directly return mock data

### Frontend Development Standards

For detailed standards, please refer to: [Frontend Development Standards](.cursor/rules/frontend.mdc)

**Core Standards**:
- Use TypeScript type definitions
- Use functional components and Hooks
- **Do not** use `any` type
- **Must** use internationalization (i18n) for all text display
- **Must** use `formatUSDC` function to format USDC amounts
- **Must** support mobile and desktop
- **Do not** add TODO comments in code

### Commit Standards

Follow [Conventional Commits](https://www.conventionalcommits.org/) standards:

- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation update
- `style`: Code style adjustment
- `refactor`: Code refactoring
- `test`: Test related
- `chore`: Build/tool related

Examples:
```bash
git commit -m "feat: Add version number display feature"
git commit -m "fix: Fix order status update issue"
```

## 📡 API Documentation

### Unified Response Format

All API interfaces use POST method uniformly, response format as follows:

```json
{
  "code": 0,
  "data": {},
  "msg": ""
}
```

- `code`: Response code, 0 means success, non-0 means failure
- `data`: Response data, can be any type
- `msg`: Response message, usually empty on success, contains error message on failure

### Error Code Standards

- `0`: Success
- `1001-1999`: Parameter error
- `2001-2999`: Authentication/permission error
- `3001-3999`: Resource not found
- `4001-4999`: Business logic error
- `5001-5999`: Server internal error

### Main API Interfaces

#### Account Management

- `POST /api/accounts/list` - Get account list
- `POST /api/accounts/import` - Import account (via private key)
- `POST /api/accounts/detail` - Get account details
- `POST /api/accounts/edit` - Edit account
- `POST /api/accounts/delete` - Delete account
- `POST /api/accounts/balance` - Get account balance

#### Leader Management

- `POST /api/leaders/list` - Get Leader list
- `POST /api/leaders/add` - Add Leader
- `POST /api/leaders/edit` - Edit Leader
- `POST /api/leaders/delete` - Delete Leader

#### Copy Trading Templates

- `POST /api/templates/list` - Get template list
- `POST /api/templates/add` - Add template
- `POST /api/templates/edit` - Edit template
- `POST /api/templates/delete` - Delete template

#### Copy Trading Configuration

- `POST /api/copy-trading/list` - Get copy trading configuration list
- `POST /api/copy-trading/add` - Add copy trading configuration
- `POST /api/copy-trading/edit` - Edit copy trading configuration
- `POST /api/copy-trading/delete` - Delete copy trading configuration
- `POST /api/copy-trading/enable` - Enable copy trading
- `POST /api/copy-trading/disable` - Disable copy trading

#### Order Management

- `POST /api/copy-trading/orders/buy` - Get buy order list
- `POST /api/copy-trading/orders/sell` - Get sell order list
- `POST /api/copy-trading/orders/matched` - Get matched order list

#### Statistical Analysis

- `POST /api/statistics/global` - Get global statistics
- `POST /api/statistics/leader` - Get Leader statistics
- `POST /api/statistics/category` - Get category statistics
- `POST /api/copy-trading/statistics` - Get copy trading relationship statistics

#### Position Management

- `POST /api/positions/list` - Get position list
- `POST /api/positions/sell` - Sell position
- `POST /api/positions/redeem` - Redeem position

#### System Management

- `POST /api/system-settings/proxy` - Configure proxy
- `POST /api/system-settings/api-health` - Get API health status
- `POST /api/users/list` - Get user list
- `POST /api/users/add` - Add user
- `POST /api/users/edit` - Edit user
- `POST /api/users/delete` - Delete user

For detailed API interface documentation, please refer to: [Copy Trading System Requirements](../zh/copy-trading-requirements.md)

## 🗄️ Database Design

### Main Data Tables

- `accounts` - Account table
- `leaders` - Leader table
- `templates` - Copy trading template table
- `copy_trading` - Copy trading configuration table
- `copy_orders` - Copy trading order table
- `positions` - Position table
- `users` - User table
- `system_settings` - System settings table

Database migration scripts are located at `backend/src/main/resources/db/migration/`, managed using Flyway.

## 🎨 Frontend Development Guide

### Project Structure

```
frontend/src/
├── components/          # Common components
│   ├── Layout.tsx      # Layout component (supports mobile)
│   └── Logo.tsx        # Logo component
├── pages/              # Page components
│   ├── AccountList.tsx
│   ├── LeaderList.tsx
│   ├── CopyTradingList.tsx
│   └── ...
├── services/           # API services
│   ├── api.ts         # API service definitions
│   └── websocket.ts   # WebSocket service
├── store/             # State management (Zustand)
├── types/             # TypeScript type definitions
├── utils/             # Utility functions
│   ├── index.ts       # Unified export
│   ├── ethers.ts      # Ethereum related utilities
│   ├── auth.ts        # Authentication related utilities
│   └── version.ts     # Version number utilities
├── hooks/             # React Hooks
├── locales/           # Internationalization resources
│   ├── zh-CN/
│   ├── zh-TW/
│   └── en/
└── styles/            # Style files
```

### Internationalization Support

The project supports multiple languages (Simplified Chinese, Traditional Chinese, English), using `react-i18next`.

**Adding New Translations**:
1. Add translations in `src/locales/{locale}/common.json`
2. Use `useTranslation` Hook in components:

```typescript
import { useTranslation } from 'react-i18next'

const MyComponent: React.FC = () => {
  const { t } = useTranslation()
  return <div>{t('key')}</div>
}
```

### Mobile Adaptation

- Use `react-responsive` to detect device type
- Breakpoint settings: Mobile < 768px, Desktop >= 768px
- Use responsive layouts and components

### Utility Functions

**USDC Amount Formatting**:
```typescript
import { formatUSDC } from '../utils'

const balance = formatUSDC('1.23456')  // "1.2345"
```

**Ethereum Address Validation**:
```typescript
import { isValidWalletAddress } from '../utils'

if (isValidWalletAddress(address)) {
  // Address is valid
}
```

## ⚙️ Backend Development Guide

### Project Structure

```
backend/src/main/kotlin/com/wrbug/polymarketbot/
├── api/                # API interface definitions (Retrofit)
│   ├── PolymarketClobApi.kt
│   ├── PolymarketGammaApi.kt
│   └── GitHubApi.kt
├── controller/         # REST controllers
├── service/            # Business logic services
├── entity/             # Database entities
├── repository/         # Data access layer
├── dto/                # Data Transfer Objects
├── util/               # Utility classes
│   ├── CryptoUtils.kt  # Encryption utilities
│   ├── RetrofitFactory.kt  # Retrofit factory
│   └── ...
└── websocket/          # WebSocket handling
```

### Creating New API Interface

1. **Define Retrofit Interface** (in `api/` directory):

```kotlin
interface MyApi {
    @POST("/endpoint")
    suspend fun myMethod(@Body request: MyRequest): Response<MyResponse>
}
```

2. **Create Service** (in `service/` directory):

```kotlin
@Service
class MyService(
    private val myApi: MyApi
) {
    suspend fun doSomething(): Result<MyResponse> {
        // Business logic
    }
}
```

3. **Create Controller** (in `controller/` directory):

```kotlin
@RestController
@RequestMapping("/api/my")
class MyController(
    private val myService: MyService,
    private val messageSource: MessageSource
) {
    @PostMapping("/list")
    fun list(@RequestBody request: MyListRequest): ResponseEntity<ApiResponse<MyListResponse>> {
        return try {
            val data = runBlocking { myService.getList(request) }
            ResponseEntity.ok(ApiResponse.success(data))
        } catch (e: Exception) {
            logger.error("Failed to get list", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, messageSource = messageSource))
        }
    }
}
```

### Database Operations

Using Spring Data JPA:

```kotlin
@Repository
interface MyRepository : JpaRepository<MyEntity, Long> {
    fun findByCode(code: String): MyEntity?
    fun findByCategory(category: String): List<MyEntity>
}
```

### Encrypted Storage

Use `CryptoUtils` to encrypt sensitive data:

```kotlin
@Autowired
private lateinit var cryptoUtils: CryptoUtils

// Encrypt
val encrypted = cryptoUtils.encrypt("sensitive-data")

// Decrypt
val decrypted = cryptoUtils.decrypt(encrypted)
```

## 🔧 FAQ

### Q1: How to add a new page?

1. Create page component in `frontend/src/pages/`
2. Add route in `frontend/src/App.tsx`
3. Add menu item in `frontend/src/components/Layout.tsx` (if needed)

### Q2: How to add a new API interface?

1. Create Controller in `backend/src/main/kotlin/.../controller/`
2. Create Service in `backend/src/main/kotlin/.../service/`
3. Add API call method in `frontend/src/services/api.ts`

### Q3: How to add a database table?

1. Create Entity class (in `entity/` directory)
2. Create Repository interface (in `repository/` directory)
3. Create Flyway migration script (in `resources/db/migration/`)

### Q4: How to test WebSocket?

Use browser console or WebSocket client tool to connect to `ws://localhost:8000/ws`

### Q5: How to debug backend code?

1. Use IDE's debugging feature (IntelliJ IDEA, VS Code, etc.)
2. Add logs in code: `logger.debug("Debug info")`
3. View log output: `./gradlew bootRun` or view log files

## 📚 Related Documentation

- [Deployment Documentation](../zh/DEPLOYMENT.md) / [English](../en/DEPLOYMENT.md) - Detailed deployment guide
- [Version Management Documentation](../zh/VERSION_MANAGEMENT.md) / [English](../en/VERSION_MANAGEMENT.md) - Version number management and auto-build
- [Copy Trading System Requirements](../zh/copy-trading-requirements.md) - Backend API interface documentation
- [Frontend Requirements](../zh/copy-trading-frontend-requirements.md) - Frontend feature documentation

## 🤝 Contributing

Contributions are welcome! Please follow these steps:

1. Fork this repository
2. Create a feature branch (`git checkout -b feature/AmazingFeature`)
3. Follow code standards
4. Commit your changes (`git commit -m 'feat: Add some AmazingFeature'`)
5. Push to the branch (`git push origin feature/AmazingFeature`)
6. Open a Pull Request

---

**Happy Coding! 🚀**

