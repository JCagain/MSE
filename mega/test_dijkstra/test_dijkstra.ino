// mega/test_dijkstra/test_dijkstra.ino
//
// Self-contained Dijkstra test using sample-topology.json topology.
// Nodes: 0=1A  1=1B  2=1C  3=1Exit-A
//
// Expected results (all nodes passable):
//   1A      (0): dist=7.0, next_hop=2 (->1C->1Exit-A)
//   1B      (1): dist=7.0, next_hop=3 (->1Exit-A)
//   1C      (2): dist=4.0, next_hop=3 (->1Exit-A)
//   1Exit-A (3): dist=0.0, next_hop=-1

#include <stdint.h>
#include <float.h>

#define T_NUM_NODES    4
#define T_MAX_NEIGHBORS 2

static const int8_t T_NEIGHBOR_IDX[T_NUM_NODES][T_MAX_NEIGHBORS] = {
    {1, 2}, {0, 3}, {0, 3}, {1, 2}
};
static const float T_WEIGHTS[T_NUM_NODES][T_MAX_NEIGHBORS] = {
    {5.0f, 3.0f}, {5.0f, 7.0f}, {3.0f, 4.0f}, {7.0f, 4.0f}
};
static const bool T_IS_EXIT[T_NUM_NODES] = {false, false, false, true};

void dijkstra_test(const bool passable[], int8_t next_hop[], float dist[]) {
    bool visited[T_NUM_NODES] = {};
    for (int i = 0; i < T_NUM_NODES; i++) {
        dist[i]     = FLT_MAX;
        next_hop[i] = -1;
    }
    for (int i = 0; i < T_NUM_NODES; i++) {
        if (T_IS_EXIT[i] && passable[i]) dist[i] = 0.0f;
    }
    for (int iter = 0; iter < T_NUM_NODES; iter++) {
        int u = -1;
        for (int i = 0; i < T_NUM_NODES; i++) {
            if (!visited[i] && dist[i] < FLT_MAX && (u == -1 || dist[i] < dist[u]))
                u = i;
        }
        if (u == -1) break;
        visited[u] = true;
        for (int j = 0; j < T_MAX_NEIGHBORS; j++) {
            int8_t v = T_NEIGHBOR_IDX[u][j];
            if (v < 0) break;
            if (!passable[v]) continue;
            float nd = dist[u] + T_WEIGHTS[u][j];
            if (nd < dist[v]) { dist[v] = nd; next_hop[v] = (int8_t)u; }
        }
    }
}

static bool check(const char* name, float got_d, float want_d, int8_t got_nh, int8_t want_nh) {
    if (got_d != want_d || got_nh != want_nh) {
        Serial.print("FAIL "); Serial.print(name);
        Serial.print(": dist="); Serial.print(got_d);
        Serial.print(" want="); Serial.print(want_d);
        Serial.print("  next_hop="); Serial.print(got_nh);
        Serial.print(" want="); Serial.println(want_nh);
        return false;
    }
    return true;
}

void setup() {
    Serial.begin(115200);
    while (!Serial) {}

    bool passable[T_NUM_NODES] = {true, true, true, true};
    int8_t nh[T_NUM_NODES];
    float  dist[T_NUM_NODES];
    dijkstra_test(passable, nh, dist);

    bool pass = true;
    pass &= check("1A",      dist[0], 7.0f, nh[0],  2);
    pass &= check("1B",      dist[1], 7.0f, nh[1],  3);
    pass &= check("1C",      dist[2], 4.0f, nh[2],  3);
    pass &= check("1Exit-A", dist[3], 0.0f, nh[3], -1);

    // Test: block 1C (index 2) — 1A must now go via 1B
    passable[2] = false;
    dijkstra_test(passable, nh, dist);
    pass &= check("1A (1C blocked)", dist[0], 12.0f, nh[0], 1);

    if (pass) Serial.println("ALL TESTS PASSED");
}

void loop() {}
