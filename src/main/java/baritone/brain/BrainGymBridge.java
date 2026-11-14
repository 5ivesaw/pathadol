/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.brain;

import baritone.brain.BrainActionSpace.BrainDecision;
import baritone.brain.BrainActionSpace.CombatMode;
import baritone.brain.BrainActionSpace.Maneuver;
import baritone.brain.BrainActionSpace.StyleSliders;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Optional localhost loopback ({@code 127.0.0.1}) JSONL bridge allowing an external Python RL
 * process or live dashboard to inspect {@value BrainSchema#OBS_DIM}-float observations and
 * supply live {@link BrainDecision} overrides without modifying Minecraft runtime internals.
 */
public final class BrainGymBridge {

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<String> latestObsJson = new AtomicReference<>();
    private final AtomicReference<BrainDecision> pendingDecision = new AtomicReference<>();
    private final AtomicReference<String> clientStatus = new AtomicReference<>("disconnected");

    private volatile ServerSocket serverSocket;
    private volatile Socket clientSocket;
    private volatile BufferedWriter clientWriter;
    private Thread acceptThread;
    private int boundPort = -1;

    public synchronized boolean start(int port) throws IOException {
        if (running.get()) {
            if (boundPort == port) {
                return true;
            }
            stop();
        }
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        ServerSocket ss = new ServerSocket(port, 4, loopback);
        ss.setReuseAddress(true);
        this.serverSocket = ss;
        this.boundPort = port;
        this.running.set(true);
        this.clientStatus.set("listening on 127.0.0.1:" + port);

        Thread t = new Thread(this::acceptLoop, "pathadol-brain-gym-bridge");
        t.setDaemon(true);
        this.acceptThread = t;
        t.start();
        return true;
    }

    public synchronized void stop() {
        running.set(false);
        pendingDecision.set(null);
        latestObsJson.set(null);
        closeQuietly(clientSocket);
        clientSocket = null;
        clientWriter = null;
        closeQuietly(serverSocket);
        serverSocket = null;
        boundPort = -1;
        clientStatus.set("stopped");
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isClientConnected() {
        return clientSocket != null && !clientSocket.isClosed() && clientSocket.isConnected();
    }

    public int getPort() {
        return boundPort;
    }

    public String getStatus() {
        return clientStatus.get();
    }

    /**
     * Publishes the current tick observation/reward to a connected localhost Python client (non-blocking)
     * and returns any queued remote action if available.
     */
    public BrainDecision exchangeStep(float[] obs, boolean[] mask, float reward, boolean done, long tick) {
        if (!running.get()) {
            return null;
        }
        if (obs != null && isClientConnected()) {
            String line = buildObsLine(obs, mask, reward, done, tick);
            latestObsJson.set(line);
            BufferedWriter w = clientWriter;
            if (w != null) {
                try {
                    w.write(line);
                    w.newLine();
                    w.flush();
                } catch (IOException e) {
                    closeQuietly(clientSocket);
                    clientSocket = null;
                    clientWriter = null;
                    clientStatus.set("listening on 127.0.0.1:" + boundPort + " (client dropped)");
                }
            }
        }
        return pendingDecision.get();
    }

    private void acceptLoop() {
        while (running.get()) {
            ServerSocket ss = this.serverSocket;
            if (ss == null || ss.isClosed()) {
                break;
            }
            try {
                Socket sock = ss.accept();
                sock.setTcpNoDelay(true);
                closeQuietly(this.clientSocket);
                this.clientSocket = sock;
                this.clientWriter = new BufferedWriter(new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8));
                this.clientStatus.set("connected (" + sock.getRemoteSocketAddress() + ")");
                readClientLoop(sock);
            } catch (IOException ignored) {
                if (!running.get()) {
                    break;
                }
            }
        }
    }

    private void readClientLoop(Socket sock) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                parseClientCommand(line);
            }
        } catch (IOException ignored) {
        } finally {
            pendingDecision.set(null);
            closeQuietly(sock);
            if (clientSocket == sock) {
                clientSocket = null;
                clientWriter = null;
                clientStatus.set(running.get() ? "listening on 127.0.0.1:" + boundPort : "stopped");
            }
        }
    }

    private void parseClientCommand(String line) {
        try {
            JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
            if (obj.has("cmd") && "ping".equalsIgnoreCase(obj.get("cmd").getAsString())) {
                BufferedWriter w = clientWriter;
                if (w != null) {
                    JsonObject pong = new JsonObject();
                    pong.addProperty("pong", true);
                    pong.addProperty("schema", BrainSchema.SCHEMA_VERSION);
                    w.write(pong.toString());
                    w.newLine();
                    w.flush();
                }
                return;
            }
            if (!obj.has("maneuver")) {
                return;
            }
            int mIdx = obj.get("maneuver").getAsInt();
            int cIdx = obj.has("combatMode") ? obj.get("combatMode").getAsInt() : 0;
            float[] sliders = new float[]{0.55f, 0.72f, 0.45f, 0.55f};
            if (obj.has("sliders") && obj.get("sliders").isJsonArray()) {
                JsonArray arr = obj.getAsJsonArray("sliders");
                for (int i = 0; i < Math.min(4, arr.size()); i++) {
                    sliders[i] = arr.get(i).getAsFloat();
                }
            }
            float val = obj.has("value") ? obj.get("value").getAsFloat() : 0.0f;
            float conf = obj.has("confidence") ? obj.get("confidence").getAsFloat() : 1.0f;
            pendingDecision.set(new BrainDecision(
                    Maneuver.fromIndex(mIdx),
                    StyleSliders.fromArray(sliders),
                    CombatMode.fromIndex(cIdx),
                    val,
                    conf
            ));
        } catch (Exception ignored) {
        }
    }

    private static String buildObsLine(float[] obs, boolean[] mask, float reward, boolean done, long tick) {
        JsonObject root = new JsonObject();
        root.addProperty("type", "obs");
        root.addProperty("schema", BrainSchema.SCHEMA_VERSION);
        root.addProperty("step", tick);
        root.addProperty("reward", reward);
        root.addProperty("done", done);

        JsonArray obsArr = new JsonArray(obs.length);
        for (float v : obs) {
            obsArr.add(Math.round(v * 10000.0f) / 10000.0f);
        }
        root.add("obs", obsArr);

        if (mask != null) {
            JsonArray maskArr = new JsonArray(mask.length);
            for (boolean b : mask) {
                maskArr.add(b ? 1 : 0);
            }
            root.add("mask", maskArr);
        }
        return root.toString();
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void closeQuietly(ServerSocket ss) {
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
            }
        }
    }
}
