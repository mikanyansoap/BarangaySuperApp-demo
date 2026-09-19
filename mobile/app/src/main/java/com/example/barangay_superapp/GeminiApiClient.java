package com.example.barangay_superapp;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;

public class GeminiApiClient {
    private static final String API_KEY = "AQ.Ab8RN6Iawgtoym2mMhXx4WROWJJfEr7k2zaoERl9MsuIRHlNJA";
    
    // Using the Gemini 1.5 Flash model for fast chat responses
    private static final String URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=" + API_KEY;
    private static final OkHttpClient client = new OkHttpClient();

    public interface ChatCallback {
        void onSuccess(String responseText);
        void onError(String errorMessage);
    }

    public static void sendMessage(String userMessage, ChatCallback callback) {
        try {
            // Build the JSON request body exactly as Gemini expects it
            JSONObject textPart = new JSONObject().put("text", userMessage);
            JSONObject partsObj = new JSONObject().put("parts", new JSONArray().put(textPart));
            JSONObject bodyJson = new JSONObject().put("contents", new JSONArray().put(partsObj));

            RequestBody body = RequestBody.create(bodyJson.toString(), MediaType.get("application/json; charset=utf-8"));
            Request request = new Request.Builder()
                    .url(URL)
                    .post(body)
                    .build();

            // Send the network request in the background
            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    callback.onError("Network error: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (response.isSuccessful() && response.body() != null) {
                        try {
                            String responseBody = response.body().string();
                            JSONObject jsonResponse = new JSONObject(responseBody);
                            
                            // Dig deep into the JSON response to extract the actual text generated
                            String aiText = jsonResponse.getJSONArray("candidates")
                                    .getJSONObject(0)
                                    .getJSONObject("content")
                                    .getJSONArray("parts")
                                    .getJSONObject(0)
                                    .getString("text");
                                    
                            callback.onSuccess(aiText.trim());
                        } catch (Exception e) {
                            callback.onError("Failed to read AI response");
                        }
                    } else {
                        callback.onError("AI Server Error: " + response.code());
                    }
                }
            });
        } catch (Exception e) {
            callback.onError(e.getMessage());
        }
    }
}
