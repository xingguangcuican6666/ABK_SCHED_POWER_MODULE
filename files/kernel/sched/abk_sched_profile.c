// SPDX-License-Identifier: GPL-2.0
#include <linux/abk_control.h>
#include <linux/abk_ddr_cdev.h>
#include <linux/abk_sched_profile.h>
#include <linux/cpufreq.h>
#include <linux/cpumask.h>
#include <linux/devfreq.h>
#include <linux/init.h>
#include <linux/kernel.h>
#include <linux/list.h>
#include <linux/module.h>
#include <linux/of.h>
#include <linux/pm_qos.h>
#include <linux/proc_fs.h>
#include <linux/seq_file.h>
#include <linux/slab.h>
#include <linux/string.h>
#include <linux/topology.h>
#include <linux/uaccess.h>
#include <linux/units.h>
#include <linux/workqueue.h>

#include "../../drivers/thermal/thermal_core.h"

#define ABK_SCHED_PROFILE_PROC "abk_sched_profile"
#define ABK_DISPLAY_STATE_PROC "abk_sched_display_conservative_state"
#define ABK_PROFILE_RETRY_MS 5000
#define ABK_PROFILE_MAX_RETRIES 24

static enum abk_sched_profile_mode abk_sched_profile_mode =
	ABK_SCHED_PROFILE_CONSERVATIVE;
static struct delayed_work abk_profile_apply_work;
static unsigned int abk_profile_apply_retries;
static unsigned int abk_display_conservative_state = 9;
static LIST_HEAD(abk_cpufreq_reqs);
static DEFINE_MUTEX(abk_cpufreq_lock);

struct abk_cpufreq_req {
	struct list_head node;
	struct cpufreq_policy *policy;
	struct freq_qos_request min_req;
	struct freq_qos_request max_req;
};

enum abk_sched_capacity_tier {
	ABK_SCHED_TIER_LITTLE = 0,
	ABK_SCHED_TIER_MID,
	ABK_SCHED_TIER_BIG,
	ABK_SCHED_TIER_PRIME,
};

static enum abk_sched_capacity_tier
abk_sched_capacity_tier(unsigned long cap)
{
	if (cap >= (SCHED_CAPACITY_SCALE * 7 / 8))
		return ABK_SCHED_TIER_PRIME;
	if (cap >= (SCHED_CAPACITY_SCALE * 3 / 4))
		return ABK_SCHED_TIER_BIG;
	if (cap >= (SCHED_CAPACITY_SCALE * 5 / 8))
		return ABK_SCHED_TIER_MID;
	return ABK_SCHED_TIER_LITTLE;
}

static const char *abk_sched_profile_name(enum abk_sched_profile_mode mode)
{
	return mode == ABK_SCHED_PROFILE_AGGRESSIVE ?
		"aggressive" : "conservative";
}

static bool abk_type_eq(const char *type, const char *needle)
{
	return type && needle && !strcmp(type, needle);
}

static bool abk_type_prefix(const char *type, const char *prefix)
{
	size_t len;

	if (!type || !prefix)
		return false;

	len = strlen(prefix);
	return !strncmp(type, prefix, len);
}

static void abk_schedule_profile_apply(unsigned long delay_ms)
{
	mod_delayed_work(system_wq, &abk_profile_apply_work,
			 msecs_to_jiffies(delay_ms));
}

enum abk_sched_profile_mode abk_sched_profile_get_mode(void)
{
	return READ_ONCE(abk_sched_profile_mode);
}
EXPORT_SYMBOL_GPL(abk_sched_profile_get_mode);

bool abk_sched_profile_aggressive(void)
{
	return abk_sched_profile_get_mode() == ABK_SCHED_PROFILE_AGGRESSIVE;
}
EXPORT_SYMBOL_GPL(abk_sched_profile_aggressive);

int abk_sched_profile_set_mode(enum abk_sched_profile_mode mode)
{
	if (mode != ABK_SCHED_PROFILE_CONSERVATIVE &&
	    mode != ABK_SCHED_PROFILE_AGGRESSIVE)
		return -EINVAL;

	WRITE_ONCE(abk_sched_profile_mode, mode);
	pr_info("abk_sched_profile: switched to %s\n",
		abk_sched_profile_name(mode));
	abk_profile_apply_retries = 0;
	abk_schedule_profile_apply(0);
	return 0;
}
EXPORT_SYMBOL_GPL(abk_sched_profile_set_mode);

