package com.example.barangay_superapp;

import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.os.Bundle;
import androidx.cardview.widget.CardView;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.starting);

        // Hook up the main buttons to navigate to the respective forms
        CardView btnCreateAccount = findViewById(R.id.btnCreateAccount);
        CardView btnSignIn = findViewById(R.id.btnSignIn);

        if (btnCreateAccount != null) {
            btnCreateAccount.setOnClickListener(v -> {
                Intent intent = new Intent(MainActivity.this, PreviewActivity.class);
                intent.putExtra("LAYOUT_ID", R.layout.sign_up);
                startActivity(intent);
            });
        }

        if (btnSignIn != null) {
            btnSignIn.setOnClickListener(v -> {
                Intent intent = new Intent(MainActivity.this, PreviewActivity.class);
                intent.putExtra("LAYOUT_ID", R.layout.sign_in);
                startActivity(intent);
            });
        }
    }
}