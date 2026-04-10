// mega/serial_laptop.cpp
#include "serial_laptop.h"
#include "node_state.h"
#include "topology.h"
#include <Arduino.h>
#include <float.h>

void laptop_init(void) {
    Serial.begin(115200);
}

void laptop_send_snapshot(void) {
    // Stream JSON character-by-character to avoid large heap allocation
    Serial.print(F("{\"type\":\"state_snapshot\",\"nodes\":["));
    for (int i = 0; i < NUM_NODES; i++) {
        const NodeState& s = node_states[i];
        Serial.print(F("{\"id\":\""));
        Serial.print(NODE_IDS[i]);
        Serial.print(F("\",\"passable\":"));
        Serial.print(s.passable      ? F("true")  : F("false"));
        Serial.print(F(",\"temp\":"));     Serial.print(s.temperature,   1);
        Serial.print(F(",\"co2\":"));      Serial.print(s.co2,            3);
        Serial.print(F(",\"next_hop\":"));
        if (s.next_hop >= 0) {
            Serial.print('"'); Serial.print(NODE_IDS[s.next_hop]); Serial.print('"');
        } else {
            Serial.print(F("null"));
        }
        Serial.print(F(",\"dist\":"));
        Serial.print(s.dist >= FLT_MAX ? 3.4028235E38f : s.dist, 2);
        Serial.print(F(",\"distress\":"));  Serial.print(s.distress_active ? F("true") : F("false"));
        Serial.print(F(",\"timed_out\":")); Serial.print(s.timed_out      ? F("true") : F("false"));
        Serial.print('}');
        if (i < NUM_NODES - 1) Serial.print(',');
    }
    Serial.println(F("]}"));
}

void laptop_forward_distress(const char* raw_json) {
    Serial.println(raw_json);
}

static char ack_buf[128];
static int  ack_pos = 0;

void laptop_poll_ack(void) {
    while (Serial.available()) {
        char c = (char)Serial.read();
        if (c == '\n') {
            ack_buf[ack_pos] = '\0';
            // Broadcast ack to both directly-connected ESP32 nodes
            Serial1.println(ack_buf);
            Serial2.println(ack_buf);
            ack_pos = 0;
        } else if (ack_pos < 127) {
            ack_buf[ack_pos++] = c;
        }
    }
}
