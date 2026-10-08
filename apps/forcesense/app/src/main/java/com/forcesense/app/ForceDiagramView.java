package com.forcesense.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.util.Locale;

/** Native illustration of the force path. All deformation is illustrative. */
public final class ForceDiagramView extends View {
    private static final float DESIGN_WIDTH = 344.0f;
    private static final int BG = Color.rgb(234, 245, 239);
    private static final int WHITE = Color.WHITE;
    private static final int INK = Color.rgb(22, 44, 41);
    private static final int MUTED = Color.rgb(96, 116, 111);
    private static final int ACCENT = Color.rgb(10, 135, 112);
    private static final int PHONE = Color.rgb(200, 213, 208);
    private static final int GLASS = Color.rgb(245, 248, 245);
    private static final int SKIN = Color.rgb(234, 217, 199);
    private static final int SKIN_LINE = Color.rgb(196, 170, 146);
    private static final int LINE = Color.rgb(220, 231, 226);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final DashPathEffect dashed = new DashPathEffect(new float[] {4, 4}, 0);
    private final Typeface normal = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final float density;

    private boolean detailMode;
    private boolean hasValidForce;
    private float force;
    private float drawingScale = 1.0f;
    private int accessiblePercent = -1;
    private boolean accessibleWaiting;
    private boolean accessibleDetail;

