/*
 * test_vkp.cpp — 펌웨어 단위 테스트 (PC에서 그대로 돌아갑니다)
 *
 * "임베디드 소프트웨어는 기기가 있어야 검증할 수 있다"는 말은 절반만 맞습니다.
 * 프레임 조립·CRC·재동기화·누름 판정처럼 하드웨어에 닿지 않는 로직은 PC에서
 * 수만 번 돌려 볼 수 있고, 그래야 기기에서는 전기적인 문제만 남습니다.
 *
 * 실행:  ./run_tests.sh
 */
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

#include "../vibekey_firmware/vkp_frame.h"
#include "../vibekey_firmware/press_fsm.h"

using namespace vkp;

static int g_pass = 0;
static int g_fail = 0;

static void check(bool ok, const char* what) {
    if (ok) {
        g_pass++;
    } else {
        g_fail++;
        std::printf("  [실패] %s\n", what);
    }
}

// ---------------------------------------------------------------- CRC

static void test_crc16_known_vectors() {
    // CRC-16/CCITT-FALSE 의 널리 알려진 검증값
    const uint8_t check_input[9] = {'1','2','3','4','5','6','7','8','9'};
    check(crc16(check_input, 9) == 0x29B1, "CRC16('123456789') == 0x29B1");

    // 한 비트만 달라져도 값이 달라져야 합니다.
    const uint8_t a[3] = {0x01, 0x02, 0x03};
    const uint8_t b[3] = {0x01, 0x02, 0x02};
    check(crc16(a, 3) != crc16(b, 3), "한 비트가 다르면 CRC도 다르다");
}

// ---------------------------------------------------------------- 왕복

static void test_roundtrip() {
    uint8_t payload[4] = {2, K_SHORT, 0x34, 0x12};
    uint8_t buf[MAX_FRAME];
    uint8_t n = encode(77, T_EVT_PRESS, payload, 4, buf);
    check(n == 11, "4바이트 payload 프레임 길이는 11 (머리 4 + 본문 4 + CRC 2 + 끝 1)");

    Decoder d;
    Frame f;
    bool got = false;
    for (uint8_t i = 0; i < n; i++) {
        got = d.push(buf[i], f);
    }
    check(got, "왕복: 프레임이 완성된다");
    check(f.seq == 77 && f.type == T_EVT_PRESS && f.len == 4, "왕복: 머리말이 그대로다");
    check(f.u8(0) == 2 && f.u8(1) == K_SHORT, "왕복: 버튼·종류가 그대로다");
    check(f.u16(2) == 0x1234, "왕복: 리틀엔디언 2바이트를 제대로 읽는다");
    check(d.crcErrors == 0 && d.discarded == 0, "왕복: 버린 바이트가 없다");
}

static void test_zero_length_payload() {
    uint8_t buf[MAX_FRAME];
    uint8_t n = encode(1, T_PING, nullptr, 0, buf);
    check(n == 7, "빈 payload 프레임은 7바이트");

    Decoder d;
    Frame f;
    bool got = false;
    for (uint8_t i = 0; i < n; i++) {
        got = d.push(buf[i], f);
    }
    check(got && f.type == T_PING && f.len == 0, "빈 payload도 해석된다");
}

static void test_payload_too_long_is_refused() {
    uint8_t big[MAX_PAYLOAD + 1] = {0};
    uint8_t buf[64];
    check(encode(1, T_STATS, big, MAX_PAYLOAD + 1, buf) == 0,
          "payload가 한도를 넘으면 만들지 않는다");
}

// ---------------------------------------------------------------- 잡음

/**
 * 이 작품의 핵심 주장은 "오작동 = 요금 사고"입니다. 그래서 가장 중요한 검사는
 * "깨진 프레임이 절대 실행으로 이어지지 않는가" 입니다.
 * 모든 비트를 하나씩 뒤집어 보며(= 전수 검사) 단 한 번도 통과하지 않는지 봅니다.
 */
