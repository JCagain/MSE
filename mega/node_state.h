// mega/node_state.h
#pragma once
#include <stdint.h>
#include <stdbool.h>
#include "topology.h"

typedef struct {
    bool     passable;
    float    temperature;
    float    co2;
    int8_t   next_hop;          // index into NODE_IDS toward nearest exit; -1 = exit or unreachable
    float    dist;              // computed distance to nearest exit
    bool     timed_out;
    bool     distress_active;
    uint32_t last_seen_ms;      // millis() when last routing packet was received; 0 = never
} NodeState;

extern NodeState node_states[NUM_NODES];

// Initialise all node_states to defaults. Call from setup().
void node_init(void);

// Return the index of the node with the given ID, or -1 if not found.
int8_t node_find(const char* node_id);

// Update dynamic fields from a routing packet. Also resets timed_out and stamps last_seen_ms.
void node_update(int8_t idx, bool passable, float temp, float co2);

// Check for timed-out nodes. Sets passable=false, timed_out=true for any node not heard
// from within timeout_ms. Returns true if any node newly timed out.
bool node_check_timeouts(uint32_t timeout_ms);
