package io.github.popzol.heavenfall;

import static io.github.popzol.heavenfall.HomeModel.More;
import static io.github.popzol.heavenfall.HomeModel.Source;
import static io.github.popzol.heavenfall.HomeModel.Storage;
import static io.github.popzol.heavenfall.Support.sha;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import io.github.popzol.heavenfall.MorePresenter.Command;

public class MorePresenterTest {
    private final HomeModel model = Support.model();

    private HomeModel.Input input() {
        HomeModel.Input in = new HomeModel.Input();
        in.game = Source.GOOD;
        in.update = Source.GOOD;
        in.installed = Support.installed("beta", "0.2.3", "s", sha('a'), false, sha('b'), "t", sha('b'));
        in.latestMod = "v0.2.3";
        in.latestApp = "v1.0.0";
        in.appVersion = "1.0.0";
        in.library = HomeModel.Library.GOOD;
        return in;
    }

    private static MorePresenter.Row row(List<MorePresenter.Row> rows, More m) {
        for (MorePresenter.Row r : rows) {
            if (r.entry == m) return r;
        }
        throw new AssertionError(m);
    }

    /** Every entry of the model is a row, in the model's order. */
    @Test
    public void everyEntryIsARow() {
        List<MorePresenter.Row> rows = MorePresenter.rows(model.compute(input()));
        assertEquals(More.values().length, rows.size());
        for (int i = 0; i < rows.size(); i++) assertEquals(More.values()[i], rows.get(i).entry);
    }

    /** An enabled entry does what its name says. */
    @Test
    public void enabledEntriesRun() {
        List<MorePresenter.Row> rows = MorePresenter.rows(model.compute(input()));
        assertEquals(Command.CHOOSE_FOLDER, row(rows, More.GAME_FOLDER).press());
        assertEquals(Command.OPEN_XX, row(rows, More.MH_XX).press());
        assertEquals(Command.CONFIRM_REINSTALL, row(rows, More.REINSTALL).press());
        assertEquals(Command.CONFIRM_REBUILD, row(rows, More.REBUILD).press());
        assertEquals(Command.CHOOSE_LANGUAGE, row(rows, More.LANGUAGE).press());
        assertEquals(Command.OPEN_CREDITS, row(rows, More.CREDITS).press());
        assertEquals(Command.SHARE_LOG, row(rows, More.SHARE_LOG).press());
        for (MorePresenter.Row r : rows) assertNull(r.unavailable);
    }

    /** An entry the model does not enable takes no tap and says it is not available. */
    @Test
    public void disabledEntriesTakeNoTap() {
        HomeModel.Input in = input();
        in.storage = Storage.NO_PERMISSION;
        in.installed = null;
        List<MorePresenter.Row> rows = MorePresenter.rows(model.compute(in));
        for (More m : new More[] {More.GAME_FOLDER, More.MH_XX, More.REINSTALL, More.REBUILD}) {
            MorePresenter.Row r = row(rows, m);
            assertFalse(m.name(), r.enabled);
            assertEquals(m.name(), Command.NONE, r.press());
            assertNotNull(m.name(), r.unavailable);
        }
        assertEquals(Command.CHOOSE_LANGUAGE, row(rows, More.LANGUAGE).press());
        assertEquals(Command.OPEN_CREDITS, row(rows, More.CREDITS).press());
        assertEquals(Command.SHARE_LOG, row(rows, More.SHARE_LOG).press());
    }

    /** While a job runs only the harmless entries take a tap (Reinstall and Build again do not). */
    @Test
    public void duringAJobOnlyHarmlessEntries() {
        HomeModel.Input in = input();
        in.job = HomeModel.Job.RUNNING;
        List<MorePresenter.Row> rows = MorePresenter.rows(model.compute(in));
        assertEquals(Command.NONE, row(rows, More.REINSTALL).press());
        assertEquals(Command.NONE, row(rows, More.REBUILD).press());
        assertEquals(Command.NONE, row(rows, More.GAME_FOLDER).press());
        assertEquals(Command.OPEN_CREDITS, row(rows, More.CREDITS).press());
    }

    /** Reinstall and Build again ask first, each in its own words. */
    @Test
    public void confirmationsAreTheirOwn() {
        assertEquals("hf_confirm_reinstall_title", MorePresenter.confirmTitle(Command.CONFIRM_REINSTALL).key);
        assertEquals("hf_confirm_reinstall_text", MorePresenter.confirmText(Command.CONFIRM_REINSTALL).key);
        assertEquals("hf_confirm_rebuild_title", MorePresenter.confirmTitle(Command.CONFIRM_REBUILD).key);
        assertEquals("hf_confirm_rebuild_text", MorePresenter.confirmText(Command.CONFIRM_REBUILD).key);
    }

    /** The MH XX page says what the copy is, how it stands, and what to do. */
    @Test
    public void xxPageStates() {
        HomeModel.Input in = input();
        in.xx = Source.MISSING;
        MorePresenter.XxPage missing = MorePresenter.xxPage(model.compute(in), in);
        assertEquals("hf_problem_xx_missing", missing.state.key);
        assertEquals("hf_advice_xx_missing", missing.advice.key);

        in.xx = Source.ENCRYPTED;
        assertEquals("hf_problem_xx_encrypted", MorePresenter.xxPage(model.compute(in), in).state.key);

        in.xx = Source.GOOD;
        MorePresenter.XxPage found = MorePresenter.xxPage(model.compute(in), in);
        assertEquals("hf_xx_state_found", found.state.key);
        assertEquals("hf_xx_advice_update", found.advice.key);

        in.installed = Support.installed("beta", "0.2.3", "s", sha('a'), true, sha('b'), "t", sha('b'));
        MorePresenter.XxPage used = MorePresenter.xxPage(model.compute(in), in);
        assertEquals("hf_xx_state_in_use", used.state.key);
        assertNull(used.advice);
        assertTrue(used.what.key.startsWith("hf_xx_"));
        assertEquals("hf_xx_where", used.where.key);
    }
}
