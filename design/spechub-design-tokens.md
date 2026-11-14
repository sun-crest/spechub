# SpecHub 设计 Token 草案（antd v5 工程界面视觉基准）

> **用途**：H 组 Web（React + shadcn/Radix + Tailwind v4）主题的输入
> **来源**：① 从 2026-11-14 实测打包产物提取的色值/字形/圆角（antd v5 工程界面；实证产物已清理）；② ui-ux-pro-max 技能校验（Minimalism/Swiss 风、IBMPlexSans+JetBrainsMono 配对、shadcn theming 规则）
> **实证依据**：字号/间距/几何参数为 2026-11-14 对 antd v5 工程界面打包产物逐项提取后固化（实证产物已清理；结论以本文档 §2–§6 为准，勿再追溯基线文件）
> **结论口径**：「整体风格参考 antd v5 工程界面，缺失功能自行补充调整」→ 本 token 即该口径的实现层落地；视觉同源工程性，语义上已做 SpecHub 化修正（§7）
> **日期**：2026-11-14 | 状态：草案 v0.1（评审用，不随 H 组直接进入代码）

---

## 1. 一句话设计语言

**antd 中性工程风**：`#1677ff` 做主行动/选中、swagger 五色方法色承担唯一彩色辨识、无阴影极简、等宽精确，**密度对齐 antd v5 实证基准（14px 正文 / 32 控件 / 6 圆角 / 64 顶栏）**。亮暗双主题，默认跟随系统。

## 2. 亮色 Tokens（antd v5 默认浅色视觉提取）

| 语义 | 值 | 来源/取法 |
|---|---|---|
| `primary` | `#1677ff`（hover `#0958d9`） | antd blue-6/7，实证（链接/主选色） |
| `background`（页面底） | `#fafafa`（操作 nav/内容底实证）；布局大底可用 `#f5f5f5`（antd colorBgLayout） | 实证 |
| `surface`（内容面/卡片） | `#ffffff` | —
| `surface-subtle`（表格斑马/悬浮） | `#f6f8fa` / `#f5f5f5` | 实证 |
| `border`（控件边框） | `#d9d9d9` | antd colorBorder |
| `border-weak`（分割线） | `#f0f0f0` | 实证 |
| `foreground`（正文） | `#141414`（rgba(0,0,0,.88)） | antd colorText |
| `muted`（次级文字） | `#8b949e` | 实证灰 |
| `sidebar`（侧栏底） | 亮 `#fafafa` / 暗 `#1f1f1f`，右分隔 `#f0f0f0`/`#2f2f2f` | 2026-11-14 比稿拍板：**弃 #001529 深蓝黑**，侧栏与主区同族浅灰 |
| `sidebar-fg` / `sidebar-fg-muted` | `#262626` / `#6b7280`（亮）· rgba(255,255,255,.85/.45)（暗） | 侧栏文字，muted 对 #fafafa ≈4.6:1 |
| `sidebar-hover` / `sidebar-active` | `#e6f4ff` / `#1677ff`（暗 hover rgba(22,119,255,.16)） | 悬浮浅蓝柔底 · 选中主色块 |
| `success` | `#52c41a` | antd green-6 实证 |
| `warning` | `#faad14` | antd gold-6 实证 |
| `danger` | `#f5222d` | antd red-6（**操作语义**，与方法色区分） |

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

## 4. 暗色 Tokens（antd dark 惯例 + 对比校验落地）

`background #141414` · `surface #1f1f1f` · `surface-subtle #262626` · `border #424242` · `foreground rgba(255,255,255,.85)` · `muted #8b949e`

暗色专属调整（逐项达 4.5:1，原型已验证）：
- `--link`：`#1677ff → #4e9cff`（主区链接/路径/页签；`#1677ff` 对暗底仅 ~3.9:1，提亮后 ~6.7:1）
- `--danger`：`#f5222d → #ff4d4f`（~4.1 → ~5.7:1；required 标记、危险操作文本）
- `--surface-elevated #262626`（弹层/浮层）· `--overlay rgba(0,0,0,.65)` · `--ring #1668dc`（focus 环）
- **侧栏跟随明暗（SpecHub 比稿拍板）**：亮 `#fafafa` / 暗 `#1f1f1f`；真品 siderBg 恒定 `#001529` 是 antd 组件常量（实证结论固化 §2），SpecHub 决策**不复用**该深蓝黑底，改浅灰融合主区
- primary/方法色沿用亮色值：方法标签色块在暗底对比更好（POST 绿 + 白字 ~2.1:1 是 swagger 原厂表现，对齐真品不修正）
- `--shadow-lifted` 极微阴影仅限浮层使用，正文/卡片区仍零阴影

