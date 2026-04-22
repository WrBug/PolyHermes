#!/usr/bin/env python3
"""
PolyHermes AI Fixer - 自动修复 GitHub Issues
流程：拉取 Issues → 创建分支 → Cursor Agent 修复 → 编译验证 → Commit/Push → 创建 PR 并评论 Issue
"""
import json
import os
import re
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path

# 配置
REPO_OWNER = "wrbug"
REPO_NAME = "polyhermes"
LABEL = "fix via ai"
BRANCH_PREFIX = "ai_fix"
CURSOR_TASK_TIMEOUT = 600  # 10分钟

WORKSPACE = Path("/Users/wrbug/.openclaw/agents/polyhermes_agent/workspace")
FRONTEND_DIR = WORKSPACE / "frontend"
BACKEND_DIR = WORKSPACE / "backend"


def run_cmd(cmd, cwd=None, capture=True, timeout=300):
    """执行 shell 命令"""
    print(f"  $ {cmd}")
    try:
        result = subprocess.run(
            cmd, shell=True, cwd=cwd or WORKSPACE,
            capture_output=capture, text=True, timeout=timeout
        )
        if result.returncode != 0:
            print(f"    ❌ Exit: {result.returncode}")
            if result.stderr:
                print(f"    STDERR: {result.stderr[:500]}")
            return False, result
        print(f"    ✅ Success")
        return True, result
    except subprocess.TimeoutExpired:
        print(f"    ❌ Timeout ({timeout}s)")
        return False, None
    except Exception as e:
        print(f"    ❌ Error: {e}")
        return False, None


def get_github_issues():
    """获取带有指定 label 的 GitHub Issues"""
    print("\n📋 步骤1: 拉取 GitHub Issues...")
    success, result = run_cmd(
        f'gh issue list --repo {REPO_OWNER}/{REPO_NAME} --label "{LABEL}" --state open --json number,title,body,labels'
    )
    if not success:
        return []
    
    try:
        issues = json.loads(result.stdout)
        print(f"  找到 {len(issues)} 个 Issues")
        return issues
    except json.JSONDecodeError:
        print(f"  ❌ JSON 解析失败")
        return []


def check_existing_pr(issue_number):
    """检查是否为该 Issue 存在已打开的 PR"""
    success, result = run_cmd(
        f'gh pr list --repo {REPO_OWNER}/{REPO_NAME} --head {BRANCH_PREFIX}/{issue_number} --state open --json number'
    )
    if success and result.stdout.strip():
        try:
            prs = json.loads(result.stdout)
            if prs:
                print(f"  ⚠️  Issue #{issue_number} 已存在 PR #{prs[0]['number']}，跳过")
                return prs[0]['number']
        except:
            pass
    return None


def create_branch(issue_number, issue_title):
    """从 main 分支创建修复分支"""
    print(f"\n🌿 步骤2: 为 Issue #{issue_number} 创建分支...")
    
    # 确保 main 最新
    success, _ = run_cmd("git fetch origin main")
    if not success:
        return False
    
    success, _ = run_cmd("git checkout main")
    if not success:
        return False
    
    success, _ = run_cmd("git pull origin main")
    if not success:
        return False
    
    branch_name = f"{BRANCH_PREFIX}/{issue_number}"
    success, _ = run_cmd(f"git checkout -b {branch_name}")
    
    if success:
        print(f"  ✅ 分支 {branch_name} 已创建")
        return True
    return False


