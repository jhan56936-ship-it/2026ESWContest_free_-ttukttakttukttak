/*
 * ============================================================================
 *  바이브키(Vibe-key) 기기 펌웨어  ·  Seeed Studio XIAO ESP32-S3
 * ============================================================================
 *
 *  이 펌웨어가 하는 일 (= 폰이 못 하는 일)
 *  ---------------------------------------------------------------------------
 *  2.0 펌웨어(firmware/legacy/sketch_jul10a_v2.ino)는 40줄이었습니다. loop()에서
 *  핀을 읽어 "Button1"을 println 하고, 손을 뗄 때까지 while 안에 멈춰 있었습니다.
 *  신호 해석·떨림 방지·실행 판단은 전부 안드로이드에 있었고 기기는 전선이었습니다.
 *  3.0에서는 신뢰에 관한 판단을 전부 기기로 내렸습니다.
 *
 *  버튼 핀(D0·D1·D2)은 2.0과 같습니다 — 기기를 다시 만들 필요가 없습니다.
 *
 *    1. 입력 판정   ISR → 큐 → 상태머신(디바운스·짧게/길게·떨림 가드)  [press_fsm.h]
 *    2. 전송 신뢰   프레임 + CRC16 + SEQ + ACK + 재전송 3회            [vkp_frame.h]
 *
 *  기기에는 출력 장치가 없습니다(버튼 3개 + USB가 전부). 사용자에게 알리는 일은
 *  폰이 맡고(음성·진동), 기기는 "제대로 전달됐는가"를 스스로 판단해 재전송하고
 *  실패 횟수를 세어 둡니다. 그 값은 STATS 프레임으로 앱의 자가진단 화면에 나옵니다.
 *
 *  태스크 구성 (모두 core 1, Wi-Fi 없이 동작)
 *  ---------------------------------------------------------------------------
 *   이름        우선순위  주기      스택   하는 일
 *   inputTask      4     10ms     3072B  ISR 큐를 비우고 누름 종류를 확정
 *   protoTask      3      5ms     4096B  프레임 송신·ACK 대기·재전송·수신 해석
 *
 *  아두이노 IDE 설정
 *  ---------------------------------------------------------------------------
 *   보드            : XIAO_ESP32S3  (Seeed Studio XIAO ESP32S3)
 *   USB CDC On Boot : Enabled   ← 이걸 켜야 Serial이 네이티브 USB(CDC)로 나갑니다
 *   USB Mode        : USB-OTG (TinyUSB)  또는 Hardware CDC and JTAG
 *   Upload Mode     : USB-OTG CDC
 *   추가 라이브러리  : 없음 (ESP32 Arduino 코어만 있으면 빌드됩니다)
 *
 *  회로·부품·핀맵은 firmware/README.md 에 표로 정리해 두었습니다.
 * ============================================================================
 */

#include <Arduino.h>

// FreeRTOS 는 Arduino.h 가 간접적으로 끌어오지만, 큐·태스크 API를 직접 쓰므로
// 코어 버전에 상관없이 확실히 잡히도록 명시해 둡니다.
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <freertos/queue.h>

#include "vkp_frame.h"
#include "press_fsm.h"

using namespace vkp;

// ---------------------------------------------------------------- 버전 / 설정

static const uint8_t FW_MAJOR = 3;
static const uint8_t FW_MINOR = 0;

/** 버튼 접점 → 프레임 송출 지연을 로직 애널라이저로 재기 위한 관측 핀 */
#define VK_LATENCY_PROBE 1

/** ACK를 못 받았을 때 다시 보내는 간격과 횟수 */
static const uint16_t ACK_TIMEOUT_MS = 150;
static const uint8_t  MAX_RETRY      = 3;

