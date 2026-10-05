package com.example.barangay_superapp;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class PSGCClient {
    // Upgraded to psgc.cloud API as requested
    private static final String BASE_URL = "https://psgc.cloud/api";
    private static final OkHttpClient client = new OkHttpClient();

    public static class LocationItem {
        public String name;
        public String code;
        public LocationItem(String name, String code) {
            this.name = TextFix.fix(name);
            this.code = code;
        }
        @Override
        public String toString() {
            return name;
        }
    }

    public interface LocationCallback {
        void onSuccess(List<LocationItem> items);
        void onError(String error);
    }

    /**
     * Reads the response body as UTF-8 bytes. We do NOT use response.body().string() because if the
     * server omits/mislabels the charset, "\u00F1" (e.g. Para\u00F1aque, Las Pi\u00F1as) gets garbled.
     */
    private static String readUtf8(Response response) throws IOException {
        byte[] bytes = response.body().bytes();
        return TextFix.fix(new String(bytes, StandardCharsets.UTF_8));
    }

    /** Region name from the first 2 digits of any 10-digit PSGC code (province / city / barangay). */
    public static String regionNameFromCode(String psgcCode) {
        if (psgcCode == null || psgcCode.length() < 2) return "";
        switch (psgcCode.substring(0, 2)) {
            case "01": return "Region I (Ilocos Region)";
            case "02": return "Region II (Cagayan Valley)";
            case "03": return "Region III (Central Luzon)";
            case "04": return "Region IV-A (CALABARZON)";
            case "05": return "Region V (Bicol Region)";
            case "06": return "Region VI (Western Visayas)";
            case "07": return "Region VII (Central Visayas)";
            case "08": return "Region VIII (Eastern Visayas)";
            case "09": return "Region IX (Zamboanga Peninsula)";
            case "10": return "Region X (Northern Mindanao)";
            case "11": return "Region XI (Davao Region)";
            case "12": return "Region XII (SOCCSKSARGEN)";
            case "13": return "NCR";
            case "14": return "CAR";
            case "15":
            case "19": return "BARMM";
            case "16": return "Region XIII (Caraga)";
            case "17": return "MIMAROPA Region";
            case "18": return "NIR (Negros Island Region)";
            default: return "";
        }
    }

    public interface SingleLocationCallback {
        void onSuccess(LocationItem item);
        void onError(String error);
    }

    /** Looks up a single barangay by its PSGC code (used to restore the barangay name on login). */
    public static void fetchBarangayByCode(String barangayCode, SingleLocationCallback callback) {
        String modern = toModernCode(barangayCode);
        if (!modern.isEmpty()) barangayCode = modern;
        final String lookupCode = barangayCode;
        Request request = new Request.Builder().url(BASE_URL + "/barangays/" + barangayCode).build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        String body = readUtf8(response).trim();
                        JSONObject obj = body.startsWith("[") ? new JSONArray(body).getJSONObject(0) : new JSONObject(body);
                        callback.onSuccess(new LocationItem(obj.getString("name"), obj.optString("code", lookupCode)));
                    } catch (Exception e) {
                        callback.onError("Parsing error");
                    }
                } else {
                    callback.onError("Failed to fetch barangay");
                }
            }
        });
    }

    // ====================================================================
    // 10-digit (psgc.cloud / PSA 2023+) <-> 9-digit (old PSGC, used in your Supabase data) codes
    // e.g. Napindan, Taguig: 1381500010 <-> 137607010
    // ====================================================================

    /** New NCR city prefix (first 5 digits, Pateros = 7) -> old 6-digit prefix. */
    private static final java.util.Map<String, String> NCR_NEW_TO_OLD = new java.util.HashMap<>();
    static {
        NCR_NEW_TO_OLD.put("13801", "137501"); // Caloocan
        NCR_NEW_TO_OLD.put("13802", "137601"); // Las Pinas
        NCR_NEW_TO_OLD.put("13803", "137602"); // Makati
        NCR_NEW_TO_OLD.put("13804", "137502"); // Malabon
        NCR_NEW_TO_OLD.put("13805", "137401"); // Mandaluyong
        NCR_NEW_TO_OLD.put("13807", "137402"); // Marikina
        NCR_NEW_TO_OLD.put("13808", "137603"); // Muntinlupa
        NCR_NEW_TO_OLD.put("13809", "137503"); // Navotas
        NCR_NEW_TO_OLD.put("13810", "137604"); // Paranaque
        NCR_NEW_TO_OLD.put("13811", "137605"); // Pasay
        NCR_NEW_TO_OLD.put("13812", "137403"); // Pasig
        NCR_NEW_TO_OLD.put("13813", "137404"); // Quezon City
        NCR_NEW_TO_OLD.put("13814", "137405"); // San Juan
        NCR_NEW_TO_OLD.put("13815", "137607"); // Taguig
        NCR_NEW_TO_OLD.put("13816", "137504"); // Valenzuela
    }

    private static boolean digits(String s, int len) {
        return s != null && s.length() == len && s.chars().allMatch(Character::isDigit);
    }

    /** 10-digit PSGC -> 9-digit PSGC. Returns the input if it's already 9 digits, "" if it can't be mapped. */
    public static String toLegacyCode(String code) {
        if (code == null) return "";
        code = code.trim();
        if (digits(code, 9)) return code;
        if (!digits(code, 10)) return "";
        String brgy = code.substring(7);
        if (code.startsWith("13")) {
            if (code.startsWith("1381701")) return "137606" + brgy;                      // Pateros
            if (code.startsWith("13806")) return "1339" + code.substring(5, 7) + brgy;   // Manila districts
            String old = NCR_NEW_TO_OLD.get(code.substring(0, 5));
            return old != null ? old + brgy : "";
        }
        // Provinces: RR 0PP MM BBB -> RR PP MM BBB
        if (code.charAt(2) == '0') return code.substring(0, 2) + code.substring(3);
        return ""; // independent cities / new regions were re-coded; no reliable old equivalent
    }

    /** 9-digit PSGC -> 10-digit PSGC. Returns the input if it's already 10 digits, "" if it can't be mapped. */
    public static String toModernCode(String code) {
        if (code == null) return "";
        code = code.trim();
        if (digits(code, 10)) return code;
        if (!digits(code, 9)) return "";
        String brgy = code.substring(6);
        if (code.startsWith("13")) {
            String prefix6 = code.substring(0, 6);
            if (prefix6.equals("137606")) return "1381701" + brgy;
            if (code.startsWith("1339")) return "13806" + code.substring(4, 6) + brgy;
            for (java.util.Map.Entry<String, String> e : NCR_NEW_TO_OLD.entrySet()) {
                if (e.getValue().equals(prefix6)) return e.getKey() + "00" + brgy;
            }
            return "";
        }
        return code.substring(0, 2) + "0" + code.substring(2);
    }

    public static void fetchProvinces(LocationCallback callback) {
        Request request = new Request.Builder().url(BASE_URL + "/provinces").build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        JSONArray array = new JSONArray(readUtf8(response));
                        List<LocationItem> provinces = new ArrayList<>();
                        for (int i = 0; i < array.length(); i++) {
                            JSONObject obj = array.getJSONObject(i);
                            provinces.add(new LocationItem(TextFix.fix(obj.getString("name")), obj.getString("code")));
                        }
                        
                        // Add Metro Manila manually since it's a region (NCR)
                        provinces.add(new LocationItem("Metro Manila (NCR)", "1300000000"));
                        
                        // Sort alphabetically for better UX
                        Collections.sort(provinces, (a, b) -> a.name.compareToIgnoreCase(b.name));
                        callback.onSuccess(provinces);
                    } catch (Exception e) {
                        callback.onError("Parsing error");
                    }
                } else {
                    callback.onError("Failed to fetch provinces");
                }
            }
        });
    }

    public static void fetchCities(String provinceCode, LocationCallback callback) {
        String url;
        if ("1300000000".equals(provinceCode)) {
            // NCR cities are accessed via regions endpoint
            url = BASE_URL + "/regions/" + provinceCode + "/cities-municipalities";
        } else {
            url = BASE_URL + "/provinces/" + provinceCode + "/cities-municipalities";
        }

        Request request = new Request.Builder().url(url).build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        JSONArray array = new JSONArray(readUtf8(response));
                        List<LocationItem> items = new ArrayList<>();
                        for (int i = 0; i < array.length(); i++) {
                            JSONObject obj = array.getJSONObject(i);
                            items.add(new LocationItem(TextFix.fix(obj.getString("name")), obj.getString("code")));
                        }
                        Collections.sort(items, (a, b) -> a.name.compareToIgnoreCase(b.name));
                        callback.onSuccess(items);
                    } catch (Exception e) {
                        callback.onError("Parsing error");
                    }
                } else {
                    callback.onError("Failed to fetch cities");
                }
            }
        });
    }

    public static void fetchBarangays(String cityCode, LocationCallback callback) {
        Request request = new Request.Builder().url(BASE_URL + "/cities-municipalities/" + cityCode + "/barangays").build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        JSONArray array = new JSONArray(readUtf8(response));
                        List<LocationItem> items = new ArrayList<>();
                        for (int i = 0; i < array.length(); i++) {
                            JSONObject obj = array.getJSONObject(i);
                            items.add(new LocationItem(TextFix.fix(obj.getString("name")), obj.getString("code")));
                        }
                        Collections.sort(items, (a, b) -> a.name.compareToIgnoreCase(b.name));
                        callback.onSuccess(items);
                    } catch (Exception e) {
                        callback.onError("Parsing error");
                    }
                } else {
                    callback.onError("Failed to fetch barangays");
                }
            }
        });
    }
}
