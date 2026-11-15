# @spechub/web

H 组 Web（由 `apps/web-spike` 于 2026-11-14 转正）。Vite 6 + React 19 + TypeScript + Tailwind v4 + [shadcn/ui](https://ui.shadcn.com)（Base UI 引擎）。

## 状态（开发计划 H 组）

- **H1** 三区壳 + 侧栏分段控件（接口 / AI 文档）✅
- **H2** 项目 / 仓库 / 接口列表（读）✅ —— 接后端 `GET /api/projects|repos|apis`；tag / repo 过滤 + 搜索；侧栏 sync 状态
- **H3** 接口详情三段式 ✅ —— 契约从 `specJson` 前端解析渲染（Parameters 表 + Responses + schema 递归）；Doc 域**真 Markdown 渲染**（react-markdown + remark-gfm，标题索引从 md 提取）
- **H4** 编辑即提交 ✅ —— 双栏编辑器保存 = 强制 diff 确认（Monaco DiffEditor + commit message）→ `PUT /api/repos/{id}/spec` 写回真源（platform commit → 自动摄取）；e2e 实证
- **H5** Monaco 双栏编辑器 ✅ —— 左 YAML/JSON 编辑（本地打包 monaco，dev/构建均验证）｜右实时渲染预览（`yaml` 解析 → 操作列表 + 复用契约渲染引擎）
- **H6** 登录 / admin 初始化 — 未做（backend 未配 `admin-token` 时免鉴权可开发）

## 运行

```bash
npm install
npm run dev      # http://localhost:5173
npm run build
```

前提：`docker compose up -d` 先起 backend（:8080）+ gitea。dev 时 Vite 把 `/api` 代理到 `http://localhost:8080`（可用 `VITE_API_BASE` 覆盖）。

## 对接说明

- `src/lib/api.ts`：REST 客户端，类型对齐 `modules/kernel/spec-index .../Records.kt`。
- `specJson` = 归一化后**单个 Operation** 的 JSON；详情页 `JSON.parse` 后直接渲染 `parameters` / `responses`。
- 编辑器读文件走 `GET /repos/{id}/specs|spec`（SpecWriter 工作树），写回走 `PUT /repos/{id}/spec`。
- 详情页「编辑此文件」用 `GET /apis/{id}` 返回的 `specPath`（source spec 文件路径，后端 join version 带出）直达编辑器。
- 溯源区的 `specHash` / 最近 commit 需后端小扩展（docs/SpecHub-UI方案.md §6.2）。
- AI 文档域目前为内置样例（`src/demo/data.ts`，markdown 文本），P2 K 摄取落地后切换后端。
- Monaco 本地打包（`src/lib/monaco.ts`）：`loader.config` 指向本地 esm、`?worker` 独立 chunk，离线/内网可用；构建时 monaco 单独拆 chunk 不阻塞首屏。