package dev.nok.adblock;

import java.io.IOException;
import java.net.*;

/** One UDP query to the network's own resolver, used only when encrypted DNS is unreachable. */
public final class PlainDns {
    public interface Binder { void bind(DatagramSocket socket) throws IOException; }
    private PlainDns() {}
    public static byte[] exchange(InetAddress server, int port, byte[] query, int timeoutMs, Binder binder) throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            if (binder != null) binder.bind(socket);
            socket.setSoTimeout(timeoutMs); socket.connect(server, port);
            socket.send(new DatagramPacket(query, query.length));
            byte[] buffer = new byte[65535];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);
            byte[] answer = java.util.Arrays.copyOf(buffer, packet.getLength());
            DnsMessage.validateResponse(query, answer);
            return answer;
        }
    }
}
