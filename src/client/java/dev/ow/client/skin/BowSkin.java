package dev.ow.client.skin;

import net.minecraft.resources.Identifier;

/**
 * Selectable Overwatch bow skins for Minecraft 26.2.
 */
public enum BowSkin {
    MCCREE_PEACEKEEPER(
        "mccree_peacekeeper",
        "McCree's Peacekeeper",
        false
    ),
    TRACER_PULSE_PISTOL(
        "tracer_pulse_pistol",
        "Tracer's Pulse Pistol",
        true
    );

    public static final String MOD_ID = "ow";

    private final String id;
    private final String displayName;
    private final Identifier idleItemModelId;
    private final Identifier pulling0ItemModelId;
    private final boolean dualWield;

    BowSkin(String id, String displayName, boolean dualWield) {
        this.id = id;
        this.displayName = displayName;
        this.idleItemModelId = Identifier.fromNamespaceAndPath(MOD_ID, id + "_idle");
        this.pulling0ItemModelId = Identifier.fromNamespaceAndPath(MOD_ID, id + "_pulling_0");
        this.dualWield = dualWield;
    }

    public String id() {
        return this.id;
    }

    public String displayName() {
        return this.displayName;
    }

    public Identifier idleItemModelId() {
        return this.idleItemModelId;
    }

    public Identifier pullingItemModelId() {
        return this.pulling0ItemModelId;
    }

    public boolean dualWield() {
        return this.dualWield;
    }
}
