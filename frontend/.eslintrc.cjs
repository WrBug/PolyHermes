module.exports = {
  root: true,
  env: {
    browser: true,
    es2020: true,
  },
  parser: '@typescript-eslint/parser',
  parserOptions: {
    ecmaVersion: 'latest',
    sourceType: 'module',
  },
  plugins: ['@typescript-eslint', 'react-hooks', 'react-refresh'],
  extends: [
    'eslint:recommended',
    'plugin:react-hooks/recommended',
  ],
  ignorePatterns: ['dist', 'build', 'node_modules', '.eslintrc.cjs'],
  rules: {
    // TypeScript 编译阶段已经开启 strict/noUnused*，这里避免历史代码里
    // 大量 any、依赖数组 warning 让 lint 从“可运行检查”变成一次性大迁移。
    'react-hooks/exhaustive-deps': 'off',
    'react-refresh/only-export-components': 'off',
    'no-undef': 'off',
    'no-unused-vars': 'off',
    'no-useless-catch': 'off',
    'no-useless-escape': 'off',
    'no-redeclare': 'off',
  },
}
