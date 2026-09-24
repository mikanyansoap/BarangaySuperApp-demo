package com.example.barangay_superapp;

import android.app.DatePickerDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Pair;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import android.net.Uri;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.Toast;

import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

public class PreviewActivity extends AppCompatActivity {
    
    private int selectedDay = -1;
    private String currentHistoryFilter = "All";
    private String selectedBarangayCode = "";
    private String selectedBarangayName = "";
    
    private final List<JSONObject> allRequests = new ArrayList<>();
    private final List<JSONObject> filteredRequests = new ArrayList<>();
    private final List<JSONObject> allAnnouncements = new ArrayList<>();
    
    private TextView currentUploadTextView;
    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri selectedFileUri = result.getData().getData();
                    if (selectedFileUri != null) {
                        if (currentUploadTextView != null) {
                            currentUploadTextView.setText("File Selected: " + selectedFileUri.getLastPathSegment());
                            currentUploadTextView.setTextColor(Color.parseColor("#1B5E20")); // Green text for success
                        } else {
                            // Update Profile Picture
                            SharedPreferences prefs = getSharedPreferences("AppSession", MODE_PRIVATE);
                            prefs.edit().putString("USER_AVATAR_URI", selectedFileUri.toString()).apply();
                            
                            ImageView ivProfileImage = findViewById(R.id.ivProfileImage);
                            if (ivProfileImage != null) {
                                ivProfileImage.setImageURI(selectedFileUri);
                            }
                            Toast.makeText(this, "Profile Picture Updated!", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            }
    );

    // ChatMessage model for interactive prompts
    public static class ChatMessage {
        public boolean isUser;
        public String text;
        public boolean hasPrompt;
        public boolean promptHandled;

        public ChatMessage(boolean isUser, String text, boolean hasPrompt) {
            this.isUser = isUser;
            this.text = text;
            this.hasPrompt = hasPrompt;
            this.promptHandled = false;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Retrieve persistent session state
        SharedPreferences prefs = getSharedPreferences("AppSession", MODE_PRIVATE);
        boolean isLoggedIn = prefs.getBoolean("IS_LOGGED_IN", false);
        int defaultLayout = isLoggedIn ? R.layout.dashboard : R.layout.starting;

        int layoutId = getIntent().getIntExtra("LAYOUT_ID", defaultLayout);
        setContentView(layoutId);

        if (layoutId == R.layout.request_history || layoutId == R.layout.calendar) {
            loadMockData();
        }

        if (layoutId == R.layout.account_review_ntf) {
            String selectedBarangay = getIntent().getStringExtra("SELECTED_BARANGAY");
            if (selectedBarangay != null && !selectedBarangay.isEmpty()) {
                TextView tvReviewDesc = findViewById(R.id.tvReviewDesc);
                if (tvReviewDesc != null) {
                    tvReviewDesc.setText(selectedBarangay + " checks new registrations against their records before activating an account — usually within 1–2 working days.");
                }
            }

            CardView btnBackToSignIn = findViewById(R.id.btnBackToSignIn);
            if (btnBackToSignIn != null) {
                btnBackToSignIn.setOnClickListener(v -> launchPreview(R.layout.sign_in));
            }
        }

        // ====================================================================
        // GLOBAL GO BACK LOGIC (For screens without bottom nav)
        // ====================================================================
        CardView btnGoBack = findViewById(R.id.btnGoBack);
        if (btnGoBack != null) {
            btnGoBack.setOnClickListener(v -> finish()); // Closes current activity and returns to previous
        }

        // ====================================================================
        // DATE OF BIRTH PICKER LOGIC (Sign Up & Brgy ID Form)
        // ====================================================================
        if (layoutId == R.layout.sign_up || layoutId == R.layout.request_brgy_id) {
            EditText etFormDOB = findViewById(R.id.etFormDOB);
            if (etFormDOB != null) {
                etFormDOB.setOnClickListener(v -> {
                    Calendar calendar = Calendar.getInstance();
                    int year = calendar.get(Calendar.YEAR);
                    int month = calendar.get(Calendar.MONTH);
                    int day = calendar.get(Calendar.DAY_OF_MONTH);

                    DatePickerDialog datePickerDialog = new DatePickerDialog(
                            PreviewActivity.this,
                            (view, selectedYear, selectedMonth, selectedDay) -> {
                                // Format and set the text
                                String date = (selectedMonth + 1) + "/" + selectedDay + "/" + selectedYear;
                                etFormDOB.setText(date);
                            }, year, month, day);

                    // CRITICAL: This restricts the maximum selectable date to today!
                    datePickerDialog.getDatePicker().setMaxDate(System.currentTimeMillis());
                    
                    datePickerDialog.show();
                });
            }
        }

        // ====================================================================
        // FILE UPLOAD LOGIC (For forms with upload buttons)
        // ====================================================================
        // Sign Up Form
        if (layoutId == R.layout.sign_up) {
            CardView cardUploadId = findViewById(R.id.cardUploadId);
            TextView tvUploadIdText = findViewById(R.id.tvUploadIdText);
            if (cardUploadId != null && tvUploadIdText != null) {
                cardUploadId.setOnClickListener(v -> {
                    currentUploadTextView = tvUploadIdText;
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.setType("image/*");
                    filePickerLauncher.launch(intent);
                });
            }
        }

        // Brgy ID Form
        if (layoutId == R.layout.request_brgy_id) {
            CardView cardScanId = findViewById(R.id.cardScanId);
            TextView tvScanIdSub = findViewById(R.id.tvScanIdSub);
            if (cardScanId != null && tvScanIdSub != null) {
                cardScanId.setOnClickListener(v -> {
                    currentUploadTextView = tvScanIdSub;
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.setType("image/*");
                    filePickerLauncher.launch(intent);
                });
            }

            CardView cardSelfie = findViewById(R.id.cardSelfie);
            TextView tvSelfieSub = findViewById(R.id.tvSelfieSub);
            if (cardSelfie != null && tvSelfieSub != null) {
                cardSelfie.setOnClickListener(v -> {
                    currentUploadTextView = tvSelfieSub;
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.setType("image/*");
                    filePickerLauncher.launch(intent);
                });
            }

            CardView cardUploadBrgyId = findViewById(R.id.cardUploadBrgyId);
            TextView tvBrgyIdFile = findViewById(R.id.tvBrgyIdFile);
            if (cardUploadBrgyId != null && tvBrgyIdFile != null) {
                cardUploadBrgyId.setOnClickListener(v -> {
                    currentUploadTextView = tvBrgyIdFile;
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.setType("*/*");
                    String[] mimetypes = {"image/*", "application/pdf"};
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, mimetypes);
                    filePickerLauncher.launch(intent);
                });
            }

            CardView cardUploadProof = findViewById(R.id.cardUploadProof);
            TextView tvProofFile = findViewById(R.id.tvProofFile);
            if (cardUploadProof != null && tvProofFile != null) {
                cardUploadProof.setOnClickListener(v -> {
                    currentUploadTextView = tvProofFile;
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.setType("*/*");
                    String[] mimetypes = {"image/*", "application/pdf"};
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, mimetypes);
                    filePickerLauncher.launch(intent);
                });
            }

            CardView btnSubmitBrgyId = findViewById(R.id.btnSubmitBrgyId);
            if (btnSubmitBrgyId != null) {
                btnSubmitBrgyId.setOnClickListener(v -> {
                    Toast.makeText(this, "Application Submitted Successfully!", Toast.LENGTH_SHORT).show();
                    launchPreview(R.layout.account_review_ntf);
                });
            }
        }

        // Request Form
        if (layoutId == R.layout.request_form) {
            Spinner spinnerDoc = findViewById(R.id.spinnerDocumentType);
            if (spinnerDoc != null) {
                ArrayAdapter<String> adapterDoc = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"Barangay Clearance", "Certificate of Residency", "Certificate of Indigency", "Business Clearance", "Others"});
                spinnerDoc.setAdapter(adapterDoc);
            }

