/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 后端地址（Vite 代理目标）；生产构建由部署方配置 */
  readonly VITE_API_BASE?: string;
  /** 仅 dev：预填兼容令牌登录（不写入 localStorage，不进生产包） */
  readonly VITE_DEV_ADMIN_TOKEN?: string;
}
