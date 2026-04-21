#!/bin/bash

# PolyHermes AI Fixer Runner Script
# This script executes the polyhermes-ai-fixer script

# Navigate to scripts directory
cd "$(dirname "$0")"

echo "🚀 Starting PolyHermes AI Fixer..."

# Check if GitHub token is set
if [ -z "$GITHUB_TOKEN" ]; then
    echo "❌ GITHUB_TOKEN environment variable is not set"
    exit 1
fi

# Check if Python 3 is available
if ! command -v python3 &> /dev/null; then
    echo "❌ Python 3 is not available"
    exit 1
fi

# Run the AI fixer script
python3 polyhermes-ai-fixer.py

echo "✅ PolyHermes AI Fixer completed"