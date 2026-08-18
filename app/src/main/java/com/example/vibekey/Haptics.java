package com.example.vibekey;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * 눌렀다는 사실을 손끝으로도 알 수 있게 짧은 진동을 줍니다.
 * (설정에서 끌 수 있습니다.)
 */
public final class Haptics {

    private Haptics() {
    }

    public static void tap(Context context) {
        vibrate(context, 30);
    }

    public static void success(Context context) {
        vibrate(context, 60);
    }

    private static void vibrate(Context context, long millis) {
        if (context == null || !Prefs.with(context).isHapticOn()) {
            return;
        }
        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            //noinspection deprecation
            vibrator.vibrate(millis);
        }
    }
}
