#include <Arduino.h>
#include <string.h>
#include <stdio.h>

#define MY_NODE_ID "5"

static char line_buf[128];
static int  line_pos      = 0;
static char direction_buf[16];
static bool has_direction = false;

void path_receiver_init(void) {
    Serial.begin(115200);
    line_pos         = 0;
    has_direction    = false;
    direction_buf[0] = '\0';
}

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

void setup() {
    path_receiver_init();
}

void loop() {
    path_receiver_poll();
}
