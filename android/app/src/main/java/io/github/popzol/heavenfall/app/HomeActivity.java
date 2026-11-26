package io.github.popzol.heavenfall.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.mh4u.port.HeavenfallScreens;
import com.mh4u.port.R;

import io.github.popzol.heavenfall.HomeModel;
import io.github.popzol.heavenfall.HomePresenter;
import io.github.popzol.heavenfall.JobProgress;
import io.github.popzol.heavenfall.PhoneFacts;
import io.github.popzol.heavenfall.StartChoice;
import io.github.popzol.heavenfall.Text;

/**
 * The app's entry: one state line, one big button (Set up, Install, Update to X, Build, Resume or Play), what blocks
 * it with its advice, Play beside it when the installed game can start, and More. While a job runs the button gives
 * way to the one bar (the step, the time left, Cancel) and the screen stays on. What is shown is
 * {@link HomePresenter}'s; this class reads the phone, draws, and runs the command a tap hands back.
 */
public final class HomeActivity extends ScreenActivity implements UpdateService.Observer {
    private final HomeModel.Input input = new HomeModel.Input();
    private PhoneFacts facts = new PhoneFacts();
    private HomeModel.Output output;
    private HomePresenter.Screen screen;
    private boolean loaded;
    private boolean checked;
    private boolean cancelling;
    private boolean destroyed;
    /** The assistant opens by itself once per process, on a first run (nothing installed, setup not right). */
    private static boolean assistantOffered;
    private String language;
    private boolean resumed;

