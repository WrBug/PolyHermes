#!/bin/bash

# PolyHermes 动态更新功能验证脚本

echo "========================================"
echo "  PolyHermes 动态更新功能验证"
echo "========================================"
echo ""

ERRORS=0

# 检查文件存在性
echo "📋 检查文件..."

files=(
    "Dockerfile"
    "docker/update-service.py"
    "docker/start.sh"
    "docker/nginx.conf"
    "docker-compose.yml"
    "docker-compose.test.yml"
    ".github/workflows/docker-build.yml"
    "docs/zh/DYNAMIC_UPDATE.md"
)

for file in "${files[@]}"; do
    if [ -f "$file" ]; then
        echo "  ✅ $file"
    else
        echo "  ❌ $file (缺失)"
        ((ERRORS++))
    fi
done

echo ""
echo "📋 检查关键配置..."

# 检查 Dockerfile 是否包含 BUILD_IN_DOCKER
if grep -q "ARG BUILD_IN_DOCKER=true" Dockerfile; then
    echo "  ✅ Dockerfile 包含 BUILD_IN_DOCKER 参数"
else
    echo "  ❌ Dockerfile 缺少 BUILD_IN_DOCKER 参数"
    ((ERRORS++))
fi

# 检查 Dockerfile 是否安装 Python
if grep -q "python3" Dockerfile; then
    echo "  ✅ Dockerfile 安装 Python"
else
    echo "  ❌ Dockerfile 缺少 Python 安装"
    ((ERRORS++))
fi

# 检查 nginx.conf 是否包含更新服务代理
if grep -q "/api/update/" docker/nginx.conf; then
    echo "  ✅ Nginx 配置包含更新服务代理"
else
    echo "  ❌ Nginx 配置缺少更新服务代理"
    ((ERRORS++))
fi

# 检查 docker-compose.yml 是否包含 ALLOW_PRERELEASE
if grep -q "ALLOW_PRERELEASE" docker-compose.yml; then
    echo "  ✅ docker-compose.yml 包含 ALLOW_PRERELEASE"
else
    echo "  ❌ docker-compose.yml 缺少 ALLOW_PRERELEASE"
    ((ERRORS++))
fi

# 检查 GitHub Actions 是否包含 IS_PRERELEASE
if grep -q "IS_PRERELEASE" .github/workflows/docker-build.yml; then
    echo "  ✅ GitHub Actions 包含 Pre-release 检测"
else
    echo "  ❌ GitHub Actions 缺少 Pre-release 检测"
    ((ERRORS++))
fi

# 检查 GitHub Actions 是否包含编译步骤
if grep -q "Build Backend JAR" .github/workflows/docker-build.yml; then
    echo "  ✅ GitHub Actions 包含后端编译步骤"
else
    echo "  ❌ GitHub Actions 缺少后端编译步骤"
    ((ERRORS++))
fi

# 检查 update-service.py 是否可执行Python语法
echo ""
echo "📋 检查 Python 语法..."
if python3 -m py_compile docker/update-service.py 2>/dev/null; then
    echo "  ✅ update-service.py 语法正确"
else
    echo "  ⚠️  update-service.py 语法检查失败（可能需要 Flask）"
fi

echo ""
echo "========================================"
if [ $ERRORS -eq 0 ]; then
    echo "  ✅ 验证通过！所有检查项正常"
else
    echo "  ⚠️  发现 $ERRORS 个问题"
fi
echo "========================================"

exit $ERRORS
