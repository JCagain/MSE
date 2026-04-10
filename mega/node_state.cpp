// mega/node_state.cpp
#include "node_state.h"
#include <Arduino.h>
#include <string.h>
#include <float.h>

NodeState node_states[NUM_NODES];

void node_init(void) {
    for (int i = 0; i < NUM_NODES; i++) {
        node_states[i].passable       = true;
        node_states[i].temperature    = 0.0f;
        node_states[i].co2            = 0.0f;
        node_states[i].next_hop       = -1;
        node_states[i].dist           = FLT_MAX;
        node_states[i].timed_out      = false;
        node_states[i].distress_active = false;
        node_states[i].last_seen_ms   = 0;
    }
}

int8_t node_find(const char* node_id) {
    for (int i = 0; i < NUM_NODES; i++) {
        if (strcmp(NODE_IDS[i], node_id) == 0) return (int8_t)i;
    }
    return -1;
}

void node_update(int8_t idx, bool passable, float temp, float co2) {
    node_states[idx].passable      = passable;
    node_states[idx].temperature   = temp;
    node_states[idx].co2           = co2;
    node_states[idx].timed_out     = false;
    node_states[idx].last_seen_ms  = millis();
}

bool node_check_timeouts(uint32_t timeout_ms) {
    uint32_t now = millis();
    bool any = false;
    for (int i = 0; i < NUM_NODES; i++) {
        NodeState& s = node_states[i];
        if (!s.timed_out && s.last_seen_ms > 0 &&
            (now - s.last_seen_ms) > timeout_ms) {
            s.timed_out  = true;
            s.passable   = false;
            any = true;
        }
    }
    return any;
}