    public ForceDiagramView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setFocusable(true);
        updateAccessibility(true);
    }

    /** Receives an already animated percentage; this view owns no animator. */
    public void setForce(float percent) {
        boolean valid = !Float.isNaN(percent) && !Float.isInfinite(percent);
        float next = valid ? Math.max(0, Math.min(100, percent)) : 0;
        if (valid != hasValidForce || next != force) {
            hasValidForce = valid;
            force = next;
            updateAccessibility(false);
            invalidate();
        }
    }

    public void setDetailMode(boolean detail) {
        if (detailMode != detail) {
            detailMode = detail;
            updateAccessibility(true);
            requestLayout();
            invalidate();
        }
    }

    public boolean isDetailMode() {
        return detailMode;
    }

    private float designHeight() {
        return detailMode ? 350.0f : 392.0f;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int horizontalPadding = getPaddingLeft() + getPaddingRight();
        int verticalPadding = getPaddingTop() + getPaddingBottom();
        int desiredWidth = Math.round(DESIGN_WIDTH * density) + horizontalPadding;
        int measuredWidth = resolveSizeAndState(desiredWidth, widthMeasureSpec, 0);
        float availableWidth = Math.max(0,
                (measuredWidth & MEASURED_SIZE_MASK) - horizontalPadding);
        int desiredHeight = Math.round(availableWidth * designHeight() / DESIGN_WIDTH)
                + verticalPadding;
        int measuredHeight = resolveSizeAndState(desiredHeight, heightMeasureSpec, 0);
        setMeasuredDimension(measuredWidth, measuredHeight);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth() - getPaddingLeft() - getPaddingRight();
        float height = getHeight() - getPaddingTop() - getPaddingBottom();
        if (width <= 0 || height <= 0) {
            return;
        }
        // A uniform scale preserves all geometry if the parent constrains height.
        drawingScale = Math.min(width / DESIGN_WIDTH, height / designHeight());
        int save = canvas.save();
        canvas.translate(getPaddingLeft() + (width - DESIGN_WIDTH * drawingScale) / 2,
                getPaddingTop() + (height - designHeight() * drawingScale) / 2);
        canvas.scale(drawingScale, drawingScale);
        roundRect(canvas, 0, 0, DESIGN_WIDTH, designHeight(), 24, BG);
        if (detailMode) {
            drawDetail(canvas);
        } else {
            drawOverview(canvas);
        }
        canvas.restoreToCount(save);
    }

    private void drawOverview(Canvas canvas) {
        label(canvas, "手机侧键透视", 16, 25, 12, INK, true, Paint.Align.LEFT);
        label(canvas, "形变同步示意", 328, 25, 11, MUTED, false, Paint.Align.RIGHT);

        int save = canvas.save();
        canvas.translate(18, 30);
        canvas.scale(0.90f, 0.90f);
        float savedScale = drawingScale;
        drawingScale *= 0.90f;
        drawPhone(canvas);
        drawingScale = savedScale;
        canvas.restoreToCount(save);

        roundRect(canvas, 7, 251, 337, 385, 19, WHITE);
        focusIcon(canvas, 24, 272);
        label(canvas, "传感器 · 形变放大", 37, 275, 12, INK, true, Paint.Align.LEFT);
        dimensionBadge(canvas, 220, 257, 329, 281);
        drawBeam(canvas, 326, 346, 356, 375, false);
    }

    private void drawPhone(Canvas canvas) {
        float move = force / 100.0f * 1.8f;
        fill(INK, 15);
        rect.set(61, 219, 191, 233);
        canvas.drawOval(rect, paint);

        roundRect(canvas, 57, 19, 177, 224, 22, PHONE);
        outlineRoundRect(canvas, 57, 19, 177, 224, 22, MUTED, 0.8f);
        roundRect(canvas, 63, 24, 171, 218, 18, GLASS);
        roundRect(canvas, 69, 31, 165, 211, 14, Color.rgb(232, 238, 234));
        fill(INK, 150);
        canvas.drawCircle(118, 34, 3, paint);
        fill(WHITE, 105);
        rect.set(76, 60, 138, 181);
        canvas.drawRoundRect(rect, 10, 10, paint);
        fill(MUTED, 26);
        rect.set(87, 80, 119, 121);
        canvas.drawRoundRect(rect, 5, 5, paint);
        path.reset();
        path.moveTo(102, 121);
        path.lineTo(102, 153);
        path.lineTo(126, 153);
        path.moveTo(119, 97);
        path.lineTo(145, 97);
        path.moveTo(87, 98);
        path.lineTo(77, 98);
        path.lineTo(77, 170);
        path.lineTo(89, 170);
        stroke(MUTED, 1, 70);
        canvas.drawPath(path, paint);

        fill(ACCENT, 24);
        rect.set(142, 78, 166, 159);
        canvas.drawRoundRect(rect, 6, 6, paint);
        dashedLine(canvas, 158, 88, 158, 149);
        path.reset();
        path.moveTo(158, 88);
        // A quadratic control point twice the displacement gives a 1.8-unit midpoint.
        path.quadTo(158 - move * 2, 118.5f, 158, 149);
        stroke(ACCENT, 3.4f, 255);
        canvas.drawPath(path, paint);
        roundRect(canvas, 153, 84, 162, 89, 1.5f, MUTED);
        roundRect(canvas, 153, 149, 162, 154, 1.5f, MUTED);
        roundRect(canvas, 173 - move, 88, 178 - move, 149, 2.5f, MUTED);

        polyline(canvas, MUTED, 0.8f, 178, 87, 211, 50, 257, 50);
        label(canvas, "侧边按钮", 214, 40, 12.5f, INK, false, Paint.Align.LEFT);

        int fingerSave = canvas.save();
        canvas.translate(-move, 0);
        path.reset();
        path.moveTo(313, 104);
        path.lineTo(209, 104);
        path.cubicTo(195, 104, 183, 109, 181, 119);
        path.cubicTo(179, 129, 192, 137, 210, 137);
        path.lineTo(313, 137);
        path.close();
        fill(SKIN, 255);
        canvas.drawPath(path, paint);
        stroke(SKIN_LINE, 0.85f, 255);
        canvas.drawPath(path, paint);
        path.reset();
        path.moveTo(201, 111);
        path.cubicTo(190, 112, 188, 124, 198, 129);
        path.moveTo(220, 109);
        path.lineTo(220, 132);
        stroke(SKIN_LINE, 0.7f, 165);
        canvas.drawPath(path, paint);
        canvas.restoreToCount(fingerSave);

        arrow(canvas, 217 + force * 0.11f, 82, 190, 82, 2.0f);
        label(canvas, "按压力", 250, 73, 12.5f, MUTED, false, Paint.Align.CENTER);
        fill(ACCENT, 255);
        canvas.drawCircle(157, 148, 3, paint);
        polyline(canvas, ACCENT, 0.9f, 160, 150, 200, 186, 299, 186);
        label(canvas, "按钮下方力传感器", 200, 177, 12.5f, INK, false,
                Paint.Align.LEFT);
        label(canvas, "向下查看形变特写", 200, 205, 12.5f, MUTED, false,
                Paint.Align.LEFT);
        fill(MUTED, 90);
        rect.set(103, 206, 132, 209);
        canvas.drawRoundRect(rect, 1.5f, 1.5f, paint);
    }

    private void drawDetail(Canvas canvas) {
        label(canvas, "从侧键，到传感器", 16, 25, 12, INK, true, Paint.Align.LEFT);
        label(canvas, "同步示意", 328, 25, 11, MUTED, false, Paint.Align.RIGHT);
        float move = force / 100.0f * 1.8f;
        roundRect(canvas, 22, 45, 85, 178, 13, PHONE);
        outlineRoundRect(canvas, 22, 45, 85, 178, 13, MUTED, 0.7f);
        roundRect(canvas, 26, 49, 81, 173, 10, GLASS);
        fill(MUTED, 220);
        canvas.drawCircle(53, 56, 2, paint);
        fill(ACCENT, 28);
        rect.set(66, 85, 79, 132);
        canvas.drawRoundRect(rect, 3, 3, paint);
        dashedLine(canvas, 73, 89, 73, 128);
        path.reset();
        path.moveTo(73, 89);
        path.quadTo(73 - move * 2, 108.5f, 73, 128);
        stroke(ACCENT, 2.6f, 255);
        canvas.drawPath(path, paint);
        roundRect(canvas, 84 - move, 89, 88 - move, 128, 2, MUTED);

        // A small fingertip preserves the right-to-left force path in detail mode.
        path.reset();
        path.moveTo(122 - move, 99);
        path.lineTo(103 - move, 99);
        path.cubicTo(96 - move, 99, 89 - move, 103, 89 - move, 109);
        path.cubicTo(89 - move, 116, 97 - move, 121, 105 - move, 121);
        path.lineTo(122 - move, 121);
        path.close();
        fill(SKIN, 255);
        canvas.drawPath(path, paint);
        stroke(SKIN_LINE, 0.8f, 255);
        canvas.drawPath(path, paint);
        arrow(canvas, 117, 81, 91, 81, 1.5f);
        roundRect(canvas, 42, 164, 64, 166, 1, MUTED);
        label(canvas, "手机侧键", 54, 192, 11, MUTED, false, Paint.Align.CENTER);

        label(canvas, "按钮向内微移", 137, 75, 17, INK, true, Paint.Align.LEFT);
        label(canvas, "传感器弹性形变", 137, 101, 17, INK, true, Paint.Align.LEFT);
        label(canvas, "受力区域就在按钮下方", 137, 125, 11, MUTED, false,
                Paint.Align.LEFT);
        roundRect(canvas, 137, 140, 319, 167, 7, WHITE);
        label(canvas, "传感器外形 1.8 × 4 mm", 228, 158, 11, INK, false,
                Paint.Align.CENTER);

        roundRect(canvas, 7, 202, 337, 343, 19, WHITE);
        focusIcon(canvas, 24, 222);
        label(canvas, "微形变特写", 37, 225, 12, INK, true, Paint.Align.LEFT);
        label(canvas, "侧视 · 放大示意", 322, 225, 11, MUTED, false,
                Paint.Align.RIGHT);
        drawBeam(canvas, 284, 305, 315, 335, true);
    }

    private void drawBeam(Canvas canvas, float base, float floor, float dimension,
            float legend, boolean detail) {
        float displacement = force / 100.0f * 13.0f;
        float left = 44;
        float right = 300;
        float center = 172;
        float thickness = detail ? 6 : 5;

        line(canvas, 26, floor, 318, floor, LINE, 1);
        roundRect(canvas, left - 8, base + thickness, left + 9, floor - 1, 2, PHONE);
        roundRect(canvas, right - 9, base + thickness, right + 8, floor - 1, 2, PHONE);
        dashedLine(canvas, left, base + thickness / 2, right, base + thickness / 2);
        path.reset();
        path.moveTo(left, base);
        path.cubicTo(left + 48, base, center - 46, base + displacement,
                center, base + displacement);
        path.cubicTo(center + 46, base + displacement, right - 48, base, right, base);
        path.lineTo(right, base + thickness);
        path.cubicTo(right - 48, base + thickness, center + 46,
                base + displacement + thickness, center, base + displacement + thickness);
        path.cubicTo(center - 46, base + displacement + thickness, left + 48,
                base + thickness, left, base + thickness);
        path.close();
        fill(ACCENT, 255);
        canvas.drawPath(path, paint);

        float contactTop = base - 30 + displacement;
        roundRect(canvas, center - 28, contactTop, center + 28, contactTop + 11, 3, PHONE);
        roundRect(canvas, center - 5, contactTop + 11, center + 5,
                base + displacement, 2, MUTED);
        arrow(canvas, center, base - 43, center, contactTop - 5, 1.7f);
        label(canvas, "按钮", 104, base - 25, 11, MUTED, false, Paint.Align.CENTER);
        line(canvas, 122, base - 26, center - 29, contactTop + 3, MUTED, 0.8f);

        line(canvas, left, dimension - 3, left, dimension + 3, MUTED, 0.8f);
        line(canvas, right, dimension - 3, right, dimension + 3, MUTED, 0.8f);
        line(canvas, left, dimension, right, dimension, MUTED, 0.8f);
        roundRect(canvas, center - 34, dimension - 9, center + 34,
                dimension + 9, 1, WHITE);
        label(canvas, "长度 4 mm", center, dimension + 4, 11, MUTED, false,
                Paint.Align.CENTER);

        line(canvas, 22, legend - 4, 34, legend - 4, ACCENT, 3);
        label(canvas, "受力后", 40, legend, 11, MUTED, false, Paint.Align.LEFT);
        dashedLine(canvas, 91, legend - 4, 106, legend - 4);
        label(canvas, "未受力", 112, legend, 11, MUTED, false, Paint.Align.LEFT);
        label(canvas, "形变放大示意", 322, legend, 11, MUTED, false, Paint.Align.RIGHT);
    }

    private void dimensionBadge(Canvas canvas, float left, float top, float right,
            float bottom) {
        roundRect(canvas, left, top, right, bottom, 6, BG);
        label(canvas, "外形 1.8 × 4 mm", (left + right) / 2, top + 16, 11, INK,
                false, Paint.Align.CENTER);
    }

    private void focusIcon(Canvas canvas, float x, float y) {
        path.reset();
        path.moveTo(x - 5, y - 2);
        path.lineTo(x - 5, y - 5);
        path.lineTo(x - 2, y - 5);
        path.moveTo(x + 2, y - 5);
        path.lineTo(x + 5, y - 5);
        path.lineTo(x + 5, y - 2);
        path.moveTo(x + 5, y + 2);
        path.lineTo(x + 5, y + 5);
        path.lineTo(x + 2, y + 5);
        path.moveTo(x - 2, y + 5);
        path.lineTo(x - 5, y + 5);
        path.lineTo(x - 5, y + 2);
        stroke(ACCENT, 1.3f, 255);
        canvas.drawPath(path, paint);
    }

    private void arrow(Canvas canvas, float fromX, float fromY, float toX, float toY,
            float width) {
        float angle = (float) Math.atan2(toY - fromY, toX - fromX);
        float length = 6;
        float spread = 0.6f;
        path.reset();
        path.moveTo(fromX, fromY);
        path.lineTo(toX, toY);
        path.moveTo(toX - length * (float) Math.cos(angle - spread),
                toY - length * (float) Math.sin(angle - spread));
        path.lineTo(toX, toY);
        path.lineTo(toX - length * (float) Math.cos(angle + spread),
                toY - length * (float) Math.sin(angle + spread));
        stroke(ACCENT, width, 255);
        canvas.drawPath(path, paint);
    }

    private void polyline(Canvas canvas, int color, float width, float... points) {
        path.reset();
        path.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) {
            path.lineTo(points[i], points[i + 1]);
        }
        stroke(color, width, 255);
        canvas.drawPath(path, paint);
    }

    private void line(Canvas canvas, float x1, float y1, float x2, float y2, int color,
            float width) {
        stroke(color, width, 255);
        canvas.drawLine(x1, y1, x2, y2, paint);
    }

    private void dashedLine(Canvas canvas, float x1, float y1, float x2, float y2) {
        stroke(MUTED, 1.1f, 160);
        paint.setPathEffect(dashed);
        canvas.drawLine(x1, y1, x2, y2, paint);
        paint.setPathEffect(null);
    }

    private void roundRect(Canvas canvas, float left, float top, float right, float bottom,
            float radius, int color) {
        fill(color, 255);
        rect.set(left, top, right, bottom);
        canvas.drawRoundRect(rect, radius, radius, paint);
    }

    private void outlineRoundRect(Canvas canvas, float left, float top, float right,
            float bottom, float radius, int color, float width) {
        stroke(color, width, 255);
        rect.set(left, top, right, bottom);
        canvas.drawRoundRect(rect, radius, radius, paint);
    }

    private void label(Canvas canvas, String text, float x, float baseline, float size,
            int color, boolean bold, Paint.Align align) {
        fill(color, 255);
        // Keep labels at least 11 dp on screen, including the nested phone scale.
        paint.setTextSize(Math.max(size, 11 * density / drawingScale));
        paint.setTypeface(bold ? medium : normal);
        paint.setTextAlign(align);
        canvas.drawText(text, x, baseline, paint);
    }

    private void fill(int color, int alpha) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setAlpha(alpha);
        paint.setPathEffect(null);
    }

    private void stroke(int color, float width, int alpha) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setAlpha(alpha);
        paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setPathEffect(null);
    }

    private void updateAccessibility(boolean forceUpdate) {
        int rounded = Math.round(force);
        boolean waiting = !hasValidForce;
        if (!forceUpdate && rounded == accessiblePercent && waiting == accessibleWaiting
                && detailMode == accessibleDetail) {
            return;
        }
        accessiblePercent = rounded;
        accessibleWaiting = waiting;
        accessibleDetail = detailMode;
        String mode = detailMode ? "形变特写" : "侧键透视";
        String state = waiting ? "等待有效光感数据"
                : String.format(Locale.CHINA, "光感模拟按压力 %d%%", rounded);
        setContentDescription(mode + "，" + state
                + "。手指从右向左按侧键，按钮下方传感器微小弯曲。"
                + "传感器外形 1.8 乘 4 毫米，4 毫米为长度。形变为放大模拟示意。");
    }
}
