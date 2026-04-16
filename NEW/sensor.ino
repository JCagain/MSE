#include <Wire.h>
#include <Adafruit_SGP30.h>
#include "DHT.h"

// --- 引脚定义 ---
#define DHTPIN 4          // DHT22 引脚
#define DHTTYPE DHT22
#define SDA_PIN 18        // SGP30 引脚
#define SCL_PIN 19

// --- 传感器对象 ---
Adafruit_SGP30 sgp;
DHT dht(DHTPIN, DHTTYPE);

// --- 配置 ---
#define SAMPLING_INTERVAL 5000
unsigned long lastSampleTime = 0;

void setup() {
  Serial.begin(9600);
  delay(1000);

  // 初始化 DHT22
  pinMode(DHTPIN, INPUT_PULLUP);
  dht.begin();

  // 初始化 SGP30
  Wire.begin(SDA_PIN, SCL_PIN);
  if (!sgp.begin()) {
    Serial.println("SGP30_INIT_FAIL");
    while (1); // 停住
  }

  Serial.println("ALL_SENSORS_READY");
}

void loop() {
  if (millis() - lastSampleTime >= SAMPLING_INTERVAL) {
    lastSampleTime = millis();

    // --- 1. 读取 DHT22 (只取温度) ---
    float temp = dht.readTemperature();
    bool dhtOk = !isnan(temp) && temp < 80;

    // --- 2. 读取 SGP30 (只取 CO2) ---
    bool sgpOk = sgp.IAQmeasure();

    // --- 3. 统一输出 (格式: Temp,CO2) ---
    if (dhtOk && sgpOk) {
      Serial.print(temp, 1);    // 温度
      Serial.print(",");
      Serial.println(sgp.eCO2); // CO2
    } else {
      Serial.println("READ_ERROR"); // 任意一个传感器失败
    }
  }
}
