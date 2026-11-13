package obd.app;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Opt-in background Android Service exposing the BLE OBD-II scanner as a standard localhost
 * Wi-Fi ELM327 TCP adapter ({@code 127.0.0.1:35000}).
 *
 * Security & Coexistence Guarantees:
 * - Binds STRICTLY to loopback {@code 127.0.0.1} (never {@code 0.0.0.0}), preventing external Wi-Fi
 *   clients or cabin hotspots from reaching the vehicle CAN bus.
 * - Keeps {@link BleCoordinator}'s BLE link alive in the background when the user switches to
 *   3rd-party Gen5W apps (such as Car Scanner ELM OBD2 or Torque Pro) or wireless projection.
 * - Automatically pauses internal dashboard polling whenever at least one TCP client is connected,
 *   and restores E-GMP framing cleanly when all TCP clients disconnect.
 */
public final class WifiElmBridgeService extends Service {

    public static final String ACTION_START = "obd.app.action.START_WIFI_BRIDGE";
    public static final String ACTION_STOP  = "obd.app.action.STOP_WIFI_BRIDGE";
    public static final String EXTRA_PORT   = "port";

    private static volatile LoopbackBridgeServer sActiveServer;

    public static LoopbackBridgeServer getActiveServer() {
        return sActiveServer;
    }

    public static boolean isBridgeRunning() {
        LoopbackBridgeServer srv = sActiveServer;
        return srv != null && srv.isRunning();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopBridgeInternal();
            stopSelf();
            return START_NOT_STICKY;
        }

        DevicePrefs prefs = new DevicePrefs(getApplicationContext());
        int port = prefs.getBridgePort();
        if (intent != null && intent.hasExtra(EXTRA_PORT)) {
            port = intent.getIntExtra(EXTRA_PORT, port);
        }

        BleCoordinator coord = BleCoordinator.getInstance(getApplicationContext());
        startBridgeInternal(coord, port);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopBridgeInternal();
        super.onDestroy();
    }

    private synchronized void startBridgeInternal(BleCoordinator coord, int port) {
        if (sActiveServer != null && sActiveServer.isRunning() && sActiveServer.getPort() == port) {
            return;
        }
        stopBridgeInternal();
        LoopbackBridgeServer srv = new LoopbackBridgeServer(coord, port);
        try {
            srv.start();
            sActiveServer = srv;
            coord.setKeepAliveInBackground(true);
            DiagLogger.get().info("BRIDGE", "Started Wi-Fi ELM327 loopback bridge on 127.0.0.1:" + port);
        } catch (IOException ioe) {
            DiagLogger.get().error("BRIDGE", "Failed to bind 127.0.0.1:" + port + " — " + ioe.getMessage());
        }
    }

    private synchronized void stopBridgeInternal() {
        LoopbackBridgeServer srv = sActiveServer;
        sActiveServer = null;
        if (srv != null) {
            srv.stop();
            DiagLogger.get().info("BRIDGE", "Stopped Wi-Fi ELM327 loopback bridge");
        }
        try {
            BleCoordinator.getInstance(getApplicationContext()).setKeepAliveInBackground(false);
        } catch (Throwable ignored) {}
    }

    /**
     * Pure Java loopback TCP <-> BLE ELM327 byte relay (testable directly on host JVM).
     */
    public static final class LoopbackBridgeServer implements BleCoordinator.RawByteSink {

        private final BleCoordinator coordinator;
        private final int reqPort;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final List<Socket> clients = new CopyOnWriteArrayList<Socket>();
        private final AtomicLong txBytes = new AtomicLong(0L); // TCP -> BLE
        private final AtomicLong rxBytes = new AtomicLong(0L); // BLE -> TCP
        private volatile ServerSocket serverSocket;
        private volatile int boundPort;

        public LoopbackBridgeServer(BleCoordinator coordinator, int port) {
            this.coordinator = coordinator;
            this.reqPort = port;
        }

        public void start() throws IOException {
            if (!running.compareAndSet(false, true)) return;
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            ServerSocket ss = new ServerSocket(reqPort, 4, loopback);
            this.serverSocket = ss;
            this.boundPort = ss.getLocalPort();

            Thread acceptThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    acceptLoop();
                }
            }, "ElmLoopbackAccept-" + boundPort);
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        public void stop() {
            if (!running.compareAndSet(true, false)) return;
            ServerSocket ss = serverSocket;
            serverSocket = null;
            if (ss != null) {
                try { ss.close(); } catch (Throwable ignored) {}
            }
            for (int i = 0; i < clients.size(); i++) {
                try { clients.get(i).close(); } catch (Throwable ignored) {}
            }
            clients.clear();
            coordinator.attachBridgeClient(null, 0);
        }

        public boolean isRunning() {
            return running.get();
        }

        public int getPort() {
            return boundPort;
        }

        public int getClientCount() {
            return clients.size();
        }

        public long getTxBytes() {
            return txBytes.get();
        }

        public long getRxBytes() {
            return rxBytes.get();
        }

        public InetAddress getBoundAddress() {
            ServerSocket ss = serverSocket;
            return ss != null ? ss.getInetAddress() : null;
        }

        private void acceptLoop() {
            while (running.get()) {
                ServerSocket ss = serverSocket;
                if (ss == null) break;
                try {
                    final Socket client = ss.accept();
                    client.setTcpNoDelay(true);
                    clients.add(client);
                    coordinator.attachBridgeClient(this, clients.size());
                    DiagLogger.get().info("BRIDGE", "TCP client connected (" + clients.size() + " active)");

                    Thread reader = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            handleClientReads(client);
                        }
                    }, "ElmBridgeClientRx");
                    reader.setDaemon(true);
                    reader.start();
                } catch (IOException ioe) {
                    if (running.get()) {
                        DiagLogger.get().warn("BRIDGE", "Accept loop ended: " + ioe.getMessage());
                    }
                    break;
                }
            }
        }

        private void handleClientReads(Socket client) {
            byte[] buf = new byte[256];
            try {
                InputStream in = client.getInputStream();
                while (running.get()) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    if (n == 0) continue;
                    byte[] payload = new byte[n];
                    System.arraycopy(buf, 0, payload, 0, n);
                    txBytes.addAndGet(n);
                    boolean sent = coordinator.sendRawFromBridge(payload);
                    if (!sent) {
                        // Inform TCP client cleanly if BLE adapter isn't ready yet
                        try {
                            OutputStream out = client.getOutputStream();
                            out.write("NO DATA\r>".getBytes(Elm327Protocol.ASCII));
                            out.flush();
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (IOException ignored) {
            } finally {
                clients.remove(client);
                try { client.close(); } catch (Throwable ignored) {}
                int remaining = clients.size();
                coordinator.attachBridgeClient(remaining > 0 ? this : null, remaining);
                DiagLogger.get().info("BRIDGE", "TCP client disconnected (" + remaining + " active)");
            }
        }

        @Override
        public void onAdapterBytes(byte[] bytes) {
            if (bytes == null || bytes.length == 0) return;
            rxBytes.addAndGet(bytes.length);
            for (int i = 0; i < clients.size(); i++) {
                Socket c = clients.get(i);
                try {
                    OutputStream out = c.getOutputStream();
                    out.write(bytes);
                    out.flush();
                } catch (IOException ioe) {
                    clients.remove(c);
                    try { c.close(); } catch (Throwable ignored) {}
                }
            }
        }
    }
}
