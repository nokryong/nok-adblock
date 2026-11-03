package dev.nok.adblock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.net.IDN;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class DomainRules {
    private final Set<String> blocked, allowed;
    public DomainRules(Set<String> blocked, Set<String> allowed) {
        this.blocked = Collections.unmodifiableSet(new HashSet<>(blocked));
        this.allowed = Collections.unmodifiableSet(new HashSet<>(allowed));
    }
    public int size() { return blocked.size(); }
    public boolean blocks(String domain) {
        String d = domain.toLowerCase(Locale.ROOT);
        if (matches(allowed, d)) return false;
        return matches(blocked, d);
    }
    private static boolean matches(Set<String> set, String domain) {
        while (true) {
            if (set.contains(domain)) return true;
            int dot = domain.indexOf('.');
            if (dot < 0) return false;
            domain = domain.substring(dot + 1);
        }
    }
    public static String normalize(String input) {
        String d = input.trim().toLowerCase(Locale.ROOT);
        if (d.endsWith(".")) d = d.substring(0, d.length() - 1);
        try { d = IDN.toASCII(d, IDN.USE_STD3_ASCII_RULES); }
        catch (IllegalArgumentException e) { return null; }
        if (d.length() > 253 || d.indexOf('.') < 1 || d.matches("[0-9.]+")) return null;
        for (String label : d.split("\\.", -1))
            if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) return null;
        return d;
    }
    /** Parse hosts format; only sinkhole entries are eligible. */
    public static Set<String> parse(Reader input) throws IOException {
        HashSet<String> result = new HashSet<>();
        BufferedReader reader = new BufferedReader(input);
        String line; int lines = 0;
        while ((line = reader.readLine()) != null) {
            if (++lines > 500000 || line.length() > 8192) throw new IOException("차단 목록이 너무 큽니다");
            int hash = line.indexOf('#'); if (hash >= 0) line = line.substring(0, hash);
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 2 || !(parts[0].equals("0.0.0.0") || parts[0].equals("127.0.0.1") || parts[0].equals("::"))) continue;
            for (int i = 1; i < parts.length; i++) {
                String d = normalize(parts[i]);
                if (d != null && !d.equals("localhost.localdomain") && !d.endsWith(".local")) result.add(d);
            }
        }
        return result;
    }
}
