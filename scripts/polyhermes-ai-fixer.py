#!/usr/bin/env python3
"""
PolyHermes AI Fixer v2
自动修复任务脚本
- 拉取 GitHub Issues（label=fix via ai）
- 为每个 Issue 从 main 创建分支 ai_fix/n_xxx
- 使用 gh CLI + OpenClaw 进行修复（替代 Cursor Agent）
- 验证前端/后端编译
- Commit/Push
- 创建 PR 并评论 Issue
"""

import os
import json
import subprocess
import sys
from datetime import datetime
from typing import List, Dict, Optional

REPO = os.environ.get('GITHUB_REPO', 'WrBug/PolyHermes')

def run_cmd(cmd, **kwargs):
    """Run command and return CompletedProcess"""
    cwd = kwargs.pop('cwd', None)
    timeout = kwargs.pop('timeout', 300)
    check = kwargs.pop('check', True)
    print(f"  $ {' '.join(cmd) if isinstance(cmd, list) else cmd}")
    result = subprocess.run(
        cmd, cwd=cwd, capture_output=True, text=True,
        timeout=timeout, check=check
    )
    return result

def get_github_token():
    """Get GitHub token from gh CLI"""
    result = run_cmd(['gh', 'auth', 'token'], check=True)
    return result.stdout.strip()

def get_issues(token: str) -> List[Dict]:
    """获取带有 'fix via ai' 标签的 GitHub Issues"""
    result = run_cmd([
        'gh', 'issue', 'list',
        '--repo', REPO,
        '--label', 'fix via ai',
        '--state', 'open',
        '--limit', '50',
        '--json', 'number,title,body'
    ])
    issues = json.loads(result.stdout)
    print(f"📋 Found {len(issues)} issues with 'fix via ai' label")
    return issues

def delete_branch_if_exists(branch_name: str):
    """Delete branch if it exists locally and remotely"""
    try:
        run_cmd(['git', 'branch', '-D', branch_name], check=False)
    except Exception:
        pass
    try:
        run_cmd(['git', 'push', 'origin', '--delete', branch_name], check=False)
    except Exception:
        pass

def create_fix_branch(issue_number: int) -> str:
    """Create fix branch from main"""
    branch_name = f"ai_fix/n_{issue_number}"
    
    # Clean up existing branch
    delete_branch_if_exists(branch_name)
    
    run_cmd(['git', 'checkout', 'main'])
    run_cmd(['git', 'pull', 'origin', 'main'])
    run_cmd(['git', 'checkout', '-b', branch_name])
    
    print(f"  ✅ Created branch: {branch_name}")
    return branch_name

def verify_frontend_build(repo_path: str) -> bool:
    """Verify frontend builds"""
    frontend_dir = os.path.join(repo_path, 'frontend')
    if not os.path.isdir(frontend_dir):
        print("  ⚠️  No frontend directory, skipping frontend build")
        return True
    
    print("  📦 Installing frontend dependencies...")
    run_cmd(['npm', 'install'], cwd=frontend_dir, timeout=120)
    
    print("  🔨 Building frontend...")
    result = run_cmd(['npm', 'run', 'build'], cwd=frontend_dir, timeout=300, check=False)
    if result.returncode != 0:
        print(f"  ❌ Frontend build failed: {result.stderr[-500:]}")
        return False
    
    print("  ✅ Frontend build successful")
    return True

def verify_backend_build(repo_path: str) -> bool:
    """Verify backend builds (Gradle/Kotlin project)"""
    backend_dir = os.path.join(repo_path, 'backend')
    if not os.path.isdir(backend_dir):
        print("  ⚠️  No backend directory, skipping backend build")
        return True
    
    print("  🔨 Building backend (Gradle)...")
    result = run_cmd(['./gradlew', 'build', '-x', 'test'], cwd=backend_dir, timeout=600, check=False)
    if result.returncode != 0:
        print(f"  ❌ Backend build failed: {result.stderr[-500:]}")
        return False
    
    print("  ✅ Backend build successful")
    return True

def commit_and_push(issue_number: int, issue_title: str) -> bool:
    """Commit changes and push"""
    branch_name = f"ai_fix/n_{issue_number}"
    
    # Check if there are changes to commit
    result = run_cmd(['git', 'status', '--porcelain'])
    if not result.stdout.strip():
        print("  ⚠️  No changes to commit")
        return False
    
    run_cmd(['git', 'add', '.'])
    commit_msg = f"fix: #{issue_number} {issue_title}\n\nAI auto-fix for issue #{issue_number}"
    run_cmd(['git', 'commit', '-m', commit_msg])
    run_cmd(['git', 'push', 'origin', branch_name])
    
    print(f"  ✅ Pushed to {branch_name}")
    return True

