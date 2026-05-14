---
name: polyhermes-ai-fixer
description: Autonomous GitHub issue fixer. Uses AI to automatically fix issues labeled "fix via ai" by creating branches, calling Cursor Agent, verifying builds, and creating PRs.
---

# polyhermes-ai-fixer

## 使用方式

```bash
cd skills/polyhermes-ai-fixer/scripts
python3 run.py
```

## 工作流程

1. **拉取 Issues**: 从 GitHub 获取带有 "fix via ai" label 的 Issues
2. **创建分支**: 为每个 Issue 从 main 分支创建 `ai_fix/n_xxx` 分支
3. **AI 修复**: 调用 Cursor Agent 进行代码修复
4. **编译验证**: 验证前端和后端代码能正常编译
5. **提交推送**: Commit 并 Push 更改
6. **创建 PR**: 创建 Pull Request 并评论相关 Issue

## 依赖

- Python 3.x
- gh CLI (GitHub CLI)
- Cursor Agent
- Node.js (前端编译)
- Go/Rust/Python (后端编译)

## 注意事项

- 确保 gh CLI 已登录 (`gh auth status`)
- Cursor Agent 需要正确配置
- 建议先在测试环境验证
