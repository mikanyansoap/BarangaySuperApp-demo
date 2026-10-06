package com.example.barangay_superapp;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Guided "AI report builder": asks the resident a few questions, then drafts a report
 * (category + title + description + location). Uses Gemini when an API key is configured,
 * otherwise a local keyword-based drafter so the feature always works.
 * The draft is never submitted directly - it opens the report form so the user can review and edit it.
 */
public final class ReportAssistant {

    private ReportAssistant() {}

    public static final String[] REPORT_CATEGORIES = {
            "Disturbance / Noise Complaint", "Delinquency / Vandalism", "Blocked Drainage / Flooding",
            "Garbage Collection Issue", "Streetlight Repair", "Others"};

    public static final String[] DISASTER_TYPES = {
            "Flooding", "Fire Emergency", "Landslide", "Typhoon / Strong Winds", "Medical Emergency", "Others"};

    public static final String[] QUESTIONS = {
            "Ano ang nangyari? Ikwento mo nang maikli. (What happened?)",
            "Saan ito nangyari? Ibigay ang street, purok o landmark. (You can pin the exact spot on the map later.)",
            "Kailan ito nangyari? (e.g. ngayon lang, kaninang 3PM, kagabi)",
            "May nasaktan ba o may taong nasa panganib ngayon? (e.g. wala, 2 ang sugatan)",
            "May iba ka pang gustong idagdag? (e.g. sino ang sangkot, plate number). I-type ang \"wala\" kung wala na."
    };

    public static class Draft {
        public boolean isDisaster;
        public String category = "Others";
        public String title = "";
        public String description = "";
        public String location = "";
        public boolean fromGemini;

        public String summary() {
            return (isDisaster ? "Disaster / Emergency report" : "Barangay report") + "\n"
                    + "Category: " + category + "\n"
                    + "Location: " + (location.isEmpty() ? "(to be pinned)" : location) + "\n\n"
                    + description;
        }
    }

    public interface DraftCallback {
        void onDraft(Draft draft);
    }

    private static final String[] INTENT_WORDS = {
            "report", "ireport", "magreport", "reklamo", "complain", "blotter",
            "sunog", "nasusunog", "fire", "baha", "bumabaha", "binaha", "flood", "ingay", "maingay", "noise",
            "away", "nag-aaway", "gulo", "nakaw", "ninakaw", "magnanakaw", "aksidente", "accident", "disaster",
            "emergency", "landslide", "guho", "gumuho", "bagyo", "typhoon", "basura", "garbage", "kanal",
            "drainage", "streetlight", "poste", "vandal", "sugatan", "injured"};

    public static boolean looksLikeReportIntent(String message) {
        if (message == null) return false;
        String m = message.toLowerCase(Locale.ROOT);
        if (m.contains("gumawa ng report") || m.contains("i-report") || m.contains("mag-report")) return true;
        return has(m, INTENT_WORDS);
    }

    public static boolean isNone(String answer) {
        if (answer == null) return true;
        String a = answer.trim().toLowerCase(Locale.ROOT);
        return a.isEmpty() || a.equals("wala") || a.equals("none") || a.equals("no") || a.equals("n/a") || a.equals("wala na");
    }

