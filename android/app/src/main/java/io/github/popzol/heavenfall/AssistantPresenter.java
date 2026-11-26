package io.github.popzol.heavenfall;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The first-run assistant: the model's checklist as steps (the permission, the folder, the American game, its 1.3.0
 * update, MH XX), each with what to do when it is missing or wrong, and the one step the screen is on. It is done
 * when every required step is right; MH XX never holds it back.
 */
public final class AssistantPresenter {
    public enum Kind { PERMISSION, FOLDER, GAME, UPDATE, XX }

    /** What the step's button does. */
    public enum Do { NONE, GIVE_ACCESS, CHOOSE_FOLDER }

    public static final class Step {
        public final Kind kind;
        public final Text label;
        public final HomeModel.CheckStatus status;
        public final boolean required;
        /** What is wrong and what to do about it; null when the step is right. */
        public final HomeModel.Block problem;
        /** What to do to make it right, in order; empty when the step is right. */
        public final List<Text> help;
        public final Text actionLabel;
        public final Do action;

        Step(Kind kind, Text label, HomeModel.CheckStatus status, boolean required, HomeModel.Block problem,
             List<Text> help, Text actionLabel, Do action) {
            this.kind = kind;
            this.label = label;
            this.status = status;
            this.required = required;
            this.problem = problem;
            this.help = Collections.unmodifiableList(help);
            this.actionLabel = actionLabel;
            this.action = action;
        }
    }

    public static final class Screen {
        public final List<Step> steps;
        /** The step shown in full; null when the assistant is done and MH XX is right too. */
        public final Step current;
        /** Every required step is right: the assistant ends on Home. */
        public final boolean done;
        public final Text title;
        /** The folder is read anew. */
        public final Text checkAgainLabel;
        /** The button that ends the assistant (done) or leaves it for later. */
        public final Text leaveLabel;

        Screen(List<Step> steps, Step current, boolean done) {
            this.steps = Collections.unmodifiableList(steps);
            this.current = current;
            this.done = done;
            this.title = new Text(done ? "hf_assistant_done" : "hf_assistant_title");
            this.checkAgainLabel = new Text("hf_btn_check_again");
            this.leaveLabel = new Text(done ? "hf_btn_finish" : "hf_btn_later");
        }
    }

    private AssistantPresenter() {
    }

    public static Screen present(HomeModel.Output out) {
        List<Step> steps = new ArrayList<>();
        for (HomeModel.Check c : out.checks) {
            Kind kind = kindOf(c.label);
            if (kind != null) steps.add(step(kind, c));
        }
        Step current = null;
        for (Step s : steps) {
            if (s.required && s.status != HomeModel.CheckStatus.OK) {
                current = s;
                break;
            }
        }
        boolean done = current == null;
        if (done) {
            // everything required is right: MH XX, when it is not there, is offered as the optional last word
            for (Step s : steps) {
                if (s.kind == Kind.XX && s.status != HomeModel.CheckStatus.OK) current = s;
            }
        }
        return new Screen(steps, current, done);
    }

    private static Kind kindOf(Text label) {
        switch (label.key) {
            case "hf_check_permission": return Kind.PERMISSION;
            case "hf_check_folder": return Kind.FOLDER;
            case "hf_check_game": return Kind.GAME;
            case "hf_check_update": return Kind.UPDATE;
            case "hf_check_xx": return Kind.XX;
            default: return null;
        }
    }

    private static Step step(Kind kind, HomeModel.Check c) {
        boolean ok = c.status == HomeModel.CheckStatus.OK;
        List<Text> help = new ArrayList<>();
        Text actionLabel;
        Do action;
        switch (kind) {
            case PERMISSION:
                actionLabel = new Text("hf_btn_give_access");
                action = Do.GIVE_ACCESS;
                if (!ok) help.add(new Text("hf_help_permission"));
                break;
            case FOLDER:
                actionLabel = new Text("hf_btn_choose_folder");
                action = Do.CHOOSE_FOLDER;
                if (!ok) help.add(new Text("hf_help_folder"));
                break;
            default:
                actionLabel = new Text("hf_btn_other_folder");
                action = Do.CHOOSE_FOLDER;
                if (!ok) {
                    help.add(new Text(kind == Kind.GAME ? "hf_help_game" : kind == Kind.UPDATE ? "hf_help_update"
                            : "hf_help_xx"));
                    if (c.status == HomeModel.CheckStatus.MISSING) help.add(new Text("hf_help_where"));
                    help.add(new Text("hf_help_decrypt"));
                }
                break;
        }
        return new Step(kind, c.label, c.status, c.required, c.problem, help, actionLabel, action);
    }
}
