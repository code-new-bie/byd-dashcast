package com.byd.dashcast.phase1;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.byd.dashcast.phase1.ncm.LogHub;
import com.byd.dashcast.phase1.ncm.Usb;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Phase one diagnostic activity for the BYD Android side.
 *
 * @author Codex
 * @date 2026/10/09
 */
public final class MainActivity extends Activity {

    private static final String ADB_HOST = "127.0.0.1";
    private static final int ADB_PORT = 5555;
    private static final int ADB_TIMEOUT_MILLIS = 20000;
    private static final int MAX_TEXT_PROBE_BYTES = 96 * 1024;
    private static final int MAX_LOGGED_PROBE_LINES = 40;
    private static final String SCREENRECORD_COMMAND =
            "screenrecord --output-format=h264 --size 1920x720 "
                    + "--bit-rate 6000000 --time-limit 5 -";
    private static final String CAR_PROTOCOL_COMMAND = "getprop ro.car.protocol";
    private static final String SDK_VERSION_COMMAND = "getprop ro.build.version.sdk";
    private static final String RELEASE_VERSION_COMMAND =
            "getprop ro.build.version.release";
    private static final String SERVICE_LIST_COMMAND = "service list";
    private static final String MEDIA_CODEC_COMMAND = "dumpsys media.codec";
    private static final String NETWORK_LINK_COMMAND = "ip link show";
    private static final int EXPORT_REQUEST = 10;
    private static final int MAX_REPORT_CHARS = 512 * 1024;
    private static final int MAX_VIEW_CHARS = 64 * 1024;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final StringBuffer reportLog = new StringBuffer();
    private final java.util.ArrayList<Button> taskButtons = new java.util.ArrayList<>(12);
    private volatile File lastTsFile;
    private CheckBox receiverInstalled;
    private EditText targetField;
    private EditText portField;
    private EditText interfaceField;
    private EditText secondsField;
    private EditText continuousSecondsField;
    private TextView continuousStatusView;
    private CaptureSettingsView captureSettingsView;
    private volatile boolean taskRunning;
    private volatile boolean adbReady;
    private volatile boolean adbAuthorizationOnly;
    private volatile boolean awaitingAdbTrust;
    private volatile InstrumentUsbCommand lastUsbCommand;
    private volatile long lastUsbRequestMillis;
    private volatile String lastRelayEvidence = "No USB request observed in this APK session.\n";
    private volatile String lastAndroidUsbEvidence = "Read-only Android USB snapshot not collected yet.\n";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor probeExecutor = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(2),
            new ProbeThreadFactory(),
            new ThreadPoolExecutor.AbortPolicy());

    private Button captureButton;
    private Button usbButton;
    private Button capabilityButton;
    private Button allButton;
    private TextView logView;
    private TextView exportStatusView;
    private TextView controlStatusView;
    private final Runnable projectionMonitor = new Runnable() {
        @Override
        public void run() {
            if (continuousStatusView != null) {
                continuousStatusView.setText(getString(R.string.continuous_status, ProjectionService.status()));
            }
            setBusy(taskRunning || ProjectionService.blocksOperations());
            mainHandler.postDelayed(this, 1000);
        }
    };

    /**
     * Creates the diagnostic UI.
     *
     * @param savedInstanceState activity state
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContentView());
        appendLog("APK " + BuildConfig.VERSION_NAME
                + "; NCM capability-driven initialization / alternate-0 cleanup; DCV1/1 short + DCV1/2 continuous, "
                + NoAuthVideoProtocol.WIDTH + "x" + NoAuthVideoProtocol.HEIGHT + "/"
                + NoAuthVideoProtocol.FPS + "fps."
                + " Instrument installed version is not remotely verified; runtime not yet certified.");
        appendLog("Legacy app BYDAuto grants: " + InstrumentUsbController.permissionSummary(this)
                + "; not required by the original navigation relay; direct setBuffer removed.");
        appendLog("Control: authorized local ADB -> package-scoped OEM navigation broadcast -> original instrument text");
    }

    /**
     * Stops the bounded probe executor.
     */
    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(projectionMonitor);
        cancelled.set(true);
        probeExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        mainHandler.removeCallbacks(projectionMonitor);
        if (taskRunning && !(adbAuthorizationOnly && awaitingAdbTrust)) {
            cancelled.set(true);
            appendLog("Activity hidden: cancel current probe. USB role is NOT automatically restored.");
        }
        super.onStop();
    }

    @Override
    protected void onStart() {
        super.onStart();
        mainHandler.post(projectionMonitor);
    }

    private View createContentView() {
        int padding = dp(12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);

        TextView titleView = new TextView(this);
        titleView.setText(R.string.screen_title);
        titleView.setTextSize(20);
        root.addView(titleView, new LinearLayout.LayoutParams(-1, -2));

        TextView scopeView = new TextView(this);
        scopeView.setText(R.string.screen_scope);
        scopeView.setPadding(0, dp(8), 0, dp(8));
        root.addView(scopeView, new LinearLayout.LayoutParams(-1, -2));

        addControlSection(root);
        captureSettingsView = new CaptureSettingsView(this, this::appendLog);
        root.addView(captureSettingsView.view());
        continuousSecondsField = createField(root, "持续投屏时长 1..1800 秒（先600秒，再1800秒）", "600");
        root.addView(createButton("开始持续投屏（可切到导航，最多30分钟）", view -> startContinuousProjection()));
        continuousStatusView = new TextView(this);
        continuousStatusView.setText(getString(R.string.continuous_status, ProjectionService.status()));
        root.addView(continuousStatusView);
        secondsField = createField(root, "动态视频时长 1..60 秒", "10");
        addVideoButtons(root);
        addLegacyAdvancedSection(root);

        captureButton = createButton("1. ADB / H.264 采集探针", view -> runCaptureProbe());
        usbButton = createButton("2. 检查 USB 枚举 / 网络接口", view -> runUsbProbe());
        capabilityButton = createButton("3. 车机 / CAN 能力证据", view -> runCapabilityProbe());
        allButton = createButton("4. 运行全部检查", view -> runAllProbes());
        Button clearButton = createButton("清空日志", view -> logView.setText(""));
        root.addView(captureButton);
        root.addView(usbButton);
        root.addView(capabilityButton);
        root.addView(allButton);
        Button downloadButton = new Button(this);
        downloadButton.setText(R.string.export_session_logs);
        downloadButton.setOnClickListener(view -> exportLogsToDownloads());
        root.addView(downloadButton);
        root.addView(clearButton);

        exportStatusView = new TextView(this);
        exportStatusView.setText("日志导出后，到文件管理器的“下载”查找 dashcast-logs 文件。");
        exportStatusView.setTextSize(14);
        root.addView(exportStatusView, new LinearLayout.LayoutParams(-1, -2));
        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setTextIsSelectable(true);
        root.addView(logView, new LinearLayout.LayoutParams(-1, -2));
        ScrollView page = new ScrollView(this);
        page.addView(root);
        return page;
    }

    private void addControlSection(LinearLayout root) {
        root.addView(createButton("① 连接 / 授权 ADB（首次弹窗点允许）",
                view -> executeProbe("ADB authorization", this::performAdbAuthorization)));
        controlStatusView = new TextView(this);
        controlStatusView.setText(R.string.control_initial_status);
        root.addView(controlStatusView);
        receiverInstalled = new CheckBox(this);
        receiverInstalled.setText(R.string.matching_firmware);
        root.addView(receiverInstalled);
        root.addView(createButton("仪表切 NCM / Device", view -> confirmUsbCommand(InstrumentUsbCommand.NCM)));
        root.addView(createButton("仪表恢复 Host（先拔 USB 线）", view -> confirmUsbCommand(InstrumentUsbCommand.HOST)));
        root.addView(createButton("只读诊断原厂中继（不会重发切换命令）", view ->
                executeProbe("read-only relay diagnosis", () -> {
                    if (!adbReady) {
                        throw new IOException("请先连接 / 授权 ADB；没有发送 USB 命令。");
                    }
                    collectRelayEvidence();
                    performUsbProbe();
                })));
    }

    private void addVideoButtons(LinearLayout root) {
        root.addView(createButton("阶段一：允许访问仪表 USB（系统权限）", view -> requestInstrumentUsbPermission()));
        root.addView(createButton("阶段一：USB 车机画面验证（ADB 录屏，最长 10 秒）", view -> startReferenceVideo(true)));
        root.addView(createButton("可选：USB 动态测试图验证（Surface/EGL）", view -> startReferenceVideo(false)));
        root.addView(createButton("本地硬编动态画面 → TS（不联网）", view -> startVideoProbe(false)));
        Button cancelButton = new Button(this);
        cancelButton.setText(R.string.stop_projection);
        cancelButton.setOnClickListener(view -> {
            cancelled.set(true);
            ProjectionService.requestStop(this);
        });
        root.addView(cancelButton);
        root.addView(createButton("记录仪表画面观察结果", view -> recordDisplayObservation()));
        root.addView(createButton("另存日志 + 最近 TS 样本 ZIP（选择位置）", view -> exportReport()));

    }

    private void addLegacyAdvancedSection(LinearLayout root) {
        LinearLayout advanced = new LinearLayout(this);
        advanced.setOrientation(LinearLayout.VERTICAL);
        advanced.setVisibility(View.GONE);
        root.addView(createButton("展开旧 RTP/TS 高级检查（不用于本次阶段一投屏）", view ->
                advanced.setVisibility(advanced.getVisibility() == View.GONE ? View.VISIBLE : View.GONE)));
        interfaceField = createField(advanced, "旧 RTP/TS：Android USB 网卡名称", "usb0");
        targetField = createField(advanced, "旧 RTP/TS：仪表 IPv4", "192.168.77.1");
        portField = createField(advanced, "旧 RTP/TS：另外配置的接收端口，本包未配置", "5004");
        advanced.addView(createButton("旧 RTP/TS 发送（不是 DCV1 验证）", view -> startVideoProbe(true)));
        root.addView(advanced);
    }

    private EditText createField(LinearLayout root, String label, String value) {
        TextView text = new TextView(this);
        text.setText(label);
        root.addView(text);
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setText(value);
        root.addView(field);
        return field;
    }

    private Button createButton(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(listener);
        taskButtons.add(button);
        return button;
    }

    private void confirmUsbCommand(InstrumentUsbCommand command) {
        if (!receiverInstalled.isChecked()) {
            appendLog("BLOCKED: original firmware has no SCMD USB receiver. No command sent.");
            return;
        }
        if (!adbReady) {
            appendLog("BLOCKED: 请先点“连接 / 授权 ADB”，在车机弹窗允许本 APK 的调试密钥。");
            return;
        }
        String message = "仅在车辆停稳的台架测试；已准备独立恢复入口。"
                + "请先退出正在进行的导航。将通过原厂导航文字中继发送，可能改变仪表导航页面。"
                + "广播提交不等于仪表已处理或已完成 USB 切换；只发送一次，不自动重发。";
        message += command == InstrumentUsbCommand.HOST
                ? "恢复 Host 前必须拔掉仪表与车机的 USB 线，避免两端同时 Host/供电。"
                : "切入 NCM 前移除仪表 U 盘；确认 NCM 切换后再接车机 USB。";
        new AlertDialog.Builder(this).setTitle(command.commandText()).setMessage(message)
                .setNegativeButton("取消", null).setPositiveButton("条件已满足，发送一次", (dialog, which) ->
                        executeProbe("USB " + command, () -> performUsbCommand(command))).show();
    }

    private void performUsbCommand(InstrumentUsbCommand command) throws IOException {
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (manager == null) {
            throw new IOException("UsbManager unavailable");
        }
        int before = countNcmDevices(manager);
        int targets = 0;
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (device.getVendorId() == 0x1234 && device.getProductId() == 0xfff1
                    && UsbNcmSession.isNcmDevice(device)) {
                targets++;
            }
        }
        if (UsbRoleRequestPolicy.keepExistingNcm(command, before, targets)) {
            appendLog("NCM_ALREADY_ENUMERATED_NO_COMMAND: preserving existing target; TCP/video not yet certified");
            showControlStatus("目标 NCM 已存在，不重复切换、不要求拔线。可授权 USB 并验证视频；枚举不代表视频连通。");
            return;
        }
        appendLog("Before request NCM devices=" + before);
        showControlStatus("正在核对原厂中继及 ADB 身份，尚未提交 USB 切换请求。");
        InstrumentUsbController controller = new InstrumentUsbController(getApplicationContext(),
                authorizedAdbClient(), cancelled, this::appendLog);
        lastUsbCommand = command;
        lastUsbRequestMillis = System.currentTimeMillis();
        lastRelayEvidence = "request=" + command.commandText() + "; processing evidence not yet collected.\n";
        try {
            controller.request(command);
        } catch (IOException error) {
            showControlStatus("本次请求失败 / 完成未确认；请导出日志，不要连续重发。");
            collectRelayEvidence();
            throw error;
        }
        showControlStatus("导航广播已提交，USB 模式尚未确认。先观察仪表提示，确认切换后接线；拔线时无 NCM 属正常现象。");
        // Collect immediately as well as after enumeration; don't wait 15 seconds for OEM logs to be evicted.
        collectRelayEvidence();
        for (int second = 0; second < 15 && !cancelled.get(); second++) {
            LockSupport.parkNanos(1_000_000_000L);
            if (Thread.currentThread().isInterrupted()) {
                cancelled.set(true);
            }
            int current = countNcmDevices(manager);
            if (current != before || second == 14) {
                appendLog("After request t=" + (second + 1) + "s NCM devices=" + current);
            }
            if (command == InstrumentUsbCommand.NCM && current > 0) {
                appendLog(before > 0 ? "PREEXISTING_NCM: no new switch evidence."
                        : "NCM_ENUMERATED: verify identity against the target instrument.");
                appendLog("IP, QNX actual role and video must be verified separately.");
                break;
            }
        }
        showUsbObservation(command, before, countNcmDevices(manager));
        collectRelayEvidence();
        performUsbProbe();
    }

    private void showUsbObservation(InstrumentUsbCommand command, int before, int after) {
        UsbSwitchEvidence.State state = UsbSwitchEvidence.classify(command, before, after, cancelled.get());
        appendLog("USB_OBSERVATION " + state + "; no CAN/QNX role acknowledgement");
        switch (state) {
            case CANCELLED_UNCONFIRMED:
                showControlStatus("检查已取消，已提交的请求不能撤销；当前 USB 模式未确认，不会自动重发或恢复 Host。");
                break;
            case HOST_UNCONFIRMED:
                showControlStatus("Host 请求已提交，尚未确认；在仪表侧用普通无升级文件 U 盘检查。");
                break;
            case PEER_NOT_OBSERVED:
                appendLog("PEER_NOT_OBSERVED: unplugged USB is expected; cannot infer switch failure from zero devices.");
                showControlStatus("未观察到 NCM；若数据线仍拔着这是预期结果。先看仪表提示，接线后点 USB 检查，不必重发。");
                break;
            case PREEXISTING_NCM:
                showControlStatus("NCM 设备原已存在，不能证明本次切换；请核对目标仪表并导出日志。");
                break;
            case NEW_NCM_OBSERVED:
                showControlStatus("发现新增 NCM 设备；确认是目标仪表后授权 USB，再验证视频，尚不代表实显成功。");
                break;
            default:
                throw new IllegalStateException("Unknown USB observation state");
        }
    }

    private void collectRelayEvidence() {
        if (!adbReady || cancelled.get() || lastUsbCommand == null) {
            return;
        }
        NavigationRelayEvidence evidence = new NavigationRelayEvidence(lastUsbCommand, lastUsbRequestMillis);
        try {
            authorizedAdbClient().executeShell(AdbCommandOutput.wrap(NavigationRelayEvidence.LOG_COMMAND), evidence);
            // Do not erase an earlier useful request snapshot after log-ring eviction.
            if (evidence.hasProcessingEvidence() || (!lastRelayEvidence.contains("OEM_NEXT_ROAD")
                    && !lastRelayEvidence.contains("OEM_PERMISSION_ERROR_IN_WINDOW"))) {
                lastRelayEvidence = evidence.snapshot();
            }
            appendLog(evidence.summary());
        } catch (IOException | SecurityException error) {
            if (!lastRelayEvidence.contains("OEM_NEXT_ROAD")
                    && !lastRelayEvidence.contains("OEM_PERMISSION_ERROR_IN_WINDOW")) {
                lastRelayEvidence = "request=" + lastUsbCommand.commandText()
                        + "\nRead-only OEM log collection failed: " + error.getClass().getSimpleName()
                        + "\nOEM processing UNKNOWN; no switch command resent.\n";
            }
            appendLog("NAV_EVIDENCE_UNAVAILABLE " + error.getClass().getSimpleName() + "; do not resend automatically");
        }
    }

    private void collectAndroidUsbEvidence() {
        if (!adbReady || cancelled.get()) {
            return;
        }
        StringBuilder snapshot = new StringBuilder(512);
        try {
            InstrumentUsbController controller = new InstrumentUsbController(getApplicationContext(),
                    authorizedAdbClient(), cancelled, this::appendLog);
            String user = controller.executeChecked("am get-current-user").trim();
            if (user.matches("[0-9]{1,6}")) {
                snapshot.append("current_android_user=").append(user).append('\n');
                if (!"0".equals(user)) {
                    snapshot.append("USER_PROFILE_MISMATCH: analyzed OEM service UID=1000 belongs to user 0.\n");
                }
            } else {
                snapshot.append("current_android_user=UNKNOWN\n");
            }
            UsbStateEvidence flags = new UsbStateEvidence();
            authorizedAdbClient().executeShell(AdbCommandOutput.wrap(UsbStateEvidence.COMMAND), flags);
            snapshot.append(flags.snapshot());
        } catch (IOException | SecurityException error) {
            snapshot.append("Read-only Android state collection failed: ")
                    .append(error.getClass().getSimpleName()).append("; remaining state UNKNOWN.\n");
            appendLog("ANDROID_STATE_EVIDENCE_UNAVAILABLE " + error.getClass().getSimpleName());
        }
        collectKernelUsbEvidence(snapshot);
        lastAndroidUsbEvidence = "snapshot_epoch_ms=" + System.currentTimeMillis() + "\n" + snapshot;
    }

    private void collectKernelUsbEvidence(StringBuilder snapshot) {
        if (cancelled.get()) {
            snapshot.append("USB_ENUM_LAYER=KERNEL_ENUMERATION_UNKNOWN; collection cancelled\n");
            return;
        }
        try {
            KernelUsbEvidence evidence = new KernelUsbEvidence();
            authorizedAdbClient().executeShell(AdbCommandOutput.wrap(KernelUsbEvidence.COMMAND), evidence);
            UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
            if (manager == null) {
                throw new IOException("UsbManager unavailable");
            }
            int devices = manager.getDeviceList().size();
            int ncmDevices = countNcmDevices(manager);
            snapshot.append(evidence.snapshot(devices, ncmDevices));
            appendLog(evidence.summary(devices, ncmDevices));
            if (devices == 0) {
                appendLog("USB_ENUMERATION_GATE: no framework USB device, so permission/TCP/video have not started."
                        + " Kernel evidence separates absent enumeration from framework filtering; it does not certify QNX role.");
            }
        } catch (IOException | SecurityException error) {
            snapshot.append("USB_ENUM_LAYER=KERNEL_ENUMERATION_UNKNOWN; read-only collection unavailable: ")
                    .append(error.getClass().getSimpleName()).append('\n');
            appendLog("KERNEL_USB_EVIDENCE_UNAVAILABLE " + error.getClass().getSimpleName());
        }
    }

    private int countNcmDevices(UsbManager manager) {
        int count = 0;
        for (UsbDevice device : manager.getDeviceList().values()) {
            for (int index = 0; index < device.getInterfaceCount(); index++) {
                UsbInterface item = device.getInterface(index);
                if (item.getInterfaceClass() == 2 && item.getInterfaceSubclass() == 13) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    private void requestInstrumentUsbPermission() {
        if (!receiverInstalled.isChecked()) {
            appendLog("BLOCKED: first deploy the matching phase-one instrument package");
            return;
        }
        try {
            UsbDevice device = UsbNcmSession.findDevice(this);
            appendLog(String.format(Locale.US, "Selected USB %04x:%04x %s permission=%s",
                    device.getVendorId(), device.getProductId(), device.getDeviceName(),
                    Usb.hasPermission(this, device)));
            if (!Usb.hasPermission(this, device)) {
                Usb.requestPermission(this, device);
                appendLog("Allow the system USB prompt, then press the phase-one video button.");
            }
        } catch (IOException | SecurityException error) {
            appendLog("USB permission unavailable: " + error.getMessage());
            appendLog("没有枚举到 USB 设备时，系统授权无法解决。重新接线后点“检查 USB 枚举”，不要连续发送切换请求。");
        }
    }

    private void startReferenceVideo(boolean screenCapture) {
        if (!receiverInstalled.isChecked()) {
            appendLog("BLOCKED: matching USB + video instrument package required");
            return;
        }
        final int seconds;
        try {
            seconds = Integer.parseInt(secondsField.getText().toString().trim());
            if (seconds < 1 || seconds > 60) {
                throw new IllegalArgumentException("Duration must be 1..60 seconds");
            }
            if (screenCapture) {
                    AdbScreenVideoSource.smallWindowCommand(seconds);
                if (!adbReady) {
                    throw new IOException("请先连接 / 授权本 APK 的 ADB");
                }
            }
            UsbDevice device = UsbNcmSession.findDevice(this);
            if (!Usb.hasPermission(this, device)) {
                appendLog("First press USB authorization and allow the system prompt.");
                return;
            }
        } catch (IOException | IllegalArgumentException | SecurityException error) {
            appendLog("Video blocked: " + error.getMessage());
            return;
        }
        new AlertDialog.Builder(this).setTitle("阶段一 USB 视频验证")
                .setMessage("确认仅连接目标仪表、已停稳且有独立恢复手段。"
                        + "测试暂时接管该 NCM 设备，可能让它的系统网卡消失；不修改车机 IP/路由。"
                        + "本包使用仪表 1920×720 视频窗口，车机硬件编码为 1920×720/30FPS；告警隔离尚未完成，请观察并随时停止。"
                        + "视频无需激活授权；系统 USB 访问许可和 ADB 授权仍按正常流程允许。"
                        + (screenCapture ? "发送当前车机画面，可能含个人信息；录像样本仅保存在应用缓存，"
                                + "不会随一键日志导出，只有手动另存样本才导出。"
                                : "发送动态测试条纹，不是导航或整屏采集。")
                        + "无需填写上面的 RTP 网卡/端口。")
                .setNegativeButton("取消", null)
                .setPositiveButton("已确认，验证", (dialog, which) ->
                        executeProbe("reference USB video", () -> performReferenceVideo(seconds, screenCapture))).show();
    }

    private void startContinuousProjection() {
        if (taskRunning || ProjectionService.blocksOperations()) {
            appendLog("持续投屏未启动：已有任务或清理未确认，请先停止并查看状态。");
            return;
        }
        if (!receiverInstalled.isChecked() || !adbReady) {
            appendLog("持续投屏未启动：确认已装0.7.7 HD/DCV1/2仪表App、车辆停稳，并先授权本APK的ADB。");
            return;
        }
        if (!captureSettingsView.benchAcknowledged()) {
            appendLog("持续投屏未启动：请确认仅停稳/台架验证，中央区域含动态告警，不能用于行驶。");
            return;
        }
        final int seconds;
        final CaptureOptions options;
        try {
            options = captureSettingsView.snapshot();
            seconds = Integer.parseInt(continuousSecondsField.getText().toString().trim());
            ProjectionLimits.requireSeconds(seconds);
            UsbDevice device = UsbNcmSession.findDevice(this);
            if (!Usb.hasPermission(this, device)) {
                appendLog("持续投屏未启动：请先允许访问仪表USB。");
                return;
            }
        } catch (IOException | IllegalArgumentException | SecurityException error) {
            appendLog("持续投屏未启动：" + error.getMessage());
            return;
        }
        new AlertDialog.Builder(this).setTitle("持续小窗投屏：停稳验证")
                .setMessage("采集模式：" + options + "。需要配套0.7.7 HD/DCV1/2仪表App。"
                        + "视频与仪表窗口为1920×720，Surface无新画面时按33,333微秒重复节奏，车机端再按编码PTS限流并重定时到30FPS；实际编码输出尺寸不匹配时会立即失败，并记录编码PTS FPS/墙钟 FPS；固件告警隔离未完成，禁止行驶使用。"
                        + "先600秒再1800秒；切到导航不会停止，可回应用或通知栏点停止。"
                        + "不保存视频文件，不自动切Host，不自动重连。普通ADB/USB授权仍保留。"
                        + "若窗口未退出或清理不确认，停止测试并正常重启，不重复发切换命令。")
                .setNegativeButton("取消", null)
                .setPositiveButton("已停稳，开始", (dialog, which) -> {
                    try {
                        captureSettingsView.remember(options);
                        ProjectionService.start(this, seconds, options);
                        appendLog("CONTINUOUS_START_REQUEST seconds=" + seconds + " capture=" + options
                                + "; user_ack=stationary_only; Service owns the frozen session configuration");
                        setBusy(true);
                    } catch (RuntimeException error) {
                        appendLog("持续服务启动未确认：" + error);
                    }
                }).show();
    }

    private void performReferenceVideo(int seconds, boolean screenCapture) throws IOException {
        LogHub.banner("VIDEO_ATTEMPT " + System.currentTimeMillis()
                + " screenCapture=" + screenCapture + "; prior attempts retained");
        File sample = new File(getCacheDir(), "reference_video_" + System.currentTimeMillis() + ".ts");
        lastTsFile = sample;
        try (UsbNcmSession session = UsbNcmSession.open(this, cancelled);
                OutputStream output = new BufferedOutputStream(new FileOutputStream(sample));
                NoAuthVideoClient client = new NoAuthVideoClient(session::connect, cancelled, this::appendLog)) {
            appendLog("USB_USERSPACE: fixed private peer 192.168.77.1; no Wi-Fi/mobile fallback");
            long[] lastTables = {-1};
            boolean[] begun = {false};
            AvcReceiverContract contract = new AvcReceiverContract();
            MpegTsMuxer muxer = new MpegTsMuxer(output::write);
            VideoPatternProbe.Observer observer = new VideoPatternProbe.Observer() {
                @Override
                public void log(String message) {
                    appendLog(message);
                }

                @Override
                public void frame(byte[] data, long pts, boolean key) throws IOException {
                    contract.accept(data, MainActivity.this::appendLog);
                    // Connect/start only once the encoder has produced a complete picture.
                    // Otherwise the receiver's 1.5s video-idle safety timeout races codec startup.
                    if (!begun[0]) {
                        client.begin();
                        begun[0] = true;
                    }
                    if (key || lastTables[0] < 0 || pts - lastTables[0] >= 100_000L) {
                        muxer.writeTables();
                        lastTables[0] = pts;
                    }
                    muxer.writeAccessUnit(data, pts, key);
                    client.frame(data, pts);
                }
            };
            try {
                if (screenCapture) {
                    new AdbScreenVideoSource(authorizedAdbClient(), observer, this::appendLog)
                            .runSmallWindow(seconds, getApplicationInfo().sourceDir);
                } else {
                    new VideoPatternProbe(cancelled, observer,
                            NoAuthVideoProtocol.WIDTH, NoAuthVideoProtocol.HEIGHT).run(seconds);
                }
                client.requireWindowReport();
                appendLog(client.statistics());
                appendLog("Stream submitted and Screen visible state reported; NOT decoded-frame evidence; waiting for cleanup.");
            } finally {
                appendLog(session.statistics());
            }
        } finally {
            appendLog("NCM diagnostics saved for export. If the window did not clear, stop testing.");
        }
        appendLog("Protocol session ended with receiver window/cleanup reports; instrument picture and safety"
                + " still require manual observation. USB role NOT automatically restored.");
    }

    private void startVideoProbe(boolean transmit) {
        final int seconds;
        final int port;
        try {
            seconds = Integer.parseInt(secondsField.getText().toString().trim());
            port = Integer.parseInt(portField.getText().toString().trim());
            if (seconds < 1 || seconds > 60 || port < 1024 || port > 65535) {
                throw new IllegalArgumentException("Duration 1..60, port 1024..65535 required");
            }
        } catch (IllegalArgumentException error) {
            appendLog("Invalid video settings: " + error.getMessage());
            return;
        }
        String name = interfaceField.getText().toString().trim();
        String target = targetField.getText().toString().trim();
        ProbeAction action = () -> performVideoProbe(transmit, name, target, port, seconds);
        if (!transmit) {
            executeProbe("local hardware video", action);
            return;
        }
        new AlertDialog.Builder(this).setTitle("USB 视频台架验证")
                .setMessage("确认该网卡确为仪表 USB、接收 URL/端口已配置、小窗口不遮挡安全信息。"
                        + "本功能仅发送测试画面，不会自动改 QNX PPS、IP 或启动接收服务。")
                .setNegativeButton("取消", null)
                .setPositiveButton("已确认，发送", (dialog, which) -> executeProbe("USB RTP-TS video", action)).show();
    }

    private void performVideoProbe(boolean transmit, String name, String target, int port, int seconds)
            throws IOException {
        File sample = new File(getCacheDir(), "video_pattern_" + System.currentTimeMillis() + ".ts");
        lastTsFile = sample;
        try (UsbRtpSender sender = transmit ? new UsbRtpSender(this, name, target, port) : null;
                OutputStream output = new BufferedOutputStream(new FileOutputStream(sample))) {
            appendLog("Sample=" + sample.getAbsolutePath() + " source=moving-pattern (NOT screen capture)");
            if (sender != null) {
                appendLog(sender.description());
            }
            SecureRandom random = new SecureRandom();
            RtpTransportPacketizer rtp = sender == null ? null
                    : new RtpTransportPacketizer(sender, random.nextInt(), random.nextInt(65536));
            MpegTsMuxer muxer = new MpegTsMuxer(packet -> {
                output.write(packet);
                if (rtp != null) {
                    rtp.write(packet);
                }
            });
            long clockStart = System.nanoTime();
            long[] lastTables = {-1};
            VideoPatternProbe probe = new VideoPatternProbe(cancelled, new VideoPatternProbe.Observer() {
                @Override
                public void log(String message) {
                    appendLog(message);
                }

                @Override
                public void frame(byte[] data, long pts, boolean key) throws IOException {
                    if (rtp != null) {
                        rtp.beginAccessUnit((System.nanoTime() - clockStart) / 1000);
                    }
                    if (key || lastTables[0] < 0 || pts - lastTables[0] >= 100_000L) {
                        muxer.writeTables();
                        lastTables[0] = pts;
                    }
                    muxer.writeAccessUnit(data, pts, key);
                    if (rtp != null) {
                        rtp.flush();
                    }
                }
            });
            probe.run(seconds);
            if (sender != null) {
                appendLog(sender.statistics());
            }
            appendLog("Video probe finished. Receiver decode/display NOT automatically certified.");
        }
    }

    private void recordDisplayObservation() {
        new AlertDialog.Builder(this).setTitle("人工观察，单独记录，不替代接收日志")
                .setItems(new String[] {"仪表小窗口看到连续变化的视频/测试图", "仪表没有显示/黑屏", "尚未检查仪表"},
                        (dialog, index) -> appendLog("OPERATOR_OBSERVATION=" + index
                                + " (0=moving picture, 1=no picture, 2=not inspected); not machine verification"))
                .show();
    }

    private void exportReport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, "dashcast-phase1-" + System.currentTimeMillis() + ".zip");
        if (intent.resolveActivity(getPackageManager()) == null) {
            appendLog("No document picker installed; retrieve sample/logs through authorized PC ADB.");
            return;
        }
        startActivityForResult(intent, EXPORT_REQUEST);
    }

    private void exportLogsToDownloads() {
        executeProbe("export logs to Downloads", () -> {
            // Read-only refresh; keep the saved per-request OEM evidence, never send again.
            collectAndroidUsbEvidence();
            long exportedAt = System.currentTimeMillis();
            String fileName = DownloadLogExporter.fileName(exportedAt);
            String report = "Dashcast phase-one diagnostic snapshot\n"
                    + "APK=" + BuildConfig.VERSION_NAME + " package=" + getPackageName() + "\n"
                    + "exported_at_epoch_ms=" + exportedAt + "\n"
                    + "Android=" + android.os.Build.VERSION.RELEASE
                    + " SDK=" + android.os.Build.VERSION.SDK_INT + "\n"
                    + "BYDAuto grants: " + InstrumentUsbController.permissionSummary(this) + "\n"
                    + "Application log plus bounded, allowlisted OEM/Android state evidence; NOT QNX instrument logs.\n"
                    + "Network diagnostic addresses may be included. No video or credentials are collected.\n\n"
                    + reportLog.toString() + "\n" + ProjectionService.journal();
            String usbLog = LogHub.snapshot();
            try {
                DownloadLogExporter.export(new MediaStoreDownloadStore(getContentResolver()),
                        fileName, report, usbLog, lastRelayEvidence, lastAndroidUsbEvidence);
            } catch (IOException | RuntimeException error) {
                showExportMessage("日志导出失败，下载目录未确认保存。可使用“另存日志”选择位置。", true);
                throw error;
            }
            appendLog("EXPORT_SAVED Download/" + fileName
                    + " (report.txt + usb_ncm.txt + navigation_relay.txt + android_usb_state.txt; no TS sample)");
            showExportMessage("日志已保存到 下载 / " + fileName, false);
        }, true);
    }

    private void showExportMessage(String message, boolean failed) {
        mainHandler.post(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            exportStatusView.setText(message);
            Toast.makeText(this, failed ? message : "日志已导出，请到文件管理器的“下载”查看。",
                    Toast.LENGTH_LONG).show();
        });
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != EXPORT_REQUEST || result != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        String report = reportLog.toString() + "\n" + ProjectionService.journal();
        File sample = lastTsFile;
        android.net.Uri uri = data.getData();
        executeProbe("export", () -> {
            OutputStream destination = getContentResolver().openOutputStream(uri, "w");
            if (destination == null) {
                throw new IOException("Document output unavailable");
            }
            try (ZipOutputStream archive = new ZipOutputStream(destination)) {
                archive.putNextEntry(new ZipEntry("report.txt"));
                archive.write(report.getBytes(StandardCharsets.UTF_8));
                archive.closeEntry();
                archive.putNextEntry(new ZipEntry("usb_ncm.txt"));
                archive.write(LogHub.snapshot().getBytes(StandardCharsets.UTF_8));
                archive.closeEntry();
                archive.putNextEntry(new ZipEntry("navigation_relay.txt"));
                archive.write(lastRelayEvidence.getBytes(StandardCharsets.UTF_8));
                archive.closeEntry();
                archive.putNextEntry(new ZipEntry("android_usb_state.txt"));
                archive.write(lastAndroidUsbEvidence.getBytes(StandardCharsets.UTF_8));
                archive.closeEntry();
                if (sample != null && sample.isFile()) {
                    archive.putNextEntry(new ZipEntry(sample.getName()));
                    try (FileInputStream input = new FileInputStream(sample)) {
                        byte[] buffer = new byte[8192];
                        int length;
                        while ((length = input.read(buffer)) >= 0) {
                            archive.write(buffer, 0, length);
                        }
                    }
                    archive.closeEntry();
                }
            }
        });
    }

    private void runCaptureProbe() {
        executeProbe("H.264 probe", this::performCaptureProbe);
    }

    private void performAdbAuthorization() throws IOException {
        adbReady = false;
        adbAuthorizationOnly = true;
        try {
            AdbRsaIdentity identity = AndroidAdbIdentity.load();
            appendLog("ADB_KEY_FINGERPRINT=" + identity.fingerprint() + "; app-owned, preserved on update");
            appendLog("如出现 USB 调试授权弹窗，请核对指纹并点允许；电脑 ADB 的授权不是本 APK 的授权。");
            AdbShellClient client = newAdbClient(identity);
            InstrumentUsbController controller = new InstrumentUsbController(getApplicationContext(),
                    client, cancelled, this::appendLog);
            String uid = controller.executeChecked("id -u").trim();
            if (!"2000".equals(uid) && !"0".equals(uid)) {
                throw new IOException("ADB 身份不匹配 uid=" + uid);
            }
            adbReady = true;
            appendLog("ADB_AUTHORIZED uid=" + uid + "; read-only connection test; no USB/CAN command sent");
            showControlStatus("ADB 已连通；USB 切换仍须勾选停车 / 匹配固件并单独发送。");
            try {
                controller.verifyProfile();
                controller.receiverRegistered();
            } catch (IOException error) {
                appendLog("NAV_PROFILE_CHECK failed: " + describe(error) + "; USB sends will be blocked if unmatched");
                showControlStatus("ADB 已连通，但原厂中继版本未确认；请查看并导出日志。");
            }
        } catch (IOException error) {
            showControlStatus("ADB 连接未确认；请允许调试弹窗后重新连接，或导出日志。");
            throw error;
        } finally {
            awaitingAdbTrust = false;
            adbAuthorizationOnly = false;
        }
    }

    private AdbShellClient authorizedAdbClient() throws IOException {
        if (!adbReady) {
            throw new IOException("请先点“连接 / 授权 ADB”并允许车机调试授权。");
        }
        return newAdbClient(AndroidAdbIdentity.load());
    }

    private AdbShellClient newAdbClient(AdbRsaIdentity identity) {
        return new AdbShellClient(ADB_HOST, ADB_PORT, ADB_TIMEOUT_MILLIS, identity, message -> {
            if (message.startsWith("ADB_AUTH_CONFIRM")) {
                awaitingAdbTrust = true;
            } else if (message.startsWith("ADB_CONNECTED")) {
                awaitingAdbTrust = false;
            }
            appendLog(message);
        }, cancelled);
    }

    private void showControlStatus(String message) {
        mainHandler.post(() -> {
            if (!isFinishing() && !isDestroyed() && controlStatusView != null) {
                controlStatusView.setText(message);
            }
        });
    }

    private void runUsbProbe() {
        executeProbe("USB probe", this::performUsbProbe);
    }

    private void runCapabilityProbe() {
        executeProbe("Android capability probe", this::performCapabilityProbe);
    }

    private void runAllProbes() {
        executeProbe("all probes", () -> {
            try {
                performCaptureProbe();
            } catch (Exception error) {
                appendLog("H.264 probe failed; continuing with USB probe: "
                        + describe(error));
            }
            try {
                performUsbProbe();
            } catch (Exception error) {
                appendLog("USB probe failed: " + describe(error));
            }
            try {
                performCapabilityProbe();
            } catch (Exception error) {
                appendLog("Android capability probe failed: " + describe(error));
            }
        });
    }

    private void executeProbe(String label, ProbeAction action) {
        executeProbe(label, action, false);
    }

    private void executeProbe(String label, ProbeAction action, boolean readOnlyExport) {
        if (taskRunning || !readOnlyExport && ProjectionService.blocksOperations()) {
            appendLog("Busy; cancel/wait for current probe first");
            return;
        }
        final UsbOperationGate.Lease lease = readOnlyExport ? null : UsbOperationGate.acquire();
        if (!readOnlyExport && lease == null) {
            appendLog("Busy: another Activity/Service operation still owns the session or its cleanup");
            return;
        }
        taskRunning = true;
        cancelled.set(false);
        setBusy(true);
        appendLog("START " + label);
        try {
            probeExecutor.execute(() -> {
                try {
                    action.run();
                    appendLog("END " + label);
                } catch (Exception error) {
                    appendLog(label + " failed: " + describe(error));
                } finally {
                    if (lease != null) {
                        lease.close();
                    }
                    taskRunning = false;
                    setBusy(ProjectionService.blocksOperations());
                }
            });
        } catch (RuntimeException error) {
            if (lease != null) {
                lease.close();
            }
            taskRunning = false;
            setBusy(ProjectionService.blocksOperations());
            appendLog("Probe queue rejected: " + describe(error));
        }
    }

    private void performCaptureProbe() throws IOException {
        AdbShellClient client = authorizedAdbClient();
        File outputFile = new File(getCacheDir(),
                "dashcast_probe_" + System.currentTimeMillis() + ".h264");
        appendLog("ADB endpoint: " + ADB_HOST + ":" + ADB_PORT);
        appendLog("Command: " + SCREENRECORD_COMMAND);
        appendLog("Output: " + outputFile.getAbsolutePath());
        try (FileOutputStream outputStream = new FileOutputStream(outputFile)) {
            H264StreamAnalyzer analyzer = new H264StreamAnalyzer(outputStream);
            client.executeShellV2(SCREENRECORD_COMMAND, analyzer);
            analyzer.flush();
            H264StreamAnalyzer.Statistics statistics = analyzer.snapshot();
            appendLog("H.264: " + statistics);
            if (statistics.getSpsCount() == 0
                    || statistics.getPpsCount() == 0
                    || statistics.getIdrCount() == 0) {
                appendLog("H.264 CHECK FAIL: missing SPS/PPS/IDR");
            } else {
                appendLog("H.264 CHECK PASS: Annex-B key frames detected");
            }
        }
    }

    private void performUsbProbe() throws SocketException {
        appendLog("Checking Android USB Host enumeration...");
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            appendLog("USB CHECK FAIL: UsbManager unavailable");
        } else {
            inspectUsbDevices(usbManager.getDeviceList());
        }
        inspectNetworkInterfaces();
        inspectEthernetNetworks();
        collectAndroidUsbEvidence();
        appendLog("USB CHECK COMPLETE: this enumeration step does not change or certify QNX Role.");
    }

    private void performCapabilityProbe() throws IOException {
        appendLog("Checking read-only Android and CAN control-plane evidence...");
        EncoderCapabilityProbe.logHardwareAvc(this::appendLog);
        AdbShellClient client = authorizedAdbClient();
        probeAdbTextCommand(client, "execution identity", "id");
        probeAdbTextCommand(client, "car protocol", CAR_PROTOCOL_COMMAND);
        probeAdbTextCommand(client, "Android SDK", SDK_VERSION_COMMAND);
        probeAdbTextCommand(client, "Android release", RELEASE_VERSION_COMMAND);
        probeAdbTextCommand(client, "services", SERVICE_LIST_COMMAND,
                "byd", "vehicle", "instrument", "can", "auto");
        probeAdbTextCommand(client, "media codec", MEDIA_CODEC_COMMAND,
                "avc", "encoder", "omx.qcom", "c2.qti");
        probeAdbTextCommand(client, "network links", NETWORK_LINK_COMMAND,
                "can", "eth", "usb", "ncm", "ecm", "rndis");
        appendLog("CAN CONTROL CHECK COMPLETE: APK sent no CAN frame and did not change QNX Role.");
    }

    private void probeAdbTextCommand(AdbShellClient client, String label,
            String command, String... keywords) throws IOException {
        AdbTextProbe probe = new AdbTextProbe(MAX_TEXT_PROBE_BYTES);
        client.executeShell(AdbCommandOutput.wrap(command), probe);
        appendLog("ADB " + label + ": bytes=" + probe.getTotalBytes()
                + " truncated=" + probe.isTruncated());
        try {
            String output = ReadOnlyProbeResult.requireOutput(probe.getText(), probe.isTruncated());
            appendMatchingProbeLines(label, output, keywords);
        } catch (IOException error) {
            appendLog("ADB " + label + " CHECK_UNAVAILABLE: " + describe(error));
        }
    }

    private void appendMatchingProbeLines(String label, String text, String... keywords) {
        String[] lines = text.split("\\r?\\n", -1);
        int loggedLines = 0;
        for (String line : lines) {
            String trimmedLine = line.trim();
            if (trimmedLine.isEmpty() || !containsKeyword(trimmedLine, keywords)) {
                continue;
            }
            appendLog("ADB " + label + ": " + trimmedLine);
            loggedLines++;
            if (loggedLines >= MAX_LOGGED_PROBE_LINES) {
                appendLog("ADB " + label + ": output logging capped at "
                        + MAX_LOGGED_PROBE_LINES + " lines");
                return;
            }
        }
        if (loggedLines == 0) {
            appendLog("ADB " + label + ": no matching output");
        }
    }

    private boolean containsKeyword(String line, String... keywords) {
        if (keywords.length == 0) {
            return true;
        }
        String normalizedLine = line.toLowerCase(Locale.US);
        for (String keyword : keywords) {
            if (normalizedLine.contains(keyword.toLowerCase(Locale.US))) {
                return true;
            }
        }
        return false;
    }

    private void inspectUsbDevices(Map<String, UsbDevice> devices) {
        if (devices == null || devices.isEmpty()) {
            appendLog("USB devices=0");
            return;
        }
        appendLog("USB devices=" + devices.size());
        for (Map.Entry<String, UsbDevice> entry : devices.entrySet()) {
            UsbDevice device = entry.getValue();
            appendLog(String.format(Locale.US,
                    "USB %s vid=0x%04x pid=0x%04x class=%d subclass=%d "
                            + "protocol=%d interfaces=%d",
                    entry.getKey(),
                    device.getVendorId(),
                    device.getProductId(),
                    device.getDeviceClass(),
                    device.getDeviceSubclass(),
                    device.getDeviceProtocol(),
                    device.getInterfaceCount()));
            for (int index = 0; index < device.getInterfaceCount(); index++) {
                UsbInterface usbInterface = device.getInterface(index);
                appendLog(String.format(Locale.US,
                        "  IF%d class=%d subclass=%d protocol=%d endpoints=%d",
                        index,
                        usbInterface.getInterfaceClass(),
                        usbInterface.getInterfaceSubclass(),
                        usbInterface.getInterfaceProtocol(),
                        usbInterface.getEndpointCount()));
            }
        }
    }

    private void inspectNetworkInterfaces() throws SocketException {
        appendLog("Checking Android network interfaces...");
        java.util.Enumeration<NetworkInterface> enumeration =
                NetworkInterface.getNetworkInterfaces();
        if (enumeration == null) {
            appendLog("NET interfaces unavailable");
            return;
        }
        List<NetworkInterface> interfaces = Collections.list(enumeration);
        int upCount = 0;
        for (NetworkInterface networkInterface : interfaces) {
            if (!networkInterface.isUp()) {
                continue;
            }
            upCount++;
            StringBuilder addresses = new StringBuilder();
            for (InetAddress address : Collections.list(
                    networkInterface.getInetAddresses())) {
                if (addresses.length() > 0) {
                    addresses.append(',');
                }
                addresses.append(address.getHostAddress());
            }
            appendLog("NET " + networkInterface.getName()
                    + " mtu=" + networkInterface.getMTU()
                    + " addresses=" + addresses);
        }
        appendLog("up interfaces=" + upCount);
    }

    private void inspectEthernetNetworks() {
        ConnectivityManager connectivityManager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            appendLog("ETHERNET unavailable: ConnectivityManager unavailable");
            return;
        }
        int ethernetCount = 0;
        for (Network network : connectivityManager.getAllNetworks()) {
            NetworkCapabilities capabilities =
                    connectivityManager.getNetworkCapabilities(network);
            if (capabilities != null
                    && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                ethernetCount++;
                appendLog("ETHERNET network=" + network
                        + " links=" + connectivityManager.getLinkProperties(network)
                        + " validated="
                        + capabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED));
            }
        }
        appendLog("Ethernet networks=" + ethernetCount);
    }

    private void setBusy(boolean busy) {
        mainHandler.post(() -> {
            if (captureSettingsView != null) {
                captureSettingsView.controlsEnabled(!busy);
            }
            for (Button button : taskButtons) {
                button.setEnabled(!busy);
            }
            if (receiverInstalled != null) {
                receiverInstalled.setEnabled(!busy);
            }
            for (EditText field : new EditText[] {targetField, portField, interfaceField, secondsField,
                    continuousSecondsField}) {
                if (field != null) {
                    field.setEnabled(!busy);
                }
            }
            if (captureButton != null) {
                captureButton.setEnabled(!busy);
            }
            if (usbButton != null) {
                usbButton.setEnabled(!busy);
            }
            if (capabilityButton != null) {
                capabilityButton.setEnabled(!busy);
            }
            if (allButton != null) {
                allButton.setEnabled(!busy);
            }
        });
    }

    private void appendLog(String message) {
        synchronized (reportLog) {
            reportLog.append(System.currentTimeMillis()).append(' ').append(message).append('\n');
            if (reportLog.length() > MAX_REPORT_CHARS) {
                reportLog.delete(0, reportLog.length() - MAX_REPORT_CHARS);
            }
        }
        mainHandler.post(() -> {
            if (logView == null) {
                return;
            }
            logView.append(message);
            logView.append("\n");
            if (logView.length() > MAX_VIEW_CHARS) {
                logView.setText(logView.getText().subSequence(logView.length() - MAX_VIEW_CHARS, logView.length()));
            }
        });
    }

    private String describe(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isEmpty()) {
            return error.getClass().getSimpleName();
        }
        return error.getClass().getSimpleName() + ": " + message;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * One bounded background diagnostic operation.
     *
     * @author Codex
     * @date 2026/10/09
     */
    private interface ProbeAction {

        /**
         * Runs the diagnostic operation.
         *
         * @throws Exception when the probe fails
         */
        void run() throws Exception;
    }

    /**
     * Gives probe threads stable names for field diagnostics.
     *
     * @author Codex
     * @date 2026/10/09
     */
    private static final class ProbeThreadFactory implements ThreadFactory {

        private final AtomicInteger sequence = new AtomicInteger(1);

        /**
         * Creates one daemon probe thread.
         *
         * @param runnable probe task
         * @return named daemon thread
         */
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable,
                    "dashcast-phase1-probe-" + sequence.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
