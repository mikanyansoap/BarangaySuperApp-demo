package com.example.barangay_superapp;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.json.JSONObject;

import java.util.Locale;

public class SupabaseClient {
    // The REAL Supabase Project URL
    public static final String SUPABASE_URL = "https://wjrabyrmhymwtvcjywea.supabase.co"; 
    
    public static final String SUPABASE_PUBLIC_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6IndqcmFieXJtaHltd3R2Y2p5d2VhIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODk3MTk3MTksImV4cCI6MjEwNTI5NTcxOX0.4Eat43MG-bqiofQWvab0iEnzDlLsPiVbiMQLen_e-5o";
    
    private static final OkHttpClient client = new OkHttpClient();

    public static Request.Builder getAuthenticatedBuilder(String endpoint) {
        return new Request.Builder()
                .url(SUPABASE_URL + endpoint)
                .addHeader("apikey", SUPABASE_PUBLIC_KEY)
                .addHeader("Authorization", "Bearer " + SUPABASE_PUBLIC_KEY)
                .addHeader("Content-Type", "application/json");
    }

    // Converts MM/DD/YYYY to YYYY-MM-DD for PostgreSQL DATE column
    public static String formatToIsoDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) return "2000-01-01";
        try {
            String[] parts = dateStr.split("/");
            if (parts.length == 3) {
                int month = Integer.parseInt(parts[0]);
                int day = Integer.parseInt(parts[1]);
                int year = Integer.parseInt(parts[2]);
                return String.format(Locale.US, "%04d-%02d-%02d", year, month, day);
            }
        } catch (Exception ignored) {}
        return "2000-01-01";
    }

    // Inserts user record directly into public.users table via Supabase Database REST API
    public static void insertUserToPublicTable(String userId, JSONObject userData, String password, Callback callback) {
        try {
            JSONObject bodyJson = new JSONObject();
            bodyJson.put("id", userId);
            bodyJson.put("first_name", userData.optString("first_name", "Resident"));
            bodyJson.put("middle_name", userData.optString("middle_name", ""));
            bodyJson.put("last_name", userData.optString("last_name", "Resident"));
            bodyJson.put("suffix", userData.optString("suffix", ""));
            bodyJson.put("mobile_number", userData.optString("mobile_number", "+639000000000"));
            bodyJson.put("email", userData.optString("email", ""));
            bodyJson.put("password_hash", password);
            bodyJson.put("province", userData.optString("province", "Metro Manila (NCR)"));
            bodyJson.put("city", userData.optString("city", "Taguig City"));
            bodyJson.put("barangay_id", userData.optString("barangay_id", "137607010"));
            bodyJson.put("address", userData.optString("address", "Brgy. Napindan"));
            bodyJson.put("id_type", userData.optString("id_type", "Passport"));
            
            String rawDob = userData.optString("dob", "10/25/2007");
            bodyJson.put("birthdate", formatToIsoDate(rawDob));
            
            String rawGender = userData.optString("gender", "Female");
            bodyJson.put("sex", rawGender.isEmpty() ? "Female" : rawGender);
            
            String rawCivil = userData.optString("civil_status", "Single");
            bodyJson.put("marital_status", rawCivil.isEmpty() ? "Single" : rawCivil);
            
            bodyJson.put("region", userData.optString("region", "NCR"));
            bodyJson.put("verification_status", userData.optString("verification_status", "approved"));
            bodyJson.put("role", "resident");

            RequestBody body = RequestBody.create(bodyJson.toString(), MediaType.get("application/json; charset=utf-8"));
            Request request = getAuthenticatedBuilder("/rest/v1/users")
                    .addHeader("Prefer", "return=minimal")
                    .post(body)
                    .build();

            client.newCall(request).enqueue(callback);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Inserts a new request directly into Supabase requests table
    public static void submitRequestToSupabase(String userId, String psgcCode, String category, String title, String description, String locationAddress, double lat, double lng, Callback callback) {
        try {
            JSONObject bodyJson = new JSONObject();
            if (userId != null && !userId.isEmpty()) {
                bodyJson.put("user_id", userId);
            }
            bodyJson.put("psgc_code", psgcCode != null && !psgcCode.isEmpty() ? psgcCode : "137607010");
            bodyJson.put("category", category); // 'document', 'report', 'disaster', 'barangay_id'
            bodyJson.put("status", "pending");
            bodyJson.put("priority", "medium");
            bodyJson.put("title", title);
            bodyJson.put("description", description);
            bodyJson.put("location_address", locationAddress);
            bodyJson.put("latitude", lat);
            bodyJson.put("longitude", lng);

            RequestBody body = RequestBody.create(bodyJson.toString(), MediaType.get("application/json; charset=utf-8"));
            Request request = getAuthenticatedBuilder("/rest/v1/requests")
                    .addHeader("Prefer", "return=minimal")
                    .post(body)
                    .build();

            client.newCall(request).enqueue(callback);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Handles User Sign Up via Supabase Auth REST API
    public static void signUpUser(String email, String password, JSONObject userData, Callback callback) {
        try {
            JSONObject bodyJson = new JSONObject();
            bodyJson.put("email", email);
            bodyJson.put("password", password);
            
            // Note: Phone is passed inside 'data' (user_metadata) so Supabase doesn't throw 
            // "Only an email address or phone number should be provided on signup" error.
            bodyJson.put("data", userData); // Supabase puts this inside 'user_metadata' / 'raw_user_meta_data'

            RequestBody body = RequestBody.create(bodyJson.toString(), MediaType.get("application/json; charset=utf-8"));
            Request request = getAuthenticatedBuilder("/auth/v1/signup")
                    .post(body)
                    .build();

            client.newCall(request).enqueue(callback);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Handles User Sign In via Supabase Auth REST API (Supports both Email and Phone Number!)
    public static void signInUser(String emailOrPhone, String password, Callback callback) {
        try {
            JSONObject bodyJson = new JSONObject();
            bodyJson.put("password", password);

            if (emailOrPhone.contains("@")) {
                bodyJson.put("email", emailOrPhone);
            } else {
                // Format phone number to +63XXXXXXXXXX
                String phoneDigits = emailOrPhone.replaceAll("[^0-9]", "");
                if (phoneDigits.startsWith("0")) {
                    phoneDigits = phoneDigits.substring(1);
                }
                if (!phoneDigits.startsWith("63") && phoneDigits.length() == 10) {
                    phoneDigits = "63" + phoneDigits;
                }
                String formattedPhone = "+" + phoneDigits;
                bodyJson.put("phone", formattedPhone);
            }

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
