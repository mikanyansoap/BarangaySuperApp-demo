package com.example.barangay_superapp;

import android.content.Context;
import android.content.SharedPreferences;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import androidx.annotation.NonNull;

/**
 * Supabase REST helper.
 *
 * - Only the `profiles` table is used (the old `users` table does not exist -> 404).
 * - Profile rows are created by the Auth trigger on sign-up, so the app never INSERTs into profiles.
 * - Every authenticated DB / Storage request uses the signed-in user's JWT access token
 *   (refreshed automatically when it is about to expire). The anon key is only used for
 *   /auth/v1/* calls and when nobody is signed in.
 */
public class SupabaseClient {
    public static final String SUPABASE_URL = "https://wjrabyrmhymwtvcjywea.supabase.co";

    public static final String SUPABASE_PUBLIC_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6IndqcmFieXJtaHltd3R2Y2p5d2VhIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODk3MTk3MTksImV4cCI6MjEwNTI5NTcxOX0.4Eat43MG-bqiofQWvab0iEnzDlLsPiVbiMQLen_e-5o";

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient client = new OkHttpClient();

    // SharedPreferences keys for the auth session
    public static final String PREFS_NAME = "AppSession";
    public static final String KEY_ACCESS_TOKEN = "USER_ACCESS_TOKEN";
    public static final String KEY_REFRESH_TOKEN = "USER_REFRESH_TOKEN";
    public static final String KEY_TOKEN_EXPIRES_AT = "USER_TOKEN_EXPIRES_AT"; // epoch seconds

    private static Context appContext;

    /** Call once from Activity.onCreate so the client can read/refresh the stored session. */
    public static void init(Context context) {
        if (context != null) appContext = context.getApplicationContext();
    }

