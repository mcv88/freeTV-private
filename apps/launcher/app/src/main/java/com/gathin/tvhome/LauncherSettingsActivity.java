package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.EditText;
import android.view.inputmethod.EditorInfo;
import android.view.WindowManager;
import android.view.Window;
import android.view.Gravity;
import android.view.KeyEvent;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.TypefaceSpan;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;
import java.util.ArrayList;
import java.util.List;

/**
 * Remote-friendly settings owned by the launcher, as a side sheet over Home: collapsible sections
 * (Header, Widgets, Appearance, System, About), each row with its icon. The update row stays
 * outside any section, one tap away.
 */
public final class LauncherSettingsActivity extends Activity {
    private SharedPreferences choices;
    private static final int[] COLUMN_OPTIONS = {4, 5, 6, 7};
    private static final String TURN_OFF_COMMAND = "input keyevent KEYCODE_SLEEP";
    private static final String RESTART_COMMAND = "reboot";
    private TextView columnsChoice;
    private TextView animationsChoice;
    private TextView advancedChoice;
    private M3Switch animationsSwitch;
    private M3Switch advancedSwitch;
    private M3Switch widgetsSwitch;
    private TextView widgetsChoice;
    private TextView headlineStripChoice;
    private M3Switch headlineStripSwitch;
    private TextView tickerLengthChoice;
    private TextView sourcesChoice;
    private TextView headerSectionChoice;
    private TextView lookSectionChoice;
    private TextView systemSectionChoice;
    private TextView aboutSectionChoice;
    private TextView rebootChoice;
    private TextView resetChoice;
    private TextView greetingChoice;
    private M3Switch greetingSwitch;
    private TextView greetingTextChoice;
    private TextView headerBoldChoice;
    private M3Switch headerBoldSwitch;

    private TextView widgetSectionChoice;
    private TextView widgetLayoutChoice;
    private TextView widgetTapChoice;
    private TextView popupBlurChoice;
    private final TextView[] widgetHeightRows = new TextView[WIDGET_IDS.length];
    private final TextView[] widgetWidthRows = new TextView[WIDGET_IDS.length];
    private TextView tvSettingsChoice;
    private TextView bootAppChoice;
    private TextView colorChoice;
    private TextView backdropChoice;
    private TextView screensaverTimeChoice;
    private TextView screensaverStyleChoice;
    /** A theme's wallpaper is showing: it sets the scheme and covers the backdrop, so both rows rest. */
    private boolean wallpaper;
    private static final int[] BACKDROP_NAMES = {
            R.string.look_backdrop_shapes, R.string.look_backdrop_sky, R.string.look_backdrop_plain};
    private static final int[] SCREENSAVER_STYLE_NAMES = {R.string.screensaver_style_shapes,
            R.string.screensaver_style_glow, R.string.screensaver_style_minimal, R.string.screensaver_style_large};
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SideSheet sideSheet;
    private Palette palette;
    /** Google TV's own home screen, which tvctl turns off when this launcher takes over. */
    private static final String STOCK_LAUNCHER = "com.google.android.apps.tv.launcherx";
    private int columns;
    private boolean animations;
    private boolean advancedEffects;
    private TextView updateChoice;
    private LauncherRelease offeredRelease;
    private boolean updateBusy;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        overridePendingTransition(0, 0);
        Palette.loadDarkMode(this);
        palette = Palette.currentOrBaseline();
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        columns = config.columns;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;

        columnsChoice = findViewById(R.id.columnsChoice);
        columnsChoice.setCompoundDrawablesRelativeWithIntrinsicBounds(icon(R.drawable.ic_grid_view), null, null, null);
        columnsChoice.setOnClickListener(v -> chooseColumns(config));
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            setResult(RESULT_OK);
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        // A plain switch: the row's supporting text says what it does, no dialog in the way.
        advancedChoice.setOnClickListener(v -> setAdvanced(!advancedEffects));
        float density = getResources().getDisplayMetrics().density;
        animationsSwitch = new M3Switch(density, palette, config.animations);
        advancedSwitch = new M3Switch(density, palette, config.animations);
        animationsChoice.setCompoundDrawablesRelativeWithIntrinsicBounds(icon(R.drawable.ic_animation), null, animationsSwitch, null);
        advancedChoice.setCompoundDrawablesRelativeWithIntrinsicBounds(icon(R.drawable.ic_auto_awesome), null, advancedSwitch, null);
        tvSettingsChoice = findViewById(R.id.tvSettingsChoice);
        tvSettingsChoice.setText(getString(R.string.tv_settings));
        tvSettingsChoice.setCompoundDrawablesRelativeWithIntrinsicBounds(icon(R.drawable.ic_settings), null,
                icon(R.drawable.ic_open_in_new), null);
        rebootChoice = findViewById(R.id.rebootChoice);
        rebootChoice.setText(item(getString(R.string.reboot), getString(R.string.reboot_desc)));
        rebootChoice.setCompoundDrawablesRelativeWithIntrinsicBounds(icon(R.drawable.ic_power), null, null, null);
        rebootChoice.setOnClickListener(v -> confirmPower(config));
        resetChoice = findViewById(R.id.resetChoice);
        resetChoice.setText(item(getString(R.string.reset_settings),
                getString(R.string.reset_settings_desc)));
        resetChoice.setCompoundDrawablesRelativeWithIntrinsicBounds(
                icon(R.drawable.ic_refresh), null, null, null);
        resetChoice.setOnClickListener(v -> confirmResetSettings(config));

