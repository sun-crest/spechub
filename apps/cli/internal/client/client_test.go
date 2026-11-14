package client

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func testServer(t *testing.T, handler http.HandlerFunc) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(handler)
	t.Cleanup(srv.Close)
	return srv
}

func TestListRepos_SendsTokenAndParses(t *testing.T) {
	srv := testServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/repos" {
			t.Errorf("unexpected path: %s", r.URL.Path)
		}
		if got := r.Header.Get("Authorization"); got != "Bearer tok" {
			t.Errorf("Authorization = %q, want Bearer tok", got)
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`[{"id":7,"url":"http://x/repo.git","branch":"main","syncStatus":"ok"}]`))
	})
	c := New(srv.URL, "tok")
	repos, err := c.ListRepos()
	if err != nil {
		t.Fatal(err)
	}
	if len(repos) != 1 || repos[0].ID != 7 || repos[0].SyncStatus != "ok" {
		t.Fatalf("unexpected repos: %+v", repos)
	}
}

func TestWriteSpec_PutBodyAndResult(t *testing.T) {
	srv := testServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPut {
			t.Errorf("method = %s, want PUT", r.Method)
		}
		body := make([]byte, r.ContentLength)
		r.Body.Read(body)
		if !strings.Contains(string(body), `"specs/a.yaml"`) {
			t.Errorf("body lacks path: %s", body)
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"repoId":3,"path":"specs/a.yaml","commit":"abc123","branch":"main","apiCount":4}`))
	})
	c := New(srv.URL, "")
	res, err := c.WriteSpec(3, "specs/a.yaml", "openapi: 3.1.0")
	if err != nil {
		t.Fatal(err)
	}
	if res.Commit != "abc123" || res.APICount != 4 {
		t.Fatalf("unexpected write result: %+v", res)
	}
}

func TestErrorSurfaces(t *testing.T) {
	srv := testServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		w.Write([]byte(`{"error":"missing"}`))
	})
	c := New(srv.URL, "")
	if _, err := c.WriteSpec(1, "specs/x.yaml", "x"); err == nil || !strings.Contains(err.Error(), "404") {
		t.Fatalf("want 404 error, got: %v", err)
	}
}