unsigned int abk_sched_profile_decay_ewma(unsigned int ewma, unsigned int util)
{
	if (ewma <= util)
		return util;

	if (abk_sched_profile_aggressive())
		return max_t(unsigned int, util, ((ewma * 3) + util) >> 2);

	return max_t(unsigned int, util, (ewma + util) >> 1);
}
EXPORT_SYMBOL_GPL(abk_sched_profile_decay_ewma);

unsigned long abk_sched_profile_scale_util(unsigned long util,
					   unsigned long cpu_capacity)
{
	unsigned int factor;

	switch (abk_sched_capacity_tier(cpu_capacity)) {
	case ABK_SCHED_TIER_PRIME:
		factor = abk_sched_profile_aggressive() ? 1088 : 1024;
		break;
	case ABK_SCHED_TIER_BIG:
		factor = abk_sched_profile_aggressive() ? 1120 : 1040;
		break;
	case ABK_SCHED_TIER_MID:
		factor = abk_sched_profile_aggressive() ? 1152 : 1072;
		break;
	case ABK_SCHED_TIER_LITTLE:
	default:
		factor = abk_sched_profile_aggressive() ? 1184 : 1088;
		break;
	}

	util = (util * factor) >> SCHED_CAPACITY_SHIFT;
	return min_t(unsigned long, util, SCHED_CAPACITY_SCALE);
}
EXPORT_SYMBOL_GPL(abk_sched_profile_scale_util);

unsigned long abk_sched_profile_override_thermal_target(const char *type,
							unsigned long target,
							unsigned long max_state)
{
	unsigned long ddr_floor;
	unsigned long display_state;

	if (!type)
		return target;

	if (abk_type_eq(type, "panel0-backlight") ||
	    abk_type_eq(type, "backlight"))
		return 0;

	if (abk_type_eq(type, "display-fps") ||
	    abk_type_eq(type, "fps") ||
	    abk_type_eq(type, "refresh-rate")) {
		if (abk_sched_profile_aggressive())
			return 0;

		display_state = min_t(unsigned long,
				      READ_ONCE(abk_display_conservative_state),
				      max_state);
		return max(target, display_state);
	}

	if (!abk_sched_profile_aggressive())
		return target;

	if (abk_type_eq(type, "ddr-cdev")) {
		if (!max_state)
			return target;
		ddr_floor = max_t(unsigned long, 1, mult_frac(max_state, 3, 4));
		return max(target, ddr_floor);
	}

	if (abk_type_eq(type, "display-fps") ||
	    abk_type_eq(type, "panel0-backlight") ||
	    abk_type_eq(type, "gpu") ||
	    abk_type_eq(type, "gpu-dump-skip-cdev") ||
	    abk_type_eq(type, "devfreq-3d00000.qcom,kgsl-3d0") ||
	    abk_type_prefix(type, "cpufreq-cpu") ||
	    abk_type_prefix(type, "cpu-cluster") ||
	    abk_type_prefix(type, "thermal-cluster-") ||
	    abk_type_prefix(type, "cpu-hotplug") ||
	    abk_type_prefix(type, "pause-cpu") ||
	    abk_type_prefix(type, "thermal-pause-"))
		return 0;

	return target;
}
EXPORT_SYMBOL_GPL(abk_sched_profile_override_thermal_target);

static unsigned int abk_cpufreq_pick_khz(const struct cpufreq_policy *policy,
					 unsigned int permille)
{
	unsigned int freq;

	freq = mult_frac(policy->cpuinfo.max_freq, permille, 1000);
	freq = clamp_t(unsigned int, freq, policy->cpuinfo.min_freq,
		       policy->cpuinfo.max_freq);
	return freq;
}

