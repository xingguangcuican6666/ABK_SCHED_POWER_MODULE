// SPDX-License-Identifier: GPL-2.0-only
/*
 * Minimal backport of Qualcomm DDR cooling device with a small exported API
 * so the ABK runtime profile can raise/lower the DDR floor from kernel space.
 */
#include <linux/abk_ddr_cdev.h>
#include <linux/device.h>
#include <linux/err.h>
#include <linux/interconnect.h>
#include <linux/module.h>
#include <linux/mutex.h>
#include <linux/of_device.h>
#include <linux/platform_device.h>
#include <linux/slab.h>
#include <linux/thermal.h>

#define DDR_CDEV_NAME "ddr-cdev"

struct ddr_cdev {
	u32 cur_state;
	u32 max_state;
	struct thermal_cooling_device *cdev;
	struct icc_path *icc_path;
	struct device *dev;
	u32 *freq_table;
};

static DEFINE_MUTEX(abk_ddr_cdev_lock);
static struct ddr_cdev *abk_primary_ddr_cdev;

static int __ddr_set_cur_state(struct ddr_cdev *ddr_cdev, unsigned long state)
{
	int ret;

	if (state > ddr_cdev->max_state)
		return -EINVAL;

	if (ddr_cdev->cur_state == state)
		return 0;

	ret = icc_set_bw(ddr_cdev->icc_path, 0, ddr_cdev->freq_table[state]);
	if (ret < 0) {
		dev_err(ddr_cdev->dev, "Error placing DDR freq%u. err:%d\n",
			ddr_cdev->freq_table[state], ret);
		return ret;
	}

	ddr_cdev->cur_state = state;
	return 0;
}

bool abk_ddr_cdev_ready(void)
{
	bool ready;

	mutex_lock(&abk_ddr_cdev_lock);
	ready = abk_primary_ddr_cdev && abk_primary_ddr_cdev->icc_path;
	mutex_unlock(&abk_ddr_cdev_lock);

	return ready;
}
EXPORT_SYMBOL_GPL(abk_ddr_cdev_ready);

unsigned long abk_ddr_cdev_max_state(void)
{
	unsigned long max_state = 0;

	mutex_lock(&abk_ddr_cdev_lock);
	if (abk_primary_ddr_cdev)
		max_state = abk_primary_ddr_cdev->max_state;
	mutex_unlock(&abk_ddr_cdev_lock);

	return max_state;
}
EXPORT_SYMBOL_GPL(abk_ddr_cdev_max_state);

int abk_ddr_cdev_set_state(unsigned long state)
{
	struct ddr_cdev *ddr_cdev;
	int ret = -ENODEV;

	mutex_lock(&abk_ddr_cdev_lock);
	ddr_cdev = abk_primary_ddr_cdev;
	if (ddr_cdev)
		ret = __ddr_set_cur_state(ddr_cdev, state);
	mutex_unlock(&abk_ddr_cdev_lock);

	return ret;
}
EXPORT_SYMBOL_GPL(abk_ddr_cdev_set_state);

static int ddr_set_cur_state(struct thermal_cooling_device *cdev,
			     unsigned long state)
{
	struct ddr_cdev *ddr_cdev = cdev->devdata;

	return __ddr_set_cur_state(ddr_cdev, state);
}

static int ddr_get_cur_state(struct thermal_cooling_device *cdev,
			     unsigned long *state)
{
	struct ddr_cdev *ddr_cdev = cdev->devdata;

	*state = ddr_cdev->cur_state;
	return 0;
}

static int ddr_get_max_state(struct thermal_cooling_device *cdev,
			     unsigned long *state)
{
	struct ddr_cdev *ddr_cdev = cdev->devdata;

	*state = ddr_cdev->max_state;
	return 0;
}

static const struct thermal_cooling_device_ops ddr_cdev_ops = {
	.get_max_state = ddr_get_max_state,
	.get_cur_state = ddr_get_cur_state,
	.set_cur_state = ddr_set_cur_state,
};