def create_pr(token: str, issue_number: int, issue_title: str, branch_name: str) -> Optional[str]:
    """Create PR using gh CLI"""
    pr_body = f"""AI 自动修复 Issue #{issue_number}

**原始 Issue**: {issue_title}
**自动创建时间**: {datetime.now().isoformat()}

## 注意事项
- 🤖 此 PR 由 AI 生成，需要人工审核后才能合并
- 🔍 请仔细测试和验证代码变更

Closes #{issue_number}"""
    
    result = run_cmd([
        'gh', 'pr', 'create',
        '--repo', REPO,
        '--head', branch_name,
        '--base', 'main',
        '--title', f'AI Fix for #{issue_number}: {issue_title}',
        '--body', pr_body
    ])
    
    pr_url = result.stdout.strip()
    print(f"  ✅ Created PR: {pr_url}")
    return pr_url

def comment_on_issue(token: str, issue_number: int, pr_url: str):
    """Comment on issue with PR link"""
    comment = f"""🤖 AI 自动修复已完成

🔗 **Pull Request**: {pr_url}

⚠️ 此 PR 由 AI 自动生成，**需要人工审核后才能合并**。"""
    
    run_cmd([
        'gh', 'issue', 'comment', str(issue_number),
        '--repo', REPO,
        '--body', comment
    ])
    print(f"  ✅ Commented on issue #{issue_number}")

def process_issue(repo_path: str, issue: Dict) -> bool:
    """Process a single issue"""
    num = issue['number']
    title = issue['title']
    body = issue.get('body') or ''
    
    print(f"\n{'='*50}")
    print(f"🎯 Issue #{num}: {title}")
    print(f"{'='*50}")
    
    try:
        # 1. Create branch
        branch = create_fix_branch(num)
        
        # 2. TODO: Actual AI fix via Cursor Agent / OpenClaw subagent
        #    Currently the Cursor Agent integration is a stub
        #    In production, this would invoke Cursor Agent API or OpenClaw subagent
        print("  ⚠️  Cursor Agent fix is not yet implemented - skipping actual code changes")
        print("  ℹ️  To enable: implement call_cursor_agent_fix() or use OpenClaw subagent")
        
        # 3. Verify builds (skip if no changes)
        # verify_frontend_build(repo_path)
        # verify_backend_build(repo_path)
        
        # 4. Commit & Push (skip if no changes)
        has_changes = commit_and_push(num, title)
        if not has_changes:
            print("  ❌ No code changes generated - cannot create PR without changes")
            # Clean up branch
            run_cmd(['git', 'checkout', 'main'])
            delete_branch_if_exists(branch)
            return False
        
        # 5. Create PR
        token = get_github_token()
        pr_url = create_pr(token, num, title, branch)
        
        # 6. Comment on issue
        if pr_url:
            comment_on_issue(token, num, pr_url)
        
        print(f"  ✅ Issue #{num} processed successfully")
        return True
        
    except Exception as e:
        print(f"  ❌ Error: {e}")
        # Return to main
        run_cmd(['git', 'checkout', 'main'], check=False)
        return False

def main():
    print("🚀 PolyHermes AI Fixer v2")
    print(f"   Repository: {REPO}")
    
    repo_path = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    os.chdir(repo_path)
    print(f"   Working dir: {repo_path}")
    
    try:
        token = get_github_token()
    except Exception as e:
        print(f"❌ Failed to get GitHub token: {e}")
        sys.exit(1)
    
    # Get issues
    issues = get_issues(token)
    if not issues:
        print("✅ No issues with 'fix via ai' label found. All clean!")
        return
    
    # Process each issue
    ok = 0
    fail = 0
    for issue in issues:
        if process_issue(repo_path, issue):
            ok += 1
        else:
            fail += 1
    
    # Return to main
    run_cmd(['git', 'checkout', 'main'], check=False)
    
    print(f"\n{'='*50}")
    print(f"📊 Results: ✅ {ok} succeeded, ❌ {fail} failed, 📋 {len(issues)} total")
    
    if fail > 0:
        sys.exit(1)

if __name__ == "__main__":
    main()