// ---------------------------------------------------------------- 핀맵
//  XIAO ESP32-S3 (D 표기 → 실제 GPIO)
//   D0=1  D1=2  D2=3  D3=4  D4=5  D5=6  D6=43(TX) D7=44(RX) D8=7  D9=8  D10=9
//
//  버튼 세 개는 2.0 펌웨어(firmware/legacy/sketch_jul10a_v2.ino)가 쓰던 D0·D1·D2를
//  그대로 씁니다. 이미 납땜해 둔 기기를 뜯지 않고 펌웨어만 올리면 되도록 한 것입니다.
//
//  ※ D2(GPIO3)는 부팅 스트래핑 핀(JTAG 소스 선택)입니다. 전원이 들어오는 순간
//    3번 버튼이 눌려 있으면 스트래핑 값이 바뀔 수 있습니다. 정상 부팅에는 영향이
//    없지만, 기판을 새로 뜬다면 D8(GPIO7)로 옮기는 편이 안전합니다.
//    그때는 아래 배열의 3을 7로만 바꾸면 됩니다.

static const uint8_t BUTTON_COUNT = 3;
static const uint8_t BUTTON_PINS[BUTTON_COUNT] = {
    1,   // D0 — 1번 버튼
    2,   // D1 — 2번 버튼
    3    // D2 — 3번 버튼  (스트래핑 핀 주의, 위 설명 참고)
};
static const uint8_t PROBE_PIN = 9;   // D10 — 지연 측정용 관측 핀
// D3~D10 은 비어 있습니다. 나중에 표시 장치(LED 등)를 붙인다면 여기를 씁니다.

// ---------------------------------------------------------------- 큐 / 상태

/** ISR이 남기는 최소 정보. ISR 안에서는 이것만 하고 즉시 빠져나옵니다. */
struct EdgeMsg {
    uint8_t  index;    // 버튼 번호 - 1
    uint8_t  level;    // 핀 전압 (풀업이므로 0 = 눌림)
    uint32_t stampUs;  // 접점이 바뀐 시각
};

/** 입력 태스크가 확정한 "사람이 의도한 누름" */
struct PressMsg {
    uint8_t  button;   // 1~3
    uint8_t  kind;     // K_SHORT / K_LONG / K_DOUBLE
    uint32_t stampUs;  // 판정 근거가 된 접점 시각 (지연 계산의 기준점)
};

static QueueHandle_t edgeQueue   = nullptr;
static QueueHandle_t pressQueue  = nullptr;

static PressFsm  fsm[BUTTON_COUNT];
static Decoder   rx;

/** 보고서에 그대로 넣을 수 있는 실측 카운터 */
static struct {
    uint16_t sent;          // 보낸 이벤트 프레임 수
    uint16_t retx;          // 재전송 횟수
    uint16_t ackTimeout;    // 세 번 다 실패한 횟수
    uint16_t lastLatencyUs; // 마지막 접점→송출 지연
    uint16_t maxLatencyUs;  // 최악값
} stats = {0, 0, 0, 0, 0};

// ---------------------------------------------------------------- ISR

/**
 * 버튼 인터럽트. 여기서 하는 일은 "언제·어디서 바뀌었는지"를 큐에 넣는 것뿐입니다.
 * 판단은 전부 태스크에서 합니다 (ISR을 짧게 유지해야 지연이 흔들리지 않습니다).
 */
static void IRAM_ATTR onButtonEdge(void* arg) {
    EdgeMsg msg;
    msg.index   = (uint8_t)(uint32_t)arg;
    msg.level   = (uint8_t)digitalRead(BUTTON_PINS[msg.index]);
    msg.stampUs = micros();

#if VK_LATENCY_PROBE
    // 손을 떼는 순간(풀업이 다시 HIGH로 올라오는 접점 = 짧게 누름이 확정되는 시점)에
    // 관측 핀을 올리고, 프레임을 다 보낸 뒤 내립니다. 로직 애널라이저에서 이 펄스
    // 폭을 그대로 읽으면 "버튼 접점 → USB 프레임 송출" 지연이 됩니다.
    if (msg.level == HIGH) {
        digitalWrite(PROBE_PIN, HIGH);
    }
#endif

    BaseType_t woken = pdFALSE;
    xQueueSendFromISR(edgeQueue, &msg, &woken);
    if (woken == pdTRUE) {
        portYIELD_FROM_ISR();
    }
}

// ---------------------------------------------------------------- 입력 태스크

