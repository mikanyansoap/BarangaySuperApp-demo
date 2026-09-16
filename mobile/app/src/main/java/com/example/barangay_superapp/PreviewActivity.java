package com.example.barangay_superapp;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import androidx.annotation.NonNull;

public class PreviewActivity extends AppCompatActivity {
    
    // Track selected day state for the Calendar preview
    private int selectedDay = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Retrieve the layout ID passed from MainActivity
        int layoutId = getIntent().getIntExtra("LAYOUT_ID", R.layout.starting);
        setContentView(layoutId);

        // ====================================================================
        // INTERACTIVE DEMO LOGIC FOR "CALENDAR"
        // ====================================================================
        if (layoutId == R.layout.calendar) {
            RecyclerView rvGrid = findViewById(R.id.rvCalendarGrid);
            RecyclerView rvEvents = findViewById(R.id.rvCalendarEvents);
            TextView tvUpcomingHeader = findViewById(R.id.tvUpcomingHeader);
            LinearLayout layoutEmptyState = findViewById(R.id.layoutEmptyStateCalendar);

            if (rvGrid != null && rvEvents != null && tvUpcomingHeader != null && layoutEmptyState != null) {
                
                // Initialize the Events List Adapter (We will notify it when data changes)
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
                        
                        // If a specific day with an event is selected
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
                        // Default view (Upcoming)
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
                        if (selectedDay == 11 || selectedDay == 14 || selectedDay == 22) {
                            return 1; // Only show 1 event if a specific date is clicked
                        } else if (selectedDay == -1) {
                            return 2; // Show 2 upcoming events by default
                        } else {
                            return 0; // Show 0 events if a blank day is clicked
                        }
                    }
                };

                rvEvents.setLayoutManager(new LinearLayoutManager(this));
                rvEvents.setAdapter(eventsAdapter);

                // Initialize the Grid Adapter
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
                            
                            // Check if this date is currently "selected"
                            if (dayNum == selectedDay) {
                                background.setCardBackgroundColor(Color.parseColor("#DDF0EC")); // Light teal selection highlight
                                tvDay.setTextColor(Color.parseColor("#174A45"));
                            } else if (dayNum == 9 && selectedDay == -1) { // Default highlight for "today"
                                background.setCardBackgroundColor(Color.parseColor("#0D4A41"));
                                tvDay.setTextColor(Color.parseColor("#FFFFFF"));
                            } else {
                                background.setCardBackgroundColor(Color.TRANSPARENT);
                                tvDay.setTextColor(Color.parseColor("#11231D"));
                            }
                            
                            if (dayNum == 11 || dayNum == 14 || dayNum == 22) {
                                dot.setVisibility(View.VISIBLE);
                            } else {
                                dot.setVisibility(View.INVISIBLE);
                            }

                            // Click Listener for the day!
                            holder.itemView.setOnClickListener(v -> {
                                selectedDay = dayNum;
                                notifyDataSetChanged(); // Refresh grid selection UI
                                
                                // Update the bottom list
                                tvUpcomingHeader.setText("Sept " + dayNum + "'s events");
                                
                                eventsAdapter.notifyDataSetChanged(); // Refresh list data
                                
                                // Toggle Empty State
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
                            holder.itemView.setOnClickListener(null); // Disable clicks for faded days
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
}