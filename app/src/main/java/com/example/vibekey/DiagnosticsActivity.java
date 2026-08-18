package com.example.vibekey;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.content.pm.ShortcutManagerCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 테스트(자가진단) 화면.
 *
 * 이 앱은 하드웨어와 인터넷, 권한이 모두 맞아야 동작하기 때문에
 * "왜 안 되는지"를 사용자가 스스로 알 수 있어야 합니다. 그래서
 *
 *  1. 열 가지 항목을 한 번에 검사하고 ✅ / ⚠️ 로 보여 주고,
 *  2. 기기가 없어도 가짜 버튼 신호를 넣어 실제 동작을 시험해 보고,
 *  3. 기기가 보낸 신호 기록을 눈으로 확인할 수 있게
 *
 * 만들었습니다.
 */
public class DiagnosticsActivity extends BaseActivity {

    /** 검사 한 항목의 결과 */
    private static class Check {
        final String name;
        final boolean ok;
        final String detail;

        Check(String name, boolean ok, String detail) {
            this.name = name;
            this.ok = ok;
            this.detail = detail;
        }
    }

    private LinearLayout resultContainer;
    private TextView summaryText;
    private TextView logText;

    private Prefs prefs;
    private AppRepository appRepository;
    private GeminiClient gemini;

    /** 로그가 바뀌면 화면을 새로 그립니다. */
    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshLog();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diagnostics);

        prefs = Prefs.with(this);
        appRepository = new AppRepository(this);
        gemini = new GeminiClient(this);

        resultContainer = findViewById(R.id.resultContainer);
        summaryText = findViewById(R.id.testSummary);
        logText = findViewById(R.id.logText);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        findViewById(R.id.btnRunAll).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.tap(DiagnosticsActivity.this);
                runAllChecks();
            }
        });

        findViewById(R.id.btnSignal1).setOnClickListener(signalListener("Button1"));
        findViewById(R.id.btnSignal2).setOnClickListener(signalListener("Button2"));
        findViewById(R.id.btnSignal3).setOnClickListener(signalListener("Button3"));
        findViewById(R.id.btnSignalAi).setOnClickListener(signalListener("AI"));

        findViewById(R.id.btnClearLog).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SerialLog.clear();
                refreshLog();
            }
        });

        refreshLog();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(SerialLog.ACTION_LOG_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(logReceiver, filter);
        }
        refreshLog();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(logReceiver);
        } catch (IllegalArgumentException ignored) {
            // 이미 해제된 경우입니다.
        }
    }

    // ------------------------------------------------------------------ 가상 신호

    private View.OnClickListener signalListener(final String signal) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.tap(DiagnosticsActivity.this);
                UsbSerialService.simulate(DiagnosticsActivity.this, signal);
                Toast.makeText(DiagnosticsActivity.this,
                        "'" + signal + "' 신호를 넣었어요.", Toast.LENGTH_SHORT).show();
            }
        };
    }

    private void refreshLog() {
        List<String> lines = SerialLog.snapshot();
        if (lines.isEmpty()) {
            logText.setText(R.string.test_log_empty);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        logText.setText(sb.toString().trim());
    }

    // ------------------------------------------------------------------ 전체 검사

    private void runAllChecks() {
        summaryText.setText(R.string.test_running);
        resultContainer.removeAllViews();

        final List<Check> checks = new ArrayList<>();
        checks.add(checkUsbDevice());
        checks.add(checkOverlayPermission());
        checks.add(checkBatteryOptimization());
        checks.add(checkNotificationPermission());
        checks.add(checkInternet());
        checks.add(checkSpeechRecognition());
        checks.add(checkTextToSpeech());
        checks.addAll(checkSlots());
        checks.add(checkShortcuts());
        checks.add(checkOfflineKeywords());

        render(checks);

        // AI 연결은 실제로 불러 봐야 알 수 있으므로 마지막에 따로 확인합니다.
        if (!gemini.hasApiKey()) {
            checks.add(new Check("AI(제미나이) 연결", false,
                    "API 키가 없어요. 설정에서 키를 넣어 주세요."));
            render(checks);
            return;
        }

        final int aiIndex = checks.size();
        checks.add(new Check("AI(제미나이) 연결", true, "확인하는 중이에요…"));
        render(checks);

        gemini.testApiKey(new GeminiClient.Callback<String>() {
            @Override
            public void onSuccess(String result) {
                checks.set(aiIndex, new Check("AI(제미나이) 연결", true, "잘 연결됩니다."));
                render(checks);
            }

            @Override
            public void onError(String friendlyMessage) {
                checks.set(aiIndex, new Check("AI(제미나이) 연결", false, friendlyMessage));
                render(checks);
            }
        });
    }

    // ------------------------------------------------------------------ 검사 항목

    private Check checkUsbDevice() {
        boolean attached = UsbSerialService.hasUsbDevice(this);
        return new Check("기기 연결", attached,
                attached ? "기기가 꽂혀 있어요."
                        : "지금은 기기가 안 꽂혀 있어요. (아래 '기기 없이 눌러 보기'로 시험할 수 있어요.)");
    }

    private Check checkOverlayPermission() {
        boolean ok = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        return new Check("다른 앱 위에 표시", ok,
                ok ? "허락돼 있어요."
                        : "없으면 버튼을 눌러도 앱이 안 열려요. 설정에서 켜 주세요.");
    }

    private Check checkBatteryOptimization() {
        boolean ok = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            ok = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        }
        return new Check("배터리 절약 예외", ok,
                ok ? "설정돼 있어요." : "없으면 한참 뒤에 버튼이 안 먹을 수 있어요.");
    }

    private Check checkNotificationPermission() {
        boolean ok = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ok = ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.POST_NOTIFICATIONS)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return new Check("알림 권한", ok,
                ok ? "허락돼 있어요." : "없으면 상시 알림이 안 보여요. (동작에는 문제 없어요.)");
    }

    private Check checkInternet() {
        ConnectivityManager cm =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        boolean online = false;
        if (cm != null) {
            NetworkInfo info = cm.getActiveNetworkInfo();
            online = info != null && info.isConnected();
        }
        return new Check("인터넷 연결", online,
                online ? "연결돼 있어요." : "AI 기능을 쓰려면 인터넷이 필요해요.");
    }

    private Check checkSpeechRecognition() {
        boolean ok = SpeechRecognizer.isRecognitionAvailable(this);
        return new Check("말로 입력하기", ok,
                ok ? "쓸 수 있어요." : "이 휴대폰에서는 말로 입력할 수 없어요. 글자로 입력해 주세요.");
    }

    private Check checkTextToSpeech() {
        // 실제로 한 문장 읽어 보게 해서 귀로 확인하실 수 있게 합니다.
        SpeechManager.get(this).speak("소리 확인입니다. 이 말이 들리면 잘 되는 거예요.");
        return new Check("소리로 읽어 주기", true,
                "지금 한 문장 읽어 드렸어요. 안 들리면 휴대폰 소리를 키워 주세요.");
    }

    private List<Check> checkSlots() {
        List<Check> checks = new ArrayList<>();
        for (int slot = 1; slot <= Prefs.SLOT_COUNT; slot++) {
            String packageName = prefs.getSlotPackage(slot);
            String label = prefs.getSlotLabel(slot);
            String name = slot + "번 버튼";

            if (TextUtils.isEmpty(packageName)) {
                checks.add(new Check(name, false, "연결한 앱이 없어요."));
                continue;
            }
            boolean installed = appRepository.findByPackage(packageName) != null;
            checks.add(new Check(name, installed,
                    installed ? (TextUtils.isEmpty(label) ? packageName : label)
                            : (label + " 앱이 지워졌어요. 다시 정해 주세요.")));
        }
        return checks;
    }

    private Check checkShortcuts() {
        int count = 0;
        try {
            count = ShortcutManagerCompat.getDynamicShortcuts(this).size();
        } catch (Exception ignored) {
            // 바로가기를 못 읽는 기기도 있습니다.
        }
        boolean ok = count > 0;
        return new Check("삼성 루틴 바로가기", ok,
                ok ? count + "개가 등록돼 있어요." : "아직 없어요. '삼성 루틴 연결'에서 만들어 주세요.");
    }

    private Check checkOfflineKeywords() {
        // AI 없이도 앱을 찾을 수 있는지, 실제 사전을 한 번 돌려서 확인합니다.
        boolean ok = !KeywordMatcher.candidatesFor("길 찾고 싶어").isEmpty();
        return new Check("AI 없이 앱 찾기", ok,
                ok ? "낱말 " + KeywordMatcher.keywordCount() + "개로 찾을 수 있어요."
                        : "사전을 읽지 못했어요.");
    }

    // ------------------------------------------------------------------ 결과 그리기

    private void render(List<Check> checks) {
        resultContainer.removeAllViews();

        int failed = 0;
        for (Check check : checks) {
            if (!check.ok) {
                failed++;
            }
            resultContainer.addView(buildRow(check));
        }

        if (failed == 0) {
            summaryText.setText("✅ 모두 좋아요. (" + checks.size() + "가지 검사)");
            summaryText.setTextColor(ContextCompat.getColor(this, R.color.vk_success));
        } else {
            summaryText.setText("⚠️ " + failed + "가지를 손봐야 해요. (" + checks.size() + "가지 검사)");
            summaryText.setTextColor(ContextCompat.getColor(this, R.color.vk_danger));
        }
    }

    private View buildRow(Check check) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(check.ok ? R.drawable.bg_card : R.drawable.bg_status_bad);
        int pad = dp(14);
        row.setPadding(pad, pad, pad, pad);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        row.setLayoutParams(params);

        TextView title = new TextView(this);
        title.setText((check.ok ? "✅  " : "⚠️  ") + check.name);
        title.setTextSize(20f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(this,
                check.ok ? R.color.vk_success : R.color.vk_danger));
        row.addView(title);

        TextView detail = new TextView(this);
        detail.setText(check.detail);
        detail.setTextSize(17f);
        detail.setTextColor(ContextCompat.getColor(this, R.color.vk_text));
        row.addView(detail);

        return row;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    /** 다른 화면에서 테스트 화면을 열 때 씁니다. */
    public static void open(Context context) {
        context.startActivity(new Intent(context, DiagnosticsActivity.class));
    }
}
