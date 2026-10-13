package com.zhou.douyinlite;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.FrameLayout;

/**
 * 包住 WebView 的透明手势容器。中间滑动、边缘短划均交给网页；只有静止长按
 * 成功后才取消网页触摸并接管本次手势。真正设置 video.playbackRate 由 Activity 负责。
 */
public final class EdgeGestureLayout extends FrameLayout {
    public interface Listener {
        void onBoost(boolean boosted, boolean locked);
        default void onUnavailable() { }
    }

    public static final long LONG_PRESS_MILLIS = 380L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final EdgeGestureState state;
    private final float density;
    private Listener listener;
    private MotionEvent lastChildEvent;
    private boolean sendingChildCancel;
    private boolean reportedBoosted;
    private boolean reportedLocked;

    private final Runnable activateLongPress = this::activateLongPress;

    private void activateLongPress() {
        boolean hadCandidate = state.isCandidate();
        if (!state.longPress()) {
            recycleChildEvent();
            if (hadCandidate && listener != null) listener.onUnavailable();
            return;
        }
        // WebView 可能要求父容器不截获；长按已确认后才撤销本容器上的这个标志。
        super.requestDisallowInterceptTouchEvent(false);
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
        cancelChildTouch();
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        reportState();
    }

    public EdgeGestureLayout(Context context) {
        this(context, null);
    }

    public EdgeGestureLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        float slop = Math.max(ViewConfiguration.get(context).getScaledTouchSlop(), dp(8));
        state = new EdgeGestureState(slop, dp(72));
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** 默认 false。Activity 只有确认页面存在当前可播放视频后才打开。 */
    public void setBoostAllowed(boolean allowed) {
        state.setAllowed(allowed);
        if (!state.isCandidate()) stopLongPress();
        reportState();
    }

    public boolean isBoostLocked() {
        return state.isLocked();
    }

    public void resetBoost() {
        stopLongPress();
        state.resetBoost();
        reportState();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();
        // UP/CANCEL 更新状态后 captured 会清除，但该事件依然属于已接管的触摸序列。
        final boolean ownedAtEntry = state.isCaptured();
        if (action == MotionEvent.ACTION_DOWN) {
            stopLongPress();
            state.down(event.getX(), event.getY(), isEligibleEdge(event), event.getPointerCount());
            if (state.isCandidate()) {
                saveChildEvent(event);
                handler.postDelayed(activateLongPress, LONG_PRESS_MILLIS);
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            state.move(event.getX(), event.getY(), event.getPointerCount());
            if (state.isCandidate()) saveChildEvent(event);
            else stopLongPress();
        } else if (action == MotionEvent.ACTION_POINTER_DOWN
                || action == MotionEvent.ACTION_POINTER_UP) {
            state.multiplePointers();
            stopLongPress();
        } else if (action == MotionEvent.ACTION_UP) {
            state.up();
            stopLongPress();
        } else if (action == MotionEvent.ACTION_CANCEL) {
            state.cancel();
            stopLongPress();
        }
        reportState();

        boolean handled;
        if (ownedAtEntry && !state.isCaptured()) {
            // 子 View 已经收到 CANCEL，终止事件不能重新分发给它。
            handled = true;
        } else {
            handled = super.dispatchTouchEvent(event);
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            ViewParent parent = getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
        }
        return handled;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return !sendingChildCancel && state.isCaptured();
    }

    @Override
    @SuppressLint("ClickableViewAccessibility") // 仅接管已确认的长按，点击由网页处理，不合成点击事件。
    public boolean onTouchEvent(MotionEvent event) {
        return state.isCaptured() || super.onTouchEvent(event);
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        // 候选阶段还没有接管网页，只保留观察长按的机会。短划超过 slop 后立即尊重网页。
        if (disallowIntercept && state.isCandidate()) return;
        super.requestDisallowInterceptTouchEvent(disallowIntercept);
    }

    @Override
    protected void onDetachedFromWindow() {
        resetBoost();
        state.cancel();
        super.onDetachedFromWindow();
    }

    private boolean isEligibleEdge(MotionEvent event) {
        float edgeWidth = Math.min(dp(72), Math.max(dp(48), getWidth() * 0.12f));
        float x = event.getX();
        float y = event.getY();
        return getWidth() > 0 && getHeight() > dp(190)
                && y >= dp(90) && y <= getHeight() - dp(100)
                && (x <= edgeWidth || x >= getWidth() - edgeWidth);
    }

    private void cancelChildTouch() {
        if (lastChildEvent == null) return;
        MotionEvent cancel = MotionEvent.obtain(lastChildEvent);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        sendingChildCancel = true;
        try {
            super.dispatchTouchEvent(cancel);
        } finally {
            sendingChildCancel = false;
            cancel.recycle();
            recycleChildEvent();
        }
    }

    private void saveChildEvent(MotionEvent event) {
        recycleChildEvent();
        lastChildEvent = MotionEvent.obtain(event);
    }

    private void stopLongPress() {
        handler.removeCallbacks(activateLongPress);
        recycleChildEvent();
    }

    private void recycleChildEvent() {
        if (lastChildEvent != null) {
            lastChildEvent.recycle();
            lastChildEvent = null;
        }
    }

    private void reportState() {
        boolean boosted = state.isBoosted();
        boolean locked = state.isLocked();
        if (boosted != reportedBoosted || locked != reportedLocked) {
            reportedBoosted = boosted;
            reportedLocked = locked;
            if (listener != null) listener.onBoost(boosted, locked);
        }
    }

    private float dp(float value) { return value * density; }
}
