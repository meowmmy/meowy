package dev.meowy.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.ow.client.skin.BowSkin;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Real-time JSON-backed configuration store for Meowy.
 * Every boolean toggle starts OFF (false) by default, and all sliders start at their vanilla defaults.
 */
public final class MeowyConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("Meowy-Config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("meowy.json");

    private static MeowyConfig INSTANCE = new MeowyConfig();

    // =========================================================================
    // 1. PLAYER Category (Own Nametag, Character, Cape & Armor Opacity)
    // =========================================================================
    public boolean showOwnNametag = false;  // First setting in PLAYER list; starts OFF by default
    public float playerModelOpacity = 1.0f; // Local player skin, first-person empty hand, and 3D body
    public float capeOpacity = 1.0f;        // Separate local player cape opacity
    public float helmetOpacity = 1.0f;      // Helmet, skulls, carved pumpkins, trims, and glint
    public float chestplateOpacity = 1.0f;  // Chestplate, Elytra, trims, and glint
    public float leggingsOpacity = 1.0f;    // Leggings, trims, and glint
    public float bootsOpacity = 1.0f;       // Boots, trims, and glint

    // =========================================================================
    // 2. HAND Category (Scale, XYZ, Rotations & Visual Swing Speed)
    // =========================================================================
    public float heldItemScale = 1.0f;           // 0.20f (0.20x) to 2.50f (2.50x), default 1.00f
    public float heldItemX = 0.0f;               // -1.50f to +1.50f
    public float heldItemY = 0.0f;               // -1.50f to +1.50f
    public float heldItemZ = 0.0f;               // -1.50f to +1.50f
    public float heldItemRotSide = 0.0f;         // -180.0f to +180.0f degrees (side-to-side rotation)
    public float heldItemRotForward = 0.0f;      // -180.0f to +180.0f degrees (forwards/backwards rotation)
    public float visualSwingSpeed = 1.0f;        // 0.20f (0.2x) to 3.00f (3.0x), 100% client-side smooth animation

    // =========================================================================
    // 3. CAMERA Category (Disable Front Camera & Accurate Third-Person Crosshair)
    // =========================================================================
    public boolean disableFrontCamera = false;
    public boolean f5CrosshairEnabled = false;   // Always uses Accurate 3D raycast projection when enabled

    // =========================================================================
    // 4. VISUAL Category (Fullbright & Custom Skybox)
    // =========================================================================
    public boolean fullbrightEnabled = false;
    public boolean customSkyboxEnabled = false;  // Starts OFF by default
    public float skyboxBrightness = 1.0f;        // 0.0f (0%) to 2.0f (200%), default 1.0f (100%)
    public SkyboxFormat skyboxFormat = SkyboxFormat.OPTIFINE_STANDARD;

    // =========================================================================
    // 5. MISC Category (Discord RPC, Meowy Crosshair, Crosshair Scale, Hitmarkers, Bow Skins & Laser Bullets)
    // =========================================================================
    public boolean discordRpcEnabled = false;      // Starts OFF by default
    public boolean meowyCrosshairEnabled = false;  // Starts OFF by default; hides default crosshair & draws CrosshairWithoutHitmarker.png
    public float crosshairScale = 1.0f;            // 0.25f (0.25x) to 3.00f (3.00x), default 1.00f
    public boolean hitmarkersEnabled = false;      // Starts OFF by default; draws Overwatch hitmarker from CrosshairWithHitmarker.png
    public boolean bowSkinsEnabled = false;        // Starts OFF by default
    public BowSkin selectedBowSkin = BowSkin.MCCREE_PEACEKEEPER;
    public BowLaserMode bowLaserMode = BowLaserMode.ENABLED;

    public enum BowLaserMode {
        ENABLED("Enabled"),
        DISABLED("Disabled");

        private final String displayName;

        BowLaserMode(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return this.displayName;
        }

    }

    public enum SkyboxFormat {
        OPTIFINE_STANDARD("Modern OptiFine (3x2)"),
        LEGACY_1_8_9("Legacy 1.8.9 (Flipped)");

        private final String displayName;

        SkyboxFormat(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return this.displayName;
        }

    }

    public enum Category {
        PLAYER("PLAYER", "PLAYER SETTINGS"),
        HAND("HAND", "HAND SETTINGS"),
        CAMERA("CAMERA", "CAMERA SETTINGS"),
        VISUAL("VISUAL", "VISUAL SETTINGS"),
        MISC("MISC", "MISC SETTINGS");

        private final String title;
        private final String heading;

        Category(String title, String heading) {
            this.title = title;
            this.heading = heading;
        }

        public String title() {
            return this.title;
        }

        public String heading() {
            return this.heading;
        }
    }

    public static MeowyConfig get() {
        return INSTANCE;
    }

    public boolean isLaserBulletsActive() {
        return this.bowSkinsEnabled && this.bowLaserMode == BowLaserMode.ENABLED;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            save();
            return;
        }
        try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
            MeowyConfig loaded = GSON.fromJson(reader, MeowyConfig.class);
            if (loaded != null) {
                loaded.sanitize();
                INSTANCE = loaded;
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load meowy.json, falling back to defaults", e);
            INSTANCE = new MeowyConfig();
        }
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
                GSON.toJson(INSTANCE, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save meowy.json", e);
        }
    }

    public void sanitize() {
        this.playerModelOpacity = clamp(this.playerModelOpacity, 0.0f, 1.0f);
        this.capeOpacity = clamp(this.capeOpacity, 0.0f, 1.0f);
        this.helmetOpacity = clamp(this.helmetOpacity, 0.0f, 1.0f);
        this.chestplateOpacity = clamp(this.chestplateOpacity, 0.0f, 1.0f);
        this.leggingsOpacity = clamp(this.leggingsOpacity, 0.0f, 1.0f);
        this.bootsOpacity = clamp(this.bootsOpacity, 0.0f, 1.0f);

        this.heldItemScale = clamp(this.heldItemScale, 0.20f, 2.50f);
        this.heldItemX = clamp(this.heldItemX, -1.50f, 1.50f);
        this.heldItemY = clamp(this.heldItemY, -1.50f, 1.50f);
        this.heldItemZ = clamp(this.heldItemZ, -1.50f, 1.50f);
        this.heldItemRotSide = clamp(this.heldItemRotSide, -180.0f, 180.0f);
        this.heldItemRotForward = clamp(this.heldItemRotForward, -180.0f, 180.0f);
        this.visualSwingSpeed = clamp(this.visualSwingSpeed, 0.20f, 3.00f);

        this.crosshairScale = clamp(this.crosshairScale, 0.25f, 3.00f);

        if (this.skyboxFormat == null) {
            this.skyboxFormat = SkyboxFormat.OPTIFINE_STANDARD;
        }
        if (this.selectedBowSkin == null) {
            this.selectedBowSkin = BowSkin.MCCREE_PEACEKEEPER;
        }
        if (this.bowLaserMode == null) {
            this.bowLaserMode = BowLaserMode.ENABLED;
        }
    }

    public void resetCategory(Category category) {
        switch (category) {
            case PLAYER -> {
                this.showOwnNametag = false;
                this.playerModelOpacity = 1.0f;
                this.capeOpacity = 1.0f;
                this.helmetOpacity = 1.0f;
                this.chestplateOpacity = 1.0f;
                this.leggingsOpacity = 1.0f;
                this.bootsOpacity = 1.0f;
            }
            case HAND -> {
                this.heldItemScale = 1.0f;
                this.heldItemX = 0.0f;
                this.heldItemY = 0.0f;
                this.heldItemZ = 0.0f;
                this.heldItemRotSide = 0.0f;
                this.heldItemRotForward = 0.0f;
                this.visualSwingSpeed = 1.0f;
            }
            case CAMERA -> {
                this.disableFrontCamera = false;
                this.f5CrosshairEnabled = false;
            }
            case VISUAL -> {
                this.fullbrightEnabled = false;
                this.customSkyboxEnabled = false;
                this.skyboxBrightness = 1.0f;
                this.skyboxFormat = SkyboxFormat.OPTIFINE_STANDARD;
            }
            case MISC -> {
                this.discordRpcEnabled = false;
                this.meowyCrosshairEnabled = false;
                this.crosshairScale = 1.0f;
                this.hitmarkersEnabled = false;
                this.bowSkinsEnabled = false;
                this.selectedBowSkin = BowSkin.MCCREE_PEACEKEEPER;
                this.bowLaserMode = BowLaserMode.ENABLED;
            }
        }
        save();
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
