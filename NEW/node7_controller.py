import mapnode20 as sim
import serial
import time
import networkx as nx
import matplotlib
matplotlib.use('TkAgg')
import matplotlib.pyplot as plt

# PORT = '/dev/cu.usbmodem5B5F0153401'  # macOS
PORT = '/dev/ttyACM0'  # WSL/Linux
BAUD = 9600


def compute_node7_result():
    clicked_node = 7

    # 每次触发都重新随机一次
    edge_data, node_temp, node_co2, fire_node, scenario = sim.generate_fire_data(clicked_node)

    G = nx.Graph()
    G_no_block = nx.Graph()
    G_physical = nx.Graph()

    G.add_nodes_from(sim.all_nodes)
    G_no_block.add_nodes_from(sim.all_nodes)
    G_physical.add_nodes_from(sim.all_nodes)

    for u, v, L, T, C, W in edge_data:
        G.add_edge(u, v, weight=W)
        if W == float('inf'):
            G_no_block.add_edge(u, v, weight=10000.0)
        else:
            G_no_block.add_edge(u, v, weight=W)
        G_physical.add_edge(u, v, weight=L)

    guide, exits = sim.compute_escape(G, G_physical, G_no_block, fire_node)
    _, main_path, main_cost, backup_path, backup_cost = guide[clicked_node]

    # 当前 node7：优先主路径；不行就 backup；都不行默认 right
    if main_cost != "INF" and len(main_path) >= 2:
        chosen_path = main_path
        path_type = "MAIN"
    elif backup_cost != "INF" and len(backup_path) >= 2:
        chosen_path = backup_path
        path_type = "BACKUP"
    else:
        return {
            "direction": "right",
            "scenario": scenario,
            "fire_node": fire_node,
            "main_path": main_path,
            "backup_path": backup_path,
            "path_type": "FALLBACK",
            "edge_data": edge_data,
            "node_temp": node_temp,
            "node_co2": node_co2,
            "guide": guide,
            "exits": exits,
        }

    final_exit = chosen_path[-1]

    if final_exit == 15:
        direction = "left"
    elif final_exit == 16:
        direction = "right"
    else:
        direction = "right"

    return {
        "direction": direction,
        "scenario": scenario,
        "fire_node": fire_node,
        "main_path": main_path,
        "backup_path": backup_path,
        "path_type": path_type,
        "edge_data": edge_data,
        "node_temp": node_temp,
        "node_co2": node_co2,
        "guide": guide,
        "exits": exits,
    }


