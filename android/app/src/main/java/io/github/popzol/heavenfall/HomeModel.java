package io.github.popzol.heavenfall;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The home screen's rules as a pure function of what the app found: one state line, the one big button, what stops
 * that button (with the advice for it), the "More" entries that work now. Nothing here touches the phone.
 *
 * <p>The button, in the order the rules apply: a job running (no button); the setup missing or wrong (SET_UP); a
 * job left half-done (RESUME); nothing installed (INSTALL); a newer app or mod, or MH XX copied since the install
 * (UPDATE: one button for the APK, the mod and the library); the library missing or not made for this mod (BUILD);
 * otherwise PLAY. Not knowing the latest versions (no network) never takes PLAY away from an installed game whose
 * library is good.
 */
public final class HomeModel {
    public enum Storage { NO_PERMISSION, NO_FOLDER, READY }

    /** The game dump, the 1.3.0 update or the MH XX dump as the app found it. */
    public enum Source { MISSING, ENCRYPTED, WRONG_REGION, WRONG_VERSION, GOOD }

    /** The code library on the phone: none, made by other tools, made for another mod code, or good. */
    public enum Library { NONE, OTHER_TOOLS, OTHER_MOD, GOOD }

    public enum Job { NONE, RUNNING, HALF_DONE }

    public enum Action { NONE, SET_UP, INSTALL, UPDATE, BUILD, RESUME, PLAY }

    /** The entries of the "More" list. */
    public enum More {
        GAME_FOLDER("hf_more_game_folder"), MH_XX("hf_more_mh_xx"), REINSTALL("hf_more_reinstall"),
        REBUILD("hf_more_rebuild"), LANGUAGE("hf_more_language"), CREDITS("hf_more_credits"),
        SHARE_LOG("hf_more_share_log");

        private final String labelKey;

        More(String labelKey) {
            this.labelKey = labelKey;
        }

        public String labelKey() {
            return labelKey;
        }
    }

    public enum CheckStatus { OK, MISSING, PROBLEM }

    /** What the home screen needs to know; fields not set mean "not known" or "nothing". */
    public static final class Input {
        public Storage storage = Storage.READY;
        public Source game = Source.MISSING;
        public Source update = Source.MISSING;
        public Source xx = Source.MISSING;
        /** What installed.json says, or null when nothing is installed. */
        public Installed installed;
        public Library library = Library.NONE;
        /** The id found beside a library made by other tools ("" for none): shown, never compared here. */
        public String libraryId = "";
        /** The running app's version. */
        public String appVersion = "";
        /** The latest app / mod versions (release tags), or null when not known (offline, or not asked yet). */
        public String latestApp;
        public String latestMod;
        /** The latest mod release carries the set with MH XX. */
        public boolean releaseHasXxSet;
        /** The check for the latest versions is still running. */
        public boolean checking;
        /** Free bytes where the downloads and the mod go, available memory in MB, battery percent: -1 unknown. */
        public long freeBytes = -1;
        public long availableMemoryMb = -1;
        public int batteryPercent = -1;
        public boolean charging;
        public Job job = Job.NONE;
    }

    /** A reason with what to do about it. */
    public static final class Block {
        public final Text reason;
        public final Text advice;

        Block(Text reason, Text advice) {
            this.reason = reason;
            this.advice = advice;
        }

        @Override
        public String toString() {
            return reason + " / " + advice;
        }
    }

    /** One line of the setup's checklist. */
    public static final class Check {
        public final Text label;
        public final CheckStatus status;
        public final boolean required;
        /** Why it is not OK and what to do; null when it is OK. An optional line's problem stops nothing. */
        public final Block problem;

        Check(Text label, CheckStatus status, boolean required, Block problem) {
            this.label = label;
            this.status = status;
            this.required = required;
            this.problem = problem;
        }
    }

    public static final class Output {
        /** The state line. */
        public Text state;
        public Action action = Action.NONE;
        /** The big button's label (null when there is no button). */
        public Text button;
        /** The button takes a tap (false while something blocks it, or when there is no button). */
        public boolean enabled;
        /** What stops the button when it is not enabled, in the order to fix it; each has its advice. */
        public List<Block> blocks = Collections.emptyList();
        /** SET_UP only: what is missing or wrong, in the order of the checklist. */
        public List<Block> problems = Collections.emptyList();
        /** The setup's checklist (always filled: the first-run assistant shows it). */
        public List<Check> checks = Collections.emptyList();
        /** Lines under the state that stop nothing (an MH XX copy that cannot be used). */
        public List<Text> notes = Collections.emptyList();
        /** The version the button's label names (UPDATE), else null. */
        public String targetVersion;
        /** The Play entry beside the big button when it is not the big button (the installed game can start). */
        public boolean canPlay;
        public Set<More> more = EnumSet.noneOf(More.class);
        /** UPDATE: the update includes building the library (the mod's code changes or the library is not good). */
        public boolean includesBuild;
    }

