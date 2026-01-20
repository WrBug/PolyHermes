#!/bin/bash

# PolyHermes Release 创建脚本
# 功能：创建 tag、推送 tag、创建 GitHub Release（支持 pre-release）

set -e

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 打印信息
info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 显示使用说明
usage() {
    cat << EOF
用法: $0 [选项]

选项:
    -t, --tag TAG              版本号 tag（必需，格式：v1.0.0）
    -T, --title TITLE          Release 标题（可选，默认使用 tag）
    -d, --description DESC     Release 描述内容（可选）
    -f, --description-file FILE 从文件读取 Release 描述（可选）
    -p, --prerelease           标记为 Pre-release（会自动拼接 -beta 后缀，默认：false）
    -y, --yes                  无交互模式，自动确认所有操作（默认：false）
    -h, --help                 显示此帮助信息

示例:
    # 创建正式版本
    $0 -t v1.0.1 -T "Release v1.0.1" -d "## 新功能\n- 功能1\n- 功能2"

    # 创建 Pre-release（自动拼接 -beta）
    $0 -t v1.0.1 -T "Release v1.0.1-beta" -d "测试版本" --prerelease
    # 实际创建的 tag: v1.0.1-beta

    # 从文件读取描述
    $0 -t v1.0.1 -f CHANGELOG.md --prerelease
    # 实际创建的 tag: v1.0.1-beta

    # 无交互模式（适合 CI/CD 或自动化脚本）
    $0 -t v1.0.1 -d "更新内容" --yes

版本号格式:
    - 必须格式: v数字.数字.数字 (例如: v1.0.0, v1.10.2, v1.1.12)
    - 如果指定 --prerelease，会自动拼接 -beta 后缀 (例如: v1.0.1 -> v1.0.1-beta)

EOF
}

