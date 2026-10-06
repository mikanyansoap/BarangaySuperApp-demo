package com.example.barangay_superapp;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Keeps the History screen in sync with Supabase and tells the resident when the barangay updates
 * one of their requests / reports (status, pickup date or remarks).
 *
 * Supabase is the source of truth: whoever changes a row (web portal, or someone editing it in the
 * Supabase table editor) - the phone picks it up on the next sync.
 * Used by PreviewActivity (while the app is open) and StatusCheckWorker (in the background).
 */
public final class HistorySync {
    private HistorySync() {}

    public static final String PREFS = "AppSession";
    public static final String KEY_HISTORY = "USER_SUBMITTED_REQUESTS";
    public static final String CHANNEL_ID = "request_updates";
    private static final Object LOCK = new Object();

    /** A change worth a notification. */
    public static final class Update {
        public final String key, title, message;
        Update(String key, String title, String message) { this.key = key; this.title = title; this.message = message; }
    }

    // ----------------------------------------------------------------
    // Status labels / colours
    // ----------------------------------------------------------------

    /** {label, text colour, background colour} for a database status. */
    public static String[] statusDisplay(String status) {
        switch (statusKey(status)) {
            case "approved":         return new String[]{"Approved", "#2F7D5B", "#DDF0E4"};
            case "in_progress":      return new String[]{"In Progress", "#247D76", "#DDF0EC"};
            case "ready_for_pickup": return new String[]{"Ready for Pickup", "#247D76", "#DDF0EC"};
            case "resolved":         return new String[]{"Resolved", "#8D9691", "#DFE2DD"};
            case "rejected":         return new String[]{"Rejected", "#CC4E42", "#FCEBEA"};
            case "cancelled":        return new String[]{"Cancelled", "#8D9691", "#DFE2DD"};
            default:                 return new String[]{"Pending", "#DBA03B", "#FDF1DA"};
        }
    }

    /** "Ready for Pickup" / "ready_for_pickup" / "READY FOR PICKUP" -> "ready_for_pickup". */
    public static String statusKey(String status) {
        String s = status == null ? "" : status.trim().toLowerCase(Locale.US).replace(' ', '_');
        if (s.isEmpty() || "null".equals(s)) return "pending";
        if (s.equals("canceled")) return "cancelled";
        return s;
    }