        widgetsChoice = findViewById(R.id.widgetsChoice);
        widgetsChoice.setText(item(getString(R.string.widgets), getString(R.string.widgets_desc)));
        boolean widgetsDefault = getResources().getBoolean(R.bool.widgets);
        widgetsSwitch = new M3Switch(density, palette, config.animations);
        widgetsSwitch.setChecked(choices.getBoolean(WidgetShelf.PREF_KEY, widgetsDefault));
        widgetsChoice.setCompoundDrawablesRelativeWithIntrinsicBounds(icon(R.drawable.ic_widgets), null, widgetsSwitch, null);
        widgetsChoice.setOnClickListener(v -> {
            // Home reads it when it shows again.
            boolean on = !choices.getBoolean(WidgetShelf.PREF_KEY, widgetsDefault);
            choices.edit().putBoolean(WidgetShelf.PREF_KEY, on).apply();
            widgetsSwitch.setChecked(on);
            refreshWidgetRows();
        });
        headlineStripChoice = findViewById(R.id.headlineStripChoice);
        headlineStripChoice.setText(item(getString(R.string.headline_strip),
                getString(R.string.headline_strip_desc)));
        headlineStripSwitch = new M3Switch(density, palette, config.animations);
        headlineStripSwitch.setChecked(
                choices.getBoolean(MainActivity.HEADLINE_STRIP_KEY, true));
        headlineStripChoice.setCompoundDrawablesRelativeWithIntrinsicBounds(
                icon(R.drawable.ic_info), null, headlineStripSwitch, null);
        headlineStripChoice.setOnClickListener(v -> {
            boolean on = !choices.getBoolean(MainActivity.HEADLINE_STRIP_KEY, true);
            choices.edit().putBoolean(MainActivity.HEADLINE_STRIP_KEY, on).apply();
            headlineStripSwitch.setChecked(on);
        });
        tickerLengthChoice = findViewById(R.id.tickerLengthChoice);
        tickerLengthChoice.setCompoundDrawablesRelativeWithIntrinsicBounds(
                icon(R.drawable.ic_info), null, null, null);
        tickerLengthChoice.setOnClickListener(v -> chooseTickerLength(config));
        refreshTickerLengthRow();
        sourcesChoice = findViewById(R.id.sourcesChoice);
        sourcesChoice.setCompoundDrawablesRelativeWithIntrinsicBounds(
                icon(R.drawable.ic_info), null, null, null);
        sourcesChoice.setOnClickListener(v -> chooseSources(config));
        refreshSourcesRow();
        greetingChoice = findViewById(R.id.greetingChoice);
        greetingChoice.setText(item(getString(R.string.greeting),
                getString(R.string.greeting_desc)));
        greetingSwitch = new M3Switch(density, palette, config.animations);
        greetingSwitch.setChecked(choices.getBoolean(MainActivity.GREETING_KEY, false));
        greetingChoice.setCompoundDrawablesRelativeWithIntrinsicBounds(
                icon(R.drawable.ic_person), null, greetingSwitch, null);
        greetingChoice.setOnClickListener(v -> {
            boolean on = !choices.getBoolean(MainActivity.GREETING_KEY, false);
            choices.edit().putBoolean(MainActivity.GREETING_KEY, on).apply();
            greetingSwitch.setChecked(on);
            refreshGreetingRows();
        });
        greetingTextChoice = findViewById(R.id.greetingTextChoice);
        greetingTextChoice.setCompoundDrawablesRelativeWithIntrinsicBounds(
                icon(R.drawable.ic_code), null, null, null);
        greetingTextChoice.setOnClickListener(v -> chooseGreetingText());
        refreshGreetingRows();
        headerBoldChoice = findViewById(R.id.headerBoldChoice);
        headerBoldChoice.setText(item(getString(R.string.header_bold),
                getString(R.string.header_bold_desc)));
        headerBoldSwitch = new M3Switch(density, palette, config.animations);
        headerBoldSwitch.setChecked(
                choices.getBoolean(MainActivity.HEADER_BOLD_KEY, false));
        headerBoldChoice.setCompoundDrawablesRelativeWithIntrinsicBounds(
                icon(R.drawable.ic_contrast), null, headerBoldSwitch, null);
        headerBoldChoice.setOnClickListener(v -> {
            boolean on = !choices.getBoolean(MainActivity.HEADER_BOLD_KEY, false);
            choices.edit().putBoolean(MainActivity.HEADER_BOLD_KEY, on).apply();
            headerBoldSwitch.setChecked(on);
        });
        showHeaderSection();
        showWidgetToggles(density);
        showWidgetLayout(config);
        showWidgetHeights(config);
        showWidgetWidths(config);
        showWidgetSection();
        tvSettingsChoice.setOnClickListener(v -> open(TvSettings.main(this)));
        showMenuInputs(config, density);
        showLook(config);
        showScreensaver(config);
        showLookSection();
        showHomeStatus();
        showBootApp(config);
        showSystemSection();
        showUpdate();
        showAbout();
        showAboutSection();
        refresh();
        View sheet = findViewById(R.id.sheet);
        GradientDrawable sheetBg = new GradientDrawable();
        sheetBg.setColor(palette.surface);
        sheetBg.setCornerRadius(Shapes.EXTRA_LARGE * density);
        sheet.setBackground(sheetBg);
        sheet.setClipToOutline(true);
        M3Skin.apply(sheet, palette, config.animations, config.reducedMotion);
        View scrim = findViewById(R.id.scrim);
        sideSheet = new SideSheet(this, scrim, sheet, config);
        scrim.setOnClickListener(v -> sideSheet.close());
        FocusScrollView settingsScroll = findViewById(R.id.settingsScroll);
        settingsScroll.setMotion(config.animations, config.reducedMotion);
        sideSheet.slideIn((ViewGroup) settingsScroll.getChildAt(0));
        columnsChoice.requestFocus();
    }

    /** The widgets the owner can show or hide one by one, in shelf order,
     *  each with its settings rows. */
    private static final String[] WIDGET_IDS = {WidgetShelf.WEATHER, WidgetShelf.SYSTEM,
            WidgetShelf.AIR, WidgetShelf.CALENDAR, WidgetShelf.PRICES};
    private static final int[] WIDGET_ROWS = {R.id.widgetToggleWeather, R.id.widgetToggleSystem,
            R.id.widgetToggleAir, R.id.widgetToggleCalendar, R.id.widgetTogglePrices};
    private static final int[] WIDGET_NAMES = {R.string.weather, R.string.widget_system,
            R.string.air_quality, R.string.lunar_calendar, R.string.prices};
    private static final int[] WIDGET_ICONS = {R.drawable.ic_w_partly_day, R.drawable.ic_memory,
            R.drawable.ic_air, R.drawable.ic_calendar, R.drawable.ic_speed};
    private final TextView[] widgetRows = new TextView[WIDGET_IDS.length];
    private final M3Switch[] widgetSwitches = new M3Switch[WIDGET_IDS.length];

    private static final String WIDGET_SECTION_KEY = "widget_section";

    private void showWidgetSection() {
        widgetSectionChoice = findViewById(R.id.widgetSectionChoice);
        widgetSectionChoice.setText(item(getString(R.string.widgets),
                getString(R.string.widget_section_desc)));
        widgetSectionChoice.setOnClickListener(v -> {
            boolean expanded = !choices.getBoolean(WIDGET_SECTION_KEY, false);
            choices.edit().putBoolean(WIDGET_SECTION_KEY, expanded).apply();
            refreshWidgetSection();
        });
        refreshWidgetSection();
    }

    private void refreshWidgetSection() {
        boolean expanded = choices.getBoolean(WIDGET_SECTION_KEY, false);
        widgetSectionChoice.setCompoundDrawablesRelativeWithIntrinsicBounds(
                icon(R.drawable.ic_widgets), null,
                icon(expanded ? R.drawable.ic_remove : R.drawable.ic_add), null);
        int visibility = expanded ? View.VISIBLE : View.GONE;
        widgetsChoice.setVisibility(visibility);
        widgetLayoutChoice.setVisibility(visibility);
        widgetTapChoice.setVisibility(visibility);
        popupBlurChoice.setVisibility(visibility);
        for (int i = 0; i < WIDGET_IDS.length; i++) {
            widgetRows[i].setVisibility(visibility);
            widgetHeightRows[i].setVisibility(visibility);
            widgetWidthRows[i].setVisibility(visibility);
        }
    }

    private static final String HEADER_SECTION_KEY = "header_section";
    private static final String LOOK_SECTION_KEY = "look_section";
    private static final String SYSTEM_SECTION_KEY = "system_section";
    private static final String ABOUT_SECTION_KEY = "about_section";

    private static final String[] TICKER_LENGTH_VALUES = {MainActivity.TICKER_LEN_SHORT,
            MainActivity.TICKER_LEN_WIFI, MainActivity.TICKER_LEN_FULL,
            MainActivity.TICKER_LEN_CUSTOM};
    private static final int[] TICKER_LENGTH_LABELS = {R.string.ticker_len_short,
            R.string.ticker_len_wifi, R.string.ticker_len_full, R.string.ticker_len_custom};

    private void chooseTickerLength(Config config) {
        CharSequence[] labels = new CharSequence[TICKER_LENGTH_LABELS.length];
        for (int i = 0; i < labels.length; i++) labels[i] = getString(TICKER_LENGTH_LABELS[i]);
        chooseOne(R.string.ticker_length, labels, null, tickerLengthIndex(), index -> {
            if (MainActivity.TICKER_LEN_CUSTOM.equals(TICKER_LENGTH_VALUES[index])) {
                chooseTickerWidthPx();
                return;
            }
            choices.edit().putString(MainActivity.TICKER_LENGTH_KEY,
                    TICKER_LENGTH_VALUES[index]).apply();
            refreshTickerLengthRow();
        }, config);
    }

    private void chooseTickerWidthPx() {
        EditText edit = new EditText(this);
        edit.setInputType(InputType.TYPE_CLASS_NUMBER);
        int current = choices.getInt(MainActivity.TICKER_WIDTH_PX_KEY, 0);
        if (current > 0) edit.setText(String.valueOf(current));
        new AlertDialog.Builder(this)
                .setTitle(R.string.ticker_len_custom)
                .setView(edit)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    String text = edit.getText().toString().trim();
                    if (text.isEmpty()) return;
                    int width;
                    try {
                        width = Integer.parseInt(text);
                    } catch (NumberFormatException e) {
                        return;
                    }
                    int max = getResources().getDisplayMetrics().widthPixels;
                    width = Math.max(0, Math.min(width, max));
                    choices.edit()
                            .putString(MainActivity.TICKER_LENGTH_KEY,
                                    MainActivity.TICKER_LEN_CUSTOM)
                            .putInt(MainActivity.TICKER_WIDTH_PX_KEY, width)
                            .apply();
                    refreshTickerLengthRow();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private int tickerLengthIndex() {
        String length = choices.getString(MainActivity.TICKER_LENGTH_KEY,
                MainActivity.TICKER_LEN_WIFI);
        for (int i = 0; i < TICKER_LENGTH_VALUES.length; i++) {
            if (TICKER_LENGTH_VALUES[i].equals(length)) return i;
        }
        return 1;
    }

    private void refreshTickerLengthRow() {
        int index = tickerLengthIndex();
        String desc;
        if (MainActivity.TICKER_LEN_CUSTOM.equals(TICKER_LENGTH_VALUES[index])) {
            int width = choices.getInt(MainActivity.TICKER_WIDTH_PX_KEY, 0);
            desc = getString(R.string.ticker_len_custom_value, width);
        } else {
            desc = getString(TICKER_LENGTH_LABELS[index]);
        }
        tickerLengthChoice.setText(item(getString(R.string.ticker_length), desc));
    }

    private void showHeaderSection() {
        headerSectionChoice = findViewById(R.id.headerSectionChoice);
        headerSectionChoice.setText(item(getString(R.string.header_section),
                getString(R.string.header_section_desc)));
        headerSectionChoice.setOnClickListener(v -> {
            boolean expanded = !choices.getBoolean(HEADER_SECTION_KEY, false);
            choices.edit().putBoolean(HEADER_SECTION_KEY, expanded).apply();
            refreshHeaderSection();
        });
        refreshHeaderSection();
    }

    private void refreshHeaderSection() {
        boolean expanded = choices.getBoolean(HEADER_SECTION_KEY, false);
        headerSectionChoice.setCompoundDrawablesRelativeWithIntrinsicBounds(
                icon(R.drawable.ic_info), null,
                icon(expanded ? R.drawable.ic_remove : R.drawable.ic_add), null);
        int visibility = expanded ? View.VISIBLE : View.GONE;
        headlineStripChoice.setVisibility(visibility);
        tickerLengthChoice.setVisibility(visibility);
        sourcesChoice.setVisibility(visibility);
        greetingChoice.setVisibility(visibility);
        headerBoldChoice.setVisibility(visibility);
    }

    private void showLookSection() {
        lookSectionChoice = findViewById(R.id.lookSectionChoice);
        lookSectionChoice.setText(item(getString(R.string.look_section),
                getString(R.string.look_section_desc)));
        lookSectionChoice.setOnClickListener(v -> {
            boolean expanded = !choices.getBoolean(LOOK_SECTION_KEY, false);
            choices.edit().putBoolean(LOOK_SECTION_KEY, expanded).apply();
            refreshLookSection();
        });
        refreshLookSection();
    }

    private void refreshLookSection() {
        boolean expanded = choices.getBoolean(LOOK_SECTION_KEY, false);
        lookSectionChoice.setCompoundDrawablesRelativeWithIntrinsicBounds(
                icon(R.drawable.ic_contrast), null,
                icon(expanded ? R.drawable.ic_remove : R.drawable.ic_add), null);
        int visibility = expanded ? View.VISIBLE : View.GONE;
        colorChoice.setVisibility(visibility);
        backdropChoice.setVisibility(visibility);
        screensaverTimeChoice.setVisibility(visibility);
        screensaverStyleChoice.setVisibility(visibility);
        animationsChoice.setVisibility(visibility);
        advancedChoice.setVisibility(visibility);
    }

    private void showSystemSection() {
        systemSectionChoice = findViewById(R.id.systemSectionChoice);
        systemSectionChoice.setText(item(getString(R.string.system_section),
                getString(R.string.system_section_desc)));
        systemSectionChoice.setOnClickListener(v -> {
            boolean expanded = !choices.getBoolean(SYSTEM_SECTION_KEY, false);
            choices.edit().putBoolean(SYSTEM_SECTION_KEY, expanded).apply();
            refreshSystemSection();
        });
        refreshSystemSection();
    }

    private void refreshSystemSection() {
        boolean expanded = choices.getBoolean(SYSTEM_SECTION_KEY, false);
        systemSectionChoice.setCompoundDrawablesRelativeWithIntrinsicBounds(
                icon(R.drawable.ic_settings), null,
                icon(expanded ? R.drawable.ic_remove : R.drawable.ic_add), null);
        int visibility = expanded ? View.VISIBLE : View.GONE;
        tvSettingsChoice.setVisibility(visibility);
        bootAppChoice.setVisibility(visibility);
        rebootChoice.setVisibility(visibility);
        resetChoice.setVisibility(visibility);
    }

    private void showAboutSection() {
        aboutSectionChoice = findViewById(R.id.aboutSectionChoice);
        aboutSectionChoice.setText(item(getString(R.string.about_section),
                getString(R.string.about_section_desc)));
        aboutSectionChoice.setOnClickListener(v -> {
            boolean expanded = !choices.getBoolean(ABOUT_SECTION_KEY, false);
            choices.edit().putBoolean(ABOUT_SECTION_KEY, expanded).apply();
            refreshAboutSection();
        });
        refreshAboutSection();
    }

    private void refreshAboutSection() {
        boolean expanded = choices.getBoolean(ABOUT_SECTION_KEY, false);
        aboutSectionChoice.setCompoundDrawablesRelativeWithIntrinsicBounds(
                icon(R.drawable.ic_info), null,
                icon(expanded ? R.drawable.ic_remove : R.drawable.ic_add), null);
        int visibility = expanded ? View.VISIBLE : View.GONE;
        findViewById(R.id.aboutVersion).setVisibility(visibility);
        findViewById(R.id.aboutSource).setVisibility(visibility);
    }

    /**
     * One switch per widget under the master one: the same list Home's shelf keeps (ORDER_KEY),
     * so hiding a widget here is its Remove on the shelf, and showing one its Add.
     */
    private void showWidgetToggles(float density) {
        for (int i = 0; i < WIDGET_IDS.length; i++) {
            String id = WIDGET_IDS[i];
            TextView row = findViewById(WIDGET_ROWS[i]);
            widgetRows[i] = row;
            row.setText(getString(WIDGET_NAMES[i]));
            M3Switch toggle = new M3Switch(density, palette, animations);
            widgetSwitches[i] = toggle;
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    icon(WIDGET_ICONS[i]), null, toggle, null);
            row.setOnClickListener(v -> {
                java.util.List<String> shown = shownWidgets();
                if (shown.contains(id)) shown.remove(id); else shown.add(id);
                choices.edit().putString(WidgetShelf.ORDER_KEY, String.join(",", shown)).apply();
                refreshWidgetRows();
            });
        }
        refreshWidgetRows();
    }

    /** The widgets on the shelf, left to right; every one until the owner changes the list. */
    private java.util.List<String> shownWidgets() {
        java.util.List<String> shown = new java.util.ArrayList<>();
        String saved = choices.getString(WidgetShelf.ORDER_KEY, WidgetShelf.DEFAULT_ORDER);
        for (String id : saved.split(",")) {
            id = id.trim();
            for (String known : WIDGET_IDS) {
                if (known.equals(id) && !shown.contains(id)) shown.add(id);
            }
        }
        return shown;
    }

    private void refreshWidgetRows() {
        boolean master = choices.getBoolean(WidgetShelf.PREF_KEY,
                getResources().getBoolean(R.bool.widgets));
        java.util.List<String> shown = shownWidgets();
        for (int i = 0; i < WIDGET_IDS.length; i++) {
            widgetSwitches[i].setChecked(shown.contains(WIDGET_IDS[i]));
            widgetRows[i].setEnabled(master);
            widgetRows[i].setAlpha(master ? 1f : 0.38f);
        }
        if (widgetLayoutChoice != null) refreshWidgetLayoutRows();
        if (widgetTapChoice != null) refreshWidgetTapRow();
        if (popupBlurChoice != null) refreshPopupBlurRow();
        if (widgetHeightRows[0] != null) refreshWidgetHeightRows();
        if (widgetWidthRows[0] != null) refreshWidgetWidthRows();
    }

    private void refreshGreetingRows() {
        String custom = choices.getString(MainActivity.GREETING_TEXT_KEY, "");
        String shown = custom != null && !custom.isEmpty() ? custom
                : getString(R.string.greeting_text_auto);
        greetingTextChoice.setText(item(getString(R.string.greeting_text), shown));
        greetingTextChoice.setVisibility(View.GONE);
    }

    private void chooseGreetingText() {
        EditText edit = new EditText(this);
        edit.setHint(R.string.greeting_text_hint);
        String current = choices.getString(MainActivity.GREETING_TEXT_KEY, "");
        if (current != null) edit.setText(current);
        new AlertDialog.Builder(this)
                .setTitle(R.string.greeting_text)
                .setView(edit)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    choices.edit().putString(MainActivity.GREETING_TEXT_KEY,
                            edit.getText().toString().trim()).apply();
                    refreshGreetingRows();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static final int[] HEIGHT_OPTIONS = {176, 192, 200, 208, 224};

    /** The shelf's arrangement and card height; Home applies both when it shows again. */
    private void showWidgetLayout(Config config) {
        widgetLayoutChoice = findViewById(R.id.widgetLayoutChoice);
        widgetLayoutChoice.setCompoundDrawablesRelativeWithIntrinsicBounds(
                icon(R.drawable.ic_grid_view), null, null, null);
        widgetLayoutChoice.setOnClickListener(v -> chooseWidgetLayout(config));
        refreshWidgetLayoutRows();
        widgetTapChoice = findViewById(R.id.widgetTapChoice);
        widgetTapChoice.setCompoundDrawablesRelativeWithIntrinsicBounds(
                icon(R.drawable.ic_open_in_new), null, null, null);
        widgetTapChoice.setOnClickListener(v -> chooseWidgetTap(config));
        refreshWidgetTapRow();
        popupBlurChoice = findViewById(R.id.popupBlurChoice);
        popupBlurChoice.setCompoundDrawablesRelativeWithIntrinsicBounds(
                icon(R.drawable.ic_contrast), null, null, null);
        popupBlurChoice.setOnClickListener(v -> choosePopupBlur(config));
        refreshPopupBlurRow();
    }

    private static final int[] HEIGHT_ROWS = {R.id.widgetHeightWeather, R.id.widgetHeightSystem,
            R.id.widgetHeightAir, R.id.widgetHeightCalendar, R.id.widgetHeightPrices};

    /** One height row under each widget's switch, so every card's height is its own choice. */
    private void showWidgetHeights(Config config) {
        for (int i = 0; i < WIDGET_IDS.length; i++) {
            int index = i;
            TextView row = findViewById(HEIGHT_ROWS[i]);
            widgetHeightRows[i] = row;
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    icon(R.drawable.ic_swap_vert), null, null, null);
            row.setOnClickListener(v -> chooseWidgetHeight(config, index));
        }
        refreshWidgetHeightRows();
    }

    private void refreshWidgetHeightRows() {
        boolean master = choices.getBoolean(WidgetShelf.PREF_KEY,
                getResources().getBoolean(R.bool.widgets));
        for (int i = 0; i < WIDGET_IDS.length; i++) {
            int height = WidgetShelf.cardHeightDp(choices, WIDGET_IDS[i]);
            widgetHeightRows[i].setText(item(
                    getString(WIDGET_NAMES[i]) + " · " + getString(R.string.widget_card_height),
                    getString(R.string.widget_height_value, height)));
            widgetHeightRows[i].setEnabled(master);
            widgetHeightRows[i].setAlpha(master ? 1f : 0.38f);
        }
    }

    private void chooseWidgetLayout(Config config) {
        CharSequence[] labels = {getString(R.string.widget_layout_stacked),
                getString(R.string.widget_layout_wide)};
        int selected = WidgetShelf.LAYOUT_WIDE.equals(
                choices.getString(WidgetShelf.LAYOUT_KEY, WidgetShelf.LAYOUT_STACKED)) ? 1 : 0;
        chooseOne(R.string.widget_layout, labels, null, selected, index -> {
            choices.edit().putString(WidgetShelf.LAYOUT_KEY,
                    index == 1 ? WidgetShelf.LAYOUT_WIDE : WidgetShelf.LAYOUT_STACKED).apply();
            setResult(RESULT_OK);
            refreshWidgetLayoutRows();
        }, config);
    }

    private void chooseWidgetTap(Config config) {
        CharSequence[] labels = {getString(R.string.widget_tap_detail),
                getString(R.string.widget_tap_classic)};
        int selected = WidgetShelf.TAP_CLASSIC.equals(
                choices.getString(WidgetShelf.TAP_KEY, WidgetShelf.TAP_DETAIL)) ? 1 : 0;
        chooseOne(R.string.widget_tap, labels, null, selected, index -> {
            choices.edit().putString(WidgetShelf.TAP_KEY,
                    index == 1 ? WidgetShelf.TAP_CLASSIC : WidgetShelf.TAP_DETAIL).apply();
            setResult(RESULT_OK);
            refreshWidgetTapRow();
        }, config);
    }

    private void refreshWidgetTapRow() {
        boolean classic = WidgetShelf.TAP_CLASSIC.equals(
                choices.getString(WidgetShelf.TAP_KEY, WidgetShelf.TAP_DETAIL));
        widgetTapChoice.setText(item(getString(R.string.widget_tap),
                getString(classic ? R.string.widget_tap_classic : R.string.widget_tap_detail)));
        boolean master = choices.getBoolean(WidgetShelf.PREF_KEY,
                getResources().getBoolean(R.bool.widgets));
        widgetTapChoice.setEnabled(master);
        widgetTapChoice.setAlpha(master ? 1f : 0.38f);
    }

    private static final String[] POPUP_BLUR_VALUES = {MainActivity.POPUP_BLUR_ALL,
            MainActivity.POPUP_BLUR_DIM, MainActivity.POPUP_BLUR_OFF};
    private static final int[] POPUP_BLUR_LABELS = {R.string.popup_blur_all,
            R.string.popup_blur_dim, R.string.popup_blur_off};

    private void choosePopupBlur(Config config) {
        CharSequence[] labels = new CharSequence[POPUP_BLUR_LABELS.length];
        for (int i = 0; i < labels.length; i++) labels[i] = getString(POPUP_BLUR_LABELS[i]);
        int selected = popupBlurIndex();
        chooseOne(R.string.popup_blur, labels, null, selected, index -> {
            choices.edit().putString(MainActivity.POPUP_BLUR_KEY,
                    POPUP_BLUR_VALUES[index]).apply();
            setResult(RESULT_OK);
            refreshPopupBlurRow();
        }, config);
    }

    private int popupBlurIndex() {
        String mode = choices.getString(MainActivity.POPUP_BLUR_KEY, MainActivity.POPUP_BLUR_ALL);
        for (int i = 0; i < POPUP_BLUR_VALUES.length; i++) {
            if (POPUP_BLUR_VALUES[i].equals(mode)) return i;
        }
        return 0;
    }

    private void refreshPopupBlurRow() {
        popupBlurChoice.setText(item(getString(R.string.popup_blur),
                getString(POPUP_BLUR_LABELS[popupBlurIndex()])));
        boolean master = choices.getBoolean(WidgetShelf.PREF_KEY,
                getResources().getBoolean(R.bool.widgets));
        popupBlurChoice.setEnabled(master);
        popupBlurChoice.setAlpha(master ? 1f : 0.38f);
    }

    private static final int[] WIDTH_ROWS = {R.id.widgetWidthWeather, R.id.widgetWidthSystem,
            R.id.widgetWidthAir, R.id.widgetWidthCalendar, R.id.widgetWidthPrices};
    private static final int[] WIDTH_LABELS = {R.string.widget_width_tiny,
            R.string.widget_width_narrow, R.string.widget_width_medium,
            R.string.widget_width_wide, R.string.widget_width_widest};

    /** One width row under each widget's height row: its share of the shelf's row, by percent. */
    private void showWidgetWidths(Config config) {
        for (int i = 0; i < WIDGET_IDS.length; i++) {
            int index = i;
            TextView row = findViewById(WIDTH_ROWS[i]);
            widgetWidthRows[i] = row;
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    icon(R.drawable.ic_swap_horiz), null, null, null);
            row.setOnClickListener(v -> chooseWidgetWidth(config, index));
        }
        refreshWidgetWidthRows();
    }

    private void refreshWidgetWidthRows() {
        boolean master = choices.getBoolean(WidgetShelf.PREF_KEY,
                getResources().getBoolean(R.bool.widgets));
        for (int i = 0; i < WIDGET_IDS.length; i++) {
            int percent = WidgetShelf.cardWidthPercent(choices, WIDGET_IDS[i]);
            widgetWidthRows[i].setText(item(
                    getString(WIDGET_NAMES[i]) + " · " + getString(R.string.widget_width),
                    getString(WIDTH_LABELS[widthOption(percent)])));
            widgetWidthRows[i].setEnabled(master);
            widgetWidthRows[i].setAlpha(master ? 1f : 0.38f);
        }
    }

    private static int widthOption(int percent) {
        for (int i = 0; i < WidgetShelf.WIDTH_OPTIONS.length; i++) {
            if (WidgetShelf.WIDTH_OPTIONS[i] == percent) return i;
        }
        return 2;
    }

    private void chooseWidgetWidth(Config config, int widget) {
        CharSequence[] labels = new CharSequence[WidgetShelf.WIDTH_OPTIONS.length];
        for (int i = 0; i < labels.length; i++) labels[i] = getString(WIDTH_LABELS[i]);
        int selected = widthOption(WidgetShelf.cardWidthPercent(choices, WIDGET_IDS[widget]));
        chooseOne(WIDGET_NAMES[widget], labels, null, selected, index -> {
            choices.edit().putInt(WidgetShelf.WIDTH_KEY_PREFIX + WIDGET_IDS[widget],
                    WidgetShelf.WIDTH_OPTIONS[index]).apply();
            setResult(RESULT_OK);
            refreshWidgetWidthRows();
        }, config);
    }

    private void chooseWidgetHeight(Config config, int widget) {
        CharSequence[] labels = new CharSequence[HEIGHT_OPTIONS.length];
        int current = WidgetShelf.cardHeightDp(choices, WIDGET_IDS[widget]);
        int selected = 0;
        for (int i = 0; i < HEIGHT_OPTIONS.length; i++) {
            labels[i] = getString(R.string.widget_height_value, HEIGHT_OPTIONS[i]);
            if (HEIGHT_OPTIONS[i] == current) selected = i;
        }
        chooseOne(WIDGET_NAMES[widget], labels, null, selected, index -> {
            choices.edit().putInt(WidgetShelf.HEIGHT_KEY_PREFIX + WIDGET_IDS[widget],
                    HEIGHT_OPTIONS[index]).apply();
            setResult(RESULT_OK);
            refreshWidgetHeightRows();
        }, config);
    }

    private void refreshWidgetLayoutRows() {
        boolean wide = WidgetShelf.LAYOUT_WIDE.equals(
                choices.getString(WidgetShelf.LAYOUT_KEY, WidgetShelf.LAYOUT_STACKED));
        widgetLayoutChoice.setText(item(getString(R.string.widget_layout),
                getString(wide ? R.string.widget_layout_wide : R.string.widget_layout_stacked)));
        boolean master = choices.getBoolean(WidgetShelf.PREF_KEY,
                getResources().getBoolean(R.bool.widgets));
        widgetLayoutChoice.setEnabled(master);
        widgetLayoutChoice.setAlpha(master ? 1f : 0.38f);
    }

    /** The app opened when the TV turns on (FPT Play, say), or none: just this home screen. */
    private void showBootApp(Config config) {
        bootAppChoice = findViewById(R.id.bootAppChoice);
        bootAppChoice.setCompoundDrawablesRelativeWithIntrinsicBounds(
                icon(R.drawable.ic_apps), null, null, null);
        bootAppChoice.setOnClickListener(v -> chooseBootApp(config));
        refreshBootAppRow();
    }

    private void refreshBootAppRow() {
        String pkg = choices.getString(BootReceiver.PREF_KEY, "");
        String name = getString(R.string.boot_app_none);
        if (pkg != null && !pkg.isEmpty()) {
            try {
                PackageManager pm = getPackageManager();
                name = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            } catch (PackageManager.NameNotFoundException gone) {
                name = getString(R.string.boot_app_none);
            }
        }
        bootAppChoice.setText(item(getString(R.string.boot_app), name));
    }

    /** Every app the TV can launch, TV entries first, by name; row one turns it off. */
    private void chooseBootApp(Config config) {
        PackageManager pm = getPackageManager();
        java.util.Map<String, ResolveInfo> unique = new java.util.LinkedHashMap<>();
        for (String category : new String[]{
                Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo info : pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                if (info.activityInfo == null || !info.activityInfo.exported) continue;
                String pkg = info.activityInfo.packageName;
                if (!pkg.equals(getPackageName()) && !unique.containsKey(pkg)) {
                    unique.put(pkg, info);
                }
            }
        }
        java.util.List<ResolveInfo> apps = new java.util.ArrayList<>(unique.values());
        java.text.Collator collator = java.text.Collator.getInstance();
        apps.sort((a, b) -> collator.compare(
                a.loadLabel(pm).toString(), b.loadLabel(pm).toString()));
        String current = choices.getString(BootReceiver.PREF_KEY, "");
        CharSequence[] labels = new CharSequence[apps.size() + 1];
        labels[0] = getString(R.string.boot_app_none);
        int selected = 0;
        for (int i = 0; i < apps.size(); i++) {
            labels[i + 1] = apps.get(i).loadLabel(pm);
            if (apps.get(i).activityInfo.packageName.equals(current)) selected = i + 1;
        }
        chooseOne(R.string.boot_app, labels, null, selected, index -> {
            String pkg = index == 0 ? "" : apps.get(index - 1).activityInfo.packageName;
            choices.edit().putString(BootReceiver.PREF_KEY, pkg).apply();
            setResult(RESULT_OK);
            refreshBootAppRow();
        }, config);
    }

    private boolean confirmKeyPressedHere;

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (isConfirmKey(event.getKeyCode())) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                confirmKeyPressedHere = true;
            } else if (event.getAction() == KeyEvent.ACTION_UP && !confirmKeyPressedHere) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private static boolean isConfirmKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    @Override
    protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        sideSheet.close();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    /** A row's icon: onSurfaceVariant at rest, the focused list item's content colour on focus. */
    private Drawable icon(int res) {
        Drawable d = getDrawable(res).mutate();
        d.setTintList(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{palette.inverseOnSurface, palette.onSurfaceVariant}));
        return d;
    }

    /** System: whether Home opens this launcher, and Google TV's own home where the TV has one; both open settings. */
    private void showHomeStatus() {
        PackageManager pm = getPackageManager();
        TextView home = findViewById(R.id.defaultInfo);
        ResolveInfo r = pm.resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY);
        boolean isDefault = r != null && r.activityInfo != null && getPackageName().equals(r.activityInfo.packageName);
        home.setText(item(getString(R.string.about_default),
                getString(isDefault ? R.string.about_default_yes : R.string.about_default_no)));
        home.setCompoundDrawablesRelativeWithIntrinsicBounds(icon(R.drawable.ic_home), null, null, null);
        home.setOnClickListener(v -> open(new Intent(Settings.ACTION_HOME_SETTINGS)));


        TextView stock = findViewById(R.id.stockInfo);
        int setting;
        try {
            setting = pm.getApplicationEnabledSetting(STOCK_LAUNCHER);
        } catch (IllegalArgumentException e) {
            stock.setVisibility(View.GONE);   // not on this TV: nothing to open
            return;
        }
        boolean off = setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                || setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                || setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
        stock.setText(item(getString(R.string.about_stock),
                getString(off ? R.string.about_stock_off : R.string.about_stock_on)));
        stock.setCompoundDrawablesRelativeWithIntrinsicBounds(icon(R.drawable.ic_tv), null, null, null);
        // Its App info page: where it can be turned back on (tvctl's undo does the same from a computer).
        stock.setOnClickListener(v -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", STOCK_LAUNCHER, null))));
    }

    /**
     * About, view only: an outlined card, M3's container for static content, of entries (a label over its value)
     * with no icons or arrows. It takes focus so the remote reaches it, but OK does nothing: focus only turns its
     * outline into the primary focus ring, where the rows that act fill in.
     */
    private void showUpdate() {
        updateChoice = findViewById(R.id.updateChoice);
        updateChoice.setCompoundDrawablesRelativeWithIntrinsicBounds(
                icon(R.drawable.ic_system_update), null, null, null);
        updateChoice.setOnClickListener(v -> onUpdateChosen());
        checkForUpdate();
    }

    private void checkForUpdate() {
        String installed = versionName();
        updateBusy = true;
        showUpdateState(getString(R.string.update_checking, installed));
        worker.execute(() -> {
            try {
                LauncherRelease latest = LauncherRelease.latest();
                runOnUiThread(() -> showRelease(latest, installed));
            } catch (IOException e) {
                runOnUiThread(() -> showUpdateFailure(R.string.update_check_failed));
            }
        });
    }

    private void showRelease(LauncherRelease latest, String installed) {
        updateBusy = false;
        offeredRelease = latest.isNewerThan(installed) ? latest : null;
        if (offeredRelease == null) {
            showUpdateState(getString(R.string.update_current, installed));
            return;
        }

        showUpdateState(getString(R.string.update_available, latest.version,
                latest.apkSize / 1024));
    }

    private void onUpdateChosen() {
        if (updateBusy) return;

        if (offeredRelease == null) {
            checkForUpdate();
            return;
        }

        if (!getPackageManager().canRequestPackageInstalls()) {
            askToAllowInstalls();
            return;
        }

        downloadAndInstall(offeredRelease);
    }

    private void downloadAndInstall(LauncherRelease release) {
        updateBusy = true;
        showUpdateState(getString(R.string.update_downloading, 0));
        worker.execute(() -> {
            try {
                ReleaseInstaller.install(getApplicationContext(), release, percent ->
                        runOnUiThread(() ->
                                showUpdateState(getString(R.string.update_downloading, percent))));
                runOnUiThread(() -> {
                    updateBusy = false;
                    showUpdateState(getString(R.string.update_installing));
                });
            } catch (IOException e) {
                runOnUiThread(() -> showUpdateFailure(R.string.update_download_failed));
            }
        });
    }

    private void askToAllowInstalls() {
        Intent allow = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.fromParts("package", getPackageName(), null));
        try {
            startActivity(allow);
        } catch (ActivityNotFoundException | SecurityException e) {
            showUpdateState(getString(R.string.update_needs_permission));
        }
    }

    private void showUpdateFailure(int message) {
        updateBusy = false;
        showUpdateState(getString(message));
    }

    private void showUpdateState(String status) {
        updateChoice.setText(item(getString(R.string.update_title), status));
    }

    private void showAbout() {
        showAboutRow(R.id.aboutVersion, R.drawable.ic_info, R.string.about_version, versionName());
        showAboutRow(R.id.aboutSource, R.drawable.ic_code, R.string.about_source,
                getString(R.string.about_source_value));
        findViewById(R.id.aboutEmail).setVisibility(View.GONE);
    }

    private void showAboutRow(int rowId, int iconRes, int label, String value) {
        TextView row = findViewById(rowId);
        row.setText(item(getString(label), value));
        row.setCompoundDrawablesRelativeWithIntrinsicBounds(icon(iconRes), null, null, null);
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }


    private static int checkedIcon(boolean on) {
        return on ? R.drawable.ic_check_on : R.drawable.ic_check_off;
    }

    private void refreshSourcesRow() {
        List<String> names = new ArrayList<>();
        for (int index = 0; index < MainActivity.NEWS_SOURCE_KEYS.length; index++) {
            if (choices.getBoolean(MainActivity.NEWS_SOURCE_KEYS[index],
                    MainActivity.NEWS_SOURCE_DEFAULTS[index])) {
                names.add(getString(MainActivity.NEWS_SOURCE_NAMES[index]));
            }
        }
        if (choices.getBoolean(MainActivity.MATCH_24H_KEY, true)) {
            names.add(getString(R.string.source_match_24h));
        }
        if (choices.getBoolean(MainActivity.MATCH_ESPN_KEY, true)) {
            names.add(getString(R.string.source_match_espn));
        }
        String desc = names.isEmpty() ? getString(R.string.source_none) : String.join(", ", names);
        sourcesChoice.setText(item(getString(R.string.sources), desc));
    }

    /** A checklist in a dialog: each row toggles its source at once, closing refreshes the row. */
    private void chooseSources(Config config) {
        Palette p = Palette.currentOrBaseline();
        float d = getResources().getDisplayMetrics().density;
        LinearLayout panel = dialogPanel(R.string.sources, p, d);
        LinearLayout options = dialogOptionList(panel, d);
        int newsCount = MainActivity.NEWS_SOURCE_KEYS.length;
        int total = newsCount + 2;
        TextView[] rows = new TextView[total];
        String[] keys = new String[total];
        boolean[] defaults = new boolean[total];
        for (int index = 0; index < newsCount; index++) {
            keys[index] = MainActivity.NEWS_SOURCE_KEYS[index];
            defaults[index] = MainActivity.NEWS_SOURCE_DEFAULTS[index];
            rows[index] = optionRow(getString(MainActivity.NEWS_SOURCE_NAMES[index]),
                    checkedIcon(choices.getBoolean(keys[index], defaults[index])));
            options.addView(rows[index]);
        }
        keys[newsCount] = MainActivity.MATCH_24H_KEY;
        defaults[newsCount] = true;
        rows[newsCount] = optionRow(getString(R.string.source_match_24h),
                checkedIcon(choices.getBoolean(keys[newsCount], true)));
        options.addView(rows[newsCount]);
        keys[newsCount + 1] = MainActivity.MATCH_ESPN_KEY;
        defaults[newsCount + 1] = true;
        rows[newsCount + 1] = optionRow(getString(R.string.source_match_espn),
                checkedIcon(choices.getBoolean(keys[newsCount + 1], true)));
        options.addView(rows[newsCount + 1]);
        Dialog dialog = showDialog(panel, p, config, d);
        for (int index = 0; index < total; index++) {
            int value = index;
            rows[index].setOnClickListener(v -> {
                boolean on = !choices.getBoolean(keys[value], defaults[value]);
                choices.edit().putBoolean(keys[value], on).apply();
                rows[value].setCompoundDrawablesRelativeWithIntrinsicBounds(
                        icon(checkedIcon(on)), null, null, null);
            });
        }
        dialog.setOnDismissListener(ignored -> refreshSourcesRow());
        rows[0].requestFocus();
    }

    private void chooseColumns(Config config) {
        CharSequence[] labels = new CharSequence[COLUMN_OPTIONS.length];
        int selected = 0;
        for (int index = 0; index < COLUMN_OPTIONS.length; index++) {
            labels[index] = String.valueOf(COLUMN_OPTIONS[index]);
            if (COLUMN_OPTIONS[index] == columns) selected = index;
        }
        chooseOne(R.string.columns_label, labels, null, selected, index -> setColumns(COLUMN_OPTIONS[index]), config);
    }

    /** A radio list in a dialog, each row optionally with a swatch at its end; a pick closes it. */
    private void chooseOne(int titleText, CharSequence[] labels, Drawable[] swatches, int selected,
                           IntConsumer pick, Config config) {
        Palette p = Palette.currentOrBaseline();
        float d = getResources().getDisplayMetrics().density;
        LinearLayout panel = dialogPanel(titleText, p, d);

        LinearLayout options = dialogOptionList(panel, d);
        TextView[] rows = new TextView[labels.length];
        for (int index = 0; index < labels.length; index++) {
            int radio = index == selected ? R.drawable.ic_radio_on : R.drawable.ic_radio_off;
            TextView row = optionRow(labels[index], radio);
            if (swatches != null) {
                row.setCompoundDrawablesRelativeWithIntrinsicBounds(row.getCompoundDrawablesRelative()[0], null,
                        swatches[index], null);
            }
            options.addView(row);
            rows[index] = row;
        }

        Dialog dialog = showDialog(panel, p, config, d);
        for (int index = 0; index < rows.length; index++) {
            int value = index;
            rows[index].setOnClickListener(v -> {
                pick.accept(value);
                dialog.dismiss();
            });
        }
        rows[selected >= 0 && selected < rows.length ? selected : 0].requestFocus();
    }

    /** The Menu button opens the inputs (InputKeyService); only on a TV with HDMI or other inputs. */
    private void showMenuInputs(Config config, float density) {
        TextView row = findViewById(R.id.menuInputsChoice);
        if (!hasInputs()) {
            row.setVisibility(View.GONE);
            return;
        }
        row.setText(item(getString(R.string.menu_inputs), getString(R.string.menu_inputs_desc)));
        M3Switch toggle = new M3Switch(density, palette, config.animations);
        toggle.setChecked(choices.getBoolean(InputKeyService.MENU_OPENS_INPUTS, false));
        row.setCompoundDrawablesRelativeWithIntrinsicBounds(icon(R.drawable.ic_input), null, toggle, null);
        row.setOnClickListener(v -> {
            boolean on = !choices.getBoolean(InputKeyService.MENU_OPENS_INPUTS, false);
            choices.edit().putBoolean(InputKeyService.MENU_OPENS_INPUTS, on).apply();
            toggle.setChecked(on);
        });
    }

    /** HDMI, AV or another passthrough input the TV lists and has not hidden, as Home's Inputs pill counts them. */
    private boolean hasInputs() {
        TvInputManager inputs = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (inputs == null) return false;
        for (TvInputInfo info : inputs.getTvInputList()) {
            if (info.isPassthroughInput() && !info.isHidden(this)) return true;
        }
        return false;
    }

    /** Appearance: the scheme's seed colour and what is behind the apps; Home repaints behind the sheet. */
    private void showLook(Config config) {
        colorChoice = findViewById(R.id.colorChoice);
        colorChoice.setCompoundDrawablesRelativeWithIntrinsicBounds(icon(R.drawable.ic_contrast), null, null, null);
        colorChoice.setOnClickListener(v -> chooseColor(config));
        backdropChoice = findViewById(R.id.backdropChoice);
        backdropChoice.setCompoundDrawablesRelativeWithIntrinsicBounds(icon(R.drawable.ic_picture), null, null, null);
        backdropChoice.setOnClickListener(v -> chooseBackdrop(config));
        wallpaper = !config.wallpapers.isEmpty();
        for (TextView row : new TextView[]{colorChoice, backdropChoice}) {
            row.setEnabled(!wallpaper);
            row.setAlpha(wallpaper ? 0.38f : 1f);
        }
        showLookValues();
    }

    private void showLookValues() {
        int seed = seedIndex();
        String[] names = getResources().getStringArray(R.array.look_seed_names);
        colorChoice.setText(item(getString(R.string.look_color),
                seed < 0 ? getString(R.string.look_color_theme) : names[seed]));
        int backdrop = indexOf(HomeLook.BACKDROPS, HomeLook.backdrop(choices));
        backdropChoice.setText(item(getString(R.string.look_backdrop),
                getString(wallpaper ? R.string.look_backdrop_wallpaper : BACKDROP_NAMES[backdrop])));
    }

    /** The chosen seed's place in HomeLook.SEEDS, or -1 for the theme's colour. */
    private int seedIndex() {
        if (!choices.contains(Contract.Key.BACKGROUND_COLOR)) return -1;
        int chosen = choices.getInt(Contract.Key.BACKGROUND_COLOR, 0);
        for (int index = 0; index < HomeLook.SEEDS.length; index++) if (HomeLook.SEEDS[index] == chosen) return index;
        return -1;
    }

    private void chooseColor(Config config) {
        String[] names = getResources().getStringArray(R.array.look_seed_names);
        CharSequence[] labels = new CharSequence[HomeLook.SEEDS.length + 1];
        Drawable[] swatches = new Drawable[labels.length];
        labels[0] = getString(R.string.look_color_theme);
        for (int index = 0; index < HomeLook.SEEDS.length; index++) {
            labels[index + 1] = names[index];
            swatches[index + 1] = swatch(Palette.fromSeed(HomeLook.SEEDS[index]).primary);
        }
        chooseOne(R.string.look_color, labels, swatches, seedIndex() + 1, index -> {
            SharedPreferences.Editor editor = choices.edit();
            if (index == 0) editor.remove(Contract.Key.BACKGROUND_COLOR);
            else editor.putInt(Contract.Key.BACKGROUND_COLOR, HomeLook.SEEDS[index - 1]);
            editor.apply();
            showLookValues();
        }, config);
    }

    private void chooseBackdrop(Config config) {
        CharSequence[] labels = new CharSequence[BACKDROP_NAMES.length];
        for (int index = 0; index < labels.length; index++) labels[index] = getString(BACKDROP_NAMES[index]);
        int selected = indexOf(HomeLook.BACKDROPS, HomeLook.backdrop(choices));
        chooseOne(R.string.look_backdrop, labels, null, selected, index -> {
            choices.edit().putString(HomeLook.BACKDROP_KEY, HomeLook.BACKDROPS[index]).apply();
            showLookValues();
        }, config);
    }

    /** Screensaver: when it starts (a system setting) and ClockDream's style (this launcher's). */
    private void showScreensaver(Config config) {
        screensaverTimeChoice = findViewById(R.id.screensaverTimeChoice);
        screensaverTimeChoice.setCompoundDrawablesRelativeWithIntrinsicBounds(icon(R.drawable.ic_timer), null, null,
                null);
        screensaverTimeChoice.setOnClickListener(v -> chooseScreensaverTime(config));
        screensaverStyleChoice = findViewById(R.id.screensaverStyleChoice);
        screensaverStyleChoice.setCompoundDrawablesRelativeWithIntrinsicBounds(icon(R.drawable.ic_w_clear_night), null,
                null, null);
        screensaverStyleChoice.setOnClickListener(v -> chooseScreensaverStyle(config));
        showScreensaverValues();
    }

    private void showScreensaverValues() {
        String after = minutesText(Screensaver.minutes(this));
        screensaverTimeChoice.setText(item(getString(R.string.screensaver_after), after));
        int style = indexOf(Screensaver.STYLES, Screensaver.style(choices));
        screensaverStyleChoice.setText(item(getString(R.string.screensaver_style),
                getString(SCREENSAVER_STYLE_NAMES[style])));
    }

    private String minutesText(int minutes) {
        return minutes == 0 ? getString(R.string.screensaver_never)
                : getResources().getQuantityString(R.plurals.screensaver_minutes, minutes, minutes);
    }

    private void chooseScreensaverTime(Config config) {
        CharSequence[] labels = new CharSequence[Screensaver.MINUTES.length];
        int current = Screensaver.minutes(this), selected = -1;
        for (int index = 0; index < labels.length; index++) {
            labels[index] = minutesText(Screensaver.MINUTES[index]);
            if (Screensaver.MINUTES[index] == current) selected = index;
        }
        chooseOne(R.string.screensaver_after, labels, null, selected, index -> {
            if (!Screensaver.setMinutes(this, Screensaver.MINUTES[index])) {
                Toast.makeText(this, R.string.screensaver_denied, Toast.LENGTH_LONG).show();
            }
            showScreensaverValues();
        }, config);
    }

    private void chooseScreensaverStyle(Config config) {
        CharSequence[] labels = new CharSequence[SCREENSAVER_STYLE_NAMES.length];
        for (int index = 0; index < labels.length; index++) labels[index] = getString(SCREENSAVER_STYLE_NAMES[index]);
        int selected = indexOf(Screensaver.STYLES, Screensaver.style(choices));
        chooseOne(R.string.screensaver_style, labels, null, selected, index -> {
            choices.edit().putString(Screensaver.STYLE_KEY, Screensaver.STYLES[index]).apply();
            showScreensaverValues();
        }, config);
    }

    private static int indexOf(String[] values, String value) {
        for (int index = 0; index < values.length; index++) if (values[index].equals(value)) return index;
        return 0;
    }

    /** A filled circle in a colour, the size of a row's icon. */
    private Drawable swatch(int color) {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        int size = Math.round(20 * getResources().getDisplayMetrics().density);
        dot.setSize(size, size);
        return dot;
    }

    private void confirmPower(Config config) {
        Palette p = Palette.currentOrBaseline();
        float d = getResources().getDisplayMetrics().density;
        LinearLayout panel = dialogHeroPanel(R.drawable.ic_power, R.string.reboot, R.string.boot_options_message, p, d);

        LinearLayout options = dialogOptionList(panel, d);
        TextView restart = optionRow(item(getString(R.string.reboot_action), getString(R.string.restart_desc)),
                R.drawable.ic_refresh);
        TextView turnOff = optionRow(item(getString(R.string.power_off_action), getString(R.string.power_off_desc)),
                R.drawable.ic_power);
        options.addView(restart);
        options.addView(turnOff);

        LinearLayout buttons = dialogButtonRow(panel, d);
        TextView cancel = dialogButton(android.R.string.cancel, d);
        buttons.addView(cancel);

        Dialog dialog = showDialog(panel, p, config, d);
        cancel.setOnClickListener(v -> dialog.dismiss());
        restart.setOnClickListener(v -> {
            dialog.dismiss();
            runPowerCommand(RESTART_COMMAND);
        });
        turnOff.setOnClickListener(v -> {
            dialog.dismiss();
            runPowerCommand(TURN_OFF_COMMAND);
        });
        cancel.requestFocus();
    }

    /** Back to the defaults: the whole settings file goes, and this sheet is rebuilt on them. */
    private void confirmResetSettings(Config config) {
        Palette p = Palette.currentOrBaseline();
        float d = getResources().getDisplayMetrics().density;
        LinearLayout panel = dialogHeroPanel(R.drawable.ic_refresh, R.string.reset_settings,
                R.string.reset_settings_message, p, d);

        LinearLayout buttons = dialogButtonRow(panel, d);
        TextView cancel = dialogButton(android.R.string.cancel, d);
        TextView reset = dialogButton(R.string.reset_settings_action, d);
        buttons.addView(cancel);
        buttons.addView(reset);

        Dialog dialog = showDialog(panel, p, config, d);
        cancel.setOnClickListener(v -> dialog.dismiss());
        reset.setOnClickListener(v -> {
            dialog.dismiss();
            choices.edit().clear().apply();
            setResult(RESULT_OK);
            recreate();
        });
        cancel.requestFocus();
    }

    private void runPowerCommand(String command) {
        LocalAdb adb = new LocalAdb(this);
        worker.execute(() -> {
            int problem;
            try {
                adb.shell(command);
                return;
            } catch (LocalAdb.OffException e) {
                problem = R.string.running_off;
            } catch (LocalAdb.NotAllowedException e) {
                problem = R.string.running_denied;
            } catch (IOException e) {
                return;
            }
            int message = problem;
            runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
        });
    }

    private void setColumns(int value) {
        if (columns == value) return;
        columns = value;
        choices.edit().putInt("columns", value).apply();
        refresh();
    }

    private LinearLayout dialogHeroPanel(int iconRes, int titleText, int messageText, Palette p, float d) {
        LinearLayout panel = dialogPanel(titleText, p, d);
        TextView title = (TextView) panel.getChildAt(0);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ShapeBadge badge = new ShapeBadge(this, null);
        badge.setPalette(p);
        badge.setIcon(getDrawable(iconRes).mutate());
        int badgeSize = (int) (48 * d);
        LinearLayout.LayoutParams badgeLayout = new LinearLayout.LayoutParams(badgeSize, badgeSize);
        badgeLayout.gravity = Gravity.CENTER_HORIZONTAL;
        badgeLayout.bottomMargin = (int) (16 * d);
        panel.addView(badge, 0, badgeLayout);

        TextView message = new TextView(this);
        message.setText(messageText);
        message.setTextSize(14f);
        message.setTextColor(p.onSurfaceVariant);
        message.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams messageLayout = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        messageLayout.topMargin = (int) (8 * d);
        panel.addView(message, messageLayout);
        return panel;
    }

    private LinearLayout dialogOptionList(LinearLayout panel, float d) {
        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setClipChildren(false);
        options.setClipToPadding(false);
        LinearLayout.LayoutParams optionsLayout = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        optionsLayout.topMargin = (int) (20 * d);
        panel.addView(options, optionsLayout);
        return options;
    }

    private TextView optionRow(CharSequence text, int iconRes) {
        TextView row = new TextView(this, null, 0, R.style.SettingsListItem);
        row.setText(text);
        row.setFocusable(true);
        row.setClickable(true);
        row.setCompoundDrawablesRelativeWithIntrinsicBounds(icon(iconRes), null, null, null);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private LinearLayout dialogButtonRow(LinearLayout panel, float d) {
        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.END);
        buttons.setClipChildren(false);
        buttons.setClipToPadding(false);
        LinearLayout.LayoutParams buttonsLayout = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonsLayout.topMargin = (int) (24 * d);
        panel.addView(buttons, buttonsLayout);
        return buttons;
    }

    private LinearLayout dialogPanel(int titleText, Palette p, float d) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setTag("m3:panel");
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        int padding = (int) (24 * d);
        panel.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(22f);
        title.setTextColor(p.onSurface);
        panel.addView(title);
        return panel;
    }

    private Dialog showDialog(LinearLayout panel, Palette p, Config config, float d) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        M3Skin.apply(panel, p, config.animations, config.reducedMotion);
        panel.setElevation(0f);   // flat: the dialog's dim already sets it apart

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0));
            window.setLayout((int) (440 * d), ViewGroup.LayoutParams.WRAP_CONTENT);
            if (config.animations) window.setWindowAnimations(0);   // the panel's own spring instead
        }
        dialog.show();
        if (config.animations) popIn(panel, config);
        return dialog;
    }

    /** M3 Expressive's dialog entrance: the panel grows from 90% on the default spatial spring as it fades in. */
    private static void popIn(View panel, Config config) {
        Motion.Spec spatial = Motion.DEFAULT_SPATIAL.reduced(config.reducedMotion), effects = Motion.DEFAULT_EFFECTS;
        panel.setScaleX(POP_FROM);
        panel.setScaleY(POP_FROM);
        panel.setAlpha(0f);
        spatial.drive(panel.animate().scaleX(1f).scaleY(1f));
        effects.fade(panel, 1f, 0);
    }

    private static final float POP_FROM = 0.9f;

    private TextView dialogButton(int text, float d) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTag("m3:button");
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setTextSize(15f);
        int h = (int) (24 * d);
        b.setPadding(h, 0, h, 0);
        b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (int) (40 * d)));
        return b;
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        setResult(RESULT_OK);
        refresh();
    }

    private void refresh() {
        columnsChoice.setText(item(getString(R.string.columns_label), String.valueOf(columns)));
        animationsChoice.setText(item(title(getString(R.string.animations_state, "")),
                getString(R.string.animations_desc)));
        animationsSwitch.setChecked(animations);
        advancedChoice.setText(item(getString(R.string.advanced_effects), getString(R.string.advanced_effects_warning)));
        advancedSwitch.setChecked(advancedEffects);
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.38f);
        M3Skin.refresh(findViewById(android.R.id.content));
    }

    /** "Animations: %1$s" with the value left out, in whatever form the language puts it. */
    private static String title(String formattedWithBlank) {
        return formattedWithBlank.replaceAll("[\\s:：\\-–—]+$", "").replaceAll("^[\\s:：\\-–—]+", "");
    }

    /** A list item's text: the title, and under it the supporting text, smaller and in the regular weight. */
    private CharSequence item(String title, String supporting) {
        if (supporting == null) return title;
        SpannableStringBuilder text = new SpannableStringBuilder(title).append('\n');
        int start = text.length();
        text.append(supporting);
        text.setSpan(new RelativeSizeSpan(0.78f), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (Build.VERSION.SDK_INT >= 28) {
            text.setSpan(new TypefaceSpan(getResources().getFont(R.font.sans_regular)), start, text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return text;
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
