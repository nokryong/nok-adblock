package dev.nok.adblock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.net.IDN;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Finds the site used to group an ASCII DNS hostname in the log. */
final class PublicSuffixes {
    private final Set<String> exact = new HashSet<>();
    private final Set<String> wildcards = new HashSet<>();
    private final Set<String> exceptions = new HashSet<>();

    PublicSuffixes() {}

    PublicSuffixes(Reader input) throws IOException {
        BufferedReader reader = new BufferedReader(input);
        String line;
        while ((line = reader.readLine()) != null) {
            String rule = line.trim();
            if (rule.isEmpty() || rule.startsWith("//")) continue;

            Set<String> rules = exact;
            if (rule.startsWith("*.")) {
                rules = wildcards;
                rule = rule.substring(2);
            } else if (rule.startsWith("!")) {
                rules = exceptions;
                rule = rule.substring(1);
            }
            // Java and Android can ship older IDN tables than the bundled list.
            try {
                rules.add(IDN.toASCII(rule, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // An unsupported rule cannot match a normalized DNS hostname.
            }
        }
    }

    String site(String domain) {
        int start = 0;
        int previous = 0;
        String match = domain;
        boolean matched = false;
        while (true) {
            String suffix = domain.substring(start);
            int dot = domain.indexOf('.', start);
            if (exceptions.contains(suffix)) return suffix;
            if (!matched && (exact.contains(suffix)
                    || (dot >= 0 && wildcards.contains(domain.substring(dot + 1))))) {
                match = domain.substring(previous);
                matched = true;
            }
            if (dot < 0) return match;
            previous = start;
            start = dot + 1;
        }
    }
}
