package com.example.barangay_superapp;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Background check (about every 15 minutes - Android's minimum) for updates to the resident's
 * requests and reports, so they get a phone notification even when the app is closed.
 */
public class StatusCheckWorker extends Worker {
    private static final String UNIQUE_NAME = "request-status-check";

    public StatusCheckWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        SharedPreferences prefs = ctx.getSharedPreferences(HistorySync.PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean("IS_LOGGED_IN", false)) return Result.success();
        List<HistorySync.Update> updates = HistorySync.syncBlocking(ctx);
        if (updates == null) return Result.retry();
        HistorySync.notifyUpdates(ctx, updates);
        return Result.success();
    }

    /** Start the periodic check (safe to call often - keeps the existing schedule). */
    public static void schedule(Context ctx) {
        Constraints c = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(StatusCheckWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(c)
                .build();
        WorkManager.getInstance(ctx.getApplicationContext())
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, req);
    }

    /** Stop it (on log out). */
    public static void cancel(Context ctx) {
        WorkManager.getInstance(ctx.getApplicationContext()).cancelUniqueWork(UNIQUE_NAME);
    }
}
