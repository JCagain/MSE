// mega/serial_mesh.cpp
#include "serial_mesh.h"
#include "node_state.h"
#include "topology.h"
#include <Arduino.h>
#include <ArduinoJson.h>
#include <string.h>

// Separate line buffers for each serial port
static char buf1[256]; static int pos1 = 0;
static char buf2[256]; static int pos2 = 0;

void mesh_init(void) {
    Serial1.begin(115200);
    Serial2.begin(115200);
}

// Parse and dispatch one complete JSON line. Returns true if it was a routing packet.
static bool process_line(char* line, char* distress_buf, int distress_buf_size) {
    StaticJsonDocument<512> doc;
    if (deserializeJson(doc, line) != DeserializationError::Ok) return false;

    const char* type = doc["type"] | "";

    if (strcmp(type, "routing") == 0) {
        const char* node_id = doc["node_id"] | "";
        int8_t idx = node_find(node_id);
        if (idx >= 0) {
            node_update(idx,
                        doc["is_passable"] | true,
                        doc["temperature"]  | 0.0f,
                        doc["co2"]          | 0.0f);
            return true;
        }
    } else if (strcmp(type, "distress") == 0) {
        if (distress_buf != nullptr) {
            // Note: if both nodes send distress in the same poll cycle, the second overwrites the first.
            // Acceptable for this prototype — distress events are rare and both get flagged in node_states.
            strncpy(distress_buf, line, distress_buf_size - 1);
            distress_buf[distress_buf_size - 1] = '\0';
            int8_t idx = node_find(doc["node_id"] | "");
            if (idx >= 0) node_states[idx].distress_active = true;
        }
    }
    return false;
}

bool mesh_poll(char* distress_buf, int distress_buf_size) {
    bool got_routing = false;

    while (Serial1.available()) {
        char c = (char)Serial1.read();
        if (c == '\n') {
            buf1[pos1] = '\0'; pos1 = 0;
            got_routing |= process_line(buf1, distress_buf, distress_buf_size);
        } else if (pos1 < 255) {
            buf1[pos1++] = c;
        }
    }

    while (Serial2.available()) {
        char c = (char)Serial2.read();
        if (c == '\n') {
            buf2[pos2] = '\0'; pos2 = 0;
            got_routing |= process_line(buf2, distress_buf, distress_buf_size);
        } else if (pos2 < 255) {
            buf2[pos2++] = c;
        }
    }

    return got_routing;
}

void mesh_send_path_push(void) {
    for (int i = 0; i < NUM_NODES; i++) {
        if (NODE_PORT[i] == 0) continue;           // not directly wired
        if (node_states[i].next_hop < 0) continue; // exit node or unreachable

        // Find direction toward next_hop
        const char* dir = "";
        for (int j = 0; j < MAX_NEIGHBORS; j++) {
            if (NEIGHBOR_IDX[i][j] == node_states[i].next_hop) {
                dir = DIRECTIONS[i][j];
                break;
            }
        }
        if (dir[0] == '\0') {
            Serial.print(F("ERR: no direction for node ")); Serial.println(NODE_IDS[i]);
            continue;
        }

        HardwareSerial& port = (NODE_PORT[i] == 2) ? Serial2 : Serial1;

        StaticJsonDocument<128> doc;
        doc["type"]      = "path_push";
        doc["node_id"]   = NODE_IDS[i];
        doc["direction"] = dir;
        serializeJson(doc, port);
        port.println();
    }
}
