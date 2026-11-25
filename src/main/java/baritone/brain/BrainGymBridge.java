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
import java.util.concurrent.locks.LockSupport;

/**
 * Optional localhost loopback ({@code 127.0.0.1}) JSONL bridge allowing an external Python RL
 * process or live dashboard to inspect {@value BrainSchema#OBS_DIM}-float observations and
 * supply live {@link BrainDecision} overrides without modifying Minecraft runtime internals.
 */
public final class BrainGymBridge {

    private static final int MAX_ACTION_AGE_TICKS = 4;
    private static final int MAX_COMMAND_CHARS = 4096;

    private record Observation(long step, String line) {}
    private record Response(long step, BrainDecision decision) {}

    private static final class Client {
        private final Socket socket;
        private final AtomicReference<Observation> latest = new AtomicReference<>();
        private final AtomicReference<Response> response = new AtomicReference<>();
        private final AtomicBoolean pong = new AtomicBoolean();
        private final Thread writer;
        private volatile long lastSent = -1L;
        private volatile long firstValidStep;
        private long lastAccepted = -1L;

        Client(Socket socket) {
            this.socket = socket;
            this.writer = Thread.ofVirtual().name("pathadol-brain-gym-output").unstarted(this::writeLoop);
        }

        void writeLoop() {
            try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                while (!socket.isClosed()) {
                    Observation next = latest.getAndSet(null);
                    boolean reply = pong.getAndSet(false);
                    if (next == null && !reply) {
                        LockSupport.park();
                        continue;
                    }
                    if (reply) {
                        JsonObject ack = new JsonObject();
                        ack.addProperty("pong", true);
                        ack.addProperty("schema", BrainSchema.SCHEMA_VERSION);
                        out.write(ack.toString());
                        out.newLine();
                    }
                    if (next != null) {
                        out.write(next.line());
                        out.newLine();
                        lastSent = next.step();
                    }
                    out.flush();
                }
            } catch (IOException ignored) {
                // Closing the socket also wakes the reader so the listener can accept another client.
            } finally {
                close();
            }
        }

        void close() {
            closeQuietly(socket);
            LockSupport.unpark(writer);
        }
    }

    private volatile ServerSocket serverSocket;
    private volatile Client client;
    private volatile String clientStatus = "disconnected";
    private volatile int boundPort = -1;

    public synchronized boolean start(int port) throws IOException {
        if (isRunning()) {
            if (boundPort == port) {
                return true;
            }
            stop();
        }
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        ServerSocket listening = new ServerSocket(port, 4, loopback);
        serverSocket = listening;
        boundPort = listening.getLocalPort();
        clientStatus = "listening on 127.0.0.1:" + boundPort;
        Thread.ofVirtual().name("pathadol-brain-gym-bridge").start(() -> acceptLoop(listening));
        return true;
    }

    public synchronized void stop() {
        ServerSocket listening = serverSocket;
        serverSocket = null;
        closeQuietly(listening);
        Client current = client;
        client = null;
        if (current != null) {
            current.close();
        }
        boundPort = -1;
        clientStatus = "stopped";
    }

    public boolean isRunning() {
        return serverSocket != null;
    }

    public boolean isClientConnected() {
        Client current = client;
        return current != null && !current.socket.isClosed();
    }

    public int getPort() {
        return boundPort;
    }

    public String getStatus() {
        return clientStatus;
    }

    /**
     * Publishes the latest observation without waiting for the socket and consumes a fresh response once.
     * Clients echo the observation's step when submitting an action.
     */
    public BrainDecision exchangeStep(float[] obs, boolean[] mask, float reward, boolean done, long tick) {
        Client current = client;
        if (current == null || current.socket.isClosed()) {
            return null;
        }
        if (done) {
            current.firstValidStep = tick + 1;
        }
        if (obs != null) {
            current.latest.set(new Observation(tick, buildObsLine(obs, mask, reward, done, tick)));
            LockSupport.unpark(current.writer);
        }
        Response reply = current.response.getAndSet(null);
        return !done && reply != null && reply.step() >= current.firstValidStep
                && tick - reply.step() <= MAX_ACTION_AGE_TICKS ? reply.decision() : null;
    }

    private void acceptLoop(ServerSocket listening) {
        while (!listening.isClosed()) {
            try (Socket socket = listening.accept()) {
                socket.setTcpNoDelay(true);
                Client connected = new Client(socket);
                synchronized (this) {
                    if (serverSocket != listening) {
                        return;
                    }
                    client = connected;
                    clientStatus = "connected";
                    connected.writer.start();
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = readCommand(reader)) != null) {
                        parseClientCommand(connected, line);
                    }
                } finally {
                    connected.close();
                    synchronized (this) {
                        if (client == connected) {
                            client = null;
                            clientStatus = "listening on 127.0.0.1:" + boundPort;
                        }
                    }
                }
            } catch (IOException ignored) {
                // A stopped listener exits; a disconnected client leaves the listener available.
            }
        }
    }

    private static String readCommand(BufferedReader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        int next;
        while ((next = reader.read()) != -1 && next != '\n') {
            if (line.length() >= MAX_COMMAND_CHARS) {
                throw new IOException("Gym command exceeded " + MAX_COMMAND_CHARS + " characters");
            }
            line.append((char) next);
        }
        return next == -1 && line.isEmpty() ? null : line.toString();
    }

    private static void parseClientCommand(Client client, String line) {
        try {
            JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
            if (obj.has("cmd") && "ping".equalsIgnoreCase(obj.get("cmd").getAsString())) {
                client.pong.set(true);
                LockSupport.unpark(client.writer);
                return;
            }
            if (!obj.has("maneuver") || !obj.has("step")) {
                return;
            }
            long step = obj.get("step").getAsLong();
            long sent = client.lastSent;
            if (step < client.firstValidStep || step <= client.lastAccepted || step > sent || sent - step > MAX_ACTION_AGE_TICKS) {
                return;
            }

            int mIdx = obj.get("maneuver").getAsInt();
            int cIdx = obj.has("combatMode") ? obj.get("combatMode").getAsInt() : 0;
            if (mIdx < 0 || mIdx >= BrainActionSpace.NUM_MANEUVERS || cIdx < 0 || cIdx >= BrainActionSpace.NUM_COMBAT_MODES) {
                return;
            }
            float[] sliders = {0.55f, 0.72f, 0.45f, 0.55f};
            if (obj.has("sliders") && obj.get("sliders").isJsonArray()) {
                JsonArray arr = obj.getAsJsonArray("sliders");
                for (int i = 0; i < Math.min(sliders.length, arr.size()); i++) {
                    sliders[i] = arr.get(i).getAsFloat();
                }
            }
            float val = obj.has("value") ? obj.get("value").getAsFloat() : 0.0f;
            float conf = obj.has("confidence") ? obj.get("confidence").getAsFloat() : 1.0f;
            if (Float.isFinite(val) && Float.isFinite(conf)) {
                BrainDecision decision = new BrainDecision(
                        Maneuver.fromIndex(mIdx), StyleSliders.fromArray(sliders), CombatMode.fromIndex(cIdx), val, conf);
                client.lastAccepted = step;
                client.response.set(new Response(step, decision));
            }
        } catch (RuntimeException ignored) {
            // Drop malformed commands without terminating the connection.
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