    private final Tuning tuning;

    public HomeModel(Tuning tuning) {
        this.tuning = tuning;
    }

    public Output compute(Input in) {
        Output out = new Output();
        out.checks = checks(in);
        out.notes = notes(in);
        out.more = more(in);
        if (in.job == Job.RUNNING) {
            out.state = new Text("hf_state_working");
            return out;
        }
        List<Block> problems = setupProblems(in, out.checks);
        if (!problems.isEmpty()) {
            out.action = Action.SET_UP;
            out.button = new Text("hf_btn_set_up");
            out.enabled = true;
            out.state = problems.get(0).reason;
            out.problems = problems;
            return out;
        }
        if (in.job == Job.HALF_DONE) {
            out.action = Action.RESUME;
            out.button = new Text("hf_btn_resume");
            out.state = new Text("hf_state_half_done");
            out.includesBuild = true;
            out.blocks = heavy(in, true);
            out.enabled = out.blocks.isEmpty();
            return out;
        }
        Installed installed = in.installed;
        if (installed == null) {
            out.action = Action.INSTALL;
            out.button = new Text("hf_btn_install");
            out.state = new Text("hf_state_not_installed");
            out.includesBuild = true;
            List<Block> blocks = new ArrayList<>();
            addNetwork(in, blocks);
            blocks.addAll(heavy(in, true));
            out.blocks = blocks;
            out.enabled = blocks.isEmpty();
            return out;
        }
        boolean modNewer = in.latestMod != null && Version.updateTo(in.latestMod, installed.modVersion);
        boolean appNewer = in.latestApp != null && Version.newer(in.latestApp, in.appVersion) == 1;
        boolean xxCame = in.latestMod != null && in.xx == Source.GOOD && !installed.withXx && in.releaseHasXxSet;
        boolean libraryBad = in.library != Library.GOOD;
        boolean playable = !libraryBad;
        if (modNewer || appNewer || xxCame) {
            out.action = Action.UPDATE;
            boolean build = modNewer || xxCame || libraryBad;
            out.includesBuild = build;
            if (modNewer) {
                out.targetVersion = displayVersion(in.latestMod);
                out.state = new Text("hf_state_update_available", out.targetVersion);
            } else if (xxCame) {
                out.targetVersion = displayVersion(installed.modVersion);
                out.state = new Text("hf_state_xx_found");
            } else {
                out.targetVersion = displayVersion(in.latestApp);
                out.state = new Text("hf_state_app_update", out.targetVersion);
            }
            out.button = new Text("hf_btn_update_to", out.targetVersion);
            List<Block> blocks = new ArrayList<>();
            if ((modNewer || xxCame) && in.update != Source.GOOD) {
                blocks.add(sourceBlock("update", in.update));
            }
            blocks.addAll(heavy(in, build));
            out.blocks = blocks;
            out.enabled = blocks.isEmpty();
            out.canPlay = playable;
            return out;
        }
        if (libraryBad) {
            out.action = Action.BUILD;
            out.button = new Text("hf_btn_build");
            out.state = in.library == Library.NONE ? new Text("hf_state_build_needed")
                    : in.library == Library.OTHER_TOOLS && !in.libraryId.isEmpty()
                            ? new Text("hf_state_rebuild_other_tools", in.libraryId)
                    : in.library == Library.OTHER_TOOLS ? new Text("hf_state_rebuild_other_tools_noid")
                    : new Text("hf_state_rebuild_other_mod");
            out.includesBuild = true;
            out.blocks = heavy(in, true);
            out.enabled = out.blocks.isEmpty();
            return out;
        }
        out.action = Action.PLAY;
        out.button = new Text("hf_btn_play");
        out.enabled = true;
        out.canPlay = true;
        boolean unknown = in.latestMod == null || in.latestApp == null;
        out.state = in.checking && unknown ? new Text("hf_state_checking")
                : unknown ? new Text("hf_state_offline") : new Text("hf_state_up_to_date");
        return out;
    }

    private static String displayVersion(String tag) {
        Version v = Version.parse(tag);
        return v == null ? tag : v.toString();
    }

    // ---- the setup ----

    private static Text problemText(String kind, String subject, Source s) {
        return new Text("hf_" + kind + "_" + subject + "_" + s.name().toLowerCase(java.util.Locale.ROOT).replace("wrong_", ""));
    }

