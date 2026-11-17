#!/usr/bin/env bash
# SpecHub 管理库恢复（PostgreSQL .dump / MySQL .sql 自动识别）。
#
# 用法：scripts/restore-db.sh <备份文件>
# 环境变量：SPECHUB_DB_CONTAINER（默认 spechub-db）、SPECHUB_DB_NAME / SPECHUB_DB_USER（默认 spechub）
# 注意：恢复会覆盖当前管理库对象（--clean --if-exists / DROP+重建），先停后端写入。
set -euo pipefail

FILE="${1:?用法: scripts/restore-db.sh <备份文件>}"
[[ -f "$FILE" ]] || { echo "备份文件不存在：$FILE" >&2; exit 1; }

CONTAINER="${SPECHUB_DB_CONTAINER:-spechub-db}"
DB="${SPECHUB_DB_NAME:-spechub}"
USER_NAME="${SPECHUB_DB_USER:-spechub}"

case "$FILE" in
  *.dump)
    PASS="$(docker exec "$CONTAINER" printenv POSTGRES_PASSWORD 2>/dev/null || true)"
    docker exec -i -e PGPASSWORD="$PASS" "$CONTAINER" \
      pg_restore -U "$USER_NAME" -d "$DB" --clean --if-exists --no-owner < "$FILE"
    echo "PostgreSQL 管理库恢复完成：$FILE"
    ;;
  *.sql)
    docker exec -i "$CONTAINER" sh -c \
      "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" $DB" < "$FILE"
    echo "MySQL 管理库恢复完成：$FILE"
    ;;
  *)
    echo "无法识别备份格式（期望 .dump 或 .sql）：$FILE" >&2
    exit 1
    ;;
esac
