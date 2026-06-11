#!/usr/bin/env bash
set -euo pipefail

MODULE_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

if [ -f "$MODULE_DIR/module.conf" ]; then
  # shellcheck disable=SC1091
  source "$MODULE_DIR/module.conf"
fi

# shellcheck disable=SC1091
source "$MODULE_DIR/scripts/libabk.sh"
# shellcheck disable=SC1091
source "$MODULE_DIR/scripts/sched_power_backport.sh"

abk_require_env KERNEL_ROOT CUSTOM_EXTERNAL_MODULE_STAGE

if [ "$CUSTOM_EXTERNAL_MODULE_STAGE" != "after_patch" ]; then
  abk_die "unsupported CUSTOM_EXTERNAL_MODULE_STAGE: $CUSTOM_EXTERNAL_MODULE_STAGE"
fi

abk_log "module: ${ABK_MODULE_NAME:-Sched Power Backport}"
abk_log "stage: $CUSTOM_EXTERNAL_MODULE_STAGE"
abk_sched_install_files
abk_sched_patch_files
abk_log "done"
