#!/usr/bin/env node

/**
 * 检查前端多语言 key 完整性
 *
 * 扫描范围：frontend 下所有 .ts / .tsx / .js / .jsx（排除 node_modules、dist、build、*.d.ts）
 *
 * 支持的检查：
 * 1. 代码引用但 JSON 没有：某 ts/js 引用了 t("common.test")，但任意语言 common.json 里没有该 key → 报缺失
 * 2. 语言间不一致：zh-CN/zh-TW 的 JSON 有 common.xxx，但 en 的没有 → 报不一致（某些语言有、某些没有）
 */

import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { glob } from 'glob';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

interface CheckResult {
  usedKeys: Set<string>;
  localeKeys: {
    'zh-CN': Set<string>;
    'zh-TW': Set<string>;
    'en': Set<string>;
  };
  missingKeys: {
    'zh-CN': string[];
    'zh-TW': string[];
    'en': string[];
  };
  inconsistentKeys: Array<{
    key: string;
    existsIn: string[];
    missingIn: string[];
  }>;
}

const LOCALES = ['zh-CN', 'zh-TW', 'en'] as const;
type Locale = typeof LOCALES[number];

// 颜色输出（如果支持）
const colors = {
  reset: '\x1b[0m',
  red: '\x1b[31m',
  green: '\x1b[32m',
  yellow: '\x1b[33m',
  blue: '\x1b[34m',
  cyan: '\x1b[36m',
};

function log(message: string, color: keyof typeof colors = 'reset') {
  console.log(`${colors[color]}${message}${colors.reset}`);
}

/**
 * 从代码文件中提取所有 t() 调用中的 key
 */