static void
abk_cpufreq_profile_limits(const struct cpufreq_policy *policy,
			   unsigned int *min_khz, unsigned int *max_khz)
{
	enum abk_sched_capacity_tier tier;
	unsigned long capacity;

	capacity = arch_scale_cpu_capacity(policy->cpu);
	tier = abk_sched_capacity_tier(capacity);

	*min_khz = policy->cpuinfo.min_freq;
	*max_khz = FREQ_QOS_MAX_DEFAULT_VALUE;

	if (abk_sched_profile_aggressive()) {
		switch (tier) {
		case ABK_SCHED_TIER_PRIME:
			*min_khz = abk_cpufreq_pick_khz(policy, 700);
			break;
		case ABK_SCHED_TIER_BIG:
			*min_khz = abk_cpufreq_pick_khz(policy, 550);
			break;
		case ABK_SCHED_TIER_MID:
			*min_khz = abk_cpufreq_pick_khz(policy, 400);
			break;
		case ABK_SCHED_TIER_LITTLE:
		default:
			*min_khz = abk_cpufreq_pick_khz(policy, 250);
			break;
		}
		return;
	}

	switch (tier) {
	case ABK_SCHED_TIER_PRIME:
		*max_khz = abk_cpufreq_pick_khz(policy, 700);
		break;
	case ABK_SCHED_TIER_BIG:
		*max_khz = abk_cpufreq_pick_khz(policy, 850);
		break;
	case ABK_SCHED_TIER_MID:
		*max_khz = abk_cpufreq_pick_khz(policy, 925);
		break;
	case ABK_SCHED_TIER_LITTLE:
	default:
		break;
	}
}

static void abk_cpufreq_release_locked(void)
{
	struct abk_cpufreq_req *req;
	struct abk_cpufreq_req *tmp;

	list_for_each_entry_safe(req, tmp, &abk_cpufreq_reqs, node) {
		list_del(&req->node);
		if (freq_qos_request_active(&req->max_req))
			freq_qos_remove_request(&req->max_req);
		if (freq_qos_request_active(&req->min_req))
			freq_qos_remove_request(&req->min_req);
		cpufreq_cpu_put(req->policy);
		kfree(req);
	}
}

static int abk_cpufreq_add_policy_locked(struct cpufreq_policy *policy)
{
	struct abk_cpufreq_req *req;
	int ret;

	list_for_each_entry(req, &abk_cpufreq_reqs, node) {
		if (req->policy == policy)
			return 0;
	}

	req = kzalloc(sizeof(*req), GFP_KERNEL);
	if (!req)
		return -ENOMEM;

	req->policy = policy;
	ret = freq_qos_add_request(&policy->constraints, &req->min_req,
				   FREQ_QOS_MIN, FREQ_QOS_MIN_DEFAULT_VALUE);
	if (ret < 0)
		goto err_free;

	ret = freq_qos_add_request(&policy->constraints, &req->max_req,
				   FREQ_QOS_MAX, FREQ_QOS_MAX_DEFAULT_VALUE);
	if (ret < 0)
		goto err_remove_min;

	list_add_tail(&req->node, &abk_cpufreq_reqs);
	return 0;

err_remove_min:
	freq_qos_remove_request(&req->min_req);
err_free:
	kfree(req);
	return ret;
}

static int abk_cpufreq_collect_locked(void)
{
	cpumask_t seen;
	struct cpufreq_policy *policy;
	int cpu;
	int ret = -ENODEV;

	cpumask_clear(&seen);
	for_each_possible_cpu(cpu) {
		if (cpumask_test_cpu(cpu, &seen))
			continue;

		policy = cpufreq_cpu_get(cpu);
		if (!policy)
			continue;

		cpumask_or(&seen, &seen, policy->related_cpus);
		ret = abk_cpufreq_add_policy_locked(policy);
		if (ret) {
			cpufreq_cpu_put(policy);
			return ret;
		}

		ret = 0;
	}

	return ret;
}

static int abk_apply_cpufreq_profile(void)
{
	struct abk_cpufreq_req *req;
	unsigned int min_khz;
	unsigned int max_khz;
	int ret = 0;

	mutex_lock(&abk_cpufreq_lock);

	if (list_empty(&abk_cpufreq_reqs)) {
		ret = abk_cpufreq_collect_locked();
		if (ret)
			goto out_unlock;
	}

	list_for_each_entry(req, &abk_cpufreq_reqs, node) {
		abk_cpufreq_profile_limits(req->policy, &min_khz, &max_khz);

		ret = freq_qos_update_request(&req->min_req, min_khz);
		if (ret < 0)
			goto out_unlock;

		ret = freq_qos_update_request(&req->max_req, max_khz);
		if (ret < 0)
			goto out_unlock;
	}

	ret = 0;

out_unlock:
	mutex_unlock(&abk_cpufreq_lock);
	return ret;
}

