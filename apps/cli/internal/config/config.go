// Package config —— 本地 CLI 配置（~/.spechub.json），token 优先环境变量 SPECHUB_TOKEN。
package config

import (
	"encoding/json"
	"os"
	"path/filepath"
)

type Config struct {
	BaseURL string `json:"base_url"`
	Token   string `json:"token,omitempty"`
}

func Path() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return ".spechub.json"
	}
	return filepath.Join(home, ".spechub.json")
}

func Load() (*Config, error) {
	var c Config
	raw, err := os.ReadFile(Path())
	switch {
	case err == nil:
		if uerr := json.Unmarshal(raw, &c); uerr != nil {
			return nil, uerr
		}
	case !os.IsNotExist(err):
		return nil, err
	}
	// 环境变量优先（文件缺失时同样生效）
	if v := os.Getenv("SPECHUB_BASE_URL"); v != "" {
		c.BaseURL = v
	}
	if v := os.Getenv("SPECHUB_TOKEN"); v != "" {
		c.Token = v
	}
	return &c, nil
}

func Save(c *Config) error {
	raw, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(Path(), raw, 0o600)
}