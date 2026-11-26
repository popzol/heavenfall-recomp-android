package io.github.popzol.heavenfall.app;

import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.mh4u.port.HeavenfallScreens;
import com.mh4u.port.R;

import io.github.popzol.heavenfall.HomeModel;
import io.github.popzol.heavenfall.MorePresenter;
import io.github.popzol.heavenfall.ScreenTexts;
import io.github.popzol.heavenfall.Text;

/** More > MH XX: what the player's own copy of Monster Hunter XX adds, where to copy it, and how it stands now. */
public final class XxActivity extends ScreenActivity {
    private LinearLayout body;
    private boolean destroyed;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout page = column();
        int pad = dp(14);
        page.setPadding(pad * 2, pad, pad * 2, pad);
        TextView title = title(str(new Text(HomeModel.More.MH_XX.labelKey())));
        title.setId(R.id.hf_title);
        page.addView(title);
        body = column();
        ScrollView scroll = new ScrollView(this);
        scroll.setPadding(0, dp(8), 0, 0);
        scroll.addView(body);
        page.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(8), 0, 0);
        Button other = button(str(new Text("hf_btn_other_folder")), false);
        other.setOnClickListener(v -> Actions.chooseFolder(this, this::refresh));
        Button again = button(str(new Text("hf_btn_check_again")), true);
        again.setId(R.id.hf_check_again);
        again.setOnClickListener(v -> refresh());
        Button back = button(str(ScreenTexts.BACK), false);
        back.setId(R.id.hf_back);
        back.setOnClickListener(v -> finish());
        for (Button b : new Button[] {other, again, back}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            lp.setMargins(dp(5), 0, dp(5), 0);
            row.addView(b, lp);
        }
        page.addView(row);
        showPage(page);
        back.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        Snapshot.load(this, s -> {
            if (destroyed) return;
            MorePresenter.XxPage page = MorePresenter.xxPage(s.output, s.input);
            body.removeAllViews();
            TextView what = text(str(page.what), 15, HeavenfallScreens.CREAM, false);
            what.setId(R.id.hf_xx_what);
            body.addView(what, wide(-1, 0));
            TextView where = text(str(page.where), 15, HeavenfallScreens.CREAM, false);
            where.setId(R.id.hf_xx_where);
            body.addView(where, wide(-1, 10));
            TextView state = text(str(page.state), 16, HeavenfallScreens.GOLD, true);
            state.setId(R.id.hf_xx_state);
            body.addView(state, wide(-1, 14));
            if (page.advice != null) {
                TextView advice = text(str(page.advice), 14, HeavenfallScreens.CREAM, false);
                advice.setId(R.id.hf_xx_advice);
                body.addView(advice, wide(-1, 2));
            }
        });
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
    }
}