## 5. 字体与刻度

- **界面无衬线**：`"IBM Plex Sans", "PingFang SC", "Microsoft YaHei", "Noto Sans CJK SC", system-ui, sans-serif`（中文走系统栈）
- **等宽**（path / hash / specJson / 方法徽标数字）：`ui-monospace, SFMono-Regular, Menlo, Consolas, "JetBrains Mono", monospace`（实证栈）
- **字号**（对齐 antd v5 默认刻度）：`10` 元数据小字（实证）→ `12` 辅助/次级标签 → `13` 表格表头/代码 → `14` 正文默认与表格正文（antd fontSize 14）→ `16` 区块标题（Heading5）→ `20` 页面标题（Heading4）→ `24` 空态大标题（Heading3）
- 表格数字用 tabular/mono 防抖动

## 6. 几何

- **间距**：对齐真品——顶栏 `64`、控制件 `32`（LG 40 / SM 24）、操作 tab 行高 `44`、侧栏菜单行 `40`、内容内衬 `16`、表格 cell `16×16`（紧凑可收 `12×12`）；沿用 4px 基数
- **圆角**：`radius-sm 4` / `radius-md 6`（antd 默认）/ `radius-lg 8` / `radius-full`（badge）
- **边框**：1px，弱分界线用 §2 `border-weak`
- **阴影**：无 / 极微（实测大量 `box-shadow:none` 取向，禁止卡片大阴影）
- **表格行高**：对齐真品默认 ≈52px（cell 16×16）；紧凑强迫表（参数/字段表）可收敛到 ~44px，不阻断

## 7. SpecHub 化修正（与参照界面原样的偏差）

1. **状态不说红绿灯**：`ok / error / syncing` 用圆点+文本（成功绿/错误红/琥珀），不用消费产品隐喻。
2. **primary 只做强调**：选中、主行动、链接；辨识交给方法色。
3. **danger ≠ DELETE 色**：操作危险（删除仓库/撤销）用 `danger #f5222d`；契约的方法色只标契约语义。
4. **无阴影、等宽精确**：工程可信观感，不做动效/渐变点缀。
5. **对齐真品刻度**：正文默认 14px，辅助/次级 12px，元数据 10px（真品实证皆有）；正文不再压缩至 <12px。长 token（path/hash）`overflow-wrap:anywhere` 换行而非截断。
6. 亮暗对比**分别在双主题下测**，不以亮色刻度推断暗色。

## 8. shadcn 落地映射（v4，OKLCH 变量 + `@theme inline`）

| shadcn 变量 | 本 token（亮） | 暗 |
|---|---|---|
| `--background` | §2 background | §4 background |
| `--foreground` | §2 foreground | §4 foreground |
| `--card` / `--card-foreground` | surface / foreground | surface / foreground |
| `--primary` / `--ring` | `#1677ff` | `#1677ff` |
| `--border` | `#d9d9d9` | `#424242` |
| `--muted` / `--muted-foreground` | `#f6f8fa` / `#8b949e` | 变体 |
| `--destructive` | `#f5222d` | 变体 |
| `--color-method-*`（自定义） | §3 七色 | 同 |

- shadcn v4 用 `oklch()`；上表 hex 是基准，**具体 oklch 值在 H1 脚手架时用转换器生成并复核对比**（勿手写近似值）。
- 方法色以 `--color-method-get`… 作自定义 token 进 `@theme`，`<Tag>` 直接消费。

## 9. 定稿前的开放项

- **主品牌色**：暂用原味 `#1677ff`；若觉得与别的平台撞脸可换同族深蓝（交互/环路仍是自己）。
- **侧栏底色（2026-11-14 已拍板）**：弃 #001529 深蓝黑，改浅灰 `#fafafa`（亮）/ `#1f1f1f`（暗），与主区同族，选中主色块 `#1677ff`。见 §2 `sidebar` 系列。
- **等宽字体包**：JetBrains Mono 是否随包（vs 系统栈）→ 进 H 组脚手架时按包体积取舍。