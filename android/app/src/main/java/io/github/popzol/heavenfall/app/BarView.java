package io.github.popzol.heavenfall.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import com.mh4u.port.HeavenfallScreens;

/** The one bar of a running job, in the game's colours: a bronze plate with a gold fill. */
final class BarView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int permille;

    BarView(Context context) {
        super(context);
    }

    void setPermille(int value) {
        permille = Math.max(0, Math.min(1000, value));
        setContentDescription(String.valueOf(permille / 10) + "%");
        invalidate();
    }

    int permille() {
        return permille;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float radius = HeavenfallScreens.dp(getContext(), 6);
        float edge = HeavenfallScreens.dp(getContext(), 2);
        rect.set(edge / 2, edge / 2, getWidth() - edge / 2, getHeight() - edge / 2);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(HeavenfallScreens.BRONZE);
        canvas.drawRoundRect(rect, radius, radius, paint);
        float fill = (getWidth() - edge * 2) * permille / 1000f;
        if (fill > 0) {
            rect.set(edge, edge, edge + fill, getHeight() - edge);
            paint.setColor(HeavenfallScreens.GOLD);
            canvas.drawRoundRect(rect, radius / 2, radius / 2, paint);
        }
        rect.set(edge / 2, edge / 2, getWidth() - edge / 2, getHeight() - edge / 2);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(edge);
        paint.setColor(HeavenfallScreens.EDGE);
        canvas.drawRoundRect(rect, radius, radius, paint);
    }
}
