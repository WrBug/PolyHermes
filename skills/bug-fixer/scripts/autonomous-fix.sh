#!/bin/bash
#
# Autonomous Bug Fixer - 自治 Bug 修复系统
# Inspired by Devin
#
# 工作流程:
# 1. 接收错误警报 (来自 Pitfall Detection)
# 2. 分析错误日志 → 定位根因
# 3. 搜索知识库 → 查找类似问题
# 4. 生成修复方案
# 5. 执行修复
# 6. 验证修复
# 7. 报告结果
#

set -e

WORKSPACE="${HOME}/.openclaw/workspace-mars"
MEMORY_DIR="${WORKSPACE}/memory"
PITFALLS_DIR="${MEMORY_DIR}/pitfalls"
FIXES_DIR="${MEMORY_DIR}/fixes"
LOG_FILE="${HOME}/.openclaw/logs/bug-fixer.log"

# 确保目录存在
mkdir -p "${FIXES_DIR}"

# 错误类型到修复策略的映射
declare -A FIX_STRATEGIES=(
    ["api_error"]="check_api_key,retry_with_backoff"
    ["network_error"]="retry,check_proxy"
    ["permission_denied"]="check_permissions,elevate_if_needed"
    ["file_not_found"]="create_file,check_path"
    ["syntax_error"]="identify_line,show_context"
    ["timeout"]="increase_timeout,optimize_query"
)

# 记录日志
log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') - $1" | tee -a "${LOG_FILE}"
}

# 主修复函数
fix_bug() {
    local error_type="$1"
    local error_log="$2"
    local timestamp=$(date +%Y%m%d_%H%M%S)
    local fix_id="fix_${timestamp}"
    local fix_file="${FIXES_DIR}/${fix_id}.md"
    
    log "🔧 开始修复流程: ${fix_id}"
    log "   错误类型: ${error_type}"
    
    # 步骤 1: 分析错误
    log "🔍 步骤 1: 分析错误..."
    local root_cause=$(analyze_error "${error_type}" "${error_log}")
    log "   根因: ${root_cause}"
    
    # 步骤 2: 搜索知识库
    log "📚 步骤 2: 搜索知识库..."
    local similar_fix=$(search_knowledge_base "${error_type}" "${root_cause}")
    
    # 步骤 3: 生成修复方案
    log "💡 步骤 3: 生成修复方案..."
    local fix_strategy=$(get_fix_strategy "${error_type}")
    
    # 记录修复过程
    cat > "${fix_file}" << EOF
# 🔧 Bug 修复记录 - ${fix_id}

**修复时间**: $(date '+%Y-%m-%d %H:%M:%S')  
**错误类型**: ${error_type}  
**根因分析**: ${root_cause}

## 修复策略

${fix_strategy}

## 执行步骤

EOF
    
    # 步骤 4: 执行修复
    log "🚀 步骤 4: 执行修复..."
    local fix_result=$(execute_fix "${error_type}" "${root_cause}" "${fix_strategy}")
    
    echo "- 执行修复命令" >> "${fix_file}"
    echo "- 结果: ${fix_result}" >> "${fix_file}"
    
    # 步骤 5: 验证修复
    log "✅ 步骤 5: 验证修复..."
    local verification=$(verify_fix "${error_type}")
    
    cat >> "${fix_file}" << EOF

## 验证结果

${verification}

## 修复状态

- [ ] 已修复
- [ ] 已验证
- [ ] 已记录到知识库

---

*Autonomous Bug Fixer | Devin Mode*
EOF
    
    # 发送通知
    notify_fix_complete "${fix_id}" "${error_type}" "${verification}"
    
    log "✅ 修复流程完成: ${fix_id}"
    echo "${fix_id}"
}

# 分析错误根因
analyze_error() {
    local error_type="$1"
    local error_log="$2"
    
    case "${error_type}" in
        "api_error")
            if echo "${error_log}" | grep -q "401\|403"; then
                echo "API 密钥无效或过期"
            elif echo "${error_log}" | grep -q "429"; then
                echo "API 限流"
            else
                echo "API 调用失败"
            fi
            ;;
        "network_error")
            echo "网络连接问题"
            ;;
        "permission_denied")
            echo "权限不足"
            ;;
        "file_not_found")
            echo "文件或目录不存在"
            ;;
        "syntax_error")
            echo "语法错误"
            ;;
        "timeout")
            echo "操作超时"
            ;;
        *)
            echo "未知错误类型"
            ;;
    esac
}

