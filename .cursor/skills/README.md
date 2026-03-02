# Cursor Skills 目录结构

本目录遵循 [Cursor Agent Skills 规范](https://cursor.com/cn/docs/context/skills)：每个技能为一个**文件夹**，内含 `SKILL.md` 及可选的 `scripts/`、`references/`、`assets/`。

## 目录结构

```
.cursor/skills/
├── frontend/                    # 前端相关 skill 分组
│   └── check-i18n-keys/         # 单个 skill（文件夹名 = name）
│       ├── SKILL.md             # 必填，技能定义与指令
│       └── scripts/
│           ├── check-i18n-keys.ts
│           └── package.json
├── backend/                     # 后端相关 skill
└── common/                      # 通用 skill
    └── create-release/          # 创建 GitHub Release
        ├── SKILL.md
        └── scripts/
            └── create-release.sh
```

- **SKILL.md**：YAML frontmatter（`name`、`description` 必填，`name` 须与父文件夹名一致、小写连字符）+ 给 Agent 的详细指令。
- **scripts/**：Agent 可执行的脚本，在 SKILL.md 中用相对路径引用。

## 添加新 Skill

1. **确定分组**：在 `frontend/`、`backend/` 或 `common/` 下新建**以技能名命名的文件夹**（仅小写、数字、连字符，如 `check-i18n-keys`）。
2. **创建 SKILL.md**：在该文件夹内创建 `SKILL.md`（大写），frontmatter 中 `name` 必须与文件夹名一致。
3. **可选 scripts/**：在技能文件夹内建 `scripts/`，放入可执行脚本；在 SKILL.md 正文中写明运行命令（如 `cd .cursor/skills/.../scripts && npm run xxx`）。

## 示例

- `frontend/check-i18n-keys/SKILL.md` + `frontend/check-i18n-keys/scripts/` — 检查前端多语言 key
- `common/create-release/SKILL.md` + `common/create-release/scripts/` — 创建 GitHub Release

## 运行 check-i18n-keys

```bash
cd .cursor/skills/frontend/check-i18n-keys/scripts
npm install
npm run check-i18n
```

## 运行 create-release

```bash
cd .cursor/skills/common/create-release/scripts
./create-release.sh -t v1.0.0 -T "Release v1.0.0" -d "发布说明"
```

参数说明：
- `-t` 版本号（必需，格式 v1.0.0）
- `-T` Release 标题
- `-d` Release 描述
- `-p` 标记为 Pre-release（自动加 -beta 后缀）
- `-y` 无交互模式

