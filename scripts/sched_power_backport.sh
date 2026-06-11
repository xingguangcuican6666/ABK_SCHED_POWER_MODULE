#!/usr/bin/env bash

abk_sched_install_files() {
  local common_dir sched_dir include_dir thermal_qcom_dir

  common_dir="$KERNEL_ROOT/common"
  sched_dir="$common_dir/kernel/sched"
  include_dir="$common_dir/include/linux"
  thermal_qcom_dir="$common_dir/drivers/thermal/qcom"

  mkdir -p "$sched_dir" "$include_dir" "$thermal_qcom_dir"
  cp -f "$MODULE_DIR/files/kernel/sched/abk_sched_profile.c" \
    "$sched_dir/abk_sched_profile.c"
  cp -f "$MODULE_DIR/files/include/linux/abk_sched_profile.h" \
    "$include_dir/abk_sched_profile.h"
  cp -f "$MODULE_DIR/files/include/linux/abk_ddr_cdev.h" \
    "$include_dir/abk_ddr_cdev.h"
  cp -f "$MODULE_DIR/files/drivers/thermal/qcom/ddr_cdev.c" \
    "$thermal_qcom_dir/ddr_cdev.c"

  grep -qF 'obj-y += abk_sched_profile.o' "$sched_dir/Makefile" || \
    printf '%s\n' 'obj-y += abk_sched_profile.o' >> "$sched_dir/Makefile"
}