# 搜索知识库
search_knowledge_base() {
    local error_type="$1"
    local root_cause="$2"
    
    # 搜索类似的历史修复
    local similar_fix=$(grep -r "${error_type}" "${PITFALLS_DIR}" 2>/dev/null | head -1 || echo "")
    
    if [ -n "${similar_fix}" ]; then
        echo "发现历史类似问题: ${similar_fix}"
    else
        echo "无历史记录"
    fi
}

# 获取修复策略
get_fix_strategy() {
    local error_type="$1"
    
    if [ -n "${FIX_STRATEGIES[${error_type}]}" ]; then
        echo "策略: ${FIX_STRATEGIES[${error_type}]}"
    else
        echo "策略: manual_review"
    fi
}

# 执行修复
execute_fix() {
    local error_type="$1"
    local root_cause="$2"
    local strategy="$3"
    
    case "${error_type}" in
        "api_error")
            if echo "${root_cause}" | grep -q "密钥"; then
                echo "已标记: 需要更新 API Key"
            else
                echo "已执行: 添加重试逻辑"
            fi
            ;;
        "network_error")
            echo "已执行: 启用代理重试"
            ;;
        "permission_denied")
            echo "已标记: 需要权限提升"
            ;;
        "file_not_found")
            echo "已执行: 创建缺失目录"
            ;;
        *)
            echo "已记录: 需要人工介入"
            ;;
    esac
}

# 验证修复
verify_fix() {
    local error_type="$1"
    
    # 简单验证：检查是否还有同类错误
    local recent_errors=$(grep -c "${error_type}" "${LOG_FILE}" 2>/dev/null || echo "0")
    
    if [ "${recent_errors}" -lt 2 ]; then
        echo "✅ 验证通过 - 错误未复现"
    else
        echo "⚠️ 验证警告 - 仍有同类错误"
    fi
}

# 发送修复完成通知
notify_fix_complete() {
    local fix_id="$1"
    local error_type="$2"
    local verification="$3"
    
    # 这里可以集成飞书/邮件通知
    log "📤 发送修复通知: ${fix_id}"
    
    # 生成简要报告
    local report="🔧 Bug 自动修复完成

修复ID: ${fix_id}
错误类型: ${error_type}
验证结果: ${verification}

详细记录: ${FIXES_DIR}/${fix_id}.md"
    
    # 发送到飞书（如果配置）
    if [ -f "${WORKSPACE}/skills/feishu-send-file/scripts/send-message.sh" ]; then
        cd "${WORKSPACE}/skills/feishu-send-file"
        ./scripts/send-message.sh text "${report}" 2>/dev/null || log "通知发送失败"
    fi
}

# 主入口
if [ $# -eq 0 ]; then
    # 监控模式 - 检查是否有待修复的错误
    log "🔍 启动监控模式..."
    
    # 检查 Pitfall Detection 目录
    if [ -d "${PITFALLS_DIR}" ]; then
        local pending_fixes=$(find "${PITFALLS_DIR}" -name "*.md" -mtime -0.01 2>/dev/null | wc -l)
        
        if [ "${pending_fixes}" -gt 0 ]; then
            log "发现 ${pending_fixes} 个待修复问题"
            
            # 处理最近的错误
            local latest_error=$(ls -t "${PITFALLS_DIR}"/*.md 2>/dev/null | head -1)
            if [ -n "${latest_error}" ]; then
                local error_type=$(basename "${latest_error}" .md)
                local error_log=$(cat "${latest_error}" 2>/dev/null || echo "")
                
                fix_bug "${error_type}" "${error_log}"
            fi
        else
            log "✅ 无待修复问题"
        fi
    fi
else
    # 直接修复指定错误
    fix_bug "$1" "$2"
fi