    private static SharedPreferences prefs() {
        return appContext != null ? appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) : null;
    }

    // ====================================================================
    // SESSION / JWT HANDLING
    // ====================================================================

    /** Stores access_token / refresh_token / expires_at from an Auth response (sign-in, sign-up or refresh). */
    public static void saveSession(JSONObject authJson) {
        SharedPreferences p = prefs();
        if (p == null || authJson == null) return;
        String access = authJson.optString("access_token", "");
        if (access.isEmpty()) return;
        long expiresAt = authJson.optLong("expires_at", 0);
        if (expiresAt == 0) {
            long expiresIn = authJson.optLong("expires_in", 3600);
            expiresAt = System.currentTimeMillis() / 1000 + expiresIn;
        }
        SharedPreferences.Editor e = p.edit()
                .putString(KEY_ACCESS_TOKEN, access)
                .putLong(KEY_TOKEN_EXPIRES_AT, expiresAt);
        String refresh = authJson.optString("refresh_token", "");
        if (!refresh.isEmpty()) e.putString(KEY_REFRESH_TOKEN, refresh);
        e.apply();
    }

    public static void clearSession() {
        SharedPreferences p = prefs();
        if (p == null) return;
        p.edit().remove(KEY_ACCESS_TOKEN).remove(KEY_REFRESH_TOKEN).remove(KEY_TOKEN_EXPIRES_AT).apply();
    }

    /** Returns the stored user JWT (may be expired). Null when not signed in. */
    public static String getAccessToken() {
        SharedPreferences p = prefs();
        if (p == null) return null;
        String t = p.getString(KEY_ACCESS_TOKEN, null);
        return (t == null || t.isEmpty()) ? null : t;
    }

    public interface TokenCallback {
        /** @param userToken the user's valid JWT, or null if there is no signed-in session */
        void onToken(String userToken);
    }

    private static final Object refreshLock = new Object();
    private static boolean refreshInProgress = false;
    private static final List<TokenCallback> pendingTokenCallbacks = new ArrayList<>();

    /** Gives you a non-expired user JWT, refreshing it with the refresh_token first if needed. */
    public static void withFreshToken(TokenCallback callback) {
        SharedPreferences p = prefs();
        String token = getAccessToken();
        if (p == null || token == null) {
            callback.onToken(null);
            return;
        }
        long expiresAt = p.getLong(KEY_TOKEN_EXPIRES_AT, 0);
        long now = System.currentTimeMillis() / 1000;
        if (expiresAt == 0) expiresAt = readJwtExp(token);
        if (expiresAt == 0 || expiresAt - now > 60) {
            callback.onToken(token); // still valid for > 60s
            return;
        }

        String refreshToken = p.getString(KEY_REFRESH_TOKEN, "");
        if (refreshToken.isEmpty()) {
            callback.onToken(token); // nothing we can do, try with what we have
            return;
        }

        synchronized (refreshLock) {
            pendingTokenCallbacks.add(callback);
            if (refreshInProgress) return;
            refreshInProgress = true;
        }

        try {
            JSONObject body = new JSONObject();
            body.put("refresh_token", refreshToken);
            Request request = anonBuilder("/auth/v1/token?grant_type=refresh_token")
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();
            client.newCall(request).enqueue(new Callback() {
                @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    finishRefresh(getAccessToken());
                }
                @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    String resp = readBody(response);
                    if (response.isSuccessful()) {
                        try { saveSession(new JSONObject(resp)); } catch (Exception ignored) {}
                        finishRefresh(getAccessToken());
                    } else {
                        android.util.Log.e("SupabaseAuth", "Token refresh failed (" + response.code() + "): " + resp);
                        finishRefresh(getAccessToken());
                    }
                }
            });
        } catch (Exception e) {
            finishRefresh(getAccessToken());
        }
    }

    private static void finishRefresh(String token) {
        List<TokenCallback> toCall;
        synchronized (refreshLock) {
            toCall = new ArrayList<>(pendingTokenCallbacks);
            pendingTokenCallbacks.clear();
            refreshInProgress = false;
        }
        for (TokenCallback cb : toCall) cb.onToken(token);
    }

    /** Reads the "exp" claim (epoch seconds) from a JWT without verifying it. */
    private static long readJwtExp(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return 0;
            byte[] decoded = android.util.Base64.decode(parts[1],
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
            return new JSONObject(new String(decoded, StandardCharsets.UTF_8)).optLong("exp", 0);
        } catch (Exception e) {
            return 0;
        }
    }

    // ====================================================================
    // REQUEST BUILDERS
    // ====================================================================

    /** Anon-key request (Auth endpoints, public data when signed out). */
    public static Request.Builder anonBuilder(String endpoint) {
        return new Request.Builder()
                .url(SUPABASE_URL + endpoint)
                .addHeader("apikey", SUPABASE_PUBLIC_KEY)
                .addHeader("Authorization", "Bearer " + SUPABASE_PUBLIC_KEY)
                .addHeader("Content-Type", "application/json; charset=utf-8");
    }

    /** Request authorized with the user's JWT (falls back to anon key only if token is null). */
    public static Request.Builder getAuthenticatedBuilder(String endpoint, String userAuthToken) {
        String token = (userAuthToken != null && !userAuthToken.isEmpty()) ? userAuthToken : SUPABASE_PUBLIC_KEY;
        return new Request.Builder()
                .url(SUPABASE_URL + endpoint)
                .addHeader("apikey", SUPABASE_PUBLIC_KEY)
                .addHeader("Authorization", "Bearer " + token)
                .addHeader("Content-Type", "application/json; charset=utf-8");
    }

    /** Uses the stored user JWT. Prefer withFreshToken(...) + getAuthenticatedBuilder(endpoint, token). */
    public static Request.Builder getAuthenticatedBuilder(String endpoint) {
        return getAuthenticatedBuilder(endpoint, getAccessToken());
    }

    private static String readBody(Response response) throws IOException {
        ResponseBody b = response.body();
        return b != null ? new String(b.bytes(), StandardCharsets.UTF_8) : "";
    }

    private static void failCallback(Callback callback, Request request, String message) {
        if (callback != null) callback.onFailure(client.newCall(request), new IOException(message));
    }

    // ====================================================================
    // STORAGE
    // ====================================================================

    public interface StorageUploadCallback {
        void onSuccess(String publicUrl);
        void onError(String error);
    }

    /** Uploads file bytes to a Storage bucket using the signed-in user's JWT and returns the public URL. */
    public static void uploadToStorageBucket(String bucketName, String fileName, byte[] fileBytes, String mimeType, StorageUploadCallback callback) {
        withFreshToken(token -> uploadToStorageBucket(bucketName, fileName, fileBytes, mimeType, token, callback));
    }

    public static void uploadToStorageBucket(String bucketName, String fileName, byte[] fileBytes, String mimeType, String userAuthToken, StorageUploadCallback callback) {
        if (userAuthToken == null || userAuthToken.isEmpty()) {
            // Try to pick up the stored session (and refresh it) before giving up
            String stored = getAccessToken();
            if (stored == null) {
                callback.onError("Not signed in - please sign in again to upload files.");
                return;
            }
            uploadToStorageBucket(bucketName, fileName, fileBytes, mimeType, callback);
            return;
        }
        try {
            String actualMime = "image/jpeg";
            if (mimeType != null) {
                String lower = mimeType.toLowerCase(Locale.US);
                if (lower.contains("png")) actualMime = "image/png";
                else if (lower.contains("pdf")) actualMime = "application/pdf";
                else if (lower.startsWith("video/")) actualMime = lower;
            }

            String endpoint = "/storage/v1/object/" + bucketName + "/" + fileName;
            RequestBody body = RequestBody.create(fileBytes, MediaType.parse(actualMime));

            Request request = new Request.Builder()
                    .url(SUPABASE_URL + endpoint)
                    .addHeader("apikey", SUPABASE_PUBLIC_KEY)
                    .addHeader("Authorization", "Bearer " + userAuthToken)
                    .addHeader("x-upsert", "true")
                    .post(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    android.util.Log.e("SupabaseStorage", "Upload failed: " + e.getMessage());
                    callback.onError(e.getMessage() != null ? e.getMessage() : "Network error");
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    String respStr = readBody(response);
                    if (response.isSuccessful()) {
                        String publicUrl = SUPABASE_URL + "/storage/v1/object/public/" + bucketName + "/" + fileName;
                        android.util.Log.d("SupabaseStorage", "Upload SUCCESS: " + publicUrl);
                        callback.onSuccess(publicUrl);
                    } else {
                        android.util.Log.e("SupabaseStorage", "Upload error (" + response.code() + "): " + respStr);
                        callback.onError("HTTP " + response.code() + ": " + respStr);
                    }
                }
            });
        } catch (Exception e) {
            android.util.Log.e("SupabaseStorage", "Upload Exception: " + e.getMessage());
            callback.onError(e.getMessage());
        }
    }

    // ====================================================================
    // RESILIENT WRITES
    // ====================================================================

    private static final Pattern MISSING_COLUMN = Pattern.compile("Could not find the '([^']+)' column");

    /**
     * If PostgREST rejects a write because of ONE field (column doesn't exist, check constraint,
     * bad foreign key, wrong type), returns the name of that field so it can be dropped and the
     * write retried. Returns null when the error isn't about a specific field we sent.
     */
    private static String offendingField(String errorBody, JSONObject fields) {
        try {
            JSONObject err = new JSONObject(errorBody);
            String code = err.optString("code", "");
            String msg = err.optString("message", "") + " " + err.optString("details", "");
            Matcher m = MISSING_COLUMN.matcher(msg);
            if (m.find() && fields.has(m.group(1))) return m.group(1);
            if (code.equals("23514") || code.equals("23503") || code.equals("22P02") || code.equals("23502") || code.equals("42703")) {
                // e.g. violates check constraint "reports_priority_check" / foreign key "profiles_barangay_id_fkey"
                String longest = null;
                JSONArray names = fields.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        String f = names.getString(i);
                        if (msg.contains(f) && (longest == null || f.length() > longest.length())) longest = f;
                    }
                }
                return longest;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Last server error per table, so the UI can show the real reason a write failed. */
    public static volatile String lastWriteError = "";

    /**
     * POST (insert) or PATCH (update) with the user's JWT. If one field is rejected it is dropped and the
     * write is retried (max 8 times), so an unknown column or value never blocks the whole row.
     */
    private static void resilientWrite(String method, String endpoint, JSONObject fields, String token, Callback callback, int attempt) {
        if (fields.length() == 0) {
            failCallback(callback, getAuthenticatedBuilder(endpoint, token).build(), "No valid columns to save");
            return;
        }
        Request.Builder b = getAuthenticatedBuilder(endpoint, token).addHeader("Prefer", "return=representation");
        RequestBody body = RequestBody.create(fields.toString(), JSON);
        Request request = ("PATCH".equals(method) ? b.patch(body) : b.post(body)).build();

        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                lastWriteError = e.getMessage() != null ? e.getMessage() : "Network error";
                android.util.Log.e("SupabaseWrite", method + " " + endpoint + " failed: " + e.getMessage());
                if (callback != null) callback.onFailure(call, e);
            }

            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (!response.isSuccessful()) {
                    String err = readBody(response);
                    String drop = (response.code() == 400 || response.code() == 409) && attempt < 8 ? offendingField(err, fields) : null;
                    if (drop != null) {
                        android.util.Log.w("SupabaseWrite", endpoint + ": dropping '" + drop + "' (" + err + ")");
                        fields.remove(drop);
                        resilientWrite(method, endpoint, fields, token, callback, attempt + 1);
                        return;
                    }
                    lastWriteError = "HTTP " + response.code() + ": " + err;
                    android.util.Log.e("SupabaseWrite", method + " " + endpoint + " -> " + lastWriteError);
                    failCallback(callback, request, lastWriteError);
                    return;
                }
                lastWriteError = "";
                android.util.Log.d("SupabaseWrite", method + " " + endpoint + " OK: " + fields);
                if (callback != null) callback.onResponse(call, response);
            }
        });
    }

    public static String getUserId() {
        SharedPreferences p = prefs();
        if (p != null) {
            String u = p.getString("USER_ID", "");
            if (!u.isEmpty()) return u;
        }
        String token = getAccessToken();
        if (token != null) {
            try {
                String[] parts = token.split("\\.");
                if (parts.length >= 2) {
                    byte[] decoded = android.util.Base64.decode(parts[1],
                            android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
                    JSONObject obj = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
                    return obj.optString("sub", "");
                }
            } catch (Exception ignored) {}
        }
        return "";
    }

    private static void authedWrite(String method, String endpoint, JSONObject fields, Callback callback) {
        withFreshToken(token -> {
            String authToken = (token != null && !token.isEmpty()) ? token : SUPABASE_PUBLIC_KEY;
            resilientWrite(method, endpoint, fields, authToken, callback, 0);
        });
    }

    // ====================================================================
    // PROFILES (the only user table)
    // ====================================================================

    /** PATCHes the signed-in user's row in public.profiles (RLS needs the user's JWT). */
    public static void updateProfile(String userId, JSONObject fields, Callback callback) {
        if (userId == null || userId.isEmpty()) {
            failCallback(callback, getAuthenticatedBuilder("/rest/v1/profiles", null).build(), "Missing user id");
            return;
        }
        authedWrite("PATCH", "/rest/v1/profiles?id=eq." + userId, fields, callback);
    }

    /** Updates the avatar_url in public.profiles. */
    public static void updateUserProfilePhoto(String userId, String publicPhotoUrl, Callback callback) {
        try {
            JSONObject bodyJson = new JSONObject();
            bodyJson.put("avatar_url", publicPhotoUrl);
            updateProfile(userId, bodyJson, callback);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Saves the address into the signed-in user's Auth metadata (raw_user_meta_data).
     * profiles has no street/city/province/region columns, so this is where those parts live -
     * it's what login restores them from.
     */
    public static void updateUserMetadata(JSONObject data, Callback callback) {
        withFreshToken(token -> {
            try {
                JSONObject body = new JSONObject().put("data", data);
                Request request = getAuthenticatedBuilder("/auth/v1/user", token)
                        .put(RequestBody.create(body.toString(), JSON))
                        .build();
                if (token == null) {
                    failCallback(callback, request, "Not signed in");
                    return;
                }
                client.newCall(request).enqueue(callback);
            } catch (Exception e) {
                android.util.Log.e("SupabaseAuth", "metadata update failed: " + e.getMessage());
            }
        });
    }

    /** Converts MM/DD/YYYY to YYYY-MM-DD for PostgreSQL DATE column. Returns "" if invalid. */
    public static String formatToIsoDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) return "";
        if (dateStr.matches("\\d{4}-\\d{2}-\\d{2}")) return dateStr;
        try {
            String[] parts = dateStr.split("/");
            if (parts.length == 3) {
                int month = Integer.parseInt(parts[0]);
                int day = Integer.parseInt(parts[1]);
                int year = Integer.parseInt(parts[2]);
                return String.format(Locale.US, "%04d-%02d-%02d", year, month, day);
            }
        } catch (Exception ignored) {}
        return "";
    }

    /** Builds a single-line address from the individual parts. */
    public static String buildFullAddress(String street, String barangay, String city, String province) {
        StringBuilder sb = new StringBuilder();
        if (street != null && !street.trim().isEmpty()) sb.append(street.trim());
        if (barangay != null && !barangay.trim().isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            String b = barangay.trim();
            sb.append(b.toLowerCase(Locale.US).startsWith("brgy") || b.toLowerCase(Locale.US).startsWith("barangay") ? b : "Brgy. " + b);
        }
        if (city != null && !city.trim().isEmpty()) { if (sb.length() > 0) sb.append(", "); sb.append(city.trim()); }
        if (province != null && !province.trim().isEmpty()) { if (sb.length() > 0) sb.append(", "); sb.append(province.trim()); }
        return sb.toString();
    }

    /** The address parts that are stored for a user. */
    public static JSONObject addressMetadata(String street, String barangayName, String barangayCode,
                                             String city, String province, String region) throws org.json.JSONException {
        String fullAddress = buildFullAddress(street, barangayName, city, province);
        JSONObject o = new JSONObject();
        o.put("street", street != null ? street : "");
        o.put("barangay", barangayName != null ? barangayName : "");
        o.put("psgc_code", dbPsgc(barangayCode));                           // the ONE barangay field (10-digit PSGC)
        o.put("city", city != null ? city : "");
        o.put("province", province != null ? province : "");
        o.put("region", region != null ? region : "");
        o.put("address", fullAddress);
        o.put("current_address", fullAddress);
        return o;
    }

    /**
     * Syncs the address everywhere: profiles (psgc_code, current_address, plus any other
     * address columns that exist) AND the Auth metadata (keeps street/city/province/region).
     * The callback reports the profiles result.
     */
    public static void syncUserAddressToSupabase(String userId, String street, String barangayName, String barangayCode,
                                                 String city, String province, String region, Callback callback) {
        try {
            JSONObject meta = addressMetadata(street, barangayName, barangayCode, city, province, region);
            updateUserMetadata(meta, new Callback() {
                @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    android.util.Log.e("SupabaseAuth", "metadata update failed: " + e.getMessage());
                }
                @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    android.util.Log.d("SupabaseAuth", "metadata update: HTTP " + response.code());
                    response.close();
                }
            });
            JSONObject profileFields = new JSONObject(meta.toString());
            profileFields.remove("address"); // current_address is the profiles column
            updateProfile(userId, profileFields, callback);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Fetches the signed-in user's row from public.profiles (with the user's JWT). */
    public static void fetchUserProfileFromSupabase(String userId, Callback callback) {
        withFreshToken(token -> {
            Request request = getAuthenticatedBuilder("/rest/v1/profiles?id=eq." + userId + "&select=*", token)
                    .get()
                    .build();
            client.newCall(request).enqueue(callback);
        });
    }

    // ====================================================================
    // REPORTS (public.reports) and REQUESTS (public.requests)
    // ====================================================================

    /**
     * The ONE psgc_code format used everywhere (app, web portal, database): the 10-digit PSGC code
     * from psgc.cloud. Old 9-digit codes are converted. The database also converts on every write
     * and stamps the code from the user's profile, so this is just to send the right value up front.
     */
    public static String dbPsgc(String psgcCode) {
        if (psgcCode == null || psgcCode.trim().isEmpty()) return "";
        String modern = PSGCClient.toModernCode(psgcCode);
        return modern.isEmpty() ? psgcCode.trim() : modern;
    }

    /**
     * Inserts a complaint / incident into public.reports (the table the web admin reads).
     * user_id and psgc_code are set by the database from the signed-in user's profile.
     * @param category "Complaint" for barangay reports, "Incident" for disasters / emergencies
     * @param priority "low" | "medium" | "high"
     * @param latitude / longitude the pinned map location, or null if the user didn't pin one
     * @param locationLabel readable address of the pin / typed location (shown to officials)
     */
    public static void submitReportToSupabase(String userId, String psgcCode, String category, String title, String description,
                                              String priority, String photoUrl, Double latitude, Double longitude,
                                              String locationLabel, Callback callback) {
        try {
            JSONObject f = new JSONObject();
            if (userId != null && !userId.isEmpty()) f.put("user_id", userId);
            String code = dbPsgc(psgcCode);
            if (!code.isEmpty()) f.put("psgc_code", code);
            f.put("title", title);
            f.put("description", description);
            f.put("category", category);
            f.put("priority", priority != null ? priority.toLowerCase(Locale.US) : "medium");
            f.put("status", "pending");
            if (photoUrl != null && !photoUrl.isEmpty()) f.put("photo_url", photoUrl);
            if (latitude != null && longitude != null) {
                f.put("latitude", latitude.doubleValue());
                f.put("longitude", longitude.doubleValue());
            }
            if (locationLabel != null && !locationLabel.trim().isEmpty()) f.put("location_label", locationLabel.trim());
            authedWrite("POST", "/rest/v1/reports", f, callback);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void submitReportToSupabase(String userId, String psgcCode, String category, String title, String description,
                                              String priority, String photoUrl, Callback callback) {
        submitReportToSupabase(userId, psgcCode, category, title, description, priority, photoUrl, null, null, null, callback);
    }

    /**
     * Reads the current status of the user's own submissions (so History shows what the barangay did on the web).
     * table = "reports" or "requests". RLS only returns rows that belong to the signed-in user.
     */
    public static void fetchSubmissionStatuses(String table, List<String> ids, Callback callback) {
        if (ids == null || ids.isEmpty()) return;
        StringBuilder in = new StringBuilder();
        for (String id : ids) {
            if (id == null || !id.matches("[0-9A-Za-z-]+")) continue;
            if (in.length() > 0) in.append(',');
            in.append(id);
        }
        if (in.length() == 0) return;
        String columns = "requests".equals(table) ? "id,status,pickup_date,admin_remarks" : "id,status";
        String endpoint = "/rest/v1/" + table + "?select=" + columns + "&id=in.(" + in + ")";
        withFreshToken(token -> {
            if (token == null) return;
            Request request = getAuthenticatedBuilder(endpoint, token).get().build();
            client.newCall(request).enqueue(callback);
        });
    }

    /** Reads the "sub" (user id) claim from a JWT, "" if not available. */
    private static String jwtSub(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return "";
            byte[] decoded = android.util.Base64.decode(parts[1],
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
            return new JSONObject(new String(decoded, StandardCharsets.UTF_8)).optString("sub", "");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Inserts a document / barangay ID request into public.requests.
     *
     * RLS on requests only allows the insert when
     *   user_id   = auth.uid()        (the id inside the JWT) AND
     *   psgc_code = get_user_psgc()   (the psgc_code stored in the user's profiles row)
     * so both values are taken from the server (JWT + profiles) right before inserting,
     * instead of from the form / local prefs which may use a different code format.
     */
    public static void submitRequestToSupabase(String userId, String psgcCode, String category, String title, String description,
                                               String locationAddress, double lat, double lng, String attachmentUrl, Callback callback) {
        withFreshToken(token -> {
            if (token == null || token.isEmpty()) {
                lastWriteError = "Not signed in - please sign in again.";
                failCallback(callback, getAuthenticatedBuilder("/rest/v1/requests", null).build(), lastWriteError);
                return;
            }
            String uid = jwtSub(token);
            if (uid.isEmpty()) uid = (userId != null && !userId.isEmpty()) ? userId : getUserId();
            final String finalUid = uid;

            Request profileReq = getAuthenticatedBuilder("/rest/v1/profiles?id=eq." + finalUid + "&select=psgc_code", token).get().build();
            client.newCall(profileReq).enqueue(new Callback() {
                @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    insertRequest(finalUid, dbPsgc(psgcCode), category, title, description, locationAddress, lat, lng, attachmentUrl, token, callback);
                }
                @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    String body = readBody(response);
                    String profilePsgc = "";
                    try {
                        JSONArray arr = new JSONArray(body);
                        if (arr.length() > 0) profilePsgc = arr.getJSONObject(0).optString("psgc_code", "");
                        if ("null".equals(profilePsgc)) profilePsgc = "";
                    } catch (Exception ignored) {}
                    if (profilePsgc.isEmpty()) {
                        android.util.Log.w("SupabaseWrite", "profiles.psgc_code is empty for " + finalUid
                                + " - requests RLS will reject the insert until the profile has a barangay");
                    }
                    insertRequest(finalUid, profilePsgc.isEmpty() ? dbPsgc(psgcCode) : profilePsgc,
                            category, title, description, locationAddress, lat, lng, attachmentUrl, token, callback);
                }
            });
        });
    }

    private static void insertRequest(String uid, String psgc, String category, String title, String description,
                                      String locationAddress, double lat, double lng, String attachmentUrl,
                                      String token, Callback callback) {
        try {
            JSONObject f = new JSONObject();
            if (uid != null && !uid.isEmpty()) f.put("user_id", uid);
            f.put("psgc_code", psgc);
            f.put("category", category); // 'document', 'barangay_id'
            f.put("status", "pending");
            f.put("title", title);
            f.put("description", description);
            f.put("location_address", locationAddress);
            f.put("latitude", lat);
            f.put("longitude", lng);
            android.util.Log.d("SupabaseWrite", "requests insert: user_id=" + uid + " psgc_code=" + psgc);
            resilientWrite("POST", "/rest/v1/requests", f, token, callback, 0);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void submitRequestToSupabase(String userId, String psgcCode, String category, String title, String description,
                                               String locationAddress, double lat, double lng, Callback callback) {
        submitRequestToSupabase(userId, psgcCode, category, title, description, locationAddress, lat, lng, null, callback);
    }

    /** Edits a submitted row (e.g. fixing a typo while still pending). table = "reports" or "requests". */
    public static void updateRequest(String table, String rowId, JSONObject fields, Callback callback) {
        authedWrite("PATCH", "/rest/v1/" + table + "?id=eq." + rowId, fields, callback);
    }

    // ====================================================================
    // AUTH (anon key)
    // ====================================================================

    /**
     * Sign up via Supabase Auth. All profile fields (incl. street / barangay / city / province / region)
     * go into user_metadata; the Auth trigger creates the public.profiles row from it.
     */
    public static void signUpUser(String email, String password, JSONObject userData, Callback callback) {
        try {
            JSONObject bodyJson = new JSONObject();
            bodyJson.put("email", email);
            bodyJson.put("password", password);
            bodyJson.put("data", userData); // -> raw_user_meta_data

            Request request = anonBuilder("/auth/v1/signup")
                    .post(RequestBody.create(bodyJson.toString(), JSON))
                    .build();
            client.newCall(request).enqueue(callback);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Sign in with email or PH mobile number + password. */
    public static void signInUser(String emailOrPhone, String password, Callback callback) {
        try {
            JSONObject bodyJson = new JSONObject();
            bodyJson.put("password", password);

            if (emailOrPhone.contains("@")) {
                bodyJson.put("email", emailOrPhone);
            } else {
                String phoneDigits = emailOrPhone.replaceAll("[^0-9]", "");
                if (phoneDigits.startsWith("0")) phoneDigits = phoneDigits.substring(1);
                if (!phoneDigits.startsWith("63") && phoneDigits.length() == 10) phoneDigits = "63" + phoneDigits;
                bodyJson.put("phone", "+" + phoneDigits);
            }

            Request request = anonBuilder("/auth/v1/token?grant_type=password")
                    .post(RequestBody.create(bodyJson.toString(), JSON))
                    .build();
            client.newCall(request).enqueue(callback);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ====================================================================
    // ANNOUNCEMENTS
    // ====================================================================

    /** Announcements for the user's barangay, matching both the 9-digit and 10-digit PSGC code (plus global ones). */
    public static void fetchAnnouncementsFromSupabase(String psgcCode, Callback callback) {
        try {
            String endpoint = "/rest/v1/announcements?select=*&order=created_at.desc";
            if (psgcCode != null && !psgcCode.isEmpty()) {
                java.util.LinkedHashSet<String> codes = new java.util.LinkedHashSet<>();
                codes.add(psgcCode);
                String legacy = PSGCClient.toLegacyCode(psgcCode);
                if (!legacy.isEmpty()) codes.add(legacy);
                String modern = PSGCClient.toModernCode(psgcCode);
                if (!modern.isEmpty()) codes.add(modern);
                StringBuilder or = new StringBuilder();
                for (String c : codes) or.append("psgc_code.eq.").append(c).append(",");
                endpoint = "/rest/v1/announcements?select=*&or=(" + or + "psgc_code.is.null)&order=created_at.desc";
            }
            final String finalEndpoint = endpoint;
            withFreshToken(token -> {
                Request request = getAuthenticatedBuilder(finalEndpoint, token).get().build();
                client.newCall(request).enqueue(callback);
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
