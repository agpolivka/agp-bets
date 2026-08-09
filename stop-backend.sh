#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

kill_pid_file() {
  local pid_file="$1"
  if [[ -f "$pid_file" ]]; then
    pid="$(tr -d '\r\n' < "$pid_file")"
    if [[ -n "$pid" ]]; then
      taskkill.exe //PID "$pid" //T //F >/dev/null 2>&1 || true
    fi
  fi
}

kill_port() {
  local port="$1"
  local pids
  pids="$(
    netstat -ano -p tcp 2>/dev/null \
      | awk -v port=":$port" '$1 ~ /^TCP/ && $2 ~ port && $4 == "LISTENING" { print $5 }' \
      | sort -u
  )"

  if [[ -z "$pids" ]]; then
    return 0
  fi

  echo "Killing processes listening on port $port..."
  while IFS= read -r pid; do
    if [[ -n "$pid" ]]; then
      taskkill.exe //PID "$pid" //T //F >/dev/null 2>&1 || true
    fi
  done <<< "$pids"
}

echo "Stopping backend..."
kill_pid_file "$script_dir/.dev/backend.pid"
kill_port 8080

echo "Backend stopped. PostgreSQL left running - stop it separately with 'docker compose down' or stop-dev.sh if you want it down too."
