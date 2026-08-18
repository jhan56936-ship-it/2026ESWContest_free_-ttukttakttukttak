package com.example.vibekey;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

/**
 * 버튼(하드웨어 버튼 · 삼성 루틴 · 화면의 "눌러 보기")에서 공통으로 쓰는 앱 실행 도우미입니다.
 * 실행 결과를 소리와 진동으로도 알려 주어, 화면을 못 보셔도 알 수 있게 합니다.
 */
public final class AppLauncher {

    private static final String TAG = "VibeKeyLauncher";

    private AppLauncher() {
    }

    /**
     * 지정한 버튼 번호에 연결된 앱을 실행합니다.
     *
     * @param source 어디서 눌렀는지 (기록/루틴 알림용): "hardware", "routine", "screen"
     * @return 실제로 앱을 열었으면 true
     */
    public static boolean runSlot(Context context, int slot, String source) {
        Prefs prefs = Prefs.with(context);
        String packageName = prefs.getSlotPackage(slot);
        String label = prefs.getSlotLabel(slot);

        if (TextUtils.isEmpty(packageName)) {
            String message = slot + "번 버튼에 연결된 앱이 아직 없어요.";
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
            SpeechManager.get(context).speakIfEnabled(context, message);
            return false;
        }

        boolean launched = launchPackage(context, packageName);
        if (launched) {
            Haptics.success(context);
            String spoken = TextUtils.isEmpty(label) ? "앱을 엽니다." : label + "을(를) 엽니다.";
            SpeechManager.get(context).speakIfEnabled(context, spoken);
            RoutineBridge.notifyButtonPressed(context, slot, packageName, label, source);
        } else {
            String message = (TextUtils.isEmpty(label) ? "그 앱" : label) + "이(가) 휴대폰에 없어요. 다시 정해 주세요.";
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
            SpeechManager.get(context).speakIfEnabled(context, message);
        }
        return launched;
    }

    /** 패키지명으로 앱을 실행합니다. 설치돼 있지 않으면 false 를 돌려 줍니다. */
    public static boolean launchPackage(Context context, String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return false;
        }
        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (launchIntent == null) {
            Log.w(TAG, "Target app not installed: " + packageName);
            return false;
        }
        // 서비스·리시버(=백그라운드)에서도 화면을 띄울 수 있도록 필수 플래그를 붙입니다.
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            context.startActivity(launchIntent);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch " + packageName, e);
            return false;
        }
    }
}
