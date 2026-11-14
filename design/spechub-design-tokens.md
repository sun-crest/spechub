# SpecHub 设计 Token 草案（现代清爽 · antd v5 工程界面视觉基准 V2）

> **用途**：H 组 Web（React + shadcn/Radix + Tailwind v4）主题的输入
> **来源**：① 从 2026-11-14 实测打包产物提取的色值/字形/圆角（antd v5 工程界面；实证产物已清理）；② ui-ux-pro-max 技能校验（Minimalism/Swiss 风、IBMPlexSans+JetBrainsMono 配对、shadcn theming 规则）
> **实证依据**：字号/间距/几何参数为 2026-11-14 对 antd v5 工程界面打包产物逐项提取后固化（实证产物已清理；结论以本文档 §2–§6 为准，勿再追溯基线文件）
> **结论口径**：「antd v5 工程界面 + 现代清爽路线（shadcn 语系）」→ 主操作近黑、品牌蓝承接链接/选中、微圆角、极轻海拔；语义上已做 SpecHub 化修正（§7）
> **日期**：2026-11-14 | 状态：草案 v0.2（现代清爽 V2，评审用，不随 H 组直接进入代码）

---

## 1. 一句话设计语言

**现代清爽工程风**：主行动**近黑 `#1b1b1f`**（shadcn 现代路线），品牌蓝 `#1677ff` 承接链接/路径/页签/选中，swagger 五色方法色（pill）承担唯一大彩色，微圆角 `6/10/12`、中性暖底 + 白卡片 + 极轻海拔、等宽精确、150ms 微过渡，**密度对齐 antd v5 实证基准（14px 正文 / 32 控件 / 64 顶栏）**。亮暗双主题，默认跟随系统。

## 2. 亮色 Tokens（现代清爽 V2）

| 语义 | 值 | 来源/取法 |
|---|---|---|
| `primary`（主行动） | `#1b1b1f`（hover `#2b2b30`） | 近黑，shadcn 现代路线（V2 拍板） |
| `link`（品牌蓝） | `#1677ff` | 链接/路径/页签/选中文字（主行动改近黑后退居次位） |
| `background`（页面底） | `#f7f7f8` | 暖中性浅底 |
| `surface`（内容面/卡片/顶栏） | `#ffffff` | — |
| `surface-subtle`（柔和填充/斑马） | `#f4f4f5` | 搜索框、分段容器、hover 基色 |
| `surface-hover`（行悬浮） | `#f0f0f2` | 行 hover 层级 |
| `surface-elevated`（浮层） | `#ffffff` | 浮层 + `shadow-lifted` |
| `border` / `border-weak` | `#e5e6e9` / `#f1f1f3` | 细控件边 / 分割线 |
| `foreground` / `muted` | `#1c1c20` / `#71717a` | 正文 / 次级（白底 muted ≈4.6:1） |
| `sidebar`（侧栏底） | 亮 `#ffffff`（=surface，与顶栏一体）· 暗 `#17171b` | V2：侧栏与表面同底 |
| `sidebar-fg` / `sidebar-fg-muted` | `#3f3f46` / `#8b8b94` | 侧栏正文 / 分组状态文字 |
| `sidebar-hover` / `sidebar-active-bg` / `sidebar-active-fg` | `#f4f4f5` / `#eef4ff` / `#1677ff` | 浅灰悬浮 · 选中浅蓝底 + 蓝字（现代轻态，弃纯蓝块） |
| `success` / `warning` / `danger` | `#16a34a` / `#d97706` / `#dc2626` | 现代绿/琥珀/红 |
| `overlay` / `ring` | `rgba(16,16,20,.45)` / `#1b1b1f` | 蒙层 / focus 环 |

## 3. 方法色（全局唯一彩色来源，swagger 标准，实证全在）

| 方法 | 色值 |
|---|---|
| GET | `#61affe` |
| POST | `#49cc90` |
| PUT | `#fca130` |
| DELETE | `#f93e3e` |
| PATCH | `#50e3c2` |
| HEAD | `#9012fe` |
| OPTIONS | `#0d5aa7` |

## 4. 暗色 Tokens（shadcn 中性灰体系 + 对比校验落地）

`background #0e0e11` · `surface #17171b` · `surface-subtle #1f1f23` · `surface-hover #24242a` · `border #28282e` · `border-weak #1f1f24` · `foreground rgba(255,255,255,.92)` · `muted #a1a1aa`

暗色专属调整（逐项达 4.5:1，原型已验证）：
- **`--primary` 反色**：`#f4f4f5`（hover `#e4e4e7`）—— 暗色主按钮为浅色（现代反色路线）
- `--link`：`#7cb8ff`（主区链接/路径/页签提亮）
- `--danger #f87171` · `--success #34d399` · `--warning #fbbf24`
- `--surface-elevated #202025` · `--overlay rgba(0,0,0,.6)` · `--ring #f4f4f5`（focus 环）
- **侧栏跟随明暗**：亮 `#ffffff` / 暗 `#17171b`；hover rgba(255,255,255,.06)，active-bg rgba(59,130,246,.16)，active-fg `#7cb8ff`
- 方法色沿用亮色值：方法标签色块在暗底对比更好（POST 绿 `#49cc90` + 白字 ~2.1:1 是 swagger 原厂表现，不修正）
- `--shadow-lifted` 双层极轻海拔仅限浮层使用，正文/卡片区仍零阴影

