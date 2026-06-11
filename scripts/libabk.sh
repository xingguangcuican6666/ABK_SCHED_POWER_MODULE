#!/usr/bin/env bash

abk_log() {
  printf '[ABK sched] %s\n' "$*"
}

abk_die() {
  printf '[ABK sched][error] %s\n' "$*" >&2
  exit 1
}

abk_require_env() {
  local name
  for name in "$@"; do
    if [ -z "${!name:-}" ]; then
      abk_die "required environment variable is empty: $name"
    fi
  done
}

abk_require_file() {
  local path="$1"
  [ -f "$path" ] || abk_die "required file not found: $path"
}

abk_require_dir() {
  local path="$1"
  [ -d "$path" ] || abk_die "required directory not found: $path"
}