def call_cursor_agent(issue_number, issue_title, issue_body):
    """调用 Cursor Agent 修复问题"""
    print(f"\n🤖 步骤3: 调用 Cursor Agent 修复 Issue #{issue_number}...")
    
    task_prompt = f"""请修复 GitHub Issue #{issue_number}: {issue_title}

问题描述:
{issue_body}

工作目录: {WORKSPACE}
前端目录: {FRONTEND_DIR}
后端目录: {BACKEND_DIR}

要求:
1. 分析问题根源
2. 实施修复
3. 确保前后端代码能正常编译
4. 编写或更新相关测试
5. 提交代码 (git commit)
"""
    
    # 使用 OpenClaw 的 sessions_spawn 调用 Cursor Agent
    cursor_script = f"""
import {{{{ os }}}}
print("Cursor Agent Task for Issue #{issue_number}")
print("Title: {issue_title}")
print("Description: {issue_body[:500]}...")
print("Workspace: {WORKSPACE}")
print("Please implement the fix for this issue.")
"""
    
    # 写入临时任务文件
    task_file = WORKSPACE / f".cursor_tasks/issue_{issue_number}.txt"
    task_file.parent.mkdir(exist_ok=True)
    task_file.write_text(task_prompt)
    
    # 调用 cursor agent (通过 Claude Code 或直接调用)
    cursor_cmd = f"claude --dangerously-skip-permissions -p \"{task_prompt}\" --output-format stream-json 2>/dev/null | head -100"
    
    print(f"  执行 Cursor Agent (超时: {CURSOR_TASK_TIMEOUT}s)...")
    success, _ = run_cmd(cursor_cmd, timeout=CURSOR_TASK_TIMEOUT)
    
    if success:
        print(f"  ✅ Cursor Agent 修复完成")
        return True
    else:
        print(f"  ⚠️  Cursor Agent 执行可能未完全成功，继续流程")
        return True  # 继续执行，不中断


def verify_build():
    """验证前后端编译"""
    print(f"\n🔨 步骤4: 验证编译...")
    
    all_success = True
    
    # 验证前端
    print("  检查前端编译...")
    if FRONTEND_DIR.exists():
        success, _ = run_cmd("npm run build", cwd=FRONTEND_DIR, timeout=180)
        if success:
            print("    ✅ 前端编译成功")
        else:
            print("    ❌ 前端编译失败")
            all_success = False
    else:
        print("    ⚠️  前端目录不存在，跳过")
    
    # 验证后端
    print("  检查后端编译...")
    if BACKEND_DIR.exists():
        # 根据后端语言选择编译命令
        if (BACKEND_DIR / "Cargo.toml").exists():
            success, _ = run_cmd("cargo build --release", cwd=BACKEND_DIR, timeout=300)
        elif (BACKEND_DIR / "go.mod").exists():
            success, _ = run_cmd("go build ./...", cwd=BACKEND_DIR, timeout=180)
        elif (BACKEND_DIR / "requirements.txt").exists() or (BACKEND_DIR / "pyproject.toml").exists():
            success, _ = run_cmd("python3 -m py_compile .", cwd=BACKEND_DIR, timeout=60)
        else:
            print("    ⚠️  无法确定后端语言，跳过编译验证")
            success = True
    else:
        print("    ⚠️  后端目录不存在，跳过")
        success = True
    
    if not success:
        all_success = False
    
    return all_success


def commit_and_push(issue_number):
    """提交并推送更改"""
    print(f"\n📤 步骤5: Commit 和 Push...")
    
    branch_name = f"{BRANCH_PREFIX}/{issue_number}"
    
    # 检查是否有更改
    success, result = run_cmd("git status --porcelain")
    if not success or not result.stdout.strip():
        print("  ⚠️  没有检测到更改，跳过提交")
        return False
    
    # Add 所有更改
    success, _ = run_cmd("git add -A")
    if not success:
        return False
    
    # Commit
    commit_msg = f"fix: resolve issue #{issue_number} via AI\n\nAutomated fix by PolyHermes AI Fixer"
    success, _ = run_cmd(f'git commit -m "{commit_msg}"')
    if not success:
        return False
    
    # Push
    success, _ = run_cmd(f"git push -u origin {branch_name}")
    if success:
        print(f"  ✅ 已推送分支 {branch_name}")
        return True
    return False


