/**
 * SpecHub MCP 配置（环境变量驱动，见 README）。
 * F3 权限：默认只读——写工具仅在 SPECHUB_ALLOW_WRITE=1 时注册。
 */
export const config = {
  /** backend 基址（remote MCP 走 HTTP 到 server） */
  baseUrl: process.env.SPECHUB_BASE_URL ?? "http://localhost:8080",
  /** backend admin token（配置后 server 侧令牌校验，见 TokenAuthFilter） */
  token: process.env.SPECHUB_TOKEN ?? "",
  /** 写操作开关：默认关（只读），=1 开启 create_api/update_api */
  allowWrite: process.env.SPECHUB_ALLOW_WRITE === "1" || process.env.SPECHUB_ALLOW_WRITE === "true",
  /** streamable HTTP 监听端口（--http 时） */
  httpPort: Number(process.env.SPECHUB_MCP_PORT ?? 8780),
};