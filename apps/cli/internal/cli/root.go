// Package cli —— cobra 命令层（G1/G2）。
package cli

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"strconv"
	"strings"

	"github.com/spf13/cobra"

	"spechub/cli/internal/client"
	"spechub/cli/internal/config"
)

// execCommandContext —— 便于测试替换系统命令的最小包装。
func execCommandContext(ctx context.Context, name string, args ...string) *exec.Cmd {
	return exec.CommandContext(ctx, name, args...)
}

func newClient() (*client.Client, error) {
	cfg, err := config.Load()
	if err != nil {
		return nil, err
	}
	if cfg.BaseURL == "" {
		return nil, errors.New("未配置 server 基址：请先 `spechub login`（或设 SPECHUB_BASE_URL）")
	}
	return client.New(strings.TrimRight(cfg.BaseURL, "/"), cfg.Token), nil
}

// resolveRepo —— 参数按 id 或 url 前缀解析到 Repo。
func resolveRepo(c *client.Client, arg string) (*client.Repo, error) {
	repos, err := c.ListRepos()
	if err != nil {
		return nil, err
	}
	if id, err := strconv.ParseInt(arg, 10, 64); err == nil {
		for i := range repos {
			if repos[i].ID == id {
				return &repos[i], nil
			}
		}
		return nil, fmt.Errorf("repo id %d 不存在", id)
	}
	for i := range repos {
		if strings.Contains(repos[i].URL, arg) {
			return &repos[i], nil
		}
	}
	return nil, fmt.Errorf("无匹配 repo：%q（现有：%s）", arg, joinRepos(repos))
}

func joinRepos(repos []client.Repo) string {
	names := make([]string, 0, len(repos))
	for _, r := range repos {
		names = append(names, fmt.Sprintf("#%d %s", r.ID, r.URL))
	}
	return strings.Join(names, ", ")
}

func Execute() error {
	root := &cobra.Command{
		Use:           "spechub",
		Short:         "SpecHub（契书）—— 与 server 同步的契约工具",
		SilenceUsage:  true,
		SilenceErrors: true,
	}
	root.AddCommand(
		loginCmd(), statusCmd(), pullCmd(), pushCmd(), diffCmd(), commitCmd(),
	)
	return root.Execute()
}

// ── login —— 配置 server 地址与 token ────────────────────────────────────

func loginCmd() *cobra.Command {
	var baseURL, token string
	cmd := &cobra.Command{
		Use:   "login",
		Short: "配置 server 基址与令牌（写入 ~/.spechub.json；环境变量优先）",
		RunE: func(cmd *cobra.Command, args []string) error {
			cfg, err := config.Load()
			if err != nil {
				return err
			}
			if baseURL != "" {
				cfg.BaseURL = baseURL
			}
			if token != "" {
				cfg.Token = token
			}
			if cfg.BaseURL == "" {
				return errors.New("需要 --url（或先有已存配置）")
			}
			if err := config.Save(cfg); err != nil {
				return err
			}
			fmt.Fprintf(cmd.OutOrStdout(), "已保存配置：base_url=%s token=%s\n", cfg.BaseURL, maskToken(cfg.Token))
			return nil
		},
	}
	cmd.Flags().StringVar(&baseURL, "url", "", "server 基址（如 http://localhost:8080）")
	cmd.Flags().StringVar(&token, "token", "", "访问令牌（可选）")
	return cmd
}

func maskToken(t string) string {
	if t == "" {
		return "(未设置)"
	}
	if len(t) <= 4 {
		return "****"
	}
	return t[:2] + "…" + t[len(t)-2:]
}

// ── status / repos —— 同步状态（G1）─────────────────────────────────────

func statusCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "status",
		Short: "查看真源仓（repo）索引同步状态",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := newClient()
			if err != nil {
				return err
			}
			repos, err := c.ListRepos()
			if err != nil {
				return err
			}
			w := cmd.OutOrStdout()
			if len(repos) == 0 {
				fmt.Fprintln(w, "（无注册仓）")
				return nil
			}
			fmt.Fprintf(w, "%-6s %-40s %-8s %s\n", "ID", "URL", "sync", "错误")
			for _, r := range repos {
				errMsg := "-"
				if r.LastError != "" {
					errMsg = r.LastError
				}
				fmt.Fprintf(w, "#%-5d %-40s %-8s %s\n", r.ID, truncate(r.URL, 38), r.SyncStatus, errMsg)
			}
			return nil
		},
	}
	return cmd
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n-3] + "..."
}

// ── pull —— 把 server 投影的 spec 拉到本地 ──────────────────────────────

