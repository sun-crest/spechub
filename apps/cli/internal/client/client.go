// Package client —— 与 server REST 的薄客户端（对应 backend /api 端点，spec-client 定位）。
package client

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
)

type Client struct {
	BaseURL string
	Token   string
	HTTP    *http.Client
}

func New(baseURL, token string) *Client {
	return &Client{BaseURL: baseURL, Token: token, HTTP: &http.Client{}}
}

type Project struct {
	ID   int64  `json:"id"`
	Name string `json:"name"`
}

type Repo struct {
	ID         int64  `json:"id"`
	URL        string `json:"url"`
	Branch     string `json:"branch"`
	SyncStatus string `json:"syncStatus"`
	LastError  string `json:"lastError,omitempty"`
}

type Api struct {
	ID          int64  `json:"id"`
	RepoID      int64  `json:"repoId"`
	Method      string `json:"method"`
	Path        string `json:"path"`
	OperationID string `json:"operationId,omitempty"`
	Tag         string `json:"tag,omitempty"`
	Summary     string `json:"summary,omitempty"`
	SpecJSON    string `json:"specJson"`
}

type WriteResult struct {
	RepoID   int64  `json:"repoId"`
	Path     string `json:"path"`
	Commit   string `json:"commit"`
	Branch   string `json:"branch"`
	APICount int    `json:"apiCount"`
}

func (c *Client) do(method, path string, body any, out any) (int, error) {
	var reader io.Reader
	if body != nil {
		raw, err := json.Marshal(body)
		if err != nil {
			return 0, err
		}
		reader = bytes.NewReader(raw)
	}
	req, err := http.NewRequest(method, c.BaseURL+path, reader)
	if err != nil {
		return 0, err
	}
	req.Header.Set("Content-Type", "application/json")
	if c.Token != "" {
		req.Header.Set("Authorization", "Bearer "+c.Token)
	}
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 400))
		return resp.StatusCode, fmt.Errorf("%s %s → HTTP %d: %s", method, path, resp.StatusCode, string(b))
	}
	if out != nil {
		if err := json.NewDecoder(resp.Body).Decode(out); err != nil {
			return resp.StatusCode, err
		}
	}
	return resp.StatusCode, nil
}

func (c *Client) ListRepos() ([]Repo, error) {
	var r []Repo
	_, err := c.do(http.MethodGet, "/api/repos", nil, &r)
	return r, err
}

func (c *Client) ListProjects() ([]Project, error) {
	var p []Project
	_, err := c.do(http.MethodGet, "/api/projects", nil, &p)
	return p, err
}

func (c *Client) ListSpecs(repoID int64) ([]string, error) {
	var s []string
	_, err := c.do(http.MethodGet, fmt.Sprintf("/api/repos/%d/specs", repoID), nil, &s)
	return s, err
}

func (c *Client) ReadSpec(repoID int64, path string) (string, error) {
	req, err := http.NewRequest(http.MethodGet, fmt.Sprintf("%s/api/repos/%d/spec?path=%s", c.BaseURL, repoID, path), nil)
	if err != nil {
		return "", err
	}
	if c.Token != "" {
		req.Header.Set("Authorization", "Bearer "+c.Token)
	}
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 400))
		return "", fmt.Errorf("read %s → HTTP %d: %s", path, resp.StatusCode, string(b))
	}
	raw, err := io.ReadAll(resp.Body)
	return string(raw), err
}

func (c *Client) WriteSpec(repoID int64, path, content string) (WriteResult, error) {
	var w WriteResult
	_, err := c.do(http.MethodPut, fmt.Sprintf("/api/repos/%d/spec", repoID), map[string]string{
		"path": path, "content": content,
	}, &w)
	return w, err
}

func (c *Client) SearchApis(q, method string) ([]Api, error) {
	var a []Api
	_, err := c.do(http.MethodGet, "/api/apis?q="+q, nil, &a)
	return a, err
}