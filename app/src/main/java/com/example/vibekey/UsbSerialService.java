package com.example.vibekey;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 버튼 기기(ESP32-S3 등)와 USB로 이어져 신호를 기다리는 백그라운드 서비스입니다.
 *
 * 기기가 보내는 신호
 *   "True", "Button1", "Button2", "Button3" → 해당 버튼에 연결된 앱 실행
 *   "AI", "Long1", "ButtonLong1"            → AI 도우미 화면 열기 (말로 물어보기)
 *
 * 버튼이 눌리면 앱 실행뿐 아니라 삼성 루틴에도 신호를 보내 줍니다.
 */
public class UsbSerialService extends Service implements SerialInputOutputManager.Listener {

    private static final String TAG = "USB_SERIAL_SERVICE";
    private static final String ACTION_USB_PERMISSION = "com.example.vibekey.USB_PERMISSION";
    private static final String CHANNEL_ID = "UsbSerialMonitorChannel";
    private static final int NOTIFICATION_ID = 999;

    /** 같은 버튼을 실수로 여러 번 눌러도 한 번만 실행되게 합니다. (손 떨림 대비) */
    private static final long DEBOUNCE_DELAY_MS = 3000;

    private UsbManager usbManager;
    private UsbSerialPort usbSerialPort;
    private SerialInputOutputManager usbIoManager;

    private final StringBuilder incomingBuffer = new StringBuilder();

    /** 버튼별로 마지막 실행 시각을 따로 기록합니다. */
    private final long[] lastLaunchTime = new long[Prefs.SLOT_COUNT + 1];
    private long lastAiLaunchTime = 0;

    public static final String ACTION_STATUS_CHANGED = "com.example.vibekey.STATUS_CHANGED";
    public static final String EXTRA_IS_CONNECTED = "is_connected";
    public static final String EXTRA_DESCRIPTION = "description";

    /**
     * 기기 없이도 동작을 확인할 수 있도록, 테스트 화면에서 가짜 신호를 넣을 때 쓰는 명령입니다.
     * (실제 시리얼로 들어온 것과 완전히 같은 경로로 처리됩니다.)
     */
    public static final String ACTION_SIMULATE_SIGNAL = "com.example.vibekey.SIMULATE_SIGNAL";
    public static final String EXTRA_SIGNAL = "signal";

