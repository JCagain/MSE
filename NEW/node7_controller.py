import mapnode20 as sim
import serial
import time
import networkx as nx
import matplotlib
import matplotlib.pyplot as plt
from matplotlib.widgets import Button

# PORT = '/dev/cu.usbmodem5B5F0153401'  # macOS
PORT = '/dev/ttyACM0'  # WSL/Linux
BAUD = 9600


def compute_node7_result():
    clicked_node = 7

    edge_data, node_temp, node_co2, fire_node, scenario = sim.get_current_state()

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

    fire_nodes = {fire_node} if fire_node is not None else set()
    available_exits = [ex for ex in sim.all_possible_exits if ex not in fire_nodes]
    guide, exits = sim.compute_escape(G, G_physical, G_no_block, available_exits)
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


def draw_full_result(fig, result, clicked_node=7, distress_info=None):
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
        warning_text = "[OK] No Fire Detected"
        warning_color = "green"
        warning_fontweight = "normal"
    elif scenario == "NORMAL":
        warning_text = "[!] Maybe Fire (Elevated Levels)"
        warning_color = "orange"
        warning_fontweight = "bold"
    else:
        warning_text = "[FIRE] FIRE! RUN NOW!"
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

    if distress_info is not None:
        import datetime
        ts = datetime.datetime.fromtimestamp(distress_info["time"]).strftime("%H:%M:%S")
        banner = (
            f"[SOS] DISTRESS \u2014 Node 7 called for help"
            f"  (\u00d7{distress_info['count']})  [{ts}]"
        )
        ax_map.text(
            0.5, 0.015, banner,
            transform=ax_map.transAxes,
            fontsize=13,
            fontweight="bold",
            color="white",
            ha="center",
            va="bottom",
            bbox=dict(boxstyle="round,pad=0.4", facecolor="red", alpha=0.92),
        )

    plt.tight_layout()
    plt.subplots_adjust(bottom=0.10)

    if hasattr(fig, '_on_generate'):
        btn_ax = fig.add_axes([0.40, 0.02, 0.20, 0.06])
        btn = Button(btn_ax, 'Generate Scenario')
        btn.on_clicked(fig._on_generate)
        fig._generate_btn = btn  # prevent garbage collection

    fig._countdown_text = fig.text(
        0.99, 0.04, "",
        fontsize=9, ha='right', va='bottom', color='gray',
        transform=fig.transFigure,
    )

    plt.draw()


def main():
    matplotlib.use('TkAgg')
    ser = serial.Serial(PORT, BAUD, timeout=1)
    time.sleep(2)

    matplotlib.rcParams['toolbar'] = 'None'
    plt.ion()
    fig = plt.figure(figsize=(14, 7), dpi=100)

    last_push_time = 0.0   # 0 forces an immediate push on startup
    distress_count = 0
    distress_time = None
    last_result = None
    last_countdown_update = 0.0

    def on_generate(event):
        nonlocal last_result
        sim.generate_all_random()
        last_result = compute_node7_result()
        di = {"count": distress_count, "time": distress_time} if distress_time is not None else None
        draw_full_result(fig, last_result, clicked_node=7, distress_info=di)
        plt.pause(0.1)

    fig._on_generate = on_generate

    def on_node_click(event):
        nonlocal last_result
        if event.xdata is None or event.ydata is None:
            return
        if len(fig.axes) < 2 or event.inaxes != fig.axes[1]:
            return
        min_dist, target = float('inf'), None
        for node, (x, y) in sim.node_positions.items():
            d = (event.xdata - x) ** 2 + (event.ydata - y) ** 2
            if d < min_dist and d < 0.8:
                min_dist, target = d, node
        if target is not None:
            sim.cycle_node(target)
            last_result = compute_node7_result()
            di = {"count": distress_count, "time": distress_time} if distress_time is not None else None
            draw_full_result(fig, last_result, clicked_node=7, distress_info=di)
            plt.pause(0.1)

    fig.canvas.mpl_connect('button_press_event', on_node_click)

    print("Python controller ready. Pushing direction every 15s. Click nodes to cycle stage.")

    try:
        while True:
            # --- 15-second proactive push ---
            if time.time() - last_push_time >= 15:
                last_result = compute_node7_result()

                print("------ Scheduled Push ------")
                print(f"Scenario   : {last_result['scenario']}")
                print(f"Fire Node  : {last_result['fire_node']}")
                print(f"Path Type  : {last_result['path_type']}")
                print(f"Main Path  : {last_result['main_path']}")
                print(f"Backup Path: {last_result['backup_path']}")
                print(f"Send       : {last_result['direction']}")
                print("----------------------------")

                distress_info = (
                    {"count": distress_count, "time": distress_time}
                    if distress_time is not None else None
                )
                draw_full_result(fig, last_result, clicked_node=7,
                                 distress_info=distress_info)
                plt.pause(0.1)

                ser.write((last_result["direction"] + '\n').encode())
                ser.flush()
                last_push_time = time.time()

            # --- Serial read ---
            if ser.in_waiting > 0:
                msg = ser.readline().decode('utf-8').strip()
                if msg:
                    print(f"From ESP32: {msg}")

                if msg == "search":
                    distress_count += 1
                    distress_time = time.time()
                    print(f"DISTRESS received (#{distress_count})")

                    if last_result is not None:
                        distress_info = {"count": distress_count, "time": distress_time}
                        draw_full_result(fig, last_result, clicked_node=7,
                                         distress_info=distress_info)
                        plt.pause(0.1)

            # --- Countdown update (once per second) ---
            now = time.time()
            if now - last_countdown_update >= 1.0:
                remaining = max(0, 15 - (now - last_push_time))
                if hasattr(fig, '_countdown_text'):
                    fig._countdown_text.set_text(f"Next push: {remaining:.0f}s")
                    fig.canvas.draw_idle()
                last_countdown_update = now

            plt.pause(0.05)   # keep GUI responsive between iterations
    finally:
        try:
            ser.write(b"idle\n")
            ser.flush()
        except Exception:
            pass
        ser.close()


if __name__ == "__main__":
    main()