static void inputTask(void* /*arg*/) {
    EdgeMsg edge;
    for (;;) {
        // 1) ISR이 쌓아 둔 접점 변화를 모두 처리
        while (xQueueReceive(edgeQueue, &edge, 0) == pdTRUE) {
            uint8_t kind;
            bool down = (edge.level == LOW);          // 풀업: LOW = 눌림

            // 상태머신은 ms 로 판단하는데 ISR은 us 로 찍습니다. micros()는 약 71분마다
            // 한 바퀴 돌아 millis()와 어긋나므로, "지금으로부터 얼마나 지난 접점인지"를
            // 빼는 방식으로 옮깁니다(부호 없는 뺄셈이라 자릿수가 넘어가도 정확합니다).
            uint32_t ageMs  = (uint32_t)(micros() - edge.stampUs) / 1000;
            uint32_t edgeMs = millis() - ageMs;

            if (fsm[edge.index].onEdge(down, edgeMs, kind)) {
                PressMsg press = {(uint8_t)(edge.index + 1), kind, edge.stampUs};
                xQueueSend(pressQueue, &press, 0);
            }
        }

        // 2) 누르고 있는 동안의 "길게 누름" 확정
        uint32_t nowMs = millis();
        for (uint8_t i = 0; i < BUTTON_COUNT; i++) {
            uint8_t kind;
            if (fsm[i].tick(nowMs, kind)) {
                PressMsg press = {(uint8_t)(i + 1), kind, micros()};
                xQueueSend(pressQueue, &press, 0);
            }
        }

        vTaskDelay(pdMS_TO_TICKS(10));
    }
}

// ---------------------------------------------------------------- 프로토콜 태스크

static uint8_t nextSeq() {
    static uint8_t seq = 0;
    seq++;
    if (seq == 0) {
        seq = 1;                                  // 0은 "시퀀스 없음"이라 건너뜁니다
    }
    return seq;
}

static void writeFrame(uint8_t seq, uint8_t type, const uint8_t* payload, uint8_t len) {
    uint8_t buf[MAX_FRAME];
    uint8_t n = encode(seq, type, payload, len, buf);
    if (n == 0) {
        return;
    }
    Serial.write(buf, n);
    Serial.flush();
}

static void sendHello() {
    // caps: 기기가 가진 기능 비트. 지금은 출력 장치가 없어 0입니다.
    uint8_t p[5] = {PROTO_VERSION, FW_MAJOR, FW_MINOR, BUTTON_COUNT, 0x00};
    writeFrame(0, T_HELLO, p, sizeof(p));
}

static void sendStats() {
    uint16_t crcErr = (uint16_t)rx.crcErrors;
    uint16_t up     = (uint16_t)(millis() / 1000);
    uint8_t p[10] = {
        (uint8_t)(stats.sent & 0xFF),        (uint8_t)(stats.sent >> 8),
        (uint8_t)(stats.retx & 0xFF),        (uint8_t)(stats.retx >> 8),
        (uint8_t)(stats.ackTimeout & 0xFF),  (uint8_t)(stats.ackTimeout >> 8),
        (uint8_t)(crcErr & 0xFF),            (uint8_t)(crcErr >> 8),
        (uint8_t)(up & 0xFF),                (uint8_t)(up >> 8)
    };
    writeFrame(0, T_STATS, p, sizeof(p));
}

