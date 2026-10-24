package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

final class MatchesFeed {

    private static final String PAGE_URL =
            "https://www.24h.com.vn/v-league-cuoc-dua-nong-bong-c48e3408.html";
    private static final String LINES_KEY = "matches_lines", AT_KEY = "matches_at";
    private static final int MAX_MATCHES = 6;
    private static final long FRESH_MS = 30 * 60 * 1000;
    private static final long LIVE_FRESH_MS = 2 * 60 * 1000;
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 14; TV)"
            + " AppleWebKit/537.36 Chrome/120.0 Safari/537.36";
    private static final Pattern FIXTURE = Pattern.compile(
            "^(.+?)\\s+vs\\s+(.+?)$\\s*^(\\d{2}/\\d{2}/\\d{2})\\s+-\\s+"
                    + "(Trực tiếp|\\d{2}:\\d{2})$",
            Pattern.MULTILINE);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yy");

    private MatchesFeed() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    static List<String> cached(Context context) {
        String text = prefs(context).getString(LINES_KEY, "");
        List<String> lines = new ArrayList<>();
        if (text.isEmpty()) return lines;
        for (String line : text.split("\n")) {
            if (!line.isEmpty()) lines.add(line);
        }
        return lines;
    }

    static boolean stale(Context context, long now) {
        long age = now - prefs(context).getLong(AT_KEY, 0);
        if (age > FRESH_MS) return true;
        if (age <= LIVE_FRESH_MS) return false;
        String live = context.getString(R.string.headline_match_live);
        for (String line : cached(context)) {
            if (line.startsWith(live)) return true;
        }
        return false;
    }

    static List<String> fetch(Context context) {
        List<String> lines = parse(context, download());
        if (lines == null) return cached(context);
        prefs(context).edit().putString(LINES_KEY, String.join("\n", lines))
                .putLong(AT_KEY, System.currentTimeMillis()).apply();
        return lines;
    }

    private static String download() {
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) new URL(PAGE_URL).openConnection();
            http.setConnectTimeout(30_000);
            http.setReadTimeout(30_000);
            http.setRequestProperty("User-Agent", USER_AGENT);
            http.setRequestProperty("Accept-Encoding", "gzip");
            InputStream raw = http.getInputStream();
            InputStream in = "gzip".equalsIgnoreCase(http.getContentEncoding())
                    ? new GZIPInputStream(raw) : raw;
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) out.append(line).append('\n');
            }
            return out.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (http != null) http.disconnect();
        }
    }

    private static List<String> parse(Context context, String html) {
        if (html == null) return null;
        String text = html.replaceAll("<[^>]*>", "\n");
        String today = LocalDate.now().format(DAY);
        int nowMinutes = LocalTime.now().getHour() * 60 + LocalTime.now().getMinute();
        List<String> lines = new ArrayList<>();
        Matcher matcher = FIXTURE.matcher(text);
        while (matcher.find() && lines.size() < MAX_MATCHES) {
            if (!matcher.group(3).equals(today)) continue;
            String home = matcher.group(1).trim();
            String away = matcher.group(2).trim();
            String when = matcher.group(4);
            String line;
            if (when.equals("Trực tiếp")) {
                String live = context.getString(R.string.headline_match_live);
                String score = liveScore(text, matcher.end());
                if (score == null) {
                    line = live + " " + home + " - " + away;
                } else {
                    line = live + " " + home + " " + score + " " + away;
                }
            } else {
                int hour = Integer.parseInt(when.substring(0, 2));
                int minute = Integer.parseInt(when.substring(3, 5));
                if (hour * 60 + minute < nowMinutes) continue;
                line = context.getString(R.string.headline_match_prefix)
                        + " " + when + " " + home + " - " + away;
            }
            if (!lines.contains(line)) lines.add(line);
        }
        return lines;
    }

    private static String liveScore(String text, int from) {
        String tail = text.substring(from, Math.min(text.length(), from + 400));
        List<String> rows = new ArrayList<>();
        for (String row : tail.split("\n")) {
            String trimmed = row.trim();
            if (!trimmed.isEmpty()) rows.add(trimmed);
            if (rows.size() >= 4) break;
        }
        if (rows.size() < 4) return null;
        if (!rows.get(1).matches("\\d+") || !rows.get(3).matches("\\d+")) return null;
        return rows.get(1) + " - " + rows.get(3);
    }
}
