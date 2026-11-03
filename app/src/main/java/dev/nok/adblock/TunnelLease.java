package dev.nok.adblock;

import java.io.Closeable;
import java.io.IOException;

/** Owns one tunnel, including one that finishes opening after cancellation. */
public final class TunnelLease implements Closeable {
    private volatile boolean stopped;
    private Closeable tunnel;
    public boolean isStopped() { return stopped; }
    /** Signal a native reader to stop before its descriptor is released. */
    public void cancel() { stopped = true; }
    public boolean attach(Closeable opened) throws IOException {
        if (opened == null) throw new NullPointerException("tunnel");
        synchronized (this) {
            if (!stopped) {
                if (tunnel != null) throw new IllegalStateException("A tunnel is already attached");
                tunnel = opened;
                return true;
            }
        }
        opened.close();
        return false;
    }
    @Override public void close() throws IOException {
        Closeable closing;
        synchronized (this) {
            stopped = true;
            closing = tunnel;
            tunnel = null;
        }
        if (closing != null) closing.close();
    }
}
