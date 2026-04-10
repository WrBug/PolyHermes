---
name: ai-bug-fixer
description: |
  AI Bug修复助手 - 利用大模型自动诊断和修复代码中的bug。
  当用户需要以下功能时触发此skill：
  (1) 自动诊断bug原因
  (2) 生成修复代码
  (3) 异常处理建议
  (4) 回归测试生成
  (5) 代码补丁生成
---

# AI Bug Fixer

AI Bug修复助手 — 自动诊断和修复代码问题

## 核心能力

### 1. Bug诊断
- **异常分析** - 解析错误堆栈
- **日志分析** - 从日志定位问题
- **代码审查** - 静态分析找bug
- **运行时分析** - 动态行为分析
- **根因定位** - 找出根本原因

### 2. 修复能力
- **自动修复** - 生成修复代码
- **补丁生成** - 生成diff补丁
- **重构建议** - 结构性修复
- **测试生成** - 验证修复的测试
- **代码解释** - 解释修复原因

### 3. 支持问题类型
- **空指针** - NullPointerException
- **索引越界** - IndexOutOfBounds
- **类型错误** - TypeError
- **资源泄漏** - 内存/连接泄漏
- **逻辑错误** - 业务逻辑bug
- **并发问题** - 竞态条件/死锁

### 4. 修复流程
- **问题识别** - 定位bug位置
- **原因分析** - 分析根本原因
- **方案生成** - 生成多个修复方案
- **方案评估** - 选择最佳方案
- **修复应用** - 应用修复代码

## 使用工作流

```bash
# 根据错误信息修复
python scripts/error_fixer.py --error "TypeError: ..." --file script.py

# 根据日志修复
python scripts/log_analyzer.py --log app.log --pattern ERROR

# 生成修复补丁
python scripts/patch_generator.py --buggy buggy.py --fixed fixed.py
```

---

*让AI帮你 debug，事半功倍。*
