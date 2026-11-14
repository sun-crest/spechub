# @spechub/web-spike

H 组 Web 真码试桩（2026-11-14 收编）。Vite 6 + React 19 + TypeScript + Tailwind v4 + [shadcn/ui](https://ui.shadcn.com)（Base UI 引擎）。

## 作用

- 验证 [design/spechub-design-tokens.md](../../design/spechub-design-tokens.md) §8 的 shadcn（OKLCH）落地映射与 `--color-method-*` 方法色
- 用真实 shadcn 组件拼出「接口列表 / 详情契约 / AI 文档阅读」三个 pane，评审组件质感与主题
- 可作为 **H1 脚手架起点**（H 组正式实现基于它扩展，或仅作参考）

## 运行

```bash
npm install
npm run dev      # http://localhost:5173
npm run build    # tsc -b + vite build
```

## 结构

```
src/
  App.tsx                三区壳：顶栏 / 侧栏（域分段 + tag/文档树）/ 主区
  index.css              tokens §8 映射（OKLCH 变量 + @theme 方法色）——H1 定稿换转换器精确值
  components/ui/         shadcn 生成组件
  demo/                  data.ts（16 接口 + 4 文档样例）+ 列表 / 详情 / 文档面板
```

## 说明

- DOCS 与接口数据为示例，仅评审用，不随 H 组进入产线。
- `index.css` 的 oklch 为近似值（hex→oklch 随手转换）；H1 定稿按 tokens §8 用转换器生成并复核对比。