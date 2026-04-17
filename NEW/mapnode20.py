import matplotlib
matplotlib.use('TkAgg')
import matplotlib.pyplot as plt
from matplotlib.widgets import Button
import networkx as nx
import random

# ==============================================
# Core Parameters
# ==============================================
T_MIN = 25.0
T_MAX = 70.0
C_MIN = 400.0
C_MAX = 2000.0
ALPHA = 0.5
BETA = 0.5
BASE_SPEED = 1.2  # m/s

STAGES = ['NORMAL', 'MAYBE FIRE', 'FIRE']

def calculate_effective_length(physical_length, temp, co2, is_fire_node_edge):
    if is_fire_node_edge and (temp > T_MAX or co2 > C_MAX):
        return float('inf')
    c_norm = (co2 - C_MIN) / (C_MAX - C_MIN) if (C_MAX - C_MIN) != 0 else 0
    t_norm = (temp - T_MIN) / (T_MAX - T_MIN) if (T_MAX - T_MIN) != 0 else 0
    return physical_length * (1 + ALPHA * max(0, c_norm) + BETA * max(0, t_norm))

# ==============================================
# Topology & Physical Distances
# ==============================================
node_positions = {
    1: (10, 0),    2: (7.5, 0),   3: (5, 0),
    4: (2.5, 0),   5: (0, 0),     6: (0, 2),
    7: (0, 6),     8: (0, 10),   9: (2.5, 10),
    10: (5, 10),   11: (7.5, 10), 12: (10, 10),
    13: (10, 7),  14: (10, 4),   15: (-3, 2),
    16: (0, 13)
}

edges_list = [
    (1,2), (2,3), (3,4), (4,5),
    (5,6), (6,7), (7,8), (8,9),
    (9,10), (10,11), (11,12), (12,13), (13,14),
    (14,1), (15,6), (8,16)
]

physical_distances = {(u,v): 5.0 for u,v in edges_list}
physical_distances.update({(v,u): 5.0 for u,v in edges_list})
physical_distances[(5,6)]   = physical_distances[(6,5)]   = 2.0
physical_distances[(6,15)]  = physical_distances[(15,6)]  = 3.0
physical_distances[(6,7)]   = physical_distances[(7,6)]   = 4.0
physical_distances[(7,8)]   = physical_distances[(8,7)]   = 4.0
physical_distances[(8,16)]  = physical_distances[(16,8)]  = 3.0
physical_distances[(12,13)] = physical_distances[(13,12)] = 3.0
physical_distances[(13,14)] = physical_distances[(14,13)] = 3.0
physical_distances[(14,1)]  = physical_distances[(1,14)]  = 4.0

all_nodes = [1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16]
all_possible_exits = [1, 15, 16]

# ==============================================
# Persistent per-node state
# ==============================================
node_temp  = {n: round(random.uniform(18.0, T_MIN), 1) for n in all_nodes}
node_co2   = {n: round(random.uniform(300.0, C_MIN), 1) for n in all_nodes}
node_stage = {n: 'NORMAL' for n in all_nodes}
selected_node = None   # node whose evacuation path is highlighted

def _random_values_for_stage(stage):
    if stage == 'NORMAL':
        return random.uniform(T_MIN, 45.0), random.uniform(C_MIN, 800.0)
    elif stage == 'MAYBE FIRE':
        return random.uniform(45.0, T_MAX), random.uniform(800.0, C_MAX)
    else:  # FIRE
        return random.uniform(80.0, 100.0), random.uniform(2500.0, 3500.0)

def generate_all_random():
    """One random node is FIRE; all others are NORMAL or MAYBE FIRE."""
    fire_node = random.choice(all_nodes)
    for n in all_nodes:
        stage = 'FIRE' if n == fire_node else random.choice(['NORMAL', 'MAYBE FIRE'])
        node_stage[n] = stage
        t, c = _random_values_for_stage(stage)
        node_temp[n]  = round(t, 1)
        node_co2[n]   = round(c, 1)

def cycle_node(node):
    """Advance one node to its next stage (NORMAL->FIRE->NORMAL)."""
    current = node_stage.get(node, 'NORMAL')
    next_stage = STAGES[(STAGES.index(current) + 1) % len(STAGES)]
    node_stage[node] = next_stage
    t, c = _random_values_for_stage(next_stage)
    node_temp[node] = round(t, 1)
    node_co2[node]  = round(c, 1)

