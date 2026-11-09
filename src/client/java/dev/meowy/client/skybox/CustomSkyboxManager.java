package dev.meowy.client.skybox;

import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads OptiFine ("optifine/sky/world0/...") and MCPatcher ("mcpatcher/sky/world0/...")
 * skybox layers from active resource packs. Works seamlessly alongside Sodium.
 */
@SuppressWarnings("deprecation")
public final class CustomSkyboxManager implements SimpleSynchronousResourceReloadListener {
    private static final Logger LOGGER = LoggerFactory.getLogger("Meowy-Skybox");
    private static final CustomSkyboxManager INSTANCE = new CustomSkyboxManager();
    private static final Identifier RELOAD_ID = Identifier.fromNamespaceAndPath("meowy", "custom_skybox_loader");
    private static final Pattern SKY_FILE_PATTERN = Pattern.compile(".*/sky(\\d+)\\.properties$");

    private final List<SkyboxLayer> overworldLayers = new ArrayList<>();

    private CustomSkyboxManager() {}

    public static CustomSkyboxManager getInstance() {
        return INSTANCE;
    }

    public void registerReloadListener() {
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(this);
    }

    @Override
    public Identifier getFabricId() {
        return RELOAD_ID;
    }

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        this.overworldLayers.clear();
        loadLayersFromDirectory(resourceManager, "optifine/sky/world0");
        if (this.overworldLayers.isEmpty()) {
            loadLayersFromDirectory(resourceManager, "mcpatcher/sky/world0");
        }
        this.overworldLayers.sort(Comparator.comparingInt(SkyboxLayer::layerIndex));
        if (!this.overworldLayers.isEmpty()) {
            LOGGER.info("Loaded {} custom skybox layer(s) from active resource packs", this.overworldLayers.size());
        }
    }

    private void loadLayersFromDirectory(ResourceManager resourceManager, String baseDir) {
        Map<Identifier, Resource> found = resourceManager.listResources(
            baseDir,
            id -> id.getPath().endsWith(".properties")
        );

        for (Map.Entry<Identifier, Resource> entry : found.entrySet()) {
            Identifier propId = entry.getKey();
            Matcher matcher = SKY_FILE_PATTERN.matcher(propId.getPath());
            if (!matcher.matches()) {
                continue;
            }
            int layerIdx;
            try {
                layerIdx = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                continue;
            }

            try (InputStream stream = entry.getValue().open();
                 InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                Properties props = new Properties();
                props.load(reader);
                SkyboxLayer layer = SkyboxLayer.parse(layerIdx, propId, props);
                if (layer != null) {
                    Optional<Resource> texResource = resourceManager.getResource(layer.textureId());
                    if (texResource.isPresent()) {
                        this.overworldLayers.add(layer);
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to parse custom skybox properties {}: {}", propId, e.getMessage());
            }
        }
    }

    public List<SkyboxLayer> getOverworldLayers() {
        return this.overworldLayers;
    }

    public boolean hasActiveSkyboxes() {
        return !this.overworldLayers.isEmpty();
    }
}
