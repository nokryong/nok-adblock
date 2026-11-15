package dev.nok.adblock;

import java.util.Locale;

public final class MessagesTests {
    public static void main(String[] args) {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.KOREA);
            assert Messages.text("한국어", "English").equals("한국어");
            Locale.setDefault(Locale.KOREAN);
            assert Messages.text("한국어", "English").equals("한국어");
            Locale.setDefault(Locale.US);
            assert Messages.text("한국어", "English").equals("English");
            Locale.setDefault(Locale.JAPAN);
            assert Messages.text("한국어", "English").equals("English");
            System.out.println("Localized diagnostics tests passed");
        } finally {
            Locale.setDefault(original);
        }
    }
}