def _edge_data_from_state():
    edge_data = []
    for a, b in edges_list:
        is_fire_edge = (node_stage.get(a) == 'FIRE' or node_stage.get(b) == 'FIRE')
        et = round((node_temp[a] + node_temp[b]) / 2, 1)
        ec = round((node_co2[a]  + node_co2[b])  / 2, 1)
        if is_fire_edge:
            eff_len = float('inf')
        else:
            eff_len = calculate_effective_length(physical_distances[(a,b)], et, ec, is_fire_edge)
        edge_data.append((a, b, physical_distances[(a,b)], et, ec, eff_len))
    return edge_data

def _global_scenario():
    stages = set(node_stage.values())
    if 'FIRE'       in stages: return 'FIRE'
    if 'MAYBE FIRE' in stages: return 'MAYBE FIRE'
    return 'NORMAL'

def get_current_state():
    """Return current node stage state without randomizing."""
    fire_node = next((n for n in all_nodes if node_stage[n] == 'FIRE'), None)
    edge_data = _edge_data_from_state()
    scenario = _global_scenario()
    return edge_data, dict(node_temp), dict(node_co2), fire_node, scenario

def generate_fire_data(clicked_node):
    """Generate random fire scenario and return edge_data, node_temp, node_co2, fire_node, scenario."""
    generate_all_random()
    fire_node = None
    for n in all_nodes:
        if node_stage[n] == 'FIRE':
            fire_node = n
            break
    edge_data = _edge_data_from_state()
    scenario = _global_scenario()
    return edge_data, dict(node_temp), dict(node_co2), fire_node, scenario

# ==============================================
# Escape path computation
# ==============================================
def compute_escape(G, G_physical, G_no_block, available_exits):
    guide = {}
    for node in all_nodes:
        if node in available_exits:
            guide[node] = ("SAFE", [], 0.0, [], 0.0)
            continue

        best_cost, best_path = float('inf'), None
        for ex in available_exits:
            try:
                path = nx.dijkstra_path(G, node, ex, weight='weight')
                cost = nx.dijkstra_path_length(G, node, ex, weight='weight')
                if cost < best_cost:
                    best_cost, best_path = cost, path
            except: continue

        backup_cost, backup_path = float('inf'), None
        if best_cost == float('inf'):
            for ex in available_exits:
                try:
                    path = nx.dijkstra_path(G_no_block, node, ex, weight='weight')
                    cost = nx.dijkstra_path_length(G_no_block, node, ex, weight='weight')
                    if cost < backup_cost:
                        backup_cost, backup_path = cost, path
                except: continue

        guide[node] = (
            None,
            best_path   if best_path   else [],
            round(best_cost,   2) if best_cost   != float('inf') else "INF",
            backup_path if backup_path else [],
            round(backup_cost, 2) if backup_cost != float('inf') else "INF"
        )
    return guide, available_exits

