import os
os.environ['MPLBACKEND'] = 'Agg'   # must be before any matplotlib import

import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import matplotlib.pyplot as plt
import networkx as nx
import mapnode20 as sim
from node7_controller import draw_full_result


def _make_result():
    """Build a minimal result dict the same way node7_controller does."""
    edge_data, node_temp, node_co2, fire_node, scenario = sim.generate_fire_data(7)

    G = nx.Graph()
    G_no_block = nx.Graph()
    G_physical = nx.Graph()
    G.add_nodes_from(sim.all_nodes)
    G_no_block.add_nodes_from(sim.all_nodes)
    G_physical.add_nodes_from(sim.all_nodes)

    for u, v, L, T, C, W in edge_data:
        G.add_edge(u, v, weight=W)
        G_no_block.add_edge(u, v, weight=W if W != float('inf') else 10000.0)
        G_physical.add_edge(u, v, weight=L)

    # Compute available exits (exclude any exits that are on fire)
    fire_nodes = {fire_node} if fire_node else set()
    available_exits = [ex for ex in sim.all_possible_exits if ex not in fire_nodes]

    guide, exits = sim.compute_escape(G, G_physical, G_no_block, available_exits)
    _, main_path, main_cost, backup_path, backup_cost = guide[7]

    return {
        "direction": "right",
        "scenario": scenario,
        "fire_node": fire_node,
        "main_path": main_path,
        "backup_path": backup_path,
        "path_type": "MAIN",
        "edge_data": edge_data,
        "node_temp": node_temp,
        "node_co2": node_co2,
        "guide": guide,
        "exits": exits,
    }


def test_draw_without_distress_does_not_raise():
    fig = plt.figure()
    draw_full_result(fig, _make_result(), clicked_node=7)
    plt.close(fig)


def test_draw_with_distress_info_shows_banner():
    fig = plt.figure()
    distress_info = {"count": 3, "time": 1713181385.0}  # fixed Unix timestamp
    draw_full_result(fig, _make_result(), clicked_node=7, distress_info=distress_info)

    # ax_map is the second subplot (index 1)
    ax_map = fig.axes[1]
    texts = [t.get_text() for t in ax_map.texts]
    assert any("DISTRESS" in t for t in texts), \
        f"Expected a text containing 'DISTRESS' in ax_map, found: {texts}"
    plt.close(fig)


def test_draw_with_distress_info_shows_count():
    fig = plt.figure()
    distress_info = {"count": 5, "time": 1713181385.0}
    draw_full_result(fig, _make_result(), clicked_node=7, distress_info=distress_info)

    ax_map = fig.axes[1]
    texts = [t.get_text() for t in ax_map.texts]
    assert any("×5" in t for t in texts), \
        f"Expected count '×5' in ax_map texts, found: {texts}"
    plt.close(fig)
