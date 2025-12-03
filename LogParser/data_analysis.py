#!/usr/bin/env python3


import os
from os import PathLike, path

from scipy.stats import kruskal, shapiro

data_group_files = (
    "Joysticks_Monitor.csv",
    "Joysticks_HMD.csv",
    "Headset.csv",
)
column_names = (
    "Presence",
    "Spatial Presence",
    "Involvement",
    "Realness",
    "IPQ Score",
)


def read_data_from_files(target_dir: PathLike | str, group_files: tuple) -> dict:
    from plot_gps_track import read_csv

    result = {}
    for file_name in group_files:
        file_path = path.join(target_dir, file_name)
        real_file_prefix = file_name.rstrip(".csv")
        print(real_file_prefix)
        tmp_data_list = read_csv(file_path)

        result[real_file_prefix] = {
            x: [y[x] for y in tmp_data_list] for x in column_names
        }
    return result


def test_if_data_normally_distributed(
    dataset: dict, dimension: str
) -> tuple[bool, dict]:
    print(f"Shapiro test for {dimension}:")

    all_normally_distributed = True
    result = {}
    for group_name, group_value in dataset.items():
        stat, p = shapiro(group_value[dimension])
        # print(
        #     f"Group and dimension: {group_name} - {dimension}: W={stat:3f}, p={p:.3f}"
        # )
        result[group_name] = {"stat": stat, "p": p}
        all_normally_distributed &= p > 0.05
    return all_normally_distributed, result


def run_krushal_test(dataset: dict, dimension: str) -> tuple[bool, dict]:
    print(f"Krushal test on dimension: {dimension}")

    dimension_data = [value[dimension] for key, value in dataset.items()]
    stat, p = kruskal(*dimension_data)
    print(f"H = {stat:3f}")
    print(f"p-value = {p:3f}")

    return p > 0.05, {"stat": stat, "p": p}


if __name__ == "__main__":
    data = read_data_from_files(os.getcwd(), data_group_files)
    for dimension in column_names:
        all_normally_distributed, result = test_if_data_normally_distributed(
            data, dimension
        )
        if all_normally_distributed:
            # ANOVA test
            print(f"All data in {dimension} is normally distributed, p values:")
            [print(f"{x}: {y['p']}") for x, y in result.items()]
            pass
        else:
            print(f"Not all data in {dimension} is normally distributed, p values:")
            [print(f"{x}: {y['p']}") for x, y in result.items()]
            # krushal test
            # nsd, result = run_krushal_test(data, dimension)
        print("")
        nsd, result = run_krushal_test(data, dimension)
        if nsd:
            print(f"NO significant difference between data in {dimension}")
        else:
            print(f"Significant difference between data in {dimension}")

        print("\n")
