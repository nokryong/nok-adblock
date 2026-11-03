package dev.nok.adblock;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import javax.net.ssl.*;

/** Optional live HTTPS checks: selected example.com and unselected public Google home page. */
public final class LiveProxyTests {
    private static void connect(LocalProxy proxy, String host, String protocol) throws Exception {
        InetAddress address = null;
        for (InetAddress candidate : InetAddress.getAllByName(host))
            if (candidate instanceof Inet4Address) { address = candidate; break; }
        if (address == null) throw new AssertionError("No IPv4 address for " + host);
        Socket raw = ProxyTests.connect(proxy, address.getHostAddress(), 443);
        try (SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, 443, true)) {
            tls.setSoTimeout(12000); tls.setEnabledProtocols(new String[]{protocol});
            SSLParameters p = tls.getSSLParameters(); p.setEndpointIdentificationAlgorithm("HTTPS");
            p.setServerNames(Collections.singletonList(new SNIHostName(host))); tls.setSSLParameters(p);
            tls.startHandshake();
            tls.getOutputStream().write(("HEAD / HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            String status = new java.io.BufferedReader(new java.io.InputStreamReader(tls.getInputStream(), StandardCharsets.US_ASCII)).readLine();
            if (status == null) throw new AssertionError("Missing HTTP response");
            if (!status.startsWith("HTTP/1.1 200") && !status.startsWith("HTTP/1.0 200")) throw new AssertionError("Unexpected HTTP response: " + status);
            System.out.println("PASS: " + host + " via " + tls.getSession().getProtocol() + ", default certificate trust and HTTPS hostname validation, HTTP 200");
        }
    }
    public static void main(String[] args) throws Exception {
        try (LocalProxy proxy = new LocalProxy(q -> { throw new java.io.IOException("DNS not used by this test"); }, host -> false, host -> host.equals("example.com"))) {
            connect(proxy, "example.com", "TLSv1.2");
            connect(proxy, "example.com", "TLSv1.3");
            if (proxy.fragmentCount() != 2) throw new AssertionError("Fragment path was not used twice");
            connect(proxy, "www.google.com", "TLSv1.3");
            if (proxy.fragmentCount() != 2) throw new AssertionError("Unselected Google connection was fragmented");
            System.out.println("PASS: selected sites fragmented, Google relayed without TLS rewriting");
        }
    }
}
