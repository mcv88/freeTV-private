package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

final class NewsFeed {

    private static final String TITLES_KEY = "news_titles", AT_KEY = "news_at";
    private static final int MAX_TITLES = 8;
    private static final int MAX_MERGED = 20;
    private static final long FRESH_MS = 30 * 60 * 1000;
    private static final String USER_AGENT = "tvhome-launcher";

    private NewsFeed() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    static List<String> cached(Context context) {
        String text = prefs(context).getString(TITLES_KEY, "");
        List<String> titles = new ArrayList<>();
        if (text.isEmpty()) return titles;
        for (String title : text.split("\n")) {
            if (!title.isEmpty()) titles.add(title);
        }
        return titles;
    }

    static boolean stale(Context context, long now) {
        return now - prefs(context).getLong(AT_KEY, 0) > FRESH_MS;
    }

    static List<String> fetch(Context context, List<String> urls) {
        List<List<String>> perSource = new ArrayList<>();
        for (String url : urls) {
            List<String> titles = download(url);
            if (titles != null && !titles.isEmpty()) perSource.add(titles);
        }
        if (perSource.isEmpty()) return cached(context);
        List<String> merged = new ArrayList<>();
        for (int index = 0; merged.size() < MAX_MERGED; index++) {
            boolean added = false;
            for (List<String> titles : perSource) {
                if (index < titles.size() && !merged.contains(titles.get(index))) {
                    merged.add(titles.get(index));
                    added = true;
                    if (merged.size() >= MAX_MERGED) break;
                }
            }
            if (!added) break;
        }
        prefs(context).edit().putString(TITLES_KEY, String.join("\n", merged))
                .putLong(AT_KEY, System.currentTimeMillis()).apply();
        return merged;
    }

    private static List<String> download(String url) {
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) new URL(url).openConnection();
            http.setConnectTimeout(30_000);
            http.setReadTimeout(30_000);
            http.setRequestProperty("User-Agent", USER_AGENT);
            try (InputStream stream = http.getInputStream()) {
                return parse(stream);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (http != null) http.disconnect();
        }
    }

    private static List<String> parse(InputStream in) throws Exception {
        XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(in, "UTF-8");
        List<String> titles = new ArrayList<>();
        boolean inItem = false;
        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.getName().equals("item")) {
                inItem = true;
            } else if (event == XmlPullParser.END_TAG && parser.getName().equals("item")) {
                inItem = false;
            } else if (event == XmlPullParser.START_TAG && inItem
                    && parser.getName().equals("title")) {
                String title = parser.nextText().replace('\n', ' ').trim();
                if (!title.isEmpty() && titles.size() < MAX_TITLES) titles.add(title);
            }
            event = parser.next();
        }
        return titles;
    }
}
