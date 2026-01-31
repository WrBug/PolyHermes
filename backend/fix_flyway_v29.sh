#!/bin/bash
# 清理 Flyway V29 失败记录的脚本

echo "=== 清理 Flyway V29 失败记录 ==="
echo ""
echo "请确保 MySQL 正在运行，然后输入数据库密码"
echo ""

# 数据库配置
DB_HOST="localhost"
DB_PORT="3306"
DB_NAME="polymarket_bot"
DB_USER="root"

# 检查 MySQL 命令是否可用
if ! command -v mysql &> /dev/null; then
    echo "❌ 错误: 未找到 mysql 命令"
    echo ""
    echo "请使用数据库客户端（如 Navicat、DataGrip 等）执行以下 SQL："
    echo ""
    echo "-- 1. 查看 Flyway 历史记录"
    echo "SELECT version, description, installed_on, success "
    echo "FROM flyway_schema_history "
    echo "WHERE version >= 28"
    echo "ORDER BY installed_rank;"
    echo ""
    echo "-- 2. 删除 V29 的失败记录"
    echo "DELETE FROM flyway_schema_history WHERE version = '29';"
    echo ""
    exit 1
fi

# 执行清理
echo "正在连接数据库..."
mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -p "$DB_NAME" << 'EOF'
-- 查看当前状态
SELECT '=== 当前 Flyway 历史记录 ===' as '';
SELECT version, description, installed_on, success 
FROM flyway_schema_history 
WHERE version >= 28
ORDER BY installed_rank;

-- 删除 V29 失败记录
SELECT '=== 删除 V29 记录 ===' as '';
DELETE FROM flyway_schema_history WHERE version = '29';

-- 确认删除结果
SELECT CONCAT('已删除 ', ROW_COUNT(), ' 条记录') as result;

-- 再次查看状态
SELECT '=== 清理后的 Flyway 历史记录 ===' as '';
SELECT version, description, installed_on, success 
FROM flyway_schema_history 
WHERE version >= 28
ORDER BY installed_rank;
EOF

echo ""
echo "✅ 清理完成！现在可以重启应用了"
