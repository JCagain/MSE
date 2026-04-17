import mapnode20 as sim
import serial
import time
import networkx as nx
import matplotlib
import matplotlib.pyplot as plt
from matplotlib.widgets import Button
from matplotlib.patches import Rectangle as MplRect

# Sign ESP (button + LED, sketch_apr8a.ino) — drives node 7 direction output
PORT_SIGN   = '/dev/ttyACM1'   # WSL/Linux
# Sensor ESP (DHT22 + SGP30, sensor.ino) — feeds real data into node 6
PORT_SENSOR = '/dev/ttyACM0'   # WSL/Linux — update to actual port when connected
BAUD = 9600
PUSH_INTERVAL = 5  # seconds between proactive direction pushes

SENSOR_NODE = 6  # node whose sim data is replaced by real sensor readings

# Thresholds for auto-deriving SENSOR_NODE stage from real readings
TEMP_FIRE  = 70.0   # °C  — at or above → FIRE
TEMP_MAYBE = 40.0   # °C  — at or above → MAYBE FIRE
CO2_FIRE   = 2000.0  # ppm — at or above → FIRE
CO2_MAYBE  = 1000.0  # ppm — at or above → MAYBE FIRE


def apply_sensor_data(temp, co2):
    """Overwrite SENSOR_NODE with real readings and auto-derive its stage."""
    sim.node_temp[SENSOR_NODE] = round(temp, 1)
    sim.node_co2[SENSOR_NODE]  = round(co2, 1)
    if temp >= TEMP_FIRE or co2 >= CO2_FIRE:
        stage = 'FIRE'
    elif temp >= TEMP_MAYBE or co2 >= CO2_MAYBE:
        stage = 'MAYBE FIRE'
    else:
        stage = 'NORMAL'
    sim.node_stage[SENSOR_NODE] = stage
    print(f"[sensor] Node {SENSOR_NODE}: {temp:.1f}°C  {co2:.0f}ppm  → {stage}")


def parse_sensor_line(line):
    """
    Parse a line from sensor.ino.
    Expected format: "23.5,412"  (temp°C, CO2 ppm)
    Returns (temp, co2) floats or None on parse/sensor error.
    """
    if line == "READ_ERROR" or line == "SGP30_INIT_FAIL" or line == "ALL_SENSORS_READY":
        print(f"[sensor] status: {line}")
        return None
    try:
        parts = line.split(',')
        if len(parts) != 2:
            return None
        return float(parts[0]), float(parts[1])
    except ValueError:
        print(f"[sensor] bad line: {line!r}")
        return None


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


_DEFAULT_XLIM = (-5, 12)
_DEFAULT_YLIM = (-2, 15)