    /** "2026-10-05T07:30:00+00:00" or "2026-10-05" -> "Oct 5, 2026" (with time: "Oct 5, 2026 · 3:30 PM") in the phone's time zone. */
    public static String formatIsoDate(String iso, boolean hasTime) {
        if (iso == null || iso.length() < 10 || "null".equals(iso)) return "";
        try {
            Date d;
            if (hasTime && iso.length() >= 19) {
                SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
                in.setTimeZone(TimeZone.getTimeZone("UTC")); // Supabase timestamps are UTC
                d = in.parse(iso.substring(0, 19).replace(' ', 'T'));
                return d != null ? new SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.US).format(d) : "";
            }
            d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso.substring(0, 10));
            return d != null ? new SimpleDateFormat("MMM d, yyyy", Locale.US).format(d) : "";
        } catch (Exception e) {
            return iso.substring(0, 10);
        }
    }

    /** Current time as a UTC ISO timestamp (same format Supabase returns). */
    public static String nowIso() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'+00:00'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    private static String str(JSONObject r, String key) {
        if (r == null || !r.has(key) || r.isNull(key)) return "";
        String v = r.optString(key, "");
        return "null".equals(v) ? "" : v;
    }

    // ----------------------------------------------------------------
    // Order: newest first, cancelled ones at the bottom
    // ----------------------------------------------------------------

    public static void sortEntries(List<JSONObject> list) {
        Collections.sort(list, (x, y) -> {
            boolean cx = "cancelled".equals(statusKey(x.optString("status_key", x.optString("status"))));
            boolean cy = "cancelled".equals(statusKey(y.optString("status_key", y.optString("status"))));
            if (cx != cy) return cx ? 1 : -1;
            String tx = x.optString("created_at", ""), ty = y.optString("created_at", "");
            if (tx.isEmpty() != ty.isEmpty()) return tx.isEmpty() ? -1 : 1;   // not yet on the server = just sent
            return ty.compareTo(tx);
        });
    }

    // ----------------------------------------------------------------
    // Merging Supabase rows into the saved history
    // ----------------------------------------------------------------

    /** Copies status / pickup date / remarks of a Supabase row onto a history entry. */
    static void applyServerStatus(JSONObject o, JSONObject r) throws JSONException {
        String key = statusKey(str(r, "status"));
        String[] d = statusDisplay(key);
        o.put("status_key", key);
        o.put("status", d[0]);
        o.put("statusText", d[0]);               // short label only (long text broke the status chips)
        o.put("statusColorHex", d[1]);
        o.put("statusBgHex", d[2]);
        o.put("pickup_date", str(r, "pickup_date"));
        o.put("admin_remarks", str(r, "admin_remarks"));
        String created = str(r, "created_at");
        if (!created.isEmpty()) o.put("created_at", created);
    }

    /** History entry for a Supabase row this phone doesn't have yet (sent from another phone, or before a reinstall). */
    static JSONObject entryFromServer(String table, JSONObject r) throws JSONException {
        JSONObject o = new JSONObject();
        String category = str(r, "category");
        String title = str(r, "title");
        String type;
        if ("reports".equals(table)) {
            type = "Incident".equalsIgnoreCase(category) ? "Disaster Report" : "Report";
        } else {
            type = "barangay_id".equalsIgnoreCase(category) ? "Barangay ID Application" : "Document Request";
            title = title.replaceFirst("^(Document Request|Barangay ID Application):\\s*", "");
        }
        o.put("local_id", "server-" + table + "-" + str(r, "id"));
        o.put("requestType", type);
        o.put("description", title);
        String when = formatIsoDate(str(r, "created_at"), true);
        o.put("dateSubmitted", when.isEmpty() ? "" : "Submitted on " + when);
        o.put("category", "reports".equals(table) ? ("Incident".equalsIgnoreCase(category) ? "disaster" : "report") : category);
        o.put("full_details", str(r, "description"));
        o.put("supabase_id", str(r, "id"));
        o.put("supabase_table", table);
        String photo = str(r, "photo_url");
        if (!photo.isEmpty()) o.put("attachments", new JSONArray().put(photo));
        if (!str(r, "latitude").isEmpty()) {
            o.put("latitude", r.optDouble("latitude"));
            o.put("longitude", r.optDouble("longitude"));
        }
        applyServerStatus(o, r);
        return o;
    }

    /** What to tell the resident when a row changed; null when nothing worth notifying changed. */
    private static Update describeChange(JSONObject before, JSONObject after) {
        // saved by an older app version (no status_key yet): just adopt the server state quietly
        if (!before.has("status_key") && !"Pending".equalsIgnoreCase(before.optString("status", "Pending"))) return null;
        String oldKey = statusKey(before.optString("status_key", before.optString("status")));
        String newKey = after.optString("status_key");
        String oldPickup = before.optString("pickup_date", "");
        String newPickup = after.optString("pickup_date", "");
        String oldRemarks = before.optString("admin_remarks", "");
        String newRemarks = after.optString("admin_remarks", "");
        if (oldKey.equals(newKey) && oldPickup.equals(newPickup) && oldRemarks.equals(newRemarks)) return null;
        if ("cancelled".equals(newKey) && "cancelled".equals(oldKey)) return null;

        String what = after.optString("description", "").replaceAll("\\s*\\((REQ|REP|DIS|BID)-\\d+\\)$", "").trim();
        String kind = after.optString("requestType", "Request");
        String subject = what.isEmpty() ? kind : what;
        String pickup = newPickup.isEmpty() ? "" : formatIsoDate(newPickup, false);

        String message;
        if (!oldKey.equals(newKey)) {
            switch (newKey) {
                case "approved":         message = "Your " + kind.toLowerCase(Locale.US) + " was approved by the barangay."; break;
                case "in_progress":      message = "The barangay is now working on it."; break;
                case "ready_for_pickup": message = "Ready for pickup" + (pickup.isEmpty() ? " at the barangay hall." : " on " + pickup + "."); break;
                case "resolved":         message = "Marked as resolved by the barangay."; break;
                case "rejected":         message = "The barangay could not approve it" + (newRemarks.isEmpty() ? "." : ": " + newRemarks); break;
                case "cancelled":        message = "This request was cancelled."; break;
                default:                 message = "Status changed to " + statusDisplay(newKey)[0] + "."; break;
            }
        } else if (!oldPickup.equals(newPickup) && !pickup.isEmpty()) {
            message = "Pickup date set: " + pickup + ".";
        } else {
            message = "New note from the barangay: " + newRemarks;
        }
        return new Update(after.optString("supabase_table") + ":" + after.optString("supabase_id"),
                subject + " · " + statusDisplay(newKey)[0], message);
    }

    /**
     * Merges the user's Supabase rows ({"requests": [...], "reports": [...]}) into the saved history.
     * Returns the changes worth a notification (rows this phone already knew about whose status changed).
     */
    public static List<Update> merge(Context ctx, Map<String, JSONArray> serverRows) {
        List<Update> updates = new ArrayList<>();
        if (serverRows.isEmpty()) return updates;
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        synchronized (LOCK) {
            try {
                JSONArray saved = new JSONArray(prefs.getString(KEY_HISTORY, "[]"));
                Map<String, JSONObject> local = new HashMap<>();
                List<JSONObject> merged = new ArrayList<>();
                for (int i = 0; i < saved.length(); i++) {
                    JSONObject o = saved.getJSONObject(i);
                    merged.add(o);
                    String id = o.optString("supabase_id", "");
                    if (!id.isEmpty()) local.put(o.optString("supabase_table", "requests") + ":" + id, o);
                }
                for (Map.Entry<String, JSONArray> e : serverRows.entrySet()) {
                    JSONArray rows = e.getValue();
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject r = rows.getJSONObject(i);
                        JSONObject mine = local.get(e.getKey() + ":" + str(r, "id"));
                        if (mine != null) {
                            JSONObject before = new JSONObject(mine.toString());
                            applyServerStatus(mine, r);
                            Update u = describeChange(before, mine);
                            if (u != null) updates.add(u);
                        } else {
                            merged.add(entryFromServer(e.getKey(), r));
                        }
                    }
                }
                sortEntries(merged);
                JSONArray out = new JSONArray();
                for (JSONObject o : merged) out.put(o);
                prefs.edit().putString(KEY_HISTORY, out.toString()).apply();
            } catch (Exception e) {
                android.util.Log.w("HistorySync", "merge: " + e.getMessage());
            }
        }
        return updates;
    }

    /**
     * Fetches the signed-in user's reports + requests from Supabase and merges them.
     * BLOCKING (network) - call from a background thread / Worker. Returns the changes, or null when it couldn't sync.
     */
    public static List<Update> syncBlocking(Context ctx) {
        SupabaseClient.init(ctx);
        String uid = SupabaseClient.getUserId();
        if (uid == null || uid.isEmpty()) return null;
        Map<String, JSONArray> rows = new HashMap<>();
        for (String table : new String[]{"requests", "reports"}) {
            JSONArray r = SupabaseClient.fetchMySubmissionsBlocking(table, uid);
            if (r != null) rows.put(table, r);
        }
        if (rows.isEmpty()) return null;
        return merge(ctx, rows);
    }

    // ----------------------------------------------------------------
    // Phone notifications
    // ----------------------------------------------------------------

    public static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Request & report updates", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("When the barangay updates one of your requests or reports");
        nm.createNotificationChannel(ch);
    }

    public static boolean canNotify(Context ctx) {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled();
    }

    /** Shows one phone notification per update; tapping it opens the History screen. */
    public static void notifyUpdates(Context ctx, List<Update> updates) {
        if (updates == null || updates.isEmpty() || !canNotify(ctx)) return;
        ensureChannel(ctx);
        Intent open = new Intent(ctx, PreviewActivity.class);
        open.putExtra("LAYOUT_ID", R.layout.request_history);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 7001, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationManagerCompat nm = NotificationManagerCompat.from(ctx);
        for (Update u : updates) {
            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_bell)
                    .setContentTitle(u.title)
                    .setContentText(u.message)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(u.message))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT);
            try {
                nm.notify(u.key.hashCode(), b.build());
            } catch (SecurityException ignored) {
                // permission was revoked meanwhile
            }
        }
    }
}