    /** answers: [what, where, when, injuries, other] */
    public static void buildDraft(String[] answers, DraftCallback callback) {
        final Draft local = localDraft(answers);
        if (!GeminiApiClient.isConfigured()) {
            callback.onDraft(local);
            return;
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("You help Filipino residents file barangay reports. Using ONLY the resident's answers below, ")
              .append("write a clear, factual incident report. Do not invent facts, names or numbers. ")
              .append("Write the description in the same language the resident used (English, Filipino or Taglish), 3 to 6 sentences.\n")
              .append("Return JSON only with keys: type (\"report\" or \"disaster\"), category, title (max 60 chars), description, location.\n")
              .append("If type is \"report\", category must be exactly one of: ").append(String.join(" | ", REPORT_CATEGORIES)).append("\n")
              .append("If type is \"disaster\" (fire, flood, landslide, typhoon, medical emergency or anything life-threatening), ")
              .append("category must be exactly one of: ").append(String.join(" | ", DISASTER_TYPES)).append("\n\n")
              .append("What happened: ").append(safe(answers, 0)).append("\n")
              .append("Where: ").append(safe(answers, 1)).append("\n")
              .append("When: ").append(safe(answers, 2)).append("\n")
              .append("Injuries / people at risk: ").append(safe(answers, 3)).append("\n")
              .append("Other details: ").append(safe(answers, 4)).append("\n");

        GeminiApiClient.generate(prompt.toString(), true, new GeminiApiClient.ChatCallback() {
            @Override public void onSuccess(String responseText) {
                try {
                    String json = responseText.trim();
                    if (json.startsWith("```")) json = json.replaceAll("^```(json)?", "").replaceAll("```$", "").trim();
                    JSONObject o = new JSONObject(json);
                    Draft d = new Draft();
                    d.isDisaster = "disaster".equalsIgnoreCase(o.optString("type", local.isDisaster ? "disaster" : "report"));
                    String[] allowed = d.isDisaster ? DISASTER_TYPES : REPORT_CATEGORIES;
                    d.category = matchCategory(o.optString("category", ""), allowed, local.isDisaster == d.isDisaster ? local.category : "Others");
                    d.title = o.optString("title", local.title);
                    d.description = o.optString("description", local.description).trim();
                    d.location = o.optString("location", local.location).trim();
                    if (d.description.isEmpty()) d.description = local.description;
                    d.fromGemini = true;
                    callback.onDraft(d);
                } catch (Exception e) {
                    callback.onDraft(local);
                }
            }
            @Override public void onError(String errorMessage) {
                callback.onDraft(local);
            }
        });
    }

    private static String safe(String[] a, int i) {
        return (a != null && i < a.length && a[i] != null) ? a[i].trim() : "";
    }

    private static String matchCategory(String value, String[] allowed, String fallback) {
        for (String c : allowed) if (c.equalsIgnoreCase(value.trim())) return c;
        return fallback;
    }

    /**
     * True if any keyword starts a word in the text (so "baha" matches "baha"/"bahang" but NOT "kapitbahay").
     * Keywords containing a space are matched as phrases.
     */
    static boolean has(String text, String... words) {
        String[] tokens = text.toLowerCase(Locale.ROOT).split("[^\\p{L}0-9]+");
        for (String w : words) {
            if (w.contains(" ")) {
                if (text.contains(w)) return true;
                continue;
            }
            for (String t : tokens) if (t.startsWith(w)) return true;
        }
        return false;
    }

    public interface SeverityCallback {
        void onSeverity(String priority);
    }

    public static void assessSeverity(String type, String category, String description, SeverityCallback callback) {
        if (!GeminiApiClient.isConfigured()) {
            callback.onSeverity(type.equalsIgnoreCase("Incident") ? "high" : "medium");
            return;
        }
        String prompt = "Rate the emergency severity of this barangay report as exactly 'low', 'medium', or 'high'. Reply with ONLY ONE WORD.\n" +
            "Type: " + type + "\nCategory: " + category + "\nDetails: " + description;
        GeminiApiClient.generate(prompt, false, new GeminiApiClient.ChatCallback() {
            @Override public void onSuccess(String r) {
                String s = r.trim().toLowerCase(Locale.ROOT);
                if (s.contains("high")) callback.onSeverity("high");
                else if (s.contains("low")) callback.onSeverity("low");
                else callback.onSeverity("medium");
            }
            @Override public void onError(String e) {
                callback.onSeverity(type.equalsIgnoreCase("Incident") ? "high" : "medium");
            }
        });
    }
    
    /** Keyword-based drafter used when Gemini isn't configured or fails. */
    public static Draft localDraft(String[] answers) {
        String what = safe(answers, 0), where = safe(answers, 1), when = safe(answers, 2),
               hurt = safe(answers, 3), other = safe(answers, 4);
        String all = (what + " " + other + " " + hurt).toLowerCase(Locale.ROOT);

        Draft d = new Draft();
        if (has(all, "sunog", "nasusunog", "sinunog", "fire", "usok", "smoke")) { d.isDisaster = true; d.category = "Fire Emergency"; }
        else if (has(all, "landslide", "guho", "gumuho", "pagguho")) { d.isDisaster = true; d.category = "Landslide"; }
        else if (has(all, "bagyo", "typhoon", "malakas na hangin", "strong wind", "natumbang puno")) { d.isDisaster = true; d.category = "Typhoon / Strong Winds"; }
        else if (has(all, "baha", "bumabaha", "bumaha", "binaha", "flood", "lubog", "nalubog", "tumataas ang tubig")) {
            if (has(all, "kanal", "drainage", "bara", "nabara", "imburnal")) { d.category = "Blocked Drainage / Flooding"; }
            else { d.isDisaster = true; d.category = "Flooding"; }
        }
        else if (has(all, "hinimatay", "nahimatay", "sugat", "injur", "aksidente", "accident", "ambulance", "medical", "atake", "inatake", "heart attack", "dugo", "dumudugo")) { d.isDisaster = true; d.category = "Medical Emergency"; }
        else if (has(all, "ingay", "maingay", "noise", "videoke", "karaoke", "away", "nag-aaway", "aaway", "gulo", "sigawan", "lasing", "fight")) { d.category = "Disturbance / Noise Complaint"; }
        else if (has(all, "vandal", "graffiti", "sinira", "nakaw", "ninakaw", "nagnakaw", "magnanakaw", "theft", "stolen", "snatch", "tambay")) { d.category = "Delinquency / Vandalism"; }
        else if (has(all, "kanal", "drainage", "bara", "nabara", "imburnal")) { d.category = "Blocked Drainage / Flooding"; }
        else if (has(all, "basura", "garbage", "trash", "hakot", "kolekta", "kinokolekta", "nakolekta")) { d.category = "Garbage Collection Issue"; }
        else if (has(all, "ilaw", "streetlight", "street light", "poste", "madilim", "patay na ilaw")) { d.category = "Streetlight Repair"; }
        else { d.category = "Others"; }

        d.location = where;
        String shortWhat = what.length() > 50 ? what.substring(0, 47).trim() + "..." : what;
        d.title = d.category + (shortWhat.isEmpty() ? "" : ": " + shortWhat);

        StringBuilder desc = new StringBuilder();
        desc.append("What happened: ").append(what.isEmpty() ? "(not specified)" : what);
        if (!isNone(when)) desc.append("\nWhen: ").append(when);
        desc.append("\nInjuries / people at risk: ").append(isNone(hurt) ? "None reported" : hurt);
        if (!isNone(other)) desc.append("\nOther details: ").append(other);
        d.description = desc.toString();
        return d;
    }
}
