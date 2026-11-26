package io.github.popzol.heavenfall.app;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.mh4u.port.HeavenfallScreens;

import io.github.popzol.heavenfall.HomePresenter;
import io.github.popzol.heavenfall.Text;

/**
 * What the launcher's screens share: the player's language, the game's colours and plates (upstream's look, through
 * {@link HeavenfallScreens}), the wine-dark page that stays clear of the system bars, and the texts of
 * {@link Text}. The screens draw what a presenter says and run the command a tap hands back.
 */
abstract class ScreenActivity extends Activity {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LanguageSetting.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
    }

    final String str(Text t) {
        return Texts.resolve(this, t);
    }

    final int dp(int v) {
        return HeavenfallScreens.dp(this, v);
    }

    final TextView text(CharSequence s, int sp, int color, boolean bold) {
        return HeavenfallScreens.text(this, s, sp, color, bold);
    }

    final Button button(CharSequence label, boolean main) {
        Button b = HeavenfallScreens.button(this, String.valueOf(label), main);
        b.setMinHeight(dp(46));
        return b;
    }

    /** Draws a presenter's button: its label and whether it takes a tap. */
    final void apply(Button view, HomePresenter.Button button) {
        view.setText(str(button.label));
        view.setEnabled(button.enabled);
        view.setAlpha(button.enabled ? 1f : 0.5f);
    }

    final LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    final LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, w);
    }

    final LinearLayout.LayoutParams wide(int heightDp, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                heightDp < 0 ? LinearLayout.LayoutParams.WRAP_CONTENT : dp(heightDp));
        lp.topMargin = dp(topDp);
        return lp;
    }

    /** The page: wine-dark, inside the system bars, with a gold title row. */
    final void showPage(View content) {
        LinearLayout whole = column();
        whole.setBackgroundColor(HeavenfallScreens.WINE);
        whole.addView(content, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        whole.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(whole);
    }

    /** A title in gold, centred, as the game folder's page has it. */
    final TextView title(CharSequence s) {
        TextView t = text(s, 20, HeavenfallScreens.GOLD, true);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }
}
