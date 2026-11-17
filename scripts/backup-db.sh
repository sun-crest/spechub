#!/usr/bin/env bash
# SpecHub 管理库备份（PostgreSQL / MySQL 自动识别）。
#
# 只备份管理库；投影库（SQLite）可随时从 git 重建，不纳入备份。
# 用法：scripts/backup-db.sh [输出目录]（默认 ./backups）
# 环境变量：SPECHUB_DB_CONTAINER（默认 spechub-db）、SPECHUB_DB_NAME / SPECHUB_DB_USER（默认 spechub）
set -euo pipefail

CONTAINER="${SPECHUB_DB_CONTAINER:-spechub-db}"
DB="${SPECHUB_DB_NAME:-spechub}"
USER_NAME="${SPECHUB_DB_USER:-spechub}"
OUT_DIR="${1:-backups}"
mkdir -p "$OUT_DIR"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"

if docker exec "$CONTAINER" sh -c 'command -v pg_dump >/dev/null 2>&1'; then
  PASS="$(docker exec "$CONTAINER" printenv POSTGRES_PASSWORD 2>/dev/null || true)"
  FILE="$OUT_DIR/management-postgres-$STAMP.dump"
  docker exec -e PGPASSWORD="$PASS" "$CONTAINER" pg_dump -U "$USER_NAME" -d "$DB" -Fc > "$FILE"
  echo "PostgreSQL 管理库备份完成：$FILE"
elif docker exec "$CONTAINER" sh -c 'command -v mysqldump >/dev/null 2>&1'; then
  FILE="$OUT_DIR/management-mysql-$STAMP.sql"
  docker exec "$CONTAINER" sh -c \
    "mysqldump -uroot -p\"\$MYSQL_ROOT_PASSWORD\" --single-transaction --routines --triggers $DB" > "$FILE"
  echo "MySQL 管理库备份完成：$FILE"
else
  echo "未在容器 $CONTAINER 内识别到 pg_dump / mysqldump" >&2
  exit 1
fi
