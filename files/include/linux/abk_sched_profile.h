/* SPDX-License-Identifier: GPL-2.0 */
#ifndef _LINUX_ABK_SCHED_PROFILE_H
#define _LINUX_ABK_SCHED_PROFILE_H

#include <linux/types.h>

enum abk_sched_profile_mode {
	ABK_SCHED_PROFILE_CONSERVATIVE = 0,
	ABK_SCHED_PROFILE_AGGRESSIVE = 1,
};

bool abk_sched_profile_aggressive(void);
enum abk_sched_profile_mode abk_sched_profile_get_mode(void);
int abk_sched_profile_set_mode(enum abk_sched_profile_mode mode);
unsigned int abk_sched_profile_decay_ewma(unsigned int ewma, unsigned int util);
unsigned long abk_sched_profile_scale_util(unsigned long util,
					   unsigned long cpu_capacity);
unsigned long abk_sched_profile_override_thermal_target(const char *type,
							unsigned long target,
							unsigned long max_state);

#endif /* _LINUX_ABK_SCHED_PROFILE_H */