    private boolean isConnected = false;
    private boolean isForegroundStarted = false;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            connectToUsbDevice(device);
                        }
                    } else {
                        broadcastStatus(false, "기기 사용을 허락해 주세요.");
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                findAndConnectUsbDevice();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                disconnectUsbDevice();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }

        // 안드로이드 14부터는 USB 기기가 실제로 꽂혀 있을 때만
        // connectedDevice 형식의 포그라운드 서비스를 켤 수 있습니다.
        // 기기가 없을 때 무조건 켜려고 하면 SecurityException 으로 앱이 튕깁니다.
        if (hasUsbDevice(this)) {
            startForegroundNotification();
        }
        findAndConnectUsbDevice();
    }

    /**
     * 이 서비스를 안전하게 시작합니다.
     * 기기가 꽂혀 있을 때만 포그라운드로 올리고, 아닐 때는 보통 서비스로 시작합니다.
     */
    public static void start(Context context) {
        Intent intent = new Intent(context, UsbSerialService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasUsbDevice(context)) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            // 백그라운드에서 보통 서비스를 시작할 수 없는 경우가 있습니다.
            // 이때는 기기를 꽂는 순간(USB_DEVICE_ATTACHED)에 다시 시작되므로 그냥 넘어갑니다.
            Log.w(TAG, "Could not start service now: " + e.getMessage());
        }
    }

    /**
     * 기기 없이 동작을 시험해 보기 위해 가짜 신호를 넣습니다. (테스트 화면 전용)
     * 실제 시리얼로 들어온 것과 완전히 같은 경로로 처리됩니다.
     */
    public static void simulate(Context context, String signal) {
        Intent intent = new Intent(context, UsbSerialService.class)
                .setAction(ACTION_SIMULATE_SIGNAL)
                .putExtra(EXTRA_SIGNAL, signal);
        try {
            context.startService(intent);
        } catch (Exception e) {
            Log.w(TAG, "Could not deliver test signal: " + e.getMessage());
        }
    }

    /** USB 기기가 하나라도 꽂혀 있는지 확인합니다. */
    public static boolean hasUsbDevice(Context context) {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        return manager != null && !manager.getDeviceList().isEmpty();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_SIMULATE_SIGNAL.equals(intent.getAction())) {
            String signal = intent.getStringExtra(EXTRA_SIGNAL);
            if (signal != null) {
                // 디바운스 때문에 시험이 막히지 않도록, 테스트 신호는 대기 시간을 지웁니다.
                resetDebounce();
                handleSerialLine(signal);
            }
        }
        // 서비스가 강제 종료되더라도 시스템이 자동으로 다시 켜 줍니다.
        return START_STICKY;
    }

    /** 테스트 화면에서 연달아 눌러도 매번 실행되도록 디바운스 기록을 지웁니다. */
    private void resetDebounce() {
        for (int i = 0; i < lastLaunchTime.length; i++) {
            lastLaunchTime[i] = 0;
        }
        lastAiLaunchTime = 0;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        disconnectUsbDevice();
        try {
            unregisterReceiver(usbReceiver);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Receiver not registered", e);
        }
    }

    // ------------------------------------------------------------------ 알림

    /**
     * 상시 알림을 띄우고 시스템이 서비스를 끄지 못하게 격상시킵니다.
     * USB 기기가 없을 때 호출하면 안드로이드 14 이상에서 예외가 나므로,
     * 실패해도 앱이 죽지 않도록 감싸 두었습니다.
     */
    private void startForegroundNotification() {
        if (isForegroundStarted) {
            return;
        }
        createNotificationChannel();
        Notification notification = buildNotification("버튼을 기다리고 있어요",
                "기기 버튼을 누르면 정해 둔 앱이 열립니다.");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            isForegroundStarted = true;
        } catch (Exception e) {
            // 기기가 빠진 상태 등에서는 격상이 거부될 수 있습니다. 서비스는 그대로 살려 둡니다.
            Log.w(TAG, "Foreground service not allowed now: " + e.getMessage());
        }
    }

    /** 기기가 빠지면 상시 알림을 내려 다음 격상이 다시 허용되게 합니다. */
    private void stopForegroundNotification() {
        if (!isForegroundStarted) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
            } else {
                //noinspection deprecation
                stopForeground(true);
            }
        } catch (Exception e) {
            Log.w(TAG, "stopForeground failed", e);
        }
        isForegroundStarted = false;
    }

    private Notification buildNotification(String title, String text) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        int pendingFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pendingIntent =
                PendingIntent.getActivity(this, 0, notificationIntent, pendingFlags);

        // 알림에서 바로 AI 도우미를 열 수 있게 해 둡니다.
        Intent aiIntent = new Intent(this, AiAssistantActivity.class);
        aiIntent.putExtra(AiAssistantActivity.EXTRA_AUTO_LISTEN, true);
        aiIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent aiPending = PendingIntent.getActivity(this, 1, aiIntent, pendingFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_usb)
                .setContentIntent(pendingIntent)
                .addAction(R.drawable.ic_sparkle, "AI 도우미", aiPending)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String title, String text) {
        if (!isForegroundStarted) {
            return; // 상시 알림이 없을 때는 새로 만들지 않습니다.
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(title, text));
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "바이브키 버튼 감지",
                    NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setDescription("기기 버튼을 기다리는 동안 계속 켜져 있는 알림입니다.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    // ------------------------------------------------------------------ USB 연결

    private void findAndConnectUsbDevice() {
        List<UsbSerialDriver> availableDrivers =
                UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (availableDrivers.isEmpty()) {
            broadcastStatus(false, "기기를 휴대폰에 꽂아 주세요.");
            return;
        }

        UsbSerialDriver driver = availableDrivers.get(0);
        UsbDevice device = driver.getDevice();

        if (usbManager.hasPermission(device)) {
            connectToUsbDevice(device);
        } else {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent permissionIntent = PendingIntent.getBroadcast(
                    this, 0, new Intent(ACTION_USB_PERMISSION), flags);
            usbManager.requestPermission(device, permissionIntent);
        }
    }

    private void connectToUsbDevice(UsbDevice device) {
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (driver == null) {
            broadcastStatus(false, "이 기기는 알아보지 못했어요.");
            return;
        }

        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            broadcastStatus(false, "기기를 여는 데 실패했어요. 케이블을 다시 꽂아 주세요.");
            return;
        }

        usbSerialPort = driver.getPorts().get(0);
        try {
            usbSerialPort.open(connection);
            usbSerialPort.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

            try {
                usbSerialPort.setDTR(true);
                usbSerialPort.setRTS(true);
            } catch (UnsupportedOperationException e) {
                Log.w(TAG, "DTR/RTS not supported.");
            }

            usbIoManager = new SerialInputOutputManager(usbSerialPort, this);
            usbIoManager.start();

            // 기기가 실제로 붙었으니 이제 상시 알림으로 격상해도 됩니다.
            startForegroundNotification();
            broadcastStatus(true, "이제 버튼을 누르시면 앱이 열려요.");

        } catch (IOException e) {
            broadcastStatus(false, "연결에 실패했어요. 케이블을 다시 꽂아 주세요.");
            disconnectUsbDevice();
        }
    }

    private void disconnectUsbDevice() {
        if (usbIoManager != null) {
            usbIoManager.setListener(null);
            usbIoManager.stop();
            usbIoManager = null;
        }
        if (usbSerialPort != null) {
            try {
                usbSerialPort.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing port", e);
            }
            usbSerialPort = null;
        }
        broadcastStatus(false, "기기를 휴대폰에 꽂아 주세요.");
        // 기기가 빠진 뒤에도 상시 알림을 붙들고 있으면 다음 격상이 거부됩니다.
        stopForegroundNotification();
    }

    /**
     * 지금 상태를 화면(MainActivity)과 바깥(삼성 루틴)에 함께 알립니다.
     * 연결 상태가 실제로 바뀌었을 때만 소리로 알려 드려, 잔소리처럼 들리지 않게 합니다.
     */
    private void broadcastStatus(boolean connected, String description) {
        boolean changed = this.isConnected != connected;
        this.isConnected = connected;

        Intent intent = new Intent(ACTION_STATUS_CHANGED);
        intent.putExtra(EXTRA_IS_CONNECTED, connected);
        intent.putExtra(EXTRA_DESCRIPTION, description);
        sendBroadcast(intent);

        updateNotification(connected ? "버튼을 기다리고 있어요" : "기기가 빠졌어요", description);

        if (changed) {
            RoutineBridge.notifyDeviceState(this, connected);
            SpeechManager.get(this).speakIfEnabled(this,
                    connected ? "기기가 연결되었어요." : "기기가 빠졌어요.");
        }
    }

    // ------------------------------------------------------------------ 신호 해석

    @Override
    public void onNewData(byte[] data) {
        String incomingText = new String(data, StandardCharsets.UTF_8);
        synchronized (incomingBuffer) {
            incomingBuffer.append(incomingText);
            int newlineIndex;
            while ((newlineIndex = incomingBuffer.indexOf("\n")) != -1) {
                String line = incomingBuffer.substring(0, newlineIndex).trim();
                incomingBuffer.delete(0, newlineIndex + 1);
                handleSerialLine(line);
            }
        }
    }

    private void handleSerialLine(String line) {
        if (line == null || line.trim().isEmpty()) {
            return;
        }
        Log.d(TAG, "Received: " + line);

        SignalParser.Result result = SignalParser.parse(line);
        switch (result.action) {
            case OPEN_AI:
                SerialLog.add(this, line, "AI 도우미 열기");
                openAiAssistant();
                break;
            case RUN_SLOT:
                SerialLog.add(this, line, result.slot + "번 버튼 실행");
                handleButtonSignalReceived(result.slot);
                break;
            default:
                SerialLog.add(this, line, "알 수 없는 신호 (무시함)");
                break;
        }
    }

    @Override
    public void onRunError(Exception e) {
        disconnectUsbDevice();
    }

    private void handleButtonSignalReceived(final int slot) {
        long now = System.currentTimeMillis();
        if (now - lastLaunchTime[slot] < DEBOUNCE_DELAY_MS) {
            return; // 너무 빨리 다시 눌린 경우는 무시합니다.
        }
        lastLaunchTime[slot] = now;

        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Prefs prefs = Prefs.with(UsbSerialService.this);
                if (!prefs.hasSlot(slot)) {
                    String message = slot + "번 버튼에 넣을 앱을 먼저 정해 주세요.";
                    Toast.makeText(UsbSerialService.this, message, Toast.LENGTH_LONG).show();
                    SpeechManager.get(UsbSerialService.this)
                            .speakIfEnabled(UsbSerialService.this, message);
                    return;
                }
                AppLauncher.runSlot(UsbSerialService.this, slot, "hardware");
            }
        });
    }

    private void openAiAssistant() {
        long now = System.currentTimeMillis();
        if (now - lastAiLaunchTime < DEBOUNCE_DELAY_MS) {
            return;
        }
        lastAiLaunchTime = now;

        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Intent intent = new Intent(UsbSerialService.this, AiAssistantActivity.class);
                intent.putExtra(AiAssistantActivity.EXTRA_AUTO_LISTEN, true);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
            }
        });
    }
}