def draw_full_result(fig, result, clicked_node=7):
    fig.clear()

    gs = fig.add_gridspec(1, 2, width_ratios=[1.45, 2.05])
    ax_info = fig.add_subplot(gs[0, 0])
    ax_map = fig.add_subplot(gs[0, 1])

    ax_info.axis('off')
    ax_map.set_aspect('equal', adjustable='box')
    ax_map.axis('off')

    ax_map.set_xlim(-5, 12)
    ax_map.set_ylim(-2, 15)

    edge_data = result["edge_data"]
    node_temp = result["node_temp"]
    node_co2 = result["node_co2"]
    fire_node = result["fire_node"]
    scenario = result["scenario"]
    guide = result["guide"]
    exits = result["exits"]

    G = nx.Graph()
    G.add_nodes_from(sim.all_nodes)

    for u, v, L, T, C, W in edge_data:
        G.add_edge(u, v, weight=W)

    normal_edges, blocked_edges = [], []
    for u, v, d in G.edges(data=True):
        if d["weight"] == float('inf'):
            blocked_edges.append((u, v))
        else:
            normal_edges.append((u, v))

    nx.draw_networkx_edges(
        G, sim.node_positions, ax=ax_map,
        edgelist=normal_edges, edge_color='dodgerblue', width=1.5
    )
    nx.draw_networkx_edges(
        G, sim.node_positions, ax=ax_map,
        edgelist=blocked_edges, edge_color='red', width=4, style='--'
    )

    _, main_path, main_cost, backup_path, backup_cost = guide[clicked_node]

    if main_cost != "INF" and len(main_path) >= 2:
        path_edges = list(zip(main_path, main_path[1:]))
        nx.draw_networkx_edges(
            G, sim.node_positions, ax=ax_map,
            edgelist=path_edges, edge_color='lime', width=4, alpha=0.9
        )
    elif backup_cost != "INF" and len(backup_path) >= 2:
        path_edges = list(zip(backup_path, backup_path[1:]))
        nx.draw_networkx_edges(
            G, sim.node_positions, ax=ax_map,
            edgelist=path_edges, edge_color='orange', width=4, alpha=0.9, style='--'
        )

    nx.draw_networkx_nodes(
        G, sim.node_positions, ax=ax_map,
        nodelist=sim.all_nodes, node_size=500,
        node_color='white', edgecolors='black'
    )
    nx.draw_networkx_nodes(
        G, sim.node_positions, ax=ax_map,
        nodelist=exits, node_size=700,
        node_color='lime', edgecolors='black'
    )

    if fire_node is not None:
        nx.draw_networkx_nodes(
            G, sim.node_positions, ax=ax_map,
            nodelist=[fire_node], node_size=900,
            node_color='red', edgecolors='black'
        )

    nx.draw_networkx_labels(
        G, sim.node_positions, ax=ax_map,
        labels={n: str(n) for n in sim.all_nodes}, font_size=10
    )

    edge_labels = {(u, v): f"{sim.physical_distances[(u, v)]}" for u, v in sim.edges_list}
    nx.draw_networkx_edge_labels(
        G, sim.node_positions, ax=ax_map,
        edge_labels=edge_labels, font_size=9
    )

    if scenario == "SAFE":
        warning_text = "✅ No Fire Detected"
        warning_color = "green"
        warning_fontweight = "normal"
    elif scenario == "NORMAL":
        warning_text = "⚠️ Maybe Fire (Elevated Levels)"
        warning_color = "orange"
        warning_fontweight = "bold"
    else:
        warning_text = "🔥 FIRE! RUN NOW!"
        warning_color = "red"
        warning_fontweight = "bold"

    ax_map.text(
        0.5, 0.985, warning_text,
        transform=ax_map.transAxes,
        fontsize=18,
        fontweight=warning_fontweight,
        color=warning_color,
        ha='center',
        va='top',
        bbox=dict(boxstyle='round,pad=0.45', facecolor='white', alpha=0.9)
    )

    ax_map.set_title(f"Clicked Node: {clicked_node}", fontsize=14, pad=10)

    ax_map.text(
        0.97, 0.90,
        "Red dashed = Blocked\nBlue = Passable\nGreen = Main Path\nOrange dashed = Backup Path",
        transform=ax_map.transAxes, fontsize=9, va='top', ha='right',
        bbox=dict(boxstyle='round', facecolor='wheat', alpha=0.9)
    )

    info = f"Clicked Node: {clicked_node}\n"
    if fire_node is not None:
        info += f"Fire Node: {fire_node}\n"
    info += f"Exits: {exits}\n"
    info += f"Send Direction: {result['direction']}\n"
    info += f"Path Type: {result['path_type']}\n\n"

    info += "=== Node Data & Escape Route ===\n"
    COL_NODE = 6
    COL_TEMP = 10
    COL_CO2 = 11
    COL_MOVETIME = 15
    COL_COST = 12
    COL_ROUTE = 20
    COL_BACKUP = 20

    header1 = (
        f"{'Node':<{COL_NODE}}"
        f"{'Temp(°C)':<{COL_TEMP}}"
        f"{'CO2(ppm)':<{COL_CO2}}"
        f"{'Move Time(s)':<{COL_MOVETIME}}"
        f"{'MainCost':<{COL_COST}}"
        f"{'MainRoute':<{COL_ROUTE}}"
        f"{'BackupRoute':<{COL_BACKUP}}"
    )
    info += header1 + "\n"
    info += "-" * len(header1) + "\n"

    for n in sorted(sim.all_nodes):
        if n in exits:
            line = (
                f"{n:<{COL_NODE}}"
                f"{node_temp[n]:<{COL_TEMP}.1f}"
                f"{node_co2[n]:<{COL_CO2}.1f}"
                f"{'0.0 (SAFE)':<{COL_MOVETIME}}"
                f"{'-':<{COL_COST}}"
                f"{'SAFE':<{COL_ROUTE}}"
                f"{'-':<{COL_BACKUP}}"
            )
        else:
            _, n_main_path, n_main_cost, n_backup_path, n_backup_cost = guide[n]
            main_route = "→".join(map(str, n_main_path)) if n_main_path else "NO PATH"
            backup_route = "→".join(map(str, n_backup_path)) if n_backup_path else "NO TRY"

            main_cost_str = f"{n_main_cost}" if isinstance(n_main_cost, str) else f"{n_main_cost:.2f}"

            if n_main_cost != "INF":
                move_time = round(n_main_cost / sim.BASE_SPEED, 2)
                time_str = f"{move_time:.2f}"
            elif n_backup_cost != "INF":
                move_time = round(n_backup_cost / sim.BASE_SPEED, 2)
                time_str = f"{move_time:.2f} (Backup)"
            else:
                time_str = "INF"

            line = (
                f"{n:<{COL_NODE}}"
                f"{node_temp[n]:<{COL_TEMP}.1f}"
                f"{node_co2[n]:<{COL_CO2}.1f}"
                f"{time_str:<{COL_MOVETIME}}"
                f"{main_cost_str:<{COL_COST}}"
                f"{main_route:<{COL_ROUTE}}"
                f"{backup_route:<{COL_BACKUP}}"
            )
            info += line + "\n"

    info += "\n"
    info += "=== Edge Environment Data ===\n"
    COL_EDGE = 10
    COL_LEN = 8
    COL_ETEMP = 10
    COL_ECO2 = 11
    COL_EFFLEN = 10

    header2 = (
        f"{'Edge':<{COL_EDGE}}"
        f"{'Len(m)':<{COL_LEN}}"
        f"{'AvgTemp':<{COL_ETEMP}}"
        f"{'AvgCO2':<{COL_ECO2}}"
        f"{'EffLen':<{COL_EFFLEN}}"
    )
    info += header2 + "\n"
    info += "-" * len(header2) + "\n"

    for a, b, L, T, C, W in sorted(edge_data):
        edge_str = f"{a}-{b}"
        eff_len_str = f"{W:.2f}" if W != float('inf') else "INF"
        line = (
            f"{edge_str:<{COL_EDGE}}"
            f"{L:<{COL_LEN}.1f}"
            f"{T:<{COL_ETEMP}.1f}"
            f"{C:<{COL_ECO2}.1f}"
            f"{eff_len_str:<{COL_EFFLEN}}"
        )
        info += line + "\n"

    ax_info.text(
        0.01, 0.99, info,
        fontsize=6.5, family='monospace',
        transform=ax_info.transAxes, va='top',
        bbox=dict(boxstyle='square,pad=0.3', facecolor='white', alpha=0.98, lw=0.8)
    )

    plt.tight_layout()
    plt.draw()


def main():
    ser = serial.Serial(PORT, BAUD, timeout=1)
    time.sleep(2)

    plt.ion()
    fig = plt.figure(figsize=(14, 7), dpi=100)

    print("Python controller ready. Press the button on ESP32.")

    while True:
        if ser.in_waiting > 0:
            msg = ser.readline().decode('utf-8').strip()

            if msg:
                print(f"From ESP32: {msg}")

            if msg == "search":
                result = compute_node7_result()

                print("------ New Search Triggered ------")
                print(f"Scenario   : {result['scenario']}")
                print(f"Fire Node  : {result['fire_node']}")
                print(f"Path Type  : {result['path_type']}")
                print(f"Main Path  : {result['main_path']}")
                print(f"Backup Path: {result['backup_path']}")
                print(f"Send       : {result['direction']}")
                print("----------------------------------")

                draw_full_result(fig, result, clicked_node=7)
                plt.pause(0.1)

                ser.write((result["direction"] + '\n').encode())
                ser.flush()


if __name__ == "__main__":
    main()
