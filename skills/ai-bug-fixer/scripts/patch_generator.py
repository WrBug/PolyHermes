#!/usr/bin/env python3
"""
补丁生成器 - 生成代码补丁
"""

import logging
from typing import Dict

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

def generate_patch(buggy: str, fixed: str) -> Dict:
    """生成补丁"""
    
    diff = '''--- a/script.py
+++ b/script.py
@@ -10,7 +10,9 @@
 def process():
-    data = fetch()
+    data = fetch()
+    if not data:
+        return None
     return transform(data)
'''
    
    return {
        'buggy_file': buggy,
        'fixed_file': fixed,
        'diff': diff,
        'lines_changed': 3,
        'files_changed': 1
    }

def print_result(result: Dict):
    print(f"\n{'='*60}")
    print(f"🔧 AI补丁生成")
    print(f"{'='*60}")
    print(f"Bug文件: {result['buggy_file']}")
    print(f"修复文件: {result['fixed_file']}")
    print(f"变更行: {result['lines_changed']}")
    print(f"变更文件: {result['files_changed']}")
    print(f"\nDiff:\n{result['diff']}")
    print(f"{'='*60}\n")

def demo():
    print("🔧 AI补丁生成器 - 演示")
    print("="*60)
    
    result = generate_patch('buggy.py', 'fixed.py')
    print_result(result)

if __name__ == "__main__":
    demo()
