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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Zero-dependency pure-Java neural policy inference engine supporting PyTorch-exported
 * {@code .pbrain} bundles (feature standardization + Encoder Linear/LayerNorm/Activation +
 * optional PyTorch-parity {@code GRUCell} + 3 tactical heads + critic value readout).
 *
 * <p>Avoids 75+ MB JNI native runtimes while executing compact Bedwars macro-policies in
 * well under 1 millisecond on laptop CPUs.
 */
public final class NeuralPolicyEngine {

    public enum Activation {
        SILU,
        RELU,
        TANH;

        public static Activation fromString(String s) {
            if (s == null) return SILU;
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "relu" -> RELU;
                case "tanh" -> TANH;
                default -> SILU;
            };
        }
    }

    public record LinearLayer(
            int outDim,
            int inDim,
            float[] weight,
            float[] bias,
            float[] lnWeight,
            float[] lnBias
    ) {}

    public record GruCellWeights(
            int inputSize,
            int hiddenSize,
            float[] weightIh,
            float[] biasIh,
            float[] weightHh,
            float[] biasHh
    ) {}

    private final int obsDim;
    private final Activation activation;
    private final float[] obsMean;
    private final float[] obsStd;
    private final List<LinearLayer> encoderLayers;
    private final GruCellWeights gru;
    private final LinearLayer maneuverHead;
    private final LinearLayer sliderHead;
    private final LinearLayer combatHead;
    private final LinearLayer valueHead;
    private final String modelName;

    private float[] hiddenState;
    private long lastInferenceMicros = 0L;

    public NeuralPolicyEngine(
            String modelName,
            int obsDim,
            Activation activation,
            float[] obsMean,
            float[] obsStd,
            List<LinearLayer> encoderLayers,
            GruCellWeights gru,
            LinearLayer maneuverHead,
            LinearLayer sliderHead,
            LinearLayer combatHead,
            LinearLayer valueHead
    ) {
        this.modelName = modelName != null ? modelName : "pathadol-brain";
        this.obsDim = obsDim;
        this.activation = activation != null ? activation : Activation.SILU;
        this.obsMean = obsMean;
        this.obsStd = obsStd;
        this.encoderLayers = encoderLayers != null ? List.copyOf(encoderLayers) : List.of();
        this.gru = gru;
        this.maneuverHead = maneuverHead;
        this.sliderHead = sliderHead;
        this.combatHead = combatHead;
        this.valueHead = valueHead;
        this.hiddenState = gru != null ? new float[gru.hiddenSize()] : null;
    }

    public String getModelName() {
        return modelName;
    }

    public long getLastInferenceMicros() {
        return lastInferenceMicros;
    }

    public synchronized void resetHiddenState() {
        if (hiddenState != null) {
            Arrays.fill(hiddenState, 0.0f);
        }
    }

    /**
     * Loads a {@code .pbrain} JSON weights bundle written by the Colab PyTorch exporter.
     */
    public static NeuralPolicyEngine loadFromFile(Path path) throws IOException {
        String raw = Files.readString(path, StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
        return fromJson(root);
    }

    public static NeuralPolicyEngine fromJson(JsonObject root) {
        int schemaVer = root.has("schema_version") ? root.get("schema_version").getAsInt() : 0;
        if (schemaVer != BrainSchema.SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported .pbrain schema_version=" + schemaVer + " (expected " + BrainSchema.SCHEMA_VERSION + ")");
        }
        int obsDim = root.has("obs_dim") ? root.get("obs_dim").getAsInt() : BrainSchema.OBS_DIM;
        if (obsDim != BrainSchema.OBS_DIM) {
            throw new IllegalArgumentException("Model obs_dim=" + obsDim + " mismatches runtime OBS_DIM=" + BrainSchema.OBS_DIM);
        }
        String name = root.has("name") ? root.get("name").getAsString() : "custom-pbrain";
        Activation act = Activation.fromString(root.has("activation") ? root.get("activation").getAsString() : "silu");
        float[] mean = root.has("obs_mean") ? parseFloatArray(root.get("obs_mean"), obsDim) : null;
        float[] std = root.has("obs_std") ? parseFloatArray(root.get("obs_std"), obsDim) : null;

        List<LinearLayer> enc = new ArrayList<>();
        if (root.has("encoder_layers") && root.get("encoder_layers").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("encoder_layers")) {
                enc.add(parseLinearLayer(el.getAsJsonObject()));
            }
        }

        GruCellWeights gru = null;
        if (root.has("gru") && root.get("gru").isJsonObject()) {
            JsonObject g = root.getAsJsonObject("gru");
            int inSize = g.get("input_size").getAsInt();
            int hidSize = g.get("hidden_size").getAsInt();
            gru = new GruCellWeights(
                    inSize,
                    hidSize,
                    parseFloatArray(g.get("weight_ih"), 3 * hidSize * inSize),
                    parseFloatArray(g.get("bias_ih"), 3 * hidSize),
                    parseFloatArray(g.get("weight_hh"), 3 * hidSize * hidSize),
                    parseFloatArray(g.get("bias_hh"), 3 * hidSize)
            );
        }

        JsonObject heads = root.getAsJsonObject("heads");
        LinearLayer mHead = parseLinearLayer(heads.getAsJsonObject("maneuver"));
        LinearLayer sHead = parseLinearLayer(heads.getAsJsonObject("sliders"));
        LinearLayer cHead = parseLinearLayer(heads.getAsJsonObject("combat"));
        LinearLayer vHead = heads.has("value") ? parseLinearLayer(heads.getAsJsonObject("value")) : null;

        return new NeuralPolicyEngine(name, obsDim, act, mean, std, enc, gru, mHead, sHead, cHead, vHead);
    }

    /**
     * Runs one forward tick of the policy (updating GRU recurrent state if configured)
     * and returns masked greedy decision + confidence + critic value estimate.
     */
    public synchronized BrainDecision infer(float[] rawObs, boolean[] validManeuverMask) {
        if (rawObs == null || rawObs.length != obsDim) {
            throw new IllegalArgumentException("Expected observation length " + obsDim);
        }
        long t0 = System.nanoTime();

        float[] cur = new float[obsDim];
        for (int i = 0; i < obsDim; i++) {
            float v = rawObs[i];
            if (obsMean != null) v -= obsMean[i];
            if (obsStd != null) v /= Math.max(1e-4f, obsStd[i]);
            cur[i] = v;
        }

        for (LinearLayer layer : encoderLayers) {
            cur = forwardLinear(cur, layer);
            if (layer.lnWeight() != null && layer.lnBias() != null) {
                cur = applyLayerNorm(cur, layer.lnWeight(), layer.lnBias());
            }
            applyActivationInPlace(cur, activation);
        }

        if (gru != null) {
            hiddenState = forwardGruCell(cur, hiddenState, gru);
            cur = hiddenState;
        }

        float[] maneuverLogits = forwardLinear(cur, maneuverHead);
        float[] sliderRaw = forwardLinear(cur, sliderHead);
        float[] combatLogits = forwardLinear(cur, combatHead);
        float value = (valueHead != null) ? forwardLinear(cur, valueHead)[0] : 0.0f;

        // Apply hard feasibility mask on maneuver logits & compute confidence
        int chosenManeuverIdx = selectMaskedArgmax(maneuverLogits, validManeuverMask);
        float confidence = computeMaskedSoftmaxProb(maneuverLogits, validManeuverMask, chosenManeuverIdx);

        float[] sliderVals = new float[BrainActionSpace.NUM_SLIDERS];
        for (int i = 0; i < sliderVals.length && i < sliderRaw.length; i++) {
            sliderVals[i] = sigmoid(sliderRaw[i]);
        }

        int chosenCombatIdx = selectMaskedArgmax(combatLogits, null);
        this.lastInferenceMicros = Math.max(1L, (System.nanoTime() - t0) / 1_000L);

        return new BrainDecision(
                Maneuver.fromIndex(chosenManeuverIdx),
                StyleSliders.fromArray(sliderVals),
                CombatMode.fromIndex(chosenCombatIdx),
                value,
                confidence
        );
    }

    public static float[] forwardLinear(float[] x, LinearLayer layer) {
        int outDim = layer.outDim();
        int inDim = layer.inDim();
        float[] w = layer.weight();
        float[] b = layer.bias();
        float[] y = new float[outDim];
        for (int o = 0; o < outDim; o++) {
            double sum = b != null ? b[o] : 0.0;
            int rowBase = o * inDim;
            for (int i = 0; i < inDim; i++) {
                sum += w[rowBase + i] * x[i];
            }
            y[o] = (float) sum;
        }
        return y;
    }

    public static float[] applyLayerNorm(float[] x, float[] gamma, float[] beta) {
        int n = x.length;
        double mean = 0.0;
        for (float v : x) {
            mean += v;
        }
        mean /= n;
        double var = 0.0;
        for (float v : x) {
            double d = v - mean;
            var += d * d;
        }
        var /= n;
        double invStd = 1.0 / Math.sqrt(var + 1e-5);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = (float) (((x[i] - mean) * invStd) * gamma[i] + beta[i]);
        }
        return out;
    }

    /**
     * Exact PyTorch {@code nn.GRUCell} forward equations with {@code (r, z, n)} gate packing.
     */
    public static float[] forwardGruCell(float[] x, float[] hPrev, GruCellWeights g) {
        int h = g.hiddenSize();
        int in = g.inputSize();
        float[] gi = matVecAdd(g.weightIh(), g.biasIh(), x, 3 * h, in);
        float[] gh = matVecAdd(g.weightHh(), g.biasHh(), hPrev, 3 * h, h);

        float[] hNext = new float[h];
        for (int i = 0; i < h; i++) {
            float r = sigmoid(gi[i] + gh[i]);
            float z = sigmoid(gi[h + i] + gh[h + i]);
            float n = (float) Math.tanh(gi[2 * h + i] + r * gh[2 * h + i]);
            hNext[i] = (1.0f - z) * n + z * hPrev[i];
        }
        return hNext;
    }

    private static float[] matVecAdd(float[] w, float[] b, float[] x, int rows, int cols) {
        float[] y = new float[rows];
        for (int r = 0; r < rows; r++) {
            double sum = b != null ? b[r] : 0.0;
            int base = r * cols;
            for (int c = 0; c < cols; c++) {
                sum += w[base + c] * x[c];
            }
            y[r] = (float) sum;
        }
        return y;
    }

    private static void applyActivationInPlace(float[] v, Activation act) {
        for (int i = 0; i < v.length; i++) {
            float x = v[i];
            v[i] = switch (act) {
                case RELU -> x > 0.0f ? x : 0.0f;
                case TANH -> (float) Math.tanh(x);
                case SILU -> x * sigmoid(x);
            };
        }
    }

    private static int selectMaskedArgmax(float[] logits, boolean[] mask) {
        int bestIdx = 0;
        float bestVal = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < logits.length; i++) {
            if (mask != null && i < mask.length && !mask[i]) {
                continue;
            }
            if (logits[i] > bestVal) {
                bestVal = logits[i];
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    private static float computeMaskedSoftmaxProb(float[] logits, boolean[] mask, int targetIdx) {
        float maxVal = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < logits.length; i++) {
            if (mask != null && i < mask.length && !mask[i]) {
                continue;
            }
            if (logits[i] > maxVal) {
                maxVal = logits[i];
            }
        }
        if (!Float.isFinite(maxVal)) {
            return 1.0f;
        }
        double sumExp = 0.0;
        double targetExp = 0.0;
        for (int i = 0; i < logits.length; i++) {
            if (mask != null && i < mask.length && !mask[i]) {
                continue;
            }
            double e = Math.exp(logits[i] - maxVal);
            sumExp += e;
            if (i == targetIdx) {
                targetExp = e;
            }
        }
        return sumExp <= 0.0 ? 1.0f : (float) (targetExp / sumExp);
    }

    private static float sigmoid(float x) {
        if (x >= 20.0f) return 1.0f;
        if (x <= -20.0f) return 0.0f;
        return (float) (1.0 / (1.0 + Math.exp(-x)));
    }

    private static LinearLayer parseLinearLayer(JsonObject obj) {
        int outDim = obj.get("out_dim").getAsInt();
        int inDim = obj.get("in_dim").getAsInt();
        float[] weight = parseFloatArray(obj.get("weight"), outDim * inDim);
        float[] bias = obj.has("bias") ? parseFloatArray(obj.get("bias"), outDim) : null;
        float[] lnW = obj.has("ln_weight") ? parseFloatArray(obj.get("ln_weight"), outDim) : null;
        float[] lnB = obj.has("ln_bias") ? parseFloatArray(obj.get("ln_bias"), outDim) : null;
        return new LinearLayer(outDim, inDim, weight, bias, lnW, lnB);
    }

    private static float[] parseFloatArray(JsonElement elem, int expectedLen) {
        if (elem.isJsonArray()) {
            JsonArray arr = elem.getAsJsonArray();
            if (arr.size() != expectedLen) {
                throw new IllegalArgumentException("Expected tensor len=" + expectedLen + ", got " + arr.size());
            }
            float[] out = new float[expectedLen];
            for (int i = 0; i < expectedLen; i++) {
                out[i] = arr.get(i).getAsFloat();
            }
            return out;
        }
        if (elem.isJsonObject()) {
            JsonObject obj = elem.getAsJsonObject();
            String b64 = obj.get("f32_b64").getAsString();
            byte[] raw = Base64.getDecoder().decode(b64);
            if (raw.length != expectedLen * 4) {
                throw new IllegalArgumentException("Expected " + (expectedLen * 4) + " bytes in f32_b64, got " + raw.length);
            }
            ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            float[] out = new float[expectedLen];
            for (int i = 0; i < expectedLen; i++) {
                out[i] = buf.getFloat();
            }
            return out;
        }
        throw new IllegalArgumentException("Unsupported tensor JSON element: " + elem);
    }
}
