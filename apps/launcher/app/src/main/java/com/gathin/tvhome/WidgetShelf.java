package com.gathin.tvhome;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.utilities.Blend;
import com.google.android.material.color.utilities.Hct;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Home's widget shelf: the owner's cards in the open space between the header and the dock. They
 * share the row's full width between them (the air card a smaller share, the weather and the
 * calendar a larger one), so a hidden widget's room goes to the rest instead of staying empty.
 * <ul>
 *   <li>Weather ({@link Weather}): the temperature and today's range, chips for the chance of rain, the
 *       humidity, the air quality and the UV, and the next five days.</li>
 *   <li>This TV: CPU load (every few seconds, {@link CpuMeter}), memory and storage in use, to read only. (Android 14 lets an app
 *       end only its own background processes, so a launcher cannot free other apps' memory itself.)</li>
 *   <li>Air quality: the US AQI and PM2.5 at the weather's place ({@link AirQuality}), its band in the
 *       band's colour harmonised with the scheme; OK switches between now and tomorrow's worst hour.</li>
 *   <li>Lunar calendar: today's lunar date, the month and the year's can chi, and the next three events
 *       with how far away each is: holidays from the published calendar feed ({@link HolidayFeed}),
 *       fixed Vietnamese observances, and full moon days.</li>
 *   <li>Prices ({@link Prices}): SJC gold, Petrolimex fuel for region 1 and Vietcombank's USD rate.</li>
 * </ul>
 * The owner picks which widgets show and in what order: Launcher settings has a switch per widget,
 * and holding OK on a widget offers Move and Remove. The list is kept in the launcher's settings
 * ({@link #ORDER_KEY}), and so is each widget's own
 * settings (Settings on its menu): the place and unit of the weather (the air quality shares the
 * place), whether the calendar counts down to every holiday or only Tết, and whether This TV shows CPU.
 * It scrolls away with the grid when focus goes below the dock. Nothing runs while Home is hidden, and
 * nothing on it animates except focus: the box this runs on is slow.
 */
@SuppressLint("RestrictedApi")   // material-color-utilities' Blend and Hct, as in Palette
final class WidgetShelf {

    static final String PREF_KEY = "widgets";
    /** The widgets on the shelf, left to right, comma separated. */
    static final String ORDER_KEY = "widget_order";
    static final String WEATHER = "weather", SYSTEM = "system", AIR = "air", CALENDAR = "calendar",
            PRICES = "prices";
    /** Shown until the owner picks their own: the weather, the calendar and the prices. */
    static final String DEFAULT_ORDER = WEATHER + "," + CALENDAR + "," + PRICES;
    static final String LAYOUT_KEY = "widget_layout";
    static final String LAYOUT_STACKED = "stacked";
    static final String LAYOUT_WIDE = "wide";
    static final String HEIGHT_KEY_PREFIX = "widget_height_";
    static final String WIDTH_KEY_PREFIX = "widget_width_";
    static final int[] WIDTH_OPTIONS = {70, 85, 100, 120, 140};
    static final int DEFAULT_WIDTH_PERCENT = 100;
    static final int DEFAULT_HEIGHT_DP = 200;
    private static final String[] ALL = {WEATHER, SYSTEM, AIR, CALENDAR, PRICES};

    /** What the shelf needs from Home: its menus, the storage page, and a say when the cards change. */
    interface Host {
        void showMenu(View anchor, List<MenuItem> items);

        /** The cards on the shelf changed: the header's Down target may have moved. */
        void slotsChanged();

        /** A widget was picked up or put down in Edit Home. */
        void carryChanged();

        /** Starts Edit Home (Move on a widget's menu); false when it cannot start. */
        boolean startEditing();
    }

    static final class MenuItem {
        final int icon;
        final String title;
        final Runnable action;
        /** A setting's current value, shown at the row's end; null for an action. */
        final String value;

        MenuItem(int icon, String title, Runnable action) {
            this(icon, title, action, null);
        }

        MenuItem(int icon, String title, Runnable action, String value) {
            this.icon = icon;
            this.title = title;
            this.action = action;
            this.value = value;
        }
    }
    /** A failed weather fetch is not retried sooner than this. */
    private static final long WEATHER_RETRY_MS = 5 * 60_000L;

    private final Activity activity;
    private final View shelf;
    private final WidgetCard[] cards;
    /** The widgets shown, left to right (ids from {@link #ALL}). */
    private final List<String> order = new ArrayList<>();
    /** The cards on the shelf now, left to right: the widgets shown. */
    private final List<WidgetCard> slots = new ArrayList<>();
    private RecyclerView lastGrid;
    /** Cards move when the list changes: added ones grow in, removed ones shrink out, the rest slide. */
    private final boolean motion;
    /** The focus indicator sliding between the cards, as between app tiles. */
    private final ShelfHighlight highlight;
    /** Edit Home is on: the indicator takes the edit colour and OK picks a widget up. */
    private boolean editing;
    /** The widget being carried in Edit Home, or null. */
    private String carried;
    private static final float ENTER_SCALE = 0.85f;
    private int lastColumns = 4, lastDockTop;
    private int rowLeft, rowWidth, cardGap, tilePitch;
    /** Each slot's place on the row (left edge and width, without the focus reach), from align. */
    private final List<Integer> slotLefts = new ArrayList<>(), slotWidths = new ArrayList<>();
    /** The dock's first tile and how many tiles it has, for moving focus between cards and apps. */
    private int dockLeft, dockTiles = 1;
    private final TextView weatherPlace, weatherTemp, weatherCond, weatherDetail;
    private final ShapeBadge weatherIcon;
    private final TextView weatherRainValue, weatherHumidityValue, weatherAqiValue, weatherUvValue;
    private final TextView chipRainLabel, chipHumidityLabel, chipAqiLabel, chipUvLabel;
    private final TextView[] dayNames, dayTemps;
    private final ImageView[] dayIcons;
    private final TextView ramValue, storageValue;
    private final M3Progress ramBar, storageBar;
    private final ImageView ramIcon, storageIcon;
    private final TextView airLabel, airValue, airDetail;
    private final ShapeBadge airBadge;
    private final TextView lunarLabel, lunarDate, lunarMonth;
    private final View[] eventRows;
    private final TextView[] eventNames, eventWhens, eventBadges;
    private final TextView pricesTitle, pricesGoldLabel, pricesFuelLabel;
    private final TextView pricesBuyLabel, pricesSellLabel, pricesRegionLabel;
    private final TextView[] pricesNames;
    private final TextView pricesUpdated, pricesBarBuy, pricesBarSell, pricesRingBuy, pricesRingSell;
    private final TextView pricesRon95, pricesE5, pricesDiesel, pricesUsd;
    private final TextView ramName, storageName, cpuName, cpuValue;
    private final M3Progress cpuBar;
    private final ImageView cpuIcon;
    private final CpuMeter cpu;
    /** How often the CPU meter reads while Home shows: often enough to see a load come and go. */
    private static final long CPU_MS = 3000;
    private final Runnable cpuTick = new Runnable() {
        @Override
        public void run() {
            int busy = cpu.sample();
            if (busy != CpuMeter.NOT_YET) lastCpu = busy;
            if (systemPage == SystemPages.USAGE && showCpu() || systemPage == SystemPages.CHIP) paintSystem();
            main.postDelayed(this, CPU_MS);
        }
    };
    private int lastCpu = CpuMeter.NOT_YET;
    /** How far the header reaches over the grid's frame, from the last align. */
    private int headerUnderlap;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float density;
    /** The weather_city in config.xml: the place until the owner picks one on the widget. */
    private final String configCity;
    private final SharedPreferences prefs;
    private static final String CITY_KEY = "weather_city", UNIT_KEY = "weather_unit",
            TET_ONLY_KEY = "calendar_tet_only", CPU_KEY = "system_cpu";
    /** The page each card opens on when Home shows, from its settings. */
    private static final String AIR_PAGE_KEY = "air_page", SYSTEM_PAGE_KEY = "system_page";
    private static final int[] AIR_PAGE_NAMES = {R.string.page_today, R.string.page_tomorrow};
    /** The places offered, as geocoder queries ("name|country"); "" is wherever the IP says. */
    private static final String[] CITY_QUERIES = {"", "Hanoi|VN", "Ho Chi Minh City|VN", "Da Nang|VN", "Haiphong|VN",
            "Can Tho|VN", "Hue|VN", "Nha Trang|VN", "Da Lat|VN"};
    private static final String[] CITY_NAMES = {null, "Hà Nội", "TP. Hồ Chí Minh", "Đà Nẵng", "Hải Phòng",
            "Cần Thơ", "Huế", "Nha Trang", "Đà Lạt"};
    private final Host host;
    private Palette palette = Palette.currentOrBaseline();
    private boolean enabled, started;

    private Weather.Report report;
    private boolean fetching;
    private long lastFailure;

    private AirQuality.Report air;
    private boolean airFetching, airTomorrow;
    private long airFailure;

    private Prices.Report prices;
    private boolean pricesFetching;
    private long pricesFailure;

    /** Vietnam's holidays from the published calendar feed ({@link HolidayFeed}), cached on the way in. */
    private List<HolidayFeed.Event> feedEvents;
    private boolean feedFetching;

    /** What the system card shows, stepped by OK (SystemPages), in its three meter rows. */
    private int systemPage;
    private final SystemPages systemPages;
    private final ImageView[] rowIcons;
    private final TextView[] rowNames, rowValues;
    private final M3Progress[] rowBars;
    private IntConsumer downFromCard;

    WidgetShelf(Activity activity, View shelf, boolean animations, boolean reducedMotion, boolean expressiveMotion, Host host) {
        this.activity = activity;
        this.shelf = shelf;
        this.host = host;
        motion = animations && !reducedMotion;
        highlight = ShelfHighlight.attach((ViewGroup) shelf, animations, reducedMotion, expressiveMotion);
        density = activity.getResources().getDisplayMetrics().density;
        configCity = activity.getString(R.string.weather_city).trim();
        prefs = activity.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        cpu = new CpuMeter(activity);
        cards = new WidgetCard[]{shelf.findViewById(R.id.widgetWeather), shelf.findViewById(R.id.widgetSystem),
                shelf.findViewById(R.id.widgetAir), shelf.findViewById(R.id.widgetCalendar),
                shelf.findViewById(R.id.widgetPrices)};
        weatherPlace = shelf.findViewById(R.id.weatherPlace);
        weatherTemp = shelf.findViewById(R.id.weatherTemp);
        weatherCond = shelf.findViewById(R.id.weatherCond);
        weatherDetail = shelf.findViewById(R.id.weatherDetail);
        weatherIcon = shelf.findViewById(R.id.weatherIcon);
        chipRainLabel = shelf.findViewById(R.id.chipRainLabel);
        chipHumidityLabel = shelf.findViewById(R.id.chipHumidityLabel);
        chipAqiLabel = shelf.findViewById(R.id.chipAqiLabel);
        chipUvLabel = shelf.findViewById(R.id.chipUvLabel);
        weatherRainValue = shelf.findViewById(R.id.weatherRainValue);
        weatherHumidityValue = shelf.findViewById(R.id.weatherHumidityValue);
        weatherAqiValue = shelf.findViewById(R.id.weatherAqiValue);
        weatherUvValue = shelf.findViewById(R.id.weatherUvValue);
        dayNames = new TextView[]{shelf.findViewById(R.id.dayName0), shelf.findViewById(R.id.dayName1),
                shelf.findViewById(R.id.dayName2), shelf.findViewById(R.id.dayName3),
                shelf.findViewById(R.id.dayName4)};
        dayTemps = new TextView[]{shelf.findViewById(R.id.dayTemp0), shelf.findViewById(R.id.dayTemp1),
                shelf.findViewById(R.id.dayTemp2), shelf.findViewById(R.id.dayTemp3),
                shelf.findViewById(R.id.dayTemp4)};
        dayIcons = new ImageView[]{shelf.findViewById(R.id.dayIcon0), shelf.findViewById(R.id.dayIcon1),
                shelf.findViewById(R.id.dayIcon2), shelf.findViewById(R.id.dayIcon3),
                shelf.findViewById(R.id.dayIcon4)};
        ramValue = shelf.findViewById(R.id.ramValue);
        storageValue = shelf.findViewById(R.id.storageValue);
        ramBar = shelf.findViewById(R.id.ramBar);
        storageBar = shelf.findViewById(R.id.storageBar);
        ramIcon = shelf.findViewById(R.id.ramIcon);
        storageIcon = shelf.findViewById(R.id.storageIcon);
        airLabel = shelf.findViewById(R.id.airLabel);
        airValue = shelf.findViewById(R.id.airValue);
        airDetail = shelf.findViewById(R.id.airDetail);
        airBadge = shelf.findViewById(R.id.airBadge);
        airBadge.setIcon(activity.getDrawable(R.drawable.ic_air));
        lunarLabel = shelf.findViewById(R.id.lunarLabel);
        lunarDate = shelf.findViewById(R.id.lunarDate);
        lunarMonth = shelf.findViewById(R.id.lunarMonth);
        eventRows = new View[]{shelf.findViewById(R.id.eventRow0), shelf.findViewById(R.id.eventRow1),
                shelf.findViewById(R.id.eventRow2)};
        eventNames = new TextView[]{shelf.findViewById(R.id.eventName0), shelf.findViewById(R.id.eventName1),
                shelf.findViewById(R.id.eventName2)};
        eventWhens = new TextView[]{shelf.findViewById(R.id.eventWhen0), shelf.findViewById(R.id.eventWhen1),
                shelf.findViewById(R.id.eventWhen2)};
        eventBadges = new TextView[]{shelf.findViewById(R.id.eventBadge0), shelf.findViewById(R.id.eventBadge1),
                shelf.findViewById(R.id.eventBadge2)};
        pricesTitle = shelf.findViewById(R.id.pricesTitle);
        pricesGoldLabel = shelf.findViewById(R.id.pricesGoldLabel);
        pricesFuelLabel = shelf.findViewById(R.id.pricesFuelLabel);
        pricesBuyLabel = shelf.findViewById(R.id.pricesBuyLabel);
        pricesSellLabel = shelf.findViewById(R.id.pricesSellLabel);
        pricesRegionLabel = shelf.findViewById(R.id.pricesRegionLabel);
        pricesNames = new TextView[]{shelf.findViewById(R.id.pricesBarName),
                shelf.findViewById(R.id.pricesRingName), shelf.findViewById(R.id.pricesRon95Name),
                shelf.findViewById(R.id.pricesE5Name), shelf.findViewById(R.id.pricesDieselName),
                shelf.findViewById(R.id.pricesUsdName)};
        pricesUpdated = shelf.findViewById(R.id.pricesUpdated);
        pricesBarBuy = shelf.findViewById(R.id.pricesBarBuy);
        pricesBarSell = shelf.findViewById(R.id.pricesBarSell);
        pricesRingBuy = shelf.findViewById(R.id.pricesRingBuy);
        pricesRingSell = shelf.findViewById(R.id.pricesRingSell);
        pricesRon95 = shelf.findViewById(R.id.pricesRon95);
        pricesE5 = shelf.findViewById(R.id.pricesE5);
        pricesDiesel = shelf.findViewById(R.id.pricesDiesel);
        pricesUsd = shelf.findViewById(R.id.pricesUsd);
        cpuName = shelf.findViewById(R.id.cpuName);
        cpuValue = shelf.findViewById(R.id.cpuValue);
        cpuBar = shelf.findViewById(R.id.cpuBar);
        cpuIcon = shelf.findViewById(R.id.cpuIcon);
        ramName = shelf.findViewById(R.id.ramName);
        storageName = shelf.findViewById(R.id.storageName);
        systemPages = new SystemPages(activity);
        rowIcons = new ImageView[]{cpuIcon, ramIcon, storageIcon};
        rowNames = new TextView[]{cpuName, ramName, storageName};
        rowValues = new TextView[]{cpuValue, ramValue, storageValue};
        rowBars = new M3Progress[]{cpuBar, ramBar, storageBar};
        for (TextView line : new TextView[]{weatherPlace, weatherDetail, airLabel, airDetail, lunarLabel}) {
            TextFit.attach(line);
        }
        for (TextView line : eventNames) TextFit.attach(line);
        for (TextView line : eventWhens) TextFit.attach(line);
        for (int i = 0; i < rowNames.length; i++) {
            TextFit.attachRow(rowNames[i], rowValues[i], (View) rowNames[i].getParent());
        }
        loadOrder();
        applyCardHeight();


        for (WidgetCard card : allCards()) {
            card.setOnKeyListener((v, code, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int column = slots.indexOf(v);
                if (code == KeyEvent.KEYCODE_DPAD_DOWN && downFromCard != null) {
                    downFromCard.accept(dockColumnUnderCard(Math.max(0, column)));
                    return true;
                }
                // The row's ends: focus must not wrap off the shelf sideways.
                return code == KeyEvent.KEYCODE_DPAD_LEFT && column == 0
                        || code == KeyEvent.KEYCODE_DPAD_RIGHT && column == slots.size() - 1;
            });
        }
        for (int i = 0; i < ALL.length; i++) {
            String id = ALL[i];
            cards[i].setOnLongClickListener(v -> {
                showEditMenu(id);
                return true;
            });
        }
        for (WidgetCard card : allCards()) card.setMotion(motion);
        cards[1].setOnClickListener(v -> {
            systemPage = (systemPage + 1) % SystemPages.COUNT;
            cards[1].turn(this::paintSystem);
        });
        cards[2].setOnClickListener(v -> {
            airTomorrow = !airTomorrow;
            cards[2].turn(this::paintAir);
        });
    }

    /** Down from the card over dock column i: the activity focuses the app below it. */
    void setOnDown(IntConsumer focusDock) {
        downFromCard = focusDock;
    }

    boolean enabled() {
        return enabled;
    }

    /** Shows or hides the shelf (Launcher settings, "widgets"); a hidden shelf reads nothing. */
    void setEnabled(boolean on) {
        if (on == enabled) return;
        enabled = on;
        shelf.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) stop();
    }

    /** Focus moves up from dock column i: onto the card over it. */
    boolean focusFromDock(int column) {
        int n = slots.size();
        if (!enabled || n == 0 || shelf.getAlpha() < 0.5f) return false;
        return slots.get(Math.max(0, Math.min(cardOverDockColumn(column), n - 1))).requestFocus();
    }

    /** The card header buttons should go down to: the last one, under them. */
    int cardUnderHeaderButtons() {
        return slots.isEmpty() ? View.NO_ID : slots.get(slots.size() - 1).getId();
    }

    private WidgetCard[] allCards() {
        return cards;
    }

    private WidgetCard card(String id) {
        for (int i = 0; i < ALL.length; i++) if (ALL[i].equals(id)) return cards[i];
        return null;
    }

    private boolean has(String id) {
        return order.contains(id);
    }

    // ---- choosing widgets ------------------------------------------------------------------------

    private void loadOrder() {
        order.clear();
        order.addAll(savedOrder());
    }

    /** The saved list: the widgets shown, left to right. */
    private List<String> savedOrder() {
        List<String> saved = new ArrayList<>();
        String text = prefs.getString(ORDER_KEY, DEFAULT_ORDER);
        for (String id : text.split(",")) {
            id = id.trim();
            if (id.equals("now_playing")) id = AIR;   // the widget air quality replaced, in its place
            if (card(id) != null && !saved.contains(id)) saved.add(id);
        }
        return saved;
    }

    /** Launcher settings flipped a widget's switch: take the list as saved there, without stealing focus. */
    void refreshOrder() {
        List<String> saved = savedOrder();
        if (!saved.equals(order)) applyOrder(saved, null, 0, false);
        applyCardHeight();
    }

    /** The arrangement the owner picked in settings: the stacked one until they choose wide. */
    static String layoutMode(SharedPreferences prefs) {
        return LAYOUT_WIDE.equals(prefs.getString(LAYOUT_KEY, LAYOUT_STACKED))
                ? LAYOUT_WIDE : LAYOUT_STACKED;
    }

    static int layoutRes(SharedPreferences prefs) {
        return LAYOUT_WIDE.equals(layoutMode(prefs)) ? R.layout.widget_shelf
                : R.layout.widget_shelf_stacked;
    }

    /** The height the owner picked for one card in settings, in dp. */
    static int cardHeightDp(SharedPreferences prefs, String id) {
        return prefs.getInt(HEIGHT_KEY_PREFIX + id, DEFAULT_HEIGHT_DP);
    }

    /** How wide the owner wants one card, as a percent of its usual share of the row. */
    static int cardWidthPercent(SharedPreferences prefs, String id) {
        return prefs.getInt(WIDTH_KEY_PREFIX + id, DEFAULT_WIDTH_PERCENT);
    }

    /** Sets each card to its own picked height; the shelf fits the tallest card shown. */
    void applyCardHeight() {
        int tallest = 0;
        for (int i = 0; i < cards.length; i++) {
            int height = Math.round(cardHeightDp(prefs, ALL[i]) * density);
            ViewGroup.LayoutParams lp = cards[i].getLayoutParams();
            if (lp != null && lp.height != height) {
                lp.height = height;
                cards[i].setLayoutParams(lp);
            }
            if (order.contains(ALL[i])) tallest = Math.max(tallest, height);
        }
        if (tallest == 0) tallest = Math.round(DEFAULT_HEIGHT_DP * density);
        ViewGroup.LayoutParams shelfLayout = shelf.getLayoutParams();
        int shelfHeight = tallest + Math.round(8 * density);
        if (shelfLayout != null && shelfLayout.height != shelfHeight) {
            shelfLayout.height = shelfHeight;
            shelf.setLayoutParams(shelfLayout);
        }
    }

    /**
     * Saves a new list and shows it, focus on focusId's card, or on the card now at the same column.
     * removing is the widget leaving, if one is: it shrinks and fades out first (emphasized accelerate,
     * as M3 exits do), then the others slide into place on a default spatial spring.
     */
    private void setOrder(List<String> next, String focusId, int fallbackColumn, String removing) {
        WidgetCard leaving = removing == null ? null : card(removing);
        if (!motion || leaving == null) {
            applyOrder(next, focusId, fallbackColumn, true);
            return;
        }
        leaving.animate().cancel();
        leaving.animate().alpha(0f).scaleX(ENTER_SCALE).scaleY(ENTER_SCALE)
                .setInterpolator(Motion.emphasizedAccelerate()).setDuration(Motion.EMPHASIZED_ACCELERATE_MS)
                .withEndAction(() -> {
                    leaving.setAlpha(1f);
                    leaving.setScaleX(1f);
                    leaving.setScaleY(1f);
                    applyOrder(next, focusId, fallbackColumn, true);
                }).start();
    }

    private void applyOrder(List<String> next, String focusId, int fallbackColumn, boolean refocus) {
        Map<WidgetCard, Integer> before = new HashMap<>();
        for (WidgetCard c : slots) before.put(c, c.getLeft());
        order.clear();
        order.addAll(next);
        activity.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit()
                .putString(ORDER_KEY, String.join(",", order)).apply();
        if (started) {
            // Start or stop what the added or removed widget reads.
            stop();
            start();
        }
        if (lastGrid != null) align(lastGrid, lastColumns, lastDockTop);
        host.slotsChanged();
        if (motion) {
            // After the new layout, before it is drawn: start each card from where it was.
            shelf.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    shelf.getViewTreeObserver().removeOnPreDrawListener(this);
                    for (WidgetCard c : slots) {
                        Integer from = before.get(c);
                        if (from == null) {
                            c.setAlpha(0f);
                            c.setScaleX(ENTER_SCALE);
                            c.setScaleY(ENTER_SCALE);
                            Motion.DEFAULT_SPATIAL.drive(c.animate().alpha(1f).scaleX(1f).scaleY(1f)).start();
                        } else if (from != c.getLeft()) {
                            c.setTranslationX(from - c.getLeft());
                            Motion.DEFAULT_SPATIAL.drive(c.animate().translationX(0f)).start();
                        }
                    }
                    return true;
                }
            });
        }
        if (refocus) shelf.post(() -> {
            WidgetCard target = focusId != null ? card(focusId) : null;
            if (target == null || !slots.contains(target)) {
                target = slots.isEmpty() ? null : slots.get(Math.max(0, Math.min(fallbackColumn, slots.size() - 1)));
            }
            if (target != null) {
                target.requestFocus();
                highlight.follow(target);   // a card that kept focus but moved fires no focus change
            }
        });
    }

    private void showEditMenu(String id) {
        int at = order.indexOf(id);
        if (at < 0) return;
        List<MenuItem> items = new ArrayList<>();
        // Move works as on an app's menu: Edit Home starts with this widget picked up.
        if (order.size() > 1) {
            items.add(new MenuItem(R.drawable.ic_move, activity.getString(R.string.tile_move), () -> {
                if (!host.startEditing()) return;
                WidgetCard card = card(id);
                if (card != null) card.requestFocus();
                carried = id;
                highlight.setLifted(true);
                host.carryChanged();
            }));
        }
        items.add(new MenuItem(R.drawable.ic_settings_outline, activity.getString(R.string.widget_settings),
                () -> showSettingsMenu(id)));
        items.add(new MenuItem(R.drawable.ic_remove, activity.getString(R.string.widget_remove), () -> {
            List<String> next = new ArrayList<>(order);
            next.remove(id);
            setOrder(next, null, at, id);
        }));
        host.showMenu(card(id), items);
    }

    private void move(String id, int by) {
        List<String> next = new ArrayList<>(order);
        int at = next.indexOf(id);
        if (at < 0 || at + by < 0 || at + by >= next.size()) return;
        Collections.swap(next, at, at + by);
        setOrder(next, id, at + by, null);
    }

    // ---- Edit Home -------------------------------------------------------------------------------

    void setEditing(boolean on) {
        editing = on;
        if (!on) drop();
        highlight.setColor(on ? palette.tertiary : palette.primary);
    }

    /** The carried widget's name, or null. */
    String carriedLabel() {
        return carried == null ? null : activity.getString(name(carried));
    }

    /**
     * A key in Edit Home with focus on the shelf. OK picks the focused widget up; while it is carried,
     * Left and Right move it along the shelf, Up and Down do nothing, and OK or Back put it down. False
     * for keys left to the activity (Back with nothing carried ends the mode).
     */
    boolean handleEditKey(KeyEvent event, View focused) {
        if (!editing || !(focused instanceof WidgetCard) || focused.getParent() != shelf) return false;
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int code = event.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE;
        if (carried != null) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (down) move(carried, code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (ok || back) {
                if (!down) drop();
                return true;
            }
            return false;
        }
        String id = idOf(focused);
        if (ok && id != null) {
            if (!down && event.getRepeatCount() == 0) {
                carried = id;
                highlight.setLifted(true);
                host.carryChanged();
            }
            return true;
        }
        return false;
    }

    private void drop() {
        if (carried == null) return;
        carried = null;
        highlight.setLifted(false);
        host.carryChanged();
    }

    private String idOf(View card) {
        for (int i = 0; i < ALL.length; i++) if (cards[i] == card) return ALL[i];
        return null;
    }

    // ---- per-widget settings ---------------------------------------------------------------------

    private String city() {
        return prefs.getString(CITY_KEY, configCity);
    }

    private boolean fahrenheit() {
        return "f".equals(prefs.getString(UNIT_KEY, "c"));
    }

    private boolean tetOnly() {
        return prefs.getBoolean(TET_ONLY_KEY, false);
    }

    private boolean showCpu() {
        return prefs.getBoolean(CPU_KEY, true);
    }

    /** The place's name as the menu shows it. */
    private String cityName(String query) {
        for (int i = 0; i < CITY_QUERIES.length; i++) {
            if (CITY_QUERIES[i].equals(query)) return CITY_NAMES[i] != null ? CITY_NAMES[i] : activity.getString(R.string.widget_city_auto);
        }
        return query.contains("|") ? query.substring(0, query.indexOf('|')) : query;
    }

    /**
     * A widget's settings, matching what the card shows: each row its icon, its name and, at its end, the current
     * value; OK on one lists its choices. Every card that steps through pages chooses the one it opens on.
     */
    private void showSettingsMenu(String id) {
        WidgetCard anchor = card(id);
        List<MenuItem> items = new ArrayList<>();
        switch (id) {
            case WEATHER:
                items.add(setting(R.drawable.ic_location, R.string.setting_location, cityName(city()),
                        () -> showCityMenu(anchor)));
                items.add(setting(R.drawable.ic_thermostat, R.string.setting_unit, fahrenheit() ? "°F" : "°C",
                        () -> showChoices(anchor, new String[]{"°C", "°F"}, fahrenheit() ? 1 : 0, i -> {
                            prefs.edit().putString(UNIT_KEY, i == 1 ? "f" : "c").apply();
                            paintWeather();
                        })));
                break;
            case PRICES:
                items.add(new MenuItem(R.drawable.ic_refresh, activity.getString(R.string.prices_refresh),
                        () -> refreshPrices(true)));
                break;
            case AIR:
                items.add(setting(R.drawable.ic_location, R.string.setting_location_shared, cityName(city()),
                        () -> showCityMenu(anchor)));
                items.add(opensOn(anchor, AIR_PAGE_KEY, AIR_PAGE_NAMES, i -> {
                    airTomorrow = i == 1;
                    paintAir();
                }));
                break;
            case CALENDAR:
                String[] countdown = {activity.getString(R.string.countdown_next),
                        activity.getString(R.string.countdown_tet)};
                items.add(setting(R.drawable.ic_timer, R.string.setting_countdown, countdown[tetOnly() ? 1 : 0],
                        () -> showChoices(anchor, countdown, tetOnly() ? 1 : 0, i -> {
                            prefs.edit().putBoolean(TET_ONLY_KEY, i == 1).apply();
                            paintCalendar();
                        })));
                break;
            default:
                items.add(opensOn(anchor, SYSTEM_PAGE_KEY, SystemPages.NAMES, i -> {
                    systemPage = i;
                    paintSystem();
                }));
                String[] shown = {activity.getString(R.string.option_shown),
                        activity.getString(R.string.option_hidden)};
                items.add(setting(R.drawable.ic_memory, R.string.setting_cpu, shown[showCpu() ? 0 : 1],
                        () -> showChoices(anchor, shown, showCpu() ? 0 : 1, i -> {
                            prefs.edit().putBoolean(CPU_KEY, i == 0).apply();
                            if (i == 0 && lastCpu == CpuMeter.UNAVAILABLE) {
                                // The TV hides the figures from apps: ask it to allow reading them through adb.
                                cpu.askToAllow();
                                lastCpu = CpuMeter.NOT_YET;
                            }
                            paintSystem();
                        })));
                break;
        }
        host.showMenu(anchor, items);
    }

    private MenuItem setting(int icon, int name, String value, Runnable choose) {
        return new MenuItem(icon, activity.getString(name), choose, value);
    }

    /** The page a card opens on when Home shows; picking one also shows it now. */
    private MenuItem opensOn(View anchor, String key, int[] pageNames, IntConsumer show) {
        String[] names = new String[pageNames.length];
        for (int i = 0; i < names.length; i++) names[i] = activity.getString(pageNames[i]);
        int current = Math.min(prefs.getInt(key, 0), names.length - 1);
        return setting(R.drawable.ic_swap_vert, R.string.setting_opens, names[current],
                () -> showChoices(anchor, names, current, i -> {
                    prefs.edit().putInt(key, i).apply();
                    show.accept(i);
                }));
    }

    /** The places: Automatic (the IP's) and the main cities. Weather and air quality both refresh for it. */
    private void showCityMenu(View anchor) {
        String current = city();
        String[] names = new String[CITY_QUERIES.length];
        int selected = -1;
        for (int i = 0; i < CITY_QUERIES.length; i++) {
            names[i] = cityName(CITY_QUERIES[i]);
            if (CITY_QUERIES[i].equals(current)) selected = i;
        }
        showChoices(anchor, names, selected, i -> {
            prefs.edit().putString(CITY_KEY, CITY_QUERIES[i]).apply();
            if (has(WEATHER)) refreshWeather(true);
            if (has(AIR)) refreshAir(true);
        });
    }

    /** A menu of choices, the current one marked, like a radio group. */
    private void showChoices(View anchor, String[] labels, int selected, IntConsumer choose) {
        List<MenuItem> items = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            items.add(new MenuItem(i == selected ? R.drawable.ic_radio_on : R.drawable.ic_radio_off, labels[i],
                    () -> choose.accept(index)));
        }
        host.showMenu(anchor, items);
    }

    /**
     * The row the cards share: the full row of dock columns, even when the dock holds fewer apps
     * (DockDecoration centres a short dock, so the full row is centred on it). The cards divide the
     * row between them by {@link #weight(WidgetCard)} instead of sitting one over each column, so
     * hiding a widget widens the rest instead of leaving its column empty.
     */
    private boolean measureDock(RecyclerView grid, int columns) {
        int itemCount = grid.getAdapter() == null ? 0 : grid.getAdapter().getItemCount();
        int lastColumn = Math.max(0, Math.min(columns, itemCount) - 1);
        Rect first = tileBounds(grid, 0);
        Rect second = lastColumn > 0 ? tileBounds(grid, 1) : null;
        Rect last = tileBounds(grid, lastColumn);
        if (first == null || last == null || (lastColumn > 0 && second == null)) return false;

        int tileWidth = first.width();
        int pitch = second != null ? Math.abs(second.left - first.left)
                : (grid.getWidth() - grid.getPaddingLeft() - grid.getPaddingRight()) / Math.max(1, columns);
        int tilesLeft = Math.min(first.left, last.left);
        int centre = (tilesLeft + Math.max(first.right, last.right)) / 2;
        rowWidth = (columns - 1) * pitch + tileWidth;
        rowLeft = centre - rowWidth / 2;
        cardGap = pitch - tileWidth;
        tilePitch = pitch;
        dockLeft = tilesLeft;
        dockTiles = lastColumn + 1;
        return true;
    }

    /** A card's share of the row: the air card reads at a glance, so it takes less; the weather
     * and the calendar carry lists, so they take more. */
    private double weight(WidgetCard card) {
        String id = idOf(card);
        double base = WEATHER.equals(id) || CALENDAR.equals(id) ? 1.15 : AIR.equals(id) ? 0.7 : 1.0;
        return base * cardWidthPercent(prefs, id) / 100.0;
    }

    private Rect tileBounds(RecyclerView grid, int position) {
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(position);
        View tile = holder == null ? null : holder.itemView.findViewById(R.id.card);
        if (tile == null || tile.getWidth() == 0) return null;

        Rect bounds = new Rect(0, 0, tile.getWidth(), tile.getHeight());
        ((ViewGroup) shelf.getParent()).offsetDescendantRectToMyCoords(tile, bounds);
        return bounds;
    }

    private int dockColumnUnderCard(int card) {
        if (tilePitch <= 0 || card >= slotLefts.size()) return card;
        int centre = slotLefts.get(card) + slotWidths.get(card) / 2;
        return Math.max(0, Math.min(dockTiles - 1, Math.floorDiv(centre - dockLeft, tilePitch)));
    }

    /** The card whose centre is nearest the dock column's centre: where Up from that app lands. */
    private int cardOverDockColumn(int column) {
        if (slotLefts.isEmpty()) return column;
        int centre = dockLeft + column * tilePitch + tilePitch / 2;
        int best = 0, bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < slotLefts.size(); i++) {
            int distance = Math.abs(slotLefts.get(i) + slotWidths.get(i) / 2 - centre);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    private static int name(String id) {
        switch (id) {
            case WEATHER: return R.string.weather;
            case SYSTEM: return R.string.widget_system;
            case AIR: return R.string.air_quality;
            case PRICES: return R.string.prices;
            default: return R.string.lunar_calendar;
        }
    }

    private static int icon(String id) {
        switch (id) {
            case WEATHER: return R.drawable.ic_w_partly_day;
            case SYSTEM: return R.drawable.ic_memory;
            case AIR: return R.drawable.ic_air;
            case PRICES: return R.drawable.ic_speed;
            default: return R.drawable.ic_calendar;
        }
    }

    // ---- lifecycle -------------------------------------------------------------------------------

    void start() {
        if (!enabled || started) return;
        started = true;
        airTomorrow = prefs.getInt(AIR_PAGE_KEY, 0) == 1;
        systemPage = prefs.getInt(SYSTEM_PAGE_KEY, 0) % SystemPages.COUNT;
        if (has(CALENDAR)) refreshHolidays(false);
        if (has(SYSTEM)) {
            // The baseline: the first figure comes with the first tick, unless the TV hides every source.
            if (cpu.sample() == CpuMeter.UNAVAILABLE) lastCpu = CpuMeter.UNAVAILABLE;
            paintSystem();
            main.postDelayed(cpuTick, CPU_MS);
        }
        if (has(WEATHER)) refreshWeather(false);
        if (has(AIR)) refreshAir(false);
        if (has(PRICES)) refreshPrices(false);
    }

    void stop() {
        if (!started) return;
        started = false;
        main.removeCallbacks(cpuTick);
        cpu.stop();
    }

    /** Once a minute while Home shows. */
    void tick() {
        if (!started) return;
        if (has(CALENDAR)) refreshHolidays(false);
        if (has(SYSTEM)) paintSystem();
        if (has(WEATHER)) refreshWeather(false);
        if (has(AIR)) refreshAir(false);
        if (has(PRICES)) refreshPrices(false);
    }

    // ---- placement -------------------------------------------------------------------------------

    /**
     * Lays the shown cards across the row's full width, left to right (each with its focus room around
     * it), and centres the shelf in the space above the dock. dockTop is the dock's top in the shelf's
     * parent, which also holds the grid.
     */
    void align(RecyclerView grid, int columns, int dockTop) {
        lastGrid = grid;
        lastColumns = columns;
        lastDockTop = dockTop;
        if (!enabled) return;
        slots.clear();
        for (String id : order) slots.add(card(id));
        for (WidgetCard c : allCards()) {
            int visibility = slots.contains(c) ? View.VISIBLE : View.GONE;
            if (c.getVisibility() != visibility) c.setVisibility(visibility);
        }
        if (!measureDock(grid, columns)) return;

        slotLefts.clear();
        slotWidths.clear();
        double weights = 0;
        for (WidgetCard c : slots) weights += weight(c);
        int room = rowWidth - cardGap * Math.max(0, slots.size() - 1);
        int nextLeft = rowLeft;
        for (int index = 0; index < slots.size(); index++) {
            int width = index == slots.size() - 1 ? rowLeft + rowWidth - nextLeft
                    : Math.round((float) (room * weight(slots.get(index)) / weights));
            slotLefts.add(nextLeft);
            slotWidths.add(width);
            nextLeft += width + cardGap;
        }
        int inset = Math.round(WidgetCard.REACH_DP * density);
        boolean moved = false;
        for (int index = 0; index < slots.size(); index++) {
            WidgetCard card = slots.get(index);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
            int left = slotLefts.get(index) - inset - shelf.getLeft();
            int width = slotWidths.get(index) + 2 * inset;
            if (lp.leftMargin != left || lp.width != width) {
                lp.leftMargin = left;
                lp.width = width;
                card.setLayoutParams(lp);
                moved = true;
            }
        }
        // A focused card that moved (the dock re-centred under it) fires no focus change: follow it once laid out.
        if (moved) shelf.post(() -> {
            for (WidgetCard card : slots) highlight.follow(card);
        });
        // Halfway between the header, which floats over the top of the grid, and the dock.
        headerUnderlap = grid instanceof HomeGrid ? ((HomeGrid) grid).underlap() : 0;
        ViewGroup.MarginLayoutParams slp = (ViewGroup.MarginLayoutParams) shelf.getLayoutParams();
        int top = headerUnderlap + Math.max(0, (dockTop - headerUnderlap - slp.height) / 2);
        if (slp.topMargin != top) {
            slp.topMargin = top;
            shelf.setLayoutParams(slp);
        }
    }

    void onGridScrolled(RecyclerView grid) {
        if (!enabled) return;

        int offset = grid.computeVerticalScrollOffset();
        shelf.setTranslationY(-offset);

        // Whole until it reaches the header, then fading out as it slides under the header's scrim.
        ViewGroup.MarginLayoutParams layout = (ViewGroup.MarginLayoutParams) shelf.getLayoutParams();
        float underHeader = offset - (layout.topMargin - headerUnderlap);
        float visibility = Math.max(0f, Math.min(1f, 1f - underHeader / Math.max(1f, shelf.getHeight())));
        shelf.setAlpha(visibility);
        shelf.setVisibility(visibility == 0f ? View.INVISIBLE : View.VISIBLE);
    }

    // ---- colour ----------------------------------------------------------------------------------

    void setPalette(Palette p) {
        palette = p;
        for (WidgetCard c : allCards()) c.setPalette(p);
        highlight.setColor(editing ? p.tertiary : p.primary);
        for (TextView t : new TextView[]{weatherPlace, airLabel, lunarLabel, chipRainLabel, chipHumidityLabel,
                chipAqiLabel, chipUvLabel, pricesTitle, pricesGoldLabel, pricesFuelLabel, pricesBuyLabel,
                pricesSellLabel, pricesRegionLabel, pricesUpdated}) t.setTextColor(p.onSurfaceVariant);
        for (TextView t : dayNames) t.setTextColor(p.onSurfaceVariant);
        for (TextView t : new TextView[]{weatherTemp, weatherCond, lunarDate, lunarMonth, airValue, cpuName,
                ramName, storageName, weatherRainValue, weatherHumidityValue, weatherAqiValue, weatherUvValue})
            t.setTextColor(p.onSurface);
        for (TextView t : dayTemps) t.setTextColor(p.onSurface);
        for (TextView t : eventNames) t.setTextColor(p.onSurface);
        for (TextView t : pricesNames) t.setTextColor(p.onSurface);
        for (TextView t : new TextView[]{weatherDetail, airDetail, cpuValue, ramValue, storageValue,
                pricesBarBuy, pricesBarSell, pricesRingBuy, pricesRingSell, pricesRon95, pricesE5,
                pricesDiesel, pricesUsd}) t.setTextColor(p.onSurfaceVariant);
        for (TextView t : eventWhens) t.setTextColor(p.onSurfaceVariant);
        for (ImageView icon : dayIcons) icon.setColorFilter(p.onSurfaceVariant);
        cpuIcon.setImageTintList(ColorStateList.valueOf(p.primary));
        cpuBar.setPalette(p);
        ramIcon.setImageTintList(ColorStateList.valueOf(p.primary));
        storageIcon.setImageTintList(ColorStateList.valueOf(p.primary));
        ramBar.setPalette(p);
        storageBar.setPalette(p);
        weatherIcon.setPalette(p);
        paintAir();
    }

    // ---- weather ---------------------------------------------------------------------------------

    private void refreshWeather(boolean force) {
        if (report == null) report = Weather.cached(activity);
        paintWeather();
        long now = System.currentTimeMillis();
        if (fetching) return;
        if (!force && report != null && !report.stale(now)) return;
        if (!force && now - lastFailure < WEATHER_RETRY_MS) return;
        fetching = true;
        Context app = activity.getApplicationContext();
        new Thread(() -> {
            Weather.Report fresh;
            try {
                fresh = Weather.fetch(app, city());
            } catch (Exception e) {
                fresh = null;
            }
            Weather.Report result = fresh;
            main.post(() -> {
                fetching = false;
                if (result != null) report = result;
                else lastFailure = System.currentTimeMillis();
                paintWeather();
            });
        }, "weather").start();
    }

    private void paintWeather() {
        if (report == null) {
            weatherPlace.setText(R.string.weather);
            weatherTemp.setText("–");
            weatherCond.setText("");
            weatherDetail.setText(lastFailure > 0 ? activity.getString(R.string.weather_offline) : "");
            weatherIcon.setIcon(activity.getDrawable(R.drawable.ic_w_cloud));
            paintWeatherDetails();
            return;
        }
        String place = report.place.isEmpty() ? activity.getString(R.string.weather) : report.place;
        Weather.Sky sky = Weather.sky(report.code);
        weatherPlace.setText(place);
        weatherTemp.setText(degrees(report.temp) + "°");
        weatherCond.setText(Weather.label(sky));
        weatherDetail.setText(activity.getString(R.string.weather_high_low,
                degrees(report.high), degrees(report.low)));
        weatherIcon.setIcon(activity.getDrawable(Weather.icon(sky, report.day)));
        paintWeatherDetails();
    }

    /** The chips under the temperature (rain, humidity, the air, the UV) and the next five days. */
    private void paintWeatherDetails() {
        if (report == null) {
            weatherRainValue.setText("–");
            weatherHumidityValue.setText("–");
            weatherAqiValue.setText("–");
            weatherUvValue.setText("–");
            for (int i = 0; i < dayNames.length; i++) {
                dayNames[i].setText("");
                dayIcons[i].setImageDrawable(null);
                dayTemps[i].setText("");
            }
            return;
        }
        weatherRainValue.setText(report.todayRain + "%");
        weatherHumidityValue.setText(report.humidity + "%");
        weatherAqiValue.setText(air == null ? "–" : String.valueOf(air.aqi));
        weatherUvValue.setText(Math.round(report.uv) + " · "
                + activity.getString(Weather.uvLabel(report.uv)));
        LocalDate today = LocalDate.now();
        for (int i = 0; i < dayNames.length; i++) {
            if (i >= report.days.length) {
                dayNames[i].setText("");
                dayIcons[i].setImageDrawable(null);
                dayTemps[i].setText("");
                continue;
            }
            Weather.Day day = report.days[i];
            dayNames[i].setText(dayName(today.plusDays(i + 1)));
            dayIcons[i].setImageResource(Weather.icon(Weather.sky(day.code), true));
            dayTemps[i].setText(degrees(day.high) + "°");
        }
    }

    /** A day's short name: T2 to T7 and CN in Vietnamese, the locale's short name in any other. */
    private static String dayName(LocalDate date) {
        if ("vi".equals(Locale.getDefault().getLanguage())) {
            switch (date.getDayOfWeek()) {
                case MONDAY: return "T2";
                case TUESDAY: return "T3";
                case WEDNESDAY: return "T4";
                case THURSDAY: return "T5";
                case FRIDAY: return "T6";
                case SATURDAY: return "T7";
                default: return "CN";
            }
        }
        return date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.getDefault());
    }

    /** A Celsius reading in the unit the owner chose, rounded. */
    private int degrees(double celsius) {
        return (int) Math.round(fahrenheit() ? celsius * 9 / 5 + 32 : celsius);
    }

    // ---- this TV ---------------------------------------------------------------------------------

    /**
     * The system card's page in its three meter rows. A page of fewer lines leaves the top row out (the CPU
     * one, as when it is hidden); a line without a share drops its bar, so the gaps between rows stay even
     * and a page of names and values sits in the middle of the card rather than high in it.
     */
    private void paintSystem() {
        List<SystemPages.Line> lines = systemPages.lines(systemPage, lastCpu, showCpu());
        int offset = rowIcons.length - lines.size();
        int top = offset > 0 ? View.GONE : View.VISIBLE;
        for (int id : new int[]{R.id.cpuRow, R.id.cpuBar, R.id.cpuGap}) shelf.findViewById(id).setVisibility(top);
        for (int i = 0; i < lines.size(); i++) {
            SystemPages.Line line = lines.get(i);
            int row = i + offset;
            rowIcons[row].setImageResource(line.icon);
            rowNames[row].setText(line.name);
            rowValues[row].setText(line.value);
            boolean bar = line.fill != SystemPages.Line.NO_BAR;
            rowBars[row].setVisibility(bar ? View.VISIBLE : View.GONE);
            if (bar) rowBars[row].setProgress(line.fill);
        }
    }

    // ---- air quality -----------------------------------------------------------------------------

    private void refreshAir(boolean force) {
        if (air == null) air = AirQuality.cached(activity);
        paintAir();
        long now = System.currentTimeMillis();
        if (airFetching) return;
        if (!force && air != null && !air.stale(now)) return;
        if (!force && now - airFailure < WEATHER_RETRY_MS) return;
        airFetching = true;
        Context app = activity.getApplicationContext();
        new Thread(() -> {
            AirQuality.Report fresh;
            try {
                fresh = AirQuality.fetch(app, city());
            } catch (Exception e) {
                fresh = null;
            }
            AirQuality.Report result = fresh;
            main.post(() -> {
                airFetching = false;
                if (result != null) air = result;
                else airFailure = System.currentTimeMillis();
                paintAir();
                paintWeather();
            });
        }, "air").start();
    }

    /**
     * Now, or tomorrow's worst hour. The badge takes the band's colour harmonised towards the scheme's
     * primary and set to the scheme's tone (light in dark mode, deep in light mode), so it reads as
     * the band at a glance and still belongs to the palette.
     */
    private void paintAir() {
        boolean tomorrow = airTomorrow && air != null && air.tomorrowMax >= 0;
        String title = activity.getString(R.string.air_quality);
        airLabel.setText(tomorrow ? activity.getString(R.string.air_tomorrow, title) : title);
        if (air == null) {
            airValue.setText("–");
            airDetail.setText(airFailure > 0 ? activity.getString(R.string.air_offline) : "");
            airBadge.setColors(palette.surfaceContainerHighest, palette.onSurfaceVariant);
            return;
        }
        int aqi = tomorrow ? air.tomorrowMax : air.aqi;
        AirQuality.Level level = AirQuality.level(aqi);
        String band = activity.getString(AirQuality.label(level));
        airValue.setText(String.valueOf(aqi));
        airDetail.setText(tomorrow ? activity.getString(R.string.air_detail_tomorrow, band)
                : activity.getString(R.string.air_detail, band, air.pm25));
        Hct hct = Hct.fromInt(Blend.harmonize(AirQuality.color(level), palette.primary));
        boolean dark = palette.dark;
        int fill = Hct.from(hct.getHue(), Math.max(hct.getChroma(), 36), dark ? 80 : 40).toInt();
        int on = Hct.from(hct.getHue(), Math.min(hct.getChroma(), 16), dark ? 20 : 100).toInt();
        airBadge.setColors(fill, on);
    }

    // ---- lunar calendar --------------------------------------------------------------------------

    /** Paints from the cached feed at once, and refetches it in the background when it is stale. */
    private void refreshHolidays(boolean force) {
        if (feedEvents == null) feedEvents = HolidayFeed.cached(activity);
        paintCalendar();
        if (feedFetching || !force && !HolidayFeed.stale(activity, System.currentTimeMillis())) return;
        feedFetching = true;
        Context app = activity.getApplicationContext();
        new Thread(() -> {
            List<HolidayFeed.Event> fresh = HolidayFeed.fetch(app);
            main.post(() -> {
                feedFetching = false;
                if (!fresh.isEmpty()) feedEvents = fresh;
                paintCalendar();
            });
        }, "holidays").start();
    }

    /** A day the calendar card counts down to: its name as published and how far away it is. */
    private static final class ShownEvent {
        final String name;
        final LocalDate date;
        final long days;

        ShownEvent(String name, LocalDate date, LocalDate today) {
            this.name = name;
            this.date = date;
            this.days = ChronoUnit.DAYS.between(today, date);
        }
    }

    /**
     * The next events, soonest first: the feed's holidays (real published dates, Tet and Hung Kings
     * among them), the fixed Vietnamese observances no feed carries (Teachers' Day, Women's Day), and
     * the next full moon, which the lunar calendar itself defines as its 15th day. Until the first
     * feed lands, the built-in rules in {@link LunarCalendar} stand in, so the card is never empty.
     */
    private List<ShownEvent> calendarEvents(LocalDate today) {
        List<ShownEvent> events = new ArrayList<>();
        if (feedEvents != null) {
            for (HolidayFeed.Event e : feedEvents) {
                if (!e.date.isBefore(today)) events.add(new ShownEvent(e.name, e.date, today));
            }
        }
        if (events.isEmpty()) {
            for (LunarCalendar.Upcoming u : LunarCalendar.upcoming(today)) {
                events.add(new ShownEvent(activity.getString(holidayName(u.holiday)), u.date, today));
            }
        } else {
            addObservance(events, today, 20, 11, R.string.holiday_teachers);
            addObservance(events, today, 20, 10, R.string.holiday_womens_day);
        }
        LunarCalendar.Upcoming moon = LunarCalendar.nextFullMoon(today);
        if (moon != null) events.add(new ShownEvent(
                activity.getString(R.string.holiday_full_moon, moon.fullMoonMonth), moon.date, today));
        events.sort((a, b) -> a.date.compareTo(b.date));
        if (tetOnly()) {
            events.removeIf(e -> !isTet(e.name));
            if (events.isEmpty()) {
                LocalDate tet = LunarCalendar.Holiday.TET.onOrAfter(today);
                if (tet != null) events.add(new ShownEvent(
                        activity.getString(holidayName(LunarCalendar.Holiday.TET)), tet, today));
            }
        }
        return events.size() > eventRows.length ? events.subList(0, eventRows.length) : events;
    }

    /** A fixed-date observance's next occurrence: this year's, or next year's once it has passed. */
    private void addObservance(List<ShownEvent> events, LocalDate today, int day, int month, int name) {
        LocalDate date = LocalDate.of(today.getYear(), month, day);
        if (date.isBefore(today)) date = date.plusYears(1);
        events.add(new ShownEvent(activity.getString(name), date, today));
    }

    /** Tet in the feed's names: "Tet" itself or a "Tet Nguyen Dan" day, not the eve or a day off. */
    private static boolean isTet(String name) {
        return name.equals("Tết") || name.contains("Nguyên Đán");
    }

    private void paintCalendar() {
        LocalDate today = LocalDate.now();
        LunarCalendar.Date l = LunarCalendar.toLunar(today);
        lunarLabel.setText(activity.getString(R.string.lunar_year, LunarCalendar.canChi(l.year)));
        lunarDate.setText(String.valueOf(l.day));
        String month = activity.getString(R.string.lunar_month, l.month);
        lunarMonth.setText(l.leap ? activity.getString(R.string.lunar_leap, month) : month);
        List<ShownEvent> events = calendarEvents(today);
        DateTimeFormatter when = DateTimeFormatter.ofPattern("EEEE, d/M", Locale.getDefault());
        for (int i = 0; i < eventRows.length; i++) {
            if (i >= events.size()) {
                eventRows[i].setVisibility(View.GONE);
                continue;
            }
            eventRows[i].setVisibility(View.VISIBLE);
            ShownEvent u = events.get(i);
            eventNames[i].setText(u.name);
            String day = when.format(u.date);
            eventWhens[i].setText(day.substring(0, 1).toUpperCase(Locale.getDefault()) + day.substring(1));
            eventBadges[i].setText(u.days == 0 ? activity.getString(R.string.badge_today)
                    : u.days == 1 ? activity.getString(R.string.badge_tomorrow)
                    : activity.getString(R.string.badge_in_days, (int) u.days));
            boolean near = u.days <= 1;
            eventBadges[i].setBackgroundTintList(ColorStateList.valueOf(
                    near ? palette.primaryContainer : palette.surfaceContainerHighest));
            eventBadges[i].setTextColor(near ? palette.onPrimaryContainer : palette.onSurfaceVariant);
        }
    }

    private static int holidayName(LunarCalendar.Holiday h) {
        switch (h) {
            case NEW_YEAR: return R.string.holiday_new_year;
            case TET: return R.string.holiday_tet;
            case HUNG_KINGS: return R.string.holiday_hung_kings;
            case REUNIFICATION: return R.string.holiday_reunification;
            case LABOUR: return R.string.holiday_labour;
            case MID_AUTUMN: return R.string.holiday_mid_autumn;
            case NATIONAL_DAY: return R.string.holiday_national_day;
            case TEACHERS: return R.string.holiday_teachers;
            default: return R.string.holiday_christmas;
        }
    }

    // ---- prices ----------------------------------------------------------------------------------

    private void refreshPrices(boolean force) {
        if (prices == null) prices = Prices.cached(activity);
        paintPrices();
        long now = System.currentTimeMillis();
        if (pricesFetching) return;
        if (!force && prices != null && !prices.stale(now)) return;
        if (!force && now - pricesFailure < WEATHER_RETRY_MS) return;
        pricesFetching = true;
        Context app = activity.getApplicationContext();
        new Thread(() -> {
            Prices.Report fresh = Prices.fetch(app);
            main.post(() -> {
                pricesFetching = false;
                prices = fresh;
                if (fresh.empty()) pricesFailure = System.currentTimeMillis();
                paintPrices();
            });
        }, "prices").start();
    }

    private void paintPrices() {
        if (prices == null || prices.empty()) {
            pricesUpdated.setText(pricesFailure > 0 ? activity.getString(R.string.prices_offline) : "");
            pricesBarBuy.setText("–");
            pricesBarSell.setText("–");
            pricesRingBuy.setText("–");
            pricesRingSell.setText("–");
            pricesRon95.setText("–");
            pricesE5.setText("–");
            pricesDiesel.setText("–");
            pricesUsd.setText("–");
            return;
        }
        String time = DateTimeFormatter.ofPattern("HH:mm").format(LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(prices.fetchedAt), ZoneId.systemDefault()));
        pricesUpdated.setText(activity.getString(R.string.prices_updated, time));
        pricesBarBuy.setText(millions(prices.barBuy));
        pricesBarSell.setText(millions(prices.barSell));
        pricesRingBuy.setText(millions(prices.ringBuy));
        pricesRingSell.setText(millions(prices.ringSell));
        pricesRon95.setText(grouped(prices.ron95));
        pricesE5.setText(grouped(prices.e5));
        pricesDiesel.setText(grouped(prices.diesel));
        pricesUsd.setText(grouped(prices.usd));
    }

    /** Gold in millions of VND with one decimal, in the locale's decimal separator (139,0). */
    private static String millions(long vnd) {
        if (vnd == 0) return "–";
        return String.format(Locale.getDefault(), "%.1f", vnd / 1_000_000.0);
    }

    /** A VND figure grouped by thousands with dots (24.910), as prices are written in Vietnam. */
    private static String grouped(int vnd) {
        if (vnd < 0) return "–";
        String digits = String.valueOf(vnd);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) out.append('.');
            out.append(digits.charAt(i));
        }
        return out.toString();
    }
}
