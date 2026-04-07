// esp32_gateway/esp32_gateway.ino
//
// MSE ESP32 Gateway — receives path_push from Arduino Mega via UART
// (Single-file version for Tinkercad)
//
// Wiring:
//   ESP32 RX (GPIO 3)  <- Mega TX1 (pin 18)
//   ESP32 TX (GPIO 1)  -> Mega RX1 (pin 19)
//   GND                -- GND
//
// No external libraries required — uses plain C string parsing.

#include <Arduino.h>
#include <string.h>
#include <stdio.h>

// ── config ───────────────────────────────────────────────────────────────────
// Change this to match this device's node_id in topology.json.
#define MY_NODE_ID "1B"

// ── path_receiver ─────────────────────────────────────────────────────────────
static char line_buf[128];
static int  line_pos      = 0;
static char direction_buf[16];
static bool has_direction = false;

void path_receiver_init(void) {
    Serial.begin(115200);
    line_pos      = 0;
    has_direction = false;
    direction_buf[0] = '\0';
}

// Extract the string value for a JSON key from a flat JSON line.
// Finds the pattern "key":"..." and copies the value into out_buf.
// Returns true on success.
static bool json_get_str(const char* json, const char* key,
                         char* out_buf, int out_size) {
    char pattern[32];
    snprintf(pattern, sizeof(pattern), "\"%s\":\"", key);
    const char* p = strstr(json, pattern);
    if (!p) return false;
    p += strlen(pattern);
    int i = 0;
    while (*p && *p != '"' && i < out_size - 1) {
        out_buf[i++] = *p++;
    }
    out_buf[i] = '\0';
    return (*p == '"');
}

void path_receiver_feed_line(const char* line) {
    if (!strstr(line, "\"path_push\"")) return;
    char node_id[16];
    if (!json_get_str(line, "node_id", node_id, sizeof(node_id))) return;
    if (strcmp(node_id, MY_NODE_ID) != 0) return;
    char dir[16];
    if (!json_get_str(line, "direction", dir, sizeof(dir))) return;
    strncpy(direction_buf, dir, sizeof(direction_buf) - 1);
    direction_buf[sizeof(direction_buf) - 1] = '\0';
    has_direction = true;
}

void path_receiver_poll(void) {
    static bool overflow = false;
    while (Serial.available()) {
        char c = (char)Serial.read();
        if (c == '\n') {
            if (!overflow) {
                line_buf[line_pos] = '\0';
                path_receiver_feed_line(line_buf);
            }
            line_pos = 0;
            overflow = false;
        } else if (c == '\r') {
            // skip carriage return (handles \r\n line endings)
        } else if (line_pos < 127) {
            line_buf[line_pos++] = c;
        } else {
            overflow = true;
        }
    }
}

const char* path_receiver_get_direction(void) {
    return has_direction ? direction_buf : nullptr;
}

// ── Arduino entry points ──────────────────────────────────────────────────────
void setup() {
    path_receiver_init();
}

void loop() {
    path_receiver_poll();

    // Use path_receiver_get_direction() wherever the direction is needed.
    // Returns direction string (e.g. "left", "right", "forward", "back"),
    // or nullptr if no matching packet has been received yet.
}
