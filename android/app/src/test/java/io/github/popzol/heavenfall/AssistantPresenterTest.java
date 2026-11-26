package io.github.popzol.heavenfall;

import static io.github.popzol.heavenfall.HomeModel.CheckStatus;
import static io.github.popzol.heavenfall.HomeModel.Source;
import static io.github.popzol.heavenfall.HomeModel.Storage;
import static io.github.popzol.heavenfall.Support.sha;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import io.github.popzol.heavenfall.AssistantPresenter.Do;
import io.github.popzol.heavenfall.AssistantPresenter.Kind;

public class AssistantPresenterTest {
    private final HomeModel model = new HomeModel(Support.tuning());

    private AssistantPresenter.Screen screen(Storage storage, Source game, Source update, Source xx, boolean installed) {
        HomeModel.Input in = new HomeModel.Input();
        in.storage = storage;
        in.game = game;
        in.update = update;
        in.xx = xx;
        if (installed) in.installed = new Installed("beta", "0.2.3", "s", sha('a'), false, sha('b'), "t", sha('b'));
        return AssistantPresenter.present(model.compute(in));
    }

    private static boolean has(java.util.List<Text> list, String key) {
        for (Text t : list) {
            if (t.key.equals(key)) return true;
        }
        return false;
    }

    /** The checklist has the five steps in order, whatever is wrong. */
    @Test
    public void fiveStepsInOrder() {
        AssistantPresenter.Screen s = screen(Storage.NO_PERMISSION, Source.MISSING, Source.MISSING, Source.MISSING, false);
        assertEquals(5, s.steps.size());
        assertEquals(Kind.PERMISSION, s.steps.get(0).kind);
        assertEquals(Kind.FOLDER, s.steps.get(1).kind);
        assertEquals(Kind.GAME, s.steps.get(2).kind);
        assertEquals(Kind.UPDATE, s.steps.get(3).kind);
        assertEquals(Kind.XX, s.steps.get(4).kind);
        assertFalse(s.steps.get(4).required);
    }

    /** The assistant is on the first required step that is not right, and the permission and the folder open upstream's flows. */
    @Test
    public void startsOnTheFirstStepThatIsNotRight() {
        AssistantPresenter.Screen s = screen(Storage.NO_PERMISSION, Source.GOOD, Source.GOOD, Source.MISSING, false);
        assertEquals(Kind.PERMISSION, s.current.kind);
        assertEquals(Do.GIVE_ACCESS, s.current.action);
        assertFalse(s.done);
        assertEquals("hf_advice_permission", s.current.problem.advice.key);
        assertTrue(has(s.current.help, "hf_help_permission"));

        s = screen(Storage.NO_FOLDER, Source.GOOD, Source.GOOD, Source.MISSING, false);
        assertEquals(Kind.FOLDER, s.current.kind);
        assertEquals(Do.CHOOSE_FOLDER, s.current.action);
        assertTrue(has(s.current.help, "hf_help_folder"));
    }

    /** A missing game says what to copy and where, and what decrypted means. */
    @Test
    public void aMissingGameSaysWhatToCopyAndWhere() {
        AssistantPresenter.Screen s = screen(Storage.READY, Source.MISSING, Source.MISSING, Source.MISSING, false);
        assertEquals(Kind.GAME, s.current.kind);
        assertEquals(CheckStatus.MISSING, s.current.status);
        assertEquals("hf_advice_game_missing", s.current.problem.advice.key);
        assertTrue(has(s.current.help, "hf_help_game"));
        assertTrue(has(s.current.help, "hf_help_where"));
        assertTrue(has(s.current.help, "hf_help_decrypt"));
        assertEquals("a wrong folder can be changed from here", Do.CHOOSE_FOLDER, s.current.action);
    }

    /** An encrypted copy is not "missing": it says to dump again, and does not tell where to copy it. */
    @Test
    public void anEncryptedCopySaysItIsEncrypted() {
        AssistantPresenter.Screen s = screen(Storage.READY, Source.GOOD, Source.ENCRYPTED, Source.MISSING, false);
        assertEquals(Kind.UPDATE, s.current.kind);
        assertEquals(CheckStatus.PROBLEM, s.current.status);
        assertEquals("hf_problem_update_encrypted", s.current.problem.reason.key);
        assertTrue(has(s.current.help, "hf_help_decrypt"));
        assertFalse(has(s.current.help, "hf_help_where"));
    }

    /** The wrong region and the wrong version each have their own words. */
    @Test
    public void wrongRegionAndVersionHaveTheirWords() {
        assertEquals("hf_problem_game_region",
                screen(Storage.READY, Source.WRONG_REGION, Source.GOOD, Source.MISSING, false).current.problem.reason.key);
        assertEquals("hf_problem_update_version",
                screen(Storage.READY, Source.GOOD, Source.WRONG_VERSION, Source.MISSING, false).current.problem.reason.key);
    }

    /** Done when every required step is right; MH XX missing never holds it back but is offered. */
    @Test
    public void doneWhenTheRequiredStepsAreRight() {
        AssistantPresenter.Screen s = screen(Storage.READY, Source.GOOD, Source.GOOD, Source.MISSING, false);
        assertTrue(s.done);
        assertEquals("hf_assistant_done", s.title.key);
        assertEquals("hf_btn_finish", s.leaveLabel.key);
        assertEquals(Kind.XX, s.current.kind);
        assertFalse(s.current.required);
        assertTrue(has(s.current.help, "hf_help_xx"));

        s = screen(Storage.READY, Source.GOOD, Source.GOOD, Source.GOOD, false);
        assertTrue(s.done);
        assertNull("nothing left to offer", s.current);
    }

    /** MH XX copied but unusable stops nothing. */
    @Test
    public void anUnusableXxStopsNothing() {
        AssistantPresenter.Screen s = screen(Storage.READY, Source.GOOD, Source.GOOD, Source.ENCRYPTED, false);
        assertTrue(s.done);
        assertEquals(CheckStatus.PROBLEM, s.steps.get(4).status);
    }

    /** Once the mod is installed the update's dump is no longer a required step. */
    @Test
    public void updateNotRequiredOnceInstalled() {
        AssistantPresenter.Screen s = screen(Storage.READY, Source.GOOD, Source.MISSING, Source.GOOD, true);
        assertTrue(s.done);
        assertFalse(s.steps.get(3).required);
    }

    /** While it is not done the screen leaves for later and offers Check again. */
    @Test
    public void notDoneLeavesForLater() {
        AssistantPresenter.Screen s = screen(Storage.READY, Source.MISSING, Source.MISSING, Source.MISSING, false);
        assertEquals("hf_btn_later", s.leaveLabel.key);
        assertEquals("hf_btn_check_again", s.checkAgainLabel.key);
        assertNotNull(s.title);
        assertEquals("hf_assistant_title", s.title.key);
    }

    /** Every step that is not right carries a button label and a way to act. */
    @Test
    public void everyOpenStepCanBeActedOn() {
        for (Storage st : Storage.values()) {
            for (Source src : Source.values()) {
                AssistantPresenter.Screen s = screen(st, src, src, src, false);
                for (AssistantPresenter.Step step : s.steps) {
                    assertNotNull(step.actionLabel);
                    assertTrue(step.action != Do.NONE);
                }
            }
        }
    }
}