function extractKeysFromCode(frontendDir: string): Set<string> {
  const keys = new Set<string>();
  
  // 匹配 t('key') 或 t("key") 或 t(`key`)
  const tPattern = /t\(['"`]([^'"`]+)['"`]\)/g;
  
  // 扫描所有 TS/JS 文件（含 .ts .tsx .js .jsx），排除 node_modules、dist、build、*.d.ts
  const files = glob.sync('**/*.{ts,tsx,js,jsx}', {
    cwd: frontendDir,
    ignore: ['**/node_modules/**', '**/dist/**', '**/build/**', '**/*.d.ts'],
    absolute: true,
  });
  
  for (const file of files) {
    try {
      const content = fs.readFileSync(file, 'utf-8');
      let match;
      
      while ((match = tPattern.exec(content)) !== null) {
        const key = match[1];
        // 过滤掉模板字符串中的变量（如 t(`key.${variable}`)）
        if (!key.includes('${') && !key.includes('${')) {
          keys.add(key);
        }
      }
    } catch (error) {
      console.error(`Error reading file ${file}:`, error);
    }
  }
  
  return keys;
}

/**
 * 加载语言文件
 */
function loadLocaleFile(localeDir: string, locale: Locale): Record<string, any> {
  const filePath = path.join(localeDir, locale, 'common.json');
  
  if (!fs.existsSync(filePath)) {
    log(`⚠️  语言文件不存在: ${filePath}`, 'yellow');
    return {};
  }
  
  try {
    const content = fs.readFileSync(filePath, 'utf-8');
    return JSON.parse(content);
  } catch (error) {
    log(`❌ 解析语言文件失败 ${filePath}: ${error}`, 'red');
    return {};
  }
}

/**
 * 展平嵌套对象为点分隔的 key 路径
 */
function flattenKeys(obj: Record<string, any>, prefix = ''): Set<string> {
  const keys = new Set<string>();
  
  for (const [key, value] of Object.entries(obj)) {
    const fullKey = prefix ? `${prefix}.${key}` : key;
    
    if (typeof value === 'object' && value !== null && !Array.isArray(value)) {
      // 递归处理嵌套对象
      const nestedKeys = flattenKeys(value, fullKey);
      nestedKeys.forEach(k => keys.add(k));
    } else {
      // 叶子节点
      keys.add(fullKey);
    }
  }
  
  return keys;
}

/**
 * 检查 key 完整性
 */
function checkKeys(usedKeys: Set<string>, localeKeys: Record<Locale, Set<string>>): CheckResult {
  const result: CheckResult = {
    usedKeys,
    localeKeys,
    missingKeys: {
      'zh-CN': [],
      'zh-TW': [],
      'en': [],
    },
    inconsistentKeys: [],
  };
  
  // 检查每个语言文件中缺失的 key
  for (const locale of LOCALES) {
    for (const key of usedKeys) {
      if (!localeKeys[locale].has(key)) {
        result.missingKeys[locale].push(key);
      }
    }
  }
  
  // 检查不一致的 key（某些语言有，某些没有）
  const allLocaleKeys = new Set<string>();
  LOCALES.forEach(locale => {
    localeKeys[locale].forEach(key => allLocaleKeys.add(key));
  });
  
  for (const key of allLocaleKeys) {
    const existsIn: Locale[] = [];
    const missingIn: Locale[] = [];
    
    for (const locale of LOCALES) {
      if (localeKeys[locale].has(key)) {
        existsIn.push(locale);
      } else {
        missingIn.push(locale);
      }
    }
    
    // 如果某些语言有，某些没有，则不一致
    if (existsIn.length > 0 && existsIn.length < LOCALES.length) {
      result.inconsistentKeys.push({
        key,
        existsIn,
        missingIn,
      });
    }
  }
  
  return result;
}

/**
 * 打印检查报告
 */
function printReport(result: CheckResult): void {
  log('\n=== 多语言 Key 检查报告 ===\n', 'cyan');
  
  // 统计信息
  log('📊 统计信息:', 'blue');
  log(`  - 代码中使用的 key 数量: ${result.usedKeys.size}`);
  log(`  - zh-CN 语言文件 key 数量: ${result.localeKeys['zh-CN'].size}`);
  log(`  - zh-TW 语言文件 key 数量: ${result.localeKeys['zh-TW'].size}`);
  log(`  - en 语言文件 key 数量: ${result.localeKeys['en'].size}`);
  log('');
  
  // 缺失的 key
  let hasMissing = false;
  for (const locale of LOCALES) {
    if (result.missingKeys[locale].length > 0) {
      hasMissing = true;
      log(`❌ 缺失的 Key (代码中使用但 ${locale} 语言文件中不存在):`, 'red');
      result.missingKeys[locale].forEach(key => {
        log(`    - ${key}`, 'red');
      });
      log('');
    }
  }
  
  // 不一致的 key
  if (result.inconsistentKeys.length > 0) {
    log('⚠️  不一致的 Key (某些语言文件有，某些没有):', 'yellow');
    result.inconsistentKeys.forEach(({ key, existsIn, missingIn }) => {
      log(`    - ${key}`, 'yellow');
      log(`      存在于: ${existsIn.join(', ')}`, 'yellow');
      log(`      缺失于: ${missingIn.join(', ')}`, 'yellow');
    });
    log('');
  }
  
  // 总结
  const hasErrors = hasMissing || result.inconsistentKeys.length > 0;
  
  if (hasErrors) {
    log('❌ 检查失败：发现缺失或不一致的 key', 'red');
  } else {
    log('✅ 检查通过：所有 key 都完整且一致', 'green');
  }
}

/**
 * 主函数
 */
function main(): void {
  // 脚本位于 .cursor/skills/frontend/check-i18n-keys/scripts/，向上到项目根
  const scriptDir = __dirname;
  const skillDir = path.resolve(scriptDir, '..');
  const frontendSkillDir = path.resolve(skillDir, '..');
  const repoRoot = path.resolve(frontendSkillDir, '../../..');
  const frontendDir = path.join(repoRoot, 'frontend');
  const localesDir = path.join(frontendDir, 'src', 'locales');
  
  // 检查目录是否存在
  if (!fs.existsSync(frontendDir)) {
    log(`❌ 前端目录不存在: ${frontendDir}`, 'red');
    process.exit(1);
  }
  
  if (!fs.existsSync(localesDir)) {
    log(`❌ 语言文件目录不存在: ${localesDir}`, 'red');
    process.exit(1);
  }
  
  log('🔍 开始检查多语言 key...\n', 'cyan');
  
  // 1. 提取代码中使用的 key
  log('📝 扫描代码文件...', 'blue');
  const usedKeys = extractKeysFromCode(frontendDir);
  log(`   找到 ${usedKeys.size} 个使用的 key\n`, 'green');
  
  // 2. 加载语言文件
  log('📚 加载语言文件...', 'blue');
  const localeKeys: Record<Locale, Set<string>> = {
    'zh-CN': new Set(),
    'zh-TW': new Set(),
    'en': new Set(),
  };
  
  for (const locale of LOCALES) {
    const localeObj = loadLocaleFile(localesDir, locale);
    localeKeys[locale] = flattenKeys(localeObj);
    log(`   ${locale}: ${localeKeys[locale].size} 个 key`, 'green');
  }
  log('');
  
  // 3. 检查
  log('🔎 检查 key 完整性...', 'blue');
  const result = checkKeys(usedKeys, localeKeys);
  
  // 4. 输出报告
  printReport(result);
  
  // 5. 退出码
  const hasErrors = 
    result.missingKeys['zh-CN'].length > 0 ||
    result.missingKeys['zh-TW'].length > 0 ||
    result.missingKeys['en'].length > 0 ||
    result.inconsistentKeys.length > 0;
  
  process.exit(hasErrors ? 1 : 0);
}

// 运行
main();

