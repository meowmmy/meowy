package dev.meowy.client.skybox;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Full OptiFine / MCPatcher custom skybox layer parser and evaluator.
 */
public final class SkyboxLayer {
    public enum BlendMode {
        ADD,
        SUBTRACT,
        MULTIPLY,
        DODGE,
        BURN,
        SCREEN,
        OVERLAY,
        REPLACE,
        ALPHA;

        public static BlendMode parse(String raw) {
            if (raw == null || raw.isBlank()) return ADD;
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "subtract" -> SUBTRACT;
                case "multiply" -> MULTIPLY;
                case "dodge" -> DODGE;
                case "burn" -> BURN;
                case "screen" -> SCREEN;
                case "overlay" -> OVERLAY;
                case "replace" -> REPLACE;
                case "alpha" -> ALPHA;
                default -> ADD;
            };
        }
    }

    public record IntRange(int min, int max) {
        public boolean contains(int value) {
            return value >= this.min && value <= this.max;
        }
    }

    private final int layerIndex;
    private final Identifier textureId;
    private final BlendMode blendMode;
    private final boolean rotate;
    private final float speed;
    private final Vector3f axis;
    private final int startFadeIn;
    private final int endFadeIn;
    private final int startFadeOut;
    private final int endFadeOut;
    private final boolean weatherClear;
    private final boolean weatherRain;
    private final boolean weatherThunder;
    private final List<String> biomes;
    private final List<IntRange> heights;
    private final int daysLoop;
    private final List<IntRange> daysRanges;

    private SkyboxLayer(
        int layerIndex,
        Identifier textureId,
        BlendMode blendMode,
        boolean rotate,
        float speed,
        Vector3f axis,
        int startFadeIn,
        int endFadeIn,
        int startFadeOut,
        int endFadeOut,
        boolean weatherClear,
        boolean weatherRain,
        boolean weatherThunder,
        List<String> biomes,
        List<IntRange> heights,
        int daysLoop,
        List<IntRange> daysRanges
    ) {
        this.layerIndex = layerIndex;
        this.textureId = textureId;
        this.blendMode = blendMode;
        this.rotate = rotate;
        this.speed = speed;
        this.axis = axis;
        this.startFadeIn = startFadeIn;
        this.endFadeIn = endFadeIn;
        this.startFadeOut = startFadeOut;
        this.endFadeOut = endFadeOut;
        this.weatherClear = weatherClear;
        this.weatherRain = weatherRain;
        this.weatherThunder = weatherThunder;
        this.biomes = biomes;
        this.heights = heights;
        this.daysLoop = daysLoop;
        this.daysRanges = daysRanges;
    }

    public static SkyboxLayer parse(int layerIndex, Identifier propertiesId, Properties props) {
        String sourceProp = props.getProperty("source");
        Identifier texture = resolveSourceTexture(propertiesId, sourceProp);
        if (texture == null) {
            return null;
        }

        BlendMode blend = BlendMode.parse(props.getProperty("blend", "add"));
        boolean rotate = Boolean.parseBoolean(props.getProperty("rotate", "true").trim());
        float speed = parseFloat(props.getProperty("speed"), 1.0f);
        Vector3f axis = parseAxis(props.getProperty("axis"));

        int startFadeIn = parseTimeTicks(props.getProperty("startFadeIn"), 0);
        int endFadeIn = parseTimeTicks(props.getProperty("endFadeIn"), startFadeIn);
        int endFadeOut = parseTimeTicks(props.getProperty("endFadeOut"), 24000);
        int startFadeOut = parseTimeTicks(props.getProperty("startFadeOut"),
            normalizeTicks(endFadeOut - normalizeTicks(endFadeIn - startFadeIn)));

        String weatherRaw = props.getProperty("weather", "clear").toLowerCase(Locale.ROOT);
        boolean clear = weatherRaw.contains("clear") || weatherRaw.isBlank();
        boolean rain = weatherRaw.contains("rain");
        boolean thunder = weatherRaw.contains("thunder");

        List<String> biomes = new ArrayList<>();
        String biomesRaw = props.getProperty("biomes", "").trim();
        if (!biomesRaw.isEmpty()) {
            for (String token : biomesRaw.split("\\s+")) {
                if (!token.isBlank()) {
                    biomes.add(token.toLowerCase(Locale.ROOT));
                }
            }
        }

        List<IntRange> heights = parseRanges(props.getProperty("heights", ""));
        int daysLoop = (int) parseFloat(props.getProperty("daysLoop"), 8.0f);
        List<IntRange> daysRanges = parseRanges(props.getProperty("days", ""));

        return new SkyboxLayer(
            layerIndex,
            texture,
            blend,
            rotate,
            speed,
            axis,
            startFadeIn,
            endFadeIn,
            startFadeOut,
            endFadeOut,
            clear,
            rain,
            thunder,
            biomes,
            heights,
            Math.max(1, daysLoop),
            daysRanges
        );
    }

    public float computeAlpha(ClientLevel level, BlockPos cameraBlockPos, float partialTicks) {
        if (!this.heights.isEmpty()) {
            int y = cameraBlockPos.getY();
            boolean matchHeight = false;
            for (IntRange range : this.heights) {
                if (range.contains(y)) {
                    matchHeight = true;
                    break;
                }
            }
            if (!matchHeight) return 0.0f;
        }

        if (!this.biomes.isEmpty()) {
            Holder<Biome> biomeHolder = level.getBiome(cameraBlockPos);
            String biomeName = biomeHolder.unwrapKey()
                .map(key -> key.identifier().toString().toLowerCase(Locale.ROOT))
                .orElse("");
            String biomeShort = biomeName.contains(":") ? biomeName.substring(biomeName.indexOf(':') + 1) : biomeName;
            boolean matchBiome = false;
            for (String target : this.biomes) {
                if (target.equals(biomeName) || target.equals(biomeShort)) {
                    matchBiome = true;
                    break;
                }
            }
            if (!matchBiome) return 0.0f;
        }

        if (!this.daysRanges.isEmpty()) {
            long totalDays = level.getOverworldClockTime() / 24000L;
            int cycleDay = (int) (totalDays % this.daysLoop);
            boolean matchDay = false;
            for (IntRange range : this.daysRanges) {
                if (range.contains(cycleDay)) {
                    matchDay = true;
                    break;
                }
            }
            if (!matchDay) return 0.0f;
        }

        int timeOfDay = (int) (level.getOverworldClockTime() % 24000L);
        if (timeOfDay < 0) timeOfDay += 24000;
        float timeAlpha = computeTimeFadeAlpha(timeOfDay);
        if (timeAlpha <= 0.001f) {
            return 0.0f;
        }

        float rainLevel = level.getRainLevel(partialTicks);
        float thunderLevel = level.getThunderLevel(partialTicks);
        float clearWeight = Math.max(0.0f, 1.0f - rainLevel);
        float rainWeight = Math.max(0.0f, rainLevel - thunderLevel);
        float thunderWeight = thunderLevel;

        float weatherAlpha = 0.0f;
        if (this.weatherClear) weatherAlpha += clearWeight;
        if (this.weatherRain) weatherAlpha += rainWeight;
        if (this.weatherThunder) weatherAlpha += thunderWeight;

        return Math.clamp(timeAlpha * weatherAlpha, 0.0f, 1.0f);
    }

    private float computeTimeFadeAlpha(int timeTicks) {
        if (this.startFadeIn == this.endFadeIn && this.startFadeOut == this.endFadeOut && this.startFadeIn == this.endFadeOut) {
            return 1.0f;
        }
        int fadeInDuration = durationBetween(this.startFadeIn, this.endFadeIn);
        int fullBrightnessDuration = durationBetween(this.endFadeIn, this.startFadeOut);
        int fadeOutDuration = durationBetween(this.startFadeOut, this.endFadeOut);

        int sinceFadeInStart = durationBetween(this.startFadeIn, timeTicks);
        if (sinceFadeInStart < fadeInDuration) {
            return fadeInDuration <= 0 ? 1.0f : (float) sinceFadeInStart / (float) fadeInDuration;
        }
        int sinceFullStart = sinceFadeInStart - fadeInDuration;
        if (sinceFullStart < fullBrightnessDuration) {
            return 1.0f;
        }
        int sinceFadeOutStart = sinceFullStart - fullBrightnessDuration;
        if (sinceFadeOutStart < fadeOutDuration) {
            return fadeOutDuration <= 0 ? 0.0f : 1.0f - ((float) sinceFadeOutStart / (float) fadeOutDuration);
        }
        return 0.0f;
    }

    private static int durationBetween(int start, int end) {
        int diff = end - start;
        if (diff < 0) diff += 24000;
        return diff;
    }

    private static int normalizeTicks(int ticks) {
        int mod = ticks % 24000;
        return mod < 0 ? mod + 24000 : mod;
    }

    private static int parseTimeTicks(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String[] parts = raw.trim().split(":");
        if (parts.length < 2) return fallback;
        try {
            int hours = Integer.parseInt(parts[0].trim());
            int minutes = Integer.parseInt(parts[1].trim());
            int ticks = hours * 1000 + (int) Math.round((minutes / 60.0) * 1000.0) - 6000;
            return normalizeTicks(ticks);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Identifier resolveSourceTexture(Identifier propertiesId, String sourceProp) {
        String propPath = propertiesId.getPath();
        int lastSlash = propPath.lastIndexOf('/');
        String baseDir = lastSlash >= 0 ? propPath.substring(0, lastSlash) : "";

        if (sourceProp == null || sourceProp.isBlank()) {
            String defaultPng = propPath.endsWith(".properties")
                ? propPath.substring(0, propPath.length() - ".properties".length()) + ".png"
                : propPath + ".png";
            return Identifier.fromNamespaceAndPath(propertiesId.getNamespace(), defaultPng);
        }

        String trimmed = sourceProp.trim().replace('\\', '/');
        if (trimmed.startsWith("./")) {
            String resolved = baseDir + "/" + trimmed.substring(2);
            return Identifier.fromNamespaceAndPath(propertiesId.getNamespace(), resolved);
        }
        if (trimmed.startsWith("~/")) {
            String rootPrefix = propPath.startsWith("mcpatcher/") ? "mcpatcher/" : "optifine/";
            return Identifier.fromNamespaceAndPath(propertiesId.getNamespace(), rootPrefix + trimmed.substring(2));
        }
        if (trimmed.contains(":")) {
            return Identifier.tryParse(trimmed);
        }
        if (!trimmed.contains("/")) {
            return Identifier.fromNamespaceAndPath(propertiesId.getNamespace(), baseDir + "/" + trimmed);
        }
        return Identifier.fromNamespaceAndPath(propertiesId.getNamespace(), trimmed);
    }

    private static Vector3f parseAxis(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Vector3f(1.0f, 0.0f, 0.0f);
        }
        String[] parts = raw.trim().split("\\s+");
        if (parts.length < 3) {
            return new Vector3f(1.0f, 0.0f, 0.0f);
        }
        float x = parseFloat(parts[0], 0.0f);
        float y = parseFloat(parts[1], 0.0f);
        float z = parseFloat(parts[2], 1.0f);
        Vector3f vec = new Vector3f(z, y, -x);
        if (vec.lengthSquared() < 1.0e-5f) {
            return new Vector3f(1.0f, 0.0f, 0.0f);
        }
        return vec.normalize();
    }

    private static List<IntRange> parseRanges(String raw) {
        List<IntRange> list = new ArrayList<>();
        if (raw == null || raw.isBlank()) return list;
        for (String token : raw.trim().split("[\\s,]+")) {
            if (token.isBlank()) continue;
            int dashIdx = token.indexOf('-', 1);
            try {
                if (dashIdx > 0) {
                    int min = Integer.parseInt(token.substring(0, dashIdx).trim());
                    int max = Integer.parseInt(token.substring(dashIdx + 1).trim());
                    list.add(new IntRange(Math.min(min, max), Math.max(min, max)));
                } else {
                    int val = Integer.parseInt(token.trim());
                    list.add(new IntRange(val, val));
                }
            } catch (NumberFormatException ignored) {}
        }
        return list;
    }

    private static float parseFloat(String raw, float fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Float.parseFloat(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public int layerIndex() {
        return this.layerIndex;
    }

    public Identifier textureId() {
        return this.textureId;
    }

    public BlendMode blendMode() {
        return this.blendMode;
    }

    public boolean rotate() {
        return this.rotate;
    }

    public float speed() {
        return this.speed;
    }

    public Vector3f axis() {
        return this.axis;
    }
}
