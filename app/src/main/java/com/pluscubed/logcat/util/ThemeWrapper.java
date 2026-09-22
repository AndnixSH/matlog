package com.pluscubed.logcat.util;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;

import androidx.annotation.StyleRes;

import com.pluscubed.logcat.App;
import com.pluscubed.logcat.R;

/**
 * Created by Snow Volf on 16.07.2019, 20:40
 */

public abstract class ThemeWrapper {
    /**
     * Apply theme to an Activity
     */
    public static void applyTheme(Activity ctx) {
        applyTheme(ctx, false);
    }

    /**
     * Apply theme to an Activity. With {@code translucent} the variant whose
     * window is see-through is used, for activities that only host a dialog
     * and want the screen behind them to stay visible under the dim.
     */
    public static void applyTheme(Activity ctx, boolean translucent) {
        Theme theme = resolveTheme(ctx);
        ctx.setTheme(translucent ? translucentStyleFor(theme) : styleFor(theme));
        applyAccent(ctx);
    }

    /**
     * The theme actually in effect, with {@link Theme#AUTO} resolved against
     * the system's day/night setting.
     *
     * <p>This deliberately does not touch the colour scheme preference. It used
     * to force the colour scheme to match the app theme, which meant picking a
     * dark colour scheme while the light app theme was selected got silently
     * reverted the next time any activity was created.
     */
    public static Theme resolveTheme(Context context) {
        Theme[] values = Theme.values();
        int index = getThemeIndex();
        Theme selected = (index >= 0 && index < values.length) ? values[index] : Theme.LIGHT;

        if (selected != Theme.AUTO) {
            return selected;
        }

        int uiMode = context.getResources().getConfiguration().uiMode;
        return (uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                ? Theme.DARK
                : Theme.LIGHT;
    }

    @StyleRes
    private static int styleFor(Theme theme) {
        switch (theme) {
            case DARK:
                return R.style.Theme_MatLog;
            case AMOLED:
                return R.style.Theme_MatLog_Amoled;
            case LIGHT:
            default:
                return R.style.Theme_MatLog_Light;
        }
    }

    @StyleRes
    private static int translucentStyleFor(Theme theme) {
        switch (theme) {
            case DARK:
                return R.style.Theme_MatLog_Translucent;
            case AMOLED:
                return R.style.Theme_MatLog_Amoled_Translucent;
            case LIGHT:
            default:
                return R.style.Theme_MatLog_Light_Translucent;
        }
    }

    private static void applyAccent(Context ctx){
        int accent;
        switch (Accent.values()[getAccentIndex()]){
            case RED:
                accent = R.style.AccentRed;
                break;
            case PINK:
                accent = R.style.AccentPink;
                break;
            case PURPLE:
                accent = R.style.AccentPurple;
                break;
            case INDIGO:
                accent = R.style.AccentIndigo;
                break;
            case BLUE:
                accent = R.style.AccentBlue;
                break;
            case LBLUE:
                accent = R.style.AccentLBlue;
                break;
            case CYAN:
                accent = R.style.AccentCyan;
                break;
            case TEAL:
                accent = R.style.AccentTeal;
                break;
            case GREEN:
                accent = R.style.AccentGreen;
                break;
            case LGREEN:
                accent = R.style.AccentLGreen;
                break;
            case LIME:
                accent = R.style.AccentLime;
                break;
            case YELLOW:
                accent = R.style.AccentYellow;
                break;
            case AMBER:
                accent = R.style.AccentAmber;
                break;
            case ORANGE:
                accent = R.style.AccentOrange;
                break;
            case DORANGE:
                accent = R.style.AccentDOrange;
                break;
            case BROWN:
                accent = R.style.AccentBrown;
                break;
            case GREY:
                accent = R.style.AccentGrey;
                break;
            case BGREY:
                accent = R.style.AccentBGrey;
                break;
            default:
                accent = R.style.AccentBlue;
                break;
        }
        ctx.getTheme().applyStyle(accent, true);
    }

    @StyleRes
    public static int getDialogTheme(){
        switch (resolveTheme(App.get())) {
            case DARK:
                return R.style.DarkAppTheme_Dialog;
            case AMOLED:
                return R.style.AmoledAppTheme_Dialog;
            case LIGHT:
            default:
                return com.google.android.material.R.style.Theme_MaterialComponents_Light_Dialog_Alert;
        }
    }

    /**
     * Get a saved theme number
     */
    private static int getThemeIndex() {
        try {
            return Integer.parseInt(App.get().getPreferences()
                    .getString("ui.theme", String.valueOf(Theme.LIGHT.ordinal())));
        } catch (NumberFormatException e) {
            // A corrupt preference should not take down every activity that
            // calls applyTheme(); fall back to the default.
            return Theme.LIGHT.ordinal();
        }
    }

    private static int getAccentIndex() {
        return  Integer.parseInt(App.get().getPreferences().getString("ui.accent",  String.valueOf(Accent.BLUE.ordinal())));
    }

    public static boolean isLightTheme(Context context) {
        return resolveTheme(context) == Theme.LIGHT;
    }

    public static int resolveNavBarColor(Context context) {
        // Android < Oreo does not have View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR flag
        // so, we need to set it a little bit more darker
        if (isLightTheme(context) && Build.VERSION.SDK_INT < Build.VERSION_CODES.O){
            return App.getColorFromAttr(context, androidx.appcompat.R.attr.colorPrimaryDark);
        }
        return  App.getColorFromAttr(context, androidx.appcompat.R.attr.colorPrimary);
    }

    /**
     * Provided themes. Order matters: the stored preference holds the ordinal,
     * so new entries may only be appended, never inserted.
     */
    public enum Theme {
        LIGHT,
        DARK,
        AMOLED,
        /** Follows the system's day/night setting. */
        AUTO
    }

    private enum Accent{
        RED,
        PINK,
        PURPLE,
        INDIGO,
        BLUE,
        LBLUE,
        CYAN,
        TEAL,
        GREEN,
        LGREEN,
        LIME,
        YELLOW,
        AMBER,
        ORANGE,
        DORANGE,
        BROWN,
        GREY,
        BGREY
    }

}
