// Node 5 Smart Emergency Escape Sign
// Left = Exit 4    Right = Exit 6
#include <string.h>
#include <stdio.h>

#define MY_NODE_ID "5"
#define LED_LEFT 13
#define LED_RIGHT 12
#define BUZZER 8

String currentMode = "off";
static char direction_buf[16];
static bool has_direction = false;

// ------ Path Helper Functions ------
void path_receiver_init(void) {
    Serial.begin(115200);
    has_direction    = false;
    direction_buf[0] = '\0';
}

// Extract a string value from a flat JSON object.
// Handles optional whitespace after the colon, e.g. "key": "value".
static bool json_get_str(const char* json, const char* key,
                         char* out_buf, int out_size) {
    char pattern[32];
    snprintf(pattern, sizeof(pattern), "\"%s\":", key);
    const char* p = strstr(json, pattern);
    if (!p) return false;
    p += strlen(pattern);
    while (*p == ' ' || *p == '\t') p++;
    if (*p != '"') return false;
    p++;
    int i = 0;
    while (*p && *p != '"' && i < out_size - 1) {
        out_buf[i++] = *p++;
    }
    out_buf[i] = '\0';
    return (*p == '"');
}

// Parse one JSON line. Sets direction_buf + has_direction if valid path_push for MY_NODE_ID.
// Expected format: {"type": "path_push", "node_id": "5", "direction": "left"}
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

// Read from Serial and feed any complete line to path_receiver_feed_line.
// Uses readStringUntil with its built-in timeout so no line terminator is required
// (compatible with Tinkercad's Serial Monitor which sends no line ending).
void path_receiver_poll(void) {
    if (Serial.available() > 0) {
        String line = Serial.readStringUntil('\n');
        line.trim();
        if (line.length() > 0) {
            path_receiver_feed_line(line.c_str());
        }
    }
}

// Returns the last received direction ("left", "right", "block"), or nullptr if none pending.
const char* path_receiver_get_direction(void) {
    return has_direction ? direction_buf : nullptr;
}

// ------ End of Path Helper Functions ------

void setup() {
  path_receiver_init();
  pinMode(LED_LEFT, OUTPUT);
  pinMode(LED_RIGHT, OUTPUT);
  pinMode(BUZZER, OUTPUT);

  digitalWrite(LED_LEFT, LOW);
  digitalWrite(LED_RIGHT, LOW);
  noTone(BUZZER);

  Serial.println("Node 5 ready. Send JSON: {\"type\":\"path_push\",\"node_id\":\"5\",\"direction\":\"left|right|block\"}");
}

void loop() {
  path_receiver_poll();

  const char* dir = path_receiver_get_direction();
  if (dir) {
    currentMode = String(dir);
    has_direction = false;
  }

  // ------------------------------
  // GO LEFT
  // ------------------------------
  if (currentMode == "left") {
    digitalWrite(LED_LEFT, HIGH);
    tone(BUZZER, 800);
    delay(200);

    digitalWrite(LED_LEFT, LOW);
    noTone(BUZZER);
    delay(300);

    Serial.println("GO LEFT");
  }

  // ------------------------------
  // GO RIGHT
  // ------------------------------
  else if (currentMode == "right") {
    digitalWrite(LED_RIGHT, HIGH);
    tone(BUZZER, 800);
    delay(180);
    digitalWrite(LED_RIGHT, LOW);
    noTone(BUZZER);
    delay(120);

    digitalWrite(LED_RIGHT, HIGH);
    tone(BUZZER, 800);
    delay(180);
    digitalWrite(LED_RIGHT, LOW);
    noTone(BUZZER);
    delay(300);

    Serial.println("GO RIGHT");
  }

  // ------------------------------
  // BLOCK (DO NOT ENTER)
  // ------------------------------
  else if (currentMode == "block") {
    digitalWrite(LED_LEFT, HIGH);
    digitalWrite(LED_RIGHT, HIGH);
    tone(BUZZER, 800);
    delay(500);

    digitalWrite(LED_LEFT, LOW);
    digitalWrite(LED_RIGHT, LOW);
    noTone(BUZZER);
    delay(300);

    Serial.println("DO NOT ENTER");
  }

  // ------------------------------
  // OFF
  // ------------------------------
  else {
    digitalWrite(LED_LEFT, LOW);
    digitalWrite(LED_RIGHT, LOW);
    noTone(BUZZER);
  }
}
