package dev.ow.client.config;

import dev.meowy.client.config.MeowyConfig;
import dev.ow.client.skin.BowSkin;

/**
 * Bridge connecting OW bow skin and laser projectile classes directly to MeowyConfig.
 */
public final class OwConfig {
    private static final OwConfig INSTANCE = new OwConfig();

    public boolean laserBulletEnabled = false;

    private OwConfig() {}

    public static OwConfig get() {
        INSTANCE.laserBulletEnabled = MeowyConfig.get().isLaserBulletsActive();
        return INSTANCE;
    }

    public boolean isBowSkinEnabled() {
        return MeowyConfig.get().bowSkinsEnabled;
    }

    public BowSkin getSelectedBowSkin() {
        return MeowyConfig.get().selectedBowSkin;
    }

    public void setSelectedBowSkin(BowSkin skin) {
        MeowyConfig.get().selectedBowSkin = (skin != null) ? skin : BowSkin.MCCREE_PEACEKEEPER;
        MeowyConfig.save();
    }
}