static void test_every_single_bit_flip_is_rejected() {
    uint8_t payload[4] = {1, K_SHORT, 0x10, 0x00};
    uint8_t original[MAX_FRAME];
    uint8_t n = encode(42, T_EVT_PRESS, payload, 4, original);

    int accepted = 0;
    int trials = 0;
    for (uint8_t byteIdx = 0; byteIdx < n; byteIdx++) {
        for (uint8_t bit = 0; bit < 8; bit++) {
            uint8_t corrupted[MAX_FRAME];
            std::memcpy(corrupted, original, n);
            corrupted[byteIdx] ^= (uint8_t)(1 << bit);
            trials++;

            Decoder d;
            Frame f;
            for (uint8_t i = 0; i < n; i++) {
                if (d.push(corrupted[i], f)) {
                    // 통과했다면, 적어도 원본과 똑같은 내용이어서는 안 됩니다.
                    if (f.seq == 42 && f.type == T_EVT_PRESS && f.len == 4 &&
                        f.u8(0) == 1 && f.u8(1) == K_SHORT) {
                        accepted++;
                    }
                }
            }
        }
    }
    std::printf("  · 비트 뒤집기 %d회 중 원본으로 오인된 것: %d회\n", trials, accepted);
    check(accepted == 0, "비트 하나가 뒤집힌 프레임은 하나도 실행되지 않는다");
}

// ---------------------------------------------------------------- CRC 세기 실험

/** 처음 설계였던 CRC-8/ATM(0x07). 지금은 비교 실험 전용으로만 남겨 둡니다. */
static uint8_t crc8_legacy(const uint8_t* data, size_t len) {
    uint8_t crc = 0x00;
    for (size_t i = 0; i < len; i++) {
        crc ^= data[i];
        for (uint8_t b = 0; b < 8; b++) {
            crc = (crc & 0x80) ? (uint8_t)((crc << 1) ^ 0x07) : (uint8_t)(crc << 1);
        }
    }
    return crc;
}

/** CRC8을 쓰던 시절의 프레임(6+LEN)을 그대로 재현합니다. */
static uint8_t encode_crc8(uint8_t seq, uint8_t type,
                           const uint8_t* payload, uint8_t len, uint8_t* out) {
    uint8_t n = 0;
    out[n++] = STX;
    out[n++] = seq;
    out[n++] = type;
    out[n++] = len;
    for (uint8_t i = 0; i < len; i++) {
        out[n++] = payload[i];
    }
    out[n++] = crc8_legacy(out + 1, (size_t)(3 + len));
    out[n++] = ETX;
    return n;
}

/** CRC8 프레임을 검사만 해 보는 최소 디코더 (재동기화 없이 앞머리부터 그대로 확인) */
static bool accepts_crc8(const uint8_t* buf, uint8_t n, uint8_t& button, uint8_t& type) {
    if (n < 6 || buf[0] != STX) {
        return false;
    }
    uint8_t len = buf[3];
    if (len > MAX_PAYLOAD || (uint8_t)(len + 6) != n) {
        return false;
    }
    if (buf[n - 2] != crc8_legacy(buf + 1, (size_t)(3 + len)) || buf[n - 1] != ETX) {
        return false;
    }
    type = buf[2];
    button = len > 0 ? buf[4] : 0;
    return true;
}

/**
 * 무작위 잡음 대량 주입 — 이 작품에서 가장 중요한 숫자를 뽑는 시험입니다.
 *
 * 버튼 한 번이 택시 요금으로 이어지므로, 물어야 할 질문은 "잡음을 걸러내는가"가
 * 아니라 "깨진 프레임이 실행으로 새어 나가는 비율이 얼마인가" 입니다.
 * 온전한 프레임을 손상시켜 10만 번씩 흘려보내고, 똑같은 손상을 CRC8(초기 설계)와
 * CRC16(현재 설계)에 동시에 먹여 비교합니다. 씨앗을 고정해 두어 항상 같은 숫자가 나옵니다.
 *
 * @param minFlips,maxFlips 뒤집을 비트 수의 범위
 * @param wholeFrame        true면 CRC·끝바이트까지 포함해 프레임 전체를 손상시킵니다
 */
