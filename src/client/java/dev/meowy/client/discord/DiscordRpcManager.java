package dev.meowy.client.discord;

import com.google.gson.JsonObject;
import dev.meowy.client.config.MeowyConfig;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

/**
 * Pure Java 25 Discord Rich Presence (IPC) client for Meowy.
 * Connects directly to the local Discord desktop client via Windows Named Pipes
 * (\\.\pipe\discord-ipc-0..9) or Unix Domain Sockets without external libraries.
 */
public final class DiscordRpcManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Meowy-DiscordRPC");
    public static final String DISCORD_APP_ID = "1569068540512702594";
    public static final String MC_VERSION = "26.2";

    private static final DiscordRpcManager INSTANCE = new DiscordRpcManager();

    private final long sessionStartEpochSec = System.currentTimeMillis() / 1000L;
    private volatile boolean running = false;
    private Thread workerThread;
    private IpcConnection connection;
    private String lastSentState = "";
    private boolean lastEnabledState = false;

    private DiscordRpcManager() {}

    public static DiscordRpcManager getInstance() {
        return INSTANCE;
    }

    public synchronized void start() {
        if (this.running) {
            return;
        }
        this.running = true;
        this.workerThread = new Thread(this::runLoop, "Meowy-DiscordRPC-Worker");
        this.workerThread.setDaemon(true);
        this.workerThread.start();
    }

    public synchronized void stop() {
        this.running = false;
        closeConnectionQuietly();
    }

    private void runLoop() {
        while (this.running) {
            try {
                boolean enabled = MeowyConfig.get().discordRpcEnabled;
                if (!enabled) {
                    if (this.connection != null) {
                        sendClearActivity();
                        closeConnectionQuietly();
                        this.lastSentState = "";
                    }
                    this.lastEnabledState = false;
                    Thread.sleep(2500L);
                    continue;
                }

                if (this.connection == null) {
                    this.connection = openIpcConnection();
                    if (this.connection != null) {
                        sendHandshake();
                        readFrame();
                        this.lastSentState = "";
                    } else {
                        Thread.sleep(6000L);
                        continue;
                    }
                }

                String currentState = computeCurrentGameState();
                if (!currentState.equals(this.lastSentState) || !this.lastEnabledState) {
                    sendSetActivity(currentState);
                    this.lastSentState = currentState;
                    this.lastEnabledState = true;
                }

                Thread.sleep(4000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                closeConnectionQuietly();
                this.lastSentState = "";
                try {
                    Thread.sleep(5000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        closeConnectionQuietly();
    }

    private static String computeCurrentGameState() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return "Starting Minecraft " + MC_VERSION;
        }
        if (client.level != null) {
            if (client.isLocalServer()) {
                return "Playing Singleplayer";
            }
            return "Playing Multiplayer";
        }
        return "In Menus";
    }

    private void sendHandshake() throws IOException {
        JsonObject handshake = new JsonObject();
        handshake.addProperty("v", 1);
        handshake.addProperty("client_id", DISCORD_APP_ID);
        writeFrame(0, handshake.toString());
    }

    private void sendSetActivity(String stateText) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("cmd", "SET_ACTIVITY");
        root.addProperty("nonce", UUID.randomUUID().toString());

        JsonObject args = new JsonObject();
        args.addProperty("pid", ProcessHandle.current().pid());

        JsonObject activity = new JsonObject();
        activity.addProperty("details", "Minecraft " + MC_VERSION);
        activity.addProperty("state", stateText);

        JsonObject timestamps = new JsonObject();
        timestamps.addProperty("start", this.sessionStartEpochSec);
        activity.add("timestamps", timestamps);

        args.add("activity", activity);
        root.add("args", args);

        writeFrame(1, root.toString());
        readFrame();
    }

    private void sendClearActivity() {
        try {
            if (this.connection == null) return;
            JsonObject root = new JsonObject();
            root.addProperty("cmd", "SET_ACTIVITY");
            root.addProperty("nonce", UUID.randomUUID().toString());

            JsonObject args = new JsonObject();
            args.addProperty("pid", ProcessHandle.current().pid());
            root.add("args", args);

            writeFrame(1, root.toString());
        } catch (Exception ignored) {}
    }

    private void writeFrame(int opcode, String jsonPayload) throws IOException {
        if (this.connection == null) return;
        byte[] payloadBytes = jsonPayload.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(8 + payloadBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(opcode);
        buffer.putInt(payloadBytes.length);
        buffer.put(payloadBytes);
        buffer.flip();
        this.connection.write(buffer.array());
    }

    private void readFrame() throws IOException {
        if (this.connection == null) return;
        byte[] header = new byte[8];
        this.connection.readFully(header);
        ByteBuffer headerBuf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int opcode = headerBuf.getInt();
        int length = headerBuf.getInt();
        if (length > 0 && length <= 65536) {
            byte[] body = new byte[length];
            this.connection.readFully(body);
        }
    }

    private void closeConnectionQuietly() {
        if (this.connection != null) {
            try {
                this.connection.close();
            } catch (IOException ignored) {}
            this.connection = null;
        }
    }

    private static IpcConnection openIpcConnection() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            for (int i = 0; i < 10; i++) {
                String pipePath = "\\\\.\\pipe\\discord-ipc-" + i;
                try {
                    RandomAccessFile raf = new RandomAccessFile(pipePath, "rw");
                    return new WindowsPipeConnection(raf);
                } catch (IOException ignored) {}
            }
            return null;
        } else {
            String[] tempDirs = {
                System.getenv("XDG_RUNTIME_DIR"),
                System.getenv("TMPDIR"),
                System.getenv("TMP"),
                System.getenv("TEMP"),
                "/tmp"
            };
            for (String dir : tempDirs) {
                if (dir == null || dir.isBlank()) continue;
                for (int i = 0; i < 10; i++) {
                    Path socketPath = Path.of(dir, "discord-ipc-" + i);
                    if (Files.exists(socketPath)) {
                        try {
                            SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
                            channel.connect(UnixDomainSocketAddress.of(socketPath));
                            return new UnixSocketConnection(channel);
                        } catch (IOException ignored) {}
                    }
                }
            }
            return null;
        }
    }

    private interface IpcConnection extends Closeable {
        void write(byte[] data) throws IOException;
        void readFully(byte[] dest) throws IOException;
    }

    private static final class WindowsPipeConnection implements IpcConnection {
        private final RandomAccessFile pipe;

        WindowsPipeConnection(RandomAccessFile pipe) {
            this.pipe = pipe;
        }

        @Override
        public void write(byte[] data) throws IOException {
            this.pipe.write(data);
        }

        @Override
        public void readFully(byte[] dest) throws IOException {
            this.pipe.readFully(dest);
        }

        @Override
        public void close() throws IOException {
            this.pipe.close();
        }
    }

    private static final class UnixSocketConnection implements IpcConnection {
        private final SocketChannel channel;

        UnixSocketConnection(SocketChannel channel) {
            this.channel = channel;
        }

        @Override
        public void write(byte[] data) throws IOException {
            ByteBuffer buf = ByteBuffer.wrap(data);
            while (buf.hasRemaining()) {
                this.channel.write(buf);
            }
        }

        @Override
        public void readFully(byte[] dest) throws IOException {
            ByteBuffer buf = ByteBuffer.wrap(dest);
            while (buf.hasRemaining()) {
                int read = this.channel.read(buf);
                if (read < 0) {
                    throw new IOException("EOF on Discord IPC socket");
                }
            }
        }

        @Override
        public void close() throws IOException {
            this.channel.close();
        }
    }
}
