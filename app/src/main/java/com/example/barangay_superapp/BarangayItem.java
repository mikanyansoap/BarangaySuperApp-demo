package com.example.barangay_superapp;

import androidx.annotation.NonNull;

public class BarangayItem {
    private final String name;
    private final String brgyCode;

    public BarangayItem(String name, String brgyCode) {
        this.name = name;
        this.brgyCode = brgyCode;
    }

    public String getName() {
        return name;
    }

    public String getBrgyCode() {
        return brgyCode;
    }

    @NonNull
    @Override
    public String toString() {
        return name;
    }
}