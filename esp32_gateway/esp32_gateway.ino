// esp32_gateway/esp32_gateway.ino
//
// MSE ESP32 Gateway — receives path_push packets from the Arduino Mega via UART
// and extracts the evacuation direction assigned to this node.
// (Single-file version for Tinkercad — no external libraries needed)
//
// How it fits into the system:
//   The laptop controller computes the shortest evacuation path and sends
//   a "path_push" JSON packet to the Arduino Mega over USB serial.
//   The Mega forwards that packet to this ESP32 over UART (hardware serial).
//   This ESP32 reads the packet, checks if it is addressed to its own node ID,
//   and stores the direction (e.g. "left", "right") so the exit light can
//   display the correct arrow.
//
// Wiring (ESP32 <-> Arduino Mega):
//   ESP32 RX (GPIO 3)  <- Mega TX1 (pin 18)   — ESP32 receives data from Mega
//   ESP32 TX (GPIO 1)  -> Mega RX1 (pin 19)   — ESP32 sends data to Mega (unused here)
//   GND                -- GND                  — shared ground required

#include <Arduino.h>   // Core Arduino types and Serial
#include <string.h>    // strcmp, strncpy, strstr, strlen
#include <stdio.h>     // snprintf

// ── config ───────────────────────────────────────────────────────────────────
// Set MY_NODE_ID to match this physical device's node_id in topology.json.
// Each ESP32 in the mesh has a unique ID (e.g. "1A", "1B", "2C").
// The node will only act on path_push packets addressed to this ID.
#define MY_NODE_ID "1B"

// ── internal state ────────────────────────────────────────────────────────────
// Buffer that accumulates incoming UART characters until a full line arrives.
static char line_buf[128];
static int  line_pos = 0;         // how many characters are currently in line_buf

// Stores the last direction received for MY_NODE_ID.
static char direction_buf[16];
static bool has_direction = false; // false until the first valid packet arrives

// ── path_receiver_init ────────────────────────────────────────────────────────
// Call once from setup().
// Starts the hardware serial port at 115200 baud (must match the Mega's baud rate)
// and resets all internal state.
void path_receiver_init(void) {
    Serial.begin(115200);   // open UART at 115200 baud
    line_pos         = 0;
    has_direction    = false;
    direction_buf[0] = '\0';
}

// ── json_get_str ──────────────────────────────────────────────────────────────
// Minimal JSON string extractor — no external library needed.
//
// Searches the raw JSON text for the pattern:  "key":"value"
// and copies value into out_buf (at most out_size-1 characters + null terminator).
//
// Example:
//   json    = {"type":"path_push","node_id":"1B","direction":"left"}
//   key     = "direction"
//   result  → out_buf = "left", returns true
//
// Returns true if the key was found and the value was extracted successfully.
// Returns false if the key is missing or the value is malformed.
static bool json_get_str(const char* json, const char* key,
                         char* out_buf, int out_size) {
    // Build the search pattern:  "key":"
    char pattern[32];
    snprintf(pattern, sizeof(pattern), "\"%s\":\"", key);

    // Find that pattern in the JSON string
    const char* p = strstr(json, pattern);
    if (!p) return false;           // key not present

    // Advance p past the pattern to point at the first character of the value
    p += strlen(pattern);

    // Copy characters until the closing quote or buffer full
    int i = 0;
    while (*p && *p != '"' && i < out_size - 1) {
        out_buf[i++] = *p++;
    }
    out_buf[i] = '\0';

    // If we stopped at a closing quote the extraction was clean
    return (*p == '"');
}

// ── path_receiver_feed_line ───────────────────────────────────────────────────
// Parses one complete JSON line and updates direction_buf if the packet
// is a path_push addressed to MY_NODE_ID.
//
// Expected packet format (sent by the laptop controller via the Mega):
//   {"type":"path_push","node_id":"1B","direction":"left"}
//
// Steps:
//   1. Quick check: skip the line if "path_push" is not in it at all.
//   2. Extract node_id — skip if it does not match MY_NODE_ID.
//   3. Extract direction — store it in direction_buf and set has_direction.
void path_receiver_feed_line(const char* line) {
    // Step 1 — fast reject: if the word "path_push" is not in the line, ignore it
    if (!strstr(line, "\"path_push\"")) return;

    // Step 2 — check the node_id field matches this device
    char node_id[16];
    if (!json_get_str(line, "node_id", node_id, sizeof(node_id))) return;
    if (strcmp(node_id, MY_NODE_ID) != 0) return;   // packet is for a different node

    // Step 3 — extract and store the direction
    char dir[16];
    if (!json_get_str(line, "direction", dir, sizeof(dir))) return;
    strncpy(direction_buf, dir, sizeof(direction_buf) - 1);
    direction_buf[sizeof(direction_buf) - 1] = '\0'; // guarantee null-termination
    has_direction = true;
}

// ── path_receiver_poll ────────────────────────────────────────────────────────
// Reads all bytes currently available on the serial port and assembles them
// into complete lines (terminated by '\n').  When a full line is ready it is
// passed to path_receiver_feed_line() for parsing.
//
// Call this every iteration of loop() so no bytes are missed.
//
// overflow flag: if a single line is longer than 127 characters (which should
// never happen with valid packets) the line is discarded to avoid a buffer
// overrun.
void path_receiver_poll(void) {
    static bool overflow = false;   // true when the current line exceeded the buffer

    while (Serial.available()) {
        char c = (char)Serial.read();

        if (c == '\n') {
            // End of line — process it if it was not too long
            if (!overflow) {
                line_buf[line_pos] = '\0';          // null-terminate the string
                path_receiver_feed_line(line_buf);  // try to parse it
            }
            // Reset for the next line regardless
            line_pos = 0;
            overflow = false;

        } else if (c == '\r') {
            // Ignore carriage return so both '\n' and '\r\n' line endings work

        } else if (line_pos < 127) {
            // Normal character — append to buffer
            line_buf[line_pos++] = c;

        } else {
            // Buffer full before a newline — mark line as overflowed and discard
            overflow = true;
        }
    }
}

// ── path_receiver_get_direction ───────────────────────────────────────────────
// Returns the most recently received direction string for MY_NODE_ID,
// e.g. "left", "right", "forward", "back".
// Returns nullptr if no valid path_push packet has been received yet.
// The caller should display the arrow or take action based on this value.
const char* path_receiver_get_direction(void) {
    return has_direction ? direction_buf : nullptr;
}

// ── Arduino entry points ──────────────────────────────────────────────────────

// setup() runs once when the ESP32 powers on or resets.
void setup() {
    path_receiver_init();   // start serial and reset state
}

// loop() runs repeatedly forever after setup() finishes.
void loop() {
    // Read any new bytes from the Mega and parse complete JSON lines.
    path_receiver_poll();

    // After calling poll(), check path_receiver_get_direction() to find out
    // which way to point the exit-light arrow.
    // Example:
    //   const char* dir = path_receiver_get_direction();
    //   if (dir != nullptr) {
    //       // update LED arrow based on dir ("left", "right", "forward", "back")
    //   }
}
