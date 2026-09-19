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
import java.util.List;

public class PSGCClient {
    // We use this reliable open-source mirror of the Philippine Standard Geographic Code (PSGC) API
    private static final String BASE_URL = "https://psgc.gitlab.io/api";
    private static final OkHttpClient client = new OkHttpClient();

    public interface LocationCallback {
        void onSuccess(List<String> names);
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
                        List<String> provinces = new ArrayList<>();
                        for (int i = 0; i < array.length(); i++) {
                            provinces.add(array.getJSONObject(i).getString("name"));
                        }
                        
                        // Add Metro Manila manually since it's a region (NCR) and not returned in the /provinces API endpoint
                        if (!provinces.contains("Metro Manila")) {
                            provinces.add("Metro Manila");
                        }
                        
                        // Sort alphabetically for better UX
                        Collections.sort(provinces);
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
}