abk_sched_patch_files() {
  local common_dir fair_c sugov_c devfreq_h devfreq_c thermal_helpers_c thermal_kconfig thermal_makefile defconfig

  common_dir="$KERNEL_ROOT/common"
  fair_c="$common_dir/kernel/sched/fair.c"
  sugov_c="$common_dir/kernel/sched/cpufreq_schedutil.c"
  devfreq_h="$common_dir/include/linux/devfreq.h"
  devfreq_c="$common_dir/drivers/devfreq/devfreq.c"
  thermal_helpers_c="$common_dir/drivers/thermal/thermal_helpers.c"
  thermal_kconfig="$common_dir/drivers/thermal/qcom/Kconfig"
  thermal_makefile="$common_dir/drivers/thermal/qcom/Makefile"
  defconfig="$common_dir/arch/arm64/configs/gki_defconfig"

  abk_require_file "$fair_c"
  abk_require_file "$sugov_c"
  abk_require_file "$devfreq_h"
  abk_require_file "$devfreq_c"
  abk_require_file "$thermal_helpers_c"
  abk_require_file "$thermal_kconfig"
  abk_require_file "$thermal_makefile"
  abk_require_file "$defconfig"

  python3 - "$fair_c" "$sugov_c" "$devfreq_h" "$devfreq_c" "$thermal_helpers_c" \
    "$thermal_kconfig" "$thermal_makefile" "$defconfig" <<'PY'
from pathlib import Path
import re
import sys

fair_c = Path(sys.argv[1])
sugov_c = Path(sys.argv[2])
devfreq_h = Path(sys.argv[3])
devfreq_c = Path(sys.argv[4])
thermal_helpers_c = Path(sys.argv[5])
thermal_kconfig = Path(sys.argv[6])
thermal_makefile = Path(sys.argv[7])
defconfig = Path(sys.argv[8])

fair = fair_c.read_text()
sugov = sugov_c.read_text()
devfreq_h_text = devfreq_h.read_text()
devfreq_c_text = devfreq_c.read_text()
thermal_kconfig_text = thermal_kconfig.read_text()
thermal_makefile_text = thermal_makefile.read_text()
thermal_helpers_text = thermal_helpers_c.read_text()
defconfig_text = defconfig.read_text()

def replace_literal(text, old, new):
    if old not in text:
        return text, False
    return text.replace(old, new, 1), True

changed = False

if "#include <linux/abk_sched_profile.h>\n" not in fair:
    fair, did = replace_literal(
        fair,
        '#include <trace/hooks/sched.h>\n',
        '#include <trace/hooks/sched.h>\n#include <linux/abk_sched_profile.h>\n',
    )
    changed |= did

for stale in (
    """
static inline bool abk_feec_skip_high_capacity_cpu(int cpu, int prev_cpu,
\t\t\t\t\t unsigned long task_est)
{
\treturn abk_sched_skip_high_capacity_cpu(cpu, prev_cpu, task_est);
}

""",
    """
static inline bool abk_feec_skip_cpu(int cpu, int prev_cpu,
\t\t\t\t\t unsigned long task_est, int sync)
{
\treturn abk_sched_skip_cpu(cpu, prev_cpu, task_est, sync);
}

""",
):
    if stale in fair:
        fair = fair.replace(stale, "", 1)
        changed = True

if "\tunsigned long p_util_est = task_util_est(p);\n" in fair:
    fair = fair.replace("\tunsigned long p_util_est = task_util_est(p);\n", "", 1)
    changed = True

stale_skip = """\n\t\t\tif (abk_feec_skip_cpu(cpu, prev_cpu, p_util_est, sync))\n\t\t\t\tcontinue;\n"""
if stale_skip in fair:
    fair = fair.replace(stale_skip, "", 1)
    changed = True

util_dequeue_re = re.compile(
    r"static inline void util_est_dequeue\(struct cfs_rq \*cfs_rq,\n"
    r"\s+struct task_struct \*p\)\n\{.*?\n\}\n",
    re.S,
)
util_dequeue_new = """static inline void util_est_dequeue(struct cfs_rq *cfs_rq,
\t\t\t\t    struct task_struct *p)
{
\tunsigned int enqueued;
\tstruct util_est ue;
\tunsigned int util;

\tif (!sched_feat(UTIL_EST))
\t\treturn;

\t/* Update root cfs_rq's estimated utilization */
\tenqueued  = cfs_rq->avg.util_est.enqueued;
\tenqueued -= min_t(unsigned int, enqueued, _task_util_est(p));
\tWRITE_ONCE(cfs_rq->avg.util_est.enqueued, enqueued);

\t/*
\t * Short-lived tasks can leave ewma glued to old peaks. If the current
\t * util_avg is now far below ewma, decay faster at dequeue time so the
\t * next wakeup doesn't keep a CPU artificially hot.
\t */
\tue = READ_ONCE(p->se.avg.util_est);
\tutil = task_util(p);
\tif (ue.ewma > (util << 1)) {
\t\tue.ewma = abk_sched_profile_decay_ewma(ue.ewma, util);
\t\tWRITE_ONCE(p->se.avg.util_est, ue);
\t}

\ttrace_sched_util_est_cfs_tp(cfs_rq);
}
"""
new_fair, count = util_dequeue_re.subn(util_dequeue_new, fair, count=1)
if count:
    fair = new_fair
    changed = True

util_decay_anchor = """\tif (sched_feat(UTIL_EST_FASTUP)) {
\t\tif (ue.ewma < ue.enqueued) {
\t\t\tue.ewma = ue.enqueued;
\t\t\tgoto done;
\t\t}
\t}
"""
util_decay_insert = util_decay_anchor + """
\t/*
\t * If a short activation falls far below the historical peak, decay
\t * ewma immediately instead of keeping the old peak around for too long.
\t */
\tif (ue.ewma > (ue.enqueued << 1)) {
\t\tue.ewma = abk_sched_profile_decay_ewma(ue.ewma, ue.enqueued);
\t\tgoto done;
\t}
"""
if "short activation falls far below the historical peak" not in fair:
    fair, did = replace_literal(fair, util_decay_anchor, util_decay_insert)
    changed |= did

if "#include <linux/abk_sched_profile.h>\n" not in sugov:
    sugov, did = replace_literal(
        sugov,
        '#include <trace/hooks/sched.h>\n',
        '#include <trace/hooks/sched.h>\n#include <linux/abk_sched_profile.h>\n',
    )
    changed |= did

old_update = """static bool sugov_update_next_freq(struct sugov_policy *sg_policy, u64 time,
\t\t\t\t   unsigned int next_freq)
{
\tif (sg_policy->need_freq_update)
\t\tsg_policy->need_freq_update = cpufreq_driver_test_flags(CPUFREQ_NEED_UPDATE_LIMITS);
\telse if (sg_policy->next_freq == next_freq)
\t\treturn false;

\tsg_policy->next_freq = next_freq;
\tsg_policy->last_freq_update_time = time;

\treturn true;
}
"""
new_update = """static bool sugov_update_next_freq(struct sugov_policy *sg_policy, u64 time,
\t\t\t\t   unsigned int next_freq)
{
\tif (sg_policy->need_freq_update) {
\t\tsg_policy->need_freq_update = false;
\t\tif (sg_policy->next_freq == next_freq &&
\t\t    !cpufreq_driver_test_flags(CPUFREQ_NEED_UPDATE_LIMITS))
\t\t\treturn false;
\t} else if (sg_policy->next_freq == next_freq) {
\t\treturn false;
\t}

\tsg_policy->next_freq = next_freq;
\tsg_policy->last_freq_update_time = time;

\treturn true;
}
"""
if old_update in sugov:
    sugov = sugov.replace(old_update, new_update, 1)
    changed = True

get_next_freq_re = re.compile(
    r"static unsigned int get_next_freq\(struct sugov_policy \*sg_policy,\n"
    r"\s+unsigned long util, unsigned long max\)\n\{.*?\n\}\n\n"
    r"static void sugov_get_util",
    re.S,
)
get_next_freq_new = """static unsigned int get_next_freq(struct sugov_policy *sg_policy,
\t\t\t\t  unsigned long util, unsigned long max)
{
\tstruct cpufreq_policy *policy = sg_policy->policy;
\tunsigned int policy_cpu = cpumask_first(policy->related_cpus);
\tunsigned long policy_cap = arch_scale_cpu_capacity(policy_cpu);
\tunsigned int freq = arch_scale_freq_invariant() ?
\t\t\t\tpolicy->cpuinfo.max_freq : policy->cur;
\tunsigned long next_freq = 0;

\tutil = abk_sched_profile_scale_util(util, policy_cap);
\ttrace_android_vh_map_util_freq(util, freq, max, &next_freq, policy,
\t\t\t&sg_policy->need_freq_update);
\tif (next_freq)
\t\tfreq = next_freq;
\telse
\t\tfreq = map_util_freq(util, freq, max);

\tif (freq == sg_policy->cached_raw_freq && !sg_policy->need_freq_update)
\t\treturn sg_policy->next_freq;

\tsg_policy->cached_raw_freq = freq;
\treturn cpufreq_driver_resolve_freq(policy, freq);
}

static void sugov_get_util"""
new_sugov, count = get_next_freq_re.subn(get_next_freq_new, sugov, count=1)
if count:
    sugov = new_sugov
    changed = True

decl = """struct devfreq *devfreq_get_devfreq_by_node(struct device_node *node);
struct devfreq *devfreq_get_devfreq_by_phandle(struct device *dev,
\t\t\t\tconst char *phandle_name, int index);
struct devfreq *devfreq_get_devfreq_by_parent_name(const char *name);
"""
if "struct devfreq *devfreq_get_devfreq_by_parent_name(const char *name);" not in devfreq_h_text:
    old = """struct devfreq *devfreq_get_devfreq_by_node(struct device_node *node);
struct devfreq *devfreq_get_devfreq_by_phandle(struct device *dev,
\t\t\t\tconst char *phandle_name, int index);
"""
    devfreq_h_text, did = replace_literal(devfreq_h_text, old, decl)
    changed |= did

stub = """static inline struct devfreq *devfreq_get_devfreq_by_parent_name(const char *name)
{
\treturn ERR_PTR(-ENODEV);
}
"""
if stub not in devfreq_h_text:
    marker = """static inline struct devfreq *devfreq_get_devfreq_by_phandle(struct device *dev,
\t\t\t\t\tconst char *phandle_name, int index)
{
\treturn ERR_PTR(-ENODEV);
}
"""
    devfreq_h_text, did = replace_literal(devfreq_h_text, marker, marker + "\n" + stub)
    changed |= did

helper = """
struct devfreq *devfreq_get_devfreq_by_parent_name(const char *name)
{
\tstruct devfreq *devfreq;

\tif (!name)
\t\treturn ERR_PTR(-EINVAL);

\tmutex_lock(&devfreq_list_lock);
\tlist_for_each_entry(devfreq, &devfreq_list, node) {
\t\tif (devfreq->dev.parent &&
\t\t    !strcmp(dev_name(devfreq->dev.parent), name)) {
\t\t\tmutex_unlock(&devfreq_list_lock);
\t\t\treturn devfreq;
\t\t}
\t}
\tmutex_unlock(&devfreq_list_lock);

\treturn ERR_PTR(-ENODEV);
}
EXPORT_SYMBOL_GPL(devfreq_get_devfreq_by_parent_name);
"""
if "devfreq_get_devfreq_by_parent_name(const char *name)" not in devfreq_c_text:
    anchor = """EXPORT_SYMBOL_GPL(devfreq_get_devfreq_by_node);
EXPORT_SYMBOL_GPL(devfreq_get_devfreq_by_phandle);
"""
    devfreq_c_text, did = replace_literal(devfreq_c_text, anchor, anchor + helper)
    changed |= did

if "#include <linux/abk_sched_profile.h>\n" not in thermal_helpers_text:
    thermal_helpers_text, did = replace_literal(
        thermal_helpers_text,
        '#include <linux/export.h>\n',
        '#include <linux/export.h>\n#include <linux/abk_sched_profile.h>\n',
    )
    changed |= did

thermal_override_old = """\tthermal_cdev_set_cur_state(cdev, target);

\ttrace_cdev_update(cdev, target);
"""
thermal_override_new = """\ttarget = abk_sched_profile_override_thermal_target(cdev->type,
\t\t\t\t\t\t       target, cdev->max_state);
\tthermal_cdev_set_cur_state(cdev, target);

\ttrace_cdev_update(cdev, target);
"""
if "abk_sched_profile_override_thermal_target(cdev->type" not in thermal_helpers_text:
    thermal_helpers_text, did = replace_literal(
        thermal_helpers_text, thermal_override_old, thermal_override_new
    )
    changed |= did

cfg_block = """
config QTI_DDR_COOLING_DEVICE
\ttristate "QTI DDR cooling devices"
\tdepends on THERMAL && INTERCONNECT
\thelp
\t   This enables the QTI DDR cooling device. It can be used both by
\t   thermal policies and by ABK runtime profiles to place a DDR floor
\t   request through the interconnect framework.
"""
if "config QTI_DDR_COOLING_DEVICE" not in thermal_kconfig_text:
    thermal_kconfig_text = thermal_kconfig_text.rstrip() + "\n\n" + cfg_block + "\n"
    changed = True

make_line = "obj-$(CONFIG_QTI_DDR_COOLING_DEVICE) += ddr_cdev.o\n"
if make_line not in thermal_makefile_text:
    thermal_makefile_text += make_line
    changed = True

if "# CONFIG_QTI_DDR_COOLING_DEVICE is not set\n" in defconfig_text:
    defconfig_text = defconfig_text.replace(
        "# CONFIG_QTI_DDR_COOLING_DEVICE is not set\n",
        "CONFIG_QTI_DDR_COOLING_DEVICE=y\n",
        1,
    )
    changed = True
elif "CONFIG_QTI_DDR_COOLING_DEVICE=y\n" not in defconfig_text:
    defconfig_text += "\nCONFIG_QTI_DDR_COOLING_DEVICE=y\n"
    changed = True

if changed:
    fair_c.write_text(fair)
    sugov_c.write_text(sugov)
    devfreq_h.write_text(devfreq_h_text)
    devfreq_c.write_text(devfreq_c_text)
    thermal_helpers_c.write_text(thermal_helpers_text)
    thermal_kconfig.write_text(thermal_kconfig_text)
    thermal_makefile.write_text(thermal_makefile_text)
    defconfig.write_text(defconfig_text)
PY
}
