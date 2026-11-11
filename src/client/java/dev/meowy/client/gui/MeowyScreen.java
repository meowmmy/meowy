package dev.meowy.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.meowy.client.MeowyClient;
import dev.meowy.client.config.MeowyConfig;
import dev.meowy.client.config.MeowyConfig.BowLaserMode;
import dev.meowy.client.config.MeowyConfig.Category;
import dev.meowy.client.config.MeowyConfig.SkyboxFormat;
import dev.meowy.client.render.MeowyPreviewContext;
import dev.ow.client.skin.BowSkin;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Meowy Custom Configuration GUI for Minecraft 26.2.
 * All setting descriptions are omitted for a clean, self-explanatory single-line row layout.
 */
public final class MeowyScreen extends Screen {
    private static final float DEFAULT_PREVIEW_YAW = 0.0f;   // 0.0f offset = facing directly front (180 deg bodyRot)
    private static final float DEFAULT_PREVIEW_PITCH = 0.0f;
    private static final float MAX_PREVIEW_PITCH = 12.0f;    // Very limited upwards and downwards rotation

    private final Screen parent;
    private Category selectedCategory;

    // Animations & Hand/Sky/Crosshair-slider live transparency
    private long openTimestampMs;
    private long categorySwitchTimestampMs;
    private long resetFeedbackTimestampMs;
    private float currentWindowAlpha = 1.0f;
    private float handPreviewAlpha = 1.0f;

    // Layout coordinates
    private int winX, winY, winW, winH;
    private int headerH;
    private int sidebarW;
    private int contentX, contentY, contentW, contentH;
    private int containerX, containerY, containerW, containerH;
    private int previewX, previewY, previewW, previewH;
    private int resetBtnX, resetBtnY, resetBtnW, resetBtnH;

    // Scrolling, Interactive Dragging & Expanded Dropdown State
    private float scrollOffset = 0.0f;
    private float targetScrollOffset = 0.0f;
    private float maxScroll = 0.0f;
    private SliderRow activeDraggingSlider = null;
    private DropdownRow expandedDropdown = null;

    // Live 3D Player Preview Drag-to-Rotate & Smooth Float-Back State
    private boolean draggingPreview = false;
    private float previewYaw = DEFAULT_PREVIEW_YAW;
    private float previewPitch = DEFAULT_PREVIEW_PITCH;
    private long lastPreviewUpdateNano = System.nanoTime();
    private long lastPreviewInteractionMs = 0L;

    // Sidebar hover animation states
    private final float[] sidebarHoverProgress = new float[Category.values().length];

    // Setting rows per category
    private final List<SettingRow> activeRows = new ArrayList<>();

    public MeowyScreen(Screen parent, Category initialCategory) {
        super(Component.translatable("meowy.gui.title"));
        this.parent = parent;
        this.selectedCategory = initialCategory != null ? initialCategory : Category.PLAYER;
        resetPreviewRotation();
    }

    private void resetPreviewRotation() {
        this.previewYaw = DEFAULT_PREVIEW_YAW;
        this.previewPitch = DEFAULT_PREVIEW_PITCH;
        this.draggingPreview = false;
        this.lastPreviewUpdateNano = System.nanoTime();
        this.lastPreviewInteractionMs = 0L;
        MeowyPreviewContext.stopPreviewSwing();
    }

