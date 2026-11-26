package io.github.popzol.heavenfall;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the home screen shows and what each tap does, from the model's output and the job's snapshot: which views
 * are there, their texts, whether each takes a tap. The Android class only draws this and runs the command a button
 * hands back; nothing here touches the phone.
 */
public final class HomePresenter {
    /** What a tap on a button makes the screen do. */
    public enum Command {
        NONE, OPEN_ASSISTANT, ASK_ALLOW_INSTALLS, START_JOB, RESUME_JOB, PLAY, OPEN_MORE, CANCEL_JOB,
        /** Asks first, then deletes what the half-done job left ({@link JobState#discard}). */
        DISCARD_JOB
    }

    public static final class Button {
        public final Text label;
        public final boolean enabled;
        private final Command command;

        Button(Text label, boolean enabled, Command command) {
            this.label = label;
            this.enabled = enabled;
            this.command = command;
        }

        /** What a tap does: nothing for a button that is blocked. */
        public Command press() {
            return enabled ? command : Command.NONE;
        }
    }

    /** The one bar of a running job. */
    public static final class Bar {
        /** 0..1000. */
        public final int permille;
        /** The sentence for what is happening now. */
        public final Text step;
        /** "Step n of m"; null while there is no plan. */
        public final Text counter;
        /** The time left; null when not known. */
        public final Text timeLeft;

        Bar(int permille, Text step, Text counter, Text timeLeft) {
            this.permille = permille;
            this.step = step;
            this.counter = counter;
            this.timeLeft = timeLeft;
        }
    }

    public static final class Screen {
        public final Text state;
        /** What stops the big button, or (Set up) the first thing to do, each with its advice. */
        public final List<HomeModel.Block> blocks;
        public final List<Text> notes;
        /** Why the last job stopped and what to do; null when it did not. */
        public final HomeModel.Block failure;
        /** Null unless a job runs. */
        public final Bar bar;
        /** The big button; null while a job runs. */
        public final Button main;
        /** The Play entry beside the big button; null when it is not offered. */
        public final Button play;
        /** Resume's Discard entry; null when it is not offered. */
        public final Button discard;
        public final Button more;
        /** Null unless a job runs. */
        public final Button cancel;
        public final boolean keepScreenOn;
        /** The app's and the mod's versions, as one line. */
        public final Text versions;
        /** A line under the bar while a job runs; null otherwise. */
        public final Text hint;

        Screen(Text state, List<HomeModel.Block> blocks, List<Text> notes, HomeModel.Block failure, Bar bar, Button main,
               Button play, Button discard, Button more, Button cancel, Text versions) {
            this.discard = discard;
            this.versions = versions;
            this.hint = bar != null ? ScreenTexts.JOB_HINT : null;
            this.state = state;
            this.blocks = Collections.unmodifiableList(blocks);
            this.notes = Collections.unmodifiableList(notes);
            this.failure = failure;
            this.bar = bar;
            this.main = main;
            this.play = play;
            this.more = more;
            this.cancel = cancel;
            this.keepScreenOn = bar != null;
        }
    }

    private HomePresenter() {
    }

    /** The latest app release is newer than the running one (the update would replace the app). */
    public static boolean appNewer(HomeModel.Input in) {
        return in.latestApp != null && Version.newer(in.latestApp, in.appVersion) == 1;
    }

    /** The player must allow installs before an update that replaces the app (Android's setting, asked once). */
    public static boolean asksToAllowInstalls(HomeModel.Input in, boolean canInstall) {
        return !canInstall && appNewer(in);
    }

    /**
     * The screen for {@code out} (computed from {@code in}) with the job's last snapshot. {@code canInstall} is
     * whether Android lets the app install apps; {@code cancelling} that Cancel was tapped and the job has not
     * stopped yet.
     */
    public static Screen present(HomeModel.Output out, HomeModel.Input in, JobProgress progress, boolean canInstall,
                                 boolean cancelling) {
        Button more = new Button(new Text("hf_btn_more"), true, Command.OPEN_MORE);
        Text versions = ScreenTexts.versions(in.appVersion, in.installed);
        if (out.button == null) return running(out, progress, more, cancelling, versions);
        HomeModel.Block failure = startsJob(out.action) ? failure(progress) : null;
        List<HomeModel.Block> blocks = new ArrayList<>();
        if (out.action == HomeModel.Action.SET_UP) {
            if (!out.problems.isEmpty()) blocks.add(out.problems.get(0));
        } else {
            blocks.addAll(out.blocks);
        }
        Command command = commandFor(out.action, asksToAllowInstalls(in, canInstall));
        Button main = new Button(out.button, out.enabled, command);
        Button play = out.canPlay && out.action != HomeModel.Action.PLAY
                ? new Button(new Text("hf_btn_play"), true, Command.PLAY) : null;
        Button discard = out.canDiscard ? new Button(new Text("hf_btn_discard"), true, Command.DISCARD_JOB) : null;
        return new Screen(out.state, blocks, out.notes, failure, null, main, play, discard, more, null, versions);
    }

    /** The big button starts (or goes on with) an update job: the last job's failure is shown above it. */
    static boolean startsJob(HomeModel.Action action) {
        switch (action) {
            case REINSTALL:
            case INSTALL:
            case UPDATE:
            case BUILD:
            case RESUME:
                return true;
            default:
                return false;
        }
    }

    private static Screen running(HomeModel.Output out, JobProgress progress, Button more, boolean cancelling,
                                  Text versions) {
        Text counter = progress.stepCount > 0
                ? new Text("hf_bar_step", String.valueOf(progress.stepNumber), String.valueOf(progress.stepCount)) : null;
        Bar bar = new Bar(progress.permille, progress.state, counter, TimeLeft.of(progress.msLeft));
        Button cancel = cancelling ? new Button(new Text("hf_btn_stopping"), false, Command.NONE)
                : new Button(new Text("hf_btn_cancel"), true, Command.CANCEL_JOB);
        return new Screen(out.state, Collections.<HomeModel.Block>emptyList(), out.notes, null, bar, null, null, null,
                more, cancel, versions);
    }

    private static HomeModel.Block failure(JobProgress p) {
        if (p == null || (p.phase != JobProgress.Phase.FAILED && p.phase != JobProgress.Phase.CANCELLED)) return null;
        Text reason = p.failureReason != null ? p.failureReason : p.state;
        Text advice = p.failureAdvice != null ? p.failureAdvice : new Text("hf_advice_resume");
        return new HomeModel.Block(reason, advice);
    }

    private static Command commandFor(HomeModel.Action action, boolean asksInstalls) {
        switch (action) {
            case SET_UP:
                return Command.OPEN_ASSISTANT;
            case REINSTALL:
            case INSTALL:
            case UPDATE:
                return asksInstalls ? Command.ASK_ALLOW_INSTALLS : Command.START_JOB;
            case RESUME:
                return asksInstalls ? Command.ASK_ALLOW_INSTALLS : Command.RESUME_JOB;
            case BUILD:
                return Command.START_JOB;
            case PLAY:
                return Command.PLAY;
            default:
                return Command.NONE;
        }
    }
}
