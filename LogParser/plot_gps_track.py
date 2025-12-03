import datetime
import os
import re
from csv import DictReader
from math import atan2, cos, degrees, radians, sin

import matplotlib.pyplot as plt
from geopy.distance import geodesic


def read_csv(file_path):
    """Reads a CSV file and returns the data as a list of lists."""
    with open(file_path, "r") as file:
        dict_reader = DictReader(file)
        return list(dict_reader)


def plot_gps_data():
    # result = read_csv("36748412_2025_08_06_16_48_10.csv")
    # result = read_csv("36757301_2025_08_06_16_24_37.csv")
    # result = read_csv("37427597_2025_08_06_15_51_43.csv")
    # result = read_csv("GPS_36757301_2025_11_22_16_58_49.csv")
    result = read_csv("GPS_36757301_2025_11_23_17_19_55.csv")

    start_timestamp = float(result[0]["UPDATETIMESTAMP"])
    end_timestamp = float(result[-1]["UPDATETIMESTAMP"])

    tracking_time = (end_timestamp - start_timestamp) / 1000  # in seconds
    gps_returning_frequency = len(result) / tracking_time  # in Hz
    print("GPS returning frequency:", gps_returning_frequency)
    print("Tracking time:", tracking_time)
    print(
        f"From {datetime.datetime.fromtimestamp(start_timestamp / 1000).isoformat()} to {datetime.datetime.fromtimestamp(end_timestamp / 1000).isoformat()}"
    )

    # 1. List of GPS positions (latitude, longitude)

    def _get_point_color(point):
        log_mark = point["LOGMARK"]
        if "Origin" in log_mark:
            return "green"
        if "North" in log_mark:
            return "orange"
        if "East" in log_mark:
            return "blue"
        if "-- Forward" in log_mark:
            return "orange"
        if "-- Right" in log_mark:
            return "blue"
        return "blue"

    gps_points = [
        (float(x["DRONELATITUDE"]), float(x["DRONELONGITUDE"]), _get_point_color(x))
        for x in result  # [:600]
        if "Fixed Point" in x["LOGMARK"]
        # if "Linear Position" in x["LOGMARK"]
    ]

    # 2. Choose an origin
    origin = (
        float(result[0]["BENCHMARKLATITUDE"]),
        float(result[0]["BENCHMARKLONGITUDE"]),
    )

    # 3. Function to compute bearing from origin to a point
    def calculate_bearing(start, end):
        lat1, lon1 = radians(start[0]), radians(start[1])
        lat2, lon2 = radians(end[0]), radians(end[1])
        dlon = lon2 - lon1

        x = sin(dlon) * cos(lat2)
        y = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dlon)

        bearing = atan2(x, y)
        bearing = degrees(bearing)
        return (bearing + 360) % 360  # Normalize to 0–360°

    # 4. Convert GPS to polar coords (distance, bearing)
    polar_points = []
    for point in gps_points[1:]:  # Skip origin itself
        distance = geodesic(origin, point[:-1]).meters
        bearing = calculate_bearing(origin, point[:-1])
        polar_points.append((distance, bearing, point))

    # 5. Convert polar (distance, bearing) → Cartesian (x, y)
    xy_points = []
    for distance, bearing, point in polar_points:
        angle_rad = radians(bearing)
        x = distance * cos(angle_rad)
        y = distance * sin(angle_rad)
        xy_points.append((x, y, point))

    # 6. Plot in 2D
    plt.figure(figsize=(10, 10))
    plt.axhline(0, color="gray", linestyle="--")
    plt.axvline(0, color="gray", linestyle="--")

    # Origin A at (0,0)
    plt.plot(0, 0, "ro")
    plt.text(-0.1, -0.2, "A", fontsize=23, ha="right")

    # B at (5, 0)
    plt.plot(5, 0, "ro")
    plt.text(5.1, -0.2, "C", fontsize=23, ha="left")

    # C at (0, 5)
    plt.plot(0, 5, "ro")
    plt.text(-0.1, 5.05, "B", fontsize=23, ha="right")

    # Plot all other points
    for x, y, point in xy_points:
        plt.plot(y, x, "bo", color=point[2])
        # plt.text(x, y, f"{point}", fontsize=9)

    plt.xlabel("East-West (m)", fontsize=23)
    plt.ylabel("North-South (m)", fontsize=23)
    plt.axis("equal")
    plt.tick_params(axis="both", labelsize=22)
    plt.grid(True)
    plt.legend()
    plt.show()


def plot_gps_data_new():
    pass


