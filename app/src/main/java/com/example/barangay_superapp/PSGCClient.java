package com.example.barangay_superapp;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
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
            this.name = name;
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
                        JSONArray array = new JSONArray(response.body().string());
                        List<LocationItem> provinces = new ArrayList<>();
                        for (int i = 0; i < array.length(); i++) {
                            JSONObject obj = array.getJSONObject(i);
                            provinces.add(new LocationItem(obj.getString("name"), obj.getString("code")));
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
                        JSONArray array = new JSONArray(response.body().string());
                        List<LocationItem> items = new ArrayList<>();
                        for (int i = 0; i < array.length(); i++) {
                            JSONObject obj = array.getJSONObject(i);
                            items.add(new LocationItem(obj.getString("name"), obj.getString("code")));
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
                        JSONArray array = new JSONArray(response.body().string());
                        List<LocationItem> items = new ArrayList<>();
                        for (int i = 0; i < array.length(); i++) {
                            JSONObject obj = array.getJSONObject(i);
                            items.add(new LocationItem(obj.getString("name"), obj.getString("code")));
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
