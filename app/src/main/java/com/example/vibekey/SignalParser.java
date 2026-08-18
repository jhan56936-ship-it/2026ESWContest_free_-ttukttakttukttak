package com.example.vibekey;

import java.util.Locale;

/**
 * 기기(ESP32-S3 등)가 시리얼로 보내 온 한 줄을 "무엇을 하라는 뜻인지"로 바꿉니다.
 *
 * 안드로이드 클래스를 쓰지 않는 순수 자바 로직이라 단위 테스트로 바로 검증할 수 있습니다.
 * (app/src/test/java/com/example/vibekey/SignalParserTest.java)
 */
public final class SignalParser {

    /** 신호 한 줄이 뜻하는 동작 */
    public enum Action {
        /** 1·2·3번 버튼에 연결된 앱 실행 */
        RUN_SLOT,
        /** AI 도우미 열기 (버튼 길게 누름) */
        OPEN_AI,
        /** 알아듣지 못한 신호 */
        UNKNOWN
    }

    /** 해석 결과. RUN_SLOT 일 때만 slot(1~3) 값이 뜻이 있습니다. */
    public static final class Result {
        public final Action action;
        public final int slot;

        Result(Action action, int slot) {
            this.action = action;
            this.slot = slot;
        }

        public boolean isRunSlot() {
            return action == Action.RUN_SLOT;
        }

        @Override
        public String toString() {
            return action == Action.RUN_SLOT ? "RUN_SLOT(" + slot + ")" : action.name();
        }
    }

    private static final Result UNKNOWN = new Result(Action.UNKNOWN, 0);
    private static final Result OPEN_AI = new Result(Action.OPEN_AI, 0);

    private SignalParser() {
    }

    /**
     * 받아들이는 신호
     *   짧게 누름 : "True", "Button1", "1"  /  "Button2", "2"  /  "Button3", "3"
     *   길게 누름 : "AI", "Long1", "ButtonLong1"
     *
     * 앞뒤 공백과 캐리지리턴(\r), 대소문자는 무시합니다.
     */
    public static Result parse(String rawLine) {
        if (rawLine == null) {
            return UNKNOWN;
        }
        String signal = rawLine.trim().toLowerCase(Locale.ROOT);
        if (signal.isEmpty()) {
            return UNKNOWN;
        }

        // 길게 누름을 먼저 봅니다. ("buttonlong1" 이 "button1" 로 잘못 읽히지 않도록)
        if (signal.equals("ai") || signal.startsWith("long") || signal.startsWith("buttonlong")) {
            return OPEN_AI;
        }

        switch (signal) {
            case "true":
            case "button1":
            case "1":
                return new Result(Action.RUN_SLOT, 1);
            case "button2":
            case "2":
                return new Result(Action.RUN_SLOT, 2);
            case "button3":
            case "3":
                return new Result(Action.RUN_SLOT, 3);
            default:
                return UNKNOWN;
        }
    }
}
