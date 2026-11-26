package io.github.popzol.heavenfall.app;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.mh4u.port.HeavenfallScreens;
import com.mh4u.port.R;

import io.github.popzol.heavenfall.AssistantPresenter;
import io.github.popzol.heavenfall.HomeModel;
import io.github.popzol.heavenfall.ScreenTexts;
import io.github.popzol.heavenfall.Text;

/**
 * The first-run assistant: the checklist on the left (the permission, the folder, the American game, its 1.3.0 update,
 * MH XX), the step that is not right on the right with what to do about it, and its button. It reads the phone again
 * every time it comes to the front (after Android's all-files page) and on Check again; it ends when every required
 * step is right.
 */
public final class AssistantActivity extends ScreenActivity {
    private AssistantPresenter.Screen screen;
    private LinearLayout steps, detail;
    private Button action, again, leave;
    private TextView title;
    private boolean destroyed;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        build();
    }

    private void build() {
        LinearLayout page = column();
        int pad = dp(14);
        page.setPadding(pad * 2, pad, pad * 2, pad);
        title = title("");
        title.setId(R.id.hf_title);
        page.addView(title);

        LinearLayout body = new LinearLayout(this);
        body.setPadding(0, dp(8), 0, dp(8));
        LinearLayout list = column();
        list.setBackground(HeavenfallScreens.plate(this, HeavenfallScreens.PANEL, 6));
        list.setPadding(pad, dp(10), pad, dp(10));
        steps = column();
        steps.setId(R.id.hf_steps);
        list.addView(steps);
        body.addView(list, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 4));

        detail = column();
        detail.setPadding(dp(18), 0, 0, 0);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(detail);
        body.addView(scroll, weight(6));
        page.addView(body, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        action = button("", true);
        action.setId(R.id.hf_step_action);
        again = button("", false);
        again.setId(R.id.hf_check_again);
        leave = button("", false);
        leave.setId(R.id.hf_leave);
        for (Button b : new Button[] {action, again, leave}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            lp.setMargins(dp(5), 0, dp(5), 0);
            row.addView(b, lp);
        }
        action.setOnClickListener(v -> doStep());
        again.setOnClickListener(v -> refresh());
        leave.setOnClickListener(v -> finish());
        page.addView(row);
        showPage(page);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        refresh();
    }

    private void refresh() {
        Snapshot.load(this, s -> {
            if (destroyed) return;
            screen = AssistantPresenter.present(s.output);
            render();
        });
    }

    private void render() {
        if (screen == null) {
            title.setText(str(new Text("hf_assistant_title")));
            action.setVisibility(View.GONE);
            again.setVisibility(View.GONE);
            leave.setText(str(new Text("hf_btn_later")));
            return;
        }
        title.setText(str(screen.title));
        steps.removeAllViews();
        for (AssistantPresenter.Step s : screen.steps) {
            String mark = s.status == HomeModel.CheckStatus.OK ? "✓  " : s.required ? "✗  " : "○  ";
            int color = s.status == HomeModel.CheckStatus.OK ? HeavenfallScreens.GOLD
                    : s.required ? HeavenfallScreens.ALERT : HeavenfallScreens.CREAM;
            TextView line = text(mark + str(s.label), 15, color, s == screen.current);
            steps.addView(line, wide(-1, 4));
        }
        detail.removeAllViews();
        AssistantPresenter.Step current = screen.current;
        if (screen.done) detail.addView(text(str(ScreenTexts.ASSISTANT_DONE_TEXT), 15, HeavenfallScreens.CREAM, false), wide(-1, 0));
        if (current != null) {
            TextView head = text(str(current.label), 17, HeavenfallScreens.GOLD, true);
            head.setId(R.id.hf_step_title);
            detail.addView(head, wide(-1, screen.done ? 14 : 0));
            if (current.problem != null) {
                TextView reason = text(str(current.problem.reason), 15, current.required ? HeavenfallScreens.ALERT
                        : HeavenfallScreens.CREAM, true);
                reason.setId(R.id.hf_step_problem);
                detail.addView(reason, wide(-1, 6));
                TextView advice = text(str(current.problem.advice), 14, HeavenfallScreens.CREAM, false);
                advice.setId(R.id.hf_step_advice);
                detail.addView(advice, wide(-1, 2));
            }
            StringBuilder help = new StringBuilder();
            for (Text h : current.help) help.append(help.length() > 0 ? "\n\n" : "").append(str(h));
            if (help.length() > 0) {
                TextView helpView = text(help, 14, HeavenfallScreens.CREAM, false);
                helpView.setId(R.id.hf_step_help);
                helpView.setLineSpacing(dp(2), 1);
                detail.addView(helpView, wide(-1, 10));
            }
        }
        boolean hasAction = current != null && current.action != AssistantPresenter.Do.NONE;
        action.setVisibility(hasAction ? View.VISIBLE : View.GONE);
        if (hasAction) action.setText(str(current.actionLabel));
        again.setVisibility(screen.done ? View.GONE : View.VISIBLE);
        again.setText(str(screen.checkAgainLabel));
        leave.setText(str(screen.leaveLabel));
        (hasAction ? action : screen.done ? leave : again).requestFocus();
    }

    private void doStep() {
        if (screen == null || screen.current == null) return;
        switch (screen.current.action) {
            case GIVE_ACCESS:
                HeavenfallScreens.requestAccess(this);
                break;
            case CHOOSE_FOLDER:
                Actions.chooseFolder(this, this::refresh);
                break;
            default:
                break;
        }
    }
}