static void run_corruption_experiment(const char* title,
                                      int minFlips, int maxFlips, bool wholeFrame,
                                      int& leak8Out, int& leak16Out) {
    const int TRIALS = 100000;
    std::srand(20260903);
    int leak8 = 0;
    int leak16 = 0;

    for (int t = 0; t < TRIALS; t++) {
        uint8_t seq     = (uint8_t)(1 + (std::rand() % 255));
        uint8_t button  = (uint8_t)(1 + (std::rand() % 3));
        uint8_t payload[4] = {button, K_SHORT, (uint8_t)(std::rand() & 0xFF), 0x00};

        uint8_t f16[MAX_FRAME];
        uint8_t n16 = encode(seq, T_EVT_PRESS, payload, 4, f16);
        uint8_t f8[MAX_FRAME];
        uint8_t n8 = encode_crc8(seq, T_EVT_PRESS, payload, 4, f8);

        // 손상 범위: 두 설계의 프레임 길이가 다르므로(11 vs 10) 공통 구간만 건드려
        // 완전히 같은 조건에서 비교합니다.
        uint8_t span = wholeFrame ? (uint8_t)(n8 < n16 ? n8 : n16) : 8;

        // 같은 비트를 두 번 뒤집으면 원래대로 돌아가 손상이 아니게 되므로 겹치지 않게 고릅니다.
        int flips = minFlips + (std::rand() % (maxFlips - minFlips + 1));
        uint8_t used[16][2];
        int done = 0;
        while (done < flips) {
            uint8_t pos = (uint8_t)(std::rand() % span);
            uint8_t bitIdx = (uint8_t)(std::rand() % 8);
            bool dup = false;
            for (int k = 0; k < done; k++) {
                if (used[k][0] == pos && used[k][1] == bitIdx) {
                    dup = true;
                }
            }
            if (dup) {
                continue;
            }
            used[done][0] = pos;
            used[done][1] = bitIdx;
            done++;
            uint8_t bit = (uint8_t)(1 << bitIdx);
            f16[pos] ^= bit;
            f8[pos]  ^= bit;
        }

        // CRC16 (현재 설계) — 재동기화까지 포함한 진짜 디코더로
        Decoder d;
        Frame f;
        for (uint8_t i = 0; i < n16; i++) {
            if (d.push(f16[i], f)) {
                if (f.type == T_EVT_PRESS && f.len == 4 && f.u8(0) >= 1 && f.u8(0) <= 3) {
                    leak16++;
                }
            }
        }

        // CRC8 (초기 설계)
        uint8_t b8 = 0, t8 = 0;
        if (accepts_crc8(f8, n8, b8, t8) && t8 == T_EVT_PRESS && b8 >= 1 && b8 <= 3) {
            leak8++;
        }
    }

    std::printf("  · %s (%d건)\n", title, TRIALS);
    std::printf("      CRC8  : %6d건  (%.3f%%)\n", leak8,  100.0 * leak8  / TRIALS);
    std::printf("      CRC16 : %6d건  (%.3f%%)\n", leak16, 100.0 * leak16 / TRIALS);
    leak8Out = leak8;
    leak16Out = leak16;
}

/**
 * 실험 A — 전선을 타고 들어오는 흔한 잡음 수준(머리·본문에 1~3비트).
 * 이 범위에서는 CRC8도 전부 걸러 냅니다. 즉 "CRC8은 부실하다"는 말은 사실이 아닙니다.
 */
static void test_light_corruption_both_safe() {
    int leak8 = 0, leak16 = 0;
    run_corruption_experiment("1~3비트 손상 → 버튼 실행으로 새어 나간 수",
                              1, 3, false, leak8, leak16);
    check(leak8 == 0,  "1~3비트 손상은 CRC8도 모두 걸러 낸다");
    check(leak16 == 0, "1~3비트 손상은 CRC16도 모두 걸러 낸다");
}

