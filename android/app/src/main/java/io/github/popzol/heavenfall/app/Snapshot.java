package io.github.popzol.heavenfall.app;

import android.content.Context;

import io.github.popzol.heavenfall.HomeModel;
import io.github.popzol.heavenfall.PhoneFacts;
import io.github.popzol.heavenfall.UpdateCheck;

/** The phone read once for a screen of its own (the assistant, More): the facts, the model's input and its output. */
final class Snapshot {
    final PhoneFacts facts;
    final HomeModel.Input input;
    final HomeModel.Output output;

    private Snapshot(PhoneFacts facts, HomeModel.Input input, HomeModel.Output output) {
        this.facts = facts;
        this.input = input;
        this.output = output;
    }

    interface Done {
        void onLoaded(Snapshot snapshot);
    }

    /** Reads the phone off the main thread; {@code done} runs on the main thread. The last check's answer is used. */
    static void load(Context context, Done done) {
        Context c = context.getApplicationContext();
        PhoneReader.readAsync(c, facts -> {
            HomeModel.Input in = new HomeModel.Input();
            facts.applyTo(in);
            UpdateCheck.Latest last = UpdateChecker.last();
            if (last != null) UpdateCheck.fill(last, Config.editions(c), in);
            if (UpdateService.running()) in.job = HomeModel.Job.RUNNING;
            done.onLoaded(new Snapshot(facts, in, new HomeModel(Config.tuning(c), Config.buildNeeds(c)).compute(in)));
        });
    }
}