static int abk_refresh_one_thermal_zone(struct thermal_zone_device *tz, void *arg)
{
	thermal_zone_device_update(tz, THERMAL_EVENT_UNSPECIFIED);
	return 0;
}

static void abk_refresh_thermal_zones(void)
{
	for_each_thermal_zone(abk_refresh_one_thermal_zone, NULL);
}

static unsigned long abk_devfreq_lowest_freq(struct devfreq *df)
{
	unsigned long lowest = ULONG_MAX;
	unsigned int i;

	for (i = 0; i < df->max_state; i++)
		lowest = min(lowest, df->freq_table[i]);

	return lowest == ULONG_MAX ? 0 : lowest;
}

static unsigned long abk_devfreq_highest_freq(struct devfreq *df)
{
	unsigned long highest = 0;
	unsigned int i;

	for (i = 0; i < df->max_state; i++)
		highest = max(highest, df->freq_table[i]);

	return highest;
}

static unsigned long abk_devfreq_pick_floor(struct devfreq *df,
					    unsigned int permille)
{
	unsigned long highest = abk_devfreq_highest_freq(df);
	unsigned long target;
	unsigned long best = ULONG_MAX;
	unsigned int i;

	if (!highest)
		return 0;

	target = mult_frac(highest, permille, 1000);
	for (i = 0; i < df->max_state; i++) {
		unsigned long freq = df->freq_table[i];

		if (freq >= target)
			best = min(best, freq);
	}

	if (best != ULONG_MAX)
		return best;

	return highest;
}

static unsigned long abk_devfreq_pick_cap(struct devfreq *df,
					  unsigned int permille)
{
	unsigned long highest = abk_devfreq_highest_freq(df);
	unsigned long lowest = abk_devfreq_lowest_freq(df);
	unsigned long target;
	unsigned long best = 0;
	unsigned int i;

	if (!highest)
		return 0;

	target = mult_frac(highest, permille, 1000);
	for (i = 0; i < df->max_state; i++) {
		unsigned long freq = df->freq_table[i];

		if (freq <= target)
			best = max(best, freq);
	}

	if (best)
		return best;

	return lowest;
}

static int abk_devfreq_apply_qos(struct devfreq *df, unsigned long min_hz,
				 unsigned long max_hz)
{
	s32 min_req;
	s32 max_req;
	int ret;

	if (IS_ERR_OR_NULL(df))
		return -ENODEV;

	min_req = min_hz ? DIV_ROUND_UP(min_hz, HZ_PER_KHZ) : 0;
	max_req = max_hz ? DIV_ROUND_UP(max_hz, HZ_PER_KHZ) :
		PM_QOS_MAX_FREQUENCY_DEFAULT_VALUE;

	dev_pm_qos_update_request(&df->user_min_freq_req, min_req);
	dev_pm_qos_update_request(&df->user_max_freq_req, max_req);

	mutex_lock(&df->lock);
	ret = update_devfreq(df);
	mutex_unlock(&df->lock);

	return ret;
}

static struct devfreq *abk_gpu_devfreq_get(void)
{
	struct device_node *node;
	struct devfreq *df;

	node = of_find_compatible_node(NULL, NULL, "qcom,kgsl-3d0");
	if (node) {
		df = devfreq_get_devfreq_by_node(node);
		of_node_put(node);
		if (!IS_ERR(df))
			return df;
	}

	return devfreq_get_devfreq_by_parent_name("3d00000.qcom,kgsl-3d0");
}

static struct devfreq *abk_gpubw_devfreq_get(void)
{
	return devfreq_get_devfreq_by_parent_name("kgsl-busmon");
}

static int abk_apply_gpu_profile(void)
{
	struct devfreq *gpu_df;
	struct devfreq *gpubw_df;
	unsigned long min_hz;
	unsigned long max_hz;
	int ret;

	gpu_df = abk_gpu_devfreq_get();
	if (IS_ERR(gpu_df))
		return PTR_ERR(gpu_df);

	if (abk_sched_profile_aggressive()) {
		min_hz = abk_devfreq_pick_floor(gpu_df, 550);
		max_hz = 0;
	} else {
		min_hz = 0;
		max_hz = abk_devfreq_pick_cap(gpu_df, 750);
	}

	ret = abk_devfreq_apply_qos(gpu_df, min_hz, max_hz);
	if (ret)
		return ret;

	gpubw_df = abk_gpubw_devfreq_get();
	if (IS_ERR(gpubw_df))
		return PTR_ERR(gpubw_df);

	if (abk_sched_profile_aggressive()) {
		min_hz = abk_devfreq_pick_floor(gpubw_df, 500);
		max_hz = 0;
	} else {
		min_hz = 0;
		max_hz = abk_devfreq_pick_cap(gpubw_df, 650);
	}

	return abk_devfreq_apply_qos(gpubw_df, min_hz, max_hz);
}