def draw_full_result(fig, result, clicked_node=7, distress_info=None, sensor_live=False):
    print(f"[draw] scenario={result['scenario']} fire_node={result['fire_node']} "
          f"direction={result['direction']} distress={distress_info is not None}")

    # Preserve zoom state across redraws
    saved_xlim, saved_ylim = None, None
    if len(fig.axes) >= 2:
        ax_prev = fig.axes[1]
        xl, yl = ax_prev.get_xlim(), ax_prev.get_ylim()
        if xl != _DEFAULT_XLIM or yl != _DEFAULT_YLIM:
            saved_xlim, saved_ylim = xl, yl

    fig.clear()

    gs = fig.add_gridspec(1, 2, width_ratios=[1.45, 2.05])
    ax_info = fig.add_subplot(gs[0, 0])
    ax_map = fig.add_subplot(gs[0, 1])

    ax_info.axis('off')
    ax_map.set_aspect('equal', adjustable='box')
    ax_map.set_anchor('W')
    ax_map.axis('off')

    if saved_xlim is not None:
        ax_map.set_xlim(saved_xlim)
        ax_map.set_ylim(saved_ylim)
    else:
        ax_map.set_xlim(_DEFAULT_XLIM)
        ax_map.set_ylim(_DEFAULT_YLIM)

    edge_data = result["edge_data"]
    node_temp = result["node_temp"]
    node_co2  = result["node_co2"]
    fire_node = result["fire_node"]
    scenario  = result["scenario"]
    guide     = result["guide"]
    exits     = result["exits"]

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

    normal_nodes = [n for n in sim.all_nodes if n not in exits and sim.node_stage.get(n) == 'NORMAL']
    maybe_nodes  = [n for n in sim.all_nodes if n not in exits and sim.node_stage.get(n) == 'MAYBE FIRE']
    fire_nodes   = [n for n in sim.all_nodes if n not in exits and sim.node_stage.get(n) == 'FIRE']

    if normal_nodes:
        nx.draw_networkx_nodes(G, sim.node_positions, ax=ax_map,
                               nodelist=normal_nodes, node_size=500,
                               node_color='white', edgecolors='black')
    if maybe_nodes:
        nx.draw_networkx_nodes(G, sim.node_positions, ax=ax_map,
                               nodelist=maybe_nodes, node_size=500,
                               node_color='yellow', edgecolors='black')
    if fire_nodes:
        nx.draw_networkx_nodes(G, sim.node_positions, ax=ax_map,
                               nodelist=fire_nodes, node_size=700,
                               node_color='red', edgecolors='black')

    exit_normal = [n for n in exits if sim.node_stage.get(n) == 'NORMAL']
    exit_maybe  = [n for n in exits if sim.node_stage.get(n) == 'MAYBE FIRE']
    exit_fire   = [n for n in exits if sim.node_stage.get(n) == 'FIRE']

    if exit_normal:
        nx.draw_networkx_nodes(G, sim.node_positions, ax=ax_map,
                               nodelist=exit_normal, node_size=700,
                               node_color='lime', edgecolors='black')
    if exit_maybe:
        nx.draw_networkx_nodes(G, sim.node_positions, ax=ax_map,
                               nodelist=exit_maybe, node_size=700,
                               node_color='yellow', edgecolors='black')
    if exit_fire:
        nx.draw_networkx_nodes(G, sim.node_positions, ax=ax_map,
                               nodelist=exit_fire, node_size=700,
                               node_color='red', edgecolors='black')

    nx.draw_networkx_labels(
        G, sim.node_positions, ax=ax_map,
        labels={n: str(n) for n in sim.all_nodes}, font_size=10
    )

    edge_labels = {(u, v): f"{sim.physical_distances[(u, v)]}" for u, v in sim.edges_list}
    nx.draw_networkx_edge_labels(
        G, sim.node_positions, ax=ax_map,
        edge_labels=edge_labels, font_size=9
    )

    node7_stage = sim.node_stage.get(clicked_node, 'NORMAL')
    if node7_stage == 'NORMAL':
        warning_text = f"[OK] Node {clicked_node}: Clear"
        warning_color = "green"
        warning_fontweight = "normal"
    elif node7_stage == 'MAYBE FIRE':
        warning_text = f"[!] Node {clicked_node}: Maybe Fire"
        warning_color = "orange"
        warning_fontweight = "bold"
    else:
        warning_text = f"[FIRE] Node {clicked_node}: FIRE!"
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

    sensor_status = "LIVE" if sensor_live else "NO SIGNAL"
    sensor_color  = "green" if sensor_live else "gray"
    ax_map.set_title(
        f"Sign: Node {clicked_node}  |  Sensor: Node {SENSOR_NODE} [{sensor_status}]",
        fontsize=14, pad=10, color=sensor_color if not sensor_live else 'black'
    )

    import matplotlib.patches as mpatches
    import matplotlib.lines as mlines
    legend_handles = [
        mlines.Line2D([], [], marker='o', linestyle='None', markersize=10,
                      markerfacecolor='white',  markeredgecolor='black', label='Normal node'),
        mlines.Line2D([], [], marker='o', linestyle='None', markersize=10,
                      markerfacecolor='yellow', markeredgecolor='black', label='Maybe Fire node'),
        mlines.Line2D([], [], marker='o', linestyle='None', markersize=10,
                      markerfacecolor='red',    markeredgecolor='black', label='Fire node'),
        mlines.Line2D([], [], marker='o', linestyle='None', markersize=10,
                      markerfacecolor='lime',   markeredgecolor='black', label='Safe exit'),
        mlines.Line2D([], [], color='dodgerblue', linewidth=2,               label='Passable edge'),
        mlines.Line2D([], [], color='red',        linewidth=2, linestyle='--', label='Blocked edge'),
        mlines.Line2D([], [], color='lime',       linewidth=3,               label='Main path'),
        mlines.Line2D([], [], color='orange',     linewidth=3, linestyle='--', label='Backup path'),
    ]
    ax_map.legend(
        handles=legend_handles, loc='upper right', fontsize=8,
        framealpha=0.9, facecolor='wheat', edgecolor='gray',
        labelspacing=0.5, handletextpad=0.5
    )

    FONTSIZE = 8.5
    LINE_H = 0.022  # line height in axes-fraction units

    info_lines = []  # list of (text, bg_color_or_None, text_color_or_None)

    # Summary header
    info_lines.append((f"Sign Node : {clicked_node}", None, None))
    info_lines.append((f"Sensor Node: {SENSOR_NODE} ({'LIVE' if sensor_live else 'NO SIGNAL'})", None, None))
    if fire_node is not None:
        info_lines.append((f"Fire Node: {fire_node}", None, None))
    info_lines.append((f"Exits: {exits}", None, None))
    info_lines.append((f"Send Direction: {result['direction']}", None, None))
    info_lines.append(("(Blue rows = backup path used)", None, 'blue'))
    info_lines.append(("", None, None))

    # Node table
    info_lines.append(("=== Node Data & Escape Route ===", None, None))
    COL_NODE     = 7   # one extra char for [R] marker on sensor node
    COL_TEMP     = 10
    COL_CO2      = 11
    COL_MOVETIME = 14
    COL_COST     = 10
    COL_ROUTE    = 32

    header1 = (
        f"{'Node':<{COL_NODE}}"
        f"{'Temp(°C)':<{COL_TEMP}}"
        f"{'CO2(ppm)':<{COL_CO2}}"
        f"{'Time(s)':<{COL_MOVETIME}}"
        f"{'Cost':<{COL_COST}}"
        f"{'Path':<{COL_ROUTE}}"
    )
    info_lines.append((header1, None, None))
    info_lines.append(("-" * len(header1), None, None))

    for n in sorted(sim.all_nodes):
        stage = sim.node_stage.get(n, 'NORMAL')
        if stage == 'FIRE':
            row_bg = '#ffaaaa'
        elif stage == 'MAYBE FIRE':
            row_bg = '#ffff99'
        else:
            row_bg = None

        # Label real-sensor node with [R]
        node_label = f"{n}[R]" if (n == SENSOR_NODE and sensor_live) else str(n)

        if n in exits:
            line = (
                f"{node_label:<{COL_NODE}}"
                f"{node_temp[n]:<{COL_TEMP}.1f}"
                f"{node_co2[n]:<{COL_CO2}.1f}"
                f"{'0.0 (SAFE)':<{COL_MOVETIME}}"
                f"{'-':<{COL_COST}}"
                f"{'SAFE':<{COL_ROUTE}}"
            )
            info_lines.append((line, row_bg, None))
        else:
            _, n_main_path, n_main_cost, n_backup_path, n_backup_cost = guide[n]
            using_backup = (n_main_cost == "INF" and n_backup_cost != "INF")

            if not using_backup:
                route = "→".join(map(str, n_main_path)) if n_main_path else "NO PATH"
                cost  = f"{n_main_cost}" if isinstance(n_main_cost, str) else f"{n_main_cost:.2f}"
                time_str = f"{round(n_main_cost / sim.BASE_SPEED, 2):.2f}" if n_main_cost != "INF" else "INF"
            else:
                route = "→".join(map(str, n_backup_path)) if n_backup_path else "NO PATH"
                cost  = f"{n_backup_cost:.2f}"
                time_str = f"{round(n_backup_cost / sim.BASE_SPEED, 2):.2f}"

            line = (
                f"{node_label:<{COL_NODE}}"
                f"{node_temp[n]:<{COL_TEMP}.1f}"
                f"{node_co2[n]:<{COL_CO2}.1f}"
                f"{time_str:<{COL_MOVETIME}}"
                f"{cost:<{COL_COST}}"
                f"{route:<{COL_ROUTE}}"
            )
            info_lines.append((line, row_bg, 'blue' if using_backup else None))

    info_lines.append(("", None, None))

    # Edge table
    info_lines.append(("=== Edge Environment Data ===", None, None))
    COL_EDGE   = 10
    COL_LEN    = 8
    COL_ETEMP  = 10
    COL_ECO2   = 11
    COL_EFFLEN = 10

    header2 = (
        f"{'Edge':<{COL_EDGE}}"
        f"{'Len(m)':<{COL_LEN}}"
        f"{'AvgTemp':<{COL_ETEMP}}"
        f"{'AvgCO2':<{COL_ECO2}}"
        f"{'EffLen':<{COL_EFFLEN}}"
    )
    info_lines.append((header2, None, None))
    info_lines.append(("-" * len(header2), None, None))

    for a, b, L, T, C, W in sorted(edge_data):
        edge_str    = f"{a}-{b}"
        eff_len_str = f"{W:.2f}" if W != float('inf') else "INF"
        line = (
            f"{edge_str:<{COL_EDGE}}"
            f"{L:<{COL_LEN}.1f}"
            f"{T:<{COL_ETEMP}.1f}"
            f"{C:<{COL_ECO2}.1f}"
            f"{eff_len_str:<{COL_EFFLEN}}"
        )
        info_lines.append((line, None, None))

    # White background for the whole info panel
    ax_info.add_patch(MplRect(
        (0, 0), 1, 1, facecolor='white', alpha=0.98,
        edgecolor='#aaaaaa', lw=0.8,
        transform=ax_info.transAxes, zorder=0, clip_on=False
    ))

    for i, (text, bg, fg) in enumerate(info_lines):
        y_top = 0.99 - i * LINE_H
        if bg is not None:
            ax_info.add_patch(MplRect(
                (0, y_top - LINE_H), 1.0, LINE_H,
                facecolor=bg, alpha=0.85, lw=0,
                transform=ax_info.transAxes, clip_on=True, zorder=1
            ))
        if text:
            ax_info.text(
                0.01, y_top, text,
                fontsize=FONTSIZE, family='monospace', color=fg if fg else 'black',
                transform=ax_info.transAxes, va='top', zorder=2
            )

    if distress_info is not None:
        import datetime
        ts = datetime.datetime.fromtimestamp(distress_info["time"]).strftime("%H:%M:%S")
        banner = (
            f"[SOS] DISTRESS \u2014 Node {clicked_node} called for help"
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
    plt.subplots_adjust(bottom=0.10, wspace=0.02)

    if hasattr(fig, '_on_generate'):
        btn_ax = fig.add_axes([0.40, 0.02, 0.20, 0.06])
        btn = Button(btn_ax, 'Generate Scenario')
        btn.on_clicked(fig._on_generate)
        fig._generate_btn = btn  # prevent garbage collection

    fig._countdown_text = fig.text(
        0.99, 0.04, "",
        fontsize=16, ha='right', va='bottom', color='gray',
        transform=fig.transFigure,
    )

    plt.draw()


def main():
    matplotlib.use('TkAgg')

    # Sign ESP — required
    ser_sign = serial.Serial(PORT_SIGN, BAUD, timeout=1)
    time.sleep(2)

    # Sensor ESP — optional (may not be connected yet)
    ser_sensor = None
    try:
        ser_sensor = serial.Serial(PORT_SENSOR, BAUD, timeout=1)
        print(f"[sensor] Connected on {PORT_SENSOR}")
    except serial.SerialException as e:
        print(f"[sensor] Not available ({e}) — node {SENSOR_NODE} stays simulated")

    matplotlib.rcParams['toolbar'] = 'None'
    plt.ion()
    fig = plt.figure(figsize=(15, 8), dpi=100)

    last_push_time      = 0.0   # 0 forces an immediate push on startup
    distress_count      = 0
    distress_time       = None
    last_result         = None
    last_countdown_update = 0.0
    sensor_live         = False  # becomes True after first valid sensor reading

    def on_generate(event):
        nonlocal last_result, last_countdown_update
        sim.generate_all_random()
        last_result = compute_node7_result()
        di = {"count": distress_count, "time": distress_time} if distress_time is not None else None
        draw_full_result(fig, last_result, clicked_node=7, distress_info=di,
                         sensor_live=sensor_live)
        last_countdown_update = 0.0

    fig._on_generate = on_generate

    def on_node_click(event):
        nonlocal last_result, last_countdown_update
        print(f"[click] xdata={event.xdata} ydata={event.ydata} inaxes={event.inaxes}")
        if event.xdata is None or event.ydata is None:
            print("[click] ignored: outside axes")
            return
        if len(fig.axes) < 2 or event.inaxes != fig.axes[1]:
            print("[click] ignored: not in map axes")
            return
        min_dist, target = float('inf'), None
        for node, (x, y) in sim.node_positions.items():
            d = (event.xdata - x) ** 2 + (event.ydata - y) ** 2
            if d < min_dist and d < 0.8:
                min_dist, target = d, node
        if target is not None:
            if target == SENSOR_NODE and sensor_live:
                print(f"[click] node {target} is sensor-locked — manual cycle ignored")
            else:
                sim.cycle_node(target)
                print(f"[click] node {target} stage cycled to {sim.node_stage[target]}")
            last_result = compute_node7_result()
            di = {"count": distress_count, "time": distress_time} if distress_time is not None else None
            draw_full_result(fig, last_result, clicked_node=7, distress_info=di,
                             sensor_live=sensor_live)
            last_countdown_update = 0.0
        else:
            print(f"[click] no node within threshold (closest dist={min_dist:.3f})")

    fig.canvas.mpl_connect('button_press_event', on_node_click)

    def on_scroll(event):
        if event.inaxes is None:
            return
        ax = event.inaxes
        scale = 0.85 if event.button == 'up' else 1.0 / 0.85
        cx, cy = event.xdata, event.ydata
        xlim = ax.get_xlim()
        ylim = ax.get_ylim()
        ax.set_xlim([cx + (x - cx) * scale for x in xlim])
        ax.set_ylim([cy + (y - cy) * scale for y in ylim])
        fig.canvas.draw_idle()

    fig.canvas.mpl_connect('scroll_event', on_scroll)

    print("Python controller ready. Sign ESP on node 7, sensor ESP on node 6.")
    print(f"Pushing direction every {PUSH_INTERVAL}s. Click nodes to cycle stage.")

    try:
        while True:
            # --- Poll sensor ESP ---
            if ser_sensor is not None and ser_sensor.in_waiting > 0:
                raw = ser_sensor.readline().decode('utf-8', errors='replace').strip()
                if raw:
                    print(f"[sensor raw] {raw}")
                    parsed = parse_sensor_line(raw)
                    if parsed is not None:
                        temp, co2 = parsed
                        apply_sensor_data(temp, co2)
                        sensor_live = True

            # --- proactive push ---
            if time.time() - last_push_time >= PUSH_INTERVAL:
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
                                 distress_info=distress_info, sensor_live=sensor_live)
                plt.pause(0.1)

                ser_sign.write((last_result["direction"] + '\n').encode())
                ser_sign.flush()
                last_push_time = time.time()

            # --- Sign ESP serial read ---
            if ser_sign.in_waiting > 0:
                msg = ser_sign.readline().decode('utf-8').strip()
                if msg:
                    print(f"[sign] {msg}")

                if msg == "search":
                    now_t = time.time()
                    if distress_time is None or (now_t - distress_time) > 2.0:
                        distress_count += 1
                        distress_time = now_t
                        print(f"DISTRESS received (#{distress_count})")

                        if last_result is not None:
                            distress_info = {"count": distress_count, "time": distress_time}
                            draw_full_result(fig, last_result, clicked_node=7,
                                             distress_info=distress_info, sensor_live=sensor_live)
                            plt.pause(0.1)

            # --- Countdown update (once per second) ---
            now = time.time()
            if now - last_countdown_update >= 1.0:
                remaining = max(0, PUSH_INTERVAL - (now - last_push_time))
                if hasattr(fig, '_countdown_text'):
                    fig._countdown_text.set_text(f"Next push: {remaining:.0f}s")
                last_countdown_update = now

            if not plt.fignum_exists(fig.number):
                break
            plt.pause(0.05)

    finally:
        try:
            ser_sign.write(b"idle\n")
            ser_sign.flush()
        except Exception:
            pass
        ser_sign.close()
        if ser_sensor is not None:
            ser_sensor.close()


if __name__ == "__main__":
    main()
