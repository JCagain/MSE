#define LED_RIGHT 8
#define LED_LEFT 13
#define BUZZER 19
#define MANUAL_BUTTON 7

String currentMode = "idle";
bool lastButtonState = HIGH;

unsigned long lastBlinkTime = 0;
bool blinkState = false;

void setup() {
  Serial.begin(9600);

  pinMode(LED_RIGHT, OUTPUT);
  pinMode(LED_LEFT, OUTPUT);
  pinMode(BUZZER, OUTPUT);
  pinMode(MANUAL_BUTTON, INPUT_PULLUP);

  allOff();
  Serial.println("System Ready");
}

void loop() {
  readButton();
  readSerialCommand();
  updateOutput();
}

void readButton() {
  bool buttonState = digitalRead(MANUAL_BUTTON);

  // 每次按下都发送 search
  if (lastButtonState == HIGH && buttonState == LOW) {
    Serial.println("search");
  }

  lastButtonState = buttonState;
}

void readSerialCommand() {
  if (Serial.available() > 0) {
    String cmd = Serial.readStringUntil('\n');
    cmd.trim();

    if (cmd == "left" || cmd == "right" || cmd == "idle") {
      currentMode = cmd;
      Serial.print("Received: ");
      Serial.println(currentMode);
    }
  }
}

void updateOutput() {
  unsigned long now = millis();

  if (currentMode == "idle") {
    allOff();
  }
  else if (currentMode == "right") {
    if (now - lastBlinkTime >= 300) {
      lastBlinkTime = now;
      blinkState = !blinkState;
      digitalWrite(LED_LEFT, LOW);
      digitalWrite(LED_RIGHT, blinkState);
      if (blinkState) tone(BUZZER, 800, 80);
    }
  }
  else if (currentMode == "left") {
    if (now - lastBlinkTime >= 300) {
      lastBlinkTime = now;
      blinkState = !blinkState;
      digitalWrite(LED_RIGHT, LOW);
      digitalWrite(LED_LEFT, blinkState);
      if (blinkState) tone(BUZZER, 800, 80);
    }
  }
}

void allOff() {
  digitalWrite(LED_RIGHT, LOW);
  digitalWrite(LED_LEFT, LOW);
  noTone(BUZZER);
}