static int abk_apply_ddr_profile(void)
{
	unsigned long state;
	unsigned long max_state;

	if (!abk_ddr_cdev_ready())
		return -ENODEV;

	max_state = abk_ddr_cdev_max_state();
	if (!abk_sched_profile_aggressive())
		return abk_ddr_cdev_set_state(0);

	state = max_t(unsigned long, 1, mult_frac(max_state, 3, 4));
	return abk_ddr_cdev_set_state(state);
}

static bool abk_apply_runtime_profiles_once(void)
{
	bool retry = false;
	int ret;

	ret = abk_apply_cpufreq_profile();
	if (ret == -ENODEV) {
		retry = true;
	} else if (ret) {
		pr_warn("abk_sched_profile: cpufreq profile apply failed: %d\n",
			ret);
	}

	ret = abk_apply_gpu_profile();
	if (ret == -ENODEV) {
		retry = true;
	} else if (ret) {
		pr_warn("abk_sched_profile: gpu profile apply failed: %d\n", ret);
	}

	ret = abk_apply_ddr_profile();
	if (ret == -ENODEV) {
		retry = true;
	} else if (ret) {
		pr_warn("abk_sched_profile: ddr profile apply failed: %d\n", ret);
	}

	return retry;
}

static void abk_profile_apply_workfn(struct work_struct *work)
{
	bool retry;

	abk_refresh_thermal_zones();
	retry = abk_apply_runtime_profiles_once();
	if (!retry)
		return;

	if (abk_profile_apply_retries++ >= ABK_PROFILE_MAX_RETRIES)
		return;

	abk_schedule_profile_apply(ABK_PROFILE_RETRY_MS);
}

static int abk_sched_profile_show(struct seq_file *m, void *v)
{
	seq_printf(m, "%s\n",
		   abk_sched_profile_name(abk_sched_profile_get_mode()));
	return 0;
}

static int abk_display_state_show(struct seq_file *m, void *v)
{
	seq_printf(m, "%u\n", READ_ONCE(abk_display_conservative_state));
	return 0;
}

static int abk_sched_profile_open(struct inode *inode, struct file *file)
{
	return single_open(file, abk_sched_profile_show, NULL);
}

static int abk_display_state_open(struct inode *inode, struct file *file)
{
	return single_open(file, abk_display_state_show, NULL);
}

static ssize_t abk_sched_profile_write(struct file *file,
				       const char __user *ubuf,
				       size_t len, loff_t *ppos)
{
	char buf[32];
	size_t copy = min(len, sizeof(buf) - 1);

	if (copy_from_user(buf, ubuf, copy))
		return -EFAULT;
	buf[copy] = '\0';
	strim(buf);

	if (!strcmp(buf, "1") || !strcmp(buf, "aggressive") ||
	    !strcmp(buf, "game"))
		abk_sched_profile_set_mode(ABK_SCHED_PROFILE_AGGRESSIVE);
	else if (!strcmp(buf, "0") || !strcmp(buf, "conservative") ||
		 !strcmp(buf, "normal"))
		abk_sched_profile_set_mode(ABK_SCHED_PROFILE_CONSERVATIVE);
	else
		return -EINVAL;

	return len;
}

static ssize_t abk_display_state_write(struct file *file,
				       const char __user *ubuf,
				       size_t len, loff_t *ppos)
{
	char buf[32];
	size_t copy = min(len, sizeof(buf) - 1);
	unsigned int state;

	if (copy_from_user(buf, ubuf, copy))
		return -EFAULT;
	buf[copy] = '\0';
	strim(buf);

	if (kstrtouint(buf, 10, &state))
		return -EINVAL;

	WRITE_ONCE(abk_display_conservative_state, state);
	abk_profile_apply_retries = 0;
	abk_schedule_profile_apply(0);
	return len;
}

static const struct proc_ops abk_sched_profile_proc_ops = {
	.proc_open	= abk_sched_profile_open,
	.proc_read	= seq_read,
	.proc_lseek	= seq_lseek,
	.proc_release	= single_release,
	.proc_write	= abk_sched_profile_write,
};

