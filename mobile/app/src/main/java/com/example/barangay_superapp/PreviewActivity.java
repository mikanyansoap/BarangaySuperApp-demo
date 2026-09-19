package com.example.barangay_superapp;

import android.app.DatePickerDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Pair;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.content.Intent;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

public class PreviewActivity extends AppCompatActivity {
    
    private int selectedDay = -1;
    private String currentHistoryFilter = "All";
    
    private final List<JSONObject> allRequests = new ArrayList<>();
    private final List<JSONObject> filteredRequests = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        int layoutId = getIntent().getIntExtra("LAYOUT_ID", R.layout.starting);
        setContentView(layoutId);

        if (layoutId == R.layout.request_history || layoutId == R.layout.calendar) {
            loadMockData();
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
        // SIGN UP FORM LOGIC -> SUPABASE INTEGRATION
        // ====================================================================
        if (layoutId == R.layout.sign_up) {
            CardView btnCreateAccount = findViewById(R.id.btnCreateAccount);
            EditText etFirstName = findViewById(R.id.etFirstName);
            EditText etLastName = findViewById(R.id.etLastName);
            EditText etMobileNumber = findViewById(R.id.etMobileNumber);
            EditText etEmail = findViewById(R.id.etEmail);
            EditText etSignUpPassword = findViewById(R.id.etSignUpPassword);
            CheckBox cbTerms = findViewById(R.id.cbTerms);
            TextView tvSignInRedirect = findViewById(R.id.tvSignInRedirect);

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

                    // Simple frontend validation
                    if (email.isEmpty() || password.isEmpty() || firstName.isEmpty() || lastName.isEmpty()) {
                        Toast.makeText(this, "Please fill in all required fields", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (cbTerms != null && !cbTerms.isChecked()) {
                        Toast.makeText(this, "Please agree to the Terms and Conditions", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    Toast.makeText(this, "Creating account...", Toast.LENGTH_SHORT).show();

                    try {
                        // Package user info for Supabase 'user_metadata'
                        JSONObject userData = new JSONObject();
                        userData.put("first_name", firstName);
                        userData.put("last_name", lastName);
                        userData.put("phone", "+63" + phone); 

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
                                        // SUCCESS! 
                                        Toast.makeText(PreviewActivity.this, "Account Created Successfully!", Toast.LENGTH_LONG).show();
                                        
                                        // Transition to Account Review notification screen
                                        Intent intent = new Intent(PreviewActivity.this, PreviewActivity.class);
                                        intent.putExtra("LAYOUT_ID", R.layout.account_review_ntf);
                                        startActivity(intent);
                                        finish();
                                    } else {
                                        // HANDLE SUPABASE ERRORS (e.g. Email already registered)
                                        try {
                                            JSONObject errorJson = new JSONObject(responseBody);
                                            String errorMsg = errorJson.optString("msg", "Unknown error occurred");
                                            Toast.makeText(PreviewActivity.this, "Error: " + errorMsg, Toast.LENGTH_LONG).show();
                                        } catch (Exception ex) {
                                            Toast.makeText(PreviewActivity.this, "Sign Up Failed", Toast.LENGTH_SHORT).show();
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
                List<Pair<Boolean, String>> messages = new ArrayList<>();
                // Starting with just the welcome message!
                messages.add(new Pair<>(false, "Hello! I am your Barangay SuperApp Assistant. How can I help you today?"));

                RecyclerView.Adapter<RecyclerView.ViewHolder> chatAdapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    @NonNull
                    @Override
                    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chat_message, parent, false);
                        return new RecyclerView.ViewHolder(view) {};
                    }

                    @Override
                    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                        Pair<Boolean, String> msg = messages.get(position);
                        boolean isUser = msg.first;
                        String text = msg.second;

                        LinearLayout layoutBot = holder.itemView.findViewById(R.id.layoutBotMessage);
                        LinearLayout layoutUser = holder.itemView.findViewById(R.id.layoutUserMessage);
                        TextView tvBot = holder.itemView.findViewById(R.id.tvBotText);
                        TextView tvUser = holder.itemView.findViewById(R.id.tvUserText);
                        CardView cardUserMedia = holder.itemView.findViewById(R.id.cardUserMedia);

                        if (isUser) {
                            layoutBot.setVisibility(View.GONE);
                            layoutUser.setVisibility(View.VISIBLE);
                            tvUser.setText(text);
                            // Hide the media block by default unless you explicitly add an image logic here later
                            if (cardUserMedia != null) cardUserMedia.setVisibility(View.GONE);
                        } else {
                            layoutBot.setVisibility(View.VISIBLE);
                            layoutUser.setVisibility(View.GONE);
                            tvBot.setText(text);
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
                        messages.add(new Pair<>(true, userText));
                        etChatMessage.setText(""); // clear the input box
                        
                        // Tell the adapter a new item was added at the very end
                        chatAdapter.notifyItemInserted(messages.size() - 1);
                        // Instantly scroll to the bottom so the user sees their message
                        rvChatMessages.scrollToPosition(messages.size() - 1);

                        // 2. Call the REAL Gemini AI Backend!
                        GeminiApiClient.sendMessage(userText, new GeminiApiClient.ChatCallback() {
                            @Override
                            public void onSuccess(String responseText) {
                                // We must update the UI on the main thread
                                runOnUiThread(() -> {
                                    messages.add(new Pair<>(false, responseText));
                                    chatAdapter.notifyItemInserted(messages.size() - 1);
                                    rvChatMessages.scrollToPosition(messages.size() - 1);
                                });
                            }

                            @Override
                            public void onError(String errorMessage) {
                                runOnUiThread(() -> {
                                    messages.add(new Pair<>(false, errorMessage));
                                    chatAdapter.notifyItemInserted(messages.size() - 1);
                                    rvChatMessages.scrollToPosition(messages.size() - 1);
                                });
                            }
                        });
                    }
                });
            }
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
                            
                            tvType.setText(req.getString("requestType"));
                            tvDesc.setText(req.getString("description"));
                            tvDate.setText(req.getString("dateSubmitted"));
                            tvStatus.setText(req.getString("status"));
                            
                            tvStatus.setTextColor(Color.parseColor(req.getString("statusColorHex")));
                            cardStatus.setCardBackgroundColor(Color.parseColor(req.getString("statusBgHex")));
                            colorStrip.setBackgroundColor(Color.parseColor(req.getString("statusColorHex")));
                            
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
            LinearLayout layoutMainForm = findViewById(R.id.layoutMainForm);
            LinearLayout layoutTransferForm = findViewById(R.id.layoutTransferForm);
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
                            layoutMainForm.setVisibility(View.GONE);
                            layoutTransferForm.setVisibility(View.GONE);

                            if (selectedId == R.id.rbNewApplicant) {
                                tvSelectedType.setText("Application: New Applicant");
                                layoutMainForm.setVisibility(View.VISIBLE);
                            } else if (selectedId == R.id.rbRenewal) {
                                tvSelectedType.setText("Application: Renewal");
                                layoutMainForm.setVisibility(View.VISIBLE);
                            } else if (selectedId == R.id.rbTransfer) {
                                tvSelectedType.setText("Application: Transfer");
                                layoutTransferForm.setVisibility(View.VISIBLE);
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

    private void launchPreview(int layoutId) {
        Intent intent = new Intent(PreviewActivity.this, PreviewActivity.class);
        intent.putExtra("LAYOUT_ID", layoutId);
        startActivity(intent);
    }

    private void loadMockData() {
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
        }
    }
}