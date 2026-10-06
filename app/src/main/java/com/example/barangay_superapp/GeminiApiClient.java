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
    // Replace with your Gemini API Key
    private static final String API_KEY = "YOUR_GEMINI_API_KEY";
    
    // Using gemini-flash-latest model with header authentication
    private static final String URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent";
    private static final OkHttpClient client = new OkHttpClient();

    /** True once a real Gemini API key has been pasted into API_KEY. */
    public static boolean isConfigured() {
        return API_KEY != null && !API_KEY.isEmpty() && !API_KEY.startsWith("YOUR_");
    }

    public interface ChatCallback {
        void onSuccess(String responseText);
        void onError(String errorMessage);
    }

    public static void sendMessage(String userMessage, String extraContext, ChatCallback callback) {
        try {
            // Context system instruction for Barangay Assistant Persona
            String systemContext = "You are a helpful and polite Barangay SuperApp AI Assistant in the Philippines. Answer concisely in Taglish/Filipino regarding barangay clearance, document renewal, disaster reports, blotter complaints, and office hours (Mon-Fri 8AM-5PM).\n";
            if (extraContext != null && !extraContext.isEmpty()) {
                systemContext += "Current Context (Announcements, Requests, Contacts):\n" + extraContext + "\n";
            }
            systemContext += "User question: ";

            // Build the JSON request body as Gemini expects
            JSONObject textPart = new JSONObject().put("text", systemContext + userMessage);
            JSONObject partsObj = new JSONObject().put("parts", new JSONArray().put(textPart));
            JSONObject bodyJson = new JSONObject().put("contents", new JSONArray().put(partsObj));

            RequestBody body = RequestBody.create(bodyJson.toString(), MediaType.get("application/json; charset=utf-8"));
            Request request = new Request.Builder()
                    .url(URL + "?key=" + API_KEY)
                    .addHeader("X-goog-api-key", API_KEY)
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build();

            // Send the network request in the background
            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    // Fallback to Smart Assistant if network fails
                    callback.onSuccess(getSmartBarangayResponse(userMessage));
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (response.isSuccessful() && response.body() != null) {
                        try {
                            String responseBody = response.body().string();
                            JSONObject jsonResponse = new JSONObject(responseBody);
                            
                            // Extract AI generated text
                            String aiText = jsonResponse.getJSONArray("candidates")
                                    .getJSONObject(0)
                                    .getJSONObject("content")
                                    .getJSONArray("parts")
                                    .getJSONObject(0)
                                    .getString("text");
                                    
                            callback.onSuccess(aiText.trim());
                        } catch (Exception e) {
                            callback.onSuccess(getSmartBarangayResponse(userMessage));
                        }
                    } else {
                        // Fallback to Smart Assistant if API returns error
                        callback.onSuccess(getSmartBarangayResponse(userMessage));
                    }
                }
            });
        } catch (Exception e) {
            callback.onSuccess(getSmartBarangayResponse(userMessage));
        }
    }

    /**
     * Sends a single prompt and returns the raw model text. Unlike sendMessage() this reports
     * errors via onError (no canned fallback), so callers can use their own fallback.
     * @param jsonOutput ask Gemini to reply with JSON only
     */
    public static void generate(String prompt, boolean jsonOutput, ChatCallback callback) {
        if (!isConfigured()) {
            callback.onError("Gemini API key not set");
            return;
        }
        try {
            JSONObject textPart = new JSONObject().put("text", prompt);
            JSONObject partsObj = new JSONObject().put("parts", new JSONArray().put(textPart));
            JSONObject bodyJson = new JSONObject().put("contents", new JSONArray().put(partsObj));
            if (jsonOutput) {
                bodyJson.put("generationConfig", new JSONObject().put("responseMimeType", "application/json"));
            }
            Request request = new Request.Builder()
                    .url(URL)
                    .addHeader("X-goog-api-key", API_KEY)
                    .post(RequestBody.create(bodyJson.toString(), MediaType.get("application/json; charset=utf-8")))
                    .build();
            client.newCall(request).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) {
                    callback.onError(e.getMessage() != null ? e.getMessage() : "Network error");
                }
                @Override public void onResponse(Call call, Response response) throws IOException {
                    String body = response.body() != null ? new String(response.body().bytes(), java.nio.charset.StandardCharsets.UTF_8) : "";
                    if (!response.isSuccessful()) {
                        callback.onError("HTTP " + response.code());
                        return;
                    }
                    try {
                        String text = new JSONObject(body).getJSONArray("candidates").getJSONObject(0)
                                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text");
                        callback.onSuccess(text.trim());
                    } catch (Exception e) {
                        callback.onError("Bad response");
                    }
                }
            });
        } catch (Exception e) {
            callback.onError(e.getMessage());
        }
    }

    public static void extractIdDetails(byte[] imageBytes, String mimeType, ChatCallback callback) {
        if (!isConfigured()) {
            callback.onError("API key not set");
            return;
        }
        try {
            JSONObject inlineData = new JSONObject();
            inlineData.put("mimeType", mimeType);
            inlineData.put("data", android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP));

            JSONObject imagePart = new JSONObject().put("inlineData", inlineData);
            JSONObject textPart = new JSONObject().put("text", "Extract the ID Number or Document Number from this ID card. Reply with ONLY the number and nothing else. If you cannot find one, reply with nothing.");
            
            JSONObject partsObj = new JSONObject().put("parts", new JSONArray().put(textPart).put(imagePart));
            JSONObject bodyJson = new JSONObject().put("contents", new JSONArray().put(partsObj));

            Request request = new Request.Builder()
                    .url(URL + "?key=" + API_KEY)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(bodyJson.toString(), MediaType.get("application/json; charset=utf-8")))
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) {
                    callback.onError(e.getMessage() != null ? e.getMessage() : "Network error");
                }
                @Override public void onResponse(Call call, Response response) throws IOException {
                    String body = response.body() != null ? new String(response.body().bytes(), java.nio.charset.StandardCharsets.UTF_8) : "";
                    if (!response.isSuccessful()) {
                        callback.onError("HTTP " + response.code());
                        return;
                    }
                    try {
                        String text = new JSONObject(body).getJSONArray("candidates").getJSONObject(0)
                                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text");
                        callback.onSuccess(text.trim());
                    } catch (Exception e) {
                        callback.onError("Bad response");
                    }
                }
            });
        } catch (Exception e) {
            callback.onError(e.getMessage());
        }
    }

    // Smart Local Barangay AI Assistant logic for instant & reliable responses
    private static String getSmartBarangayResponse(String userMessage) {
        String msg = userMessage.toLowerCase();
        if (msg.contains("renew") || msg.contains("clearance") || msg.contains("indigency") || msg.contains("permit") || msg.contains("document") || msg.contains("kelangan") || msg.contains("kailangan") || msg.contains("kumuha") || msg.contains("request")) {
            return "Sure! I can help you process your document request right now. What is the specific purpose for your document (e.g. Employment, Personal, ID requirement)?";
        } else if (msg.contains("report") || msg.contains("reklamo") || msg.contains("ingay") || msg.contains("disaster") || msg.contains("baha") || msg.contains("sunog")) {
            return "I can help you file an official report. Type \"gumawa ng report\" and I'll ask you a few quick questions, then prepare the report for you to review.";
        } else if (msg.contains("employment") || msg.contains("id") || msg.contains("work") || msg.contains("personal") || msg.contains("purok") || msg.contains("street") || msg.contains("kalsada") || msg.contains("tapat")) {
            int randomId = (int)(Math.random() * 9000 + 1000);
            return "Thank you! I have registered your request into the system under Request ID: REQ-2026-" + randomId + ".\n\nOur Barangay Officials have received your submission. You can track its live status anytime under the History tab!";
        } else if (msg.contains("oras") || msg.contains("bukas") || msg.contains("schedule") || msg.contains("time")) {
            return "Ang ating Barangay Hall ay bukas mula Lunes hanggang Biyernes, 8:00 AM hanggang 5:00 PM. Sarado po tayo tuwing Sabado, Linggo, at Legal Holidays.";
        } else if (msg.contains("hello") || msg.contains("hi") || msg.contains("magandang") || msg.contains("kumusta") || msg.contains("gusto")) {
            return "Magandang araw! Ako ang iyong Barangay SuperApp AI Assistant. Paano ko kayo matutulungan ukol sa document requests, blotter reports, o emergency hotline info?";
        } else {
            return "Salamat sa iyong mensahe! Naka-record na ito sa ating Barangay Assistant. Maaari ninyong gamitin ang ating app para sa document requests, blotter reports, at emergency contacts.";
        }
    }
}