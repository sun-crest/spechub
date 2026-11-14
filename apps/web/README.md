# @spechub/web

H 组 Web（由 `apps/web-spike` 于 2026-11-14 转正）。Vite 6 + React 19 + TypeScript + Tailwind v4 + [shadcn/ui](https://ui.shadcn.com)（Base UI 引擎）。

## 状态（开发计划 H 组）

- **H1** 三区壳 + 侧栏分段控件（接口 / AI 文档）✅
- **H2** 项目 / 仓库 / 接口列表（读）✅ —— 接后端 `GET /api/projects|repos|apis`；tag / repo 过滤 + 搜索；侧栏 sync 状态
- **H3** 接口详情三段式 ✅ —— 契约从 `specJson` 前端解析渲染（Parameters 表 + Responses + schema 递归）；溯源区（specHash 待后端扩展）
- **H4** 编辑写回（`PUT /api/repos/{id}/spec`）— 占位未实现
- **H5** Monaco 双栏编辑器 — 未做
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
- 溯源区的 `specHash` / 最近 commit 需后端小扩展（docs/SpecHub-UI方案.md §6.2）。
- AI 文档域目前为内置样例（`src/demo/data.ts`），P2 K 摄取落地后切换后端。