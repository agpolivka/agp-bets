#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "Stopping PostgreSQL..."
docker compose down

echo "Stopping backend and frontend helper processes..."
for pid_file in "$script_dir/.dev/backend.pid" "$script_dir/.dev/frontend.pid"; do
  if [[ -f "$pid_file" ]]; then
    pid="$(tr -d '\r\n' < "$pid_file")"
    if [[ -n "$pid" ]]; then
      taskkill.exe //PID "$pid" //T //F >/dev/null 2>&1 || true
    fi
  fi
done

echo "Development stack stopped."
