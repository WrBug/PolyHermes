---
name: bug-fixer
description: Autonomous bug diagnosis and repair. Use when user reports a bug, error, or unexpected behavior in code or systems.
---

# bug-fixer

## 使用方式

```bash
# 自动诊断并修复
./scripts/autonomous-fix.sh <问题描述>

# 或直接运行诊断
./scripts/autonomous-fix.sh diagnose <错误信息>
```

## 工作流程

1. **问题收集**: 收集错误日志、症状描述
2. **根因分析**: 定位问题根源
3. **修复执行**: 实施修复
4. **验证确认**: 确保问题解决

## 注意事项

- 修复前先备份原文件
- 修复后运行验证
- 记录修复过程到 `.learnings/ERRORS.md`
