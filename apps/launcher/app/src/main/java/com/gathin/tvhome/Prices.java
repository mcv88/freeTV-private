package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The prices widget's data, all from public sources that need no key: SJC gold (the bar and the
 * 9999 ring) from vang.today's JSON, Petrolimex's region 1 fuel prices from giaxanghomnay.com's
 * table of them, and Vietcombank's USD selling rate from the bank's published XML. Kept for an
 * hour in the launcher's preferences, like the weather; a source that cannot be reached keeps
 * its last figures, so one failure never blanks the card.
 */
final class Prices {

    static final long STALE_MS = 60 * 60_000L;
    private static final String PREFS = "prices";
    private static final int TIMEOUT_MS = 8000;
    /** A fuel price on the page: two digits, a separator, three digits (24.910 or 24,910). */
    private static final Pattern FUEL_PRICE = Pattern.compile("(\\d{2})[.,](\\d{3})");

    static final class Report {
        /** Gold in VND per lượng; 0 while never fetched. */
        final long barBuy, barSell, ringBuy, ringSell;
        /** Fuel in VND per litre, region 1; -1 while never fetched. */
        final int ron95, e5, diesel;
        /** Vietcombank's USD selling rate in VND; -1 while never fetched. */
        final int usd;
        final long fetchedAt;

        Report(long barBuy, long barSell, long ringBuy, long ringSell, int ron95, int e5, int diesel,
               int usd, long fetchedAt) {
            this.barBuy = barBuy;
            this.barSell = barSell;
            this.ringBuy = ringBuy;
            this.ringSell = ringSell;
            this.ron95 = ron95;
            this.e5 = e5;
            this.diesel = diesel;
            this.usd = usd;
            this.fetchedAt = fetchedAt;
        }

        boolean stale(long now) {
            return now - fetchedAt > STALE_MS;
        }

        boolean empty() {
            return barBuy == 0 && ron95 < 0 && usd < 0;
        }
    }

    private Prices() {
    }

    /** The last report saved, or null. */
    static Report cached(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.contains("fetched")) return null;
        return new Report(p.getLong("bar_buy", 0), p.getLong("bar_sell", 0), p.getLong("ring_buy", 0),
                p.getLong("ring_sell", 0), p.getInt("ron95", -1), p.getInt("e5", -1), p.getInt("diesel", -1),
                p.getInt("usd", -1), p.getLong("fetched", 0));
    }

    /** Off the main thread: every source over the last report, saved for next time. */
    static Report fetch(Context context) {
        Report last = cached(context);
        long barBuy = last == null ? 0 : last.barBuy, barSell = last == null ? 0 : last.barSell;
        long ringBuy = last == null ? 0 : last.ringBuy, ringSell = last == null ? 0 : last.ringSell;
        int ron95 = last == null ? -1 : last.ron95, e5 = last == null ? -1 : last.e5;
        int diesel = last == null ? -1 : last.diesel, usd = last == null ? -1 : last.usd;
        boolean reached = false;
        try {
            JSONObject prices = new JSONObject(getText("https://www.vang.today/api/prices"))
                    .getJSONObject("prices");
            JSONObject bar = prices.getJSONObject("SJL1L10"), ring = prices.getJSONObject("SJ9999");
            barBuy = bar.getLong("buy");
            barSell = bar.getLong("sell");
            ringBuy = ring.getLong("buy");
            ringSell = ring.getLong("sell");
            reached = true;
        } catch (IOException | JSONException ignored) {
        }
        try {
            int rate = vietcombankUsd(getText(
                    "https://portal.vietcombank.com.vn/Usercontrols/TVPortal.TyGia/pXML.aspx"));
            if (rate > 0) {
                usd = rate;
                reached = true;
            }
        } catch (IOException ignored) {
        }
        try {
            String page = getText("https://giaxanghomnay.com/");
            int r95 = fuel(page, "Xăng E10 RON 95 Mức 3"), e5Price = fuel(page, "Xăng E5 RON 92");
            int dieselPrice = fuel(page, "Dầu DO 0,05S Mức 2");
            if (r95 > 0 && e5Price > 0 && dieselPrice > 0) {
                ron95 = r95;
                e5 = e5Price;
                diesel = dieselPrice;
                reached = true;
            }
        } catch (IOException ignored) {
        }
        long now = System.currentTimeMillis();
        Report report = new Report(barBuy, barSell, ringBuy, ringSell, ron95, e5, diesel, usd,
                reached ? now : last == null ? 0 : last.fetchedAt);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("bar_buy", barBuy).putLong("bar_sell", barSell).putLong("ring_buy", ringBuy)
                .putLong("ring_sell", ringSell).putInt("ron95", ron95).putInt("e5", e5)
                .putInt("diesel", diesel).putInt("usd", usd).putLong("fetched", report.fetchedAt).apply();
        return report;
    }

    /** Vietcombank's USD selling rate from its rates XML, or -1 when the row cannot be read. */
    static int vietcombankUsd(String xml) {
        int row = xml.indexOf("CurrencyCode=\"USD\"");
        if (row < 0) return -1;
        String sellMark = "Sell=\"";
        int at = xml.indexOf(sellMark, row);
        if (at < 0) return -1;
        int end = xml.indexOf('"', at + sellMark.length());
        if (end < 0) return -1;
        try {
            return (int) Math.round(Double.parseDouble(
                    xml.substring(at + sellMark.length(), end).replace(",", "")));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** A product's region 1 price on the fuel page: the first price after its name, or -1. */
    static int fuel(String page, String product) {
        int at = 0;
        while (true) {
            at = page.indexOf(product, at);
            if (at < 0) return -1;
            Matcher m = FUEL_PRICE.matcher(page.substring(at, Math.min(page.length(), at + 1500)));
            if (m.find()) return Integer.parseInt(m.group(1) + m.group(2));
            at += product.length();
        }
    }

    private static String getText(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setRequestProperty("User-Agent", "tvhome-launcher");
        try (InputStream in = c.getInputStream()) {
            if (c.getResponseCode() != 200) throw new IOException(url + ": HTTP " + c.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