## 5. 字体与刻度

- **界面无衬线**：`"IBM Plex Sans", "PingFang SC", "Microsoft YaHei", "Noto Sans CJK SC", system-ui, sans-serif`（中文走系统栈）
- **等宽**（path / hash / specJson / 方法徽标数字）：`ui-monospace, SFMono-Regular, Menlo, Consolas, "JetBrains Mono", monospace`（实证栈）
- **字号**（对齐 antd v5 默认刻度）：`10` 元数据小字（实证）→ `12` 辅助/次级标签 → `13` 表格表头/代码 → `14` 正文默认与表格正文（antd fontSize 14）→ `16` 区块标题（Heading5）→ `20` 页面标题（Heading4）→ `24` 空态大标题（Heading3）
- 表格数字用 tabular/mono 防抖动

## 6. 几何

- **间距**：对齐真品——顶栏 `64`、控制件 `32`（LG 40 / SM 24）、操作 tab 行高 `44`、侧栏菜单行 `40`、主区内衬 `24×28`（清爽留白，V2 放宽）、卡片内衬 `16×18`；沿用 4px 基数
- **圆角**：`radius-sm 6`（控件/按钮）/ `radius-md 10`（卡片/容器）/ `radius-lg 12`（弹层）/ method·chips `999px` pill
- **边框**：1px，控件 `border`、分割与弱容器 `border-weak`
- **阴影**：正文区零阴影；浮层唯一极轻双层海拔 `shadow-lifted`（0 1px 2px + 0 6px 20px，暗色加深）
- **表格行高**：对齐真品默认 ≈52px（cell 16×16）；紧凑强迫表（参数/字段表）可收敛到 ~44px，不阻断

## 7. SpecHub 化修正（与参照界面原样的偏差）

1. **状态不说红绿灯**：`ok / error / syncing` 用圆点+文本（成功绿/错误红/琥珀），不用消费产品隐喻。
2. **主行动近黑、品牌蓝只承接链接/选中**：primary `#1b1b1f`（现代清爽路线），`#1677ff` 用于链接/路径/页签/选中文字；辨识交给方法色。
3. **danger ≠ DELETE 色**：操作危险（删除仓库/撤销）用 `danger #dc2626`（暗色 `#f87171`）；契约的方法色只标契约语义。
4. **微圆角 + 海拔分层 + 微过渡（150ms）**：圆角 6/10/12、浮层极轻阴影、正文零阴影、hover/选中平滑过渡；不做大动效/渐变点缀。
5. **对齐真品刻度**：正文默认 14px，辅助/次级 12px，元数据 10px（真品实证皆有）；正文不再压缩至 <12px。长 token（path/hash）`overflow-wrap:anywhere` 换行而非截断。
6. 亮暗对比**分别在双主题下测**，不以亮色刻度推断暗色。

## 8. shadcn 落地映射（v4，OKLCH 变量 + `@theme inline`）

| shadcn 变量 | 本 token（亮） | 暗 |
|---|---|---|
| `--background` | §2 background | §4 background |
| `--foreground` | §2 foreground | §4 foreground |
| `--card` / `--card-foreground` | surface / foreground | surface / foreground |
| `--primary` / `--ring` | `#1b1b1f`（近黑） | `#f4f4f5`（反色） |
| `--link`（自定义） | `#1677ff` | `#7cb8ff` |
| `--border` | `#e5e6e9` | `#28282e` |
| `--muted` / `--muted-foreground` | `#f4f4f5` / `#71717a` | `#1f1f23` / `#a1a1aa` |
| `--destructive` | `#dc2626` | `#f87171` |
| `--color-method-*`（自定义） | §3 七色 | 同 |

- shadcn v4 用 `oklch()`；上表 hex 是基准，**具体 oklch 值在 H1 脚手架时用转换器生成并复核对比**（勿手写近似值）。
- 方法色以 `--color-method-get`… 作自定义 token 进 `@theme`，`<Tag>` 直接消费。

## 9. 定稿前的开放项

- **主品牌色（2026-11-14 V2 已定）**：主行动改近黑 `#1b1b1f`；品牌蓝 `#1677ff` 仅作 link/交互/选中。若日后要"更有品牌色"，把 `--primary` 一处换回蓝即可，其余已解耦。
- **侧栏底色（V2 更新拍板）**：弃深蓝黑与浅灰，亮色侧栏 = `#ffffff`（与顶栏/卡片一体），选中浅蓝底 + 蓝字；暗色 `#17171b`。见 §2 `sidebar` 系列。
- **等宽字体包**：JetBrains Mono 是否随包（vs 系统栈）→ 进 H 组脚手架时按包体积取舍。