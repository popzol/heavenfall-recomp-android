package io.github.popzol.heavenfall;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The More list: every entry of the model, in its order, with whether it takes a tap (only those the home model
 * enables) and what a tap does. Reinstall and Build again ask first.
 */
public final class MorePresenter {
    public enum Command {
        NONE, CHOOSE_FOLDER, OPEN_XX, CONFIRM_REINSTALL, CONFIRM_REBUILD, CHOOSE_LANGUAGE, OPEN_CREDITS, SHARE_LOG
    }

    public static final class Row {
        public final HomeModel.More entry;
        public final Text label;
        public final boolean enabled;
        /** A short line under a row that cannot be used now; null for the others. */
        public final Text unavailable;
        private final Command command;

        Row(HomeModel.More entry, boolean enabled, Command command) {
            this.entry = entry;
            this.label = new Text(entry.labelKey());
            this.enabled = enabled;
            this.unavailable = enabled ? null : new Text("hf_more_unavailable");
            this.command = command;
        }

        /** What a tap does: nothing for a row that is not enabled. */
        public Command press() {
            return enabled ? command : Command.NONE;
        }
    }

    /** The MH XX page: what it adds, where to copy it, and how it stands. */
    public static final class XxPage {
        public final Text what = new Text("hf_xx_what");
        public final Text where = new Text("hf_xx_where");
        /** The state of the dump now. */
        public final Text state;
        /** What to do about it; null when there is nothing to do. */
        public final Text advice;

        XxPage(Text state, Text advice) {
            this.state = state;
            this.advice = advice;
        }
    }

    private MorePresenter() {
    }

    public static List<Row> rows(HomeModel.Output out) {
        List<Row> rows = new ArrayList<>();
        for (HomeModel.More m : HomeModel.More.values()) rows.add(new Row(m, out.more.contains(m), commandOf(m)));
        return Collections.unmodifiableList(rows);
    }

    private static Command commandOf(HomeModel.More m) {
        switch (m) {
            case GAME_FOLDER: return Command.CHOOSE_FOLDER;
            case MH_XX: return Command.OPEN_XX;
            case REINSTALL: return Command.CONFIRM_REINSTALL;
            case REBUILD: return Command.CONFIRM_REBUILD;
            case LANGUAGE: return Command.CHOOSE_LANGUAGE;
            case CREDITS: return Command.OPEN_CREDITS;
            case SHARE_LOG: return Command.SHARE_LOG;
            default: return Command.NONE;
        }
    }

    /** The question Reinstall and Build again ask before they start. */
    public static Text confirmTitle(Command c) {
        return new Text(c == Command.CONFIRM_REINSTALL ? "hf_confirm_reinstall_title" : "hf_confirm_rebuild_title");
    }

    public static Text confirmText(Command c) {
        return new Text(c == Command.CONFIRM_REINSTALL ? "hf_confirm_reinstall_text" : "hf_confirm_rebuild_text");
    }

    public static XxPage xxPage(HomeModel.Output out, HomeModel.Input in) {
        HomeModel.Check xx = null;
        for (HomeModel.Check c : out.checks) {
            if (c.label.key.equals("hf_check_xx")) xx = c;
        }
        if (xx == null || xx.status == HomeModel.CheckStatus.OK) {
            boolean used = in.installed != null && in.installed.withXx;
            return new XxPage(new Text(used ? "hf_xx_state_in_use" : "hf_xx_state_found"),
                    used ? null : new Text("hf_xx_advice_update"));
        }
        return new XxPage(xx.problem.reason, xx.problem.advice);
    }
}