            CardView btnUploadProof = findViewById(R.id.btnUploadProof);
            TextView tvUploadProofText = findViewById(R.id.tvUploadProofText);
            if (btnUploadProof != null && tvUploadProofText != null) {
                btnUploadProof.setOnClickListener(v -> {
                    currentUploadTextView = tvUploadProofText;
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.setType("*/*");
                    String[] mimetypes = {"image/*", "application/pdf"};
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, mimetypes);
                    filePickerLauncher.launch(intent);
                });
            }

            CardView btnSubmitRequest = findViewById(R.id.btnSubmitRequest);
            if (btnSubmitRequest != null) {
                btnSubmitRequest.setOnClickListener(v -> {
                    EditText etPurpose = findViewById(R.id.etDocumentPurpose);
                    String docType = spinnerDoc != null && spinnerDoc.getSelectedItem() != null ? spinnerDoc.getSelectedItem().toString() : "Barangay Clearance";
                    String purpose = etPurpose != null ? etPurpose.getText().toString().trim() : "";

                    if (purpose.isEmpty()) {
                        Toast.makeText(this, "Please state the purpose of your request", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    String reqId = "REQ-" + (int)(Math.random() * 9000 + 1000);
                    String details = "• Request ID: " + reqId + "\n• Document Type: " + docType + "\n• Purpose: " + purpose + "\n• Applicant: " + prefs.getString("USER_NAME", "Resident") + "\n• Status: Pending Review by Barangay Staff";
                    saveNewUserRequest("Document Request", docType + " (" + reqId + ")", getCurrentFormattedDateTime(), "Pending", "document", details);

                    // Submit to Supabase REST API!
                    SupabaseClient.submitRequestToSupabase(null, selectedBarangayCode, "document", "Document Request: " + docType, details, prefs.getString("USER_ADDRESS", "Barangay Area"), 14.5995, 120.9842, new Callback() {
                        @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {}
                        @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {}
                    });

                    Toast.makeText(this, "Document Request Submitted! (" + reqId + ")", Toast.LENGTH_LONG).show();
                    launchPreview(R.layout.request_history);
                });
            }
        }

        // Report Form
        if (layoutId == R.layout.report_form) {
            Spinner spinnerCat = findViewById(R.id.spinnerReportCategory);
            if (spinnerCat != null) {
                ArrayAdapter<String> adapterCat = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"Disturbance / Noise Complaint", "Delinquency / Vandalism", "Blocked Drainage / Flooding", "Garbage Collection Issue", "Streetlight Repair", "Others"});
                spinnerCat.setAdapter(adapterCat);
            }

            CardView btnGetCurrentLocation = findViewById(R.id.btnGetCurrentLocation);
            TextView tvLocationAddress = findViewById(R.id.tvLocationAddress);
            if (btnGetCurrentLocation != null && tvLocationAddress != null) {
                btnGetCurrentLocation.setOnClickListener(v -> showMapPickerDialog(tvLocationAddress));
            }

            CardView btnAddPhoto = findViewById(R.id.btnAddPhoto);
            TextView tvAddPhotoText = findViewById(R.id.tvAddPhotoText);
            if (btnAddPhoto != null && tvAddPhotoText != null) {
                btnAddPhoto.setOnClickListener(v -> {
                    currentUploadTextView = tvAddPhotoText;
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.setType("image/*");
                    filePickerLauncher.launch(intent);
                });
            }

            CardView btnSubmitReport = findViewById(R.id.btnSubmitReport);
            if (btnSubmitReport != null) {
                btnSubmitReport.setOnClickListener(v -> {
                    EditText etDetails = findViewById(R.id.etReportDescription);
                    String cat = spinnerCat != null && spinnerCat.getSelectedItem() != null ? spinnerCat.getSelectedItem().toString() : "Blotter Report";
                    String details = etDetails != null ? etDetails.getText().toString().trim() : "";

                    if (details.isEmpty()) {
                        Toast.makeText(this, "Please provide description details for your report", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    String reqId = "REP-" + (int)(Math.random() * 9000 + 1000);
                    String fullDetails = "• Report ID: " + reqId + "\n• Category: " + cat + "\n• Description: " + details + "\n• Location: Purok 3 (Lat: 14.5995, Lng: 120.9842)\n• Reported By: " + prefs.getString("USER_NAME", "Resident") + "\n• Status: Pending Review by Barangay Officers";
                    saveNewUserRequest("Report", cat + " (" + reqId + ")", getCurrentFormattedDateTime(), "Pending", "report", fullDetails);

                    // Submit to Supabase REST API!
                    SupabaseClient.submitRequestToSupabase(null, selectedBarangayCode, "report", "Report: " + cat, fullDetails, "Purok 3", 14.5995, 120.9842, new Callback() {
                        @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {}
                        @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {}
                    });

                    Toast.makeText(this, "Report Submitted Successfully! (" + reqId + ")", Toast.LENGTH_LONG).show();
                    launchPreview(R.layout.request_history);
                });
            }
        }

        // Report Disaster Form
        if (layoutId == R.layout.report_disaster_form) {
            Spinner spinnerDisaster = findViewById(R.id.spinnerDisasterType);
            if (spinnerDisaster != null) {
                ArrayAdapter<String> adapterDisaster = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"Flooding", "Fire Emergency", "Landslide", "Typhoon / Strong Winds", "Medical Emergency", "Others"});
                spinnerDisaster.setAdapter(adapterDisaster);
            }

            CardView cardUploadDisasterMedia = findViewById(R.id.cardUploadDisasterMedia);
            TextView tvDisasterMediaFile = findViewById(R.id.tvDisasterMediaFile);
            if (cardUploadDisasterMedia != null && tvDisasterMediaFile != null) {
                cardUploadDisasterMedia.setOnClickListener(v -> {
                    currentUploadTextView = tvDisasterMediaFile;
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.setType("*/*");
                    String[] mimetypes = {"image/*", "video/*"};
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, mimetypes);
                    filePickerLauncher.launch(intent);
                });
            }

            CardView btnSubmitDisaster = findViewById(R.id.btnSubmitDisaster);
            if (btnSubmitDisaster != null) {
                btnSubmitDisaster.setOnClickListener(v -> {
                    EditText etLoc = findViewById(R.id.etDisasterLocation);
                    EditText etDet = findViewById(R.id.etDisasterDetails);
                    String loc = etLoc != null ? etLoc.getText().toString().trim() : "";
                    String det = etDet != null ? etDet.getText().toString().trim() : "";

                    if (loc.isEmpty() && det.isEmpty()) {
                        Toast.makeText(this, "Please provide the location or details of the emergency", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    String disasterType = spinnerDisaster != null && spinnerDisaster.getSelectedItem() != null ? spinnerDisaster.getSelectedItem().toString() : "Emergency Incident";
                    String reqId = "DIS-" + (int)(Math.random() * 9000 + 1000);
                    String fullDetails = "• Disaster ID: " + reqId + "\n• Incident Type: " + disasterType + "\n• Location: " + (loc.isEmpty() ? "Barangay Area (Lat: 14.5995, Lng: 120.9842)" : loc) + "\n• Details: " + (det.isEmpty() ? "Emergency assistance requested" : det) + "\n• Reported By: " + prefs.getString("USER_NAME", "Resident") + "\n• Status: Pending Emergency Response";
                    saveNewUserRequest("Disaster Report", disasterType + " (" + reqId + ")", getCurrentFormattedDateTime(), "Pending", "disaster", fullDetails);

                    // Submit to Supabase REST API!
                    SupabaseClient.submitRequestToSupabase(null, selectedBarangayCode, "disaster", "Disaster Report: " + disasterType, fullDetails, loc, 14.5995, 120.9842, new Callback() {
                        @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {}
                        @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {}
                    });

                    Toast.makeText(this, "Disaster Incident Reported! (" + reqId + ")", Toast.LENGTH_LONG).show();
                    launchPreview(R.layout.request_history);
                });
            }
        }

        // ====================================================================
        // SIGN UP & BRGY ID FORM LOGIC (Spinners + Supabase Integration)
        // ====================================================================
        if (layoutId == R.layout.sign_up || layoutId == R.layout.request_brgy_id) {
            // Populate Spinners to prevent "empty" lists!
            Spinner spinnerGender = findViewById(R.id.spinnerGender);
            if (spinnerGender != null) {
                ArrayAdapter<String> adapterGender = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"Male", "Female", "Other", "Prefer not to say"});
                spinnerGender.setAdapter(adapterGender);
            }

            Spinner spinnerIdType = findViewById(R.id.spinnerIdType);
            if (spinnerIdType != null) {
                ArrayAdapter<String> adapterId = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"Passport", "Driver's License", "UMID", "PhilSys ID", "Voter's ID", "Postal ID", "Others"});
                spinnerIdType.setAdapter(adapterId);
            }

            Spinner spinnerCivilStatus = findViewById(R.id.spinnerCivilStatus);
            if (spinnerCivilStatus != null) {
                ArrayAdapter<String> adapterCivil = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"Single", "Married", "Widowed", "Separated"});
                spinnerCivilStatus.setAdapter(adapterCivil);
            }

            // Location Cascading Dropdowns
            AutoCompleteTextView spinnerProvince = findViewById(R.id.spinnerProvince);
            AutoCompleteTextView spinnerCity = findViewById(R.id.spinnerCity);
            AutoCompleteTextView spinnerBarangay = findViewById(R.id.spinnerBarangay);

            if (spinnerProvince != null && spinnerCity != null && spinnerBarangay != null) {
                PSGCClient.fetchProvinces(new PSGCClient.LocationCallback() {
                    @Override
                    public void onSuccess(List<PSGCClient.LocationItem> items) {
                        runOnUiThread(() -> {
                            ArrayAdapter<PSGCClient.LocationItem> adapterProv = new ArrayAdapter<>(PreviewActivity.this, android.R.layout.simple_dropdown_item_1line, items);
                            spinnerProvince.setAdapter(adapterProv);
                            spinnerProvince.setOnClickListener(v -> spinnerProvince.showDropDown());
                            spinnerProvince.setOnFocusChangeListener((v, hasFocus) -> { if (hasFocus) spinnerProvince.showDropDown(); });
                        });
                    }

                    @Override
                    public void onError(String error) {
                        // Fallback list if the internet/API fails
                        runOnUiThread(() -> {
                            List<PSGCClient.LocationItem> fallback = new ArrayList<>();
                            fallback.add(new PSGCClient.LocationItem("Cavite (API Failed to Load)", "0402100000"));
                            fallback.add(new PSGCClient.LocationItem("Metro Manila", "1300000000"));
                            ArrayAdapter<PSGCClient.LocationItem> fallbackProv = new ArrayAdapter<>(PreviewActivity.this, android.R.layout.simple_dropdown_item_1line, fallback);
                            spinnerProvince.setAdapter(fallbackProv);
                            spinnerProvince.setOnClickListener(v -> spinnerProvince.showDropDown());
                            spinnerProvince.setOnFocusChangeListener((v, hasFocus) -> { if (hasFocus) spinnerProvince.showDropDown(); });
                        });
                    }
                });

                spinnerProvince.setOnItemClickListener((parent, view, position, id) -> {
                    PSGCClient.LocationItem selectedProv = (PSGCClient.LocationItem) parent.getItemAtPosition(position);
                    spinnerCity.setText("");
                    spinnerBarangay.setText("");
                    
                    PSGCClient.fetchCities(selectedProv.code, new PSGCClient.LocationCallback() {
                        @Override
                        public void onSuccess(List<PSGCClient.LocationItem> items) {
                            runOnUiThread(() -> {
                                ArrayAdapter<PSGCClient.LocationItem> adapterCity = new ArrayAdapter<>(PreviewActivity.this, android.R.layout.simple_dropdown_item_1line, items);
                                spinnerCity.setAdapter(adapterCity);
                                spinnerCity.setOnClickListener(v -> spinnerCity.showDropDown());
                                spinnerCity.setOnFocusChangeListener((v, hasFocus) -> { if (hasFocus) spinnerCity.showDropDown(); });
                            });
                        }
                        @Override
                        public void onError(String error) {}
                    });
                });

                spinnerCity.setOnItemClickListener((parent, view, position, id) -> {
                    PSGCClient.LocationItem selectedCity = (PSGCClient.LocationItem) parent.getItemAtPosition(position);
                    spinnerBarangay.setText("");
                    selectedBarangayCode = "";
                    selectedBarangayName = "";
                    
                    PSGCClient.fetchBarangays(selectedCity.code, new PSGCClient.LocationCallback() {
                        @Override
                        public void onSuccess(List<PSGCClient.LocationItem> items) {
                            runOnUiThread(() -> {
                                ArrayAdapter<PSGCClient.LocationItem> adapterBrgy = new ArrayAdapter<>(PreviewActivity.this, android.R.layout.simple_dropdown_item_1line, items);
                                spinnerBarangay.setAdapter(adapterBrgy);
                                spinnerBarangay.setOnClickListener(v -> spinnerBarangay.showDropDown());
                                spinnerBarangay.setOnFocusChangeListener((v, hasFocus) -> { if (hasFocus) spinnerBarangay.showDropDown(); });
                            });
                        }
                        @Override
                        public void onError(String error) {}
                    });
                });

                spinnerBarangay.setOnItemClickListener((parent, view, position, id) -> {
                    Object item = parent.getItemAtPosition(position);
                    if (item instanceof PSGCClient.LocationItem) {
                        PSGCClient.LocationItem selectedBrgy = (PSGCClient.LocationItem) item;
                        selectedBarangayCode = selectedBrgy.code;
                        selectedBarangayName = selectedBrgy.name;
                    }
                });
            }
        }

        if (layoutId == R.layout.sign_up) {
            CardView btnCreateAccount = findViewById(R.id.btnCreateAccount);
            EditText etFirstName = findViewById(R.id.etFirstName);
            EditText etLastName = findViewById(R.id.etLastName);
            EditText etMobileNumber = findViewById(R.id.etMobileNumber);
            EditText etEmail = findViewById(R.id.etEmail);
            EditText etSignUpPassword = findViewById(R.id.etSignUpPassword);
            CheckBox cbTerms = findViewById(R.id.cbTerms);
            TextView tvSignInRedirect = findViewById(R.id.tvSignInRedirect);

            // Automatically strip leading zero if user accidentally types it
            if (etMobileNumber != null) {
                etMobileNumber.addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {}
                    @Override
                    public void afterTextChanged(Editable s) {
                        if (s.length() > 0 && s.charAt(0) == '0') {
                            s.delete(0, 1);
                        }
                    }
                });
            }

            if (tvSignInRedirect != null) {
                tvSignInRedirect.setOnClickListener(v -> {
                    Intent intent = new Intent(PreviewActivity.this, PreviewActivity.class);
                    intent.putExtra("LAYOUT_ID", R.layout.sign_in);
                    startActivity(intent);
                    finish();
                });
            }

            if (btnCreateAccount != null) {
                btnCreateAccount.setOnClickListener(v -> {
                    String email = etEmail != null ? etEmail.getText().toString().trim() : "";
                    String password = etSignUpPassword != null ? etSignUpPassword.getText().toString() : "";
                    String firstName = etFirstName != null ? etFirstName.getText().toString().trim() : "";
                    String lastName = etLastName != null ? etLastName.getText().toString().trim() : "";
                    String phone = etMobileNumber != null ? etMobileNumber.getText().toString().trim() : "";

                    // Additional fields
                    EditText etMiddleName = findViewById(R.id.etMiddleName);
                    EditText etSuffix = findViewById(R.id.etSuffix);
                    EditText etFormDOB = findViewById(R.id.etFormDOB);
                    Spinner spinnerGender = findViewById(R.id.spinnerGender);
                    Spinner spinnerCivilStatus = findViewById(R.id.spinnerCivilStatus);
                    Spinner spinnerIdType = findViewById(R.id.spinnerIdType);
                    AutoCompleteTextView spinnerProvince = findViewById(R.id.spinnerProvince);
                    AutoCompleteTextView spinnerCity = findViewById(R.id.spinnerCity);
                    AutoCompleteTextView spinnerBarangay = findViewById(R.id.spinnerBarangay);

                    String middleName = etMiddleName != null ? etMiddleName.getText().toString().trim() : "";
                    String suffix = etSuffix != null ? etSuffix.getText().toString().trim() : "";
                    String dob = etFormDOB != null ? etFormDOB.getText().toString().trim() : "";
                    String gender = spinnerGender != null && spinnerGender.getSelectedItem() != null ? spinnerGender.getSelectedItem().toString() : "";
                    String civilStatus = spinnerCivilStatus != null && spinnerCivilStatus.getSelectedItem() != null ? spinnerCivilStatus.getSelectedItem().toString() : "";
                    String idType = spinnerIdType != null && spinnerIdType.getSelectedItem() != null ? spinnerIdType.getSelectedItem().toString() : "";
                    String province = spinnerProvince != null ? spinnerProvince.getText().toString().trim() : "";
                    String city = spinnerCity != null ? spinnerCity.getText().toString().trim() : "";
                    String barangay = spinnerBarangay != null ? spinnerBarangay.getText().toString().trim() : "";

                    // Simple frontend validation
                    if (email.isEmpty() || password.isEmpty() || firstName.isEmpty() || lastName.isEmpty() || phone.isEmpty()) {
                        Toast.makeText(this, "Please fill in all required fields", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (phone.length() != 10) {
                        Toast.makeText(this, "Mobile number must be exactly 10 digits (e.g. 9123456789)", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (cbTerms != null && !cbTerms.isChecked()) {
                        Toast.makeText(this, "Please agree to the Terms and Conditions", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    Toast.makeText(this, "Creating account...", Toast.LENGTH_SHORT).show();

                    try {
                        String formattedPhone = "+63" + phone;
                        String constructedFullName = (firstName + (middleName.isEmpty() ? "" : " " + middleName) + " " + lastName + (suffix.isEmpty() ? "" : " " + suffix)).trim();
                        String brgyIdToSave = !selectedBarangayCode.isEmpty() ? selectedBarangayCode : (barangay.isEmpty() ? "137607010" : barangay);
                        String brgyNameToSave = !selectedBarangayName.isEmpty() ? selectedBarangayName : (barangay.isEmpty() ? "Napindan" : barangay);
                        String fullAddress = "Brgy. " + brgyNameToSave + (city.isEmpty() ? "" : ", " + city);

                        prefs.edit().putString("USER_NAME", constructedFullName).apply();
                        prefs.edit().putString("USER_PHONE", formattedPhone).apply();
                        prefs.edit().putString("USER_ADDRESS", fullAddress).apply();
                        prefs.edit().putString("USER_EMAIL", email).apply();

                        // Package metadata matching new Supabase public.users schema
                        JSONObject userData = new JSONObject();
                        userData.put("first_name", firstName);
                        userData.put("middle_name", middleName);
                        userData.put("last_name", lastName);
                        userData.put("suffix", suffix);
                        userData.put("full_name", constructedFullName);
                        userData.put("mobile_number", formattedPhone);
                        userData.put("phone", formattedPhone);
                        userData.put("email", email);
                        userData.put("birthdate", dob);
                        userData.put("dob", dob);
                        userData.put("sex", gender);
                        userData.put("gender", gender);
                        userData.put("marital_status", civilStatus);
                        userData.put("civil_status", civilStatus);
                        userData.put("region", province.contains("NCR") || province.contains("Metro Manila") ? "NCR" : "Region IV-A");
                        userData.put("province", province.isEmpty() ? "Metro Manila (NCR)" : province);
                        userData.put("city", city.isEmpty() ? "Taguig City" : city);
                        userData.put("barangay_id", brgyIdToSave);
                        userData.put("address", fullAddress);
                        userData.put("id_type", idType);
                        userData.put("verification_status", "approved");
                        userData.put("role", "resident");

                        // Trigger the Supabase network call!
                        SupabaseClient.signUpUser(email, password, userData, new Callback() {
                            @Override
                            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                                runOnUiThread(() -> Toast.makeText(PreviewActivity.this, "Network Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
                            }

                            @Override
                            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                                String responseBody = response.body() != null ? response.body().string() : "";
                                runOnUiThread(() -> {
                                    if (response.isSuccessful()) {
                                        // Save persistent session data ONLY on successful Supabase backend registration!
                                        prefs.edit().putString("USER_NAME", constructedFullName)
                                                    .putString("USER_PHONE", formattedPhone)
                                                    .putString("USER_ADDRESS", fullAddress)
                                                    .putString("USER_EMAIL", email)
                                                    .putBoolean("IS_LOGGED_IN", true)
                                                    .apply();

                                        // Also insert user directly to Supabase public.users database table!
                                        try {
                                            JSONObject json = new JSONObject(responseBody);
                                            String userId = json.optString("id", "");
                                            if (userId.isEmpty() && json.has("user")) {
                                                userId = json.getJSONObject("user").optString("id", "");
                                            }
                                            if (!userId.isEmpty()) {
                                                SupabaseClient.insertUserToPublicTable(userId, userData, password, new Callback() {
                                                    @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {}
                                                    @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {}
                                                });
                                            }
                                        } catch (Exception ignored) {}

                                        // SUCCESS! 
                                        Toast.makeText(PreviewActivity.this, "Account Created Successfully!", Toast.LENGTH_LONG).show();
                                        
                                        // Transition to Account Review notification screen
                                        Intent intent = new Intent(PreviewActivity.this, PreviewActivity.class);
                                        intent.putExtra("LAYOUT_ID", R.layout.account_review_ntf);
                                        startActivity(intent);
                                        finish();
                                    } else {
                                        // HANDLE SUPABASE BACKEND REGISTRATION FAILURE - DO NOT SAVE SESSION!
                                        try {
                                            JSONObject errorJson = new JSONObject(responseBody);
                                            String errorMsg = errorJson.optString("msg", errorJson.optString("message", errorJson.optString("error_description", "Registration rejected by server.")));
                                            Toast.makeText(PreviewActivity.this, "Sign Up Failed: " + errorMsg, Toast.LENGTH_LONG).show();
                                        } catch (Exception ex) {
                                            Toast.makeText(PreviewActivity.this, "Sign Up Failed on Server.", Toast.LENGTH_SHORT).show();
                                        }
                                    }
                                });
                            }
                        });
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }
        }

        // ====================================================================
        // INTERACTIVE DEMO LOGIC FOR "AI CHATBOT"
        // ====================================================================
        if (layoutId == R.layout.ai_chatbot) {
            RecyclerView rvChatMessages = findViewById(R.id.rvChatMessages);
            CardView btnSendMessage = findViewById(R.id.btnSendMessage);
            EditText etChatMessage = findViewById(R.id.etChatMessage);

            if (rvChatMessages != null && btnSendMessage != null && etChatMessage != null) {
                // List of messages
                List<ChatMessage> messages = new ArrayList<>();
                // Starting with welcome message
                messages.add(new ChatMessage(false, "Hello! I am your Barangay SuperApp Assistant. How can I help you today?", false));

                RecyclerView.Adapter<RecyclerView.ViewHolder> chatAdapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    @NonNull
                    @Override
                    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chat_message, parent, false);
                        return new RecyclerView.ViewHolder(view) {};
                    }

                    @Override
                    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                        ChatMessage msg = messages.get(position);

                        LinearLayout layoutBot = holder.itemView.findViewById(R.id.layoutBotMessage);
                        LinearLayout layoutUser = holder.itemView.findViewById(R.id.layoutUserMessage);
                        TextView tvBot = holder.itemView.findViewById(R.id.tvBotText);
                        TextView tvUser = holder.itemView.findViewById(R.id.tvUserText);
                        CardView cardUserMedia = holder.itemView.findViewById(R.id.cardUserMedia);

                        LinearLayout layoutActionButtons = holder.itemView.findViewById(R.id.layoutActionButtons);
                        CardView btnPromptYes = holder.itemView.findViewById(R.id.btnPromptYes);
                        CardView btnPromptNo = holder.itemView.findViewById(R.id.btnPromptNo);

                        if (msg.isUser) {
                            layoutBot.setVisibility(View.GONE);
                            layoutUser.setVisibility(View.VISIBLE);
                            tvUser.setText(msg.text);
                            if (cardUserMedia != null) cardUserMedia.setVisibility(View.GONE);
                        } else {
                            layoutBot.setVisibility(View.VISIBLE);
                            layoutUser.setVisibility(View.GONE);
                            tvBot.setText(msg.text);

                            // Show Yes / No Action Buttons if message has prompt and hasn't been handled yet
                            if (msg.hasPrompt && !msg.promptHandled && layoutActionButtons != null) {
                                layoutActionButtons.setVisibility(View.VISIBLE);

                                if (btnPromptYes != null) {
                                    btnPromptYes.setOnClickListener(v -> {
                                        msg.promptHandled = true;
                                        layoutActionButtons.setVisibility(View.GONE);

                                        // Add user response "Yes"
                                        messages.add(new ChatMessage(true, "Yes", false));
                                        if (rvChatMessages.getAdapter() != null) {
                                            rvChatMessages.getAdapter().notifyItemInserted(messages.size() - 1);
                                        }

                                        // Redirect to Request Document page!
                                        launchPreview(R.layout.request_form);
                                    });
                                }

                                if (btnPromptNo != null) {
                                    btnPromptNo.setOnClickListener(v -> {
                                        msg.promptHandled = true;
                                        layoutActionButtons.setVisibility(View.GONE);

                                        // Add user response "No"
                                        messages.add(new ChatMessage(true, "No", false));

                                        // Bot replies: "Alright then! I'm here ready to help."
                                        messages.add(new ChatMessage(false, "Alright then! I'm here ready to help.", false));
                                        
                                        if (rvChatMessages.getAdapter() != null) {
                                            rvChatMessages.getAdapter().notifyDataSetChanged();
                                        }
                                        rvChatMessages.scrollToPosition(messages.size() - 1);
                                    });
                                }
                            } else if (layoutActionButtons != null) {
                                layoutActionButtons.setVisibility(View.GONE);
                            }
                        }
                    }

                    @Override
                    public int getItemCount() {
                        return messages.size();
                    }
                };

                rvChatMessages.setLayoutManager(new LinearLayoutManager(this));
                rvChatMessages.setAdapter(chatAdapter);
                
                // Add click listener for sending messages
                btnSendMessage.setOnClickListener(v -> {
                    String userText = etChatMessage.getText().toString().trim();
                    if (!userText.isEmpty()) {
                        // 1. Add user message to the list
                        messages.add(new ChatMessage(true, userText, false));
                        etChatMessage.setText(""); // clear the input box
                        
                        chatAdapter.notifyItemInserted(messages.size() - 1);
                        rvChatMessages.scrollToPosition(messages.size() - 1);

                        // 2. Add temporary "Thinking..." bubble
                        int thinkingIndex = messages.size();
                        messages.add(new ChatMessage(false, "Thinking... 💭", false));
                        chatAdapter.notifyItemInserted(thinkingIndex);
                        rvChatMessages.scrollToPosition(thinkingIndex);

                        // 3. Call Gemini AI Backend!
                        GeminiApiClient.sendMessage(userText, new GeminiApiClient.ChatCallback() {
                            @Override
                            public void onSuccess(String responseText) {
                                runOnUiThread(() -> {
                                    boolean hasPrompt = responseText.toLowerCase().contains("should i do it for you") || responseText.toLowerCase().contains("request document");

                                    if (thinkingIndex < messages.size()) {
                                        messages.set(thinkingIndex, new ChatMessage(false, responseText, hasPrompt));
                                        chatAdapter.notifyItemChanged(thinkingIndex);
                                    } else {
                                        messages.add(new ChatMessage(false, responseText, hasPrompt));
                                        chatAdapter.notifyItemInserted(messages.size() - 1);
                                    }
                                    rvChatMessages.scrollToPosition(messages.size() - 1);
                                });
                            }

                            @Override
                            public void onError(String errorMessage) {
                                runOnUiThread(() -> {
                                    if (thinkingIndex < messages.size()) {
                                        messages.set(thinkingIndex, new ChatMessage(false, errorMessage, false));
                                        chatAdapter.notifyItemChanged(thinkingIndex);
                                    } else {
                                        messages.add(new ChatMessage(false, errorMessage, false));
                                        chatAdapter.notifyItemInserted(messages.size() - 1);
                                    }
                                    rvChatMessages.scrollToPosition(messages.size() - 1);
                                });
                            }
                        });
                    }
                });
            }
        }

        // ====================================================================
        // SIGN IN FORM LOGIC -> SUPABASE INTEGRATION
        // ====================================================================
        if (layoutId == R.layout.sign_in) {
            CardView btnSignIn = findViewById(R.id.btnSignIn);
            EditText etUsername = findViewById(R.id.etUsername);
            EditText etPassword = findViewById(R.id.etPassword);
            TextView tvCreateAccount = findViewById(R.id.tvCreateAccount);

            if (tvCreateAccount != null) {
                tvCreateAccount.setOnClickListener(v -> {
                    Intent intent = new Intent(PreviewActivity.this, PreviewActivity.class);
                    intent.putExtra("LAYOUT_ID", R.layout.sign_up);
                    startActivity(intent);
                    finish();
                });
            }

            if (btnSignIn != null) {
                btnSignIn.setOnClickListener(v -> {
                    String email = etUsername != null ? etUsername.getText().toString().trim() : "";
                    String password = etPassword != null ? etPassword.getText().toString() : "";

                    if (email.isEmpty() || password.isEmpty()) {
                        Toast.makeText(this, "Please enter your email and password.", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    Toast.makeText(this, "Signing in...", Toast.LENGTH_SHORT).show();

                    SupabaseClient.signInUser(email, password, new Callback() {
                        @Override
                        public void onFailure(@NonNull Call call, @NonNull IOException e) {
                            runOnUiThread(() -> Toast.makeText(PreviewActivity.this, "Network Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
                        }

                        @Override
                        public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                            String responseBody = response.body() != null ? response.body().string() : "";
                            runOnUiThread(() -> {
                                if (response.isSuccessful()) {
                                    // Save persistent session so user stays logged in!
                                    prefs.edit().putBoolean("IS_LOGGED_IN", true).apply();

                                    try {
                                        JSONObject json = new JSONObject(responseBody);
                                        if (json.has("user")) {
                                            JSONObject userObj = json.getJSONObject("user");
                                            String emailStr = userObj.optString("email", "");
                                            String phoneStr = userObj.optString("phone", "");

                                            if (!emailStr.isEmpty()) prefs.edit().putString("USER_EMAIL", emailStr).apply();
                                            if (!phoneStr.isEmpty()) prefs.edit().putString("USER_PHONE", phoneStr).apply();

                                            JSONObject metaObj = userObj.optJSONObject("user_metadata");
                                            if (metaObj != null) {
                                                String firstName = metaObj.optString("first_name", "");
                                                String lastName = metaObj.optString("last_name", "");
                                                String fullName = (firstName + " " + lastName).trim();
                                                String userAddr = metaObj.optString("address", metaObj.optString("current_address", ""));
                                                String userMob = metaObj.optString("mobile_number", metaObj.optString("phone", phoneStr));

                                                if (!fullName.isEmpty()) prefs.edit().putString("USER_NAME", fullName).apply();
                                                if (!userAddr.isEmpty()) prefs.edit().putString("USER_ADDRESS", userAddr).apply();
                                                if (!userMob.isEmpty()) prefs.edit().putString("USER_PHONE", userMob).apply();

                                                String status = metaObj.optString("verification_status", metaObj.optString("account_status", "approved"));
                                                if ("pending".equalsIgnoreCase(status) || "unapproved".equalsIgnoreCase(status)) {
                                                    Toast.makeText(PreviewActivity.this, "Account pending review by Barangay Officials.", Toast.LENGTH_LONG).show();
                                                    Intent intent = new Intent(PreviewActivity.this, PreviewActivity.class);
                                                    intent.putExtra("LAYOUT_ID", R.layout.account_review_ntf);
                                                    startActivity(intent);
                                                    finish();
                                                    return;
                                                }
                                            } else if (!emailStr.isEmpty() && emailStr.contains("@")) {
                                                prefs.edit().putString("USER_NAME", emailStr.split("@")[0]).apply();
                                            }
                                        }
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }

                                    Toast.makeText(PreviewActivity.this, "Sign In Successful!", Toast.LENGTH_LONG).show();
                                    // Transition to Dashboard
                                    Intent intent = new Intent(PreviewActivity.this, PreviewActivity.class);
                                    intent.putExtra("LAYOUT_ID", R.layout.dashboard);
                                    startActivity(intent);
                                    finish();
                                } else {
                                    // Handle Incorrect Password / Account missing
                                    try {
                                        JSONObject errorJson = new JSONObject(responseBody);
                                        String errorMsg = errorJson.optString("error_description", "Invalid login credentials.");
                                        Toast.makeText(PreviewActivity.this, "Sign In Failed: " + errorMsg, Toast.LENGTH_LONG).show();
                                    } catch (Exception ex) {
                                        Toast.makeText(PreviewActivity.this, "Sign In Failed. Please check your credentials.", Toast.LENGTH_LONG).show();
                                    }
                                }
                            });
                        }
                    });
                });
            }
        }

        // ====================================================================
        // DASHBOARD & STARTING NAVIGATION LOGIC
        // ====================================================================
        if (layoutId == R.layout.dashboard) {
            TextView tvUserName = findViewById(R.id.tvUserName);
            if (tvUserName != null) {
                String savedName = prefs.getString("USER_NAME", "Resident");
                tvUserName.setText(savedName);
            }

            CardView btnProfilePicture = findViewById(R.id.btnProfilePicture);
            if (btnProfilePicture != null) {
                btnProfilePicture.setOnClickListener(v -> launchPreview(R.layout.profile));
            }

            TextView tvWeatherDate = findViewById(R.id.tvWeatherDate);
            ImageView ivWeatherIcon = findViewById(R.id.ivWeatherIcon);
            fetchLiveWeather(tvWeatherDate, ivWeatherIcon);

            RecyclerView rvDashboardAnnouncements = findViewById(R.id.rvDashboardAnnouncements);
            if (rvDashboardAnnouncements != null && !allAnnouncements.isEmpty()) {
                RecyclerView.Adapter<RecyclerView.ViewHolder> dashAnnounceAdapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    @NonNull
                    @Override
                    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_dashboard_announcement, parent, false);
                        return new RecyclerView.ViewHolder(v) {};
                    }

                    @Override
                    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                        try {
                            JSONObject item = allAnnouncements.get(position);
                            TextView tvCategory = holder.itemView.findViewById(R.id.tvAnnouncementCategory);
                            TextView tvTitle = holder.itemView.findViewById(R.id.tvAnnouncementTitle);
                            TextView tvDate = holder.itemView.findViewById(R.id.tvAnnouncementDate);
                            View colorStrip = holder.itemView.findViewById(R.id.announcementColorStrip);

                            String cat = item.optString("category", "General");
                            String date = item.optString("date", "Recently");
                            String title = item.optString("title", "");
                            String colorHex = item.optString("categoryColorHex", "#247D76");

                            if (tvCategory != null) tvCategory.setText(cat);
                            if (tvTitle != null) tvTitle.setText(title);
                            if (tvDate != null) tvDate.setText("Posted " + date);
                            if (colorStrip != null) colorStrip.setBackgroundColor(Color.parseColor(colorHex));
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }

                    @Override
                    public int getItemCount() {
                        return allAnnouncements.size();
                    }
                };

                rvDashboardAnnouncements.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
                rvDashboardAnnouncements.setAdapter(dashAnnounceAdapter);
            }

            CardView cardDashboardReport = findViewById(R.id.cardDashboardReport);
            CardView cardDashboardRequest = findViewById(R.id.cardDashboardRequest);
            CardView cardDashboardDisaster = findViewById(R.id.cardDashboardDisaster);
            CardView cardDashboardEmergency = findViewById(R.id.cardDashboardEmergency);
            TextView btnSeeAllAnnouncements = findViewById(R.id.btnSeeAllAnnouncements);
            CardView fabAiChatbot = findViewById(R.id.fabAiChatbot);

            if (cardDashboardReport != null) cardDashboardReport.setOnClickListener(v -> launchPreview(R.layout.report_form));
            if (cardDashboardRequest != null) cardDashboardRequest.setOnClickListener(v -> launchPreview(R.layout.request_form));
            if (cardDashboardDisaster != null) cardDashboardDisaster.setOnClickListener(v -> launchPreview(R.layout.report_disaster_form));
            if (cardDashboardEmergency != null) cardDashboardEmergency.setOnClickListener(v -> launchPreview(R.layout.emergency_contacts_list));
            if (btnSeeAllAnnouncements != null) btnSeeAllAnnouncements.setOnClickListener(v -> launchPreview(R.layout.announcements));
            if (fabAiChatbot != null) fabAiChatbot.setOnClickListener(v -> launchPreview(R.layout.ai_chatbot));
        }

        if (layoutId == R.layout.starting) {
            // Reset session on starting screen so user can re-authenticate if they logged out
            prefs.edit().putBoolean("IS_LOGGED_IN", false).apply();

            CardView btnCreateAccount = findViewById(R.id.btnCreateAccount);
            CardView btnSignIn = findViewById(R.id.btnSignIn);

            if (btnCreateAccount != null) btnCreateAccount.setOnClickListener(v -> launchPreview(R.layout.sign_up));
            if (btnSignIn != null) btnSignIn.setOnClickListener(v -> launchPreview(R.layout.sign_in));
        }

        // ====================================================================
        // GLOBAL BOTTOM NAVIGATION LOGIC
        // ====================================================================
        View navHome = findViewById(R.id.navHome);
        View navHistory = findViewById(R.id.navHistory);
        View navCenterId = findViewById(R.id.navCenterId);
        View navAlerts = findViewById(R.id.navAlerts);
        View navProfile = findViewById(R.id.navProfile);

        if (navHome != null) navHome.setOnClickListener(v -> launchPreview(R.layout.dashboard));
        if (navHistory != null) navHistory.setOnClickListener(v -> launchPreview(R.layout.request_history));
        if (navCenterId != null) navCenterId.setOnClickListener(v -> launchPreview(R.layout.request_brgy_id));
        if (navAlerts != null) navAlerts.setOnClickListener(v -> launchPreview(R.layout.notifs));
        if (navProfile != null) navProfile.setOnClickListener(v -> launchPreview(R.layout.profile));

        // ====================================================================
        // PROFILE SCREEN LOGIC (Read-only fields, Editable Address, Upload Photo, Log Out)
        // ====================================================================
        if (layoutId == R.layout.profile) {
            CardView btnChangeProfilePic = findViewById(R.id.btnChangeProfilePic);
            ImageView ivProfileImage = findViewById(R.id.ivProfileImage);
            EditText etProfileName = findViewById(R.id.etProfileName);
            EditText etProfilePhone = findViewById(R.id.etProfilePhone);
            EditText etProfileEmail = findViewById(R.id.etProfileEmail);
            EditText etProfileAddress = findViewById(R.id.etProfileAddress);
            CardView btnSaveProfile = findViewById(R.id.btnSaveProfile);
            CardView btnLogOut = findViewById(R.id.btnLogOut);

            // Load saved user session data
            if (etProfileName != null) etProfileName.setText(prefs.getString("USER_NAME", "Resident"));
            if (etProfilePhone != null) etProfilePhone.setText(prefs.getString("USER_PHONE", ""));
            if (etProfileEmail != null) etProfileEmail.setText(prefs.getString("USER_EMAIL", ""));
            if (etProfileAddress != null) etProfileAddress.setText(prefs.getString("USER_ADDRESS", "Barangay San Isidro, City"));

            // Load saved avatar picture
            String savedAvatarUri = prefs.getString("USER_AVATAR_URI", null);
            if (savedAvatarUri != null && ivProfileImage != null) {
                try {
                    ivProfileImage.setImageURI(Uri.parse(savedAvatarUri));
                } catch (Exception ignored) {}
            }

            // Upload profile picture click
            if (btnChangeProfilePic != null) {
                btnChangeProfilePic.setOnClickListener(v -> {
                    currentUploadTextView = null; // signifies profile picture upload
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.setType("image/*");
                    filePickerLauncher.launch(intent);
                });
            }

            // Save address changes
            if (btnSaveProfile != null) {
                btnSaveProfile.setOnClickListener(v -> {
                    String newAddress = etProfileAddress != null ? etProfileAddress.getText().toString().trim() : "";
                    if (!newAddress.isEmpty()) prefs.edit().putString("USER_ADDRESS", newAddress).apply();

                    Toast.makeText(this, "Address Updated Successfully!", Toast.LENGTH_SHORT).show();
                    finish(); // return to dashboard
                });
            }

            // Log Out
            if (btnLogOut != null) {
                btnLogOut.setOnClickListener(v -> {
                    prefs.edit().putBoolean("IS_LOGGED_IN", false)
                                .remove("USER_NAME")
                                .remove("USER_EMAIL")
                                .remove("USER_PHONE")
                                .remove("USER_ADDRESS")
                                .apply();
                    Toast.makeText(this, "Logged Out Successfully", Toast.LENGTH_SHORT).show();
                    Intent intent = new Intent(PreviewActivity.this, PreviewActivity.class);
                    intent.putExtra("LAYOUT_ID", R.layout.sign_in);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                });
            }
        }

        // ====================================================================
        // ANNOUNCEMENTS SCREEN LOGIC
        // ====================================================================
        if (layoutId == R.layout.announcements) {
            RecyclerView rvAnnouncements = findViewById(R.id.rvAnnouncements);
            LinearLayout layoutEmptyState = findViewById(R.id.layoutEmptyState);

            CardView chipAll = findViewById(R.id.chipAnnouncementsAll);
            CardView chipHealth = findViewById(R.id.chipAnnouncementsHealth);
            CardView chipAdvisory = findViewById(R.id.chipAnnouncementsAdvisory);
            CardView chipEvent = findViewById(R.id.chipAnnouncementsEvent);

            TextView tvAll = findViewById(R.id.tvAnnouncementsAll);
            TextView tvHealth = findViewById(R.id.tvAnnouncementsHealth);
            TextView tvAdvisory = findViewById(R.id.tvAnnouncementsAdvisory);
            TextView tvEvent = findViewById(R.id.tvAnnouncementsEvent);

            List<JSONObject> filteredAnnouncements = new ArrayList<>(allAnnouncements);

            if (rvAnnouncements != null) {
                RecyclerView.Adapter<RecyclerView.ViewHolder> announceAdapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    @NonNull
                    @Override
                    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_announcement, parent, false);
                        return new RecyclerView.ViewHolder(v) {};
                    }

                    @Override
                    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                        try {
                            JSONObject item = filteredAnnouncements.get(position);
                            TextView categoryText = holder.itemView.findViewById(R.id.categoryText);
                            TextView dateText = holder.itemView.findViewById(R.id.dateText);
                            TextView titleText = holder.itemView.findViewById(R.id.titleText);
                            TextView descriptionText = holder.itemView.findViewById(R.id.descriptionText);
                            CardView categoryCard = holder.itemView.findViewById(R.id.categoryCard);
                            CardView timelineDot = holder.itemView.findViewById(R.id.timelineDot);

                            String cat = item.optString("category", "General");
                            String date = item.optString("date", "Today");
                            String title = item.optString("title", "");
                            String desc = item.optString("description", "");
                            String colorHex = item.optString("categoryColorHex", "#247D76");
                            String bgHex = item.optString("categoryBgHex", "#DFF2F0");

                            if (categoryText != null) {
                                categoryText.setText(cat);
                                categoryText.setTextColor(Color.parseColor(colorHex));
                            }
                            if (categoryCard != null) categoryCard.setCardBackgroundColor(Color.parseColor(bgHex));
                            if (timelineDot != null) timelineDot.setCardBackgroundColor(Color.parseColor(colorHex));
                            if (dateText != null) dateText.setText(date);
                            if (titleText != null) titleText.setText(title);
                            if (descriptionText != null) descriptionText.setText(desc);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }

                    @Override
                    public int getItemCount() {
                        return filteredAnnouncements.size();
                    }
                };

                rvAnnouncements.setLayoutManager(new LinearLayoutManager(this));
                rvAnnouncements.setAdapter(announceAdapter);

                Runnable filterAnnouncements = () -> {
                    if (chipAll != null && chipHealth != null && chipAdvisory != null && chipEvent != null) {
                        chipAll.setCardBackgroundColor(Color.parseColor("#FFFFFF"));
                        tvAll.setTextColor(Color.parseColor("#2A3532"));
                        chipHealth.setCardBackgroundColor(Color.parseColor("#FFFFFF"));
                        tvHealth.setTextColor(Color.parseColor("#2A3532"));
                        chipAdvisory.setCardBackgroundColor(Color.parseColor("#FFFFFF"));
                        tvAdvisory.setTextColor(Color.parseColor("#2A3532"));
                        chipEvent.setCardBackgroundColor(Color.parseColor("#FFFFFF"));
                        tvEvent.setTextColor(Color.parseColor("#2A3532"));
                    }

                    filteredAnnouncements.clear();
                    if ("Health".equalsIgnoreCase(currentHistoryFilter)) {
                        if (chipHealth != null) chipHealth.setCardBackgroundColor(Color.parseColor("#0D4A41"));
                        if (tvHealth != null) tvHealth.setTextColor(Color.parseColor("#FFFFFF"));
                        for (JSONObject a : allAnnouncements) {
                            if ("Health".equalsIgnoreCase(a.optString("category"))) filteredAnnouncements.add(a);
                        }
                    } else if ("Advisory".equalsIgnoreCase(currentHistoryFilter)) {
                        if (chipAdvisory != null) chipAdvisory.setCardBackgroundColor(Color.parseColor("#0D4A41"));
                        if (tvAdvisory != null) tvAdvisory.setTextColor(Color.parseColor("#FFFFFF"));
                        for (JSONObject a : allAnnouncements) {
                            if ("Advisory".equalsIgnoreCase(a.optString("category"))) filteredAnnouncements.add(a);
                        }
                    } else if ("Event".equalsIgnoreCase(currentHistoryFilter)) {
                        if (chipEvent != null) chipEvent.setCardBackgroundColor(Color.parseColor("#0D4A41"));
                        if (tvEvent != null) tvEvent.setTextColor(Color.parseColor("#FFFFFF"));
                        for (JSONObject a : allAnnouncements) {
                            if ("Event".equalsIgnoreCase(a.optString("category"))) filteredAnnouncements.add(a);
                        }
                    } else {
                        if (chipAll != null) chipAll.setCardBackgroundColor(Color.parseColor("#0D4A41"));
                        if (tvAll != null) tvAll.setTextColor(Color.parseColor("#FFFFFF"));
                        filteredAnnouncements.addAll(allAnnouncements);
                    }

                    if (filteredAnnouncements.isEmpty()) {
                        if (layoutEmptyState != null) layoutEmptyState.setVisibility(View.VISIBLE);
                        rvAnnouncements.setVisibility(View.GONE);
                    } else {
                        if (layoutEmptyState != null) layoutEmptyState.setVisibility(View.GONE);
                        rvAnnouncements.setVisibility(View.VISIBLE);
                    }
                    announceAdapter.notifyDataSetChanged();
                };

                filterAnnouncements.run();

                if (chipAll != null) chipAll.setOnClickListener(v -> { currentHistoryFilter = "All"; filterAnnouncements.run(); });
                if (chipHealth != null) chipHealth.setOnClickListener(v -> { currentHistoryFilter = "Health"; filterAnnouncements.run(); });
                if (chipAdvisory != null) chipAdvisory.setOnClickListener(v -> { currentHistoryFilter = "Advisory"; filterAnnouncements.run(); });
                if (chipEvent != null) chipEvent.setOnClickListener(v -> { currentHistoryFilter = "Event"; filterAnnouncements.run(); });
            }
        }

        // ====================================================================
        // EMERGENCY CONTACTS DIRECT DIAL LOGIC
        // ====================================================================
        if (layoutId == R.layout.emergency_contacts_list) {
            View btnCallBarangayHall = findViewById(R.id.btnCallBarangayHall);
            View btnCallTanod = findViewById(R.id.btnCallTanod);
            View btnCallBhert = findViewById(R.id.btnCallBhert);
            View btnCallPolice = findViewById(R.id.btnCallPolice);
            View btnCallFire = findViewById(R.id.btnCallFire);
            View btnCallRescue = findViewById(R.id.btnCallRescue);
            View btnCall911 = findViewById(R.id.btnCall911);
            View btnCallRedCross = findViewById(R.id.btnCallRedCross);

            if (btnCallBarangayHall != null) btnCallBarangayHall.setOnClickListener(v -> dialNumber("0281234567"));
            if (btnCallTanod != null) btnCallTanod.setOnClickListener(v -> dialNumber("09171234567"));
            if (btnCallBhert != null) btnCallBhert.setOnClickListener(v -> dialNumber("09189876543"));
            if (btnCallPolice != null) btnCallPolice.setOnClickListener(v -> dialNumber("0289991111"));
            if (btnCallFire != null) btnCallFire.setOnClickListener(v -> dialNumber("0289992222"));
            if (btnCallRescue != null) btnCallRescue.setOnClickListener(v -> dialNumber("0289993333"));
            if (btnCall911 != null) btnCall911.setOnClickListener(v -> dialNumber("911"));
            if (btnCallRedCross != null) btnCallRedCross.setOnClickListener(v -> dialNumber("143"));
        }

        // ====================================================================
        // SERVICES REDIRECTION LOGIC
        // ====================================================================
        if (layoutId == R.layout.services) {
            CardView cardReportForm = findViewById(R.id.cardReportForm);
            CardView cardRequestForm = findViewById(R.id.cardRequestForm);
            CardView cardReportDisaster = findViewById(R.id.cardReportDisaster);
            CardView cardRequestBrgyId = findViewById(R.id.cardRequestBrgyId);
            CardView cardRequestHistory = findViewById(R.id.cardRequestHistory);
            CardView cardEmergencyContacts = findViewById(R.id.cardEmergencyContacts);
            CardView cardAiChatbot = findViewById(R.id.cardAiChatbot);
            CardView cardCalendar = findViewById(R.id.cardCalendar);
            CardView cardAnnouncements = findViewById(R.id.cardAnnouncements);

            if (cardReportForm != null) cardReportForm.setOnClickListener(v -> launchPreview(R.layout.report_form));
            if (cardRequestForm != null) cardRequestForm.setOnClickListener(v -> launchPreview(R.layout.request_form));
            if (cardReportDisaster != null) cardReportDisaster.setOnClickListener(v -> launchPreview(R.layout.report_disaster_form));
            if (cardRequestBrgyId != null) cardRequestBrgyId.setOnClickListener(v -> launchPreview(R.layout.request_brgy_id));
            if (cardRequestHistory != null) cardRequestHistory.setOnClickListener(v -> launchPreview(R.layout.request_history));
            if (cardEmergencyContacts != null) cardEmergencyContacts.setOnClickListener(v -> launchPreview(R.layout.emergency_contacts_list));
            if (cardAiChatbot != null) cardAiChatbot.setOnClickListener(v -> launchPreview(R.layout.ai_chatbot));
            if (cardCalendar != null) cardCalendar.setOnClickListener(v -> launchPreview(R.layout.calendar));
            if (cardAnnouncements != null) cardAnnouncements.setOnClickListener(v -> launchPreview(R.layout.announcements));
        }

        // ====================================================================
        // HISTORY FILTER LOGIC
        // ====================================================================
        if (layoutId == R.layout.request_history) {
            CardView chipAll = findViewById(R.id.chipHistoryAll);
            CardView chipDocs = findViewById(R.id.chipHistoryDocuments);
            CardView chipReports = findViewById(R.id.chipHistoryReports);
            
            TextView tvAll = findViewById(R.id.tvChipHistoryAll);
            TextView tvDocs = findViewById(R.id.tvChipHistoryDocuments);
            TextView tvReports = findViewById(R.id.tvChipHistoryReports);
            
            RecyclerView rvRequests = findViewById(R.id.rvRequests);
            LinearLayout layoutEmptyState = findViewById(R.id.layoutEmptyStateRequests);

            if (chipAll != null && chipDocs != null && chipReports != null) {
                
                RecyclerView.Adapter<RecyclerView.ViewHolder> historyAdapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    @NonNull
                    @Override
                    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_request_history, parent, false);
                        return new RecyclerView.ViewHolder(view) {};
                    }

                    @Override
                    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                        TextView tvType = holder.itemView.findViewById(R.id.tvRequestType);
                        TextView tvDesc = holder.itemView.findViewById(R.id.tvRequestDesc);
                        TextView tvDate = holder.itemView.findViewById(R.id.tvRequestDate);
                        TextView tvStatus = holder.itemView.findViewById(R.id.tvRequestStatus);
                        CardView cardStatus = holder.itemView.findViewById(R.id.cardRequestStatus);
                        View colorStrip = holder.itemView.findViewById(R.id.statusColorStrip);

                        try {
                            JSONObject req = filteredRequests.get(position);
                            
                            tvType.setText(req.optString("requestType", "Request"));
                            tvDesc.setText(req.optString("description", ""));
                            tvDate.setText(req.optString("dateSubmitted", ""));
                            tvStatus.setText(req.optString("statusText", req.optString("status", "Pending")));
                            
                            tvStatus.setTextColor(Color.parseColor(req.optString("statusColorHex", "#DBA03B")));
                            cardStatus.setCardBackgroundColor(Color.parseColor(req.optString("statusBgHex", "#FDF1DA")));
                            colorStrip.setBackgroundColor(Color.parseColor(req.optString("statusColorHex", "#DBA03B")));

                            // Clicking item opens full submitted details modal!
                            holder.itemView.setOnClickListener(v -> showRequestDetailsModal(req));
                            
                        } catch (Exception e) {
                        }
                    }

                    @Override
                    public int getItemCount() {
                        return filteredRequests.size();
                    }
                };

                rvRequests.setLayoutManager(new LinearLayoutManager(this));
                rvRequests.setAdapter(historyAdapter);

                // Populate allRequests with dynamic user submissions
                try {
                    String userReqsJson = prefs.getString("USER_SUBMITTED_REQUESTS", "[]");
                    JSONArray userArray = new JSONArray(userReqsJson);
                    if (userArray.length() > 0) {
                        allRequests.clear();
                        for (int i = 0; i < userArray.length(); i++) {
                            allRequests.add(userArray.getJSONObject(i));
                        }
                    }
                } catch (Exception ignored) {}
                
                Runnable updateUI = () -> {
                    chipAll.setCardBackgroundColor(Color.parseColor("#FFFFFF"));
                    tvAll.setTextColor(Color.parseColor("#2A3532"));
                    
                    chipDocs.setCardBackgroundColor(Color.parseColor("#FFFFFF"));
                    tvDocs.setTextColor(Color.parseColor("#2A3532"));
                    
                    chipReports.setCardBackgroundColor(Color.parseColor("#FFFFFF"));
                    tvReports.setTextColor(Color.parseColor("#2A3532"));

                    filteredRequests.clear();
                    
                    if (currentHistoryFilter.equals("All")) {
                        chipAll.setCardBackgroundColor(Color.parseColor("#0D4A41"));
                        tvAll.setTextColor(Color.parseColor("#FFFFFF"));
                        filteredRequests.addAll(allRequests);
                    } 
                    else if (currentHistoryFilter.equals("Documents")) {
                        chipDocs.setCardBackgroundColor(Color.parseColor("#0D4A41"));
                        tvDocs.setTextColor(Color.parseColor("#FFFFFF"));
                        for (JSONObject req : allRequests) {
                            if (req.optString("requestType").contains("Document") || req.optString("requestType").contains("ID")) {
                                filteredRequests.add(req);
                            }
                        }
                    } 
                    else if (currentHistoryFilter.equals("Reports")) {
                        chipReports.setCardBackgroundColor(Color.parseColor("#0D4A41"));
                        tvReports.setTextColor(Color.parseColor("#FFFFFF"));
                        for (JSONObject req : allRequests) {
                            if (req.optString("requestType").contains("Report")) {
                                filteredRequests.add(req);
                            }
                        }
                    }
                    
                    historyAdapter.notifyDataSetChanged();
                    
                    if (filteredRequests.isEmpty()) {
                        rvRequests.setVisibility(View.GONE);
                        layoutEmptyState.setVisibility(View.VISIBLE);
                    } else {
                        rvRequests.setVisibility(View.VISIBLE);
                        layoutEmptyState.setVisibility(View.GONE);
                    }
                };

                updateUI.run();

                chipAll.setOnClickListener(v -> { currentHistoryFilter = "All"; updateUI.run(); });
                chipDocs.setOnClickListener(v -> { currentHistoryFilter = "Documents"; updateUI.run(); });
                chipReports.setOnClickListener(v -> { currentHistoryFilter = "Reports"; updateUI.run(); });
            }
        }


        // ====================================================================
        // INTERACTIVE DEMO LOGIC FOR "CALENDAR"
        // ====================================================================
        if (layoutId == R.layout.calendar) {
            RecyclerView rvGrid = findViewById(R.id.rvCalendarGrid);
            RecyclerView rvEvents = findViewById(R.id.rvCalendarEvents);
            TextView tvUpcomingHeader = findViewById(R.id.tvUpcomingHeader);
            LinearLayout layoutEmptyState = findViewById(R.id.layoutEmptyStateCalendar);

            if (rvGrid != null && rvEvents != null && tvUpcomingHeader != null && layoutEmptyState != null) {
                
                RecyclerView.Adapter<RecyclerView.ViewHolder> eventsAdapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    @NonNull
                    @Override
                    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_calendar_event, parent, false);
                        return new RecyclerView.ViewHolder(view) {};
                    }

                    @Override
                    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                        TextView tvDay = holder.itemView.findViewById(R.id.tvEventDay);
                        TextView tvTitle = holder.itemView.findViewById(R.id.tvEventTitle);
                        TextView tvTime = holder.itemView.findViewById(R.id.tvEventTime);
                        TextView tvCategory = holder.itemView.findViewById(R.id.tvEventCategory);
                        CardView cardCategory = holder.itemView.findViewById(R.id.cardEventCategory);
                        
                        if (selectedDay == 11) {
                            tvDay.setText("11");
                            tvTitle.setText("Water interruption on Rizal St.");
                            tvTime.setText("8:00 AM – 2:00 PM");
                            tvCategory.setText("Advisory");
                            tvCategory.setTextColor(Color.parseColor("#CC4E42"));
                            cardCategory.setCardBackgroundColor(Color.parseColor("#FCEBEA"));
                        } 
                        else if (selectedDay == 14) {
                            tvDay.setText("14");
                            tvTitle.setText("Free anti-rabies vaccination");
                            tvTime.setText("Barangay hall, all day");
                            tvCategory.setText("Health");
                            tvCategory.setTextColor(Color.parseColor("#247D76"));
                            cardCategory.setCardBackgroundColor(Color.parseColor("#DFF2F0"));
                        }
                        else if (selectedDay == 22) {
                            tvDay.setText("22");
                            tvTitle.setText("Barangay assembly");
                            tvTime.setText("6:00 PM");
                            tvCategory.setText("Event");
                            tvCategory.setTextColor(Color.parseColor("#DBA03B"));
                            cardCategory.setCardBackgroundColor(Color.parseColor("#FDF1DA"));
                        }
                        else {
                            if (position == 0) {
                                tvDay.setText("11");
                                tvTitle.setText("Water interruption on Rizal St.");
                                tvTime.setText("8:00 AM – 2:00 PM");
                                tvCategory.setText("Advisory");
                                tvCategory.setTextColor(Color.parseColor("#CC4E42"));
                                cardCategory.setCardBackgroundColor(Color.parseColor("#FCEBEA"));
                            } else {
                                tvDay.setText("14");
                                tvTitle.setText("Free anti-rabies vaccination");
                                tvTime.setText("Barangay hall, all day");
                                tvCategory.setText("Health");
                                tvCategory.setTextColor(Color.parseColor("#247D76"));
                                cardCategory.setCardBackgroundColor(Color.parseColor("#DFF2F0"));
                            }
                        }
                    }

                    @Override
                    public int getItemCount() {
                        if (selectedDay == 11 || selectedDay == 14 || selectedDay == 22) return 1;
                        else if (selectedDay == -1) return 2;
                        else return 0;
                    }
                };

                rvEvents.setLayoutManager(new LinearLayoutManager(this));
                rvEvents.setAdapter(eventsAdapter);

                RecyclerView.Adapter<RecyclerView.ViewHolder> gridAdapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    @NonNull
                    @Override
                    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_calendar_day, parent, false);
                        return new RecyclerView.ViewHolder(view) {};
                    }

                    @Override
                    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                        TextView tvDay = holder.itemView.findViewById(R.id.tvDayNumber);
                        View dot = holder.itemView.findViewById(R.id.viewEventDot);
                        CardView background = holder.itemView.findViewById(R.id.cardDayBackground);
                        
                        final int dayNum = (position - 2); 
                        
                        if (dayNum > 0 && dayNum <= 30) {
                            tvDay.setText(String.valueOf(dayNum));
                            
                            if (dayNum == selectedDay) {
                                background.setCardBackgroundColor(Color.parseColor("#DDF0EC"));
                                tvDay.setTextColor(Color.parseColor("#174A45"));
                            } else if (dayNum == 9 && selectedDay == -1) {
                                background.setCardBackgroundColor(Color.parseColor("#0D4A41"));
                                tvDay.setTextColor(Color.parseColor("#FFFFFF"));
                            } else {
                                background.setCardBackgroundColor(Color.TRANSPARENT);
                                tvDay.setTextColor(Color.parseColor("#11231D"));
                            }
                            
                            if (dayNum == 11 || dayNum == 14 || dayNum == 22) dot.setVisibility(View.VISIBLE);
                            else dot.setVisibility(View.INVISIBLE);

                            holder.itemView.setOnClickListener(v -> {
                                selectedDay = dayNum;
                                notifyDataSetChanged(); 
                                tvUpcomingHeader.setText(String.format(Locale.US, "Sept %d's events", dayNum));
                                eventsAdapter.notifyDataSetChanged(); 
                                
                                if (dayNum != 11 && dayNum != 14 && dayNum != 22) {
                                    rvEvents.setVisibility(View.GONE);
                                    layoutEmptyState.setVisibility(View.VISIBLE);
                                } else {
                                    rvEvents.setVisibility(View.VISIBLE);
                                    layoutEmptyState.setVisibility(View.GONE);
                                }
                            });
                        } else {
                            if (dayNum <= 0) tvDay.setText(String.valueOf(30 + dayNum));
                            else tvDay.setText(String.valueOf(dayNum - 30));
                            
                            tvDay.setTextColor(Color.parseColor("#DFE2DD"));
                            background.setCardBackgroundColor(Color.TRANSPARENT);
                            dot.setVisibility(View.INVISIBLE);
                            holder.itemView.setOnClickListener(null);
                        }
                    }

                    @Override
                    public int getItemCount() {
                        return 35;
                    }
                };
                
                rvGrid.setAdapter(gridAdapter);
            }
        }

        // ====================================================================
        // INTERACTIVE DEMO LOGIC FOR "REQUEST BARANGAY ID"
        // ====================================================================
        if (layoutId == R.layout.request_brgy_id) {
            CardView btnOpenModal = findViewById(R.id.btnOpenApplicationModal);
            TextView tvSelectedType = findViewById(R.id.tvSelectedApplicationType);

            if (btnOpenModal != null) {
                btnOpenModal.setOnClickListener(v -> {
                    View dialogView = getLayoutInflater().inflate(R.layout.dialog_application_type, null);
                    
                    AlertDialog.Builder builder = new AlertDialog.Builder(PreviewActivity.this);
                    builder.setView(dialogView);
                    AlertDialog dialog = builder.create();
                    
                    if (dialog.getWindow() != null) {
                        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
                    }

                    RadioGroup rgType = dialogView.findViewById(R.id.rgApplicationType);
                    CardView btnProceed = dialogView.findViewById(R.id.btnProceedType);
                    TextView btnCancel = dialogView.findViewById(R.id.btnCancelModal);

                    btnProceed.setOnClickListener(proceedView -> {
                        int selectedId = rgType.getCheckedRadioButtonId();
                        
                        if (selectedId != -1) {
                            if (selectedId == R.id.rbNewApplicant) {
                                tvSelectedType.setText("Application: New Applicant");
                            } else if (selectedId == R.id.rbRenewal) {
                                tvSelectedType.setText("Application: Renewal");
                            } else if (selectedId == R.id.rbTransfer) {
                                tvSelectedType.setText("Application: Transfer");
                            }
                            
                            dialog.dismiss();
                        }
                    });

                    btnCancel.setOnClickListener(cancelView -> dialog.dismiss());
                    dialog.show();
                });
            }
        }
    }

    private void fetchLiveWeather(TextView tvWeatherDate, ImageView ivWeatherIcon) {
        // System current date
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, MMM d", Locale.getDefault());
        String currentDateStr = sdf.format(new Date());

        if (tvWeatherDate != null) {
            tvWeatherDate.setText("28°C · " + currentDateStr);
        }

        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url("https://api.open-meteo.com/v1/forecast?latitude=14.5995&longitude=120.9842&current_weather=true")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> {
                    if (tvWeatherDate != null) {
                        tvWeatherDate.setText("⛅ 28°C · " + currentDateStr);
                    }
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        String body = response.body().string();
                        JSONObject json = new JSONObject(body);
                        JSONObject currentWeather = json.getJSONObject("current_weather");
                        double temp = currentWeather.getDouble("temperature");
                        int code = currentWeather.getInt("weathercode");

                        int roundedTemp = (int) Math.round(temp);

                        String weatherEmoji;
                        if (code == 0) {
                            weatherEmoji = "☀️"; // Sunny
                        } else if (code >= 1 && code <= 3) {
                            weatherEmoji = "⛅"; // Cloudy
                        } else if (code >= 51 && code <= 82) {
                            weatherEmoji = "🌧️"; // Rain
                        } else if (code >= 95) {
                            weatherEmoji = "🌩️"; // Thunderstorm
                        } else {
                            weatherEmoji = "🌤️";
                        }

                        String weatherText = weatherEmoji + " " + roundedTemp + "°C · " + currentDateStr;

                        runOnUiThread(() -> {
                            if (tvWeatherDate != null) {
                                tvWeatherDate.setText(weatherText);
                            }
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> {
                            if (tvWeatherDate != null) {
                                tvWeatherDate.setText("⛅ 28°C · " + currentDateStr);
                            }
                        });
                    }
                }
            }
        });
    }

    private String getCurrentFormattedDateTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault());
        return sdf.format(new Date());
    }

    private void saveNewUserRequest(String type, String desc, String date, String status, String category, String fullDetails) {
        try {
            SharedPreferences prefs = getSharedPreferences("AppSession", MODE_PRIVATE);
            String existingJson = prefs.getString("USER_SUBMITTED_REQUESTS", "[]");
            JSONArray array = new JSONArray(existingJson);

            JSONObject newReq = new JSONObject();
            newReq.put("requestType", type);
            newReq.put("description", desc);
            newReq.put("dateSubmitted", "Submitted on " + date);
            newReq.put("statusText", status);
            newReq.put("status", status);
            newReq.put("category", category);
            newReq.put("full_details", fullDetails);

            if ("Pending".equalsIgnoreCase(status)) {
                newReq.put("statusBgHex", "#FDF1DA");
                newReq.put("statusColorHex", "#DBA03B");
            } else if ("Resolved".equalsIgnoreCase(status)) {
                newReq.put("statusBgHex", "#DFE2DD");
                newReq.put("statusColorHex", "#8D9691");
            } else {
                newReq.put("statusBgHex", "#DDF0EC");
                newReq.put("statusColorHex", "#247D76");
            }

            JSONArray updatedArray = new JSONArray();
            updatedArray.put(newReq);
            for (int i = 0; i < array.length(); i++) {
                updatedArray.put(array.get(i));
            }

            prefs.edit().putString("USER_SUBMITTED_REQUESTS", updatedArray.toString()).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void showRequestDetailsModal(JSONObject req) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_request_details, null);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        TextView tvTitle = dialogView.findViewById(R.id.tvDialogTitle);
        TextView tvSub = dialogView.findViewById(R.id.tvDialogSubtitle);
        TextView tvStatus = dialogView.findViewById(R.id.tvDialogStatus);
        TextView tvDate = dialogView.findViewById(R.id.tvDialogSubmittedDate);
        TextView tvDetails = dialogView.findViewById(R.id.tvDialogFullDetails);
        CardView btnClose = dialogView.findViewById(R.id.btnCloseDialog);

        String type = req.optString("requestType", req.optString("type", "Request"));
        String desc = req.optString("description", "");
        String status = req.optString("statusText", req.optString("status", "Pending"));
        String date = req.optString("dateSubmitted", req.optString("date", "Submitted on " + getCurrentFormattedDateTime()));
        String fullDetails = req.optString("full_details", "• Type: " + type + "\n• Description: " + desc + "\n• Status: " + status);

        if (tvTitle != null) tvTitle.setText(type);
        if (tvSub != null) tvSub.setText(desc);
        if (tvStatus != null) tvStatus.setText(status);
        if (tvDate != null) tvDate.setText(date);
        if (tvDetails != null) tvDetails.setText(fullDetails);

        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dialog.dismiss());
        }

        dialog.show();
    }

    private void dialNumber(String phoneNumber) {
        try {
            Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phoneNumber));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Unable to open phone dialer", Toast.LENGTH_SHORT).show();
        }
    }

    private double selectedLat = 14.5995;
    private double selectedLng = 120.9842;
    private String selectedLocationAddress = "📍 Pinned: Lat 14.5995, Lng 120.9842";

    private void showMapPickerDialog(TextView targetLocationTextView) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_map_picker, null);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        WebView wvMap = dialogView.findViewById(R.id.wvMapPicker);
        TextView tvAddress = dialogView.findViewById(R.id.tvMapAddressDisplay);
        CardView btnGps = dialogView.findViewById(R.id.btnGpsLiveLocation);
        CardView btnConfirm = dialogView.findViewById(R.id.btnConfirmMapLocation);
        TextView btnCancel = dialogView.findViewById(R.id.btnCancelMapLocation);

        if (wvMap != null) {
            wvMap.getSettings().setJavaScriptEnabled(true);
            wvMap.addJavascriptInterface(new Object() {
                @JavascriptInterface
                public void onLocationPinned(double lat, double lng) {
                    runOnUiThread(() -> {
                        selectedLat = lat;
                        selectedLng = lng;
                        selectedLocationAddress = String.format(Locale.US, "📍 Pinned: Lat %.4f, Lng %.4f", lat, lng);
                        if (tvAddress != null) {
                            tvAddress.setText(selectedLocationAddress);
                        }
                    });
                }
            }, "AndroidMapBridge");

            String htmlMap = "<!DOCTYPE html><html><head>" +
                    "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\" />" +
                    "<link rel=\"stylesheet\" href=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.css\" />" +
                    "<script src=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.js\"></script>" +
                    "<style>body,html,#map{margin:0;padding:0;height:100%;width:100%;}</style></head><body>" +
                    "<div id=\"map\"></div><script>" +
                    "var map = L.map('map').setView([14.5995, 120.9842], 15);" +
                    "L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {maxZoom: 19}).addTo(map);" +
                    "var marker = L.marker([14.5995, 120.9842], {draggable: true}).addTo(map);" +
                    "function updateLoc(lat, lng) { if(window.AndroidMapBridge) window.AndroidMapBridge.onLocationPinned(lat, lng); }" +
                    "marker.on('dragend', function(e){ var pos = marker.getLatLng(); updateLoc(pos.lat, pos.lng); });" +
                    "map.on('click', function(e){ marker.setLatLng(e.latlng); updateLoc(e.latlng.lat, e.latlng.lng); });" +
                    "function setGps(lat, lng){ map.setView([lat, lng], 17); marker.setLatLng([lat, lng]); updateLoc(lat, lng); }" +
                    "</script></body></html>";

            wvMap.loadDataWithBaseURL("https://openstreetmap.org", htmlMap, "text/html", "UTF-8", null);
        }

        if (btnGps != null && wvMap != null) {
            btnGps.setOnClickListener(v -> {
                wvMap.evaluateJavascript("setGps(14.5995, 120.9842);", null);
                Toast.makeText(this, "Acquired Live GPS Location!", Toast.LENGTH_SHORT).show();
            });
        }

        if (btnCancel != null) btnCancel.setOnClickListener(v -> dialog.dismiss());

        if (btnConfirm != null) {
            btnConfirm.setOnClickListener(v -> {
                if (targetLocationTextView != null) {
                    targetLocationTextView.setText(selectedLocationAddress);
                }
                Toast.makeText(this, "Location Pinned!", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });
        }

        dialog.show();
    }

    private void launchPreview(int layoutId) {
        Intent intent = new Intent(PreviewActivity.this, PreviewActivity.class);
        intent.putExtra("LAYOUT_ID", layoutId);
        startActivity(intent);
    }

    private void loadMockData() {
        // Run on background thread to prevent lag/UI freezing!
        new Thread(() -> {
            try {
                InputStream is = getAssets().open("mock_data.json");
                int size = is.available();
                byte[] buffer = new byte[size];
                int bytesRead = is.read(buffer);
                is.close();
                
                if (bytesRead > 0) {
                    String jsonStr = new String(buffer, StandardCharsets.UTF_8);
                    JSONObject obj = new JSONObject(jsonStr);
                    JSONArray requestsArray = obj.getJSONArray("requests");

                    allRequests.clear();
                    for (int i = 0; i < requestsArray.length(); i++) {
                        allRequests.add(requestsArray.getJSONObject(i));
                    }

                    JSONArray announcementsArray = obj.optJSONArray("announcements");
                    if (announcementsArray != null) {
                        allAnnouncements.clear();
                        for (int j = 0; j < announcementsArray.length(); j++) {
                            allAnnouncements.add(announcementsArray.getJSONObject(j));
                        }
                    }
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }).start();
    }
}