static int ddr_cdev_probe(struct platform_device *pdev)
{
	struct device *dev = &pdev->dev;
	struct device_node *np = dev->of_node;
	struct ddr_cdev *ddr_cdev;
	u32 *freq_table;
	char cdev_name[THERMAL_NAME_LENGTH] = DDR_CDEV_NAME;
	int ret, opp_ct, bus_width = 1, idx;

	ddr_cdev = devm_kzalloc(dev, sizeof(*ddr_cdev), GFP_KERNEL);
	if (!ddr_cdev)
		return -ENOMEM;

	ddr_cdev->icc_path = of_icc_get(dev, NULL);
	if (IS_ERR(ddr_cdev->icc_path)) {
		ret = PTR_ERR(ddr_cdev->icc_path);
		if (ret != -EPROBE_DEFER)
			dev_err(dev, "Unable to register icc path: %d\n", ret);
		return ret;
	}

	if (!of_find_property(np, "qcom,freq-table", &opp_ct)) {
		dev_err(dev, "No DDR frequency entries\n");
		ret = -ENODEV;
		goto err_icc;
	}

	opp_ct /= sizeof(*freq_table);
	opp_ct++;
	if (opp_ct <= 1) {
		ret = -ENODEV;
		goto err_icc;
	}

	freq_table = devm_kcalloc(dev, opp_ct, sizeof(*freq_table), GFP_KERNEL);
	if (!freq_table) {
		ret = -ENOMEM;
		goto err_icc;
	}

	freq_table[0] = 0;
	ret = of_property_read_u32_array(np, "qcom,freq-table",
					 &freq_table[1], opp_ct - 1);
	if (ret < 0) {
		dev_err(dev, "DDR frequency read error:%d\n", ret);
		goto err_icc;
	}

	ret = of_property_read_u32(np, "qcom,bus-width", &bus_width);
	if (ret < 0) {
		dev_err(dev, "DDR bus width read error:%d\n", ret);
		goto err_icc;
	}

	for (idx = 0; idx < opp_ct; idx++)
		freq_table[idx] *= bus_width;

	ddr_cdev->freq_table = freq_table;
	ddr_cdev->cur_state = 0;
	ddr_cdev->max_state = opp_ct - 1;
	ddr_cdev->dev = dev;

	ret = icc_set_bw(ddr_cdev->icc_path, 0, freq_table[0]);
	if (ret < 0) {
		dev_err(dev, "Error placing DDR freq request. err:%d\n", ret);
		goto err_icc;
	}

	ddr_cdev->cdev = devm_thermal_of_cooling_device_register(dev, np,
						cdev_name, ddr_cdev,
						&ddr_cdev_ops);
	if (IS_ERR(ddr_cdev->cdev)) {
		ret = PTR_ERR(ddr_cdev->cdev);
		dev_err(dev, "Cdev register failed for %s, ret:%d\n",
			cdev_name, ret);
		goto err_icc;
	}

	dev_set_drvdata(dev, ddr_cdev);
	mutex_lock(&abk_ddr_cdev_lock);
	if (!abk_primary_ddr_cdev)
		abk_primary_ddr_cdev = ddr_cdev;
	mutex_unlock(&abk_ddr_cdev_lock);

	return 0;

err_icc:
	icc_put(ddr_cdev->icc_path);
	return ret;
}

static int ddr_cdev_remove(struct platform_device *pdev)
{
	struct ddr_cdev *ddr_cdev = dev_get_drvdata(&pdev->dev);

	mutex_lock(&abk_ddr_cdev_lock);
	if (abk_primary_ddr_cdev == ddr_cdev)
		abk_primary_ddr_cdev = NULL;
	mutex_unlock(&abk_ddr_cdev_lock);

	if (ddr_cdev && ddr_cdev->icc_path) {
		icc_set_bw(ddr_cdev->icc_path, 0, ddr_cdev->freq_table[0]);
		icc_put(ddr_cdev->icc_path);
		ddr_cdev->icc_path = NULL;
	}

	return 0;
}

static const struct of_device_id ddr_cdev_match[] = {
	{ .compatible = "qcom,ddr-cooling-device", },
	{},
};
MODULE_DEVICE_TABLE(of, ddr_cdev_match);

static struct platform_driver ddr_cdev_driver = {
	.probe = ddr_cdev_probe,
	.remove = ddr_cdev_remove,
	.driver = {
		.name = KBUILD_MODNAME,
		.of_match_table = ddr_cdev_match,
	},
};
module_platform_driver(ddr_cdev_driver);

MODULE_LICENSE("GPL");
