#!/bin/bash
# Test script to run the AI fixer with basic setup

cd "$(dirname "$0")"

echo "🚀 Starting PolyHermes AI Fixer (Test Mode)...";

# Check if GitHub token is set
if [ -z "$GITHUB_TOKEN" ]; then
    echo "⚠️  GITHUB_TOKEN environment variable is not set"
    echo "📝 Please set your GitHub token before running the full fixer"
    echo ""
    echo "Example:"
    echo "  export GITHUB_TOKEN=your_github_token_here"
    echo ""
    echo "Environment variables needed:"
    echo "  GITHUB_TOKEN: GitHub personal access token"
    echo "  GITHUB_REPO: GitHub repository (default: WrBug/PolyHermes)"
    echo "  CURSOR_AGENT_CMD: Cursor agent command (optional)"
    exit 1
fi

echo "✅ Environment variables check passed"
echo "📋 Repository: ${GITHUB_REPO:-WrBug/PolyHermes}"
echo "🤖 Cursor Agent: ${CURSOR_AGENT_CMD:-cursor-agent}"

# Check if Python 3 is available
if ! command -v python3 &> /dev/null; then
    echo "❌ Python 3 is not available"
    exit 1
fi

echo "✅ Python 3 is available"

# Run the AI fixer script
python3 polyhermes-ai-fixer.py

echo "✅ PolyHermes AI Fixer completed"