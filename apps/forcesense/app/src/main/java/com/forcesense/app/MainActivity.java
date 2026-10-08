package com.forcesense.app;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Native UI; real TYPE_LIGHT readings drive an explicitly simulated force. */
public final class MainActivity extends Activity implements SensorEventListener {
    private static final String TAG = "ForceSense";
    private static final int BG = Color.rgb(248, 250, 249);
    private static final int INK = Color.rgb(22, 44, 41);
    private static final int MUTED = Color.rgb(96, 116, 111);
    private static final int TEAL = Color.rgb(10, 135, 112);
    private static final int TINT = Color.rgb(234, 245, 239);
    private static final int LINE = Color.rgb(220, 231, 226);
    private final LightForceMapper mapper = new LightForceMapper();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SensorManager sensorManager;
    private Sensor lightSensor;
    private SharedPreferences preferences;
    private ForceDiagramView diagram;
    private TextView sourceBadge, forceValue, forceState, lightValue, baselineValue, instruction, sensorName;
    private ProgressBar forceBar;
    private Button calibrateButton, modeButton;
    private ValueAnimator animator;
    private float currentLux = Float.NaN;
    private float animatedForce = 0f;
    private float mappedForce = Float.NaN;
    private float requestedForce = Float.NaN;
    private boolean receivedReading = false;
    private boolean active = false;
    private long eventCount = 0;
    private long lastEventElapsedMs = 0;
    private long lastLogElapsedMs = 0;
    private final Runnable noDataCheck = () -> {
        if (active && !receivedReading && lightSensor != null) {
            showUnavailable("等待光感数据", "请保持手机亮屏，并移动到有光的环境。", false);
            Log.w(TAG, "LIGHT_WAITING: registered but no sensor event yet");
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("light_calibration", MODE_PRIVATE);
        mapper.calibrate(preferences.getFloat("baseline_lux", Float.NaN));
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        buildInterface();
        diagram.setDetailMode(preferences.getBoolean("detail_mode", false));
        updateModeLabel();
    }

    private void buildInterface() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout page = column();
        page.setPadding(dp(20), dp(10), dp(20), dp(18));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        // API 35+ enforces edge-to-edge on Android 15/16. Respect both system
        // bars and camera cutouts, including landscape and gesture navigation.
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return insets;
            });
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) controller.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            root.requestApplyInsets();
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }

        LinearLayout header = row();
        TextView logo = label("FS", 14, Color.WHITE, true);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(rounded(Color.rgb(21, 63, 53), 10));
        header.addView(logo, new LinearLayout.LayoutParams(dp(34), dp(34)));
        TextView brand = label("ForceSense", 19, INK, true);
        LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(0, -2, 1f);
        brandParams.leftMargin = dp(9);
        header.addView(brand, brandParams);
        sourceBadge = label("读取光感…", 11, TEAL, false);
        sourceBadge.setPadding(dp(10), dp(6), dp(10), dp(6));
        sourceBadge.setBackground(rounded(TINT, 30));
        header.addView(sourceBadge);
        add(page, header, 0);

        TextView eyebrow = label("FEEL THE FORCE", 10, MUTED, false);
        eyebrow.setLetterSpacing(.18f);
        add(page, eyebrow, 18);
        TextView title = label("让每一分力量，都看得见。", 24, INK, true);
        title.setLineSpacing(0, 1.12f);
        add(page, title, 4);
        LinearLayout subtitleRow = row();
        TextView subtitle = label("光感模拟 · 越遮光，形变越大", 12, MUTED, false);
        subtitleRow.addView(subtitle, new LinearLayout.LayoutParams(0, -2, 1));
        modeButton = new Button(this);
        modeButton.setTextSize(11);
        modeButton.setTextColor(TEAL);
        modeButton.setAllCaps(false);
        modeButton.setMinHeight(0);
        modeButton.setMinimumHeight(0);
        modeButton.setMinWidth(0);
        modeButton.setMinimumWidth(0);
        modeButton.setPadding(dp(8), 0, dp(8), 0);
        modeButton.setBackground(rounded(TINT, 8));
        subtitleRow.addView(modeButton, new LinearLayout.LayoutParams(-2, dp(40)));
        modeButton.setOnClickListener(view -> {
            diagram.setDetailMode(!diagram.isDetailMode());
            preferences.edit().putBoolean("detail_mode", diagram.isDetailMode()).apply();
            updateModeLabel();
        });
        add(page, subtitleRow, 4);

        diagram = new ForceDiagramView(this);
        add(page, diagram, 6);

        LinearLayout pressureRow = row();
        pressureRow.addView(label("模拟按压强度", 13, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        forceValue = label("—", 34, INK, false);
        forceValue.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        forceValue.setGravity(Gravity.END);
        forceValue.setMinWidth(dp(86));
        pressureRow.addView(forceValue);
        add(page, pressureRow, 12);
        forceBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        forceBar.setMax(1000);
        forceBar.setProgressTintList(ColorStateList.valueOf(TEAL));
        forceBar.setProgressBackgroundTintList(ColorStateList.valueOf(LINE));
        forceBar.setContentDescription("模拟按压力，等待真实光感数据");
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, dp(12));
        barParams.topMargin = dp(2);
        page.addView(forceBar, barParams);
        forceState = label("等待光感数据", 11, TEAL, false);
        forceState.setGravity(Gravity.CENTER);
        add(page, forceState, 3);

        LinearLayout readings = row();
        readings.setPadding(dp(12), dp(8), dp(12), dp(8));
        readings.setBackground(rounded(Color.WHITE, 12));
        lightValue = label("实时光照\n— lx", 12, INK, false);
        baselineValue = label("零点光照\n— lx", 12, INK, false);
        baselineValue.setGravity(Gravity.END);
        readings.addView(lightValue, new LinearLayout.LayoutParams(0, -2, 1));
        readings.addView(baselineValue, new LinearLayout.LayoutParams(0, -2, 1));
        add(page, readings, 10);

        calibrateButton = new Button(this);
        calibrateButton.setText("以当前光照设为零点");
        calibrateButton.setTextSize(13);
        calibrateButton.setTextColor(Color.WHITE);
        calibrateButton.setAllCaps(false);
        calibrateButton.setBackground(rounded(Color.rgb(21, 63, 53), 16));
        calibrateButton.setEnabled(false);
        calibrateButton.setOnClickListener(view -> calibrate());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, dp(48));
        buttonParams.topMargin = dp(12);
        page.addView(calibrateButton, buttonParams);
        instruction = label("先露出光感区域设定零点，再遮挡体验形变。", 11, MUTED, false);
        instruction.setGravity(Gravity.CENTER);
        instruction.setLineSpacing(dp(2), 1);
        add(page, instruction, 8);
        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(-1, dp(1));
        lineParams.topMargin = dp(14);
        page.addView(divider, lineParams);
        TextView note = label("光照为实测 · 按压力与形变为放大模拟示意", 10, MUTED, false);
        note.setGravity(Gravity.CENTER);
        add(page, note, 9);
        sensorName = label(lightSensor == null ? "未检测到光感传感器" : lightSensor.getName(), 9, MUTED, false);
        sensorName.setGravity(Gravity.CENTER);
        add(page, sensorName, 3);
    }

    private void updateModeLabel() {
        modeButton.setText(diagram.isDetailMode() ? "侧键透视" : "形变特写");
        modeButton.setContentDescription("切换到" + modeButton.getText() + "视图");
    }

    @Override protected void onResume() {
        super.onResume();
        active = true;
        receivedReading = false;
        currentLux = Float.NaN;
        mappedForce = Float.NaN;
        showUnavailable("读取光感…", "请露出手机光感区域，正在接收真实光照。", false);
        if (lightSensor == null) {
            showUnavailable("无光感传感器", "这台设备没有可用的光感传感器。", true);
            Log.w(TAG, "LIGHT_UNAVAILABLE: TYPE_LIGHT sensor missing");
            return;
        }
        boolean registered = sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        Log.i(TAG, "LIGHT_REGISTERED=" + registered + " name=" + lightSensor.getName() + " source=TYPE_LIGHT reportingMode=" + lightSensor.getReportingMode());
        if (!registered) {
            showUnavailable("光感不可用", "光感传感器注册失败，请重新打开应用。", true);
            return;
        }
        handler.postDelayed(noDataCheck, 5000);
    }

    @Override protected void onPause() {
        active = false;
        sensorManager.unregisterListener(this);
        handler.removeCallbacks(noDataCheck);
        if (animator != null) animator.cancel();
        Log.i(TAG, "LIGHT_UNREGISTERED: activity paused");
        writeSnapshot();
        super.onPause();
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (!active || event.sensor.getType() != Sensor.TYPE_LIGHT || event.values.length == 0) return;
        float lux = event.values[0];
        if (!Float.isFinite(lux) || lux < 0f) {
            currentLux = Float.NaN;
            mappedForce = Float.NaN;
            receivedReading = false;
            showUnavailable("光感读数异常", "收到无效光照，正在等待有效数据。", false);
            return;
        }
        receivedReading = true;
        currentLux = lux;
        lastEventElapsedMs = SystemClock.elapsedRealtime();
        eventCount++;
        handler.removeCallbacks(noDataCheck);
        calibrateButton.setEnabled(true);
        calibrateButton.setAlpha(1f);
        setTextIfChanged(lightValue, getString(R.string.light_reading, formatLux(lux)));
        if (!mapper.hasBaseline()) {
            if (mapper.calibrate(lux)) {
                preferences.edit().putFloat("baseline_lux", mapper.getBaselineLux()).apply();
                Log.i(TAG, "AUTO_CALIBRATION baseline_lux=" + mapper.getBaselineLux());
            } else {
                forceValue.setText("—");
                diagram.setForce(Float.NaN);
                forceState.setText("光线不足，尚未建立零点");
                sourceBadge.setText("光线不足");
                instruction.setText("移动到明亮处、露出光感区域后再设定零点。");
                writeSnapshot();
                return;
            }
        }
        mappedForce = mapper.forceForLux(lux);
        setTextIfChanged(sourceBadge, "● 光感驱动");
        setTextIfChanged(baselineValue, getString(R.string.baseline_reading, formatLux(mapper.getBaselineLux())));
        setTextIfChanged(instruction, "遮挡光感区域 → 加力；移开遮挡 → 回弹。\n环境光变化后可重新设定零点。");
        updateForce(mappedForce);
        if (lastEventElapsedMs - lastLogElapsedMs >= 400) {
            Log.i(TAG, String.format(Locale.US, "LIGHT_EVENT count=%d lux=%.3f baseline_lux=%.3f simulated_force_percent=%.2f source=TYPE_LIGHT", eventCount, currentLux, mapper.getBaselineLux(), mappedForce));
            lastLogElapsedMs = lastEventElapsedMs;
            writeSnapshot();
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {
        Log.d(TAG, "LIGHT_ACCURACY=" + accuracy);
    }

    private void calibrate() {
        if (!active || !receivedReading || !mapper.calibrate(currentLux)) {
            Toast.makeText(this, "当前光照不足，请露出光感区域并移到明亮处。", Toast.LENGTH_LONG).show();
            return;
        }
        preferences.edit().putFloat("baseline_lux", mapper.getBaselineLux()).apply();
        mappedForce = mapper.forceForLux(currentLux);
        baselineValue.setText(getString(R.string.baseline_reading, formatLux(mapper.getBaselineLux())));
        updateForce(mappedForce);
        Log.i(TAG, "USER_CALIBRATION baseline_lux=" + mapper.getBaselineLux());
        Toast.makeText(this, "零点已设定，现在遮挡光感区域体验形变。", Toast.LENGTH_SHORT).show();
        writeSnapshot();
    }

    private void updateForce(float force) {
        if (!Float.isFinite(force)) return;
        if (Float.isFinite(requestedForce) && Math.abs(requestedForce - force) < .01f) return;
        requestedForce = force;
        if (animator != null) animator.cancel();
        forceBar.setContentDescription("由实测光照计算的模拟按压强度 " + Math.round(force) + "%");
        setTextIfChanged(forceState, force < .5f ? "未按压 · 恢复原状" : force < 40f ? "轻按 · 微小形变" : force < 75f ? "中等力度 · 形变增加" : "较大力度 · 形变更明显");
        // Avoid starting zero-distance animations for stable on-change readings.
        if (Math.abs(animatedForce - force) < .01f || !ValueAnimator.areAnimatorsEnabled()) {
            renderForce(force);
            return;
        }
        animator = ValueAnimator.ofFloat(animatedForce, force);
        animator.setDuration(180);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> renderForce((float) animation.getAnimatedValue()));
        animator.start();
    }

    private void renderForce(float force) {
        animatedForce = force;
        setTextIfChanged(forceValue, getString(R.string.force_percent, Math.round(force)));
        forceBar.setProgress(Math.round(force * 10));
        diagram.setForce(force);
    }

    private static void setTextIfChanged(TextView view, String text) {
        if (!text.contentEquals(view.getText())) view.setText(text);
    }

    private void showUnavailable(String badge, String message, boolean noSensor) {
        if (animator != null) animator.cancel();
        animatedForce = 0f;
        mappedForce = Float.NaN;
        requestedForce = Float.NaN;
        sourceBadge.setText(badge);
        forceValue.setText("—");
        forceBar.setProgress(0);
        forceBar.setContentDescription(noSensor ? "光感数据不可用，模拟按压力未显示" : "模拟按压力，等待真实光感数据");
        diagram.setForce(Float.NaN);
        lightValue.setText("实时光照\n— lx");
        baselineValue.setText(getString(R.string.baseline_reading, mapper.hasBaseline() ? formatLux(mapper.getBaselineLux()) : "—"));
        forceState.setText(noSensor ? "光感数据不可用" : "等待有效光感数据");
        instruction.setText(message);
        calibrateButton.setEnabled(false);
        calibrateButton.setAlpha(.45f);
        writeSnapshot();
    }

    /** On-device evidence only; no network, storage, or sensor permissions. */
    private void writeSnapshot() {
        String json = String.format(Locale.US,
                "{\"active\":%s,\"source\":\"TYPE_LIGHT\",\"receivedReading\":%s,\"sensor\":\"%s\",\"lux\":%s,\"baselineLux\":%s,\"simulatedForcePercent\":%s,\"eventCount\":%d,\"lastEventElapsedMs\":%d}",
                active, receivedReading, lightSensor == null ? "none" : lightSensor.getName().replace("\\", "\\\\").replace("\"", "\\\""),
                jsonNumber(currentLux), jsonNumber(mapper.getBaselineLux()), jsonNumber(mappedForce), eventCount, lastEventElapsedMs);
        try (FileOutputStream output = new FileOutputStream(new File(getFilesDir(), "sensor_state.json"))) {
            output.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            Log.w(TAG, "Could not write sensor evidence", error);
        }
    }

    private static String jsonNumber(float value) { return Float.isFinite(value) ? String.format(Locale.US, "%.4f", value) : "null"; }
    private static String formatLux(float lux) { return String.format(Locale.US, lux >= 1000 ? "%.0f" : "%.1f", lux); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private TextView label(String text, float size, int color, boolean medium) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        view.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return view;
    }
    private void add(LinearLayout parent, View child, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topMargin);
        parent.addView(child, params);
    }
    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }
}