# ==============================================
# Draw
# ==============================================
def draw(fig):
    fig.clear()
    gs = fig.add_gridspec(1, 2, width_ratios=[1.4, 2.1])
    ax_info = fig.add_subplot(gs[0, 0])
    ax_map  = fig.add_subplot(gs[0, 1])

    fig.ax_map  = ax_map
    fig.ax_info = ax_info

    ax_info.axis('off')
    ax_map.axis('off')
    ax_map.set_xlim(-5, 12)
    ax_map.set_ylim(-2, 15)

    edge_data = _edge_data_from_state()
    fire_nodes      = {n for n in all_nodes if node_stage.get(n) == 'FIRE'}
    available_exits = [ex for ex in all_possible_exits if ex not in fire_nodes]
    scenario        = _global_scenario()

    # Build graphs
    G          = nx.Graph()
    G_no_block = nx.Graph()
    G_physical = nx.Graph()
    G.add_nodes_from(all_nodes)
    G_no_block.add_nodes_from(all_nodes)
    G_physical.add_nodes_from(all_nodes)
    for u, v, L, T, C, W in edge_data:
        G.add_edge(u, v, weight=W)
        G_no_block.add_edge(u, v, weight=10000.0 if W == float('inf') else W)
        G_physical.add_edge(u, v, weight=L)

    guide, exits = compute_escape(G, G_physical, G_no_block, available_exits)

    # Draw edges
    normal_edges  = [(u,v) for u,v,d in G.edges(data=True) if d['weight'] != float('inf')]
    blocked_edges = [(u,v) for u,v,d in G.edges(data=True) if d['weight'] == float('inf')]
    nx.draw_networkx_edges(G, node_positions, ax=ax_map, edgelist=normal_edges,
                           edge_color='dodgerblue', width=1.5)
    nx.draw_networkx_edges(G, node_positions, ax=ax_map, edgelist=blocked_edges,
                           edge_color='red', width=4, style='--')

    # Draw selected node's evacuation path
    if selected_node is not None and selected_node not in fire_nodes:
        _, main_path, main_cost, backup_path, backup_cost = guide[selected_node]
        if main_cost != "INF" and len(main_path) >= 2:
            nx.draw_networkx_edges(G, node_positions, ax=ax_map,
                                   edgelist=list(zip(main_path, main_path[1:])),
                                   edge_color='lime', width=4, alpha=0.9)
        elif backup_cost != "INF" and len(backup_path) >= 2:
            nx.draw_networkx_edges(G, node_positions, ax=ax_map,
                                   edgelist=list(zip(backup_path, backup_path[1:])),
                                   edge_color='orange', width=4, alpha=0.9, style='--')

    # Node colours by stage
    normal_nodes     = [n for n in all_nodes if node_stage.get(n) == 'NORMAL']
    maybe_fire_nodes = [n for n in all_nodes if node_stage.get(n) == 'MAYBE FIRE']
    nx.draw_networkx_nodes(G, node_positions, ax=ax_map, nodelist=normal_nodes,
                           node_size=500, node_color='white', edgecolors='black')
    nx.draw_networkx_nodes(G, node_positions, ax=ax_map, nodelist=maybe_fire_nodes,
                           node_size=500, node_color='gold', edgecolors='black')
    nx.draw_networkx_nodes(G, node_positions, ax=ax_map, nodelist=list(fire_nodes),
                           node_size=500, node_color='red', edgecolors='black')
    nx.draw_networkx_nodes(G, node_positions, ax=ax_map, nodelist=available_exits,
                           node_size=700, node_color='lime', edgecolors='black')

    nx.draw_networkx_labels(G, node_positions, ax=ax_map,
                            labels={n: str(n) for n in all_nodes}, font_size=10)
    nx.draw_networkx_edge_labels(G, node_positions, ax=ax_map,
                                 edge_labels={(u,v): f"{physical_distances[(u,v)]}"
                                              for u,v in edges_list}, font_size=9)

    # Warning banner — shows selected node's stage; global scenario when none selected
    banner_stage = node_stage.get(selected_node) if selected_node is not None else scenario
    if banner_stage == 'FIRE':
        wtext, wcolor, wbold = "FIRE! RUN NOW!", "red", "bold"
    elif banner_stage == 'MAYBE FIRE':
        wtext, wcolor, wbold = "Maybe Fire (Elevated Levels)", "orange", "bold"
    else:
        wtext, wcolor, wbold = "Normal", "green", "normal"

    ax_map.text(0.5, 1.02, wtext, transform=ax_map.transAxes, fontsize=18,
                fontweight=wbold, color=wcolor, ha='center', va='bottom',
                bbox=dict(boxstyle='round,pad=0.5', facecolor='white', alpha=0.9))

    sel_label = f"  (selected: Node {selected_node})" if selected_node else ""
    ax_map.set_title(f"Scenario: {scenario}{sel_label}", fontsize=13, pad=40)

    ax_map.text(0.97, 0.97,
                "Red dashed = Blocked\nBlue = Passable\nGreen = Main Path\n"
                "Orange dashed = Backup\nGold = Maybe Fire node",
                transform=ax_map.transAxes, fontsize=8, va='top', ha='right',
                bbox=dict(boxstyle='round', facecolor='wheat', alpha=0.9))

    # Info panel
    fire_list = sorted(fire_nodes) if fire_nodes else []
    info  = f"Fire nodes : {fire_list}\n"
    info += f"Exits avail: {available_exits}\n"
    if selected_node:
        info += f"Selected   : Node {selected_node} [{node_stage[selected_node]}]\n"
    info += "\n"

    info += "=== Node Data & Escape Route ===\n"
    C0,C1,C2,C3,C4,C5,C6 = 6,10,11,15,12,20,20
    header = (f"{'Node':<{C0}}{'Temp':<{C1}}{'CO2':<{C2}}"
              f"{'Stage':<8}{'Time(s)':<{C3}}{'MainCost':<{C4}}"
              f"{'MainRoute':<{C5}}{'BackupRoute':<{C6}}")
    info += header + "\n" + "-" * len(header) + "\n"

    for n in sorted(all_nodes):
        stage_str = node_stage.get(n, 'SAFE')
        if n in exits:
            line = (f"{n:<{C0}}{node_temp[n]:<{C1}.1f}{node_co2[n]:<{C2}.1f}"
                    f"{'EXIT':<8}{'0.0':<{C3}}{'SAFE':<{C4}}"
                    f"{'-':<{C5}}{'-':<{C6}}")
        else:
            _, mp, mc, bp, bc = guide[n]
            mr = "->".join(map(str, mp)) if mp else "NO PATH"
            br = "->".join(map(str, bp)) if bp else "NO TRY"
            mc_s = str(mc) if isinstance(mc, str) else f"{mc:.2f}"
            if mc != "INF":
                t_str = f"{round(mc/BASE_SPEED,2):.2f}"
            elif bc != "INF":
                t_str = f"{round(bc/BASE_SPEED,2):.2f}(B)"
            else:
                t_str = "INF"
            line = (f"{n:<{C0}}{node_temp[n]:<{C1}.1f}{node_co2[n]:<{C2}.1f}"
                    f"{stage_str:<8}{t_str:<{C3}}{mc_s:<{C4}}"
                    f"{mr:<{C5}}{br:<{C6}}")
        info += line + "\n"

    info += "\n=== Edge Environment Data ===\n"
    h2 = f"{'Edge':<10}{'Len(m)':<8}{'AvgTemp':<10}{'AvgCO2':<11}{'EffLen':<10}"
    info += h2 + "\n" + "-" * len(h2) + "\n"
    for a, b, L, T, C, W in sorted(edge_data):
        info += (f"{f'{a}-{b}':<10}{L:<8.1f}{T:<10.1f}{C:<11.1f}"
                 f"{'INF' if W==float('inf') else f'{W:.2f}':<10}\n")

    ax_info.text(0.01, 0.99, info, fontsize=6.5, family='monospace',
                 transform=ax_info.transAxes, va='top',
                 bbox=dict(boxstyle='square,pad=0.3', facecolor='white', alpha=0.98, lw=0.8))

    ax_map.set_xlim(-5, 12)
    ax_map.set_ylim(-2, 15)
    _add_button(fig)
    fig.subplots_adjust(left=0.01, right=0.99, top=0.88, bottom=0.12, wspace=0.05)
    plt.draw()

# ==============================================
# Button
# ==============================================
def _add_button(fig):
    btn_ax = fig.add_axes([0.40, 0.02, 0.20, 0.06])
    btn = Button(btn_ax, 'Generate Scenario')
    btn.on_clicked(_on_generate_click)
    fig._generate_btn = btn  # prevent garbage collection

def _on_generate_click(event):
    generate_all_random()
    draw(fig)

# ==============================================
# Click Event — cycles clicked node's stage
# ==============================================
def onclick(event):
    global selected_node
    if not hasattr(fig, 'ax_map') or event.inaxes != fig.ax_map:
        return
    min_dist, target = float('inf'), None
    for node, (x, y) in node_positions.items():
        d = (event.xdata - x)**2 + (event.ydata - y)**2
        if d < min_dist and d < 0.8:
            min_dist, target = d, node
    if target is not None:
        cycle_node(target)
        selected_node = target
        draw(fig)

# ==============================================
# Main
# ==============================================
if __name__ == "__main__":
    global fig
    fig = plt.figure(figsize=(14, 7), dpi=100)
    fig.canvas.mpl_connect('button_press_event', onclick)
    draw(fig)
    plt.show()
