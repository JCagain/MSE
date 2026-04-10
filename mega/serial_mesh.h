// mega/serial_mesh.h
#pragma once
#include <stdbool.h>

// Initialise Serial1 and Serial2 (both at 115200). Call once from setup().
void mesh_init(void);

// Poll Serial1 and Serial2 for incoming packets from directly-connected ESP32 nodes.
// If a distress packet arrives, copies its raw JSON into distress_buf (size distress_buf_size).
// Returns true if any routing packet was received (caller should trigger Dijkstra recompute).
bool mesh_poll(char* distress_buf, int distress_buf_size);

// Send a path_push packet to each directly-connected node via its assigned serial port.
// Uses NODE_PORT[] from topology.h to route to Serial1 or Serial2.
void mesh_send_path_push(void);