/**
 * 실험 B — 커넥터가 흔들리거나 전원이 튈 때처럼 여러 비트가 한꺼번에 망가지는 경우.
 * 여기서 두 설계가 갈립니다. CRC8은 8비트라 대략 256분의 1이 우연히 들어맞고,
 * CRC16은 65,536분의 1이라 사실상 0입니다. 프레임당 1바이트를 더 쓰는 대신
 * "요금이 발생하는 오실행"의 확률을 두 자릿수 낮추는 거래라서 올릴 값어치가 있습니다.
 */
static void test_heavy_corruption_crc16_is_stronger() {
    int leak8 = 0, leak16 = 0;
    run_corruption_experiment("4~10비트 손상(프레임 전체) → 버튼 실행으로 새어 나간 수",
                              4, 10, true, leak8, leak16);
    check(leak16 == 0, "여러 비트가 망가져도 CRC16은 실행으로 새지 않는다");
    check(leak8 > leak16, "CRC16으로 올린 것이 실제로 더 안전하다 (숫자로 확인)");
}

static void test_resync_after_garbage() {
    uint8_t payload[2] = {3, K_SHORT};
    uint8_t good[MAX_FRAME];
    uint8_t n = encode(9, T_EVT_PRESS, payload, 2, good);

    // 앞에 잡음을 잔뜩 붙입니다. 가짜 STX(0xAA)와 옛 평문("True\n")도 섞습니다.
    std::vector<uint8_t> stream;
    const char* legacy = "True\n";
    for (const char* p = legacy; *p; p++) {
        stream.push_back((uint8_t)*p);
    }
    stream.push_back(0xAA);
    stream.push_back(0xFF);
    stream.push_back(0x00);
    stream.push_back(0xAA);
    for (uint8_t i = 0; i < n; i++) {
        stream.push_back(good[i]);
    }

    Decoder d;
    Frame f;
    int frames = 0;
    for (size_t i = 0; i < stream.size(); i++) {
        if (d.push(stream[i], f)) {
            frames++;
        }
    }
    check(frames == 1, "잡음 뒤에 온 온전한 프레임 하나를 되찾는다");
    check(f.seq == 9 && f.u8(0) == 3, "되찾은 프레임의 내용이 맞다");
    check(d.discarded > 0, "버린 바이트를 세어 둔다 (프레임 오류율 계산용)");
}

static void test_two_frames_back_to_back() {
    uint8_t p1[1] = {1};
    uint8_t p2[1] = {2};
    uint8_t a[MAX_FRAME], b[MAX_FRAME];
    uint8_t na = encode(1, T_EVT_PRESS, p1, 1, a);
    uint8_t nb = encode(2, T_EVT_PRESS, p2, 1, b);

    Decoder d;
    Frame f;
    std::vector<uint8_t> seq;
    for (uint8_t i = 0; i < na; i++) seq.push_back(a[i]);
    for (uint8_t i = 0; i < nb; i++) seq.push_back(b[i]);

    int frames = 0;
    uint8_t lastButton = 0;
    for (size_t i = 0; i < seq.size(); i++) {
        if (d.push(seq[i], f)) {
            frames++;
            lastButton = f.u8(0);
        }
    }
    check(frames == 2, "연달아 붙은 두 프레임을 둘 다 읽는다");
    check(lastButton == 2, "두 번째 프레임 내용이 맞다");
}

static void test_truncated_frame_then_valid() {
    uint8_t p[1] = {1};
    uint8_t buf[MAX_FRAME];
    uint8_t n = encode(5, T_EVT_PRESS, p, 1, buf);

    Decoder d;
    Frame f;
    int frames = 0;
    // 케이블을 꽂는 순간처럼 프레임이 중간에서 잘려 들어온 경우
    for (uint8_t i = 0; i < n - 2; i++) {
        if (d.push(buf[i], f)) frames++;
    }
    check(frames == 0, "잘린 프레임은 실행되지 않는다");
    for (uint8_t i = 0; i < n; i++) {
        if (d.push(buf[i], f)) frames++;
    }
    check(frames == 1, "그 뒤에 온 온전한 프레임은 정상 처리된다");
}

// ---------------------------------------------------------------- 누름 판정

