name: polyhermes-ai-fixer
description: |
  Automated AI-powered bug fixer for PolyHermes project. Automatically detects GitHub Issues with 'fix via ai' label, creates branches, applies fixes using Cursor Agent, verifies builds, and creates PRs.
  Use when you need to automatically fix bugs in the PolyHermes project using AI assistance.

# PolyHermes AI Fixer

Automated AI-powered bug fixer for PolyHermes project

## Overview

This skill implements a complete automated bug fixing workflow for the PolyHermes project. It fetches GitHub Issues marked with 'fix via ai' label, automatically creates fix branches, applies AI-powered fixes using Cursor Agent, verifies builds, and creates PRs with appropriate comments.

## Workflow

The complete workflow includes:

1. **Issue Detection** - Fetch GitHub Issues with 'fix via ai' label
2. **Branch Creation** - Create `ai_fix/n_xxx` branches from main for each issue
3. **AI Fixing** - Use Cursor Agent to automatically fix the identified issues
4. **Build Verification** - Verify both frontend and backend compilation
5. **Commit & Push** - Commit changes and push to remote repository
6. **PR Creation** - Create pull requests and comment on original issues

## Usage

```bash
# Execute the complete AI fixing workflow
cd scripts
python3 run.py
```

## Prerequisites

- GitHub CLI (`gh`) must be installed and authenticated
- Cursor Agent must be available for AI-powered fixes
- Git repository must be properly configured with remote
- Node.js and npm for build verification

## Configuration

The script automatically detects:
- GitHub repository from current git configuration
- Current branch (defaults to main)
- Remote repository URL
- Issue labels and filtering

## Output

The workflow provides detailed logs for each step:
- Issues found and their details
- Branch creation status
- Fix application results
- Build verification outcomes
- PR creation progress
- Issue comment updates

## Error Handling

- Continues to next issue if one fails
- Provides detailed error messages
- Logs all operations for debugging
- Handles authentication issues gracefully

---

*Automating AI-powered bug fixes for PolyHermes project*