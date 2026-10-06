package io.github.hypermusicscape.lock;

import android.app.Activity;
import android.app.Instrumentation;
import android.database.Cursor;
import android.os.Bundle;

/** On-device regression for preference writes and the read-only SystemUI configuration path. */
public final class ToggleInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Config.setEnabled(getTargetContext(), false);
            if (!Config.setEnabled(getTargetContext(), true))
                throw new AssertionError("Could not save enabled state");
            if (!Config.enabled(getTargetContext()) || !providerValue())
                throw new AssertionError("Switch did not turn on");
            if (!Config.setEnabled(getTargetContext(), false))
                throw new AssertionError("Could not save disabled state");
            if (Config.enabled(getTargetContext()) || providerValue())
                throw new AssertionError("Switch did not turn off");
            result.putString("result", "preference on/off and provider read succeeded");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            Config.setEnabled(getTargetContext(), false);
            result.putString("error", error.toString());
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private boolean providerValue() {
        try (Cursor cursor = getContext().getContentResolver().query(Config.URI, null, null, null, null)) {
            return cursor != null && cursor.moveToFirst() && cursor.getInt(0) == 1;
        }
    }
}