    private TextView title, versions, state, failureReason, failureAdvice, jobStep, jobTime, jobHint;
    private LinearLayout blocks, notes, jobBox;
    private BarView bar;
    private Button main, play, cancel, more;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        language = LanguageSetting.get(this);
        build();
        render();
    }

    private HomeModel model() {
        return new HomeModel(Config.tuning(this));
    }

    private void build() {
        LinearLayout page = column();
        int pad = dp(14);
        page.setPadding(pad * 2, pad, pad * 2, pad);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        title = title(Config.editions(this).first().name);
        title.setId(R.id.hf_title);
        head.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        versions = text("", 12, HeavenfallScreens.CREAM, false);
        versions.setId(R.id.hf_versions);
        head.addView(versions);
        page.addView(head);

        LinearLayout body = new LinearLayout(this);
        body.setPadding(0, dp(8), 0, 0);

        LinearLayout left = column();
        state = text("", 17, HeavenfallScreens.CREAM, true);
        state.setId(R.id.hf_state);
        left.addView(state);

        failureReason = text("", 15, HeavenfallScreens.ALERT, true);
        failureReason.setId(R.id.hf_failure_reason);
        failureAdvice = text("", 14, HeavenfallScreens.CREAM, false);
        failureAdvice.setId(R.id.hf_failure_advice);
        left.addView(failureReason, wide(-1, 10));
        left.addView(failureAdvice, wide(-1, 2));

        blocks = column();
        blocks.setId(R.id.hf_blocks);
        left.addView(blocks, wide(-1, 8));
        notes = column();
        notes.setId(R.id.hf_notes);
        left.addView(notes, wide(-1, 8));

        jobBox = column();
        jobStep = text("", 15, HeavenfallScreens.GOLD, true);
        jobStep.setId(R.id.hf_job_step);
        jobBox.addView(jobStep);
        bar = new BarView(this);
        bar.setId(R.id.hf_bar);
        jobBox.addView(bar, wide(22, 8));
        jobTime = text("", 14, HeavenfallScreens.CREAM, false);
        jobTime.setId(R.id.hf_job_time);
        jobBox.addView(jobTime, wide(-1, 6));
        jobHint = text("", 13, HeavenfallScreens.CREAM, false);
        jobHint.setId(R.id.hf_job_hint);
        jobBox.addView(jobHint, wide(-1, 8));
        left.addView(jobBox, wide(-1, 12));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(left);
        body.addView(scroll, weight(3));

        LinearLayout right = column();
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setPadding(dp(18), 0, 0, 0);
        main = button("", true);
        main.setId(R.id.hf_main);
        main.setTextSize(18);
        main.setOnClickListener(v -> tap(screen == null || screen.main == null ? HomePresenter.Command.NONE : screen.main.press()));
        main.setMinHeight(dp(64));
        right.addView(main, wide(-1, 0));
        play = button("", false);
        play.setId(R.id.hf_play);
        play.setOnClickListener(v -> tap(screen == null || screen.play == null ? HomePresenter.Command.NONE : screen.play.press()));
        right.addView(play, wide(-1, 8));
        cancel = button("", false);
        cancel.setId(R.id.hf_cancel);
        cancel.setOnClickListener(v -> tap(screen == null || screen.cancel == null ? HomePresenter.Command.NONE : screen.cancel.press()));
        right.addView(cancel, wide(-1, 8));
        more = button("", false);
        more.setId(R.id.hf_more);
        more.setOnClickListener(v -> tap(HomePresenter.Command.OPEN_MORE));
        right.addView(more, wide(-1, 8));
        body.addView(right, weight(2));

        page.addView(body, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        showPage(page);
        main.requestFocus();   // a pad: A presses the big button
    }

    // ---- reading the phone

    @Override
    protected void onStart() {
        super.onStart();
        UpdateService.observe(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!language.equals(LanguageSetting.get(this))) {
            recreate();   // the language was changed in More
            return;
        }
        resumed = true;
        refresh();
        if (!checked) {
            checked = true;
            if (UpdateChecker.check(this, input, this::recompute)) recompute();
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override
    protected void onStop() {
        UpdateService.unobserve(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
    }

    private void refresh() {
        PhoneReader.readAsync(this, f -> {
            if (destroyed) return;
            facts = f;
            loaded = true;
            recompute();
        });
    }

    private void recompute() {
        if (!loaded) return;
        facts.applyTo(input);
        if (UpdateService.running()) input.job = HomeModel.Job.RUNNING;
        output = model().compute(input);
        offerAssistant();
        render();
    }

    @Override
    public void onProgress(JobProgress p) {
        if (p.phase == JobProgress.Phase.DONE || p.phase == JobProgress.Phase.FAILED
                || p.phase == JobProgress.Phase.CANCELLED) {
            cancelling = false;
            refresh();
        } else {
            recompute();
        }
    }

    private void offerAssistant() {
        if (!resumed || assistantOffered || output == null || output.action != HomeModel.Action.SET_UP || input.installed != null) return;
        assistantOffered = true;
        startActivity(new Intent(this, AssistantActivity.class));
    }

    // ---- drawing

    private void render() {
        boolean known = output != null;
        screen = known ? HomePresenter.present(output, input, UpdateService.progress(), SelfUpdate.canInstall(this), cancelling)
                : null;
        if (screen == null) {
            state.setText(str(new Text("hf_state_checking")));
            versions.setText("");
            failureReason.setVisibility(View.GONE);
            failureAdvice.setVisibility(View.GONE);
            blocks.removeAllViews();
            notes.removeAllViews();
            jobBox.setVisibility(View.GONE);
            for (Button b : new Button[] {main, play, cancel}) b.setVisibility(View.GONE);
            more.setText(str(new Text("hf_btn_more")));
            more.setEnabled(false);
            return;
        }
        versions.setText(str(screen.versions));
        state.setText(str(screen.state));
        if (screen.failure != null) {
            failureReason.setText(str(screen.failure.reason));
            failureAdvice.setText(str(screen.failure.advice));
            failureReason.setVisibility(View.VISIBLE);
            failureAdvice.setVisibility(View.VISIBLE);
        } else {
            failureReason.setVisibility(View.GONE);
            failureAdvice.setVisibility(View.GONE);
        }
        blocks.removeAllViews();
        for (HomeModel.Block b : screen.blocks) {
            // the first thing to do on Set up is also the state line: only its advice is added
            if (!b.reason.equals(screen.state)) {
                TextView reason = text(str(b.reason), 15, HeavenfallScreens.ALERT, true);
                blocks.addView(reason, wide(-1, 4));
            }
            blocks.addView(text(str(b.advice), 14, HeavenfallScreens.CREAM, false), wide(-1, 2));
        }
        notes.removeAllViews();
        for (Text n : screen.notes) notes.addView(text(str(n), 13, HeavenfallScreens.CREAM, false), wide(-1, 2));

        if (screen.bar != null) {
            jobBox.setVisibility(View.VISIBLE);
            jobStep.setText(str(screen.bar.step));
            bar.setPermille(screen.bar.permille);
            StringBuilder line = new StringBuilder();
            if (screen.bar.counter != null) line.append(str(screen.bar.counter));
            if (screen.bar.timeLeft != null) line.append(line.length() > 0 ? "  ·  " : "").append(str(screen.bar.timeLeft));
            jobTime.setText(line);
            jobHint.setText(screen.hint == null ? "" : str(screen.hint));
        } else {
            jobBox.setVisibility(View.GONE);
        }
        show(main, screen.main);
        show(play, screen.play);
        show(cancel, screen.cancel);
        more.setVisibility(View.VISIBLE);
        apply(more, screen.more);
        if (screen.keepScreenOn) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void show(Button view, HomePresenter.Button button) {
        if (button == null) {
            view.setVisibility(View.GONE);
            return;
        }
        view.setVisibility(View.VISIBLE);
        apply(view, button);
    }

    // ---- what a tap does

    private void tap(HomePresenter.Command command) {
        switch (command) {
            case OPEN_ASSISTANT:
                startActivity(new Intent(this, AssistantActivity.class));
                break;
            case ASK_ALLOW_INSTALLS:
                Actions.askAllowInstalls(this);
                break;
            case START_JOB:
                if (output != null && Actions.start(this, facts, StartChoice.forAction(output.action, input))) recompute();
                break;
            case RESUME_JOB:
                if (UpdateService.resume(this)) recompute();
                else refresh();
                break;
            case PLAY:
                Actions.play(this, facts);
                break;
            case OPEN_MORE:
                startActivity(new Intent(this, MoreActivity.class));
                break;
            case CANCEL_JOB:
                cancelling = true;
                UpdateService.cancel(this);
                render();
                break;
            default:
                break;
        }
    }
}
