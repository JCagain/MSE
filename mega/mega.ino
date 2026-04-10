// mega/mega.ino
//
// MSE Emergency Exit Controller — Arduino Mega firmware
//
// Wiring:
//   Serial0 (USB)          <- -> Laptop      (state_snapshot, distress, distress_ack)
//   Serial1 (TX1/RX1 pins) <- -> ESP32 node A (routing, distress, path_push)
//   Serial2 (TX2/RX2 pins) <- -> ESP32 node B (routing, distress, path_push)
//
// Pin mapping on Mega: TX1=18, RX1=19 | TX2=16, RX2=17

#include "topology.h"
#include "node_state.h"
#include "dijkstra.h"
#include "serial_laptop.h"
#include "serial_mesh.h"

// ----- Configuration -----
static const uint32_t BROADCAST_INTERVAL_MS = 3000;   // minimum ms between Dijkstra runs
static const uint32_t NODE_TIMEOUT_MS       = 20000;  // ms of silence before node marked offline

// ----- State -----
static uint32_t last_dijkstra_ms = 0;
static bool     recompute_needed = false;
static char     distress_buf[256];

// -------------------------

void setup() {
    laptop_init();   // Serial0 @ 115200
    mesh_init();     // Serial1 + Serial2 @ 115200
    node_init();
}

void loop() {
    // 1. Read incoming packets from directly-connected ESP32 nodes
    distress_buf[0] = '\0';
    if (mesh_poll(distress_buf, sizeof(distress_buf))) {
        recompute_needed = true;
    }

    // 2. Forward any distress event to laptop
    if (distress_buf[0] != '\0') {
        laptop_forward_distress(distress_buf);
    }

    // 3. Relay distress_ack from laptop to both ESP32 nodes
    laptop_poll_ack();

    // 4. Check node timeouts (marks nodes offline if not heard from)
    if (node_check_timeouts(NODE_TIMEOUT_MS)) {
        recompute_needed = true;
    }

    // 5. Recompute paths if needed, subject to debounce interval
    uint32_t now = millis();
    if (recompute_needed && (now - last_dijkstra_ms) >= BROADCAST_INTERVAL_MS) {
        bool   passable[NUM_NODES];
        int8_t next_hop[NUM_NODES];
        float  dist[NUM_NODES];

        for (int i = 0; i < NUM_NODES; i++) passable[i] = node_states[i].passable;

        dijkstra_run(passable, next_hop, dist);

        for (int i = 0; i < NUM_NODES; i++) {
            node_states[i].next_hop = next_hop[i];
            node_states[i].dist     = dist[i];
        }

        mesh_send_path_push();
        laptop_send_snapshot();

        last_dijkstra_ms = now;
        recompute_needed = false;
    }
}