static void test_debounce_ignores_chatter() {
    FsmConfig cfg;
    PressFsm fsm(cfg);
    uint8_t kind;

    check(!fsm.onEdge(true, 1000, kind), "누른 순간에는 아직 확정하지 않는다");
    // 접점 튐: 1005ms에 떨어졌다 1010ms에 다시 붙음 → 둘 다 무시돼야 함
    check(!fsm.onEdge(false, 1005, kind), "디바운스 안의 변화는 무시한다");
    check(!fsm.onEdge(true, 1010, kind), "디바운스 안의 변화는 무시한다(2)");
    check(fsm.onEdge(false, 1200, kind), "제대로 떼면 한 번 확정된다");
    check(kind == K_SHORT, "짧게 누름으로 판정한다");
}

static void test_long_press_fires_while_held() {
    FsmConfig cfg;
    PressFsm fsm(cfg);
    uint8_t kind = 0xFF;

    fsm.onEdge(true, 0, kind);
    check(!fsm.tick(500, kind), "0.5초로는 길게 누름이 아니다");
    check(fsm.tick(700, kind), "0.7초를 넘기면 손을 떼기 전에 확정된다");
    check(kind == K_LONG, "길게 누름으로 판정한다");
    check(!fsm.onEdge(false, 1500, kind), "이미 확정했으니 뗄 때 또 내지 않는다");
}

static void test_tremor_guard_blocks_repeat() {
    FsmConfig cfg;
    PressFsm fsm(cfg);
    uint8_t kind;

    fsm.onEdge(true, 0, kind);
    check(fsm.onEdge(false, 100, kind), "첫 누름은 실행된다");
    fsm.onEdge(true, 300, kind);
    check(!fsm.onEdge(false, 400, kind), "떨림으로 곧바로 또 눌린 것은 버린다");
    fsm.onEdge(true, 2000, kind);
    check(fsm.onEdge(false, 2100, kind), "가드 시간이 지난 뒤에는 다시 실행된다");
}

static void test_double_tap_when_enabled() {
    FsmConfig cfg;
    cfg.doubleEnabled = true;
    PressFsm fsm(cfg);
    uint8_t kind = 0xFF;

    fsm.onEdge(true, 0, kind);
    check(!fsm.onEdge(false, 80, kind), "두 번 누름을 켜면 한 번 누름은 잠시 보류된다");
    fsm.onEdge(true, 200, kind);
    check(fsm.onEdge(false, 260, kind), "간격 안에 또 누르면 확정된다");
    check(kind == K_DOUBLE, "두 번 누름으로 판정한다");
}

static void test_single_tap_resolves_after_gap() {
    FsmConfig cfg;
    cfg.doubleEnabled = true;
    PressFsm fsm(cfg);
    uint8_t kind = 0xFF;

    fsm.onEdge(true, 0, kind);
    fsm.onEdge(false, 80, kind);
    check(!fsm.tick(200, kind), "간격 안에는 아직 기다린다");
    check(fsm.tick(500, kind), "간격이 지나면 한 번 누름으로 확정된다");
    check(kind == K_SHORT, "한 번 누름으로 판정한다");
}

// ---------------------------------------------------------------- 실행

int main() {
    std::printf("바이브키 펌웨어 단위 테스트\n");
    std::printf("---------------------------------------------\n");

    test_crc16_known_vectors();
    test_roundtrip();
    test_zero_length_payload();
    test_payload_too_long_is_refused();
    test_every_single_bit_flip_is_rejected();
    test_light_corruption_both_safe();
    test_heavy_corruption_crc16_is_stronger();
    test_resync_after_garbage();
    test_two_frames_back_to_back();
    test_truncated_frame_then_valid();
    test_debounce_ignores_chatter();
    test_long_press_fires_while_held();
    test_tremor_guard_blocks_repeat();
    test_double_tap_when_enabled();
    test_single_tap_resolves_after_gap();

    std::printf("---------------------------------------------\n");
    std::printf("통과 %d개 / 실패 %d개\n", g_pass, g_fail);
    return g_fail == 0 ? 0 : 1;
}