static const struct proc_ops abk_display_state_proc_ops = {
	.proc_open	= abk_display_state_open,
	.proc_read	= seq_read,
	.proc_lseek	= seq_lseek,
	.proc_release	= single_release,
	.proc_write	= abk_display_state_write,
};

static bool abk_sched_profile_is_enabled(void *data)
{
	return abk_sched_profile_aggressive();
}

static int abk_sched_profile_set_enabled(bool enabled, void *data)
{
	return abk_sched_profile_set_mode(enabled ?
		ABK_SCHED_PROFILE_AGGRESSIVE :
		ABK_SCHED_PROFILE_CONSERVATIVE);
}

static int abk_sched_profile_run_command(const char *command, void *data)
{
	char buffer[96];
	char *cursor;
	char *verb;
	char *value;
	unsigned int state;

	if (!command || !command[0])
		return -EINVAL;

	strscpy(buffer, command, sizeof(buffer));
	cursor = strim(buffer);
	verb = strsep(&cursor, " \t\r\n");
	value = strim(cursor ? cursor : "");

	if (!verb || !verb[0])
		return -EINVAL;

	if (!strcmp(verb, "mode")) {
		if (!strcmp(value, "aggressive") || !strcmp(value, "perf") ||
		    !strcmp(value, "game"))
			return abk_sched_profile_set_mode(ABK_SCHED_PROFILE_AGGRESSIVE);
		if (!strcmp(value, "conservative") || !strcmp(value, "balanced") ||
		    !strcmp(value, "normal"))
			return abk_sched_profile_set_mode(ABK_SCHED_PROFILE_CONSERVATIVE);
		return -EINVAL;
	}

	if (!strcmp(verb, "display_state")) {
		if (kstrtouint(value, 10, &state))
			return -EINVAL;
		WRITE_ONCE(abk_display_conservative_state, state);
		abk_profile_apply_retries = 0;
		abk_schedule_profile_apply(0);
		return 0;
	}

	return -EINVAL;
}

static const struct abk_control_ops abk_sched_profile_ops = {
	.id = "sched_power_backport",
	.name = "Sched Power Backport",
	.version = "0.2.0",
	.description = "Switch between conservative and aggressive CPU/GPU/DDR power profiles.",
	.module_dir = "kernel/sched",
	.web_root = "",
	.extension_id = "sched_power_profile",
	.companion_package = "com.abk.extension.schedpower",
	.companion_display_name = "ABK Sched Power Extension",
	.companion_asset_name = "abk-sched-power-extension-release.apk",
	.has_web_ui = false,
	.has_action_script = false,
	.action_supported = false,
	.requires_companion_app = true,
	.settings_supported = true,
	.per_app_supported = true,
	.oobe_priority = 100,
	.is_enabled = abk_sched_profile_is_enabled,
	.set_enabled = abk_sched_profile_set_enabled,
	.run_command = abk_sched_profile_run_command,
};

static int __init abk_sched_profile_init(void)
{
	INIT_DELAYED_WORK(&abk_profile_apply_work, abk_profile_apply_workfn);

	proc_create(ABK_SCHED_PROFILE_PROC, 0644, NULL,
		    &abk_sched_profile_proc_ops);
	proc_create(ABK_DISPLAY_STATE_PROC, 0644, NULL,
		    &abk_display_state_proc_ops);

	if (IS_ENABLED(CONFIG_ABK_CONTROL))
		abk_control_register(&abk_sched_profile_ops);

	abk_schedule_profile_apply(ABK_PROFILE_RETRY_MS);
	return 0;
}

static void __exit abk_sched_profile_exit(void)
{
	cancel_delayed_work_sync(&abk_profile_apply_work);
	remove_proc_entry(ABK_SCHED_PROFILE_PROC, NULL);
	remove_proc_entry(ABK_DISPLAY_STATE_PROC, NULL);

	if (IS_ENABLED(CONFIG_ABK_CONTROL))
		abk_control_unregister(&abk_sched_profile_ops);

	mutex_lock(&abk_cpufreq_lock);
	abk_cpufreq_release_locked();
	mutex_unlock(&abk_cpufreq_lock);
}

module_init(abk_sched_profile_init);
module_exit(abk_sched_profile_exit);
