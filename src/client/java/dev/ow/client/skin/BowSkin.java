package dev.ow.client.skin;

import net.minecraft.resources.Identifier;

import java.util.Locale;

/**
 * Represents the two selectable Overwatch bow skins integrated from the legacy 1.16.5 resource packs
 * and converted to Minecraft 26.2 client item definitions and 3D cuboid models.
 */
public enum BowSkin {
    MCCREE_PEACEKEEPER(
        "mccree_peacekeeper",
        "McCree's Peacekeeper",
        "McCree's_Peacekeeper.zip",
        "Classic six-shooter revolver with custom iron sights and raised firing stance.",
        "crizzle",
        "crizzle",
        false
    ),
    /**
     * Tracer's Pulse Pistol. The only skin that is dual-wielded: when a bow is held in one hand and the other hand
     * is empty, a second (purely visual, client-side) pistol is drawn in the empty hand.
     */
    TRACER_PULSE_PISTOL(
        "tracer_pulse_pistol",
        "Tracer's Pulse Pistol",
        "Tracer's_Pulse_Pistol.zip",
        "Twin pulse pistol rendered in both the main hand and the offhand.",
        "pulsepistol",
        "pulsepistol",
        true
    );

    public static final String MOD_ID = "ow";

    private final String id;
    private final String displayName;
    private final String sourcePackName;
    private final String description;
    private final Identifier dynamicItemModelId;
    private final Identifier idleItemModelId;
    private final Identifier pulling0ItemModelId;
    private final Identifier pulling1ItemModelId;
    private final Identifier pulling2ItemModelId;
    private final Identifier primaryTextureId;
    private final Identifier firingTextureId;
    private final boolean dualWield;

    BowSkin(
        String id,
        String displayName,
        String sourcePackName,
        String description,
        String primaryTextureName,
        String firingTextureName,
        boolean dualWield
    ) {
        this.id = id;
        this.displayName = displayName;
        this.sourcePackName = sourcePackName;
        this.description = description;
        this.dynamicItemModelId = Identifier.fromNamespaceAndPath(MOD_ID, id);
        this.idleItemModelId = Identifier.fromNamespaceAndPath(MOD_ID, id + "_idle");
        this.pulling0ItemModelId = Identifier.fromNamespaceAndPath(MOD_ID, id + "_pulling_0");
        this.pulling1ItemModelId = Identifier.fromNamespaceAndPath(MOD_ID, id + "_pulling_1");
        this.pulling2ItemModelId = Identifier.fromNamespaceAndPath(MOD_ID, id + "_pulling_2");
        this.primaryTextureId = Identifier.fromNamespaceAndPath(MOD_ID, "textures/item/" + primaryTextureName + ".png");
        this.firingTextureId = Identifier.fromNamespaceAndPath(MOD_ID, "textures/item/" + firingTextureName + ".png");
        this.dualWield = dualWield;
    }

    public String id() {
        return this.id;
    }

    public String displayName() {
        return this.displayName;
    }

    public String sourcePackName() {
        return this.sourcePackName;
    }

    public String description() {
        return this.description;
    }

    public Identifier dynamicItemModelId() {
        return this.dynamicItemModelId;
    }

    public Identifier idleItemModelId() {
        return this.idleItemModelId;
    }

    public Identifier pulling0ItemModelId() {
        return this.pulling0ItemModelId;
    }

    public Identifier pulling1ItemModelId() {
        return this.pulling1ItemModelId;
    }

    public Identifier pulling2ItemModelId() {
        return this.pulling2ItemModelId;
    }

    public Identifier primaryTextureId() {
        return this.primaryTextureId;
    }

    public Identifier firingTextureId() {
        return this.firingTextureId;
    }

    /** True if this skin is rendered in both hands (a second, visual-only pistol fills an empty hand). */
    public boolean dualWield() {
        return this.dualWield;
    }

    /**
     * Returns the client item model Identifier for the given pull stage:
     * 0 = idle (bow.json)
     * 1 = pulling_0 (bow_pulling_0.json)
     * 2 = pulling_1 (bow_pulling_1.json)
     * 3 = pulling_2 (bow_pulling_2.json)
     */
    public Identifier itemModelForStage(int stageIndex) {
        return switch (stageIndex) {
            case 1 -> this.pulling0ItemModelId;
            case 2 -> this.pulling1ItemModelId;
            case 3 -> this.pulling2ItemModelId;
            default -> this.idleItemModelId;
        };
    }

    public BowSkin next() {
        BowSkin[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }

    /**
     * Resolves a BowSkin from config or command text. Accepts canonical IDs, display names,
     * source zip names, or 1-based skin numbers ("1", "2", "3", "4", "Bow Skin 1", etc.).
     * Defaults safely to MCCREE_PEACEKEEPER if null or unrecognized.
     */
    public static BowSkin fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return MCCREE_PEACEKEEPER;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (BowSkin skin : values()) {
            if (skin.id.equalsIgnoreCase(normalized)
                || skin.name().equalsIgnoreCase(normalized)
                || skin.displayName.equalsIgnoreCase(normalized)
                || skin.sourcePackName.equalsIgnoreCase(normalized)) {
                return skin;
            }
        }
        if (normalized.contains("mccree") || normalized.contains("cassidy") || normalized.contains("peacekeeper") || normalized.equals("3") || normalized.equals("bow skin 3") || normalized.equals("skin3")) {
            return MCCREE_PEACEKEEPER;
        }
        if (normalized.contains("tracer") || normalized.contains("pulse") || normalized.equals("4") || normalized.equals("bow skin 4") || normalized.equals("skin4")) {
            return TRACER_PULSE_PISTOL;
        }
        return MCCREE_PEACEKEEPER;
    }
}