# 验证版本号格式（只允许 v数字.数字.数字，不允许后缀）
validate_tag() {
    local tag=$1
    # 匹配格式：v数字.数字.数字（不允许后缀）
    if [[ ! "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        error "版本号格式不正确：$tag"
        error "应为 v数字.数字.数字 (例如: v1.0.0, v1.10.2, v1.1.12)"
        error "如果创建 Pre-release，请使用 --prerelease 参数，脚本会自动拼接 -beta 后缀"
        exit 1
    fi
    return 0
}

# 检查必要的工具
check_requirements() {
    local auto_yes=$1

    # 检查 git
    if ! command -v git &> /dev/null; then
        error "未找到 git 命令，请先安装 git"
        exit 1
    fi

    # 检查 GitHub CLI
    if ! command -v gh &> /dev/null; then
        error "未找到 GitHub CLI (gh) 命令"
        error "请先安装 GitHub CLI: https://cli.github.com/"
        exit 1
    fi

    # 检查是否已登录 GitHub
    if ! gh auth status &> /dev/null; then
        error "未登录 GitHub，请先运行: gh auth login"
        exit 1
    fi

    # 检查是否有未提交的更改
    if [[ -n $(git status --porcelain) ]]; then
        warn "检测到未提交的更改，建议先提交或暂存"
        if [[ "$auto_yes" == "true" ]]; then
            info "无交互模式：自动继续"
        else
            read -p "是否继续？(y/N): " -n 1 -r
            echo
            if [[ ! $REPLY =~ ^[Yy]$ ]]; then
                info "已取消"
                exit 0
            fi
        fi
    fi

    # 检查是否在正确的分支
    local current_branch=$(git branch --show-current)
    info "当前分支: $current_branch"
}

# 检查 tag 是否已存在
check_tag_exists() {
    local tag=$1
    local auto_yes=$2

    if git rev-parse "$tag" >/dev/null 2>&1; then
        error "Tag $tag 已存在（本地）"
        if [[ "$auto_yes" == "true" ]]; then
            info "无交互模式：自动删除并重新创建"
            git tag -d "$tag" || true
            git push origin ":refs/tags/$tag" || true
            info "已删除旧 tag: $tag"
        else
            read -p "是否删除并重新创建？(y/N): " -n 1 -r
            echo
            if [[ $REPLY =~ ^[Yy]$ ]]; then
                git tag -d "$tag" || true
                git push origin ":refs/tags/$tag" || true
                info "已删除旧 tag: $tag"
            else
                error "已取消"
                exit 1
            fi
        fi
    fi

    # 检查远程是否存在
    if git ls-remote --tags origin "$tag" | grep -q "$tag"; then
        error "Tag $tag 已存在于远程仓库"
        if [[ "$auto_yes" == "true" ]]; then
            info "无交互模式：自动删除并重新创建"
            git tag -d "$tag" || true
            git push origin ":refs/tags/$tag" || true
            info "已删除远程 tag: $tag"
        else
            read -p "是否删除并重新创建？(y/N): " -n 1 -r
            echo
            if [[ $REPLY =~ ^[Yy]$ ]]; then
                git tag -d "$tag" || true
                git push origin ":refs/tags/$tag" || true
                info "已删除远程 tag: $tag"
            else
                error "已取消"
                exit 1
            fi
        fi
    fi
}

# 主函数
main() {
    local TAG=""
    local TITLE=""
    local DESCRIPTION=""
    local DESCRIPTION_FILE=""
    local PRERELEASE=false
    local AUTO_YES=false

    # 解析参数
    while [[ $# -gt 0 ]]; do
        case $1 in
            -t|--tag)
                TAG="$2"
                shift 2
                ;;
            -T|--title)
                TITLE="$2"
                shift 2
                ;;
            -d|--description)
                DESCRIPTION="$2"
                shift 2
                ;;
            -f|--description-file)
                DESCRIPTION_FILE="$2"
                shift 2
                ;;
            -p|--prerelease)
                PRERELEASE=true
                shift
                ;;
            -y|--yes)
                AUTO_YES=true
                shift
                ;;
            -h|--help)
                usage
                exit 0
                ;;
            *)
                error "未知参数: $1"
                usage
                exit 1
                ;;
        esac
    done

    # 检查必需参数
    if [[ -z "$TAG" ]]; then
        error "缺少必需参数: --tag"
        usage
        exit 1
    fi

    # 验证版本号格式（不允许后缀）
    validate_tag "$TAG"

    # 如果指定了 --prerelease，自动拼接 -beta 后缀
    local BASE_TAG="$TAG"
    if [[ "$PRERELEASE" == "true" ]]; then
        TAG="${BASE_TAG}-beta"
        info "Pre-release 模式：tag 将自动拼接 -beta 后缀"
        info "基础版本: $BASE_TAG -> 实际 tag: $TAG"
    fi

    # 检查工具和环境
    check_requirements "$AUTO_YES"

    # 检查 tag 是否已存在（使用拼接后的 tag）
    check_tag_exists "$TAG" "$AUTO_YES"

    # 设置默认标题
    if [[ -z "$TITLE" ]]; then
        TITLE="$TAG"
    fi

    # 读取描述内容
    if [[ -n "$DESCRIPTION_FILE" ]]; then
        if [[ ! -f "$DESCRIPTION_FILE" ]]; then
            error "描述文件不存在: $DESCRIPTION_FILE"
            exit 1
        fi
        DESCRIPTION=$(cat "$DESCRIPTION_FILE")
    fi

    # 如果没有描述，使用默认值
    if [[ -z "$DESCRIPTION" ]]; then
        if [[ "$PRERELEASE" == "true" ]]; then
            DESCRIPTION="Pre-release $TAG"
        else
            DESCRIPTION="Release $TAG"
        fi
    fi

    # 显示即将执行的操作
    echo
    info "========================================="
    info "  PolyHermes Release 创建"
    info "========================================="
    info "Tag:         $TAG"
    info "Title:       $TITLE"
    info "Pre-release: $PRERELEASE"
    if [[ "$AUTO_YES" == "true" ]]; then
        info "模式:        无交互模式（自动确认）"
    fi
    info "Description:"
    echo "$DESCRIPTION" | sed 's/^/  /'
    info "========================================="
    echo

    # 确认操作
    if [[ "$AUTO_YES" == "true" ]]; then
        info "无交互模式：自动确认创建 Release"
    else
        read -p "确认创建 Release？(y/N): " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            info "已取消"
            exit 0
        fi
    fi

    # 1. 创建 tag（基于当前 HEAD）
    info "创建 tag: $TAG"
    git tag "$TAG"
    success "Tag 创建成功: $TAG"

    # 2. 推送 tag 到远程
    info "推送 tag 到远程..."
    git push origin "$TAG"
    success "Tag 推送成功"

    # 3. 创建 GitHub Release
    info "创建 GitHub Release..."
    
    local RELEASE_ARGS=(
        "$TAG"
        --title "$TITLE"
        --notes "$DESCRIPTION"
    )

    if [[ "$PRERELEASE" == "true" ]]; then
        RELEASE_ARGS+=(--prerelease)
    fi

    if gh release create "${RELEASE_ARGS[@]}"; then
        success "GitHub Release 创建成功！"
        
        # 获取 release URL
        local RELEASE_URL=$(gh release view "$TAG" --json url -q .url)
        info "Release URL: $RELEASE_URL"
        
        echo
        success "========================================="
        success "  Release 创建完成！"
        success "========================================="
        success "Tag:         $TAG"
        success "Pre-release: $PRERELEASE"
        success "URL:         $RELEASE_URL"
        success "========================================="
        echo
        info "GitHub Actions 将自动触发构建流程"
        
        if [[ "$PRERELEASE" == "true" ]]; then
            warn "这是 Pre-release，GitHub Actions 不会发送 Telegram 通知"
        fi
    else
        error "GitHub Release 创建失败"
        error "请手动在 GitHub 上创建 Release: https://github.com/WrBug/PolyHermes/releases/new"
        exit 1
    fi
}

# 执行主函数
main "$@"

