package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

import org.json.JSONArray;
import org.json.JSONObject;

final class EspnFeed {

    private static final String[] LEAGUES = {"eng.1", "esp.1", "ita.1", "ger.1", "fra.1"};
    private static final String URL_FORMAT =
            "https://site.api.espn.com/apis/site/v2/sports/soccer/%s/scoreboard";
    private static final String LINES_KEY = "espn_lines", AT_KEY = "espn_at";
    private static final int MAX_MATCHES = 6;
    private static final long FRESH_MS = 30 * 60 * 1000;
    private static final long LIVE_FRESH_MS = 2 * 60 * 1000;
    private static final long FINISHED_KEEP_MS = 12 * 60 * 60 * 1000 + 2 * 60 * 60 * 1000;
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 14; TV)"
            + " AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

    private EspnFeed() {
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
        List<String> lines = new ArrayList<>();
        boolean anySuccess = false;
        for (String league : LEAGUES) {
            String body = download(league);
            if (body == null) continue;
            anySuccess = true;
            for (String line : parse(context, body)) {
                if (lines.size() >= MAX_MATCHES) break;
                if (!lines.contains(line)) lines.add(line);
            }
            if (lines.size() >= MAX_MATCHES) break;
        }
        if (!anySuccess) return cached(context);
        prefs(context).edit().putString(LINES_KEY, String.join("\n", lines))
                .putLong(AT_KEY, System.currentTimeMillis()).apply();
        return lines;
    }

    private static String download(String league) {
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) new URL(String.format(Locale.US, URL_FORMAT, league))
                    .openConnection();
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

    private static List<String> parse(Context context, String body) {
        List<String> lines = new ArrayList<>();
        List<String> finished = new ArrayList<>();
        String live = context.getString(R.string.headline_match_live);
        String prefix = context.getString(R.string.headline_match_prefix);
        String done = context.getString(R.string.headline_match_finished);
        String today = LocalDate.now().toString();
        int nowMinutes = LocalTime.now().getHour() * 60 + LocalTime.now().getMinute();
        try {
            JSONArray events = new JSONObject(body).getJSONArray("events");
            for (int i = 0; i < events.length() && lines.size() < MAX_MATCHES; i++) {
                JSONObject competition = events.getJSONObject(i)
                        .getJSONArray("competitions").getJSONObject(0);
                JSONObject status = competition.getJSONObject("status");
                String state = status.getJSONObject("type").getString("state");
                ZonedDateTime when = Instant.parse(events.getJSONObject(i).getString("date"))
                        .atZone(ZoneId.systemDefault());
                JSONArray competitors = competition.getJSONArray("competitors");
                String home = null, away = null, homeScore = null, awayScore = null;
                for (int j = 0; j < competitors.length(); j++) {
                    JSONObject team = competitors.getJSONObject(j);
                    if (team.getString("homeAway").equals("home")) {
                        home = team.getJSONObject("team").getString("displayName");
                        homeScore = team.optString("score", "");
                    } else {
                        away = team.getJSONObject("team").getString("displayName");
                        awayScore = team.optString("score", "");
                    }
                }
                if (home == null || away == null) continue;
                String line;
                if (state.equals("in")) {
                    if (homeScore.isEmpty() || awayScore.isEmpty()) {
                        line = live + " " + home + " - " + away;
                    } else {
                        String clock = status.optString("displayClock", "");
                        if (clock.isEmpty()) {
                            line = live + " " + home + " " + homeScore + " - " + awayScore
                                    + " " + away;
                        } else {
                            line = live + " " + clock + " " + home + " " + homeScore
                                    + " - " + awayScore + " " + away;
                        }
                    }
                    if (!lines.contains(line)) lines.add(line);
                } else if (state.equals("post")) {
                    long age = System.currentTimeMillis() - when.toInstant().toEpochMilli();
                    if (age < 0 || age > FINISHED_KEEP_MS) continue;
                    if (homeScore.isEmpty() || awayScore.isEmpty()) continue;
                    line = done + " " + home + " " + homeScore + " - " + awayScore
                            + " " + away;
                    if (!finished.contains(line)) finished.add(line);
                } else {
                    if (!when.toLocalDate().toString().equals(today)) continue;
                    int minutes = when.getHour() * 60 + when.getMinute();
                    if (minutes < nowMinutes) continue;
                    String time = String.format(Locale.US, "%02d:%02d",
                            when.getHour(), when.getMinute());
                    line = prefix + " " + time + " " + home + " - " + away;
                    if (!lines.contains(line)) lines.add(line);
                }
            }

            for (String line : finished) {
                if (lines.size() >= MAX_MATCHES) break;
                if (!lines.contains(line)) lines.add(line);
            }
        } catch (Exception e) {
            return lines;
        }
        return lines;
    }
}
