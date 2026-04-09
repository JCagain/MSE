// Node 5 Smart Emergency Escape Sign
// Left = Exit 4    Right = Exit 6
#include <string.h>
#include <stdio.h>

#define MY_NODE_ID "5"
#define LED_LEFT 13
#define LED_RIGHT 12
#define BUZZER 8

String currentMode = "off";
// Stores inline buffer
static char line_buf[128];
static int  line_pos = 0;
// Stores the last direction received for MY_NODE_ID.
static char direction_buf[16];
static bool has_direction = false;

// ------ Path Helper Functions ------
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
    printf("%s", direction_buf);
}

const char* path_receiver_get_direction(void) {
    return has_direction ? direction_buf : nullptr;
}

// ------ End of Path Helper Functions ------

void setup() {
  path_receiver_init();
  
  Serial.begin(9600);
  pinMode(LED_LEFT, OUTPUT);
  pinMode(LED_RIGHT, OUTPUT);
  pinMode(BUZZER, OUTPUT);
  
  digitalWrite(LED_LEFT, LOW);
  digitalWrite(LED_RIGHT, LOW);
  noTone(BUZZER);
  
  Serial.println("Module at Node5");
  Serial.println("enter 'block' as not accessible");
  Serial.println("enter 'exit4' as go left");
  Serial.println("enter 'exit6' as go right");
}

void loop() {
  // Check for new commands
  if (Serial.available() > 0) {
    String cmd = Serial.readStringUntil('\n');
    cmd.trim();
    currentMode = cmd;
  }
  
  // Poll received path package
  path_receiver_poll();

  // ------------------------------
  // Continuous GO LEFT (Exit 4)
  // ------------------------------
  if (currentMode == "exit4") {
    digitalWrite(LED_LEFT, HIGH);
    tone(BUZZER, 800);
    delay(200);
    
    digitalWrite(LED_LEFT, LOW);
    noTone(BUZZER);
    delay(300);
    
    Serial.println("GO LEFT: Exit 4");
  }

  // ------------------------------
  // Continuous GO RIGHT (Exit 6)
  // ------------------------------
  else if (currentMode == "exit6") {
    // Beep 1
    digitalWrite(LED_RIGHT, HIGH);
    tone(BUZZER, 800);
    delay(180);
    digitalWrite(LED_RIGHT, LOW);
    noTone(BUZZER);
    delay(120);

    // Beep 2
    digitalWrite(LED_RIGHT, HIGH);
    tone(BUZZER, 800);
    delay(180);
    digitalWrite(LED_RIGHT, LOW);
    noTone(BUZZER);
    delay(300);
    
    Serial.println("GO RIGHT: Exit 6");
  }

  // ------------------------------
  // Continuous DO NOT ENTER
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
    
    Serial.println("DO NOT ENTER!");
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