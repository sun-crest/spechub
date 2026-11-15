/** Monaco 本地加载配置（H5）：打包内置 monaco 而非走 CDN，离线/内网可用 */
import { loader } from "@monaco-editor/react";
// monaco-editor exports 映射 `./*` → `./esm/vs/*.js`，故 worker 按 `monaco-editor/editor/editor.worker` 导入
import * as monaco from "monaco-editor";
import editorWorker from "monaco-editor/editor/editor.worker?worker";

// 语法高亮用 Monaco 内置 monarch（YAML/JSON），无需额外 language worker；编辑辅助走 editor.worker
self.MonacoEnvironment = {
  getWorker() {
    return new editorWorker();
  },
};

// loader 指向本地 esm 产物（@monaco-editor/react 默认走 jsdelivr CDN）
loader.config({ monaco });

export { monaco };