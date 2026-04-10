// mega/dijkstra.h
#pragma once
#include "topology.h"
#include <stdint.h>
#include <stdbool.h>

/**
 * Multi-source Dijkstra. Seeds from all passable exits (IS_EXIT[i] && passable[i]).
 * Pure function — no Arduino dependencies, no global state reads.
 *
 * @param passable   input: passable[i] = true if node i can be traversed
 * @param next_hop   output: index of neighbor one step closer to nearest exit, or -1
 * @param dist       output: distance to nearest exit (FLT_MAX if unreachable)
 */
void dijkstra_run(const bool passable[NUM_NODES],
                  int8_t     next_hop[NUM_NODES],
                  float      dist[NUM_NODES]);
