package dev.nok.adblock;

import java.util.Locale;

/** Localized diagnostics for the Android-independent networking and update code. */
final class Messages {
    private Messages() {}

    static String text(String korean, String english) {
        return "ko".equals(Locale.getDefault().getLanguage()) ? korean : english;
    }
}
