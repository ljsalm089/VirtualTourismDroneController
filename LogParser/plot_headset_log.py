#!/usr/bin/env python3


import os
from turtle import color

from matplotlib import pyplot as plt


def parse_data_from_log_file(file: str) -> tuple[list, list]:
    import re

    from log_parse import read_file_line_by_line

    position = []
    rotation = []

    def _tmp_callback(line: str):
        if "raw position" in line:
            pattern_regex = r"raw position->\sx:(?P<raw_x>.*?),\sy:(?P<raw_y>.*?),\sz:(?P<raw_z>.*?),.*x:\s\((?P<smooth_x>.*?),.*,\sy:(?P<smooth_y>.*?),\sz:(?P<smooth_z>.*)"
            result = re.search(pattern_regex, line)
            if result:
                nonlocal position
                position.append(
                    {
                        "raw_x": float(result["raw_x"].strip()),
                        "raw_y": float(result["raw_y"].strip()),
                        "raw_z": float(result["raw_z"].strip()),
                        "smooth_x": float(result["smooth_x"].strip()),
                        "smooth_y": float(result["smooth_y"].strip()),
                        "smooth_z": float(result["smooth_z"].strip()),
                    }
                )
        elif "raw rotation" in line:
            pattern_regex = r"raw rotation->\sy:(?P<raw_y>.*?),.*y:(?P<smooth_y>.*)"
            result = re.search(pattern_regex, line)
            if result:
                nonlocal rotation
                rotation.append(
                    {
                        "raw_y": float(result["raw_y"].strip()),
                        "smooth_y": float(result["smooth_y"].strip()),
                    }
                )
        pass

    read_file_line_by_line(file, _tmp_callback)

    return position, rotation


def plot_rotations_to_chart(rotations: list, chart_file_name: str):
    plt.clf()
    plt.figure(figsize=(10, 6))
    plt.title("Headset Rotation Changes", fontsize=22)
    plt.xlabel("Time Frame", fontsize=20)
    plt.ylabel("Angle (°)", fontsize=20)
    x_data = [x for x in range(len(rotations))]

    def format_angle(angle: float) -> float:
        return angle - 360 if angle > 180 else angle

    plt.plot(
        x_data, [format_angle(x["raw_y"]) for x in rotations], color="b", label="raw y"
    )

    plt.plot(
        x_data,
        [format_angle(x["smooth_y"]) for x in rotations],
        color="m",
        label="smooth y",
    )

    plt.legend(fontsize=18)
    plt.tick_params(axis="both", labelsize=18)
    plt.tight_layout()
    plt.savefig(chart_file_name, dpi=300)


def plot_positions_to_chart(positions: list, chart_file_name: str):
    plt.clf()
    plt.figure(figsize=(10, 6))
    plt.title("Headset Position Changes", fontsize=22)
    plt.xlabel("Time Frame", fontsize=20)
    plt.ylabel("Position (meters from origin)", fontsize=20)
    x_data = [x for x in range(len(positions))]
    plt.plot(x_data, [x["raw_x"] for x in positions], color="b", label="raw x")

    plt.plot(x_data, [x["raw_z"] for x in positions], color="r", label="raw z")

    plt.plot(x_data, [x["smooth_x"] for x in positions], color="c", label="smooth x")

    plt.plot(x_data, [x["smooth_z"] for x in positions], color="y", label="smooth z")
    plt.legend(fontsize=18)
    plt.tick_params(axis="both", labelsize=18)
    plt.tight_layout()
    plt.savefig(chart_file_name, dpi=300)


if __name__ == "__main__":
    log_file_name = "headset_2025-11-18-09-44.log"
    print(f"Start parsing log file: {log_file_name}")
    positions, rotations = parse_data_from_log_file(log_file_name)

    print("Rotation:")
    [print(f"{x['raw_y']}\t{x['smooth_y']}") for x in rotations]

    plot_positions_to_chart(
        positions, os.path.join(os.getcwd(), "images", "headset_position_changes.png")
    )
    plot_rotations_to_chart(
        rotations, os.path.join(os.getcwd(), "images", "headset_rotation_changes.png")
    )
