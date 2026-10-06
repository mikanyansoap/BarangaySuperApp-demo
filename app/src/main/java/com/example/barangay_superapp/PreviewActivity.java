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
import android.widget.RadioButton;
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
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.FileProvider;

import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class PreviewActivity extends AppCompatActivity {
    
    private int selectedDay = -1;
    private String currentHistoryFilter = "All";
    private String selectedBarangayCode = "";
    private String selectedBarangayName = "";
    private String selectedProvinceCode = "";
    private String selectedProvinceName = "";
    private String selectedCityCode = "";
    
    private final List<JSONObject> allRequests = new ArrayList<>();
    private final List<JSONObject> filteredRequests = new ArrayList<>();
    private final List<JSONObject> allAnnouncements = new ArrayList<>();
    /** Set by the Announcements screen so its filtered list refreshes when the data arrives. */
    private Runnable onAnnouncementsUpdated;
    
    private TextView currentUploadTextView;
    private String currentUploadBucket = "request-evidence";
    private ImageView currentUploadPreview;                           // optional thumbnail for the current upload
    private final Set<Integer> filledUploadViews = new HashSet<>();   // upload TextView ids that received a file
    private final Map<Integer, String> localAttachmentPaths = new HashMap<>(); // upload TextView id -> local image copy

    // ---- Runtime permissions (camera for selfies, location for the map) ----
    private Runnable pendingAfterCameraPermission;
    private Runnable pendingAfterLocationPermission;

    private final ActivityResultLauncher<String> cameraPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            granted -> {
                Runnable r = pendingAfterCameraPermission;
                pendingAfterCameraPermission = null;
                if (granted && r != null) r.run();
                else if (!granted) Toast.makeText(this, "Camera permission is needed to take your selfie.", Toast.LENGTH_LONG).show();
            });

    private final ActivityResultLauncher<String[]> locationPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            result -> {
                Runnable r = pendingAfterLocationPermission;
                pendingAfterLocationPermission = null;
                if (r != null) r.run(); // runs either way; without permission it falls back to your barangay's area
            });

    // ---- AI report builder (chatbot) ----
    private List<ChatMessage> chatMessages;
    private RecyclerView chatRecycler;
    private int reportInterviewStep = -1; // -1 = not interviewing
    private final String[] reportAnswers = new String[ReportAssistant.QUESTIONS.length];
    private String pendingReportSeed = "";
    private ReportAssistant.Draft pendingReportDraft;

    private static final OkHttpClient geoClient = new OkHttpClient();
    private static final String GEO_USER_AGENT = "BarangaySuperApp/1.0 (Android; barangay services app)";

    private byte[] readBytesFromUri(Uri uri) {
        if (uri == null) return null;
        try (InputStream is = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream()) {
            if (is == null) return null;
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) {
                byteBuffer.write(buffer, 0, len);
            }
            return byteBuffer.toByteArray();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private long getFileSize(Uri uri) {
        if (uri == null) return 0;
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeIndex != -1) {
                    return cursor.getLong(sizeIndex);
                }
            }
        } catch (Exception ignored) {}
        try (AssetFileDescriptor fd = getContentResolver().openAssetFileDescriptor(uri, "r")) {
            if (fd != null) {
                return fd.getLength();
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private String saveProfileImageLocally(Uri uri) {
        if (uri == null) return null;
        try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
            if (inputStream == null) return null;
            File destFile = new File(getFilesDir(), "user_avatar.jpg");
            try (FileOutputStream outputStream = new FileOutputStream(destFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                outputStream.flush();
            }
            return destFile.getAbsolutePath();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private String createBase64ProfileString(Bitmap bitmap) {
        if (bitmap == null) return null;
        try {
            int maxDim = 300;
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            float scale = Math.min((float) maxDim / width, (float) maxDim / height);
            
            Bitmap scaledBitmap;
            if (scale < 1.0f) {
                int newWidth = Math.round(width * scale);
                int newHeight = Math.round(height * scale);
                scaledBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);
            } else {
                scaledBitmap = bitmap;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 75, baos);
            byte[] bytes = baos.toByteArray();
            return "data:image/jpeg;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private Uri cameraPhotoUri = null;

    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    Uri selectedFileUri = (result.getData() != null) ? result.getData().getData() : null;
                    boolean fromCamera = false;
                    if (selectedFileUri == null && cameraPhotoUri != null) {
                        selectedFileUri = cameraPhotoUri;
                        fromCamera = true;
                    }
                    cameraPhotoUri = null;

                    byte[] capturedFileBytes = null;
                    if (fromCamera) {
                        // Camera photos can be huge: shrink + fix rotation before uploading
                        capturedFileBytes = compressCameraImage(selectedFileUri);
                        if (capturedFileBytes == null) capturedFileBytes = readBytesFromUri(selectedFileUri);
                    } else if (selectedFileUri != null) {
                        long fileSize = getFileSize(selectedFileUri);
                        if (fileSize > 10 * 1024 * 1024) { // > 10MB
                            double sizeMb = fileSize / (1024.0 * 1024.0);
                            Toast.makeText(this, String.format(Locale.US, "File size (%.1f MB) exceeds the 10MB limit! Please select a smaller file.", sizeMb), Toast.LENGTH_LONG).show();
                            return;
                        }
                        capturedFileBytes = readBytesFromUri(selectedFileUri);
                    }

                    if ((capturedFileBytes == null || capturedFileBytes.length == 0) && result.getData() != null && result.getData().getExtras() != null) {
                        Object dataObj = result.getData().getExtras().get("data");
                        if (dataObj instanceof Bitmap) {
                            Bitmap bmp = (Bitmap) dataObj;
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            bmp.compress(Bitmap.CompressFormat.JPEG, 92, baos);
                            capturedFileBytes = baos.toByteArray();
                        }
                    }

                    if (selectedFileUri != null || (capturedFileBytes != null && capturedFileBytes.length > 0)) {
                        SharedPreferences prefs = getSharedPreferences("AppSession", MODE_PRIVATE);

                        if (currentUploadTextView != null) {
                            final TextView targetTv = currentUploadTextView;
                            filledUploadViews.add(targetTv.getId());
                            targetTv.setTag(null); // clear any previous upload URL

                            // Keep a local copy of images so History can show them even if the upload fails
                            String pickedMime = fromCamera ? "image/jpeg" : (selectedFileUri != null ? getContentResolver().getType(selectedFileUri) : "image/jpeg");
                            if (capturedFileBytes != null && (pickedMime == null || pickedMime.startsWith("image/"))) {
                                String localCopy = saveAttachmentLocally(capturedFileBytes);
                                if (localCopy != null) localAttachmentPaths.put(targetTv.getId(), localCopy);
                                if (currentUploadPreview != null) {
                                    Bitmap thumb = decodeSampled(capturedFileBytes, 400);
                                    if (thumb != null) {
                                        currentUploadPreview.setImageBitmap(thumb);
                                        currentUploadPreview.setVisibility(View.VISIBLE);
                                    }
                                }
                            } else {
                                localAttachmentPaths.remove(targetTv.getId());
                            }
                            targetTv.setText("Uploading to Supabase...");
                            targetTv.setTextColor(Color.parseColor("#E65100")); // Orange during upload

                            final byte[] fileBytes = capturedFileBytes;
                            if (fileBytes != null && fileBytes.length > 0) {
                                String targetBucket = (currentUploadBucket != null && !currentUploadBucket.isEmpty()) ? currentUploadBucket : "request-evidence";
                                String ext = ".jpg";
                                String mime = "image/jpeg";
                                if (selectedFileUri != null && !fromCamera) {
                                    String detectedMime = getContentResolver().getType(selectedFileUri);
                                    if (detectedMime != null && !detectedMime.isEmpty()) mime = detectedMime;
                                }
                                if (mime.contains("png")) ext = ".png";
                                else if (mime.contains("pdf")) ext = ".pdf";

                                // Store under the user's own folder (<userId>/...) so Storage RLS policies can match auth.uid()
                                String uploaderId = prefs.getString("USER_ID", "");
                                String fileName = (uploaderId.isEmpty() ? "" : uploaderId + "/") + targetBucket + "_" + System.currentTimeMillis() + ext;

                                final Uri finalUri = selectedFileUri;
                                // Uses the signed-in user's JWT (auto-refreshed) instead of the anon key
                                SupabaseClient.uploadToStorageBucket(targetBucket, fileName, fileBytes, mime, new SupabaseClient.StorageUploadCallback() {
                                    @Override
                                    public void onSuccess(String publicUrl) {
                                        runOnUiThread(() -> {
                                            targetTv.setTag(publicUrl);
                                            targetTv.setText("✓ Uploaded: " + fileName.substring(fileName.lastIndexOf('/') + 1));
                                            targetTv.setTextColor(Color.parseColor("#1B5E20"));
                                            Toast.makeText(PreviewActivity.this, "Successfully saved to Supabase bucket (" + targetBucket + ")!", Toast.LENGTH_SHORT).show();
                                        });
                                    }

                                    @Override
                                    public void onError(String error) {
                                        runOnUiThread(() -> {
                                            targetTv.setText(finalUri != null ? "Selected: " + finalUri.getLastPathSegment() : "✓ Photo Captured");
                                            targetTv.setTextColor(Color.parseColor("#1B5E20"));
                                            Toast.makeText(PreviewActivity.this, "Supabase Upload Warning: " + error, Toast.LENGTH_SHORT).show();
                                        });
                                    }
                                });
                            }
                        } else if (selectedFileUri != null) {
                            // Update Profile Picture using persistent internal file storage AND Supabase Storage!
                            String localPath = saveProfileImageLocally(selectedFileUri);
                            if (localPath != null) {
                                prefs.edit().putString("USER_AVATAR_PATH", localPath).apply();
                                
                                Bitmap bitmap = BitmapFactory.decodeFile(localPath);
                                if (bitmap != null) {
                                    ImageView ivProfileImage = findViewById(R.id.ivProfileImage);
                                    if (ivProfileImage != null) {
                                        ivProfileImage.setImageTintList(null);
                                        ivProfileImage.setImageBitmap(bitmap);
                                    }
                                    ImageView ivHeaderProfileImage = findViewById(R.id.ivHeaderProfileImage);
                                    if (ivHeaderProfileImage != null) {
                                        ivHeaderProfileImage.setImageTintList(null);
                                        ivHeaderProfileImage.setImageBitmap(bitmap);
                                    }
                                }

                                byte[] imageBytes = null;
                                if (bitmap != null) {
                                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos);
                                    imageBytes = baos.toByteArray();
                                } else {
                                    imageBytes = readBytesFromUri(selectedFileUri);
                                }

                                if (imageBytes != null && imageBytes.length > 0) {
                                    String avatarOwnerId = prefs.getString("USER_ID", "");
                                    String fileName = (avatarOwnerId.isEmpty() ? "" : avatarOwnerId + "/") + "avatar_" + System.currentTimeMillis() + ".jpg";
                                    SupabaseClient.uploadToStorageBucket("avatars", fileName, imageBytes, "image/jpeg", new SupabaseClient.StorageUploadCallback() {
                                        @Override
                                        public void onSuccess(String publicUrl) {
                                            runOnUiThread(() -> {
                                                prefs.edit().putString("USER_AVATAR_URL", publicUrl).apply();
                                                String userId = prefs.getString("USER_ID", "");
                                                if (!userId.isEmpty()) {
                                                    SupabaseClient.updateUserProfilePhoto(userId, publicUrl, new Callback() {
                                                        @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {}
                                                        @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {}
                                                    });
                                                }
                                                Toast.makeText(PreviewActivity.this, "Successfully uploaded profile picture to Supabase avatars bucket!", Toast.LENGTH_SHORT).show();
                                            });
                                        }

                                        @Override
                                        public void onError(String error) {
                                            // Fallback: If Supabase Storage returns 403 or RLS error, save Base64 data string directly into Supabase DB user profile!
                                            final Bitmap finalBmp = bitmap;
                                            String base64DataUrl = createBase64ProfileString(finalBmp);
                                            if (base64DataUrl != null) {
                                                prefs.edit().putString("USER_AVATAR_URL", base64DataUrl).apply();
                                                String userId = prefs.getString("USER_ID", "");
                                                if (!userId.isEmpty()) {
                                                    SupabaseClient.updateUserProfilePhoto(userId, base64DataUrl, new Callback() {
                                                        @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {}
                                                        @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {}
                                                    });
                                                }
                                                runOnUiThread(() -> {
                                                    Toast.makeText(PreviewActivity.this, "Successfully updated profile picture in Supabase!", Toast.LENGTH_SHORT).show();
                                                });
                                            } else {
                                                runOnUiThread(() -> {
                                                    Toast.makeText(PreviewActivity.this, "Profile picture saved locally. Supabase error: " + error, Toast.LENGTH_SHORT).show();
                                                });
                                            }
                                        }
                                    });
                                } else {
                                    Toast.makeText(PreviewActivity.this, "Successfully updated profile picture!", Toast.LENGTH_SHORT).show();
                                }
                            } else {
                                Toast.makeText(this, "Failed to update profile picture", Toast.LENGTH_SHORT).show();
                            }
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
        /** What the Yes button does: "document" (open request form), "start_report", "open_report". */
        public String promptAction = "document";

        public ChatMessage(boolean isUser, String text, boolean hasPrompt) {
            this.isUser = isUser;
            this.text = text;
            this.hasPrompt = hasPrompt;
            this.promptHandled = false;
        }

        public ChatMessage(boolean isUser, String text, boolean hasPrompt, String promptAction) {
            this(isUser, text, hasPrompt);
            this.promptAction = promptAction;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // The UI is designed for light backgrounds; ignore phone dark mode (it made spinner text unreadable)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(savedInstanceState);
        SupabaseClient.init(getApplicationContext());
        
        // Retrieve persistent session state
        SharedPreferences prefs = getSharedPreferences("AppSession", MODE_PRIVATE);
        boolean isLoggedIn = prefs.getBoolean("IS_LOGGED_IN", false);
        int defaultLayout = isLoggedIn ? R.layout.dashboard : R.layout.starting;

        int layoutId = getIntent().getIntExtra("LAYOUT_ID", defaultLayout);
        setContentView(layoutId);

        // Use the user's saved barangay for requests/announcements (sign-up starts blank)
        if (layoutId != R.layout.sign_up) {
            selectedBarangayCode = prefs.getString("USER_BARANGAY_CODE", "");
            selectedBarangayName = prefs.getString("USER_BARANGAY", "");
        }

        if (layoutId == R.layout.calendar) {
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
                    currentUploadBucket = "id-photos";
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.setType("image/*");
                    filePickerLauncher.launch(intent);
                });
            }
        }

        // Brgy ID Form
        if (layoutId == R.layout.request_brgy_id) {
            CardView cardSelfie = findViewById(R.id.cardSelfie);
            TextView tvSelfieSub = findViewById(R.id.tvSelfieSub);
            if (cardSelfie != null && tvSelfieSub != null) {
                cardSelfie.setOnClickListener(v -> launchSelfieCamera(tvSelfieSub, null, "avatars"));
            }

            CardView cardUploadBrgyId = findViewById(R.id.cardUploadBrgyId);
            TextView tvBrgyIdFile = findViewById(R.id.tvBrgyIdFile);
            if (cardUploadBrgyId != null && tvBrgyIdFile != null) {
                cardUploadBrgyId.setOnClickListener(v -> {
                    currentUploadTextView = tvBrgyIdFile;
                    currentUploadBucket = "id-photos";
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
                    currentUploadBucket = "proof-of-residency";
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
            HintAdapter.attach(spinnerDoc, "Select document type",
                    new String[]{"Barangay Clearance", "Certificate of Residency", "Certificate of Indigency", "Business Clearance", "Others"});

            CardView btnUploadProof = findViewById(R.id.btnUploadProof);
            TextView tvUploadProofText = findViewById(R.id.tvUploadProofText);
            if (btnUploadProof != null && tvUploadProofText != null) {
                btnUploadProof.setOnClickListener(v -> {
                    currentUploadTextView = tvUploadProofText;
                    currentUploadPreview = null;
                    currentUploadBucket = "request-evidence";
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
                    String docType = HintAdapter.getValue(spinnerDoc);
                    String purpose = etPurpose != null ? etPurpose.getText().toString().trim() : "";

                    if (docType.isEmpty()) {
                        Toast.makeText(this, "Please select the document type", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (purpose.isEmpty()) {
                        Toast.makeText(this, "Please state the purpose of your request", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    String reqId = "REQ-" + (int)(Math.random() * 9000 + 1000);
                    String details = "• Request ID: " + reqId + "\n• Document Type: " + docType + "\n• Purpose: " + purpose + "\n• Applicant: " + prefs.getString("USER_NAME", "Resident") + "\n• Status: Pending Review by Barangay Staff";
                    JSONArray attachments = collectAttachments(tvUploadProofText);
                    JSONObject extras = new JSONObject();
                    try {
                        extras.put("attachments", attachments);
                        extras.put("editable_text", purpose);
                    } catch (Exception ignored) {}
                    String localId = saveNewUserRequest("Document Request", docType + " (" + reqId + ")", getCurrentFormattedDateTime(), "Pending", "document", details, extras);

                    String userId = prefs.getString("USER_ID", "");
                    SupabaseClient.submitRequestToSupabase(userId, selectedBarangayCode, "document", "Document Request: " + docType, details,
                            prefs.getString("USER_ADDRESS", "Barangay Area"), selectedLat, selectedLng, firstRemoteUrl(attachments),
                            rememberSupabaseId(localId, "requests"));

                    Toast.makeText(this, "Document Request Submitted! (" + reqId + ")", Toast.LENGTH_LONG).show();
                    launchPreview(R.layout.request_history);
                });
            }
        }

        // Report Form
        if (layoutId == R.layout.report_form) {
            Spinner spinnerCat = findViewById(R.id.spinnerReportCategory);
            HintAdapter.attach(spinnerCat, "Select report category", ReportAssistant.REPORT_CATEGORIES);
            EditText etReportDescription = findViewById(R.id.etReportDescription);

            CardView btnGetCurrentLocation = findViewById(R.id.btnGetCurrentLocation);
            TextView tvLocationAddress = findViewById(R.id.tvLocationAddress);
            if (btnGetCurrentLocation != null && tvLocationAddress != null) {
                btnGetCurrentLocation.setOnClickListener(v -> showMapPickerDialog(tvLocationAddress));
            }

            // Prefill from an AI-drafted report (user reviews & edits before submitting)
            if (getIntent().getBooleanExtra("AI_DRAFT", false)) {
                HintAdapter.selectValue(spinnerCat, getIntent().getStringExtra("AI_CATEGORY"));
                String aiDesc = getIntent().getStringExtra("AI_DESCRIPTION");
                String aiLoc = getIntent().getStringExtra("AI_LOCATION");
                if (etReportDescription != null) {
                    etReportDescription.setText((aiDesc != null ? aiDesc : "")
                            + (aiLoc != null && !aiLoc.isEmpty() ? "\nLocation: " + aiLoc : ""));
                }
                showAiDraftNotice();
            }

            CardView btnAddPhoto = findViewById(R.id.btnAddPhoto);
            TextView tvAddPhotoText = findViewById(R.id.tvAddPhotoText);
            if (btnAddPhoto != null && tvAddPhotoText != null) {
                btnAddPhoto.setOnClickListener(v -> {
                    currentUploadTextView = tvAddPhotoText;
                    currentUploadPreview = null;
                    currentUploadBucket = "request-evidence";
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.setType("image/*");
                    filePickerLauncher.launch(intent);
                });
            }

            CardView btnSubmitReport = findViewById(R.id.btnSubmitReport);
            if (btnSubmitReport != null) {
                btnSubmitReport.setOnClickListener(v -> {
                    String cat = HintAdapter.getValue(spinnerCat);
                    String details = etReportDescription != null ? etReportDescription.getText().toString().trim() : "";

                    if (cat.isEmpty()) {
                        Toast.makeText(this, "Please select a report category", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (details.isEmpty()) {
                        Toast.makeText(this, "Please provide description details for your report", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    String locAddress = locationPinned && !selectedLocationAddress.isEmpty() ? selectedLocationAddress.replace("📍 ", "") : prefs.getString("USER_ADDRESS", "Barangay Area");
                    String reqId = "REP-" + (int)(Math.random() * 9000 + 1000);
                    String fullDetails = "• Report ID: " + reqId + "\n• Category: " + cat + "\n• Description: " + details + "\n• Location: " + locAddress + "\n• Reported By: " + prefs.getString("USER_NAME", "Resident") + "\n• Status: Pending Review by Barangay Officers";
                    JSONArray attachments = collectAttachments(tvAddPhotoText);
                    JSONObject extras = new JSONObject();
                    try {
                        extras.put("attachments", attachments);
                        extras.put("editable_text", details);
                        if (locationPinned) { extras.put("latitude", selectedLat); extras.put("longitude", selectedLng); }
                    } catch (Exception ignored) {}
                    String localId = saveNewUserRequest("Report", cat + " (" + reqId + ")", getCurrentFormattedDateTime(), "Pending", "report", fullDetails, extras);

                    String userId = prefs.getString("USER_ID", "");
                    // Goes to public.reports (what the barangay web admin reads)
                    String reportDesc = details + "\n\nLocation: " + locAddress
                            + (locationPinned ? String.format(Locale.US, "\nMap pin: %.6f, %.6f", selectedLat, selectedLng) : "");
                    SupabaseClient.submitReportToSupabase(userId, selectedBarangayCode, "Complaint", cat, reportDesc, "medium",
                            firstRemoteUrl(attachments),
                            locationPinned ? Double.valueOf(selectedLat) : null,
                            locationPinned ? Double.valueOf(selectedLng) : null,
                            locAddress, rememberSupabaseId(localId, "reports"));

                    Toast.makeText(this, "Report Submitted Successfully! (" + reqId + ")", Toast.LENGTH_LONG).show();
                    launchPreview(R.layout.request_history);
                });
            }
        }

        // Report Disaster Form
        if (layoutId == R.layout.report_disaster_form) {
            Spinner spinnerDisaster = findViewById(R.id.spinnerDisasterType);
            HintAdapter.attach(spinnerDisaster, "Select incident type", ReportAssistant.DISASTER_TYPES);
            EditText etLoc = findViewById(R.id.etDisasterLocation);
            EditText etDet = findViewById(R.id.etDisasterDetails);

            CardView btnGetCurrentLocation = findViewById(R.id.btnGetCurrentLocation);
            TextView tvLocationAddress = findViewById(R.id.tvLocationAddress);
            if (btnGetCurrentLocation != null && tvLocationAddress != null) {
                btnGetCurrentLocation.setOnClickListener(v -> showMapPickerDialog(tvLocationAddress));
            }

            if (getIntent().getBooleanExtra("AI_DRAFT", false)) {
                HintAdapter.selectValue(spinnerDisaster, getIntent().getStringExtra("AI_CATEGORY"));
                if (etLoc != null) etLoc.setText(getIntent().getStringExtra("AI_LOCATION"));
                if (etDet != null) etDet.setText(getIntent().getStringExtra("AI_DESCRIPTION"));
                showAiDraftNotice();
            }

            CardView cardUploadDisasterMedia = findViewById(R.id.cardUploadDisasterMedia);
            TextView tvDisasterMediaFile = findViewById(R.id.tvDisasterMediaFile);
            if (cardUploadDisasterMedia != null && tvDisasterMediaFile != null) {
                cardUploadDisasterMedia.setOnClickListener(v -> {
                    currentUploadTextView = tvDisasterMediaFile;
                    currentUploadPreview = null;
                    currentUploadBucket = "request-evidence";
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
                    String loc = etLoc != null ? etLoc.getText().toString().trim() : "";
                    String det = etDet != null ? etDet.getText().toString().trim() : "";
                    String disasterType = HintAdapter.getValue(spinnerDisaster);

                    if (disasterType.isEmpty()) {
                        Toast.makeText(this, "Please select the type of incident", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (loc.isEmpty() && det.isEmpty() && !locationPinned) {
                        Toast.makeText(this, "Please provide the location or details of the emergency", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    String pinnedLoc = locationPinned ? selectedLocationAddress.replace("📍 ", "") : "";
                    String finalLoc = (!loc.isEmpty() ? loc + (pinnedLoc.isEmpty() ? "" : " (" + pinnedLoc + ")") : pinnedLoc).trim();
                    if (finalLoc.isEmpty()) finalLoc = "Barangay Area";

                    String reqId = "DIS-" + (int)(Math.random() * 9000 + 1000);
                    String detailsText = det.isEmpty() ? "Emergency assistance requested" : det;
                    String fullDetails = "• Disaster ID: " + reqId + "\n• Incident Type: " + disasterType + "\n• Location: " + finalLoc + "\n• Details: " + detailsText + "\n• Reported By: " + prefs.getString("USER_NAME", "Resident") + "\n• Status: Pending Emergency Response";
                    JSONArray attachments = collectAttachments(tvDisasterMediaFile);
                    JSONObject extras = new JSONObject();
                    try {
                        extras.put("attachments", attachments);
                        extras.put("editable_text", detailsText);
                        if (!loc.isEmpty()) extras.put("location_text", loc);
                        if (locationPinned) { extras.put("latitude", selectedLat); extras.put("longitude", selectedLng); }
                    } catch (Exception ignored) {}
                    String localId = saveNewUserRequest("Disaster Report", disasterType + " (" + reqId + ")", getCurrentFormattedDateTime(), "Pending", "disaster", fullDetails, extras);

                    String userId = prefs.getString("USER_ID", "");
                    String disasterDesc = detailsText + "\n\nLocation: " + finalLoc
                            + (locationPinned ? String.format(Locale.US, "\nMap pin: %.6f, %.6f", selectedLat, selectedLng) : "");
                    SupabaseClient.submitReportToSupabase(userId, selectedBarangayCode, "Incident", disasterType, disasterDesc, "high",
                            firstRemoteUrl(attachments),
                            locationPinned ? Double.valueOf(selectedLat) : null,
                            locationPinned ? Double.valueOf(selectedLng) : null,
                            finalLoc, rememberSupabaseId(localId, "reports"));

                    Toast.makeText(this, "Disaster Incident Reported! (" + reqId + ")", Toast.LENGTH_LONG).show();
                    launchPreview(R.layout.request_history);
                });
            }
        }

        // ====================================================================
        // SIGN UP & BRGY ID FORM LOGIC (Spinners + Supabase Integration)
        // ====================================================================
        if (layoutId == R.layout.sign_up || layoutId == R.layout.request_brgy_id) {
            // Spinners with a grey placeholder that can never be chosen
            HintAdapter.attach(findViewById(R.id.spinnerGender), "Select gender", new String[]{"Male", "Female", "Other", "Prefer not to say"});
            HintAdapter.attach(findViewById(R.id.spinnerIdType), "Select ID type (optional)", new String[]{"Passport", "Driver's License", "UMID", "PhilSys ID", "Voter's ID", "Postal ID", "Others"});
            HintAdapter.attach(findViewById(R.id.spinnerCivilStatus), "Select civil status", new String[]{"Single", "Married", "Widowed", "Separated"});

            // Capitalize the first letter of every word in name fields
            NameFormat.attach(findViewById(R.id.etFirstName));
            NameFormat.attach(findViewById(R.id.etMiddleName));
            NameFormat.attach(findViewById(R.id.etLastName));
            NameFormat.attach(findViewById(R.id.etSuffix));

            // Location Cascading Dropdowns
            AutoCompleteTextView spinnerProvince = findViewById(R.id.spinnerProvince);
            AutoCompleteTextView spinnerCity = findViewById(R.id.spinnerCity);
            AutoCompleteTextView spinnerBarangay = findViewById(R.id.spinnerBarangay);

            if (spinnerProvince != null && spinnerCity != null && spinnerBarangay != null) {
                PSGCClient.fetchProvinces(new PSGCClient.LocationCallback() {
                    @Override
                    public void onSuccess(List<PSGCClient.LocationItem> items) {
                        runOnUiThread(() -> {
                            ArrayAdapter<PSGCClient.LocationItem> adapterProv = new ArrayAdapter<>(PreviewActivity.this, R.layout.spinner_dropdown_item, items);
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
                            ArrayAdapter<PSGCClient.LocationItem> fallbackProv = new ArrayAdapter<>(PreviewActivity.this, R.layout.spinner_dropdown_item, fallback);
                            spinnerProvince.setAdapter(fallbackProv);
                            spinnerProvince.setOnClickListener(v -> spinnerProvince.showDropDown());
                            spinnerProvince.setOnFocusChangeListener((v, hasFocus) -> { if (hasFocus) spinnerProvince.showDropDown(); });
                        });
                    }
                });

                spinnerProvince.setOnItemClickListener((parent, view, position, id) -> {
                    PSGCClient.LocationItem selectedProv = (PSGCClient.LocationItem) parent.getItemAtPosition(position);
                    selectedProvinceCode = selectedProv.code;
                    selectedProvinceName = selectedProv.name;
                    selectedCityCode = "";
                    selectedBarangayCode = "";
                    selectedBarangayName = "";
                    spinnerCity.setText("");
                    spinnerBarangay.setText("");
                    
                    PSGCClient.fetchCities(selectedProv.code, new PSGCClient.LocationCallback() {
                        @Override
                        public void onSuccess(List<PSGCClient.LocationItem> items) {
                            runOnUiThread(() -> {
                                ArrayAdapter<PSGCClient.LocationItem> adapterCity = new ArrayAdapter<>(PreviewActivity.this, R.layout.spinner_dropdown_item, items);
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
                    selectedCityCode = selectedCity.code;
                    spinnerBarangay.setText("");
                    selectedBarangayCode = "";
                    selectedBarangayName = "";
                    
                    PSGCClient.fetchBarangays(selectedCity.code, new PSGCClient.LocationCallback() {
                        @Override
                        public void onSuccess(List<PSGCClient.LocationItem> items) {
                            runOnUiThread(() -> {
                                ArrayAdapter<PSGCClient.LocationItem> adapterBrgy = new ArrayAdapter<>(PreviewActivity.this, R.layout.spinner_dropdown_item, items);
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

        if (layoutId == R.layout.request_brgy_id) {
            CardView btnOpenApplicationModal = findViewById(R.id.btnOpenApplicationModal);
            if (btnOpenApplicationModal != null) {
                btnOpenApplicationModal.setOnClickListener(v -> showApplicationTypeDialog());
            }

            // Digital ID preview name & address update
            TextView tvIdResidentName = findViewById(R.id.tvIdResidentName);
            TextView tvIdResidentAddress = findViewById(R.id.tvIdResidentAddress);
            if (tvIdResidentName != null) tvIdResidentName.setText(prefs.getString("USER_NAME", "Resident"));
            if (tvIdResidentAddress != null) tvIdResidentAddress.setText(prefs.getString("USER_ADDRESS", "Barangay Area"));
        }

        // ====================================================================
        // DEDICATED BARANGAY ID APPLICATION FORM SCREEN LOGIC
        // ====================================================================
        if (layoutId == R.layout.request_brgy_id_form) {
            String appType = getIntent().getStringExtra("APP_TYPE");
            if (appType == null || appType.isEmpty()) appType = "New Applicant";

            TextView tvAppTypeHeader = findViewById(R.id.tvAppTypeHeader);
            if (tvAppTypeHeader != null) {
                tvAppTypeHeader.setText("Application Type: " + appType);
            }

            // Spinners with a placeholder that can't be chosen
            Spinner spinnerGender = findViewById(R.id.spinnerGender);
            Spinner spinnerCivilStatus = findViewById(R.id.spinnerCivilStatus);
            HintAdapter.attach(spinnerGender, "Select gender", new String[]{"Male", "Female", "Other", "Prefer not to say"});
            HintAdapter.attach(spinnerCivilStatus, "Select civil status", new String[]{"Single", "Married", "Widowed", "Separated"});

            // Prefill form from user session
            EditText etFirstName = findViewById(R.id.etBrgyIdFirstName);
            EditText etMiddleName = findViewById(R.id.etBrgyIdMiddleName);
            EditText etLastName = findViewById(R.id.etBrgyIdLastName);
            EditText etDOB = findViewById(R.id.etFormDOB);
            EditText etAddress = findViewById(R.id.etCurrentAddress);
            EditText etCell = findViewById(R.id.etCellNo);
            EditText etLengthOfStay = findViewById(R.id.etLengthOfStay);
            NameFormat.attach(etFirstName);
            NameFormat.attach(etMiddleName);
            NameFormat.attach(etLastName);

            String savedFirst = prefs.getString("USER_FIRST_NAME", "");
            String savedLast = prefs.getString("USER_LAST_NAME", "");
            if (!savedFirst.isEmpty() || !savedLast.isEmpty()) {
                if (etFirstName != null) etFirstName.setText(savedFirst);
                if (etMiddleName != null) etMiddleName.setText(prefs.getString("USER_MIDDLE_NAME", ""));
                if (etLastName != null) etLastName.setText(savedLast);
            } else {
                String fullName = prefs.getString("USER_NAME", "");
                if (!fullName.isEmpty()) {
                    String[] nameParts = fullName.split(" ");
                    if (nameParts.length > 0 && etFirstName != null) etFirstName.setText(nameParts[0]);
                    if (nameParts.length > 1 && etLastName != null) etLastName.setText(nameParts[nameParts.length - 1]);
                }
            }
            HintAdapter.selectValue(spinnerGender, prefs.getString("USER_GENDER", ""));
            HintAdapter.selectValue(spinnerCivilStatus, prefs.getString("USER_CIVIL_STATUS", ""));
            if (etAddress != null) etAddress.setText(prefs.getString("USER_ADDRESS", ""));
            if (etCell != null) etCell.setText(prefs.getString("USER_PHONE", "").replace("+63", ""));

            // Date picker for DOB
            if (etDOB != null) {
                etDOB.setOnClickListener(v -> {
                    Calendar cal = Calendar.getInstance();
                    DatePickerDialog datePicker = new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
                        etDOB.setText(String.format(Locale.US, "%02d/%02d/%04d", month + 1, dayOfMonth, year));
                    }, cal.get(Calendar.YEAR) - 20, cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
                    datePicker.getDatePicker().setMaxDate(System.currentTimeMillis());
                    datePicker.show();
                });
            }

            // Live selfie (front camera) with preview
            CardView cardSelfie = findViewById(R.id.cardSelfie);
            TextView tvSelfieTitle = findViewById(R.id.tvSelfieTitle);
            TextView tvSelfieSub = findViewById(R.id.tvSelfieSub);
            ImageView ivSelfiePreview = findViewById(R.id.ivSelfiePreview);
            if (cardSelfie != null && tvSelfieSub != null) {
                cardSelfie.setOnClickListener(v -> {
                    if (tvSelfieTitle != null) tvSelfieTitle.setText("📷 Retake Selfie");
                    launchSelfieCamera(tvSelfieSub, ivSelfiePreview, "avatars");
                });
            }

            // Upload ID & Proof listeners
            CardView cardUploadBrgyId = findViewById(R.id.cardUploadBrgyId);
            TextView tvBrgyIdFile = findViewById(R.id.tvBrgyIdFile);
            if (cardUploadBrgyId != null) {
                cardUploadBrgyId.setOnClickListener(v -> {
                    currentUploadTextView = tvBrgyIdFile;
                    currentUploadPreview = null;
                    currentUploadBucket = "id-photos";
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.setType("*/*");
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/pdf"});
                    filePickerLauncher.launch(intent);
                });
            }

            CardView cardUploadProof = findViewById(R.id.cardUploadProof);
            TextView tvProofFile = findViewById(R.id.tvProofFile);
            if (cardUploadProof != null) {
                cardUploadProof.setOnClickListener(v -> {
                    currentUploadTextView = tvProofFile;
                    currentUploadPreview = null;
                    currentUploadBucket = "proof-of-residency";
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.setType("*/*");
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/pdf"});
                    filePickerLauncher.launch(intent);
                });
            }

            // Submit Application Button
            CardView btnSubmitBrgyId = findViewById(R.id.btnSubmitBrgyId);
            if (btnSubmitBrgyId != null) {
                final String finalAppType = appType;
                btnSubmitBrgyId.setOnClickListener(v -> {
                    String first = NameFormat.capitalizeWords(etFirstName != null ? etFirstName.getText().toString().trim() : "");
                    String middle = NameFormat.capitalizeWords(etMiddleName != null ? etMiddleName.getText().toString().trim() : "");
                    String last = NameFormat.capitalizeWords(etLastName != null ? etLastName.getText().toString().trim() : "");
                    String dob = etDOB != null ? etDOB.getText().toString().trim() : "";
                    String gender = HintAdapter.getValue(spinnerGender);
                    String civil = HintAdapter.getValue(spinnerCivilStatus);
                    String address = etAddress != null ? etAddress.getText().toString().trim() : "";
                    String cell = etCell != null ? etCell.getText().toString().trim() : "";
                    String stay = etLengthOfStay != null ? etLengthOfStay.getText().toString().trim() : "";

                    if (first.isEmpty() || last.isEmpty()) { Toast.makeText(this, "Please enter your first and last name", Toast.LENGTH_SHORT).show(); return; }
                    if (dob.isEmpty()) { Toast.makeText(this, "Please select your date of birth", Toast.LENGTH_SHORT).show(); return; }
                    if (gender.isEmpty()) { Toast.makeText(this, "Please select your gender", Toast.LENGTH_SHORT).show(); return; }
                    if (civil.isEmpty()) { Toast.makeText(this, "Please select your civil status", Toast.LENGTH_SHORT).show(); return; }
                    if (address.isEmpty()) { Toast.makeText(this, "Please enter your current address", Toast.LENGTH_SHORT).show(); return; }
                    if (tvSelfieSub == null || !filledUploadViews.contains(tvSelfieSub.getId())) { Toast.makeText(this, "Please take your selfie for verification", Toast.LENGTH_SHORT).show(); return; }
                    if (tvBrgyIdFile == null || !filledUploadViews.contains(tvBrgyIdFile.getId())) { Toast.makeText(this, "Please upload a photo of your valid ID", Toast.LENGTH_SHORT).show(); return; }

                    String fullName = (first + (middle.isEmpty() ? "" : " " + middle) + " " + last).trim();
                    String reqId = "BRGY-ID-" + (int)(Math.random() * 9000 + 1000);
                    String details = "• Application ID: " + reqId
                            + "\n• Type: " + finalAppType
                            + "\n• Applicant Name: " + fullName
                            + "\n• Date of Birth: " + dob
                            + "\n• Gender: " + gender
                            + "\n• Civil Status: " + civil
                            + "\n• Address: " + address
                            + (cell.isEmpty() ? "" : "\n• Mobile: +63" + cell.replaceFirst("^\\+?63", "").replaceFirst("^0", ""))
                            + (stay.isEmpty() ? "" : "\n• Years of stay: " + stay)
                            + "\n• Status: Submitted & Pending Review";
                    JSONArray attachments = collectAttachments(tvSelfieSub, tvBrgyIdFile, tvProofFile);
                    JSONObject extras = new JSONObject();
                    try {
                        extras.put("attachments", attachments);
                        extras.put("editable_text", details);
                    } catch (Exception ignored) {}
                    String localId = saveNewUserRequest("Barangay ID Application", finalAppType + " (" + reqId + ")", getCurrentFormattedDateTime(), "Pending", "barangay_id", details, extras);

                    String userId = prefs.getString("USER_ID", "");
                    String accessToken = prefs.getString("USER_ACCESS_TOKEN", "");
                    android.util.Log.d("BrgyIdSubmit", "userId=" + userId + " tokenLen=" + accessToken.length() + " brgyCode=" + selectedBarangayCode);
                    SupabaseClient.submitRequestToSupabase(userId, selectedBarangayCode, "barangay_id", "Barangay ID Application: " + finalAppType,
                            details, address, selectedLat, selectedLng, firstRemoteUrl(attachments), rememberSupabaseId(localId, "requests"));

                    Toast.makeText(this, "Barangay ID Application Submitted! (" + reqId + ")", Toast.LENGTH_LONG).show();
                    launchPreview(R.layout.request_history);
                    finish();
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
                    String firstName = NameFormat.capitalizeWords(etFirstName != null ? etFirstName.getText().toString().trim() : "");
                    String lastName = NameFormat.capitalizeWords(etLastName != null ? etLastName.getText().toString().trim() : "");
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

                    String middleName = NameFormat.capitalizeWords(etMiddleName != null ? etMiddleName.getText().toString().trim() : "");
                    String suffix = NameFormat.capitalizeWords(etSuffix != null ? etSuffix.getText().toString().trim() : "");
                    String dob = etFormDOB != null ? etFormDOB.getText().toString().trim() : "";
                    String gender = HintAdapter.getValue(spinnerGender);
                    String civilStatus = HintAdapter.getValue(spinnerCivilStatus);
                    String idType = HintAdapter.getValue(spinnerIdType);
                    TextView tvUploadIdText = findViewById(R.id.tvUploadIdText);
                    String idPhotoUrl = (tvUploadIdText != null && tvUploadIdText.getTag() != null) ? tvUploadIdText.getTag().toString() : "";
                    EditText etStreet = findViewById(R.id.etAddress);
                    String street = etStreet != null ? etStreet.getText().toString().trim() : "";
                    String province = TextFix.fix(spinnerProvince != null ? spinnerProvince.getText().toString().trim() : "");
                    String city = TextFix.fix(spinnerCity != null ? spinnerCity.getText().toString().trim() : "");
                    String barangay = TextFix.fix(spinnerBarangay != null ? spinnerBarangay.getText().toString().trim() : "");

                    // Simple frontend validation
                    if (email.isEmpty() || password.isEmpty() || firstName.isEmpty() || lastName.isEmpty() || phone.isEmpty()) {
                        Toast.makeText(this, "Please fill in all required fields", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (phone.length() != 10) {
                        Toast.makeText(this, "Mobile number must be exactly 10 digits (e.g. 9123456789)", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (gender.isEmpty()) {
                        Toast.makeText(this, "Please select your gender", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (civilStatus.isEmpty()) {
                        Toast.makeText(this, "Please select your civil status", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (province.isEmpty() || city.isEmpty() || barangay.isEmpty()) {
                        Toast.makeText(this, "Please select your province, city / municipality and barangay", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (selectedBarangayCode.isEmpty() || !barangay.equalsIgnoreCase(TextFix.fix(selectedBarangayName))) {
                        Toast.makeText(this, "Please pick your barangay from the dropdown list (typing it isn't enough)", Toast.LENGTH_LONG).show();
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

                        // Individual address parts (codes come from the PSGC dropdown picks)
                        String brgyCode = selectedBarangayCode;
                        String brgyName = !selectedBarangayName.isEmpty() ? selectedBarangayName : barangay;
                        String regionCode = !selectedProvinceCode.isEmpty() ? selectedProvinceCode : (!selectedCityCode.isEmpty() ? selectedCityCode : brgyCode);
                        String region = PSGCClient.regionNameFromCode(regionCode);
                        if (region.isEmpty() && (province.contains("NCR") || province.contains("Metro Manila"))) region = "NCR";
                        String fullAddress = SupabaseClient.buildFullAddress(street, brgyName, city, province);
                        String isoDob = SupabaseClient.formatToIsoDate(dob);
                        final String finalRegion = region;

                        // Everything goes into user_metadata -> the Auth trigger creates the profiles row from it
                        JSONObject userData = new JSONObject();
                        userData.put("first_name", firstName);
                        userData.put("middle_name", middleName);
                        userData.put("last_name", lastName);
                        userData.put("suffix", suffix);
                        userData.put("full_name", constructedFullName);
                        userData.put("mobile_number", formattedPhone);
                        userData.put("phone", formattedPhone);
                        userData.put("email", email);
                        if (!isoDob.isEmpty()) {
                            userData.put("birthdate", isoDob);
                            userData.put("birth_date", isoDob);
                        }
                        userData.put("sex", gender);
                        userData.put("gender", gender);
                        userData.put("marital_status", civilStatus);
                        userData.put("civil_status", civilStatus);
                        userData.put("street", street);
                        userData.put("barangay", brgyName);
                        userData.put("psgc_code", SupabaseClient.dbPsgc(brgyCode));       // the ONE barangay field (10-digit PSGC)
                        userData.put("city", city);
                        userData.put("province", province);
                        userData.put("region", region);
                        userData.put("address", fullAddress);
                        userData.put("current_address", fullAddress);
                        userData.put("provincial_address", province);
                        userData.put("id_type", idType);
                        if (!idPhotoUrl.isEmpty()) {
                            userData.put("id_photo_url", idPhotoUrl);
                        }
                        // No role / approval status here: the database makes every new account a
                        // pending resident, and only an official of this barangay can approve it.

                        SupabaseClient.signUpUser(email, password, userData, new Callback() {
                            @Override
                            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                                runOnUiThread(() -> Toast.makeText(PreviewActivity.this, "Network Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
                            }

                            @Override
                            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                                String responseBody = response.body() != null ? new String(response.body().bytes(), StandardCharsets.UTF_8) : "";
                                runOnUiThread(() -> {
                                    if (response.isSuccessful()) {
                                        String userId = "";
                                        boolean hasSession = false;
                                        try {
                                            JSONObject json = new JSONObject(responseBody);
                                            // With email confirmation ON the body is the user; with it OFF it is a session {access_token, user}
                                            if (json.has("user") && json.optJSONObject("user") != null) {
                                                userId = json.getJSONObject("user").optString("id", "");
                                            }
                                            if (userId.isEmpty()) userId = json.optString("id", "");
                                            if (!json.optString("access_token", "").isEmpty()) {
                                                SupabaseClient.saveSession(json);
                                                hasSession = true;
                                            }
                                        } catch (Exception ignored) {}

                                        // Save ALL address fields locally
                                        SharedPreferences.Editor ed = prefs.edit()
                                                .putString("USER_NAME", constructedFullName)
                                                .putString("USER_FIRST_NAME", firstName)
                                                .putString("USER_MIDDLE_NAME", middleName)
                                                .putString("USER_LAST_NAME", lastName)
                                                .putString("USER_GENDER", gender)
                                                .putString("USER_CIVIL_STATUS", civilStatus)
                                                .putString("USER_PHONE", formattedPhone)
                                                .putString("USER_EMAIL", email)
                                                .putString("USER_STREET", street)
                                                .putString("USER_BARANGAY", brgyName)
                                                .putString("USER_BARANGAY_CODE", brgyCode)
                                                .putString("USER_CITY", city)
                                                .putString("USER_PROVINCE", province)
                                                .putString("USER_REGION", finalRegion)
                                                .putString("USER_ADDRESS", fullAddress)
                                                // New accounts are pending until an official approves them
                                                .putBoolean("IS_LOGGED_IN", false);
                                        if (!userId.isEmpty()) ed.putString("USER_ID", userId);
                                        ed.apply();

                                        // The Auth trigger creates the profiles row. If we already have a session,
                                        // also PATCH the address columns with the user's JWT in case the trigger
                                        // doesn't copy every address field from the metadata.
                                        if (hasSession && !userId.isEmpty()) {
                                            SupabaseClient.syncUserAddressToSupabase(userId, street, brgyName, brgyCode, city, province, finalRegion, new Callback() {
                                                @Override public void onFailure(@NonNull Call c, @NonNull IOException e) {}
                                                @Override public void onResponse(@NonNull Call c, @NonNull Response r) throws IOException { r.close(); }
                                            });
                                        }

                                        Toast.makeText(PreviewActivity.this, "Account Created Successfully!", Toast.LENGTH_LONG).show();

                                        Intent intent = new Intent(PreviewActivity.this, PreviewActivity.class);
                                        intent.putExtra("LAYOUT_ID", R.layout.account_review_ntf);
                                        intent.putExtra("SELECTED_BARANGAY", "Brgy. " + brgyName);
                                        startActivity(intent);
                                        finish();
                                    } else {
                                        // Registration failed - do NOT save session
                                        try {
                                            JSONObject errorJson = new JSONObject(responseBody);
                                            String errorMsg = errorJson.optString("msg", errorJson.optString("message", errorJson.optString("error_description", "Registration rejected by server.")));
                                            if (response.code() == 429 || errorMsg.toLowerCase().contains("rate limit")) {
                                                Toast.makeText(PreviewActivity.this, "Server Email Limit Exceeded: Supabase limits confirmation emails to 3 per hour on default SMTP. Please wait a bit or disable email confirmation in Supabase Dashboard.", Toast.LENGTH_LONG).show();
                                            } else {
                                                Toast.makeText(PreviewActivity.this, "Sign Up Failed: " + errorMsg, Toast.LENGTH_LONG).show();
                                            }
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
                chatMessages = messages;
                chatRecycler = rvChatMessages;
                reportInterviewStep = -1;
                // Starting with welcome message
                messages.add(new ChatMessage(false, "Hello! I am your Barangay SuperApp Assistant. How can I help you today?\n\n"
                        + "Tip: I can also write a report for you. Just type \"gumawa ng report\" or tell me what happened.", false));

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

                                        if ("start_report".equals(msg.promptAction)) {
                                            startReportInterview();
                                        } else if ("open_report".equals(msg.promptAction)) {
                                            openDraftReport(pendingReportDraft);
                                        } else {
                                            // Redirect to Request Document page!
                                            launchPreview(R.layout.request_form);
                                        }
                                    });
                                }

                                if (btnPromptNo != null) {
                                    btnPromptNo.setOnClickListener(v -> {
                                        msg.promptHandled = true;
                                        layoutActionButtons.setVisibility(View.GONE);

                                        // Add user response "No"
                                        messages.add(new ChatMessage(true, "No", false));

                                        // Bot replies depending on what was offered
                                        String noReply = "open_report".equals(msg.promptAction)
                                                ? "Okay, hindi ko muna ipapasa. Type \"gumawa ng report\" anytime para magsimula ulit."
                                                : "Alright then! I'm here ready to help.";
                                        messages.add(new ChatMessage(false, noReply, false));
                                        
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

                        // AI report builder: answering the interview questions
                        if (reportInterviewStep >= 0) {
                            handleReportAnswer(userText);
                            return;
                        }
                        // Sounds like something to report -> offer to build the report
                        if (ReportAssistant.looksLikeReportIntent(userText)) {
                            pendingReportSeed = userText;
                            addBotMessage("Gusto mo bang tulungan kitang gumawa ng report? Tatanungin kita ng ilang maikling tanong, "
                                    + "tapos ikaw ang magre-review at mag-e-edit bago ito ipasa.", true, "start_report");
                            return;
                        }

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
                            String responseBody = response.body() != null ? new String(response.body().bytes(), StandardCharsets.UTF_8) : "";
                            runOnUiThread(() -> {
                                if (response.isSuccessful()) {
                                    handleSignInSuccess(prefs, responseBody);
                                } else {
                                    // Handle Incorrect Password / Account missing
                                    try {
                                        JSONObject errorJson = new JSONObject(responseBody);
                                        String errorMsg = errorJson.optString("error_description", errorJson.optString("msg", "Invalid login credentials."));
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
            // Announcements are based on the barangay, so it must never be empty
            if (prefs.getBoolean("IS_LOGGED_IN", false)
                    && (prefs.getString("USER_BARANGAY_CODE", "").isEmpty() || prefs.getString("USER_BARANGAY", "").isEmpty())) {
                new AlertDialog.Builder(this)
                        .setTitle("Set your barangay")
                        .setMessage("We couldn't find your barangay. Please set your address so you get the right announcements and your reports go to the right barangay.")
                        .setCancelable(false)
                        .setPositiveButton("Set address", (d, w) -> showChangeAddressDialog(prefs))
                        .show();
            }

            TextView tvUserName = findViewById(R.id.tvUserName);
            if (tvUserName != null) {
                String savedName = prefs.getString("USER_NAME", "Resident");
                tvUserName.setText(savedName);
            }

            // Reflect profile picture on Dashboard header!
            ImageView ivHeaderProfileImage = findViewById(R.id.ivHeaderProfileImage);
            loadAvatarIntoImageView(ivHeaderProfileImage);

            CardView btnProfilePicture = findViewById(R.id.btnProfilePicture);
            if (btnProfilePicture != null) {
                btnProfilePicture.setOnClickListener(v -> launchPreview(R.layout.profile));
            }


            TextView tvWeatherDate = findViewById(R.id.tvWeatherDate);
            ImageView ivWeatherIcon = findViewById(R.id.ivWeatherIcon);
            fetchLiveWeather(tvWeatherDate, ivWeatherIcon);

            // The adapter must exist BEFORE the data arrives (it loads asynchronously),
            // otherwise the list never shows anything.
            RecyclerView rvDashboardAnnouncements = findViewById(R.id.rvDashboardAnnouncements);
            if (rvDashboardAnnouncements != null) {
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
                            
                            holder.itemView.setOnClickListener(v -> showAnnouncementDetailsModal(item));
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

            // Announcements for the user's barangay (PSGC code): now, every 60 s, and when the app comes back
            startLiveUpdates(() -> {
                loadBarangayAnnouncements(prefs);
                verifyAccountStillApproved(prefs);
                syncHistoryStatuses(prefs, null);   // notifies the phone about request / report updates
            }, 60000);

            // Phone notifications for request / report updates (also while the app is closed)
            if (prefs.getBoolean("IS_LOGGED_IN", false)) {
                HistorySync.ensureChannel(this);
                StatusCheckWorker.schedule(this);
                if (android.os.Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                        && !prefs.getBoolean("ASKED_NOTIFICATION_PERMISSION", false)) {
                    prefs.edit().putBoolean("ASKED_NOTIFICATION_PERMISSION", true).apply();
                    requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 4242);
                }
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
        // PROFILE SCREEN LOGIC (Read-only fields, Complete Editable Address, Upload Photo, Log Out)
        // ====================================================================
        if (layoutId == R.layout.profile) {
            CardView btnChangeProfilePic = findViewById(R.id.btnChangeProfilePic);
            ImageView ivProfileImage = findViewById(R.id.ivProfileImage);
            CardView btnChangeAddress = findViewById(R.id.btnChangeAddress);
            CardView btnLogOut = findViewById(R.id.btnLogOut);

            // Non-editable info display
            bindProfileInfo(prefs);

            // Load saved avatar picture from local app storage or remote URL
            loadAvatarIntoImageView(ivProfileImage);

            // Upload profile picture click
            if (btnChangeProfilePic != null) {
                btnChangeProfilePic.setOnClickListener(v -> {
                    currentUploadTextView = null; // signifies profile picture upload
                    currentUploadBucket = "avatars";
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.setType("image/*");
                    filePickerLauncher.launch(intent);
                });
            }

            // Change Address -> edit dialog -> confirmation modal -> save + sync to profiles
            if (btnChangeAddress != null) {
                btnChangeAddress.setOnClickListener(v -> showChangeAddressDialog(prefs));
            }

            // Log Out
            if (btnLogOut != null) {
                btnLogOut.setOnClickListener(v -> {
                    SharedPreferences.Editor ed = prefs.edit().putBoolean("IS_LOGGED_IN", false);
                    clearUserPrefs(ed);
                    ed.apply();
                    SupabaseClient.clearSession();
                    StatusCheckWorker.cancel(this);
                    Toast.makeText(this, "Logged Out Successfully", Toast.LENGTH_SHORT).show();
                    Intent intent = new Intent(PreviewActivity.this, PreviewActivity.class);
                    intent.putExtra("LAYOUT_ID", R.layout.sign_in);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    overridePendingTransition(0, 0);
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
                            String desc = item.optString("description_plain", item.optString("description", ""));
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
                            
                            holder.itemView.setOnClickListener(v -> showAnnouncementDetailsModal(item));
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

                // This screen is its own Activity, so it has to load the announcements itself;
                // re-run the filter when they arrive.
                onAnnouncementsUpdated = filterAnnouncements;
                startLiveUpdates(() -> loadBarangayAnnouncements(prefs), 60000);

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
            // No hardcoded numbers: officials add their barangay's hotlines in the web portal,
            // the super admin adds nationwide ones. Shows the last saved list instantly, then refreshes.
            renderEmergencyContacts(prefs.getString("CACHED_EMERGENCY_CONTACTS", ""), true);
            startLiveUpdates(() -> loadEmergencyContacts(prefs), 60000);
        }

        // ====================================================================
        // NOTIFICATIONS (Alerts tab): request / report updates + new announcements,
        // each saying where it came from (the user's barangay or Nationwide)
        // ====================================================================
        if (layoutId == R.layout.notifs) {
            TextView tvEmptyDate = findViewById(R.id.tvEmptyStateDate);
            if (tvEmptyDate != null) tvEmptyDate.setText(new SimpleDateFormat("MMM d, yyyy · EEE", Locale.US).format(new Date()));
            renderNotifications();
            HistorySync.markInboxRead(this);
            startLiveUpdates(() -> syncHistoryStatuses(prefs, () -> {
                renderNotifications();
                HistorySync.markInboxRead(this);
            }), 20000);
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

                // Populate allRequests with the user's submissions (newest first, cancelled at the bottom)
                reloadLocalHistory(prefs);
                
                Runnable updateUI = () -> {
                    HistorySync.sortEntries(allRequests);
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

                // Show what the barangay did (web portal or Supabase): now, every 20 s, and when the app comes back
                startLiveUpdates(() -> syncHistoryStatuses(prefs, updateUI), 20000);

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
                        
                        String selectedType = "New Applicant";
                        if (selectedId == R.id.rbRenewal) {
                            selectedType = "Renewal";
                        } else if (selectedId == R.id.rbTransfer) {
                            selectedType = "Replacement / Transfer";
                        }
                        
                        dialog.dismiss();

                        Intent intent = new Intent(PreviewActivity.this, PreviewActivity.class);
                        intent.putExtra("LAYOUT_ID", R.layout.request_brgy_id_form);
                        intent.putExtra("APP_TYPE", selectedType);
                        startActivity(intent);
                        overridePendingTransition(0, 0);
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
        saveNewUserRequest(type, desc, date, status, category, fullDetails, null);
    }

    /**
     * Saves a submission to the local history. extras may contain:
     * attachments (JSONArray of URLs / local image paths), editable_text (what the user typed),
     * location, latitude, longitude, title. Returns the local id.
     */
    private String saveNewUserRequest(String type, String desc, String date, String status, String category, String fullDetails, JSONObject extras) {
        String localId = UUID.randomUUID().toString();
        try {
            SharedPreferences prefs = getSharedPreferences("AppSession", MODE_PRIVATE);
            String existingJson = prefs.getString("USER_SUBMITTED_REQUESTS", "[]");
            JSONArray array = new JSONArray(existingJson);

            JSONObject newReq = new JSONObject();
            newReq.put("local_id", localId);
            newReq.put("created_at", HistorySync.nowIso());
            newReq.put("requestType", type);
            newReq.put("description", desc);
            newReq.put("dateSubmitted", "Submitted on " + date);
            newReq.put("statusText", status);
            newReq.put("status", status);
            newReq.put("category", category);
            newReq.put("full_details", fullDetails);
            if (extras != null) {
                JSONArray keys = extras.names();
                if (keys != null) for (int i = 0; i < keys.length(); i++) newReq.put(keys.getString(i), extras.get(keys.getString(i)));
            }

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
        return localId;
    }

    private interface JsonMutator {
        void apply(JSONObject o) throws Exception;
    }

    /** Finds a saved request by local_id and applies the change. */
    private synchronized void updateLocalRequest(String localId, JsonMutator mutator) {
        if (localId == null) return;
        try {
            SharedPreferences prefs = getSharedPreferences("AppSession", MODE_PRIVATE);
            JSONArray arr = new JSONArray(prefs.getString("USER_SUBMITTED_REQUESTS", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (localId.equals(o.optString("local_id"))) {
                    mutator.apply(o);
                    break;
                }
            }
            prefs.edit().putString("USER_SUBMITTED_REQUESTS", arr.toString()).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Supabase insert callback that remembers the server row id (needed to edit the request later). */
    /**
     * Callback for a Supabase insert: remembers the server row id + table (needed to edit it later),
     * and TELLS the user if the barangay didn't receive it (instead of failing silently).
     */
    private Callback rememberSupabaseId(String localId, String table) {
        final android.content.Context appCtx = getApplicationContext();
        return new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                android.util.Log.e("Supabase-" + table, "Failed: " + e.getMessage());
                updateLocalRequest(localId, o -> o.put("sync_error", String.valueOf(e.getMessage())));
                new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(appCtx,
                        "Saved on your phone, but NOT sent to the barangay: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String body = response.body() != null ? new String(response.body().bytes(), StandardCharsets.UTF_8) : "";
                android.util.Log.d("Supabase-" + table, "Response: " + response.code() + " " + body);
                try {
                    JSONArray arr = new JSONArray(body);
                    if (arr.length() > 0) {
                        String supaId = arr.getJSONObject(0).optString("id", "");
                        if (!supaId.isEmpty()) updateLocalRequest(localId, o -> {
                            o.put("supabase_id", supaId);
                            o.put("supabase_table", table);
                            o.remove("sync_error");
                        });
                    }
                } catch (Exception ignored) {}
            }
        };
    }

    private static boolean isImageSource(String src) {
        String l = src.toLowerCase(Locale.US);
        return l.startsWith("data:image") || l.startsWith("/") || l.matches(".*\\.(jpg|jpeg|png|webp|gif|heic)(\\?.*)?$");
    }

    private void openAttachment(String src) {
        try {
            Uri uri;
            String mime = null;
            if (src.startsWith("/")) {
                uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", new File(src));
                mime = "image/*";
            } else {
                uri = Uri.parse(src);
            }
            Intent i = new Intent(Intent.ACTION_VIEW);
            if (mime != null) i.setDataAndType(uri, mime); else i.setData(uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Can't open this file on this device.", Toast.LENGTH_SHORT).show();
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
        TextView tvMediaLabel = dialogView.findViewById(R.id.tvDialogMediaLabel);
        View scrollMedia = dialogView.findViewById(R.id.scrollDialogMedia);
        LinearLayout layoutMedia = dialogView.findViewById(R.id.layoutDialogMedia);
        CardView btnEdit = dialogView.findViewById(R.id.btnEditRequest);
        CardView btnClose = dialogView.findViewById(R.id.btnCloseDialog);

        String type = req.optString("requestType", req.optString("type", "Request"));
        String desc = req.optString("description", "");
        String statusKey = HistorySync.statusKey(req.optString("status_key", req.optString("status", "pending")));
        String[] look = HistorySync.statusDisplay(statusKey);
        String status = look[0];
        String date = req.optString("dateSubmitted", req.optString("date", "Submitted on " + getCurrentFormattedDateTime()));

        boolean isDocument = type.contains("Document") || type.contains("ID");
        // What the resident submitted (document type / purpose / applicant, or the report text)
        String fullDetails = req.optString("full_details", "");
        if (fullDetails.isEmpty()) fullDetails = "• Type: " + type + "\n• Details: " + desc;

        if (tvTitle != null) tvTitle.setText(type);
        if (tvSub != null) tvSub.setText(desc);
        if (tvStatus != null) {
            tvStatus.setText(status);
            tvStatus.setTextColor(Color.parseColor(look[1]));
        }
        CardView cardStatus = dialogView.findViewById(R.id.cardDialogStatus);
        if (cardStatus != null) cardStatus.setCardBackgroundColor(Color.parseColor(look[2]));
        if (tvDate != null) tvDate.setText(date);
        if (tvDetails != null) tvDetails.setText(fullDetails);

        // ---- Latest update from the barangay ----
        String pickup = req.optString("pickup_date", "");
        String remarks = req.optString("admin_remarks", "");
        StringBuilder upd = new StringBuilder();
        switch (statusKey) {
            case "approved":         upd.append("Approved by the barangay."); break;
            case "in_progress":      upd.append("The barangay is working on this."); break;
            case "ready_for_pickup": upd.append("Ready for pickup at the barangay hall."); break;
            case "resolved":         upd.append("Resolved by the barangay."); break;
            case "rejected":         upd.append("Not approved by the barangay."); break;
            case "cancelled":        upd.append("This request was cancelled."); break;
            default: break;
        }
        if (!pickup.isEmpty() && !"null".equals(pickup)) {
            if (upd.length() > 0) upd.append("\n");
            upd.append("Pickup date: ").append(HistorySync.formatIsoDate(pickup, false));
        }
        if (!remarks.isEmpty() && !"null".equals(remarks)) {
            if (upd.length() > 0) upd.append("\n");
            upd.append("Note: ").append(remarks);
        }
        CardView cardUpdate = dialogView.findViewById(R.id.cardDialogUpdate);
        TextView tvUpdateBody = dialogView.findViewById(R.id.tvDialogUpdateBody);
        if (cardUpdate != null && tvUpdateBody != null && upd.length() > 0) {
            cardUpdate.setVisibility(View.VISIBLE);
            cardUpdate.setCardBackgroundColor(Color.parseColor(look[2]));
            tvUpdateBody.setText(upd.toString());
        }

        // ---- Uploaded media ----
        JSONArray attachments = req.optJSONArray("attachments");
        if (attachments == null) {
            attachments = new JSONArray();
            String single = req.optString("attachment_url", req.optString("evidence_url", ""));
            if (!single.isEmpty()) attachments.put(single);
        }
        if (attachments.length() > 0 && layoutMedia != null) {
            if (tvMediaLabel != null) tvMediaLabel.setVisibility(View.VISIBLE);
            if (scrollMedia != null) scrollMedia.setVisibility(View.VISIBLE);
            float d = getResources().getDisplayMetrics().density;
            for (int i = 0; i < attachments.length(); i++) {
                String src = attachments.optString(i, "");
                if (src.isEmpty()) continue;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (110 * d), (int) (110 * d));
                lp.setMarginEnd((int) (8 * d));
                if (isImageSource(src)) {
                    ImageView iv = new ImageView(this);
                    iv.setLayoutParams(lp);
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    iv.setBackgroundColor(Color.parseColor("#E6E8E6"));
                    iv.setContentDescription("Attached photo " + (i + 1));
                    iv.setOnClickListener(v -> openAttachment(src));
                    layoutMedia.addView(iv);
                    loadImageInto(iv, src);
                } else {
                    TextView tv = new TextView(this);
                    tv.setLayoutParams(lp);
                    tv.setGravity(android.view.Gravity.CENTER);
                    tv.setBackgroundColor(Color.parseColor("#DDF0EC"));
                    tv.setTextColor(Color.parseColor("#174A45"));
                    tv.setTextSize(13);
                    String lower = src.toLowerCase(Locale.US);
                    tv.setText(lower.contains(".pdf") ? "📄\nOpen PDF" : (lower.matches(".*\\.(mp4|3gp|mov|webm|mkv).*") ? "🎬\nPlay video" : "📎\nOpen file"));
                    tv.setOnClickListener(v -> openAttachment(src));
                    layoutMedia.addView(tv);
                }
            }
        }

        // ---- Edit (only while still Pending and not a document request) ----
        String editable = req.optString("editable_text", req.optString("full_details", ""));
        if (btnEdit != null && "pending".equals(statusKey) && !editable.isEmpty() && !isDocument) {
            btnEdit.setVisibility(View.VISIBLE);
            btnEdit.setOnClickListener(v -> showEditRequestDialog(req, dialog));
        }

        CardView btnCancel = dialogView.findViewById(R.id.btnCancelRequest);
        if (btnCancel != null && "pending".equals(statusKey)) {
            btnCancel.setVisibility(View.VISIBLE);
            btnCancel.setOnClickListener(v -> showCancelRequestDialog(req, dialog));
        }

        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dialog.dismiss());
        }

        dialog.show();
    }

    /** Lets the user fix what they typed in a pending request; updates local history and Supabase. */
    private void showEditRequestDialog(JSONObject req, AlertDialog detailsDialog) {
        String oldText = req.optString("editable_text", req.optString("full_details", ""));
        String oldLocation = req.optString("location_text", "");
        float d = getResources().getDisplayMetrics().density;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (20 * d), (int) (8 * d), (int) (20 * d), 0);

        TextView lbl = new TextView(this);
        lbl.setText("Details");
        lbl.setTextColor(Color.parseColor("#2A3532"));
        box.addView(lbl);
        EditText etText = new EditText(this);
        etText.setText(oldText);
        etText.setMinLines(4);
        etText.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        etText.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        box.addView(etText);

        EditText etLoc = null;
        if (!oldLocation.isEmpty()) {
            TextView lbl2 = new TextView(this);
            lbl2.setText("Location / landmark");
            lbl2.setTextColor(Color.parseColor("#2A3532"));
            lbl2.setPadding(0, (int) (12 * d), 0, 0);
            box.addView(lbl2);
            etLoc = new EditText(this);
            etLoc.setText(oldLocation);
            box.addView(etLoc);
        }
        final EditText finalEtLoc = etLoc;
        final String targetOldText = oldText;

        new AlertDialog.Builder(this)
                .setTitle("Edit " + req.optString("requestType", "request"))
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save changes", (dlg, which) -> {
                    String newText = etText.getText().toString().trim();
                    String newLoc = finalEtLoc != null ? finalEtLoc.getText().toString().trim() : oldLocation;
                    if (newText.isEmpty()) {
                        Toast.makeText(this, "Details can't be empty.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String full = req.optString("full_details", "");
                    if (!targetOldText.isEmpty() && full.contains(targetOldText)) {
                        full = full.replace(targetOldText, newText);
                    } else {
                        full = newText;
                    }
                    if (!oldLocation.isEmpty() && !newLoc.isEmpty()) full = full.replace(oldLocation, newLoc);
                    final String newFull = full;
                    final String localId = req.optString("local_id", "");
                    updateLocalRequest(localId, o -> {
                        o.put("editable_text", newText);
                        o.put("full_details", newFull);
                        if (!newLoc.isEmpty()) o.put("location_text", newLoc);
                        o.put("edited", true);
                    });

                    String supaId = req.optString("supabase_id", "");
                    if (!supaId.isEmpty()) {
                        try {
                            JSONObject fields = new JSONObject();
                            fields.put("description", newFull);
                            if (!newLoc.isEmpty() && !oldLocation.isEmpty()) fields.put("location_address", newLoc);
                            SupabaseClient.updateRequest(req.optString("supabase_table", "requests"), supaId, fields, new Callback() {
                                @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                                    runOnUiThread(() -> Toast.makeText(PreviewActivity.this, "Saved on this device; couldn't sync: " + e.getMessage(), Toast.LENGTH_LONG).show());
                                }
                                @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                                    String body = response.body() != null ? new String(response.body().bytes(), StandardCharsets.UTF_8) : "";
                                    boolean ok = response.isSuccessful() && !body.trim().equals("[]");
                                    runOnUiThread(() -> Toast.makeText(PreviewActivity.this,
                                            ok ? "Request updated!" : "Saved on this device; the barangay copy wasn't updated (HTTP " + response.code() + ").",
                                            Toast.LENGTH_LONG).show());
                                }
                            });
                        } catch (Exception ignored) {}
                    } else {
                        Toast.makeText(this, "Request updated!", Toast.LENGTH_SHORT).show();
                    }
                    detailsDialog.dismiss();
                    recreate(); // refresh the history list
                })
                .show();
    }

    private void showCancelRequestDialog(JSONObject req, AlertDialog detailsDialog) {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (20 * d), (int) (8 * d), (int) (20 * d), 0);

        TextView lbl = new TextView(this);
        lbl.setText("Reason for cancellation");
        lbl.setTextColor(Color.parseColor("#2A3532"));
        lbl.setPadding(0, 0, 0, (int) (8 * d));
        box.addView(lbl);

        RadioGroup rg = new RadioGroup(this);
        String[] reasons = {"Found the document elsewhere", "No longer needed", "Mistake in application", "Other"};
        for (int i = 0; i < reasons.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(reasons[i]);
            rb.setId(View.generateViewId());
            rg.addView(rb);
        }
        rg.check(rg.getChildAt(0).getId());
        box.addView(rg);

        EditText etOther = new EditText(this);
        etOther.setHint("Please specify");
        etOther.setVisibility(View.GONE);
        box.addView(etOther);

        rg.setOnCheckedChangeListener((group, checkedId) -> {
            RadioButton rb = group.findViewById(checkedId);
            if (rb != null && "Other".equals(rb.getText().toString())) {
                etOther.setVisibility(View.VISIBLE);
            } else {
                etOther.setVisibility(View.GONE);
            }
        });

        new AlertDialog.Builder(this)
                .setTitle("Cancel Request")
                .setView(box)
                .setNegativeButton("Back", null)
                .setPositiveButton("Confirm Cancellation", (dlg, which) -> {
                    RadioButton selectedRb = rg.findViewById(rg.getCheckedRadioButtonId());
                    String reason = selectedRb != null ? selectedRb.getText().toString() : "";
                    if ("Other".equals(reason)) {
                        reason = etOther.getText().toString().trim();
                        if (reason.isEmpty()) reason = "Other";
                    }
                    final String finalReason = "[Cancelled by User] Reason: " + reason;

                    final String localId = req.optString("local_id", "");
                    updateLocalRequest(localId, o -> {
                        o.put("status", "Cancelled");
                        o.put("status_key", "cancelled");
                        o.put("statusText", "Cancelled");
                        o.put("statusColorHex", "#CC4E42");
                        o.put("statusBgHex", "#FCEBEA");
                        String oldDesc = o.optString("description", "");
                        o.put("description", oldDesc + "\n\n" + finalReason);
                    });

                    String supaId = req.optString("supabase_id", "");
                    if (!supaId.isEmpty()) {
                        try {
                            JSONObject fields = new JSONObject();
                            fields.put("status", "cancelled");
                            
                            // Get old description to append the cancellation reason
                            String oldFull = req.optString("full_details", req.optString("description", ""));
                            fields.put("description", oldFull + "\n\n" + finalReason);

                            SupabaseClient.updateRequest(req.optString("supabase_table", "requests"), supaId, fields, new Callback() {
                                @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                                    runOnUiThread(() -> Toast.makeText(PreviewActivity.this, "Saved on this device; couldn't sync: " + e.getMessage(), Toast.LENGTH_LONG).show());
                                }
                                @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                                    String body = response.body() != null ? new String(response.body().bytes(), StandardCharsets.UTF_8) : "";
                                    boolean ok = response.isSuccessful() && !body.trim().equals("[]");
                                    runOnUiThread(() -> Toast.makeText(PreviewActivity.this,
                                            ok ? "Request cancelled." : "Saved on this device; the barangay copy wasn't updated (HTTP " + response.code() + ").",
                                            Toast.LENGTH_LONG).show());
                                }
                            });
                        } catch (Exception ignored) {}
                    } else {
                        Toast.makeText(this, "Request cancelled.", Toast.LENGTH_SHORT).show();
                    }
                    detailsDialog.dismiss();
                    recreate(); // refresh the history list
                })
                .show();
    }

    private void showAnnouncementDetailsModal(JSONObject item) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_announcement_details, null);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        TextView tvTitle = dialogView.findViewById(R.id.tvDialogAnnounceTitle);
        TextView tvDate = dialogView.findViewById(R.id.tvDialogAnnounceDate);
        TextView tvCat = dialogView.findViewById(R.id.tvDialogAnnounceCategory);
        CardView cardCat = dialogView.findViewById(R.id.cardDialogAnnounceCategory);
        CardView cardIcon = dialogView.findViewById(R.id.cardDialogAnnounceIcon);
        ImageView ivIcon = dialogView.findViewById(R.id.ivDialogAnnounceIcon);
        LinearLayout layoutContent = dialogView.findViewById(R.id.layoutDialogAnnounceContent);
        CardView btnClose = dialogView.findViewById(R.id.btnCloseAnnounceDialog);

        String title = item.optString("title", "");
        String date = item.optString("date", "");
        String cat = item.optString("category", "General");
        String colorHex = item.optString("categoryColorHex", "#247D76");
        String bgHex = item.optString("categoryBgHex", "#DFF2F0");
        String body = item.optString("description", ""); 

        if (tvTitle != null) tvTitle.setText(title);
        if (tvDate != null) tvDate.setText(date);
        if (tvCat != null) {
            tvCat.setText(cat);
            tvCat.setTextColor(Color.parseColor(colorHex));
        }
        if (cardCat != null) cardCat.setCardBackgroundColor(Color.parseColor(bgHex));
        if (cardIcon != null) cardIcon.setCardBackgroundColor(Color.parseColor(bgHex));
        if (ivIcon != null) {
            ivIcon.setColorFilter(Color.parseColor(colorHex), android.graphics.PorterDuff.Mode.SRC_IN);
        }

        if (layoutContent != null) {
            parseAndAddMarkdown(body, layoutContent);
        }

        if (btnClose != null) {
            btnClose.setCardBackgroundColor(Color.parseColor(colorHex));
            btnClose.setOnClickListener(v -> dialog.dismiss());
        }

        dialog.show();
    }

    private void parseAndAddMarkdown(String markdown, LinearLayout container) {
        float d = getResources().getDisplayMetrics().density;
        java.util.regex.Pattern imgPattern = java.util.regex.Pattern.compile("!\\[.*?\\]\\((.*?)\\)");
        java.util.regex.Matcher m = imgPattern.matcher(markdown);
        int lastIndex = 0;
        
        while (m.find()) {
            String textBefore = markdown.substring(lastIndex, m.start());
            if (!textBefore.trim().isEmpty()) {
                addHtmlTextViewToContainer(textBefore, container, d);
            }
            String imgUrl = m.group(1);
            addImageViewToContainer(imgUrl, container, d);
            lastIndex = m.end();
        }
        if (lastIndex < markdown.length()) {
            String textAfter = markdown.substring(lastIndex);
            if (!textAfter.trim().isEmpty()) {
                addHtmlTextViewToContainer(textAfter, container, d);
            }
        }
    }

    private void addHtmlTextViewToContainer(String md, LinearLayout container, float d) {
        // Same rules as the web portal editor. Escape first so "<" or "&" in the text show as typed.
        String html = md.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        html = html.replaceAll("(?m)^## (.*)$", "<h2>$1</h2>");
        html = html.replaceAll("(?m)^# (.*)$", "<h1>$1</h1>");
        html = html.replaceAll("(?m)^[-*] (.*)$", "&#8226; $1");
        html = html.replaceAll("(</h[12]>)\n", "$1");
        html = html.replaceAll("\\*\\*(.*?)\\*\\*", "<b>$1</b>");
        html = html.replaceAll("\\*(.*?)\\*", "<i>$1</i>");
        html = html.replaceAll("__(.*?)__", "<u>$1</u>");
        html = html.replace("\n", "<br>");

        TextView tv = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, (int)(8 * d));
        tv.setLayoutParams(lp);
        tv.setTextColor(Color.parseColor("#2A3532"));
        tv.setTextSize(15);
        tv.setLineSpacing(4 * d, 1.0f);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            tv.setText(android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_COMPACT));
        } else {
            tv.setText(android.text.Html.fromHtml(html));
        }
        container.addView(tv);
    }

    private void addImageViewToContainer(String url, LinearLayout container, float d) {
        ImageView iv = new ImageView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int)(200 * d));
        lp.setMargins(0, (int)(8 * d), 0, (int)(16 * d));
        iv.setLayoutParams(lp);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundColor(Color.parseColor("#E6E8E6"));
        iv.setOnClickListener(v -> openAttachment(url));
        container.addView(iv);
        loadImageInto(iv, url);
    }

    private void dialNumber(String phoneNumber) {
        try {
            Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phoneNumber));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Unable to open phone dialer", Toast.LENGTH_SHORT).show();
        }
    }

    private double getUserLatitude() {
        try {
            android.location.LocationManager lm = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm != null) {
                if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    android.location.Location loc = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER);
                    if (loc == null) loc = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER);
                    if (loc == null) loc = lm.getLastKnownLocation(android.location.LocationManager.PASSIVE_PROVIDER);
                    if (loc != null) return loc.getLatitude();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 14.5995; // Default Metro Manila
    }

    private double getUserLongitude() {
        try {
            android.location.LocationManager lm = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm != null) {
                if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    android.location.Location loc = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER);
                    if (loc == null) loc = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER);
                    if (loc == null) loc = lm.getLastKnownLocation(android.location.LocationManager.PASSIVE_PROVIDER);
                    if (loc != null) return loc.getLongitude();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 120.9842; // Default Metro Manila
    }

    private boolean locationPinned = false;

    // ====================================================================
    // CAMERA / IMAGE HELPERS
    // ====================================================================

    /** Opens the camera (front camera when supported) after making sure CAMERA permission is granted. */
    private void launchSelfieCamera(TextView targetTv, ImageView preview, String bucket) {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingAfterCameraPermission = () -> launchSelfieCamera(targetTv, preview, bucket);
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA);
            return;
        }
        currentUploadTextView = targetTv;
        currentUploadPreview = preview;
        currentUploadBucket = bucket;

        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        // Ask camera apps to open the front camera (honored by most OEM camera apps)
        intent.putExtra("android.intent.extras.CAMERA_FACING", 1);
        intent.putExtra("android.intent.extras.LENS_FACING_FRONT", 1);
        intent.putExtra("android.intent.extra.USE_FRONT_CAMERA", true);
        try {
            File dir = new File(getCacheDir(), "camera");
            if (!dir.exists()) dir.mkdirs();
            File photoFile = File.createTempFile("selfie_" + System.currentTimeMillis(), ".jpg", dir);
            cameraPhotoUri = FileProvider.getUriForFile(this, getPackageName() + ".provider", photoFile);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri);
            intent.setClipData(ClipData.newRawUri("selfie", cameraPhotoUri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception e) {
            e.printStackTrace();
            cameraPhotoUri = null; // camera will return a small thumbnail instead
        }
        try {
            filePickerLauncher.launch(intent);
        } catch (android.content.ActivityNotFoundException e) {
            cameraPhotoUri = null;
            Toast.makeText(this, "No camera app found on this device.", Toast.LENGTH_LONG).show();
        }
    }

    private Bitmap decodeSampled(byte[] bytes, int maxSize) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= maxSize || bounds.outHeight / (sample * 2) >= maxSize) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        } catch (Exception e) {
            return null;
        }
    }

    /** Downscales a camera photo to max 1600px, applies EXIF rotation and re-encodes as JPEG. */
    private byte[] compressCameraImage(Uri uri) {
        byte[] raw = readBytesFromUri(uri);
        if (raw == null || raw.length == 0) return null;
        try {
            Bitmap bmp = decodeSampled(raw, 1600);
            if (bmp == null) return raw;
            int rotation = 0;
            try (InputStream exifIn = new java.io.ByteArrayInputStream(raw)) {
                android.media.ExifInterface exif = new android.media.ExifInterface(exifIn);
                int o = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL);
                if (o == android.media.ExifInterface.ORIENTATION_ROTATE_90) rotation = 90;
                else if (o == android.media.ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
                else if (o == android.media.ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
            } catch (Exception ignored) {}
            if (rotation != 0) {
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.postRotate(rotation);
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out);
            return out.toByteArray();
        } catch (Exception e) {
            return raw;
        }
    }

    /** Saves an image copy in app storage so History can show it offline / if the upload failed. */
    private String saveAttachmentLocally(byte[] bytes) {
        try {
            File dir = new File(getFilesDir(), "attachments");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "att_" + System.currentTimeMillis() + ".jpg");
            Bitmap bmp = decodeSampled(bytes, 1280);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                if (bmp != null) bmp.compress(Bitmap.CompressFormat.JPEG, 85, fos);
                else fos.write(bytes);
            }
            return f.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    /** Collects uploaded URLs (or local image copies when the upload failed) from upload TextViews. */
    private JSONArray collectAttachments(TextView... uploadViews) {
        JSONArray arr = new JSONArray();
        for (TextView tv : uploadViews) {
            if (tv == null) continue;
            if (tv.getTag() != null && !tv.getTag().toString().isEmpty()) arr.put(tv.getTag().toString());
            else if (localAttachmentPaths.containsKey(tv.getId())) arr.put(localAttachmentPaths.get(tv.getId()));
        }
        return arr;
    }

    private static String firstRemoteUrl(JSONArray attachments) {
        for (int i = 0; i < attachments.length(); i++) {
            String a = attachments.optString(i, "");
            if (a.startsWith("http")) return a;
        }
        return null;
    }

    /** Loads an image from a URL, a data: URI or a local file path into the ImageView (off the UI thread). */
    private void loadImageInto(ImageView iv, String src) {
        new Thread(() -> {
            Bitmap b = null;
            try {
                if (src.startsWith("data:image") || src.contains("base64,")) {
                    byte[] d = android.util.Base64.decode(src.substring(src.indexOf(",") + 1), android.util.Base64.DEFAULT);
                    b = decodeSampled(d, 600);
                } else if (src.startsWith("http")) {
                    try (InputStream in = new java.net.URL(src).openStream(); ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) bo.write(buf, 0, n);
                        b = decodeSampled(bo.toByteArray(), 600);
                    }
                } else {
                    File f = new File(src);
                    if (f.exists()) {
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inSampleSize = 2;
                        b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                    }
                }
            } catch (Exception ignored) {}
            final Bitmap result = b;
            runOnUiThread(() -> {
                if (result != null) {
                    iv.setImageTintList(null);
                    iv.setImageBitmap(result);
                }
            });
        }).start();
    }

    // ====================================================================
    // LOCATION HELPERS
    // ====================================================================

    private boolean hasLocationPermission() {
        return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void withLocationPermission(Runnable then) {
        if (hasLocationPermission()) {
            then.run();
        } else {
            pendingAfterLocationPermission = then;
            locationPermissionLauncher.launch(new String[]{
                    android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION});
        }
    }

    private interface LocationResult {
        void onLocation(Location location);
    }

    @SuppressLint("MissingPermission")
    private Location bestLastKnownLocation(LocationManager lm) {
        Location best = null;
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
            try {
                Location l = lm.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (Exception ignored) {}
        }
        return best;
    }

    /** Gets a fresh GPS / network fix (8s timeout, falls back to the last known location). */
    @SuppressLint("MissingPermission")
    private void getFreshLocation(LocationResult callback) {
        if (!hasLocationPermission()) {
            callback.onLocation(null);
            return;
        }
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) {
            callback.onLocation(null);
            return;
        }
        final Location last = bestLastKnownLocation(lm);
        String provider = null;
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) provider = LocationManager.GPS_PROVIDER;
            else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) provider = LocationManager.NETWORK_PROVIDER;
        } catch (Exception ignored) {}
        if (provider == null) {
            callback.onLocation(last);
            return;
        }

        final boolean[] done = {false};
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable timeout = () -> {
            if (!done[0]) { done[0] = true; callback.onLocation(last); }
        };
        handler.postDelayed(timeout, 8000);

        try {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(provider, null, getMainExecutor(), loc -> {
                    if (done[0]) return;
                    done[0] = true;
                    handler.removeCallbacks(timeout);
                    callback.onLocation(loc != null ? loc : last);
                });
            } else {
                lm.requestSingleUpdate(provider, new LocationListener() {
                    @Override public void onLocationChanged(@NonNull Location loc) {
                        if (done[0]) return;
                        done[0] = true;
                        handler.removeCallbacks(timeout);
                        callback.onLocation(loc);
                    }
                    @Override public void onStatusChanged(String p, int status, Bundle extras) {}
                    @Override public void onProviderEnabled(@NonNull String p) {}
                    @Override public void onProviderDisabled(@NonNull String p) {}
                }, Looper.getMainLooper());
            }
        } catch (Exception e) {
            if (!done[0]) { done[0] = true; handler.removeCallbacks(timeout); callback.onLocation(last); }
        }
    }

    private interface GeoPointResult {
        void onResult(double[] latLng); // null when not found
    }

    private interface TextResult {
        void onResult(String text); // null when not found
    }

    /** OpenStreetMap Nominatim search (Philippines only). */
    private void nominatimSearch(String query, GeoPointResult cb) {
        try {
            String url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&countrycodes=ph&q="
                    + java.net.URLEncoder.encode(query, "UTF-8");
            Request req = new Request.Builder().url(url).header("User-Agent", GEO_USER_AGENT).header("Accept-Language", "en").build();
            geoClient.newCall(req).enqueue(new Callback() {
                @Override public void onFailure(@NonNull Call call, @NonNull IOException e) { cb.onResult(null); }
                @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    try {
                        String body = response.body() != null ? new String(response.body().bytes(), StandardCharsets.UTF_8) : "[]";
                        JSONArray arr = new JSONArray(body);
                        if (arr.length() > 0) {
                            JSONObject o = arr.getJSONObject(0);
                            cb.onResult(new double[]{Double.parseDouble(o.getString("lat")), Double.parseDouble(o.getString("lon"))});
                            return;
                        }
                    } catch (Exception ignored) {}
                    cb.onResult(null);
                }
            });
        } catch (Exception e) {
            cb.onResult(null);
        }
    }

    /** OpenStreetMap Nominatim reverse geocoding -> short readable address. */
    private void reverseGeocode(double lat, double lng, TextResult cb) {
        String url = String.format(Locale.US,
                "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&addressdetails=1&lat=%.6f&lon=%.6f", lat, lng);
        Request req = new Request.Builder().url(url).header("User-Agent", GEO_USER_AGENT).header("Accept-Language", "en").build();
        geoClient.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) { cb.onResult(null); }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try {
                    String body = response.body() != null ? new String(response.body().bytes(), StandardCharsets.UTF_8) : "{}";
                    JSONObject o = new JSONObject(body);
                    JSONObject a = o.optJSONObject("address");
                    List<String> parts = new ArrayList<>();
                    if (a != null) {
                        String road = (a.optString("house_number", "") + " " + a.optString("road", "")).trim();
                        if (!road.isEmpty()) parts.add(road);
                        for (String k : new String[]{"neighbourhood", "quarter", "suburb", "village"}) {
                            String v = a.optString(k, "");
                            if (!v.isEmpty()) { parts.add(v); break; }
                        }
                        for (String k : new String[]{"city", "town", "municipality"}) {
                            String v = a.optString(k, "");
                            if (!v.isEmpty()) { parts.add(v); break; }
                        }
                    }
                    String result = parts.isEmpty() ? o.optString("display_name", "") : String.join(", ", parts);
                    cb.onResult(result.isEmpty() ? null : TextFix.fix(result));
                } catch (Exception e) {
                    cb.onResult(null);
                }
            }
        });
    }

    // ====================================================================
    // OPENSTREETMAP PIN PICKER (osmdroid)
    // ====================================================================

    private double selectedLat = 14.5995;
    private double selectedLng = 120.9842;
    private String selectedLocationAddress = "";

    private static void deleteRecursively(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursively(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private void showMapPickerDialog(TextView targetLocationTextView) {
        // osmdroid setup: identify the app to the OSM tile servers (required by their usage policy)
        Configuration.getInstance().load(getApplicationContext(), getSharedPreferences("osmdroid", MODE_PRIVATE));
        // A real app name, NOT the package name: OSM blocks generic ids like "com.example.*" (that was the 403 "Access blocked")
        Configuration.getInstance().setUserAgentValue("BarangaySuperApp/1.0 (Android)");
        // drop every older tile cache - they contain the cached "Access blocked" images from tile.openstreetmap.org
        // ("osmdroid_voyager" holds the cached "API KEY REQUIRED" tiles from before the CARTO key was added)
        for (String old : new String[]{"osmdroid", "osmdroid_carto", "osmdroid_voyager"}) {
            File oldOsmCache = new File(getCacheDir(), old);
            if (oldOsmCache.exists()) deleteRecursively(oldOsmCache);
        }
        File osmBase = new File(getCacheDir(), "osmdroid_carto_key");
        if (!osmBase.exists()) osmBase.mkdirs();
        Configuration.getInstance().setOsmdroidBasePath(osmBase);
        Configuration.getInstance().setOsmdroidTileCache(new File(osmBase, "tiles"));

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_map_picker, null);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(dialogView).create();
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        MapView map = dialogView.findViewById(R.id.mapPicker);
        TextView tvAddress = dialogView.findViewById(R.id.tvMapAddressDisplay);
        TextView tvLoading = dialogView.findViewById(R.id.tvMapLoading);
        CardView btnGps = dialogView.findViewById(R.id.btnGpsLiveLocation);
        CardView btnConfirm = dialogView.findViewById(R.id.btnConfirmMapLocation);
        TextView btnCancel = dialogView.findViewById(R.id.btnCancelMapLocation);

        // OSM's own tile server (TileSourceFactory.MAPNIK) blocks apps -> 403 "Access blocked" tiles.
        // CARTO Voyager renders the same OpenStreetMap data; it needs the API key on every tile URL (see MapConfig).
        map.setTileSource(MapConfig.cartoVoyager());
        map.setMultiTouchControls(true);
        map.setTilesScaledToDpi(true);
        map.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT);
        map.setMinZoomLevel(5.0);
        map.setMaxZoomLevel(19.5);

        final boolean hadPin = locationPinned;
        GeoPoint start = hadPin ? new GeoPoint(selectedLat, selectedLng) : new GeoPoint(12.8797, 121.7740); // PH center
        map.getController().setZoom(hadPin ? 18.0 : 6.0);
        map.getController().setCenter(start);

        Marker marker = new Marker(map);
        marker.setPosition(start);
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        marker.setDraggable(true);
        marker.setInfoWindow(null);
        marker.setEnabled(hadPin);
        map.getOverlays().add(marker);

        final double[] pin = {start.getLatitude(), start.getLongitude()};
        final boolean[] pinSet = {hadPin};
        final String[] pinAddress = {hadPin ? selectedLocationAddress : ""};

        class PinController {
            void moveTo(GeoPoint p, boolean center, double zoom) {
                pin[0] = p.getLatitude();
                pin[1] = p.getLongitude();
                pinSet[0] = true;
                pinAddress[0] = "";
                marker.setEnabled(true);
                marker.setPosition(p);
                if (zoom > 0) map.getController().setZoom(zoom);
                if (center) map.getController().animateTo(p);
                map.invalidate();
                final String coords = String.format(Locale.US, "(%.5f, %.5f)", p.getLatitude(), p.getLongitude());
                tvAddress.setText("📍 Getting address... " + coords);
                reverseGeocode(p.getLatitude(), p.getLongitude(), addr -> runOnUiThread(() -> {
                    // ignore stale results if the pin moved again
                    if (Math.abs(pin[0] - p.getLatitude()) > 1e-7 || Math.abs(pin[1] - p.getLongitude()) > 1e-7) return;
                    pinAddress[0] = addr != null ? addr : "";
                    tvAddress.setText("📍 " + (addr != null ? addr + "\n" : "Pinned location ") + coords);
                }));
            }
        }
        final PinController pinCtl = new PinController();

        if (hadPin) tvAddress.setText(selectedLocationAddress);

        marker.setOnMarkerDragListener(new Marker.OnMarkerDragListener() {
            @Override public void onMarkerDrag(Marker m) {}
            @Override public void onMarkerDragStart(Marker m) {}
            @Override public void onMarkerDragEnd(Marker m) { pinCtl.moveTo(m.getPosition(), false, 0); }
        });

        map.getOverlays().add(0, new MapEventsOverlay(new MapEventsReceiver() {
            @Override public boolean singleTapConfirmedHelper(GeoPoint p) { pinCtl.moveTo(p, false, 0); return true; }
            @Override public boolean longPressHelper(GeoPoint p) { pinCtl.moveTo(p, false, 0); return true; }
        }));

        // Show the user's area: live GPS if allowed, otherwise their registered barangay / city
        Runnable showUserArea = () -> {
            tvLoading.setVisibility(View.VISIBLE);
            tvLoading.setText("Finding your location...");
            getFreshLocation(loc -> {
                if (loc != null) {
                    tvLoading.setVisibility(View.GONE);
                    pinCtl.moveTo(new GeoPoint(loc.getLatitude(), loc.getLongitude()), true, 18.0);
                    return;
                }
                SharedPreferences prefs = getSharedPreferences("AppSession", MODE_PRIVATE);
                String brgy = prefs.getString("USER_BARANGAY", "");
                String city = prefs.getString("USER_CITY", "");
                String prov = prefs.getString("USER_PROVINCE", "");
                String cityQuery = (city + ", " + prov).replaceAll("^, |, $", "");
                String brgyQuery = brgy.isEmpty() ? cityQuery : (brgy + ", " + cityQuery);
                tvLoading.setText("Showing your barangay...");
                nominatimSearch(brgyQuery, found -> {
                    if (found != null) {
                        runOnUiThread(() -> {
                            tvLoading.setVisibility(View.GONE);
                            map.getController().setZoom(16.0);
                            map.getController().animateTo(new GeoPoint(found[0], found[1]));
                            tvAddress.setText("📍 Tap the map to pin the exact location");
                        });
                    } else if (!cityQuery.isEmpty()) {
                        nominatimSearch(cityQuery, found2 -> runOnUiThread(() -> {
                            tvLoading.setVisibility(View.GONE);
                            if (found2 != null) {
                                map.getController().setZoom(14.0);
                                map.getController().animateTo(new GeoPoint(found2[0], found2[1]));
                            }
                            tvAddress.setText("📍 Tap the map to pin the exact location");
                        }));
                    } else {
                        runOnUiThread(() -> tvLoading.setVisibility(View.GONE));
                    }
                });
            });
        };

        if (hadPin) {
            tvLoading.setVisibility(View.GONE);
        } else {
            withLocationPermission(showUserArea);
        }

        btnGps.setOnClickListener(v -> withLocationPermission(() -> {
            if (!hasLocationPermission()) {
                Toast.makeText(this, "Allow location access to use Live GPS.", Toast.LENGTH_SHORT).show();
                return;
            }
            tvLoading.setVisibility(View.VISIBLE);
            tvLoading.setText("Getting GPS fix...");
            getFreshLocation(loc -> {
                tvLoading.setVisibility(View.GONE);
                if (loc != null) {
                    pinCtl.moveTo(new GeoPoint(loc.getLatitude(), loc.getLongitude()), true, 18.0);
                    Toast.makeText(this, "Pinned your current location", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Couldn't get a GPS fix. Turn on Location, or tap the map to pin manually.", Toast.LENGTH_LONG).show();
                }
            });
        }));

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnConfirm.setOnClickListener(v -> {
            if (!pinSet[0]) {
                Toast.makeText(this, "Tap the map to drop a pin first.", Toast.LENGTH_SHORT).show();
                return;
            }
            selectedLat = pin[0];
            selectedLng = pin[1];
            locationPinned = true;
            String coords = String.format(Locale.US, "(%.5f, %.5f)", pin[0], pin[1]);
            selectedLocationAddress = "📍 " + (pinAddress[0].isEmpty() ? "Pinned location " + coords : pinAddress[0] + " " + coords);
            if (targetLocationTextView != null) targetLocationTextView.setText(selectedLocationAddress);
            Toast.makeText(this, "Location pinned!", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        dialog.setOnDismissListener(d -> {
            map.onPause();
            map.onDetach();
        });
        dialog.show();
        map.onResume();
    }

    private void showApplicationTypeDialog() {
        String[] types = new String[]{"New Applicant", "Renewal", "Replacement for Lost / Damaged ID"};
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Select Application Type")
                .setItems(types, (dialog, which) -> {
                    String selectedType = types[which];
                    Intent intent = new Intent(PreviewActivity.this, PreviewActivity.class);
                    intent.putExtra("LAYOUT_ID", R.layout.request_brgy_id_form);
                    intent.putExtra("APP_TYPE", selectedType);
                    startActivity(intent);
                    overridePendingTransition(0, 0);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void launchPreview(int layoutId) {
        Intent intent = new Intent(PreviewActivity.this, PreviewActivity.class);
        intent.putExtra("LAYOUT_ID", layoutId);
        startActivity(intent);
        overridePendingTransition(0, 0);
    }

    /**
     * Loads the announcements of the user's barangay from Supabase.
     * The barangay comes from the user's profile (psgc_code); the database only returns rows whose
     * psgc_code matches the signed-in user's profile (RLS), so officials' posts reach exactly their residents.
     * No mock data fallback: if nothing is posted (or the network fails) the screen shows its empty state.
     */
    private void loadBarangayAnnouncements(SharedPreferences prefs) {
        String userBrgyCode = SupabaseClient.dbPsgc(prefs.getString("USER_BARANGAY_CODE", selectedBarangayCode));

        SupabaseClient.fetchAnnouncementsFromSupabase(userBrgyCode, new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                android.util.Log.e("Announcements", "Load failed: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String body = response.body() != null ? new String(response.body().bytes(), StandardCharsets.UTF_8) : "";
                if (!response.isSuccessful()) {
                    android.util.Log.e("Announcements", "HTTP " + response.code() + ": " + body);
                    return;
                }
                List<JSONObject> rows = new ArrayList<>();
                try {
                    JSONArray array = new JSONArray(body);
                    String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject row = array.getJSONObject(i);
                        if (row.optBoolean("is_archived", false)) continue;           // archived on the web portal
                        String eventDate = row.isNull("event_date") ? "" : row.optString("event_date", "");
                        if (!eventDate.isEmpty() && eventDate.compareTo(today) < 0) continue; // past event (same rule as the web)
                        rows.add(toDisplayAnnouncement(row));
                    }
                } catch (Exception e) {
                    android.util.Log.e("Announcements", "Parse failed: " + e.getMessage());
                    return;
                }
                runOnUiThread(() -> {
                    allAnnouncements.clear();
                    allAnnouncements.addAll(rows);
                    refreshAnnouncementsUI();
                });
            }
        });
    }

    /** Maps a public.announcements row to the fields the dashboard / list / details dialog read. */
    private static JSONObject toDisplayAnnouncement(JSONObject row) throws org.json.JSONException {
        JSONObject a = new JSONObject(row.toString());
        String category = row.optString("category", "");
        if (category.isEmpty() || "null".equals(category)) category = row.optString("type", "General");
        if (category.isEmpty() || "null".equals(category)) category = "General";
        category = category.substring(0, 1).toUpperCase(Locale.US) + category.substring(1);
        a.put("category", category);

        String colorHex = "#247D76", bgHex = "#DFF2F0";              // Health / General
        if ("Advisory".equalsIgnoreCase(category)) { colorHex = "#CC4E42"; bgHex = "#FCEBEA"; }
        else if ("Event".equalsIgnoreCase(category)) { colorHex = "#DBA03B"; bgHex = "#FDF1DA"; }
        a.put("categoryColorHex", colorHex);
        a.put("categoryBgHex", bgHex);

        String posted = formatIsoDate(row.optString("created_at", ""), true);
        a.put("date", posted.isEmpty() ? "Recently" : posted);

        String body = row.isNull("body") ? "" : row.optString("body", "");
        if (body.isEmpty()) body = row.optString("description", "");
        String eventDate = row.isNull("event_date") ? "" : row.optString("event_date", "");
        if (!eventDate.isEmpty()) {
            String when = formatIsoDate(eventDate, false);
            String time = row.isNull("event_time") ? "" : row.optString("event_time", "");
            body = "📅 " + (when.isEmpty() ? eventDate : when) + (time.isEmpty() ? "" : " · " + time) + "\n\n" + body;
        }
        a.put("description", body);
        a.put("description_plain", plainText(body));
        return a;
    }

    /** Announcement text without formatting symbols or image links (for the short list preview). */
    private static String plainText(String md) {
        String t = md == null ? "" : md;
        t = t.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "");   // images
        t = t.replaceAll("(?m)^#{1,2} ", "");                    // headings
        t = t.replaceAll("(?m)^[-*] ", "\u2022 ");               // bullets
        t = t.replace("**", "").replace("__", "").replaceAll("(?<![\\w*])\\*(?!\\s)(.*?)\\*", "$1");
        return t.replaceAll("\n{3,}", "\n\n").trim();
    }

    /** "2026-10-05T07:30:00+00:00" or "2026-10-05" -> "Oct 5, 2026" in the phone's time zone. */
    private static String formatIsoDate(String iso, boolean hasTime) {
        if (!hasTime) return HistorySync.formatIsoDate(iso, false);
        String s = HistorySync.formatIsoDate(iso, true);
        int dot = s.indexOf(" · ");
        return dot > 0 ? s.substring(0, dot) : s;   // announcement cards show the date only
    }

    private void refreshAnnouncementsUI() {
        RecyclerView rvDash = findViewById(R.id.rvDashboardAnnouncements);
        if (rvDash != null && rvDash.getAdapter() != null) {
            rvDash.getAdapter().notifyDataSetChanged();
        }
        RecyclerView rvAnnounce = findViewById(R.id.rvAnnouncements);
        if (rvAnnounce != null && rvAnnounce.getAdapter() != null) {
            rvAnnounce.getAdapter().notifyDataSetChanged();
        }
        if (onAnnouncementsUpdated != null) onAnnouncementsUpdated.run();
    }

    // ====================================================================
    // ACCOUNT APPROVAL (decided by the barangay officials, stored in profiles.account_status)
    // ====================================================================

    private static boolean isBlockedStatus(String status) {
        String s = status == null ? "" : status.trim().toLowerCase(Locale.US);
        return s.equals("pending") || s.equals("unapproved") || s.equals("rejected");
    }

    /** Signs the user out locally (tokens + per-user prefs). */
    private void signOutLocally(SharedPreferences prefs) {
        SharedPreferences.Editor ed = prefs.edit().putBoolean("IS_LOGGED_IN", false);
        clearUserPrefs(ed);
        ed.apply();
        SupabaseClient.clearSession();
        StatusCheckWorker.cancel(this);
    }

    /** Sends a pending user to the review screen, a rejected user back to sign in. */
    private void showAccountNotApproved(SharedPreferences prefs, String status) {
        String brgy = prefs.getString("USER_BARANGAY", "");
        signOutLocally(prefs);
        if ("rejected".equalsIgnoreCase(status)) {
            Toast.makeText(this, "Your registration was not approved by the barangay. Please contact your barangay hall.", Toast.LENGTH_LONG).show();
            openAfterSignIn(R.layout.sign_in);
            return;
        }
        Toast.makeText(this, "Account pending review by Barangay Officials.", Toast.LENGTH_LONG).show();
        Intent intent = new Intent(PreviewActivity.this, PreviewActivity.class);
        intent.putExtra("LAYOUT_ID", R.layout.account_review_ntf);
        if (!brgy.isEmpty()) intent.putExtra("SELECTED_BARANGAY", "Brgy. " + brgy);
        startActivity(intent);
        finish();
    }

    /** Dashboard check: the barangay may have rejected the account, or it went back to pending after a move. */
    private void verifyAccountStillApproved(SharedPreferences prefs) {
        String userId = prefs.getString("USER_ID", "");
        if (userId.isEmpty()) return;
        SupabaseClient.fetchUserProfileFromSupabase(userId, new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) { /* offline: keep going */ }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (!response.isSuccessful() || response.body() == null) return;
                try {
                    JSONArray arr = new JSONArray(new String(response.body().bytes(), StandardCharsets.UTF_8));
                    if (arr.length() == 0) return;
                    String status = firstNonEmpty(arr.getJSONObject(0), "account_status");
                    if (isBlockedStatus(status)) runOnUiThread(() -> showAccountNotApproved(prefs, status));
                } catch (Exception ignored) {}
            }
        });
    }


    // ====================================================================
    // NOTIFICATIONS SCREEN
    // ====================================================================

    private void renderNotifications() {
        RecyclerView rv = findViewById(R.id.rvNotifications);
        View empty = findViewById(R.id.layoutEmptyStateNotifications);
        if (rv == null) return;
        final List<JSONObject> items = HistorySync.readInbox(this);
        if (empty != null) empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        rv.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        if (rv.getLayoutManager() == null) rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
                View v = getLayoutInflater().inflate(R.layout.item_notification, parent, false);
                return new RecyclerView.ViewHolder(v) {};
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                JSONObject n = items.get(position);
                View item = holder.itemView;
                String created = n.optString("created_at", "");
                String day = HistorySync.formatIsoDate(created, true);
                int dot = day.indexOf(" · ");
                String dayOnly = dot > 0 ? day.substring(0, dot) : day;
                String time = dot > 0 ? day.substring(dot + 3) : "";
                String prevDay = "";
                if (position > 0) {
                    String p = HistorySync.formatIsoDate(items.get(position - 1).optString("created_at", ""), true);
                    int pd = p.indexOf(" · ");
                    prevDay = pd > 0 ? p.substring(0, pd) : p;
                }
                View sep = item.findViewById(R.id.dateSeparatorContainer);
                if (sep != null) sep.setVisibility(position == 0 || !dayOnly.equals(prevDay) ? View.VISIBLE : View.GONE);
                TextView tvDate = item.findViewById(R.id.tvDateHeader);
                if (tvDate != null) tvDate.setText(dayOnly);

                boolean announcement = HistorySync.KIND_ANNOUNCEMENT.equals(n.optString("kind"));
                ((TextView) item.findViewById(R.id.tvNotificationTitle)).setText(n.optString("title", "Update"));
                String source = n.optString("source", "");
                StringBuilder desc = new StringBuilder(n.optString("message", ""));
                desc.append("\n").append(announcement ? "Announcement" : "Request update");
                if (!source.isEmpty()) desc.append(" · From ").append(source);
                if (!time.isEmpty()) desc.append(" · ").append(time);
                ((TextView) item.findViewById(R.id.tvNotificationDesc)).setText(desc.toString());

                View icon = item.findViewById(R.id.iconImage);
                CardView iconBox = item.findViewById(R.id.iconContainer);
                if (icon != null) icon.setBackgroundColor(Color.parseColor(announcement ? "#DBA03B" : "#247D76"));
                if (iconBox != null) iconBox.setCardBackgroundColor(Color.parseColor(announcement ? "#FDF1DA" : "#DDF0EC"));
                View unread = item.findViewById(R.id.unreadDot);
                if (unread != null) unread.setVisibility(n.optBoolean("read", false) ? View.INVISIBLE : View.VISIBLE);

                item.setOnClickListener(v -> launchPreview(announcement ? R.layout.announcements : R.layout.request_history));
            }

            @Override
            public int getItemCount() { return items.size(); }
        });
    }

    // ====================================================================
    // EMERGENCY CONTACTS (public.emergency_contacts)
    // ====================================================================

    private void loadEmergencyContacts(SharedPreferences prefs) {
        String code = SupabaseClient.dbPsgc(prefs.getString("USER_BARANGAY_CODE", selectedBarangayCode));
        SupabaseClient.fetchEmergencyContacts(code, new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> {
                    if (prefs.getString("CACHED_EMERGENCY_CONTACTS", "").isEmpty()) {
                        TextView status = findViewById(R.id.tvContactsStatus);
                        if (status != null) status.setText("Couldn't load hotlines. Check your internet connection.");
                    }
                });
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String body = response.body() != null ? new String(response.body().bytes(), StandardCharsets.UTF_8) : "";
                if (!response.isSuccessful()) {
                    android.util.Log.e("EmergencyContacts", "HTTP " + response.code() + ": " + body);
                    return;
                }
                prefs.edit().putString("CACHED_EMERGENCY_CONTACTS", body).apply();
                runOnUiThread(() -> renderEmergencyContacts(body, false));
            }
        });
    }

    /** Builds the list: Barangay, City / municipality, then Nationwide (rows with no barangay). */
    private void renderEmergencyContacts(String json, boolean fromCache) {
        LinearLayout container = findViewById(R.id.layoutEmergencyContacts);
        TextView status = findViewById(R.id.tvContactsStatus);
        if (container == null) return;
        JSONArray rows;
        try { rows = new JSONArray(json == null || json.isEmpty() ? "[]" : json); } catch (Exception e) { rows = new JSONArray(); }

        java.util.LinkedHashMap<String, List<JSONObject>> groups = new java.util.LinkedHashMap<>();
        groups.put("Barangay", new ArrayList<>());
        groups.put("City / Municipality", new ArrayList<>());
        groups.put("Nationwide", new ArrayList<>());
        for (int i = 0; i < rows.length(); i++) {
            JSONObject c = rows.optJSONObject(i);
            if (c == null) continue;
            String number = firstNonEmpty(c, "phone_number", "contact_number", "number");
            if (number.isEmpty()) continue;
            String scope = firstNonEmpty(c, "scope", "category").toLowerCase(Locale.US);
            String key = c.isNull("psgc_code") || scope.contains("national") ? "Nationwide"
                    : (scope.contains("city") || scope.contains("municipal") || scope.contains("lgu") ? "City / Municipality" : "Barangay");
            groups.get(key).add(c);
        }

        container.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        int shown = 0;
        for (Map.Entry<String, List<JSONObject>> g : groups.entrySet()) {
            if (g.getValue().isEmpty()) continue;
            TextView header = new TextView(this);
            header.setText(g.getKey() + " Level");
            header.setTextColor(Color.parseColor("#2A3532"));
            header.setTextSize(16);
            header.setTypeface(header.getTypeface(), android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, (int) ((shown == 0 ? 24 : 20) * d), 0, (int) (12 * d));
            header.setLayoutParams(lp);
            container.addView(header);

            String bg = "Nationwide".equals(g.getKey()) ? "#FCEBEA" : ("Barangay".equals(g.getKey()) ? "#DDF0EC" : "#FDF1DA");
            String fg = "Nationwide".equals(g.getKey()) ? "#CC4E42" : ("Barangay".equals(g.getKey()) ? "#247D76" : "#C08A2E");
            for (JSONObject c : g.getValue()) {
                View card = getLayoutInflater().inflate(R.layout.item_emergency_contact, container, false);
                String name = firstNonEmpty(c, "name");
                String number = firstNonEmpty(c, "phone_number", "contact_number", "number");
                ((TextView) card.findViewById(R.id.tvContactName)).setText(name.isEmpty() ? "Hotline" : name);
                ((TextView) card.findViewById(R.id.tvContactNumber)).setText(number);
                ((CardView) card.findViewById(R.id.cardContactIcon)).setCardBackgroundColor(Color.parseColor(bg));
                ((ImageView) card.findViewById(R.id.ivContactIcon)).setColorFilter(Color.parseColor(fg));
                card.setContentDescription("Call " + name + ", " + number);
                card.setOnClickListener(v -> dialNumber(number.replaceAll("[^0-9+]", "")));
                container.addView(card);
                shown++;
            }
        }
        if (status != null) {
            if (shown > 0) {
                status.setVisibility(View.GONE);
            } else {
                status.setVisibility(View.VISIBLE);
                status.setText(fromCache ? "Loading hotlines..."
                        : "Your barangay hasn't added emergency hotlines yet. In an emergency, contact your barangay hall directly.");
            }
        }
    }

    // ====================================================================
    // HISTORY: pull the barangay's status updates for the user's submissions
    // ====================================================================

    /** Reloads the saved history (newest first, cancelled at the bottom) into allRequests. */
    private void reloadLocalHistory(SharedPreferences prefs) {
        try {
            JSONArray userArray = new JSONArray(prefs.getString("USER_SUBMITTED_REQUESTS", "[]"));
            allRequests.clear();
            for (int i = 0; i < userArray.length(); i++) allRequests.add(userArray.getJSONObject(i));
            HistorySync.sortEntries(allRequests);
        } catch (Exception ignored) {}
    }

    /**
     * Pulls the user's reports + requests from Supabase (source of truth), updates History,
     * notifies the phone about anything the barangay changed, then runs onDone on the UI thread.
     */
    private void syncHistoryStatuses(SharedPreferences prefs, Runnable onDone) {
        final android.content.Context appCtx = getApplicationContext();
        new Thread(() -> {
            List<HistorySync.Update> updates = HistorySync.syncBlocking(appCtx);
            if (updates == null) return;
            HistorySync.notifyUpdates(appCtx, updates);
            runOnUiThread(() -> {
                reloadLocalHistory(prefs);
                if (onDone != null) onDone.run();
            });
        }).start();
    }

    // ====================================================================
    // LIVE UPDATES: re-check Supabase while a screen is open
    //   History: every 20 s · Dashboard / Announcements: every 60 s · and every time the app comes back
    // ====================================================================
    private final Handler liveHandler = new Handler(Looper.getMainLooper());
    private Runnable liveTask;
    private long liveIntervalMs = 0;

    private void startLiveUpdates(Runnable task, long intervalMs) {
        liveTask = task;
        liveIntervalMs = intervalMs;
    }

    private final Runnable liveLoop = new Runnable() {
        @Override public void run() {
            if (liveTask == null) return;
            liveTask.run();
            liveHandler.postDelayed(this, liveIntervalMs);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        if (liveTask != null) {
            liveHandler.removeCallbacks(liveLoop);
            liveHandler.post(liveLoop);       // refresh right away when the user comes back
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        liveHandler.removeCallbacks(liveLoop);
    }

    // ====================================================================
    // AI REPORT BUILDER (chatbot interview -> editable draft)
    // ====================================================================

    private int addBotMessage(String text, boolean hasPrompt, String action) {
        if (chatMessages == null || chatRecycler == null) return -1;
        chatMessages.add(new ChatMessage(false, text, hasPrompt, action));
        int idx = chatMessages.size() - 1;
        if (chatRecycler.getAdapter() != null) chatRecycler.getAdapter().notifyItemInserted(idx);
        chatRecycler.scrollToPosition(idx);
        return idx;
    }

    private void replaceBotMessage(int idx, String text, boolean hasPrompt, String action) {
        if (chatMessages == null || chatRecycler == null) return;
        ChatMessage m = new ChatMessage(false, text, hasPrompt, action);
        if (idx >= 0 && idx < chatMessages.size()) {
            chatMessages.set(idx, m);
            if (chatRecycler.getAdapter() != null) chatRecycler.getAdapter().notifyItemChanged(idx);
        } else {
            chatMessages.add(m);
            if (chatRecycler.getAdapter() != null) chatRecycler.getAdapter().notifyItemInserted(chatMessages.size() - 1);
        }
        chatRecycler.scrollToPosition(chatMessages.size() - 1);
    }

    private void startReportInterview() {
        Arrays.fill(reportAnswers, "");
        String seed = pendingReportSeed == null ? "" : pendingReportSeed.trim();
        pendingReportSeed = "";
        // If the first message already described the incident, use it as the answer to question 1
        if (seed.split("\\s+").length >= 4 && !seed.toLowerCase(Locale.ROOT).contains("gumawa ng report")) {
            reportAnswers[0] = seed;
            reportInterviewStep = 1;
            addBotMessage("Sige! Noted ko na: \"" + seed + "\"\n\n" + ReportAssistant.QUESTIONS[1]
                    + "\n\n(I-type ang \"cancel\" para itigil.)", false, null);
        } else {
            reportInterviewStep = 0;
            addBotMessage("Sige! Tatanungin kita ng " + ReportAssistant.QUESTIONS.length + " maikling tanong. (I-type ang \"cancel\" para itigil.)\n\n"
                    + ReportAssistant.QUESTIONS[0], false, null);
        }
    }

    private void handleReportAnswer(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        if (t.equals("cancel") || t.equals("stop") || t.equals("itigil") || t.equals("huwag na")) {
            reportInterviewStep = -1;
            addBotMessage("Okay, kinansela ko na ang report. Nandito lang ako kung kailangan mo ulit.", false, null);
            return;
        }
        if (reportInterviewStep == 0 && ReportAssistant.isNone(text)) {
            addBotMessage("Kailangan ko munang malaman kung ano ang nangyari. " + ReportAssistant.QUESTIONS[0], false, null);
            return;
        }
        reportAnswers[reportInterviewStep] = text.trim();
        reportInterviewStep++;
        if (reportInterviewStep < ReportAssistant.QUESTIONS.length) {
            addBotMessage(ReportAssistant.QUESTIONS[reportInterviewStep], false, null);
            return;
        }

        reportInterviewStep = -1;
        final int idx = addBotMessage("Sandali lang, binubuo ko na ang report mo... ✍️", false, null);
        ReportAssistant.buildDraft(reportAnswers.clone(), draft -> runOnUiThread(() -> {
            pendingReportDraft = draft;
            replaceBotMessage(idx, "Heto ang draft ng report mo:\n\n" + draft.summary()
                    + "\n\nBuksan ko na ba ito sa report form? Doon mo ito mache-check at mae-edit, mapi-pin ang eksaktong lokasyon sa mapa, "
                    + "at makakapag-attach ng photo bago ipasa.", true, "open_report");
        }));
    }

    private void openDraftReport(ReportAssistant.Draft draft) {
        if (draft == null) {
            addBotMessage("Wala akong nakitang draft. Type \"gumawa ng report\" para magsimula ulit.", false, null);
            return;
        }
        Intent intent = new Intent(PreviewActivity.this, PreviewActivity.class);
        intent.putExtra("LAYOUT_ID", draft.isDisaster ? R.layout.report_disaster_form : R.layout.report_form);
        intent.putExtra("AI_DRAFT", true);
        intent.putExtra("AI_CATEGORY", draft.category);
        intent.putExtra("AI_TITLE", draft.title);
        intent.putExtra("AI_DESCRIPTION", draft.description);
        intent.putExtra("AI_LOCATION", draft.location);
        startActivity(intent);
        overridePendingTransition(0, 0);
    }

    /** Shown on the report forms when they were pre-filled by the AI. */
    private void showAiDraftNotice() {
        new AlertDialog.Builder(this)
                .setTitle("✨ AI draft ready")
                .setMessage("I filled this in from your answers. Please check every field and fix anything that's wrong, "
                        + "pin the exact location on the map, and add a photo if you have one. Nothing is sent until you tap Submit.")
                .setPositiveButton("Review now", null)
                .show();
    }

    // ====================================================================
    // PROFILE: INFO DISPLAY + CHANGE ADDRESS
    // ====================================================================

    private static String orDash(String s) {
        return (s == null || s.trim().isEmpty()) ? "—" : s;
    }

    /** Fills the read-only profile TextViews from the saved session. */
    private void bindProfileInfo(SharedPreferences prefs) {
        TextView tvName = findViewById(R.id.tvProfileName);
        TextView tvPhone = findViewById(R.id.tvProfilePhone);
        TextView tvEmail = findViewById(R.id.tvProfileEmail);
        TextView tvStreet = findViewById(R.id.tvProfileStreet);
        TextView tvBarangay = findViewById(R.id.tvProfileBarangay);
        TextView tvCity = findViewById(R.id.tvProfileCity);
        TextView tvProvince = findViewById(R.id.tvProfileProvince);
        TextView tvRegion = findViewById(R.id.tvProfileRegion);

        if (tvName != null) tvName.setText(orDash(prefs.getString("USER_NAME", "")));
        if (tvPhone != null) tvPhone.setText(orDash(prefs.getString("USER_PHONE", "")));
        if (tvEmail != null) tvEmail.setText(orDash(prefs.getString("USER_EMAIL", "")));
        if (tvStreet != null) tvStreet.setText(orDash(prefs.getString("USER_STREET", "")));
        if (tvBarangay != null) tvBarangay.setText(orDash(TextFix.fix(prefs.getString("USER_BARANGAY", ""))));
        if (tvCity != null) tvCity.setText(orDash(TextFix.fix(prefs.getString("USER_CITY", ""))));
        if (tvProvince != null) tvProvince.setText(orDash(TextFix.fix(prefs.getString("USER_PROVINCE", ""))));
        String region = prefs.getString("USER_REGION", "");
        if (region.isEmpty()) region = PSGCClient.regionNameFromCode(prefs.getString("USER_BARANGAY_CODE", ""));
        if (tvRegion != null) tvRegion.setText(orDash(region));
    }

    private void setupDropdown(AutoCompleteTextView view, List<PSGCClient.LocationItem> items) {
        ArrayAdapter<PSGCClient.LocationItem> adapter = new ArrayAdapter<>(PreviewActivity.this, R.layout.spinner_dropdown_item, items);
        view.setAdapter(adapter);
        view.setOnClickListener(v -> view.showDropDown());
        view.setOnFocusChangeListener((v, hasFocus) -> { if (hasFocus) view.showDropDown(); });
    }

    /** Step 1: edit dialog with cascading PSGC dropdowns. */
    private void showChangeAddressDialog(SharedPreferences prefs) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_change_address, null);
        AutoCompleteTextView acProvince = dialogView.findViewById(R.id.spinnerDialogProvince);
        AutoCompleteTextView acCity = dialogView.findViewById(R.id.spinnerDialogCity);
        AutoCompleteTextView acBarangay = dialogView.findViewById(R.id.spinnerDialogBarangay);
        EditText etStreet = dialogView.findViewById(R.id.etDialogStreet);
        CardView btnCancel = dialogView.findViewById(R.id.btnDialogCancelAddress);
        CardView btnSave = dialogView.findViewById(R.id.btnDialogSaveAddress);

        // Selections made in this dialog (PSGC codes are needed for psgc_code + region)
        final String[] pick = new String[]{"", "", "", "", ""}; // provinceCode, provinceName, cityCode, brgyCode, brgyName

        etStreet.setText(prefs.getString("USER_STREET", ""));

        AlertDialog dialog = new AlertDialog.Builder(this).setView(dialogView).create();

        PSGCClient.fetchProvinces(new PSGCClient.LocationCallback() {
            @Override public void onSuccess(List<PSGCClient.LocationItem> items) {
                runOnUiThread(() -> setupDropdown(acProvince, items));
            }
            @Override public void onError(String error) {
                runOnUiThread(() -> Toast.makeText(PreviewActivity.this, "Couldn't load provinces. Check your internet connection.", Toast.LENGTH_SHORT).show());
            }
        });

        acProvince.setOnItemClickListener((parent, view, position, id) -> {
            PSGCClient.LocationItem prov = (PSGCClient.LocationItem) parent.getItemAtPosition(position);
            pick[0] = prov.code; pick[1] = prov.name; pick[2] = ""; pick[3] = ""; pick[4] = "";
            acCity.setText("");
            acBarangay.setText("");
            PSGCClient.fetchCities(prov.code, new PSGCClient.LocationCallback() {
                @Override public void onSuccess(List<PSGCClient.LocationItem> items) { runOnUiThread(() -> setupDropdown(acCity, items)); }
                @Override public void onError(String error) {}
            });
        });

        acCity.setOnItemClickListener((parent, view, position, id) -> {
            PSGCClient.LocationItem city = (PSGCClient.LocationItem) parent.getItemAtPosition(position);
            pick[2] = city.code; pick[3] = ""; pick[4] = "";
            acBarangay.setText("");
            PSGCClient.fetchBarangays(city.code, new PSGCClient.LocationCallback() {
                @Override public void onSuccess(List<PSGCClient.LocationItem> items) { runOnUiThread(() -> setupDropdown(acBarangay, items)); }
                @Override public void onError(String error) {}
            });
        });

        acBarangay.setOnItemClickListener((parent, view, position, id) -> {
            Object item = parent.getItemAtPosition(position);
            if (item instanceof PSGCClient.LocationItem) {
                pick[3] = ((PSGCClient.LocationItem) item).code;
                pick[4] = ((PSGCClient.LocationItem) item).name;
            }
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnSave.setOnClickListener(v -> {
            String newStreet = etStreet.getText().toString().trim();
            String newProvince = TextFix.fix(acProvince.getText().toString().trim());
            String newCity = TextFix.fix(acCity.getText().toString().trim());
            String newBarangay = TextFix.fix(acBarangay.getText().toString().trim());

            // Must be picked from the lists so we have valid PSGC codes
            if (pick[0].isEmpty() || !newProvince.equals(pick[1])) {
                Toast.makeText(this, "Please select a province from the list", Toast.LENGTH_SHORT).show();
                return;
            }
            if (pick[2].isEmpty() || newCity.isEmpty()) {
                Toast.makeText(this, "Please select a city / municipality from the list", Toast.LENGTH_SHORT).show();
                return;
            }
            if (pick[3].isEmpty() || !newBarangay.equals(pick[4])) {
                Toast.makeText(this, "Please select a barangay from the list", Toast.LENGTH_SHORT).show();
                return;
            }
            String newRegion = PSGCClient.regionNameFromCode(pick[0]);
            if (newRegion.isEmpty()) newRegion = PSGCClient.regionNameFromCode(pick[3]);

            showConfirmAddressChange(prefs, dialog, newStreet, newBarangay, pick[3], newCity, newProvince, newRegion);
        });

        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }
    }

    /** Step 2: confirmation modal before anything is saved. */
    private void showConfirmAddressChange(SharedPreferences prefs, AlertDialog editDialog, String street, String barangay,
                                          String barangayCode, String city, String province, String region) {
        String fullAddress = SupabaseClient.buildFullAddress(street, barangay, city, province);
        boolean barangayChanges = !SupabaseClient.dbPsgc(barangayCode)
                .equals(SupabaseClient.dbPsgc(prefs.getString("USER_BARANGAY_CODE", "")));
        String message = "Your address will be changed to:\n\n" + fullAddress + (region.isEmpty() ? "" : "\n" + region)
                + "\n\nYour barangay, announcements and requests will follow this new address."
                + (barangayChanges ? "\n\nBecause this is a different barangay, its officials will need to verify your account again before you can use the app." : "")
                + " Continue?";

        new AlertDialog.Builder(this)
                .setTitle("Confirm address change")
                .setMessage(message)
                .setNegativeButton("Cancel", (d, w) -> d.dismiss())
                .setPositiveButton("Yes, change it", (d, w) -> {
                    prefs.edit()
                            .putString("USER_STREET", street)
                            .putString("USER_BARANGAY", barangay)
                            .putString("USER_BARANGAY_CODE", barangayCode)
                            .putString("USER_CITY", city)
                            .putString("USER_PROVINCE", province)
                            .putString("USER_REGION", region)
                            .putString("USER_ADDRESS", fullAddress)
                            .remove(HistorySync.KEY_SEEN_ANNOUNCEMENTS)   // new barangay: don't flood with its old posts
                            .remove("CACHED_EMERGENCY_CONTACTS")
                            .apply();
                    selectedBarangayCode = barangayCode;
                    selectedBarangayName = barangay;
                    bindProfileInfo(prefs);
                    loadBarangayAnnouncements(prefs);
                    editDialog.dismiss();

                    String userId = prefs.getString("USER_ID", "");
                    if (userId.isEmpty()) {
                        Toast.makeText(this, "Address saved on this device. Sign in again to sync it.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    SupabaseClient.syncUserAddressToSupabase(userId, street, barangay, barangayCode, city, province, region, new Callback() {
                        @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                            runOnUiThread(() -> Toast.makeText(PreviewActivity.this, "Saved on this device, but syncing failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                        }
                        @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                            String body = response.body() != null ? new String(response.body().bytes(), StandardCharsets.UTF_8) : "";
                            boolean ok = response.isSuccessful() && !body.trim().equals("[]"); // [] = RLS blocked / no row
                            String newStatus = "";
                            try {
                                JSONArray rows = new JSONArray(body);
                                if (rows.length() > 0) newStatus = firstNonEmpty(rows.getJSONObject(0), "account_status");
                            } catch (Exception ignored) {}
                            final String status = newStatus;
                            runOnUiThread(() -> {
                                if (ok && isBlockedStatus(status)) {
                                    showAccountNotApproved(prefs, status); // moved barangay -> re-verification
                                    return;
                                }
                                Toast.makeText(PreviewActivity.this,
                                        ok ? "Address updated!" : "Saved on this device, but your profile wasn't updated on the server (HTTP " + response.code() + ").",
                                        Toast.LENGTH_LONG).show();
                            });
                        }
                    });
                })
                .show();
    }

    // ====================================================================
    // SESSION / PROFILE RESTORE HELPERS
    // ====================================================================

    private static final String[] USER_PREF_KEYS = {
            "USER_ID", "USER_NAME", "USER_EMAIL", "USER_PHONE", "USER_ADDRESS",
            "USER_STREET", "USER_BARANGAY", "USER_BARANGAY_CODE", "USER_CITY", "USER_PROVINCE", "USER_REGION",
            "USER_AVATAR_PATH", "USER_AVATAR_URL", "USER_FIRST_NAME", "USER_MIDDLE_NAME", "USER_LAST_NAME",
            "USER_GENDER", "USER_CIVIL_STATUS"
    };

    /** Removes every per-user value so the next account never sees the previous account's address. */
    private void clearUserPrefs(SharedPreferences.Editor ed) {
        for (String k : USER_PREF_KEYS) ed.remove(k);
    }

    private static String firstNonEmpty(JSONObject src, String... keys) {
        for (String k : keys) {
            if (src.has(k) && !src.isNull(k)) {
                String v = src.optString(k, "").trim();
                if (!v.isEmpty() && !"null".equalsIgnoreCase(v)) return v;
            }
        }
        return "";
    }

    /** Copies name / phone / every address field / avatar from a profiles row or user_metadata into prefs. */
    private void applyProfileToPrefs(JSONObject src, SharedPreferences.Editor ed) {
        applyProfileToPrefs(src, ed, true);
    }

    /** @param includeAddress false = only name/phone/avatar (keeps the address already restored from metadata) */
    private void applyProfileToPrefs(JSONObject src, SharedPreferences.Editor ed, boolean includeAddress) {
        if (src == null) return;
        String first = NameFormat.capitalizeWords(firstNonEmpty(src, "first_name"));
        String middle = NameFormat.capitalizeWords(firstNonEmpty(src, "middle_name"));
        String last = NameFormat.capitalizeWords(firstNonEmpty(src, "last_name"));
        String full = NameFormat.capitalizeWords(firstNonEmpty(src, "full_name"));
        if (full.isEmpty()) full = (first + " " + last).trim();
        if (!first.isEmpty()) ed.putString("USER_FIRST_NAME", first);
        if (!middle.isEmpty()) ed.putString("USER_MIDDLE_NAME", middle);
        if (!last.isEmpty()) ed.putString("USER_LAST_NAME", last);
        String gender = firstNonEmpty(src, "gender", "sex");
        String civil = firstNonEmpty(src, "civil_status", "marital_status");
        if (!gender.isEmpty()) ed.putString("USER_GENDER", gender);
        if (!civil.isEmpty()) ed.putString("USER_CIVIL_STATUS", civil);
        String mobile = firstNonEmpty(src, "mobile_number", "phone");
        String street = firstNonEmpty(src, "street", "house_street");
        String brgyName = TextFix.fix(firstNonEmpty(src, "barangay", "barangay_name"));
        // psgc_code is the barangay field; barangay_id only exists in the sign-up data of old accounts
        String brgyCode = firstNonEmpty(src, "psgc_code", "barangay_id", "barangay_code");
        String city = TextFix.fix(firstNonEmpty(src, "city", "city_municipality"));
        String province = TextFix.fix(firstNonEmpty(src, "province"));
        String region = TextFix.fix(firstNonEmpty(src, "region"));
        String address = TextFix.fix(firstNonEmpty(src, "current_address", "address"));
        String avatar = firstNonEmpty(src, "avatar_url");

        if (!full.isEmpty()) ed.putString("USER_NAME", full);
        if (!mobile.isEmpty()) ed.putString("USER_PHONE", mobile);
        if (!avatar.isEmpty()) ed.putString("USER_AVATAR_URL", avatar);
        if (!includeAddress) return;
        if (!street.isEmpty()) ed.putString("USER_STREET", street);
        if (!brgyName.isEmpty()) ed.putString("USER_BARANGAY", brgyName);
        if (!brgyCode.isEmpty()) ed.putString("USER_BARANGAY_CODE", brgyCode);
        if (!city.isEmpty()) ed.putString("USER_CITY", city);
        if (!province.isEmpty()) ed.putString("USER_PROVINCE", province);
        if (!region.isEmpty()) ed.putString("USER_REGION", region);
        if (!address.isEmpty()) ed.putString("USER_ADDRESS", address);
        if (!avatar.isEmpty()) ed.putString("USER_AVATAR_URL", avatar);
    }

    /** Fills region / full address from the other parts when they're missing. */
    private void completeDerivedAddressFields(SharedPreferences prefs) {
        SharedPreferences.Editor ed = prefs.edit();
        if (prefs.getString("USER_REGION", "").isEmpty()) {
            String r = PSGCClient.regionNameFromCode(prefs.getString("USER_BARANGAY_CODE", ""));
            if (!r.isEmpty()) ed.putString("USER_REGION", r);
        }
        // Barangay name never empty: fall back to the "Brgy. X" part of the saved address
        if (prefs.getString("USER_BARANGAY", "").isEmpty()) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)(?:brgy\\.?|barangay)\\s+([^,]+)").matcher(prefs.getString("USER_ADDRESS", ""));
            if (m.find()) ed.putString("USER_BARANGAY", m.group(1).trim());
        }
        // Only build the one-line address if it's missing (never overwrite the saved one with stale parts)
        if (prefs.getString("USER_ADDRESS", "").isEmpty()) {
            String rebuilt = SupabaseClient.buildFullAddress(
                    prefs.getString("USER_STREET", ""), prefs.getString("USER_BARANGAY", ""),
                    prefs.getString("USER_CITY", ""), prefs.getString("USER_PROVINCE", ""));
            if (!rebuilt.isEmpty()) ed.putString("USER_ADDRESS", rebuilt);
        }
        ed.apply();
    }

    /** Sign-in succeeded: save JWT session, restore ALL profile/address fields from Supabase, then continue. */
    private void handleSignInSuccess(SharedPreferences prefs, String responseBody) {
        JSONObject json;
        try { json = new JSONObject(responseBody); } catch (Exception e) { json = new JSONObject(); }

        SupabaseClient.saveSession(json); // access_token + refresh_token for all authenticated requests

        JSONObject userObj = json.optJSONObject("user");
        String userId = userObj != null ? userObj.optString("id", "") : "";
        String emailStr = userObj != null ? userObj.optString("email", "") : "";
        String phoneStr = userObj != null ? userObj.optString("phone", "") : "";
        JSONObject meta = userObj != null ? userObj.optJSONObject("user_metadata") : null;

        SharedPreferences.Editor ed = prefs.edit();
        clearUserPrefs(ed); // never keep the previous account's address
        if (!userId.isEmpty() && !userId.equals(prefs.getString("HISTORY_OWNER_ID", ""))) {
            // another account signed in on this phone: its history comes from Supabase, not the previous user's
            ed.remove("USER_SUBMITTED_REQUESTS").remove("CACHED_EMERGENCY_CONTACTS")
              .remove(HistorySync.KEY_INBOX).remove(HistorySync.KEY_SEEN_ANNOUNCEMENTS)
              .putString("HISTORY_OWNER_ID", userId);
        }
        ed.putBoolean("IS_LOGGED_IN", false); // only set to true once the profile says "approved"
        if (!userId.isEmpty()) ed.putString("USER_ID", userId);
        if (!emailStr.isEmpty()) ed.putString("USER_EMAIL", emailStr);
        if (!phoneStr.isEmpty()) ed.putString("USER_PHONE", phoneStr);
        if (meta != null) {
            applyProfileToPrefs(meta, ed); // baseline from sign-up metadata; the profiles row overrides below
        } else if (emailStr.contains("@")) {
            ed.putString("USER_NAME", emailStr.split("@")[0]);
        }
        ed.apply();

        // Approval is decided ONLY by profiles.account_status (set by barangay officials).
        // user_metadata is written by the app itself, so it is never trusted for this.
        if (userId.isEmpty()) {
            signOutLocally(prefs);
            Toast.makeText(this, "Sign in failed: no user id returned. Please try again.", Toast.LENGTH_LONG).show();
            return;
        }

        SupabaseClient.fetchUserProfileFromSupabase(userId, new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> {
                    signOutLocally(prefs);
                    Toast.makeText(PreviewActivity.this, "Couldn't check your account: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }

            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                JSONObject row = null;
                try {
                    if (response.isSuccessful() && response.body() != null) {
                        JSONArray arr = new JSONArray(new String(response.body().bytes(), StandardCharsets.UTF_8));
                        if (arr.length() > 0) row = arr.getJSONObject(0);
                    } else {
                        android.util.Log.e("SupabaseProfile", "Profile fetch failed: " + response.code());
                    }
                } catch (Exception ignored) {}

                if (row == null) {
                    runOnUiThread(() -> {
                        signOutLocally(prefs);
                        Toast.makeText(PreviewActivity.this, "Your profile could not be found. Please contact your barangay.", Toast.LENGTH_LONG).show();
                    });
                    return;
                }

                // The profiles row is the source of truth for the barangay (psgc_code) - it is what the
                // officials verified and what announcements / reports are matched against.
                // Street / city / province only exist in the metadata, so those are kept when the row lacks them.
                String metaCode = SupabaseClient.dbPsgc(prefs.getString("USER_BARANGAY_CODE", ""));
                String rowCode = SupabaseClient.dbPsgc(firstNonEmpty(row, "psgc_code", "barangay_id"));
                SharedPreferences.Editor e2 = prefs.edit();
                if (!rowCode.isEmpty() && !rowCode.equals(metaCode) && firstNonEmpty(row, "barangay", "barangay_name").isEmpty()) {
                    e2.remove("USER_BARANGAY"); // name from metadata belongs to another barangay -> looked up below
                }
                applyProfileToPrefs(row, e2, true);
                if (!rowCode.isEmpty()) e2.putString("USER_BARANGAY_CODE", rowCode);
                e2.apply();

                final String rowStatus = firstNonEmpty(row, "account_status");
                if (isBlockedStatus(rowStatus)) {
                    runOnUiThread(() -> showAccountNotApproved(prefs, rowStatus));
                    return;
                }

                // Barangay name missing but we have the PSGC code -> look it up
                String code = prefs.getString("USER_BARANGAY_CODE", "");
                if (prefs.getString("USER_BARANGAY", "").isEmpty() && code.length() >= 9) {
                    PSGCClient.fetchBarangayByCode(code, new PSGCClient.SingleLocationCallback() {
                        @Override public void onSuccess(PSGCClient.LocationItem item) {
                            prefs.edit().putString("USER_BARANGAY", item.name).apply();
                            runOnUiThread(() -> { completeDerivedAddressFields(prefs); openAfterSignIn(R.layout.dashboard); });
                        }
                        @Override public void onError(String error) {
                            runOnUiThread(() -> { completeDerivedAddressFields(prefs); openAfterSignIn(R.layout.dashboard); });
                        }
                    });
                    return;
                }

                runOnUiThread(() -> {
                    completeDerivedAddressFields(prefs);
                    openAfterSignIn(R.layout.dashboard);
                });
            }
        });
    }

    private void openAfterSignIn(int layoutId) {
        if (isFinishing()) return;
        if (layoutId == R.layout.dashboard) {
            // Only reached after profiles.account_status was checked
            getSharedPreferences("AppSession", MODE_PRIVATE).edit().putBoolean("IS_LOGGED_IN", true).apply();
            Toast.makeText(this, "Sign In Successful!", Toast.LENGTH_SHORT).show();
        }
        Intent intent = new Intent(PreviewActivity.this, PreviewActivity.class);
        intent.putExtra("LAYOUT_ID", layoutId);
        startActivity(intent);
        finish();
    }

    private void loadAvatarIntoImageView(ImageView iv) {
        if (iv == null) return;
        SharedPreferences prefs = getSharedPreferences("AppSession", MODE_PRIVATE);
        String savedAvatarPath = prefs.getString("USER_AVATAR_PATH", null);
        String avatarUrl = prefs.getString("USER_AVATAR_URL", null);
        String userId = prefs.getString("USER_ID", "");

        if (savedAvatarPath != null) {
            try {
                File avatarFile = new File(savedAvatarPath);
                if (avatarFile.exists()) {
                    Bitmap bitmap = BitmapFactory.decodeFile(avatarFile.getAbsolutePath());
                    if (bitmap != null) {
                        iv.setImageTintList(null);
                        iv.setImageBitmap(bitmap);
                        return;
                    }
                }
            } catch (Exception ignored) {}
        }

        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            if (avatarUrl.startsWith("data:image/") || avatarUrl.contains("base64,")) {
                try {
                    String base64Data = avatarUrl.contains("base64,") ? avatarUrl.split("base64,")[1] : avatarUrl;
                    byte[] decodedBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT);
                    Bitmap b = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
                    if (b != null) {
                        iv.setImageTintList(null);
                        iv.setImageBitmap(b);
                        return;
                    }
                } catch (Exception ignored) {}
            } else {
                new Thread(() -> {
                    try {
                        InputStream in = new java.net.URL(avatarUrl).openStream();
                        Bitmap b = BitmapFactory.decodeStream(in);
                        if (b != null) {
                            File destFile = new File(getFilesDir(), "user_avatar.jpg");
                            try (FileOutputStream outputStream = new FileOutputStream(destFile)) {
                                b.compress(Bitmap.CompressFormat.JPEG, 90, outputStream);
                            }
                            prefs.edit().putString("USER_AVATAR_PATH", destFile.getAbsolutePath()).apply();

                            runOnUiThread(() -> {
                                iv.setImageTintList(null);
                                iv.setImageBitmap(b);
                            });
                        }
                    } catch (Exception ignored) {}
                }).start();
            }
        } else if (!userId.isEmpty()) {
            // Fetch profile picture from public.profiles (with the user JWT) if local cache was cleared!
            SupabaseClient.fetchUserProfileFromSupabase(userId, new Callback() {
                @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {}
                @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    if (response.isSuccessful() && response.body() != null) {
                        try {
                            String jsonStr = response.body().string();
                            JSONArray arr = new JSONArray(jsonStr);
                            if (arr.length() > 0) {
                                JSONObject userObj = arr.getJSONObject(0);
                                String fetchedAvatarUrl = userObj.optString("avatar_url", userObj.optString("id_photo_url", ""));
                                if (!fetchedAvatarUrl.isEmpty()) {
                                    prefs.edit().putString("USER_AVATAR_URL", fetchedAvatarUrl).apply();
                                    runOnUiThread(() -> loadAvatarIntoImageView(iv));
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            });
        }
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
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }).start();
    }
}
