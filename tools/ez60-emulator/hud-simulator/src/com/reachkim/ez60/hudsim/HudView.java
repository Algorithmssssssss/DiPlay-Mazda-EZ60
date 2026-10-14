package com.reachkim.ez60.hudsim;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import java.util.Locale;

/**
 * An approximation of the EZ-60 windshield HUD on its 800x480 display. The car draws the arrow, distance, road,
 * progress and time left itself from the navigation calls; this view draws its own version of them, not the car's
 * graphics. DiPlay's area, the only part of the display the car projects, stays clear while an app holds
 * navigation focus, so DiPlay's own window (MazdaHudScreen) shows through it.
 */
final class HudView extends View {
    /** MazdaHud.VISIBLE_LEFT, _TOP, _RIGHT and _BOTTOM. */
    static final Rect AREA = new Rect(573, 167, 800, 355);
    private static final int CYAN = Color.rgb(64, 200, 255);
    private static final int DIM = Color.rgb(120, 130, 140);
    private static final int TRACK = Color.rgb(40, 48, 58);
    private static final int GREEN = Color.rgb(80, 220, 120);
    private static final float TEXT_LEFT = 210;
    private static final float TEXT_RIGHT = 555;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint distance = text(64, Color.WHITE, true);
    private final TextPaint road = text(26, Color.WHITE, false);
    private final TextPaint info = text(24, Color.WHITE, false);
    private final TextPaint symbol = text(30, CYAN, true);
    private final TextPaint note = text(15, DIM, false);
    private HudState state;

    HudView(Context context) {
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(CYAN);
        fill.setColor(CYAN);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(1.5f);
        outline.setColor(DIM);
        outline.setPathEffect(new DashPathEffect(new float[] {8, 6}, 0));
        symbol.setTextAlign(Paint.Align.CENTER);
    }

    private static TextPaint text(float size, int color, boolean bold) {
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(size);
        paint.setColor(color);
        if (bold) paint.setTypeface(Typeface.DEFAULT_BOLD);
        return paint;
    }

    void setState(HudState state) {
        this.state = state;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        HudState current = state;
        // Black is not projected. The car projects DiPlay's area only while an app holds navigation focus.
        boolean projected = current != null && current.navigating() && current.appHoldsFocus();
        canvas.save();
        if (projected) canvas.clipOutRect(AREA);
        canvas.drawColor(Color.BLACK);
        canvas.restore();
        if (!projected) {
            canvas.drawRect(AREA, outline);
            canvas.drawText("DiPlay's area", AREA.left + 12, AREA.centerY() - 4, note);
            canvas.drawText("(not projected now)", AREA.left + 12, AREA.centerY() + 16, note);
        }
        canvas.drawText("Simulated HUD: an approximation, not the car's own graphics", 24, 34, note);
        if (current == null) {
            canvas.drawText("Waiting for the simulated car service.", 24, 230, info);
            canvas.drawText("Run: ez60-emu hud start", 24, 262, note);
            return;
        }
        if (HudState.FOCUS_CAR.equals(current.focus)) {
            canvas.drawText("The car's own navigation has the HUD.", 24, 230, info);
            canvas.drawText("DiPlay should stop sending until it is given back (ez60-emu hud car-nav off).", 24, 262, note);
        } else if (!current.navigating()) {
            canvas.drawText("No navigation on the HUD", 24, 240, note);
        } else {
            drawGuidance(canvas, current);
        }
        String footer = "turnId " + current.turnId + " · status " + current.status + " · focus "
                + (current.focus.isEmpty() ? "none" : current.focus) + " · " + current.lastCall;
        canvas.drawText(TextUtils.ellipsize(footer, note, 752, TextUtils.TruncateAt.END).toString(), 24, 458, note);
    }

