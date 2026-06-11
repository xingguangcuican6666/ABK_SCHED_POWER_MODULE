/* SPDX-License-Identifier: GPL-2.0 */
#ifndef _LINUX_ABK_DDR_CDEV_H
#define _LINUX_ABK_DDR_CDEV_H

#include <linux/errno.h>
#include <linux/types.h>

#if IS_ENABLED(CONFIG_QTI_DDR_COOLING_DEVICE)
bool abk_ddr_cdev_ready(void);
unsigned long abk_ddr_cdev_max_state(void);
int abk_ddr_cdev_set_state(unsigned long state);
#else
static inline bool abk_ddr_cdev_ready(void)
{
	return false;
}

static inline unsigned long abk_ddr_cdev_max_state(void)
{
	return 0;
}

static inline int abk_ddr_cdev_set_state(unsigned long state)
{
	return -ENODEV;
}
#endif

#endif /* _LINUX_ABK_DDR_CDEV_H */
