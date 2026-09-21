package com.example.barangay_superapp;

import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import androidx.cardview.widget.CardView;

public class MainActivity extends AppCompatActivity {

    // List of all the layouts we created
    private final int[] layouts = {
            R.layout.sign_in,
            R.layout.sign_up,
            R.layout.account_review_ntf,
            R.layout.dashboard,
            R.layout.services,
            R.layout.announcements,
            R.layout.calendar,
            R.layout.request_history,
            R.layout.notifs,
            R.layout.report_form,
            R.layout.request_form,
            R.layout.report_disaster_form,
            R.layout.request_brgy_id,
            R.layout.emergency_contacts_list,
            R.layout.ai_chatbot
    };

    // The friendly names for the dropdown
    private final String[] layoutNames = {
            "Sign In",
            "Sign Up",
            "Account Review",
            "Dashboard",
            "Services",
            "Announcements",
            "Calendar",
            "My Requests History",
            "Notifications",
            "Report Form",
            "Request Document Form",
            "Report Disaster Form",
            "Request Brgy ID Form",
            "Emergency Contacts",
            "AI Assistant Chatbot"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.starting);

        // Set up the temporary developer dropdown menu
        Spinner spinnerDev = findViewById(R.id.spinnerDev);
        CardView btnGoDev = findViewById(R.id.btnGoDev);

        if (spinnerDev != null && btnGoDev != null) {
            // Bind our layout names to the dropdown
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, layoutNames);
            spinnerDev.setAdapter(adapter);

            // When "Go" is clicked, check what is selected and launch it
            btnGoDev.setOnClickListener(v -> {
                int selectedIndex = spinnerDev.getSelectedItemPosition();
                int layoutId = layouts[selectedIndex];

                Intent intent = new Intent(MainActivity.this, PreviewActivity.class);
                intent.putExtra("LAYOUT_ID", layoutId);
                startActivity(intent);
            });
        }
    }
}