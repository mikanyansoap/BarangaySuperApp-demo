package com.example.barangay_superapp;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.json.JSONObject;

public class SupabaseClient {
    // Deduced the Supabase Project URL based on your Supabase dashboard link!
    public static final String SUPABASE_URL = "https://iglrczxuzljczcibvoif.supabase.co"; 
    
    public static final String SUPABASE_PUBLIC_KEY = "sb_publishable_eKmPItxbga4MB9Rn2JuMJw_04jCCvGE";
    
    private static final OkHttpClient client = new OkHttpClient();

    public static Request.Builder getAuthenticatedBuilder(String endpoint) {
        return new Request.Builder()
                .url(SUPABASE_URL + endpoint)
                .addHeader("apikey", SUPABASE_PUBLIC_KEY)
                .addHeader("Authorization", "Bearer " + SUPABASE_PUBLIC_KEY)
                .addHeader("Content-Type", "application/json");
    }

    // Handles User Sign Up via Supabase Auth REST API
    public static void signUpUser(String email, String password, JSONObject userData, Callback callback) {
        try {
            JSONObject bodyJson = new JSONObject();
            bodyJson.put("email", email);
            bodyJson.put("password", password);
            bodyJson.put("data", userData); // Supabase puts this inside 'user_metadata'

            RequestBody body = RequestBody.create(bodyJson.toString(), MediaType.get("application/json; charset=utf-8"));
            Request request = getAuthenticatedBuilder("/auth/v1/signup")
                    .post(body)
                    .build();

            client.newCall(request).enqueue(callback);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Handles User Sign In via Supabase Auth REST API
    public static void signInUser(String email, String password, Callback callback) {
        try {
            JSONObject bodyJson = new JSONObject();
            bodyJson.put("email", email);
            bodyJson.put("password", password);

            RequestBody body = RequestBody.create(bodyJson.toString(), MediaType.get("application/json; charset=utf-8"));
            // Supabase login endpoint:
            Request request = getAuthenticatedBuilder("/auth/v1/token?grant_type=password")
                    .post(body)
                    .build();

            client.newCall(request).enqueue(callback);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
