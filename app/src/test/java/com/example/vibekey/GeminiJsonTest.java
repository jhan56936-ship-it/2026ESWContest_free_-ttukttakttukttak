package com.example.vibekey;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.json.JSONObject;
import org.junit.Test;

/**
 * 제미나이가 돌려준 글에서 JSON을 꺼내는 부분을 검사합니다.
 * 모델은 형식을 지키라고 해도 ```json 으로 감싸거나 앞뒤에 말을 붙이는 일이 잦아,
 * 이 부분이 약하면 AI 답이 통째로 날아갑니다.
 */
public class GeminiJsonTest {

    @Test
    public void 순수한_JSON을_읽는다() {
        JSONObject json = GeminiClient.parseLooseJson("{\"answer\":\"안녕하세요\"}");
        assertNotNull(json);
        assertEquals("안녕하세요", json.optString("answer"));
    }

    @Test
    public void 코드블록으로_감싼_JSON을_읽는다() {
        String raw = "```json\n{\"answer\":\"약을 드실 시간이에요\",\"openPackage\":\"\"}\n```";
        JSONObject json = GeminiClient.parseLooseJson(raw);
        assertNotNull(json);
        assertEquals("약을 드실 시간이에요", json.optString("answer"));
    }

    @Test
    public void 앞뒤에_설명이_붙어도_JSON만_꺼낸다() {
        String raw = "네, 알겠습니다.\n{\"answer\":\"지도를 열게요\",\"openPackage\":\"com.kakao.map\"}\n필요하시면 말씀하세요.";
        JSONObject json = GeminiClient.parseLooseJson(raw);
        assertNotNull(json);
        assertEquals("com.kakao.map", json.optString("openPackage"));
    }

    @Test
    public void 중첩된_JSON도_끝까지_읽는다() {
        String raw = "{\"suggestions\":[{\"slot\":1,\"packageName\":\"com.kakao.talk\"}]}";
        JSONObject json = GeminiClient.parseLooseJson(raw);
        assertNotNull(json);
        assertNotNull(json.optJSONArray("suggestions"));
        assertEquals(1, json.optJSONArray("suggestions").length());
    }

    @Test
    public void JSON이_없으면_null을_돌려준다() {
        assertNull(GeminiClient.parseLooseJson("죄송해요. 잘 모르겠어요."));
        assertNull(GeminiClient.parseLooseJson(""));
        assertNull(GeminiClient.parseLooseJson("   "));
        assertNull(GeminiClient.parseLooseJson(null));
    }

    @Test
    public void 망가진_JSON은_null을_돌려준다() {
        assertNull(GeminiClient.parseLooseJson("{\"answer\": "));
    }
}
