// mega/serial_laptop.h
#pragma once
#include <stdint.h>

// Initialise Serial0 (USB) at 115200. Call once from setup().
void laptop_init(void);

// Transmit a full state_snapshot JSON packet to the laptop over Serial0.
// Call after every Dijkstra run.
void laptop_send_snapshot(void);

// Forward a raw distress JSON string to the laptop over Serial0.
void laptop_forward_distress(const char* raw_json);

// Poll Serial0 for an incoming distress_ack line; relay it to Serial1 and Serial2.
// Call every iteration of loop().
void laptop_poll_ack(void);