def plot_position_data_based_on_velocity():
    log_file = os.path.join(
        os.getcwd(), "drone_position_changes_based_on_velocity_2025_11_22_20_16.log"
    )

    from log_parse import read_file_line_by_line

    log_divider = [
        {"key": "A", "pattern": "Velocity Go Forward ----> Start"},
        {"key": "A-B", "pattern": "Velocity Go Forward ----> Stop"},
        {"key": "B", "pattern": "Velocity Go Right ----> Start"},
        {"key": "B-C", "pattern": "Velocity Go Right ----> Stop"},
        {"key": "C", "pattern": "Velocity Go Back ----> Start"},
        {"key": "C-D", "pattern": "Velocity Go Back ----> Stop"},
        {"key": "D", "pattern": "Velocity Go Left ----> Start"},
        {"key": "D-A", "pattern": "Velocity Go Left ----> Stop"},
        {"key": "A'", "pattern": "Velocity Go End ----> Start"},
    ]

    instant_data = {}
    last_parsed_instant_data = None

    average_data = {}
    last_parsed_average_data = None

    divider_index = 0

    def _line_parser(line: str):
        nonlocal divider_index
        nonlocal last_parsed_average_data
        nonlocal last_parsed_instant_data
        nonlocal instant_data
        nonlocal average_data

        divider = log_divider[divider_index]

        if divider["pattern"] in line:
            # switch to next divider
            divider_index = divider_index + 1
            last_parsed_average_data = None
            last_parsed_instant_data = None
            return
        else:
            if divider["key"] not in instant_data:
                instant_data[divider["key"]] = {"x": [], "y": []}
            if divider["key"] not in average_data:
                average_data[divider["key"]] = {"x": [], "y": []}
            current_instant_data_list = instant_data[divider["key"]]
            current_average_data_list = average_data[divider["key"]]

            regex_pattern = r"instant\sposition:\s\((?P<i_x>.*?),\s(?P<i_y>.*?)\).*position:\s\((?P<a_x>.*?),\s(?P<a_y>.*?)\)"
            result = re.search(regex_pattern, line)
            if result:
                i_x = float(result["i_x"].strip())
                i_y = float(result["i_y"].strip())
                a_x = float(result["a_x"].strip())
                a_y = float(result["a_y"].strip())

                if last_parsed_instant_data:
                    if (
                        abs(i_x - last_parsed_instant_data["x"]) < 0.01
                        and abs(i_y - last_parsed_instant_data["y"]) < 0.01
                    ):
                        pass
                    else:
                        current_instant_data_list["x"].append(i_x)
                        current_instant_data_list["y"].append(i_y)
                        last_parsed_instant_data = {"x": i_x, "y": i_y}
                else:
                    current_instant_data_list["x"].append(i_x)
                    current_instant_data_list["y"].append(i_y)
                    last_parsed_instant_data = {"x": i_x, "y": i_y}

                if last_parsed_average_data:
                    if (
                        abs(a_x - last_parsed_average_data["x"]) < 0.01
                        and abs(a_y - last_parsed_average_data["x"]) < 0.01
                    ):
                        pass
                    else:
                        current_average_data_list["x"].append(a_x)
                        current_average_data_list["y"].append(a_y)
                        last_parsed_average_data = {"x": a_x, "y": a_y}
                else:
                    current_average_data_list["x"].append(a_x)
                    current_average_data_list["y"].append(a_y)
                    last_parsed_average_data = {"x": a_x, "y": a_y}
        pass

    read_file_line_by_line(log_file, _line_parser)
    print(divider_index)
    # plot the data

    instant_x = []
    instant_y = []

    average_x = []
    average_y = []

    for divider in log_divider:
        instant_x.extend(instant_data[divider["key"]]["x"])
        instant_y.extend(instant_data[divider["key"]]["y"])
        average_x.extend(average_data[divider["key"]]["x"])
        average_y.extend(average_data[divider["key"]]["y"])

    plt.plot(
        instant_x,
        instant_y,
        color="orange",
        label="Position Change (Instant Velocity)",
        linestyle="--",
    )
    # plt.plot(
    #     average_x,
    #     average_y,
    #     color="red",
    #     label="Position Change (Average Velocity)",
    #     linestyle=":",
    # )

    plt.xlabel("X", fontsize=20)
    plt.ylabel("Y", fontsize=20)
    ax = plt.gca()
    plt.rcParams["font.size"] = "20"
    plt.tick_params(axis="both", labelsize=15)
    ax.set_xlim(-0.5, 2.5)
    ax.set_ylim(-0.5, 2.5)
    plt.tight_layout()
    plt.show()
    pass


if __name__ == "__main__":
    plot_gps_data()
    # plot_position_data_based_on_velocity()
    # plot_gps_data_new()
