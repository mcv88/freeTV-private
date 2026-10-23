package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Vietnam's holidays as published data, not dates typed into the code: Google Calendar's public
 * "Holidays in Vietnam" calendar (the same feed Google Calendar shows), read as iCalendar text.
 * It carries the lunar holidays (Tet, Hung Kings) with their real solar dates, which the public
 * holiday APIs miss. The text is cached in the launcher's settings and refreshed weekly; parsing
 * drops the "ngay nghi" (day off in lieu) entries and folds a holiday's run of days into its first.
 */
final class HolidayFeed {

    /** One holiday: its published name and the solar day it falls on. */
    static final class Event {
        final String name;
        final LocalDate date;

        Event(String name, LocalDate date) {
            this.name = name;
            this.date = date;
        }
    }

    private static final String FEED_URL = "https://calendar.google.com/calendar/ical/"
            + "vi.vietnamese%23holiday%40group.v.calendar.google.com/public/basic.ics";
    private static final String TEXT_KEY = "holiday_feed", AT_KEY = "holiday_feed_at";
    private static final long FRESH_MS = 7L * 24 * 60 * 60 * 1000;

    private HolidayFeed() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    /** The cached feed's events, soonest first; empty until the first fetch lands. */
    static List<Event> cached(Context context) {
        String text = prefs(context).getString(TEXT_KEY, "");
        return text.isEmpty() ? new ArrayList<>() : parse(text);
    }

    static boolean stale(Context context, long now) {
        return now - prefs(context).getLong(AT_KEY, 0) > FRESH_MS;
    }

    /** Downloads the feed, caches it and returns its events; empty when the download fails. */
    static List<Event> fetch(Context context) {
        String text = download();
        if (text == null || !text.contains("BEGIN:VCALENDAR")) return new ArrayList<>();
        prefs(context).edit().putString(TEXT_KEY, text)
                .putLong(AT_KEY, System.currentTimeMillis()).apply();
        return parse(text);
    }

    private static String download() {
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) new URL(FEED_URL).openConnection();
            http.setConnectTimeout(10_000);
            http.setReadTimeout(10_000);
            StringBuilder out = new StringBuilder();
            try (InputStream in = http.getInputStream();
                 BufferedReader reader = new BufferedReader(
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

    /** The feed's events, soonest first: past ones and days off in lieu dropped, runs folded. */
    static List<Event> parse(String ics) {
        List<Event> all = new ArrayList<>();
        String name = null;
        LocalDate date = null;
        for (String line : unfold(ics)) {
            if (line.equals("BEGIN:VEVENT")) {
                name = null;
                date = null;
            } else if (line.startsWith("DTSTART")) {
                date = dateOf(line);
            } else if (line.startsWith("SUMMARY")) {
                int colon = line.indexOf(':');
                if (colon >= 0) name = unescape(line.substring(colon + 1).trim());
            } else if (line.equals("END:VEVENT") && name != null && date != null) {
                if (!plain(name).startsWith("ngay nghi")) all.add(new Event(name, date));
            }
        }
        all.sort((a, b) -> a.date.compareTo(b.date));
        List<Event> folded = new ArrayList<>();
        for (Event e : all) {
            Event last = folded.isEmpty() ? null : folded.get(folded.size() - 1);
            if (last != null && last.name.equals(e.name)
                    && !e.date.isAfter(last.date.plusDays(10))) continue;
            folded.add(e);
        }
        return folded;
    }

    /** iCalendar folds long lines: a line starting with a space continues the one before. */
    private static List<String> unfold(String ics) {
        List<String> lines = new ArrayList<>();
        for (String raw : ics.split("\n")) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.startsWith(" ") && !lines.isEmpty()) {
                int last = lines.size() - 1;
                lines.set(last, lines.get(last) + line.substring(1));
            } else {
                lines.add(line);
            }
        }
        return lines;
    }

    /** The day in a DTSTART line: its first eight digits (yyyyMMdd), all-day or timed alike. */
    private static LocalDate dateOf(String line) {
        int colon = line.indexOf(':');
        if (colon < 0) return null;
        String value = line.substring(colon + 1).trim();
        if (value.length() < 8) return null;
        try {
            return LocalDate.of(Integer.parseInt(value.substring(0, 4)),
                    Integer.parseInt(value.substring(4, 6)),
                    Integer.parseInt(value.substring(6, 8)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String unescape(String text) {
        return text.replace("\\,", ",").replace("\\;", ";")
                .replace("\\n", " ").replace("\\\\", "\\");
    }

    /** Lower case without diacritics, so a "ngay nghi" prefix is recognised in any encoding. */
    private static String plain(String text) {
        String stripped = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return stripped.replace('đ', 'd').replace('Đ', 'D').toLowerCase();
    }
}