func pullCmd() *cobra.Command {
	var path, out string
	cmd := &cobra.Command{
		Use:   "pull <repo> [--path specs/xxx.yaml] [--out DIR]",
		Short: "把某仓的 spec 文件拉取到本地（当前目录或 --out）",
		RunE: func(cmd *cobra.Command, args []string) error {
			if len(args) < 1 {
				return errors.New("需要 repo 参数（id 或 url 前缀）")
			}
			c, err := newClient()
			if err != nil {
				return err
			}
			repo, err := resolveRepo(c, args[0])
			if err != nil {
				return err
			}
			if path == "" {
				return errors.New("需要 --path（仓内 spec 路径，如 specs/pet/openapi.yaml）")
			}
			content, err := c.ReadSpec(repo.ID, path)
			if err != nil {
				return err
			}
			dest := path
			if out != "" {
				if err := os.MkdirAll(out, 0o755); err != nil {
					return err
				}
				base := path[strings.LastIndex(path, "/")+1:]
				dest = out + "/" + base
			}
			if err := os.WriteFile(dest, []byte(content), 0o644); err != nil {
				return err
			}
			fmt.Fprintf(cmd.OutOrStdout(), "已拉取 repo#%d %s → %s（%d 字节）\n", repo.ID, path, dest, len(content))
			return nil
		},
	}
	cmd.Flags().StringVar(&path, "path", "", "仓内 spec 路径")
	cmd.Flags().StringVar(&out, "out", "", "输出目录（默认为当前目录，文件名用 path 基名）")
	return cmd
}

// ── push/commit —— 本地 spec 上传写回真源（平台 commit）─────────────────

type writeArgs struct {
	path string
}

func writeFlags(cmd *cobra.Command, w *writeArgs) {
	cmd.Flags().StringVar(&w.path, "path", "", "仓内 spec 路径（如 specs/pet/openapi.yaml）")
}

func runWrite(cmd *cobra.Command, repoArg, file, path string) error {
	c, err := newClient()
	if err != nil {
		return err
	}
	repo, err := resolveRepo(c, repoArg)
	if err != nil {
		return err
	}
	if path == "" {
		return errors.New("需要 --path（仓内 spec 路径）")
	}
	if !(strings.HasSuffix(path, ".yaml") || strings.HasSuffix(path, ".yml") || strings.HasSuffix(path, ".json")) {
		return errors.New("--path 须为 .yaml/.yml/.json")
	}
	content, err := os.ReadFile(file)
	if err != nil {
		return err
	}
	if len(strings.TrimSpace(string(content))) == 0 {
		return errors.New("本地文件为空")
	}
	res, err := c.WriteSpec(repo.ID, path, string(content))
	if err != nil {
		return err
	}
	fmt.Fprintf(cmd.OutOrStdout(), "已写回 repo#%d %s\n  commit %s（%s）→ 摄取 api=%d\n", res.RepoID, res.Path, res.Commit, res.Branch, res.APICount)
	return nil
}

func pushCmd() *cobra.Command {
	w := &writeArgs{}
	cmd := &cobra.Command{
		Use:   "push <repo> <file> --path X",
		Short: "上传本地 spec 文件写回真源（后端平台 commit + push）",
		RunE: func(cmd *cobra.Command, args []string) error {
			if len(args) < 2 {
				return errors.New("需要 <repo> 与 <file> 参数")
			}
			return runWrite(cmd, args[0], args[1], w.path)
		},
	}
	writeFlags(cmd, w)
	return cmd
}

func commitCmd() *cobra.Command {
	w := &writeArgs{}
	cmd := &cobra.Command{
		Use:   "commit <repo> <file> --path X",
		Short: "把本地 spec 提交回真源（等价 push：后端提交；git 提交语义在真源侧）",
		RunE: func(cmd *cobra.Command, args []string) error {
			if len(args) < 2 {
				return errors.New("需要 <repo> 与 <file> 参数")
			}
			return runWrite(cmd, args[0], args[1], w.path)
		},
	}
	writeFlags(cmd, w)
	return cmd
}

// ── diff —— 本地文件与 server 投影 diff（G1，喂 ChangeLog）─────────────

func diffCmd() *cobra.Command {
	var path string
	cmd := &cobra.Command{
		Use:   "diff <repo> <local-file> --path X",
		Short: "对比本地 spec 文件与 server 侧内容（unified diff，依赖系统 diff）",
		RunE: func(cmd *cobra.Command, args []string) error {
			if len(args) < 2 {
				return errors.New("需要 <repo> 与 <local-file>")
			}
			if path == "" {
				return errors.New("需要 --path（仓内 spec 路径）")
			}
			c, err := newClient()
			if err != nil {
				return err
			}
			repo, err := resolveRepo(c, args[0])
			if err != nil {
				return err
			}
			remote, err := c.ReadSpec(repo.ID, path)
			if err != nil {
				return err
			}
			local, err := os.ReadFile(args[1])
			if err != nil {
				return err
			}
			return printDiff(cmd.OutOrStdout(), string(local), remote, args[1], "server:"+path)
		},
	}
	cmd.Flags().StringVar(&path, "path", "", "仓内 spec 路径")
	return cmd
}

// printDiff —— 优先调系统 `diff -u`，离线时降级打印两段。
func printDiff(w io.Writer, local, remote, localName, remoteName string) error {
	tmp, err := os.CreateTemp("", "spechub-diff-*")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	if _, err := tmp.WriteString(local); err != nil {
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	ctx := context.Background()
	diff := execCommandContext(ctx, "diff", "-u", "--label", localName, "--label", remoteName, tmp.Name(), "-")
	if diff != nil {
		diff.Stdin = strings.NewReader(remote)
		out, err := diff.Output()
		if err == nil {
			_, _ = w.Write(out)
			return nil
		}
	}
	// 降级：直接并排两份
	fmt.Fprintf(w, "── %s（本地）──\n%s\n── %s（server）──\n%s\n", localName, local, remoteName, remote)
	return nil
}