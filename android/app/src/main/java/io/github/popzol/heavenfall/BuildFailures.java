package io.github.popzol.heavenfall;

/** What the builder service's failed stage means for the player (the stage names are phone_build.py's and the service's). */
public final class BuildFailures {
    private BuildFailures() {
    }

    public static UpdateFailure forStage(String stage) {
        if (stage == null) return UpdateFailure.BUILD_FAILED;
        switch (stage) {
            case "space":
                return UpdateFailure.BUILD_SPACE;
            case "memory":
                return UpdateFailure.BUILD_MEMORY;
            case "download":
            case "tools":
                return UpdateFailure.BUILD_TOOLS;
            case "extract":
            case "version":
            case "mods":
                return UpdateFailure.BUILD_GAME_FILES;
            case "recompile":
            case "shaders":
            case "compile":
            case "link":
                return UpdateFailure.BUILD_COMPILE;
            case "install":
            case "dest":
                return UpdateFailure.BUILD_INSTALL;
            default:
                return UpdateFailure.BUILD_FAILED;
        }
    }
}
