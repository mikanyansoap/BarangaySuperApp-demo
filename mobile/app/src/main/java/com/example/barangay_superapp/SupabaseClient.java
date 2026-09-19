package com.example.barangay_superapp;

import okhttp3.OkHttpClient;
import okhttp3.Request;

public class SupabaseClient {
    // IMPORTANT: We still need your project URL (e.g., https://xyz.supabase.co)
    public static final String SUPABASE_URL = "YOUR_SUPABASE_URL_HERE"; 
    
    // It is completely safe to use the PUBLIC / PUBLISHABLE key in the app
    public static final String SUPABASE_PUBLIC_KEY = "sb_publishable_eKmPItxbga4MB9Rn2JuMJw_04jCCvGE";
    
    // NOTE: DO NOT PUT YOUR SECRET KEY IN THE APP!
    // The secret key bypasses all security rules. If a hacker decompiles your app and finds the secret key,
    // they can delete your entire database. The secret key is only for server-side code (like Node.js).
    
    private static final OkHttpClient client = new OkHttpClient();

    // A helper method to easily build Supabase network requests
    public static Request.Builder getAuthenticatedBuilder(String endpoint) {
        return new Request.Builder()
                .url(SUPABASE_URL + endpoint)
                .addHeader("apikey", SUPABASE_PUBLIC_KEY)
                .addHeader("Authorization", "Bearer " + SUPABASE_PUBLIC_KEY)
                .addHeader("Content-Type", "application/json");
    }
}