    private void drawGuidance(Canvas canvas, HudState current) {
        drawManeuver(canvas, current.turnId, 40, 150, 150);
        canvas.drawText(formatDistance(current.distanceMeters), TEXT_LEFT, 222, distance);
        String nextRoad = current.nextRoad.isEmpty() ? current.currentRoad : current.nextRoad;
        if (!nextRoad.isEmpty()) {
            canvas.drawText(TextUtils.ellipsize(nextRoad, road, TEXT_RIGHT - TEXT_LEFT, TextUtils.TruncateAt.END).toString(),
                    TEXT_LEFT, 264, road);
        }
        if (current.percent >= 0) {
            float width = TEXT_RIGHT - TEXT_LEFT;
            fill.setColor(TRACK);
            canvas.drawRoundRect(new RectF(TEXT_LEFT, 286, TEXT_RIGHT, 296), 5, 5, fill);
            fill.setColor(GREEN);
            canvas.drawRoundRect(new RectF(TEXT_LEFT, 286, TEXT_LEFT + width * Math.min(100, current.percent) / 100f, 296),
                    5, 5, fill);
            fill.setColor(CYAN);
        }
        canvas.drawText(formatDistance(current.remainingMeters) + "  ·  "
                + (current.remainingSeconds + 59) / 60 + " min left", TEXT_LEFT, 334, info);
    }

    private static String formatDistance(int meters) {
        if (meters < 1000) return meters + " m";
        return String.format(Locale.US, "%.1f km", meters / 1000f);
    }

    /** The car's turn numbers (WT_TinnoveNavigation, DashboardConverter) as a symbol in a size x size box. */
    private void drawManeuver(Canvas canvas, int turnId, float left, float top, float size) {
        float cx = left + size / 2;
        float bottom = top + size - 6;
        float mid = top + size * 0.5f;
        stroke.setStrokeWidth(size * 0.09f);
        if (turnId == 16) {
            arrow(canvas, new float[] {cx, bottom, cx, top + size * 0.22f});
        } else if (turnId == 19 || turnId == 20) {
            float side = turnId == 19 ? -1 : 1;
            arrow(canvas, new float[] {cx, bottom, cx, mid, cx + side * size * 0.3f, mid});
        } else if (turnId == 18 || turnId == 17) {
            float side = turnId == 18 ? -1 : 1;
            arrow(canvas, new float[] {cx, bottom, cx, mid + size * 0.12f, cx + side * size * 0.26f, top + size * 0.22f});
        } else if (turnId == 21 || turnId == 22) {
            float side = turnId == 21 ? -1 : 1;
            arrow(canvas, new float[] {cx, bottom, cx, top + size * 0.25f, cx + side * size * 0.3f, mid + size * 0.05f});
        } else if (turnId == 23 || turnId == 24) {
            uTurn(canvas, turnId == 23 ? -1 : 1, cx, top, bottom, size);
        } else if (turnId == 53 || turnId == 63 || (turnId >= 42 && turnId <= 51)) {
            roundabout(canvas, true, turnId >= 42 && turnId <= 51 ? turnId - 41 : 0, turnId == 63, cx, top, bottom, size);
        } else if (turnId == 70 || turnId == 68 || (turnId >= 74 && turnId <= 83)) {
            roundabout(canvas, false, turnId >= 74 && turnId <= 83 ? turnId - 73 : 0, turnId == 68, cx, top, bottom, size);
        } else if (turnId == 3) {
            flag(canvas, cx, top, bottom, size);
        } else if (turnId == 54 || turnId == 13 || turnId == 11 || turnId == 15) {
            String label = turnId == 54 ? "Via" : turnId == 13 ? "Tunnel" : turnId == 11 ? "Toll" : "Service";
            canvas.drawText(label, cx, mid + 10, symbol);
        } else {
            // The car draws no arrow for an unknown number.
            canvas.drawText("no arrow", left + 4, mid, note);
            canvas.drawText("(turnId " + turnId + ")", left + 4, mid + 20, note);
        }
    }