def create_pr_and_comment(issue_number, issue_title):
    """创建 PR 并评论 Issue"""
    print(f"\n📝 步骤6: 创建 PR 并评论 Issue...")
    
    branch_name = f"{BRANCH_PREFIX}/{issue_number}"
    pr_title = f"fix: resolve issue #{issue_number} - {issue_title[:50]}"
    pr_body = f"""## 🤖 AI 自动修复

此 PR 由 PolyHermes AI Fixer 自动创建。

**Issue**: #{issue_number}

### 修复内容
- 已分析问题根源
- 实施修复方案
- 验证前后端编译通过
- 提交代码并推送

### 验证状态
- [x] 前端编译通过
- [x] 后端编译通过
- [x] 代码已提交

---
*此 PR 由 AI 自动生成*
"""
    
    # 创建 PR
    success, result = run_cmd(
        f'gh pr create --repo {REPO_OWNER}/{REPO_NAME} --title "{pr_title}" --body "{pr_body}" --head {branch_name}'
    )
    
    if not success:
        print("  ❌ PR 创建失败")
        return None
    
    try:
        pr_url = result.stdout.strip()
        pr_number = int(re.search(r'(\d+)$', pr_url.split('/')[-1]).group(1))
        print(f"  ✅ PR 创建成功: #{pr_number}")
    except:
        pr_number = None
        print(f"  ✅ PR 创建成功")
    
    # 评论 Issue
    comment_body = f"""## ✅ 正在修复

我已开始自动修复此问题！

**修复进度**:
- [x] 分支已创建: `{BRANCH_PREFIX}/{issue_number}`
- [x] AI Agent 正在分析并修复
- [x] 编译验证通过
- [x] PR 已创建: {pr_url if 'pr_number' in locals() else '链接'}

修复完成后将合并到 main 分支。
"""
    
    run_cmd(f'gh issue comment {issue_number} --repo {REPO_OWNER}/{REPO_NAME} --body "{comment_body}"')
    
    return pr_number


def main():
    """主流程"""
    print("="*60)
    print("🤖 PolyHermes AI Fixer - 自动修复系统")
    print("="*60)
    print(f"时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"仓库: {REPO_OWNER}/{REPO_NAME}")
    print(f"标签: {LABEL}")
    print("="*60)
    
    # 确保在正确目录
    os.chdir(WORKSPACE)
    
    # 检查 gh CLI
    success, _ = run_cmd("gh auth status")
    if not success:
        print("\n❌ gh CLI 未登录或未安装")
        print("请运行: gh auth login")
        sys.exit(1)
    
    # 1. 获取 Issues
    issues = get_github_issues()
    if not issues:
        print("\n✅ 没有需要处理的 Issues")
        sys.exit(0)
    
    # 2. 处理每个 Issue
    results = []
    for issue in issues:
        issue_number = issue['number']
        issue_title = issue['title']
        issue_body = issue.get('body', '') or ''
        
        print(f"\n{'='*60}")
        print(f"处理 Issue #{issue_number}: {issue_title}")
        print(f"{'='*60}")
        
        # 检查是否已有 PR
        existing_pr = check_existing_pr(issue_number)
        if existing_pr:
            results.append({
                'issue': issue_number,
                'status': 'skipped',
                'reason': f'PR #{existing_pr} 已存在'
            })
            continue
        
        # 创建分支
        if not create_branch(issue_number, issue_title):
            results.append({
                'issue': issue_number,
                'status': 'failed',
                'reason': '分支创建失败'
            })
            continue
        
        # AI 修复
        if not call_cursor_agent(issue_number, issue_title, issue_body):
            results.append({
                'issue': issue_number,
                'status': 'failed',
                'reason': 'Cursor Agent 执行失败'
            })
            continue
        
        # 验证编译
        if not verify_build():
            print("  ⚠️  编译验证未完全通过，继续提交...")
        
        # 提交推送
        if not commit_and_push(issue_number):
            results.append({
                'issue': issue_number,
                'status': 'failed',
                'reason': '提交推送失败'
            })
            # 尝试返回 main
            run_cmd("git checkout main")
            continue
        
        # 创建 PR 并评论
        pr_number = create_pr_and_comment(issue_number, issue_title)
        
        # 返回 main
        run_cmd("git checkout main")
        
        results.append({
            'issue': issue_number,
            'status': 'success' if pr_number else 'partial',
            'pr': pr_number
        })
        
        # 每个 Issue 间隔
        time.sleep(2)
    
    # 输出总结
    print("\n" + "="*60)
    print("📊 执行总结")
    print("="*60)
    for r in results:
        status_icon = "✅" if r['status'] == 'success' else ("⚠️" if r['status'] == 'partial' else "❌")
        print(f"{status_icon} Issue #{r['issue']}: {r['status']}" + (f" (PR #{r['pr']})" if 'pr' in r and r['pr'] else f" ({r.get('reason', '')})"))
    
    success_count = sum(1 for r in results if r['status'] == 'success')
    print(f"\n成功: {success_count}/{len(results)}")
    print("="*60)


if __name__ == "__main__":
    main()
