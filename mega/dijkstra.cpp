// mega/dijkstra.cpp
#include "dijkstra.h"
#include <float.h>
#include <string.h>

void dijkstra_run(const bool passable[NUM_NODES],
                  int8_t     next_hop[NUM_NODES],
                  float      dist[NUM_NODES]) {
    bool visited[NUM_NODES];
    memset(visited, 0, sizeof(visited));

    for (int i = 0; i < NUM_NODES; i++) {
        dist[i]     = FLT_MAX;
        next_hop[i] = -1;
    }

    // Seed: all passable exits at distance 0
    for (int i = 0; i < NUM_NODES; i++) {
        if (IS_EXIT[i] && passable[i]) dist[i] = 0.0f;
    }

    // O(N²) Dijkstra — fine for building-scale graphs (N <= 50)
    for (int iter = 0; iter < NUM_NODES; iter++) {
        // Find unvisited node with minimum distance
        int u = -1;
        for (int i = 0; i < NUM_NODES; i++) {
            if (!visited[i] && dist[i] < FLT_MAX && (u == -1 || dist[i] < dist[u]))
                u = i;
        }
        if (u == -1) break;
        visited[u] = true;

        // Relax neighbors
        for (int j = 0; j < MAX_NEIGHBORS; j++) {
            int8_t v = NEIGHBOR_IDX[u][j];
            if (v < 0) break;               // sentinel
            if (!passable[v]) continue;
            float nd = dist[u] + WEIGHTS[u][j];
            if (nd < dist[v]) {
                dist[v]     = nd;
                next_hop[v] = (int8_t)u;    // u is the step toward exit from v
            }
        }
    }
}
