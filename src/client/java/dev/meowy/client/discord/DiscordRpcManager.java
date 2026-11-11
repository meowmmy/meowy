package dev.meowy.client.discord;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
 * (\.\pipe\discord-ipc-0..9) or Unix Domain Sockets (including Flatpak/Snap/macOS temp paths).
 */
public final class DiscordRpcManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Meowy-DiscordRPC");
    public static final String DISCORD_APP_ID = "1569068540512702594";
    public static final String MC_VERSION = "26.2";

    private static final int OP_HANDSHAKE = 0;
    private static final int OP_FRAME     = 1;
    private static final int OP_CLOSE     = 2;
    private static final int OP_PING      = 3;
    private static final int OP_PONG      = 4;

    private static final long REFRESH_INTERVAL_MS = 12_000L;

    private static final DiscordRpcManager INSTANCE = new DiscordRpcManager();

    private final long sessionStartEpochSec = System.currentTimeMillis() / 1000L;
    private volatile boolean running = false;
    private Thread workerThread;
    private IpcConnection connection;
    private String lastSentState = "";
    private boolean lastEnabledState = false;
    private long lastActivityUpdateMs = 0L;

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
        if (this.connection != null) {
            sendClearActivity();
            closeConnectionQuietly();
        }
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
                        this.lastActivityUpdateMs = 0L;
                        LOGGER.info("Discord Rich Presence disabled; cleared activity and closed IPC.");
                    }
                    this.lastEnabledState = false;
                    Thread.sleep(1000L);
                    continue;
                }

                if (this.connection == null) {
                    this.connection = openAndHandshakeIpcConnection();
                    if (this.connection != null) {
                        this.lastSentState = "";
                        this.lastActivityUpdateMs = 0L;
                    } else {
                        Thread.sleep(4000L);
                        continue;
                    }
                }

                // Drain any incoming PING / CLOSE frames from Discord so the IPC pipe stays healthy
                drainIncomingFrames();

                long nowMs = System.currentTimeMillis();
                String currentState = computeCurrentGameState();
                if (!currentState.equals(this.lastSentState)
                    || !this.lastEnabledState
                    || (nowMs - this.lastActivityUpdateMs) >= REFRESH_INTERVAL_MS) {
                    sendSetActivity(currentState);
                    this.lastSentState = currentState;
                    this.lastEnabledState = true;
                    this.lastActivityUpdateMs = nowMs;
                }

                Thread.sleep(1000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                closeConnectionQuietly();
                this.lastSentState = "";
                this.lastActivityUpdateMs = 0L;
                try {
                    Thread.sleep(3000L);
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

    private static void sendHandshakeOn(IpcConnection conn) throws IOException {
        JsonObject handshake = new JsonObject();
        handshake.addProperty("v", 1);
        handshake.addProperty("client_id", DISCORD_APP_ID);
        writeFrameOn(conn, OP_HANDSHAKE, handshake.toString());
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
        activity.addProperty("instance", true);

        args.add("activity", activity);
        root.add("args", args);

        writeFrameOn(this.connection, OP_FRAME, root.toString());
        readNextFrameBlocking(this.connection);
    }

    private void sendClearActivity() {
        try {
            if (this.connection == null) return;
            JsonObject root = new JsonObject();
            root.addProperty("cmd", "SET_ACTIVITY");
            root.addProperty("nonce", UUID.randomUUID().toString());

            JsonObject args = new JsonObject();
            args.addProperty("pid", ProcessHandle.current().pid());
            args.add("activity", JsonNull.INSTANCE);
            root.add("args", args);

            writeFrameOn(this.connection, OP_FRAME, root.toString());
            if (this.connection.available() >= 8) {
                readNextFrameBlocking(this.connection);
            }
        } catch (Exception ignored) {}
    }

    private void drainIncomingFrames() throws IOException {
        if (this.connection == null) return;
        while (this.connection.available() >= 8) {
            readNextFrameBlocking(this.connection);
        }
    }

    private static void writeFrameOn(IpcConnection conn, int opcode, String jsonPayload) throws IOException {
        if (conn == null) return;
        byte[] payloadBytes = jsonPayload.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(8 + payloadBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(opcode);
        buffer.putInt(payloadBytes.length);
        buffer.put(payloadBytes);
        buffer.flip();
        conn.write(buffer.array());
    }

    private static String readNextFrameBlocking(IpcConnection conn) throws IOException {
        if (conn == null) return "";
        byte[] header = new byte[8];
        conn.readFully(header);
        ByteBuffer headerBuf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int opcode = headerBuf.getInt();
        int length = headerBuf.getInt();
        if (length < 0 || length > 262144) {
            throw new IOException("Invalid Discord IPC frame length: " + length);
        }
        String payload = "";
        if (length > 0) {
            byte[] body = new byte[length];
            conn.readFully(body);
            payload = new String(body, StandardCharsets.UTF_8);
        }
        if (opcode == OP_CLOSE) {
            throw new IOException("Discord closed IPC connection: " + payload);
        }
        if (opcode == OP_PING) {
            writeFrameOn(conn, OP_PONG, payload);
        }
        return payload;
    }

    private void closeConnectionQuietly() {
        if (this.connection != null) {
            try {
                this.connection.close();
            } catch (IOException ignored) {}
            this.connection = null;
        }
    }

    private static IpcConnection openAndHandshakeIpcConnection() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            for (int i = 0; i < 10; i++) {
                String pipePath = "\\\\.\\pipe\\discord-ipc-" + i;
                WindowsPipeConnection candidate = null;
                try {
                    RandomAccessFile raf = new RandomAccessFile(pipePath, "rw");
                    candidate = new WindowsPipeConnection(raf);
                    sendHandshakeOn(candidate);
                    String resp = readNextFrameBlocking(candidate);
                    if (isHandshakeReady(resp)) {
                        LOGGER.info("Connected to Discord Rich Presence on {} (App ID {})", pipePath, DISCORD_APP_ID);
                        return candidate;
                    }
                    candidate.close();
                } catch (Exception e) {
                    if (candidate != null) {
                        try { candidate.close(); } catch (IOException ignored) {}
                    }
                }
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
            String[] subPaths = {"", "app/com.discordapp.Discord", "snap.discord"};
            for (String dir : tempDirs) {
                if (dir == null || dir.isBlank()) continue;
                for (String sub : subPaths) {
                    Path base = sub.isEmpty() ? Path.of(dir) : Path.of(dir, sub);
                    for (int i = 0; i < 10; i++) {
                        Path socketPath = base.resolve("discord-ipc-" + i);
                        if (Files.exists(socketPath)) {
                            UnixSocketConnection candidate = null;
                            try {
                                SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
                                channel.connect(UnixDomainSocketAddress.of(socketPath));
                                candidate = new UnixSocketConnection(channel);
                                sendHandshakeOn(candidate);
                                String resp = readNextFrameBlocking(candidate);
                                if (isHandshakeReady(resp)) {
                                    LOGGER.info("Connected to Discord Rich Presence on {} (App ID {})", socketPath, DISCORD_APP_ID);
                                    return candidate;
                                }
                                candidate.close();
                            } catch (Exception e) {
                                if (candidate != null) {
                                    try { candidate.close(); } catch (IOException ignored) {}
                                }
                            }
                        }
                    }
                }
            }
            return null;
        }
    }

    private static boolean isHandshakeReady(String jsonResponse) {
        if (jsonResponse == null || jsonResponse.isBlank()) {
            return false;
        }
        try {
            JsonObject obj = JsonParser.parseString(jsonResponse).getAsJsonObject();
            if (obj.has("evt") && !obj.get("evt").isJsonNull()) {
                String evt = obj.get("evt").getAsString();
                if ("ERROR".equalsIgnoreCase(evt)) {
                    LOGGER.warn("Discord IPC handshake returned error: {}", jsonResponse);
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private interface IpcConnection extends Closeable {
        void write(byte[] data) throws IOException;
        void readFully(byte[] dest) throws IOException;
        int available() throws IOException;
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
        public int available() throws IOException {
            long len = this.pipe.length();
            return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, len));
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
        public int available() {
            return 0;
        }

        @Override
        public void close() throws IOException {
            this.channel.close();
        }
    }
}
