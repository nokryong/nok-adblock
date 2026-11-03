package dev.nok.adblock;

/** Optional integration check: sends only example.com DNS questions to the listed public resolvers. */
public final class LiveDnsTests {
    public static void main(String[] args) throws Exception {
        DohResolver resolver = new DohResolver();
        try {
            for (String endpoint : new String[]{"https://cloudflare-dns.com/dns-query", "https://dns.google/dns-query"}) {
                for (int type : new int[]{1, 28}) {
                    byte[] query = CoreTests.query("example.com", type);
                    byte[] answer = resolver.resolve(endpoint, query);
                    DnsMessage.validateResponse(query, answer);
                    if ((answer[3] & 15) != 0 || DnsMessage.u16(answer, 6) < 1) throw new AssertionError("Expected a positive answer");
                    System.out.println("PASS: " + endpoint + " type=" + type + " answer=" + answer.length + " bytes");
                }
            }
        } finally { resolver.close(); }
    }
}
