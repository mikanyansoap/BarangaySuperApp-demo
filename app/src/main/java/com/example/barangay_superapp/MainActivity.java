package com.example.barangay_superapp;

import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences("AppSession", MODE_PRIVATE);
        boolean isLoggedIn = prefs.getBoolean("IS_LOGGED_IN", false);

        Intent intent = new Intent(MainActivity.this, PreviewActivity.class);
        intent.putExtra("LAYOUT_ID", isLoggedIn ? R.layout.dashboard : R.layout.starting);
        startActivity(intent);
        finish();
    }
}