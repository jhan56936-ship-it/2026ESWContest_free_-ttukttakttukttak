/*
 * haptics.h — 진동 모터 패턴 재생기 (LEDC PWM)
 *
 * 작품 이름이 바이브키(Vibe-key)인데 정작 기기에는 진동이 없었습니다.
 * "화면을 보지 않고 실행한다"고 말하려면, 눌렸는지 / 됐는지 / 안 됐는지를
 * 기기가 손끝으로 대답해야 합니다. 그 대답을 여기서 만듭니다.
 *
 * 두 가지를 지켰습니다.
 *   1. delay() 를 쓰지 않습니다. 진동을 내는 동안에도 버튼 입력과 프레임 송수신이
 *      멈추면 안 되기 때문에, 패턴을 (세기, 시간) 단계 표로 만들어 전용 태스크가
 *      vTaskDelay 로 넘깁니다.
 *   2. PWM 주파수를 20kHz(가청 범위 위)로 잡아 모터가 "삐-" 하고 우는 소리를 없앴습니다.
 *      세기는 duty 로 조절하므로 같은 모터로 "톡"과 "웅-"을 구분할 수 있습니다.
 */
#ifndef VK_HAPTICS_H
#define VK_HAPTICS_H

#include <Arduino.h>
#include "vkp_frame.h"

namespace vkp {

/** 한 단계: duty(0~255)로 세기, ms로 시간. {0,0} 이면 패턴 끝. */
struct HapticStep {
    uint8_t  duty;
    uint16_t ms;
};

static const uint8_t HAPTIC_MAX_STEPS = 8;

// 패턴 표 --------------------------------------------------------------------
// 버튼마다 다른 촉감을 줘야 "몇 번 버튼이 눌렸는지"를 화면 없이 알 수 있습니다.
static const HapticStep HAPTIC_BTN1[] = { {200, 60}, {0, 0} };                                  // 톡
static const HapticStep HAPTIC_BTN2[] = { {200, 60}, {0, 90}, {200, 60}, {0, 0} };              // 톡톡
static const HapticStep HAPTIC_BTN3[] = { {200, 220}, {0, 0} };                                 // 우웅
static const HapticStep HAPTIC_AI[]   = { {160, 60}, {0, 70}, {220, 240}, {0, 0} };             // 톡-우웅 (AI)
static const HapticStep HAPTIC_OK[]   = { {255, 40}, {0, 60}, {255, 40}, {0, 0} };              // 산뜻한 두 번 = 됐어요
static const HapticStep HAPTIC_FAIL[] = { {140, 200}, {0, 120}, {140, 200}, {0, 120},
                                          {140, 200}, {0, 0} };                                  // 무거운 세 번 = 안 됐어요
static const HapticStep HAPTIC_LINK[] = { {120, 50}, {0, 60}, {180, 50}, {0, 60},
                                          {240, 80}, {0, 0} };                                   // 점점 세게 = 연결됨
static const HapticStep HAPTIC_LOST[] = { {255, 500}, {0, 0} };                                  // 길게 한 번 = 폰이 못 받음

/** 패턴 번호 → 표. 모르는 번호면 nullptr. */
inline const HapticStep* hapticPattern(uint8_t id) {
    switch (id) {
        case P_BTN1: return HAPTIC_BTN1;
        case P_BTN2: return HAPTIC_BTN2;
        case P_BTN3: return HAPTIC_BTN3;
        case P_AI:   return HAPTIC_AI;
        case P_OK:   return HAPTIC_OK;
        case P_FAIL: return HAPTIC_FAIL;
        case P_LINK: return HAPTIC_LINK;
        case P_LOST: return HAPTIC_LOST;
        default:     return nullptr;
    }
}

/** 버튼 번호(1~3)와 누름 종류에 맞는 "눌렸다" 진동 */
inline uint8_t hapticForPress(uint8_t button, uint8_t kind) {
    if (kind == K_LONG) {
        return P_AI;
    }
    switch (button) {
        case 1:  return P_BTN1;
        case 2:  return P_BTN2;
        case 3:  return P_BTN3;
        default: return P_BTN1;
    }
}

// LEDC 얇은 껍데기 ------------------------------------------------------------
// ESP32 Arduino 코어 3.x에서 LEDC API가 바뀌어(채널 → 핀 기준) 양쪽 모두 지원합니다.

static const uint32_t HAPTIC_PWM_FREQ = 20000;  // 20kHz — 사람 귀에 안 들림
static const uint8_t  HAPTIC_PWM_BITS = 8;      // 0~255
static const uint8_t  HAPTIC_LEDC_CH  = 0;      // (코어 2.x 전용)

inline void hapticBegin(uint8_t pin) {
#if ESP_ARDUINO_VERSION_MAJOR >= 3
    ledcAttach(pin, HAPTIC_PWM_FREQ, HAPTIC_PWM_BITS);
    ledcWrite(pin, 0);
#else
    ledcSetup(HAPTIC_LEDC_CH, HAPTIC_PWM_FREQ, HAPTIC_PWM_BITS);
    ledcAttachPin(pin, HAPTIC_LEDC_CH);
    ledcWrite(HAPTIC_LEDC_CH, 0);
#endif
}

inline void hapticDuty(uint8_t pin, uint8_t duty) {
#if ESP_ARDUINO_VERSION_MAJOR >= 3
    ledcWrite(pin, duty);
#else
    (void)pin;
    ledcWrite(HAPTIC_LEDC_CH, duty);
#endif
}

}  // namespace vkp

#endif  // VK_HAPTICS_H
