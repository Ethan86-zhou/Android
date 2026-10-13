package com.ethan.fruitslice;

import android.app.Application;
import android.content.SharedPreferences;

import java.util.UUID;

/** Exists only in the test APK; scene fixtures can never reach the user's history. */
public final class GameTestApplication extends Application {
    private final String testHistory = "fruit_slice_acceptance_" + UUID.randomUUID();
    private boolean isolated = true;

    void useProductionHistoryForReadOnlyAcceptance() { isolated = false; }

    @Override public SharedPreferences getSharedPreferences(String name, int mode) {
        return super.getSharedPreferences(isolated && "fruit_slice_history".equals(name) ? testHistory : name, mode);
    }

    void removeTestHistory() { super.deleteSharedPreferences(testHistory); }
}
