package com.example.barangay_superapp;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Spinner adapter with a grey placeholder at position 0 (e.g. "Select gender").
 * The placeholder is shown by default but is disabled in the dropdown, so it can never be picked.
 * Use {@link #getValue(Spinner)} to read the choice: it returns "" while the placeholder is showing.
 */
public class HintAdapter extends ArrayAdapter<String> {

    private static final int HINT_COLOR = Color.parseColor("#9AA39E");
    private static final int TEXT_COLOR = Color.parseColor("#11231D");

    public HintAdapter(@NonNull Context context, String hint, String[] items) {
        super(context, R.layout.spinner_item, build(hint, items));
        setDropDownViewResource(R.layout.spinner_dropdown_item);
    }

    private static List<String> build(String hint, String[] items) {
        List<String> list = new ArrayList<>();
        list.add(hint);
        list.addAll(Arrays.asList(items));
        return list;
    }

    @Override
    public boolean isEnabled(int position) {
        return position != 0; // placeholder can't be selected
    }

    @Override
    public boolean areAllItemsEnabled() {
        return false;
    }

    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        View v = super.getView(position, convertView, parent);
        ((TextView) v).setTextColor(position == 0 ? HINT_COLOR : TEXT_COLOR);
        return v;
    }

    @Override
    public View getDropDownView(int position, View convertView, @NonNull ViewGroup parent) {
        View v = super.getDropDownView(position, convertView, parent);
        ((TextView) v).setTextColor(position == 0 ? HINT_COLOR : TEXT_COLOR);
        return v;
    }

    /** Attaches a hint adapter to the spinner (no-op if the spinner is null). */
    public static void attach(Spinner spinner, String hint, String[] items) {
        if (spinner == null) return;
        spinner.setAdapter(new HintAdapter(spinner.getContext(), hint, items));
        spinner.setSelection(0, false);
    }

    /** Selected value, or "" if the placeholder is still showing. */
    public static String getValue(Spinner spinner) {
        if (spinner == null || spinner.getSelectedItemPosition() <= 0 || spinner.getSelectedItem() == null) return "";
        return spinner.getSelectedItem().toString();
    }

    /** Selects the item whose text equals (ignoring case) the given value. Returns true if found. */
    public static boolean selectValue(Spinner spinner, String value) {
        if (spinner == null || value == null || spinner.getAdapter() == null) return false;
        for (int i = 1; i < spinner.getAdapter().getCount(); i++) {
            Object item = spinner.getAdapter().getItem(i);
            if (item != null && item.toString().equalsIgnoreCase(value.trim())) {
                spinner.setSelection(i);
                return true;
            }
        }
        return false;
    }
}