    /** A polyline through {@code points} (x, y pairs) with an arrowhead at its end. */
    private void arrow(Canvas canvas, float[] points) {
        int n = points.length;
        float endX = points[n - 2];
        float endY = points[n - 1];
        float dx = endX - points[n - 4];
        float dy = endY - points[n - 3];
        float length = (float) Math.hypot(dx, dy);
        float ux = dx / length;
        float uy = dy / length;
        float headLength = stroke.getStrokeWidth() * 2.2f;
        Path line = new Path();
        line.moveTo(points[0], points[1]);
        for (int i = 2; i < n - 2; i += 2) line.lineTo(points[i], points[i + 1]);
        line.lineTo(endX - ux * headLength * 0.6f, endY - uy * headLength * 0.6f);
        canvas.drawPath(line, stroke);
        head(canvas, endX, endY, ux, uy);
    }

    private void head(Canvas canvas, float tipX, float tipY, float ux, float uy) {
        float length = stroke.getStrokeWidth() * 2.2f;
        float half = stroke.getStrokeWidth() * 1.6f;
        float baseX = tipX - ux * length;
        float baseY = tipY - uy * length;
        Path head = new Path();
        head.moveTo(tipX, tipY);
        head.lineTo(baseX - uy * half, baseY + ux * half);
        head.lineTo(baseX + uy * half, baseY - ux * half);
        head.close();
        canvas.drawPath(head, fill);
    }

    private void uTurn(Canvas canvas, float side, float cx, float top, float bottom, float size) {
        float radius = size * 0.17f;
        float start = cx - side * radius;
        float end = cx + side * radius;
        float arcTop = top + size * 0.2f;
        Path path = new Path();
        path.moveTo(start, bottom);
        path.lineTo(start, arcTop + radius);
        // Over the top: a left U-turn goes from the right side (0 degrees) to the left, a right one the other way.
        RectF oval = new RectF(cx - radius, arcTop, cx + radius, arcTop + 2 * radius);
        if (side < 0) path.arcTo(oval, 0, -180, false);
        else path.arcTo(oval, 180, 180, false);
        path.lineTo(end, top + size * 0.62f);
        canvas.drawPath(path, stroke);
        head(canvas, end, top + size * 0.72f, 0, 1);
    }

    /**
     * Counter-clockwise roundabouts (right-hand traffic) take exit 1 on the right; clockwise ones on the left.
     * {@code exit} 0 means entering without a known exit.
     */
    private void roundabout(Canvas canvas, boolean counterClockwise, int exit, boolean leaving,
                            float cx, float top, float bottom, float size) {
        float radius = size * 0.2f;
        float cy = top + size * 0.45f;
        float width = stroke.getStrokeWidth();
        stroke.setStrokeWidth(width * 0.7f);
        canvas.drawCircle(cx, cy, radius, stroke);
        canvas.drawLine(cx, bottom, cx, cy + radius, stroke);
        stroke.setStrokeWidth(width);
        // Exit direction in degrees from straight ahead, clockwise positive.
        double angle = exit == 1 ? 90 : exit == 2 || exit == 0 || leaving ? 0 : exit == 3 ? -90 : -150;
        if (!counterClockwise) angle = -angle;
        double radians = Math.toRadians(angle);
        float ux = (float) Math.sin(radians);
        float uy = (float) -Math.cos(radians);
        float startX = cx + ux * radius;
        float startY = cy + uy * radius;
        arrow(canvas, new float[] {startX, startY, startX + ux * size * 0.28f, startY + uy * size * 0.28f});
        if (exit > 0) canvas.drawText(String.valueOf(exit), cx, cy + 11, symbol);
    }

    private void flag(Canvas canvas, float cx, float top, float bottom, float size) {
        float pole = cx - size * 0.15f;
        canvas.drawLine(pole, bottom, pole, top + size * 0.12f, stroke);
        Path banner = new Path();
        banner.moveTo(pole, top + size * 0.12f);
        banner.lineTo(pole + size * 0.4f, top + size * 0.24f);
        banner.lineTo(pole, top + size * 0.36f);
        banner.close();
        canvas.drawPath(banner, fill);
    }
}
