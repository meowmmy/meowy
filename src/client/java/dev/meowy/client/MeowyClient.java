package dev.meowy.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.Command;
import dev.meowy.client.config.MeowyConfig;
import dev.meowy.client.discord.DiscordRpcManager;
import dev.meowy.client.gui.MeowyScreen;
import dev.meowy.client.render.HitmarkerManager;
import dev.meowy.client.skybox.CustomSkyboxManager;
import dev.ow.client.animation.CassidyAnimator;
import dev.ow.client.animation.PistolAnimator;
import dev.ow.client.projectile.LocalArrowTracker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-only entrypoint for Meowy (Minecraft 26.2 Fabric).
 */
public final class MeowyClient implements ClientModInitializer {
    public static final String MOD_ID = "meowy";
    public static final Logger LOGGER = LoggerFactory.getLogger("Meowy");

    public static final Identifier PIXEL_FONT_ID = Identifier.fromNamespaceAndPath(MOD_ID, "pixel_header");
    public static final Identifier SANS_FONT_ID = Identifier.fromNamespaceAndPath(MOD_ID, "clean_sans");

    private static KeyMapping.Category keyCategory;
    private static KeyMapping openGuiKeyMapping;
    private static volatile boolean openGuiNextTick = false;
    private static MeowyConfig.Category lastSelectedCategory = MeowyConfig.Category.PLAYER;
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    @Override
    public void onInitializeClient() {
        MeowyConfig.load();

        keyCategory = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "general"));
        openGuiKeyMapping = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.meowy.open_gui",
            InputConstants.Type.KEYSYM,
            InputConstants.UNKNOWN.getValue(),
            keyCategory
        ));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(
                ClientCommands.literal("meowy")
                    .executes(context -> {
                        openGuiNextTick = true;
                        return Command.SINGLE_SUCCESS;
                    })
                    .then(
                        ClientCommands.literal("debug")
                            .executes(context -> {
                                boolean on = HitmarkerManager.toggleDebug();
                                LocalArrowTracker.setDebug(on);
                                context.getSource().sendFeedback(
                                    Component.literal("[Meowy] Hitmarker & arrow debug logging " + (on ? "ON" : "OFF") + " (see logs/latest.log)")
                                );
                                return Command.SINGLE_SUCCESS;
                            })
                    )
            );
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openGuiKeyMapping.consumeClick()) {
                openConfigScreen(client);
            }
            if (openGuiNextTick) {
                openGuiNextTick = false;
                openConfigScreen(client);
            }
            try {
                HitmarkerManager.tick(client);
            } catch (Throwable t) {
                logOnce("HitmarkerManager.tick", t);
            }
            try {
                PistolAnimator.tick(client);
            } catch (Throwable t) {
                logOnce("PistolAnimator.tick", t);
            }
            try {
                CassidyAnimator.tick(client);
            } catch (Throwable t) {
                logOnce("CassidyAnimator.tick", t);
            }
            try {
                LocalArrowTracker.tickCleanup(client);
            } catch (Throwable t) {
                logOnce("LocalArrowTracker.tickCleanup", t);
            }
        });

        CustomSkyboxManager.getInstance().registerReloadListener();

        DiscordRpcManager.getInstance().start();
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> DiscordRpcManager.getInstance().stop());

        LOGGER.info("Meowy v1.0.0 initialized for Minecraft 26.2 (100% Client-Side)");
    }

    public static void openConfigScreen(Minecraft client) {
        if (client.gui.screen() instanceof MeowyScreen) {
            return;
        }
        client.gui.setScreen(new MeowyScreen(client.gui.screen(), lastSelectedCategory));
    }

    public static void setLastSelectedCategory(MeowyConfig.Category category) {
        if (category != null) {
            lastSelectedCategory = category;
        }
    }

    public static void logOnce(String where, Throwable t) {
        if (REPORTED.add(where)) {
            LOGGER.error("[Meowy] Unexpected error in {} (further errors from here are suppressed)", where, t);
        }
    }
}
