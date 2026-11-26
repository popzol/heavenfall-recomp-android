package io.github.popzol.heavenfall.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.mh4u.port.HeavenfallScreens;
import com.mh4u.port.R;

import java.util.List;

import io.github.popzol.heavenfall.HomeModel;
import io.github.popzol.heavenfall.HomePresenter;
import io.github.popzol.heavenfall.Languages;
import io.github.popzol.heavenfall.MorePresenter;
import io.github.popzol.heavenfall.ScreenTexts;
import io.github.popzol.heavenfall.StartChoice;

/**
 * More: the game folder, MH XX, Reinstall, Build again, the language, Credits and licences and Share the log. Only the
 * entries the home model enables take a tap; the others are drawn dim with their reason. Reinstall and Build again ask
 * first and then start the update job, leaving the screen for Home's bar.
 */
public final class MoreActivity extends ScreenActivity {
    private Snapshot snapshot;
    private LinearLayout left, right;
    private boolean destroyed;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout page = column();
        int pad = dp(14);
        page.setPadding(pad * 2, pad, pad * 2, pad);
        TextView title = title(str(ScreenTexts.MORE_TITLE));
        title.setId(R.id.hf_title);
        page.addView(title);

        LinearLayout columns = new LinearLayout(this);
        columns.setId(R.id.hf_more_list);
        left = column();
        right = column();
        right.setPadding(dp(14), 0, 0, 0);
        left.setPadding(0, 0, dp(14), 0);
        columns.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        columns.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(columns);
        page.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        Button back = button(str(ScreenTexts.BACK), false);
        back.setId(R.id.hf_back);
        back.setOnClickListener(v -> finish());
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.addView(back, new LinearLayout.LayoutParams(dp(160), dp(48)));
        row.setPadding(0, dp(8), 0, 0);
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
            snapshot = s;
            render();
        });
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
    }

    private void render() {
        left.removeAllViews();
        right.removeAllViews();
        List<MorePresenter.Row> rows = MorePresenter.rows(snapshot.output);
        int half = (rows.size() + 1) / 2;
        for (int i = 0; i < rows.size(); i++) {
            MorePresenter.Row row = rows.get(i);
            LinearLayout column = i < half ? left : right;
            String label = str(row.label);
            if (row.entry == HomeModel.More.LANGUAGE) {
                label += ": " + str(Languages.label(LanguageSetting.get(this)));
            }
            Button b = button(label, false);
            b.setEnabled(row.enabled);
            b.setAlpha(row.enabled ? 1f : 0.5f);
            b.setOnClickListener(v -> run(row.press()));
            column.addView(b, wide(-1, 8));
            if (row.unavailable != null) {
                column.addView(text(str(row.unavailable), 12, HeavenfallScreens.CREAM, false), wide(-1, 2));
            }
        }
    }

    private void run(MorePresenter.Command command) {
        switch (command) {
            case CHOOSE_FOLDER:
                Actions.chooseFolder(this, this::refresh);
                break;
            case OPEN_XX:
                startActivity(new Intent(this, XxActivity.class));
                break;
            case CONFIRM_REINSTALL:
            case CONFIRM_REBUILD:
                confirm(command);
                break;
            case CHOOSE_LANGUAGE:
                LanguageSetting.set(this, LanguageSetting.next(LanguageSetting.get(this)));
                recreate();
                break;
            case OPEN_CREDITS:
                startActivity(HeavenfallScreens.licensesIntent(this));
                break;
            case SHARE_LOG:
                startActivity(HeavenfallScreens.logShareIntent(this));
                break;
            default:
                break;
        }
    }

    private void confirm(MorePresenter.Command command) {
        HeavenfallScreens.notice(this, str(MorePresenter.confirmTitle(command)), str(MorePresenter.confirmText(command)),
                new HeavenfallScreens.Choice(str(ScreenTexts.CONTINUE), true, () -> start(command)),
                new HeavenfallScreens.Choice(str(ScreenTexts.CANCEL), false, null));
    }

    private void start(MorePresenter.Command command) {
        if (snapshot == null) return;
        if (HomePresenter.asksToAllowInstalls(snapshot.input, SelfUpdate.canInstall(this))) {
            Actions.askAllowInstalls(this);
            return;
        }
        StartChoice choice = command == MorePresenter.Command.CONFIRM_REINSTALL ? StartChoice.reinstall(snapshot.input)
                : StartChoice.rebuild(snapshot.input);
        if (Actions.start(this, snapshot.facts, choice)) finish();
    }
}
