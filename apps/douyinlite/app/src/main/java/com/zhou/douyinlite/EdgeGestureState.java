package com.zhou.douyinlite;

/**
 * 仅描述边缘长按的状态，不依赖 Android。普通滑动在长按前超出容差时立即放行。
 * captured 保持到本次手势结束，避免取消倍速后向网页发送一个缺少 DOWN 的 MOVE。
 */
public final class EdgeGestureState {
    private final float movementSlopSquared;
    private final float lockDistance;
    private float downX;
    private float downY;
    private boolean allowed;
    private boolean candidate;
    private boolean held;
    private boolean locked;
    private boolean captured;

    public EdgeGestureState(float movementSlop, float lockDistance) {
        if (movementSlop < 0 || lockDistance <= 0) {
            throw new IllegalArgumentException("Invalid gesture distances");
        }
        this.movementSlopSquared = movementSlop * movementSlop;
        this.lockDistance = lockDistance;
    }

    public void setAllowed(boolean allowed) {
        this.allowed = allowed;
        if (!allowed) resetBoost();
    }

    public void down(float x, float y, boolean edgeEligible, int pointerCount) {
        downX = x;
        downY = y;
        held = false;
        captured = false;
        candidate = edgeEligible && pointerCount == 1 && !locked;
        if (pointerCount != 1) resetBoost();
    }

    public void move(float x, float y, int pointerCount) {
        if (pointerCount != 1) {
            multiplePointers();
            return;
        }
        float dx = x - downX;
        float dy = y - downY;
        if (candidate && dx * dx + dy * dy > movementSlopSquared) {
            candidate = false;
        }
        if (held && captured && dy >= lockDistance) {
            locked = true;
        }
    }

    /** 在定时器到期时调用；false 表示网页没有可倍速的视频或候选已失效。 */
    public boolean longPress() {
        if (!candidate) return false;
        candidate = false;
        if (!allowed) return false;
        held = true;
        captured = true;
        return true;
    }

    public void up() {
        candidate = false;
        held = false;
        captured = false;
    }

    /** 新增第二根手指立即恢复倍速；已截获的触摸序列仍由容器结束。 */
    public void multiplePointers() {
        resetBoost();
    }

    public void cancel() {
        resetBoost();
        captured = false;
    }

    /** 后台、换页面、网页失去可播放视频、用户主动解锁共用这一入口。 */
    public void resetBoost() {
        candidate = false;
        held = false;
        locked = false;
    }

    public boolean isCandidate() { return candidate; }
    public boolean isBoosted() { return held || locked; }
    public boolean isLocked() { return locked; }
    public boolean isCaptured() { return captured; }
}