    private static Block sourceBlock(String subject, Source s) {
        return new Block(problemText("problem", subject, s), problemText("advice", subject, s));
    }

    private static Check sourceCheck(String subject, Source s, boolean required) {
        Text label = new Text("hf_check_" + subject);
        if (s == Source.GOOD) return new Check(label, CheckStatus.OK, required, null);
        return new Check(label, s == Source.MISSING ? CheckStatus.MISSING : CheckStatus.PROBLEM, required,
                sourceBlock(subject, s));
    }

    private List<Check> checks(Input in) {
        List<Check> list = new ArrayList<>();
        Text perm = new Text("hf_check_permission");
        list.add(in.storage == Storage.NO_PERMISSION
                ? new Check(perm, CheckStatus.MISSING, true, new Block(new Text("hf_problem_permission"), new Text("hf_advice_permission")))
                : new Check(perm, CheckStatus.OK, true, null));
        Text folder = new Text("hf_check_folder");
        list.add(in.storage == Storage.READY
                ? new Check(folder, CheckStatus.OK, true, null)
                : new Check(folder, CheckStatus.MISSING, true, in.storage == Storage.NO_FOLDER
                        ? new Block(new Text("hf_problem_folder"), new Text("hf_advice_folder")) : null));
        list.add(sourceCheck("game", in.game, true));
        // the update is read again only to apply a mod: once one is installed it is no longer a setup need
        list.add(sourceCheck("update", in.update, in.installed == null));
        list.add(sourceCheck("xx", in.xx, false));
        return list;
    }

    private static List<Block> setupProblems(Input in, List<Check> checks) {
        List<Block> list = new ArrayList<>();
        for (int i = 0; i < checks.size(); i++) {
            Check c = checks.get(i);
            if (c.required && c.status != CheckStatus.OK && c.problem != null) list.add(c.problem);
            // the permission and the folder come first: what the dumps need cannot be judged before them
            if (i == 1 && !list.isEmpty()) break;
        }
        return list;
    }

    private static List<Text> notes(Input in) {
        List<Text> list = new ArrayList<>();
        if (in.xx != Source.GOOD && in.xx != Source.MISSING) list.add(problemText("problem", "xx", in.xx));
        return list;
    }

    // ---- what stops a heavy button ----

    private static void addNetwork(Input in, List<Block> blocks) {
        if (in.latestMod != null) return;
        if (in.checking) blocks.add(new Block(new Text("hf_block_checking"), new Text("hf_advice_wait")));
        else blocks.add(new Block(new Text("hf_block_no_network"), new Text("hf_advice_connect")));
    }

    /** Space, battery and memory against the tuning, for an action that downloads and, with {@code build}, builds. */
    private List<Block> heavy(Input in, boolean build) {
        List<Block> blocks = new ArrayList<>();
        long need = build ? tuning.minFreeBuildBytes() : tuning.minFreeDownloadBytes();
        if (in.freeBytes >= 0 && in.freeBytes < need) {
            blocks.add(new Block(new Text("hf_block_space", megabytes(need), megabytes(in.freeBytes)),
                    new Text("hf_advice_space")));
        }
        if (build) {
            if (in.batteryPercent >= 0 && !in.charging && in.batteryPercent < tuning.minBatteryPercent) {
                blocks.add(new Block(new Text("hf_block_battery", String.valueOf(in.batteryPercent)),
                        new Text("hf_advice_battery")));
            }
            if (in.availableMemoryMb >= 0 && in.availableMemoryMb < tuning.minAvailableMemoryMb) {
                blocks.add(new Block(new Text("hf_block_memory", String.valueOf(in.availableMemoryMb)),
                        new Text("hf_advice_memory")));
            }
        }
        return blocks;
    }

    private static String megabytes(long bytes) {
        return String.valueOf((bytes + (1L << 20) - 1) >> 20);
    }

    // ---- More ----

    private static Set<More> more(Input in) {
        Set<More> set = EnumSet.of(More.LANGUAGE, More.CREDITS, More.SHARE_LOG);
        if (in.job == Job.RUNNING) return set;
        if (in.storage != Storage.NO_PERMISSION) {
            set.add(More.GAME_FOLDER);
            set.add(More.MH_XX);
        }
        if (in.installed != null) {
            set.add(More.REBUILD);
            if (in.storage == Storage.READY && in.game == Source.GOOD && in.update == Source.GOOD
                    && in.latestMod != null) {
                set.add(More.REINSTALL);
            }
        }
        return set;
    }
}