static void protoTask(void* /*arg*/) {
    PressMsg  pending;                 // ACK를 기다리는 중인 이벤트
    bool      waitingAck = false;
    uint8_t   pendingSeq = 0;
    uint8_t   retry      = 0;
    uint32_t  sentAtMs   = 0;
    bool      linkUp     = false;

    for (;;) {
        // --- 1. 폰이 연결됐는지 (USB CDC의 DTR) ---
        bool nowUp = (bool)Serial;
        if (nowUp != linkUp) {
            linkUp = nowUp;
            if (linkUp) {
                rx.reset();
                sendHello();
            }
        }

        // --- 2. 폰이 보낸 바이트 해석 ---
        Frame f;
        while (Serial.available() > 0) {
            uint8_t b = (uint8_t)Serial.read();
            if (!rx.push(b, f)) {
                continue;
            }
            switch (f.type) {
                case T_ACK:
                    if (waitingAck && f.u8(0) == pendingSeq) {
                        waitingAck = false;         // 잘 도착했음 — 재전송 중단
                    }
                    break;
                case T_PING:
                    sendHello();
                    sendStats();
                    break;
                default:
                    break;
            }
        }

        // --- 3. 확정된 누름을 프레임으로 내보내기 ---
        if (!waitingAck && xQueueReceive(pressQueue, &pending, 0) == pdTRUE) {
            uint32_t latency = micros() - pending.stampUs;
            if (latency > 65535) {
                latency = 65535;
            }
            stats.lastLatencyUs = (uint16_t)latency;
            if (latency > stats.maxLatencyUs) {
                stats.maxLatencyUs = (uint16_t)latency;
            }

            uint8_t p[4] = {pending.button, pending.kind,
                            (uint8_t)(latency & 0xFF), (uint8_t)(latency >> 8)};
            pendingSeq = nextSeq();
            writeFrame(pendingSeq, T_EVT_PRESS, p, sizeof(p));

#if VK_LATENCY_PROBE
            digitalWrite(PROBE_PIN, LOW);   // 관측 핀 펄스 끝 = 송출 완료
#endif
            stats.sent++;
            waitingAck = true;
            retry = 0;
            sentAtMs = millis();
        }

        // --- 4. ACK가 안 오면 다시 보내고, 세 번 다 실패하면 횟수를 기록 ---
        if (waitingAck && (uint32_t)(millis() - sentAtMs) >= ACK_TIMEOUT_MS) {
            if (retry < MAX_RETRY - 1) {
                retry++;
                stats.retx++;
                uint32_t latency = stats.lastLatencyUs;
                uint8_t p[4] = {pending.button, pending.kind,
                                (uint8_t)(latency & 0xFF), (uint8_t)(latency >> 8)};
                writeFrame(pendingSeq, T_EVT_PRESS, p, sizeof(p));  // SEQ 그대로 = 폰이 중복 실행 안 함
                sentAtMs = millis();
            } else {
                // 세 번을 다 보냈는데도 답이 없습니다. 기기에는 알릴 방법이 없으므로
                // 횟수만 세어 두고, 앱의 자가진단 화면이 STATS로 읽어 가게 합니다.
                waitingAck = false;
                stats.ackTimeout++;
            }
        }

        vTaskDelay(pdMS_TO_TICKS(5));
    }
}

// ---------------------------------------------------------------- setup / loop

void setup() {
    Serial.begin(115200);
#if ARDUINO_USB_CDC_ON_BOOT
    // 폰이 안 꽂혀 있을 때 Serial.write가 무한정 붙들리지 않게 합니다.
    // (이게 없으면 케이블을 뺀 채 버튼을 누를 때 태스크가 멈춰 버립니다.)
    Serial.setTxTimeoutMs(20);
#endif

    for (uint8_t i = 0; i < BUTTON_COUNT; i++) {
        pinMode(BUTTON_PINS[i], INPUT_PULLUP);
    }
#if VK_LATENCY_PROBE
    pinMode(PROBE_PIN, OUTPUT);
    digitalWrite(PROBE_PIN, LOW);
#endif

    edgeQueue   = xQueueCreate(32, sizeof(EdgeMsg));
    pressQueue  = xQueueCreate(8,  sizeof(PressMsg));

    // 두 태스크 모두 core 1에 올립니다 (core 0은 USB·시스템이 씁니다).
    xTaskCreatePinnedToCore(inputTask,  "input",  3072, nullptr, 4, nullptr, 1);
    xTaskCreatePinnedToCore(protoTask,  "proto",  4096, nullptr, 3, nullptr, 1);

    // 큐와 태스크가 준비된 뒤에 인터럽트를 붙입니다(먼저 붙이면 큐가 없어 죽습니다).
    for (uint8_t i = 0; i < BUTTON_COUNT; i++) {
        attachInterruptArg(digitalPinToInterrupt(BUTTON_PINS[i]),
                           onButtonEdge, (void*)(uint32_t)i, CHANGE);
    }
}

void loop() {
    // 실제 일은 전부 태스크가 합니다. 기본 loop 태스크는 잠들어 있게 둡니다.
    vTaskDelay(pdMS_TO_TICKS(1000));
}
