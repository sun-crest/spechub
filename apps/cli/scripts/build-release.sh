#!/usr/bin/env bash
# G3 交叉编译发布：linux/darwin × amd64/arm64 → dist/ 单二进制
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p dist
for target in linux/amd64 linux/arm64 darwin/amd64 darwin/arm64; do
  os="${target%/*}"
  arch="${target#*/}"
  out="dist/spechub-${os}-${arch}"
  GOOS="$os" GOARCH="$arch" CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o "$out" .
  echo "✓ dist/spechub-${os}-${arch}"
done
echo "完成：dist/spechub-{linux,darwin}-{amd64,arm64}"