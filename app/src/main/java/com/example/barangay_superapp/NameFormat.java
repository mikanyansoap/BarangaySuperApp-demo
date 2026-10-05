package com.example.barangay_superapp;

import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;

/** Capitalizes the first letter of every word (the first letter, and every letter right after a space). */
public final class NameFormat {

    private NameFormat() {}

    public static String capitalizeWords(String s) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        StringBuilder sb = new StringBuilder(s.length());
        boolean capNext = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (capNext && Character.isLetter(c)) {
                sb.append(Character.toUpperCase(c));
                capNext = false;
            } else {
                sb.append(c);
                if (c != ' ') capNext = false;
            }
            if (c == ' ') capNext = true;
        }
        return sb.toString();
    }

    /** Capitalizes each word while the user types (keyboards don't always honor textCapWords). */
    public static void attach(EditText editText) {
        if (editText == null) return;
        editText.addTextChangedListener(new TextWatcher() {
            private boolean selfChange = false;
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (selfChange) return;
                String current = s.toString();
                String fixed = capitalizeWords(current);
                if (!fixed.equals(current)) {
                    selfChange = true;
                    int sel = editText.getSelectionStart();
                    s.replace(0, s.length(), fixed);
                    if (sel >= 0 && sel <= s.length()) editText.setSelection(sel);
                    selfChange = false;
                }
            }
        });
    }
}