    @Override
    protected void init() {
        super.init();
        if (this.openTimestampMs == 0L) {
            this.openTimestampMs = System.currentTimeMillis();
        }
        this.expandedDropdown = null;
        rebuildCategoryRows();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        resetPreviewRotation();
        MeowyConfig.save();
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(this.parent);
        }
    }

    private void switchCategory(Category newCategory) {
        if (this.selectedCategory == newCategory) return;
        this.selectedCategory = newCategory;
        MeowyClient.setLastSelectedCategory(newCategory);
        this.categorySwitchTimestampMs = System.currentTimeMillis();
        this.scrollOffset = 0.0f;
        this.targetScrollOffset = 0.0f;
        this.activeDraggingSlider = null;
        this.expandedDropdown = null;
        MeowyPreviewContext.stopPreviewSwing();
        rebuildCategoryRows();
    }

    private void rebuildCategoryRows() {
        this.activeRows.clear();
        MeowyConfig cfg = MeowyConfig.get();

        switch (this.selectedCategory) {
            case PLAYER -> {
                this.activeRows.add(new ToggleRow(
                    "Show Own Nametag",
                    () -> cfg.showOwnNametag,
                    val -> { cfg.showOwnNametag = val; MeowyConfig.save(); }
                ));
                this.activeRows.add(new SliderRow(
                    "Player Model Opacity",
                    0.0f, 1.0f, 0.01f,
                    () -> cfg.playerModelOpacity,
                    val -> { cfg.playerModelOpacity = val; MeowyConfig.save(); },
                    val -> Math.round(val * 100.0f) + "%"
                ));
                this.activeRows.add(new SliderRow(
                    "Cape Opacity",
                    0.0f, 1.0f, 0.01f,
                    () -> cfg.capeOpacity,
                    val -> { cfg.capeOpacity = val; MeowyConfig.save(); },
                    val -> Math.round(val * 100.0f) + "%"
                ));
                this.activeRows.add(new SliderRow(
                    "Helmet Opacity",
                    0.0f, 1.0f, 0.01f,
                    () -> cfg.helmetOpacity,
                    val -> { cfg.helmetOpacity = val; MeowyConfig.save(); },
                    val -> Math.round(val * 100.0f) + "%"
                ));
                this.activeRows.add(new SliderRow(
                    "Chestplate Opacity",
                    0.0f, 1.0f, 0.01f,
                    () -> cfg.chestplateOpacity,
                    val -> { cfg.chestplateOpacity = val; MeowyConfig.save(); },
                    val -> Math.round(val * 100.0f) + "%"
                ));
                this.activeRows.add(new SliderRow(
                    "Leggings Opacity",
                    0.0f, 1.0f, 0.01f,
                    () -> cfg.leggingsOpacity,
                    val -> { cfg.leggingsOpacity = val; MeowyConfig.save(); },
                    val -> Math.round(val * 100.0f) + "%"
                ));
                this.activeRows.add(new SliderRow(
                    "Boots Opacity",
                    0.0f, 1.0f, 0.01f,
                    () -> cfg.bootsOpacity,
                    val -> { cfg.bootsOpacity = val; MeowyConfig.save(); },
                    val -> Math.round(val * 100.0f) + "%"
                ));
            }
            case HAND -> {
                this.activeRows.add(new SliderRow(
                    "Held Item Scale",
                    0.20f, 2.50f, 0.05f,
                    () -> cfg.heldItemScale,
                    val -> { cfg.heldItemScale = val; MeowyConfig.save(); },
                    val -> String.format("%.2fx", val)
                ));
                this.activeRows.add(new SliderRow(
                    "Held Item X Position",
                    -1.50f, 1.50f, 0.01f,
                    () -> cfg.heldItemX,
                    val -> { cfg.heldItemX = val; MeowyConfig.save(); },
                    val -> String.format("%+.2f", val)
                ));
                this.activeRows.add(new SliderRow(
                    "Held Item Y Position",
                    -1.50f, 1.50f, 0.01f,
                    () -> cfg.heldItemY,
                    val -> { cfg.heldItemY = val; MeowyConfig.save(); },
                    val -> String.format("%+.2f", val)
                ));
                this.activeRows.add(new SliderRow(
                    "Held Item Z Position",
                    -1.50f, 1.50f, 0.01f,
                    () -> cfg.heldItemZ,
                    val -> { cfg.heldItemZ = val; MeowyConfig.save(); },
                    val -> String.format("%+.2f", val)
                ));
                this.activeRows.add(new SliderRow(
                    "Held Item Side Rotation",
                    -180.0f, 180.0f, 1.0f,
                    () -> cfg.heldItemRotSide,
                    val -> { cfg.heldItemRotSide = val; MeowyConfig.save(); },
                    val -> String.format("%+.0f deg", val)
                ));
                this.activeRows.add(new SliderRow(
                    "Held Item Forward Rotation",
                    -180.0f, 180.0f, 1.0f,
                    () -> cfg.heldItemRotForward,
                    val -> { cfg.heldItemRotForward = val; MeowyConfig.save(); },
                    val -> String.format("%+.0f deg", val)
                ));
                this.activeRows.add(new SliderRow(
                    "Visual Swing Speed",
                    0.20f, 3.00f, 0.05f,
                    () -> cfg.visualSwingSpeed,
                    val -> {
                        cfg.visualSwingSpeed = val;
                        MeowyConfig.save();
                        MeowyPreviewContext.triggerPreviewSwing();
                    },
                    val -> String.format("%.2fx", val)
                ));
            }
            case CAMERA -> {
                this.activeRows.add(new ToggleRow(
                    "Disable Front Camera",
                    () -> cfg.disableFrontCamera,
                    val -> { cfg.disableFrontCamera = val; MeowyConfig.save(); }
                ));
                this.activeRows.add(new ToggleRow(
                    "Third-Person Crosshair",
                    () -> cfg.f5CrosshairEnabled,
                    val -> { cfg.f5CrosshairEnabled = val; MeowyConfig.save(); }
                ));
            }
            case VISUAL -> {
                this.activeRows.add(new ToggleRow(
                    "Fullbright",
                    () -> cfg.fullbrightEnabled,
                    val -> { cfg.fullbrightEnabled = val; MeowyConfig.save(); }
                ));
                this.activeRows.add(new ToggleRow(
                    "Custom Skybox",
                    () -> cfg.customSkyboxEnabled,
                    val -> { cfg.customSkyboxEnabled = val; MeowyConfig.save(); }
                ));
                this.activeRows.add(new SliderRow(
                    "Sky Brightness",
                    0.0f, 2.0f, 0.05f,
                    () -> cfg.skyboxBrightness,
                    val -> { cfg.skyboxBrightness = val; MeowyConfig.save(); },
                    val -> Math.round(val * 100.0f) + "%"
                ));
                SkyboxFormat[] skyFormats = SkyboxFormat.values();
                String[] skyFormatNames = new String[skyFormats.length];
                for (int i = 0; i < skyFormats.length; i++) {
                    skyFormatNames[i] = skyFormats[i].displayName();
                }
                this.activeRows.add(new DropdownRow(
                    "Skybox Pack Format",
                    skyFormatNames,
                    () -> cfg.skyboxFormat.ordinal(),
                    idx -> {
                        cfg.skyboxFormat = skyFormats[idx];
                        MeowyConfig.save();
                    }
                ));
            }
            case MISC -> {
                this.activeRows.add(new ToggleRow(
                    "Discord Rich Presence",
                    () -> cfg.discordRpcEnabled,
                    val -> { cfg.discordRpcEnabled = val; MeowyConfig.save(); }
                ));
                this.activeRows.add(new ToggleRow(
                    "Meowy Crosshair",
                    () -> cfg.meowyCrosshairEnabled,
                    val -> { cfg.meowyCrosshairEnabled = val; MeowyConfig.save(); }
                ));
                this.activeRows.add(new SliderRow(
                    "Crosshair Scale",
                    0.25f, 3.00f, 0.05f,
                    () -> cfg.crosshairScale,
                    val -> { cfg.crosshairScale = val; MeowyConfig.save(); },
                    val -> String.format("%.2fx", val)
                ));
                this.activeRows.add(new ToggleRow(
                    "Hitmarkers",
                    () -> cfg.hitmarkersEnabled,
                    val -> { cfg.hitmarkersEnabled = val; MeowyConfig.save(); }
                ));
                this.activeRows.add(new ToggleRow(
                    "Custom Bow Skins",
                    () -> cfg.bowSkinsEnabled,
                    val -> { cfg.bowSkinsEnabled = val; MeowyConfig.save(); }
                ));
                BowSkin[] bowSkins = BowSkin.values();
                String[] bowSkinNames = new String[bowSkins.length];
                for (int i = 0; i < bowSkins.length; i++) {
                    bowSkinNames[i] = bowSkins[i].displayName();
                }
                this.activeRows.add(new DropdownRow(
                    "Bow Skin",
                    bowSkinNames,
                    () -> cfg.selectedBowSkin.ordinal(),
                    idx -> {
                        cfg.selectedBowSkin = bowSkins[idx];
                        MeowyConfig.save();
                    }
                ));
                BowLaserMode[] laserModes = BowLaserMode.values();
                String[] laserModeNames = new String[laserModes.length];
                for (int i = 0; i < laserModes.length; i++) {
                    laserModeNames[i] = laserModes[i].displayName();
                }
                this.activeRows.add(new DropdownRow(
                    "Laser Bullets",
                    laserModeNames,
                    () -> cfg.bowLaserMode.ordinal(),
                    idx -> {
                        cfg.bowLaserMode = laserModes[idx];
                        MeowyConfig.save();
                    }
                ));
            }
        }
    }

    private void computeLayoutBounds() {
        this.winW = Mth.clamp(Math.round(this.width * 0.82f), 460, this.width - 16);
        this.winH = Mth.clamp(Math.round(this.height * 0.85f), 265, this.height - 16);
        this.winX = (this.width - this.winW) / 2;
        this.winY = (this.height - this.winH) / 2;

        this.headerH = Mth.clamp(Math.round(this.winH * 0.115f), 34, 46);
        this.sidebarW = Mth.clamp(Math.round(this.winW * 0.21f), 114, 164);

        int bodyY = this.winY + this.headerH;
        int bodyH = this.winH - this.headerH;

        int pad = Mth.clamp(Math.round(this.winW * 0.024f), 12, 20);
        this.contentX = this.winX + this.sidebarW + pad;
        this.contentY = bodyY + pad;
        this.contentW = this.winW - this.sidebarW - pad * 2;
        this.contentH = bodyH - pad * 2;

        int headingBlockH = 24;
        int footerBlockH = 32;

        this.previewW = Mth.clamp(Math.round(this.contentW * 0.25f), 108, 148);
        int gap = 10;

        this.containerX = this.contentX;
        this.containerY = this.contentY + headingBlockH;
        this.containerW = this.contentW - this.previewW - gap;
        this.containerH = Math.max(80, this.contentH - headingBlockH - footerBlockH);

        this.previewX = this.containerX + this.containerW + gap;
        this.previewY = this.containerY;
        this.previewH = this.containerH;

        this.resetBtnW = Math.min(this.previewW, 126);
        this.resetBtnH = 22;
        this.resetBtnX = this.previewX + (this.previewW - this.resetBtnW) / 2;
        this.resetBtnY = this.previewY + this.previewH + 8;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        float backdropFactor = Mth.clamp((this.handPreviewAlpha - 0.22f) / 0.78f, 0.0f, 1.0f);
        int topColor = MeowyTheme.withAlpha(0xFF090712, 0.52f * this.currentWindowAlpha * backdropFactor);
        int botColor = MeowyTheme.withAlpha(0xFF110E20, 0.52f * this.currentWindowAlpha * backdropFactor);
        if ((topColor >>> 24) > 0 || (botColor >>> 24) > 0) {
            graphics.fillGradient(0, 0, this.width, this.height, topColor, botColor);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        computeLayoutBounds();

        long elapsedOpen = System.currentTimeMillis() - this.openTimestampMs;
        float openProgress = Mth.clamp(elapsedOpen / 160.0f, 0.0f, 1.0f);
        float openEase = 1.0f - (float) Math.pow(1.0f - openProgress, 3.0);

        long nowNano = System.nanoTime();
        long nowMs = System.currentTimeMillis();
        float dtSec = (nowNano - this.lastPreviewUpdateNano) / 1_000_000_000.0f;
        this.lastPreviewUpdateNano = nowNano;
        if (dtSec < 0.0f || dtSec > 0.25f) {
            dtSec = 0.016f;
        }
        if (this.draggingPreview || this.activeDraggingSlider != null) {
            this.lastPreviewInteractionMs = nowMs;
        } else if (nowMs - this.lastPreviewInteractionMs > 1800L) {
            float floatBackFactor = 1.0f - (float) Math.exp(-0.38f * dtSec);
            this.previewYaw = Mth.lerp(floatBackFactor, this.previewYaw, DEFAULT_PREVIEW_YAW);
            this.previewPitch = Mth.lerp(floatBackFactor, this.previewPitch, DEFAULT_PREVIEW_PITCH);
            if (Math.abs(this.previewYaw) < 0.05f) {
                this.previewYaw = DEFAULT_PREVIEW_YAW;
            }
            if (Math.abs(this.previewPitch) < 0.05f) {
                this.previewPitch = DEFAULT_PREVIEW_PITCH;
            }
        }

        // Fade GUI to 22% opacity when dragging HAND sliders, Sky Brightness, or Crosshair Scale
        boolean draggingHandSetting = (this.selectedCategory == Category.HAND && this.activeDraggingSlider != null);
        boolean draggingSkyBrightness = (this.selectedCategory == Category.VISUAL
            && this.activeDraggingSlider != null
            && "Sky Brightness".equals(this.activeDraggingSlider.name));
        boolean draggingCrosshairScale = (this.activeDraggingSlider != null
            && "Crosshair Scale".equals(this.activeDraggingSlider.name));
        float targetPreviewAlpha = (draggingHandSetting || draggingSkyBrightness || draggingCrosshairScale) ? 0.22f : 1.0f;
        this.handPreviewAlpha = Mth.lerp(0.28f, this.handPreviewAlpha, targetPreviewAlpha);

        boolean draggingSwingSlider = draggingHandSetting
            && "Visual Swing Speed".equals(this.activeDraggingSlider.name);
        if (draggingSwingSlider) {
            MeowyPreviewContext.triggerPreviewSwing();
        }
        MeowyPreviewContext.tickFrame(draggingSwingSlider);

        this.currentWindowAlpha = openEase * this.handPreviewAlpha;
        this.scrollOffset = Mth.lerp(0.3f, this.scrollOffset, this.targetScrollOffset);

        this.extractBackground(graphics, mouseX, mouseY, delta);

        // 1. Subtle outer drop shadow
        int shadowOuter = MeowyTheme.withAlpha(0x66000000, this.currentWindowAlpha);
        int shadowInner = MeowyTheme.withAlpha(0x99000000, this.currentWindowAlpha);
        graphics.fill(this.winX - 4, this.winY - 4, this.winX + this.winW + 4, this.winY + this.winH + 4, shadowOuter);
        graphics.fill(this.winX - 2, this.winY - 2, this.winX + this.winW + 2, this.winY + this.winH + 2, shadowInner);

        // 2. Main window background & outer border
        MeowyTheme.drawSoftPanel(
            graphics,
            this.winX,
            this.winY,
            this.winW,
            this.winH,
            MeowyTheme.withAlpha(MeowyTheme.MAIN_WINDOW_BG, this.currentWindowAlpha),
            MeowyTheme.withAlpha(MeowyTheme.BORDER_COLOR, this.currentWindowAlpha)
        );

        // 3. Top Header
        renderHeader(graphics, this.currentWindowAlpha);

        // 4. Left Sidebar Navigation
        renderSidebar(graphics, mouseX, mouseY, this.currentWindowAlpha);

        // 5. Main Content Area
        renderMainContent(graphics, mouseX, mouseY, delta, this.currentWindowAlpha);
    }

    private void renderHeader(GuiGraphicsExtractor graphics, float alpha) {
        int headerBottomY = this.winY + this.headerH;
        graphics.fill(
            this.winX + 1,
            headerBottomY,
            this.winX + this.winW - 1,
            headerBottomY + 1,
            MeowyTheme.withAlpha(MeowyTheme.BORDER_COLOR, alpha)
        );

        int titleX = this.winX + 18;
        int titleY = this.winY + (this.headerH - 9) / 2;

        Component brandComp = MeowyTheme.pixelText("MEOWY", true);
        graphics.text(
            this.font,
            brandComp,
            titleX,
            titleY,
            MeowyTheme.withAlpha(MeowyTheme.TEXT_PRIMARY, alpha),
            true
        );
    }

    private void renderSidebar(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float alpha) {
        int bodyY = this.winY + this.headerH + 1;
        int bodyH = this.winH - this.headerH - 2;

        graphics.fill(
            this.winX + 1,
            bodyY,
            this.winX + this.sidebarW,
            bodyY + bodyH,
            MeowyTheme.withAlpha(MeowyTheme.SIDEBAR_BG, alpha)
        );

        graphics.fill(
            this.winX + this.sidebarW,
            bodyY,
            this.winX + this.sidebarW + 1,
            bodyY + bodyH,
            MeowyTheme.withAlpha(MeowyTheme.BORDER_COLOR, alpha)
        );

        int labelX = this.winX + 12;
        int labelY = bodyY + 12;
        graphics.text(
            this.font,
            MeowyTheme.pixelText("SETTINGS", false),
            labelX,
            labelY,
            MeowyTheme.withAlpha(MeowyTheme.TEXT_MUTED, alpha),
            true
        );

        Category[] categories = Category.values();
        int itemX = this.winX + 8;
        int itemW = this.sidebarW - 16;
        int itemH = 24;
        int startY = labelY + 14;
        int gap = 3;

        for (int i = 0; i < categories.length; i++) {
            Category cat = categories[i];
            int itemY = startY + i * (itemH + gap);
            boolean selected = (cat == this.selectedCategory);
            boolean hovered = isMouseOverRect(mouseX, mouseY, itemX, itemY, itemW, itemH);

            float targetHover = (hovered || selected) ? 1.0f : 0.0f;
            this.sidebarHoverProgress[i] = Mth.lerp(0.25f, this.sidebarHoverProgress[i], targetHover);

            if (selected) {
                MeowyTheme.drawSoftPanel(
                    graphics,
                    itemX,
                    itemY,
                    itemW,
                    itemH,
                    MeowyTheme.withAlpha(MeowyTheme.SELECTED_CATEGORY_BG, alpha),
                    MeowyTheme.withAlpha(MeowyTheme.BORDER_COLOR, alpha)
                );
                graphics.fill(
                    itemX,
                    itemY + 1,
                    itemX + 3,
                    itemY + itemH - 1,
                    MeowyTheme.withAlpha(MeowyTheme.BRIGHT_LAVENDER, alpha)
                );
            } else if (this.sidebarHoverProgress[i] > 0.02f) {
                int hoverBg = MeowyTheme.withAlpha(MeowyTheme.HOVER_BG, alpha * this.sidebarHoverProgress[i] * 0.85f);
                graphics.fill(itemX, itemY, itemX + itemW, itemY + itemH, hoverBg);
            }

            int textColor = selected
                ? MeowyTheme.TEXT_PRIMARY
                : MeowyTheme.lerpColor(MeowyTheme.TEXT_SECONDARY, MeowyTheme.TEXT_PRIMARY, this.sidebarHoverProgress[i]);
            graphics.text(
                this.font,
                MeowyTheme.sansText(cat.title()),
                itemX + 10,
                itemY + 8,
                MeowyTheme.withAlpha(textColor, alpha),
                true
            );

            if (selected) {
                graphics.text(
                    this.font,
                    MeowyTheme.pixelText(">", false),
                    itemX + itemW - 12,
                    itemY + 8,
                    MeowyTheme.withAlpha(MeowyTheme.BRIGHT_LAVENDER, alpha),
                    true
                );
            }
        }
    }

    private List<SettingRow> getVisibleRows() {
        List<SettingRow> list = new ArrayList<>(this.activeRows.size());
        for (SettingRow row : this.activeRows) {
            if (row.isVisible()) {
                list.add(row);
            }
        }
        return list;
    }

    private void renderMainContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, float alpha) {
        long sinceSwitch = System.currentTimeMillis() - this.categorySwitchTimestampMs;
        float switchProgress = Mth.clamp(sinceSwitch / 120.0f, 0.0f, 1.0f);
        float contentAlpha = alpha * (0.4f + 0.6f * switchProgress);

        graphics.text(
            this.font,
            MeowyTheme.pixelText(this.selectedCategory.heading(), true),
            this.contentX,
            this.contentY + 6,
            MeowyTheme.withAlpha(MeowyTheme.TEXT_PRIMARY, contentAlpha),
            true
        );

        MeowyTheme.drawSoftPanel(
            graphics,
            this.containerX,
            this.containerY,
            this.containerW,
            this.containerH,
            MeowyTheme.withAlpha(MeowyTheme.MAIN_SETTINGS_BG, contentAlpha),
            MeowyTheme.withAlpha(MeowyTheme.BORDER_COLOR, contentAlpha)
        );

        List<SettingRow> visibleRows = getVisibleRows();
        int totalRowsHeight = 0;
        for (SettingRow row : visibleRows) {
            totalRowsHeight += row.getRowHeight();
        }
        this.maxScroll = Math.max(0.0f, totalRowsHeight - (this.containerH - 4));
        this.targetScrollOffset = Mth.clamp(this.targetScrollOffset, 0.0f, this.maxScroll);

        graphics.enableScissor(
            this.containerX + 1,
            this.containerY + 1,
            this.containerX + this.containerW - 1,
            this.containerY + this.containerH - 1
        );

        int currentRowY = this.containerY + 2 - Math.round(this.scrollOffset);
        for (int i = 0; i < visibleRows.size(); i++) {
            SettingRow row = visibleRows.get(i);
            int rowH = row.getRowHeight();
            if (currentRowY + rowH >= this.containerY && currentRowY <= this.containerY + this.containerH) {
                row.render(
                    graphics,
                    this.containerX + 2,
                    currentRowY,
                    this.containerW - 4,
                    rowH,
                    mouseX,
                    mouseY,
                    contentAlpha,
                    i < visibleRows.size() - 1
                );
            }
            currentRowY += rowH;
        }

        graphics.disableScissor();

        if (this.maxScroll > 0.5f) {
            int trackX = this.containerX + this.containerW - 4;
            int trackY = this.containerY + 4;
            int trackH = this.containerH - 8;
            int thumbH = Math.max(18, Math.round(trackH * (this.containerH / (float) totalRowsHeight)));
            int thumbY = trackY + Math.round((trackH - thumbH) * (this.scrollOffset / this.maxScroll));
            graphics.fill(trackX, trackY, trackX + 2, trackY + trackH, MeowyTheme.withAlpha(MeowyTheme.SECONDARY_BG, contentAlpha));
            graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, MeowyTheme.withAlpha(MeowyTheme.PRIMARY_PURPLE_ACCENT, contentAlpha));
        }

        renderLivePlayerPreviewCard(graphics, contentAlpha);
        renderResetButton(graphics, mouseX, mouseY, contentAlpha);

        if (this.expandedDropdown != null) {
            this.expandedDropdown.renderDropdownMenuOverlay(graphics, mouseX, mouseY, contentAlpha);
        }
    }

    private void renderLivePlayerPreviewCard(GuiGraphicsExtractor graphics, float alpha) {
        MeowyTheme.drawSoftPanel(
            graphics,
            this.previewX,
            this.previewY,
            this.previewW,
            this.previewH,
            MeowyTheme.withAlpha(MeowyTheme.SIDEBAR_BG, alpha),
            MeowyTheme.withAlpha(MeowyTheme.BORDER_COLOR, alpha)
        );

        if (this.minecraft != null && this.minecraft.player != null) {
            int entityBoxX1 = this.previewX + 4;
            int entityBoxY1 = this.previewY + 6;
            int entityBoxX2 = this.previewX + this.previewW - 4;
            int entityBoxY2 = this.previewY + this.previewH - 6;
            int boxHeight = Math.max(40, entityBoxY2 - entityBoxY1);
            int scale = Mth.clamp(Math.round(boxHeight * 0.45f), 34, 76);

            float centerX = (entityBoxX1 + entityBoxX2) * 0.5f;
            float centerY = (entityBoxY1 + entityBoxY2) * 0.5f;

            MeowyPreviewContext.begin(this.previewYaw, this.previewPitch);
            try {
                InventoryScreen.extractEntityInInventoryFollowsMouse(
                    graphics,
                    entityBoxX1,
                    entityBoxY1,
                    entityBoxX2,
                    entityBoxY2,
                    scale,
                    0.0625f,
                    centerX,
                    centerY,
                    this.minecraft.player
                );
            } finally {
                MeowyPreviewContext.end();
            }
        } else {
            String msg = "Join a world";
            int msgW = this.font.width(msg);
            graphics.text(
                this.font,
                MeowyTheme.sansText(msg),
                this.previewX + (this.previewW - msgW) / 2,
                this.previewY + this.previewH / 2 - 4,
                MeowyTheme.withAlpha(MeowyTheme.TEXT_SECONDARY, alpha),
                true
            );
        }
    }

    private void renderResetButton(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float alpha) {
        boolean hovered = isMouseOverRect(mouseX, mouseY, this.resetBtnX, this.resetBtnY, this.resetBtnW, this.resetBtnH);
        long sinceReset = System.currentTimeMillis() - this.resetFeedbackTimestampMs;
        boolean recentlyReset = sinceReset < 900L;

        int bg = hovered ? MeowyTheme.HOVER_BG : MeowyTheme.SECONDARY_BG;
        int border = (hovered || recentlyReset) ? MeowyTheme.PRIMARY_PURPLE_ACCENT : MeowyTheme.BORDER_COLOR;

        MeowyTheme.drawSoftPanel(
            graphics,
            this.resetBtnX,
            this.resetBtnY,
            this.resetBtnW,
            this.resetBtnH,
            MeowyTheme.withAlpha(bg, alpha),
            MeowyTheme.withAlpha(border, alpha)
        );

        String label = recentlyReset ? "RESTORED" : "RESET TO DEFAULT";
        Component comp = MeowyTheme.sansText(label);
        int textW = this.font.width(comp);
        int textX = this.resetBtnX + (this.resetBtnW - textW) / 2;
        int textY = this.resetBtnY + (this.resetBtnH - 8) / 2;
        int textColor = recentlyReset ? MeowyTheme.BRIGHT_LAVENDER : MeowyTheme.TEXT_PRIMARY;

        graphics.text(
            this.font,
            comp,
            textX,
            textY,
            MeowyTheme.withAlpha(textColor, alpha),
            true
        );
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();

        if (button == 0) {
            if (this.expandedDropdown != null) {
                boolean handledByMenu = this.expandedDropdown.handleMenuClick(mouseX, mouseY);
                this.expandedDropdown = null;
                if (handledByMenu) {
                    return true;
                }
            }

            // 1. Check Sidebar Categories
            int bodyY = this.winY + this.headerH + 1;
            int labelY = bodyY + 12;
            int itemX = this.winX + 8;
            int itemW = this.sidebarW - 16;
            int itemH = 24;
            int startY = labelY + 14;
            int gap = 3;

            Category[] categories = Category.values();
            for (int i = 0; i < categories.length; i++) {
                int itemY = startY + i * (itemH + gap);
                if (isMouseOverRect(mouseX, mouseY, itemX, itemY, itemW, itemH)) {
                    switchCategory(categories[i]);
                    return true;
                }
            }

            // 2. Check RESET TO DEFAULT button
            if (isMouseOverRect(mouseX, mouseY, this.resetBtnX, this.resetBtnY, this.resetBtnW, this.resetBtnH)) {
                MeowyConfig.get().resetCategory(this.selectedCategory);
                this.resetFeedbackTimestampMs = System.currentTimeMillis();
                return true;
            }

            // 3. Check 3D Live Preview Card click-and-drag rotation
            if (isMouseOverRect(mouseX, mouseY, this.previewX, this.previewY, this.previewW, this.previewH)) {
                this.draggingPreview = true;
                return true;
            }

            // 4. Check Visible Settings Rows inside container
            if (isMouseOverRect(mouseX, mouseY, this.containerX, this.containerY, this.containerW, this.containerH)) {
                List<SettingRow> visibleRows = getVisibleRows();
                int currentRowY = this.containerY + 2 - Math.round(this.scrollOffset);
                for (SettingRow row : visibleRows) {
                    int rowH = row.getRowHeight();
                    if (mouseY >= currentRowY && mouseY < currentRowY + rowH) {
                        if (row.mouseClicked(mouseX, mouseY, this.containerX + 2, currentRowY, this.containerW - 4, rowH)) {
                            return true;
                        }
                    }
                    currentRowY += rowH;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (this.activeDraggingSlider != null) {
            this.activeDraggingSlider.updateFromMouse(event.x());
            return true;
        }
        if (this.draggingPreview) {
            this.previewYaw = Mth.wrapDegrees(this.previewYaw + (float) dx * 1.75f);
            this.previewPitch = Mth.clamp(this.previewPitch + (float) dy * 0.45f, -MAX_PREVIEW_PITCH, MAX_PREVIEW_PITCH);
            this.lastPreviewInteractionMs = System.currentTimeMillis();
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (this.activeDraggingSlider != null) {
            this.activeDraggingSlider = null;
            MeowyConfig.save();
            return true;
        }
        if (this.draggingPreview) {
            this.draggingPreview = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.expandedDropdown != null) {
            this.expandedDropdown = null;
        }
        if (this.maxScroll > 0.0f && isMouseOverRect(mouseX, mouseY, this.containerX, this.containerY, this.containerW, this.containerH)) {
            this.targetScrollOffset = Mth.clamp(this.targetScrollOffset - (float) (scrollY * 18.0), 0.0f, this.maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        if (keyCode == InputConstants.KEY_ESCAPE) {
            if (this.expandedDropdown != null) {
                this.expandedDropdown = null;
                return true;
            }
            this.onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    private static boolean isMouseOverRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private abstract class SettingRow {
        final String name;
        private BooleanSupplier visibilityCondition = () -> true;

        SettingRow(String name) {
            this.name = name;
        }

        SettingRow withVisibility(BooleanSupplier condition) {
            this.visibilityCondition = condition != null ? condition : () -> true;
            return this;
        }

        boolean isVisible() {
            return this.visibilityCondition.getAsBoolean();
        }

        int getRowHeight() {
            return 32;
        }

        void render(
            GuiGraphicsExtractor graphics,
            int x,
            int y,
            int width,
            int height,
            int mouseX,
            int mouseY,
            float alpha,
            boolean drawBottomBorder
        ) {
            boolean hovered = isMouseOverRect(mouseX, mouseY, x, y, width, height);
            int rowBg = hovered ? MeowyTheme.HOVER_BG : MeowyTheme.SETTINGS_ROW_BG;
            graphics.fill(x, y, x + width, y + height - (drawBottomBorder ? 1 : 0), MeowyTheme.withAlpha(rowBg, alpha * 0.65f));

            if (drawBottomBorder) {
                graphics.fill(
                    x + 8,
                    y + height - 1,
                    x + width - 8,
                    y + height,
                    MeowyTheme.withAlpha(MeowyTheme.BORDER_COLOR, alpha)
                );
            }

            int titleY = y + (height - 8) / 2;
            graphics.text(
                MeowyScreen.this.font,
                MeowyTheme.sansText(this.name),
                x + 10,
                titleY,
                MeowyTheme.withAlpha(MeowyTheme.TEXT_PRIMARY, alpha),
                true
            );

            renderControl(graphics, x, y, width, height, mouseX, mouseY, alpha);
        }

        abstract void renderControl(
            GuiGraphicsExtractor graphics,
            int rowX,
            int rowY,
            int rowW,
            int rowH,
            int mouseX,
            int mouseY,
            float alpha
        );

        abstract boolean mouseClicked(double mouseX, double mouseY, int rowX, int rowY, int rowW, int rowH);
    }

    private final class ToggleRow extends SettingRow {
        private final Supplier<Boolean> getter;
        private final Consumer<Boolean> setter;
        private float animProgress;

        ToggleRow(String name, Supplier<Boolean> getter, Consumer<Boolean> setter) {
            super(name);
            this.getter = getter;
            this.setter = setter;
            this.animProgress = Boolean.TRUE.equals(getter.get()) ? 1.0f : 0.0f;
        }

        @Override
        void renderControl(GuiGraphicsExtractor graphics, int rowX, int rowY, int rowW, int rowH, int mouseX, int mouseY, float alpha) {
            boolean enabled = Boolean.TRUE.equals(this.getter.get());
            this.animProgress = Mth.lerp(0.28f, this.animProgress, enabled ? 1.0f : 0.0f);

            int toggleW = 30;
            int toggleH = 15;
            int toggleX = rowX + rowW - toggleW - 10;
            int toggleY = rowY + (rowH - toggleH) / 2;

            boolean hovered = isMouseOverRect(mouseX, mouseY, toggleX, toggleY, toggleW, toggleH);
            int trackBg = MeowyTheme.lerpColor(MeowyTheme.SECONDARY_BG, MeowyTheme.PRIMARY_PURPLE_ACCENT, this.animProgress);
            int border = hovered ? MeowyTheme.BRIGHT_LAVENDER : MeowyTheme.BORDER_COLOR;

            MeowyTheme.drawSoftPanel(
                graphics,
                toggleX,
                toggleY,
                toggleW,
                toggleH,
                MeowyTheme.withAlpha(trackBg, alpha),
                MeowyTheme.withAlpha(border, alpha)
            );

            int knobW = 11;
            int knobH = 11;
            int knobX = toggleX + 2 + Math.round((toggleW - knobW - 4) * this.animProgress);
            int knobY = toggleY + 2;
            int knobColor = MeowyTheme.lerpColor(MeowyTheme.TEXT_MUTED, MeowyTheme.BRIGHT_LAVENDER, this.animProgress);

            graphics.fill(knobX, knobY, knobX + knobW, knobY + knobH, MeowyTheme.withAlpha(knobColor, alpha));
        }

        @Override
        boolean mouseClicked(double mouseX, double mouseY, int rowX, int rowY, int rowW, int rowH) {
            int toggleW = 30;
            int toggleH = 15;
            int toggleX = rowX + rowW - toggleW - 10;
            int toggleY = rowY + (rowH - toggleH) / 2;
            if (isMouseOverRect(mouseX, mouseY, toggleX, toggleY, toggleW, toggleH)) {
                this.setter.accept(!Boolean.TRUE.equals(this.getter.get()));
                return true;
            }
            return false;
        }
    }

    private final class DropdownRow extends SettingRow {
        private final String[] options;
        private final IntSupplier selectedIndexGetter;
        private final IntConsumer onSelectIndex;
        private int lastBoxX, lastBoxY, lastBoxW, lastBoxH;

        DropdownRow(String name, String[] options, IntSupplier selectedIndexGetter, IntConsumer onSelectIndex) {
            super(name);
            this.options = options;
            this.selectedIndexGetter = selectedIndexGetter;
            this.onSelectIndex = onSelectIndex;
        }

        private int computeBoxWidth() {
            int maxW = 96;
            for (String opt : this.options) {
                maxW = Math.max(maxW, MeowyScreen.this.font.width(opt) + 28);
            }
            return maxW;
        }

        @Override
        void renderControl(GuiGraphicsExtractor graphics, int rowX, int rowY, int rowW, int rowH, int mouseX, int mouseY, float alpha) {
            int idx = Mth.clamp(this.selectedIndexGetter.getAsInt(), 0, this.options.length - 1);
            String label = this.options[idx];
            int boxW = computeBoxWidth();
            int boxH = 18;
            int boxX = rowX + rowW - boxW - 10;
            int boxY = rowY + (rowH - boxH) / 2;

            this.lastBoxX = boxX;
            this.lastBoxY = boxY;
            this.lastBoxW = boxW;
            this.lastBoxH = boxH;

            boolean expanded = (MeowyScreen.this.expandedDropdown == this);
            boolean hovered = isMouseOverRect(mouseX, mouseY, boxX, boxY, boxW, boxH);
            int bg = (hovered || expanded) ? MeowyTheme.HOVER_BG : MeowyTheme.SECONDARY_BG;
            int border = (hovered || expanded) ? MeowyTheme.PRIMARY_PURPLE_ACCENT : MeowyTheme.BORDER_COLOR;

            MeowyTheme.drawSoftPanel(
                graphics,
                boxX,
                boxY,
                boxW,
                boxH,
                MeowyTheme.withAlpha(bg, alpha),
                MeowyTheme.withAlpha(border, alpha)
            );

            graphics.text(
                MeowyScreen.this.font,
                MeowyTheme.sansText(label),
                boxX + 7,
                boxY + 5,
                MeowyTheme.withAlpha(MeowyTheme.TEXT_PRIMARY, alpha),
                true
            );

            graphics.text(
                MeowyScreen.this.font,
                MeowyTheme.pixelText(expanded ? "^" : "v", false),
                boxX + boxW - 12,
                boxY + 5,
                MeowyTheme.withAlpha(MeowyTheme.BRIGHT_LAVENDER, alpha),
                true
            );
        }

        void renderDropdownMenuOverlay(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float alpha) {
            int itemH = 20;
            int menuH = this.options.length * itemH + 2;
            int menuX = this.lastBoxX;
            int menuY = this.lastBoxY + this.lastBoxH + 2;
            if (menuY + menuH > MeowyScreen.this.winY + MeowyScreen.this.winH - 6) {
                menuY = this.lastBoxY - menuH - 2;
            }

            graphics.fill(menuX - 2, menuY - 2, menuX + this.lastBoxW + 2, menuY + menuH + 2, MeowyTheme.withAlpha(0x88000000, alpha));
            MeowyTheme.drawSoftPanel(
                graphics,
                menuX,
                menuY,
                this.lastBoxW,
                menuH,
                MeowyTheme.withAlpha(MeowyTheme.MAIN_WINDOW_BG, alpha),
                MeowyTheme.withAlpha(MeowyTheme.PRIMARY_PURPLE_ACCENT, alpha)
            );

            int selectedIdx = Mth.clamp(this.selectedIndexGetter.getAsInt(), 0, this.options.length - 1);
            for (int i = 0; i < this.options.length; i++) {
                int optY = menuY + 1 + i * itemH;
                boolean isSelected = (i == selectedIdx);
                boolean optHovered = isMouseOverRect(mouseX, mouseY, menuX + 1, optY, this.lastBoxW - 2, itemH);

                if (optHovered) {
                    graphics.fill(menuX + 1, optY, menuX + this.lastBoxW - 1, optY + itemH, MeowyTheme.withAlpha(MeowyTheme.HOVER_BG, alpha));
                } else if (isSelected) {
                    graphics.fill(menuX + 1, optY, menuX + this.lastBoxW - 1, optY + itemH, MeowyTheme.withAlpha(MeowyTheme.SELECTED_CATEGORY_BG, alpha));
                }
                if (isSelected) {
                    graphics.fill(menuX + 1, optY + 2, menuX + 4, optY + itemH - 2, MeowyTheme.withAlpha(MeowyTheme.BRIGHT_LAVENDER, alpha));
                }

                int textColor = isSelected ? MeowyTheme.BRIGHT_LAVENDER : (optHovered ? MeowyTheme.TEXT_PRIMARY : MeowyTheme.TEXT_SECONDARY);
                graphics.text(
                    MeowyScreen.this.font,
                    MeowyTheme.sansText(this.options[i]),
                    menuX + 8,
                    optY + 6,
                    MeowyTheme.withAlpha(textColor, alpha),
                    true
                );
            }
        }

        boolean handleMenuClick(double mouseX, double mouseY) {
            int itemH = 20;
            int menuH = this.options.length * itemH + 2;
            int menuX = this.lastBoxX;
            int menuY = this.lastBoxY + this.lastBoxH + 2;
            if (menuY + menuH > MeowyScreen.this.winY + MeowyScreen.this.winH - 6) {
                menuY = this.lastBoxY - menuH - 2;
            }
            for (int i = 0; i < this.options.length; i++) {
                int optY = menuY + 1 + i * itemH;
                if (isMouseOverRect(mouseX, mouseY, menuX, optY, this.lastBoxW, itemH)) {
                    this.onSelectIndex.accept(i);
                    return true;
                }
            }
            return isMouseOverRect(mouseX, mouseY, this.lastBoxX, this.lastBoxY, this.lastBoxW, this.lastBoxH);
        }

        @Override
        boolean mouseClicked(double mouseX, double mouseY, int rowX, int rowY, int rowW, int rowH) {
            int boxW = computeBoxWidth();
            int boxH = 18;
            int boxX = rowX + rowW - boxW - 10;
            int boxY = rowY + (rowH - boxH) / 2;
            if (isMouseOverRect(mouseX, mouseY, boxX, boxY, boxW, boxH)) {
                MeowyScreen.this.expandedDropdown = (MeowyScreen.this.expandedDropdown == this) ? null : this;
                return true;
            }
            return false;
        }
    }

    private final class SliderRow extends SettingRow {
        private final float min;
        private final float max;
        private final float step;
        private final Supplier<Float> getter;
        private final Consumer<Float> setter;
        private final java.util.function.Function<Float, String> formatter;

        private int lastTrackX;
        private int lastTrackW;

        SliderRow(
            String name,
            float min,
            float max,
            float step,
            Supplier<Float> getter,
            Consumer<Float> setter,
            java.util.function.Function<Float, String> formatter
        ) {
            super(name);
            this.min = min;
            this.max = max;
            this.step = step;
            this.getter = getter;
            this.setter = setter;
            this.formatter = formatter;
        }

        @Override
        void renderControl(GuiGraphicsExtractor graphics, int rowX, int rowY, int rowW, int rowH, int mouseX, int mouseY, float alpha) {
            float value = Mth.clamp(this.getter.get(), this.min, this.max);
            float normalized = (value - this.min) / (this.max - this.min);

            int trackW = 92;
            int trackH = 5;
            int trackX = rowX + rowW - trackW - 10;
            int trackY = rowY + (rowH - trackH) / 2;

            this.lastTrackX = trackX;
            this.lastTrackW = trackW;

            String valueText = this.formatter.apply(value);
            int valW = MeowyScreen.this.font.width(valueText);
            graphics.text(
                MeowyScreen.this.font,
                MeowyTheme.sansText(valueText),
                trackX - valW - 8,
                trackY - 2,
                MeowyTheme.withAlpha(MeowyTheme.TEXT_PRIMARY, alpha),
                true
            );

            graphics.fill(
                trackX,
                trackY,
                trackX + trackW,
                trackY + trackH,
                MeowyTheme.withAlpha(MeowyTheme.SECONDARY_BG, alpha)
            );
            graphics.outline(
                trackX,
                trackY,
                trackW,
                trackH,
                MeowyTheme.withAlpha(MeowyTheme.BORDER_COLOR, alpha)
            );

            int activeW = Math.round(trackW * normalized);
            if (activeW > 0) {
                graphics.fill(
                    trackX,
                    trackY,
                    trackX + activeW,
                    trackY + trackH,
                    MeowyTheme.withAlpha(MeowyTheme.PRIMARY_PURPLE_ACCENT, alpha)
                );
            }

            int handleW = 6;
            int handleH = 13;
            int handleX = trackX + Math.clamp(activeW - handleW / 2, 0, trackW - handleW);
            int handleY = rowY + (rowH - handleH) / 2;
            graphics.fill(
                handleX,
                handleY,
                handleX + handleW,
                handleY + handleH,
                MeowyTheme.withAlpha(MeowyTheme.BRIGHT_LAVENDER, alpha)
            );
        }

        @Override
        boolean mouseClicked(double mouseX, double mouseY, int rowX, int rowY, int rowW, int rowH) {
            int hitX = this.lastTrackX - 6;
            int hitW = this.lastTrackW + 12;
            if (isMouseOverRect(mouseX, mouseY, hitX, rowY + 2, hitW, rowH - 4)) {
                MeowyScreen.this.activeDraggingSlider = this;
                updateFromMouse(mouseX);
                return true;
            }
            return false;
        }

        void updateFromMouse(double mouseX) {
            if (this.lastTrackW <= 0) return;
            float pct = Mth.clamp((float) ((mouseX - this.lastTrackX) / (double) this.lastTrackW), 0.0f, 1.0f);
            float raw = this.min + pct * (this.max - this.min);
            if (this.step > 0.0f) {
                raw = Math.round(raw / this.step) * this.step;
            }
            float clamped = Mth.clamp(raw, this.min, this.max);
            this.setter.accept(clamped);
        }
    }
}
