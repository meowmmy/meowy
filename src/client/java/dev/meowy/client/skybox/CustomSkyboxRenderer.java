package dev.meowy.client.skybox;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import dev.meowy.client.config.MeowyConfig;
import dev.meowy.client.config.MeowyConfig.SkyboxFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Renders OptiFine / MCPatcher 6-face custom skybox layers (3x2 UV grid) using
 * an unlit POSITION_TEX_COLOR pipeline (RenderPipelines.GUI_TEXTURED) so all 6 faces
 * render at 100% full, uniform brightness without directional entity shading or lightmap dimming,
 * modulated directly by the user's Sky Brightness slider (0% - 200%).
 */
public final class CustomSkyboxRenderer {
    private static final float SKYBOX_DISTANCE = 100.0f;
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
    private static final PoseStack SCRATCH_POSE_STACK = new PoseStack();
    private static final Quaternionf SCRATCH_ROTATION = new Quaternionf();
    private static final StagedVertexBuffer STAGED_BUFFER = new StagedVertexBuffer(
        () -> "Meowy Custom Skybox Buffer",
        RenderType.SMALL_BUFFER_SIZE
    );

    private record LayerDraw(PreparedRenderType prepared, StagedVertexBuffer.Draw draw, float brightnessBoost) {}

    private CustomSkyboxRenderer() {}

    public static void renderCustomSkyboxes(Matrix4f modelViewMatrix, float partialTicks) {
        MeowyConfig cfg = MeowyConfig.get();
        if (!cfg.customSkyboxEnabled) {
            return;
        }
        float brightnessSetting = cfg.skyboxBrightness;
        if (brightnessSetting <= 0.005f) {
            return;
        }
        List<SkyboxLayer> layers = CustomSkyboxManager.getInstance().getOverworldLayers();
        if (layers.isEmpty()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (level == null || client.gameRenderer == null || level.dimension() != Level.OVERWORLD) {
            return;
        }

        BlockPos camBlockPos = client.gameRenderer.mainCamera().blockPosition();
        long clockTime = level.getOverworldClockTime();
        double frac = Mth.frac(((double) clockTime + partialTicks) / 24000.0 - 0.25);
        double cosPart = 0.5 - Math.cos(frac * Math.PI) / 2.0;
        float celestialAngle = (float) (frac * 2.0 + cosPart) / 3.0f;

        PoseStack poseStack = SCRATCH_POSE_STACK;
        poseStack.pushPose();
        poseStack.mulPose(modelViewMatrix);

        List<LayerDraw> draws = new ArrayList<>(layers.size());
        SkyboxFormat formatMode = cfg.skyboxFormat != null ? cfg.skyboxFormat : SkyboxFormat.OPTIFINE_STANDARD;

        // Use RenderPipelines.GUI_TEXTURED (core/position_tex_color) for 100% unlit, full-brightness skybox rendering
        RenderPipeline unlitPipeline = RenderPipelines.GUI_TEXTURED;
        VertexFormat format = unlitPipeline.getVertexFormatBinding(0);
        PrimitiveTopology topology = unlitPipeline.getPrimitiveTopology();

        try {
            for (SkyboxLayer layer : layers) {
                float alpha = layer.computeAlpha(level, camBlockPos, partialTicks);
                if (alpha <= 0.005f) {
                    continue;
                }
                // Prepare texture view & sampler for Sampler0 via RenderTypes helper
                RenderType texHelper = RenderTypes.entityTranslucent(layer.textureId());
                PreparedRenderType prepared = texHelper.prepare();

                StagedVertexBuffer.Draw draw = STAGED_BUFFER.appendDraw(
                    format,
                    topology,
                    topology == PrimitiveTopology.QUADS ? RenderSystem.getProjectionType().vertexSorting() : null
                );
                VertexConsumer buffer = STAGED_BUFFER.getVertexBuilder(draw);
                buildLayerGeometry(poseStack, buffer, layer, celestialAngle, alpha, brightnessSetting, formatMode);
                draws.add(new LayerDraw(prepared, draw, Math.max(1.0f, brightnessSetting)));
            }

            if (draws.isEmpty()) {
                return;
            }

            STAGED_BUFFER.upload();

            RenderTarget mainTarget = client.gameRenderer.mainRenderTarget();
            GpuTextureView colorTexture = mainTarget.getColorTextureView();
            GpuTextureView depthTexture = mainTarget.getDepthTextureView();
            if (colorTexture == null) {
                return;
            }

            for (LayerDraw layerDraw : draws) {
                StagedVertexBuffer.ExecuteInfo info = STAGED_BUFFER.getExecuteInfo(layerDraw.draw());
                if (info == null) {
                    continue;
                }

                float boost = layerDraw.brightnessBoost();
                Vector4f colorModulator = new Vector4f(boost, boost, boost, 1.0f);
                GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                    .writeTransform(RenderSystem.getModelViewMatrixCopy(), colorModulator, MODEL_OFFSET, TEXTURE_MATRIX);

                try (RenderPass renderPass = RenderSystem.getDevice()
                    .createCommandEncoder()
                    .createRenderPass(
                        () -> "meowy_custom_skybox_unlit",
                        colorTexture,
                        Optional.empty(),
                        depthTexture,
                        OptionalDouble.empty()
                    )) {
                    renderPass.setPipeline(unlitPipeline);
                    RenderSystem.bindDefaultUniforms(renderPass);
                    renderPass.setUniform("DynamicTransforms", dynamicTransforms);
                    renderPass.setVertexBuffer(0, info.vertexBuffer().slice());

                    for (PreparedRenderType.Texture tex : layerDraw.prepared().textures()) {
                        if ("Sampler0".equals(tex.name())) {
                            renderPass.bindTexture(tex.name(), tex.textureView(), tex.sampler());
                        }
                    }

                    renderPass.setIndexBuffer(info.indexBuffer(), info.indexType());
                    renderPass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
                }
            }
        } finally {
            poseStack.popPose();
            STAGED_BUFFER.endFrame();
        }
    }

    private static void buildLayerGeometry(
        PoseStack poseStack,
        VertexConsumer buffer,
        SkyboxLayer layer,
        float celestialAngle,
        float alpha,
        float brightnessSetting,
        SkyboxFormat formatMode
    ) {
        poseStack.pushPose();

        if (layer.rotate()) {
            Vector3f axis = layer.axis();
            float angleDegrees = celestialAngle * 360.0f * layer.speed();
            Quaternionf rotation = SCRATCH_ROTATION.fromAxisAngleDeg(axis.x, axis.y, axis.z, angleDegrees);
            poseStack.mulPose(rotation);
        }

        int color = computeBlendColor(layer.blendMode(), alpha, brightnessSetting);
        boolean legacyFlip = (formatMode == SkyboxFormat.LEGACY_1_8_9);

        // Step 1: Rotate to West (-X) face (Side 4)
        poseStack.mulPose(Axis.XP.rotationDegrees(90.0f));
        poseStack.mulPose(Axis.ZP.rotationDegrees(-90.0f));
        renderSide(poseStack.last(), buffer, 4, color, legacyFlip);

        // Step 2: Top (+Y) face (Side 1)
        poseStack.pushPose();
        poseStack.mulPose(Axis.XP.rotationDegrees(90.0f));
        renderSide(poseStack.last(), buffer, legacyFlip ? 0 : 1, color, legacyFlip);
        poseStack.popPose();

        // Step 3: Bottom (-Y) face (Side 0)
        poseStack.pushPose();
        poseStack.mulPose(Axis.XP.rotationDegrees(-90.0f));
        renderSide(poseStack.last(), buffer, legacyFlip ? 1 : 0, color, legacyFlip);
        poseStack.popPose();

        // Step 4: North (-Z) face (Side 5)
        poseStack.mulPose(Axis.ZP.rotationDegrees(90.0f));
        renderSide(poseStack.last(), buffer, 5, color, legacyFlip);

        // Step 5: East (+X) face (Side 2)
        poseStack.mulPose(Axis.ZP.rotationDegrees(90.0f));
        renderSide(poseStack.last(), buffer, 2, color, legacyFlip);

        // Step 6: South (+Z) face (Side 3)
        poseStack.mulPose(Axis.ZP.rotationDegrees(90.0f));
        renderSide(poseStack.last(), buffer, 3, color, legacyFlip);

        poseStack.popPose();
    }

    private static int computeBlendColor(SkyboxLayer.BlendMode mode, float alpha, float brightnessSetting) {
        float clampedBrightness = Math.clamp(brightnessSetting, 0.0f, 1.0f);
        int a = Math.clamp(Math.round(alpha * 255.0f), 0, 255);
        int c = Math.clamp(Math.round(clampedBrightness * 255.0f), 0, 255);
        int ac = Math.clamp(Math.round(alpha * clampedBrightness * 255.0f), 0, 255);
        return switch (mode) {
            case ADD, SCREEN, DODGE -> ARGB.color(a, ac, ac, ac);
            default -> ARGB.color(a, c, c, c);
        };
    }

    private static void renderSide(
        PoseStack.Pose pose,
        VertexConsumer buffer,
        int side,
        int color,
        boolean flipV
    ) {
        int tx = side % 3;
        int ty = side / 3;
        float u0 = (float) tx / 3.0f;
        float u1 = (float) (tx + 1) / 3.0f;
        float v0 = flipV ? (float) (ty + 1) / 2.0f : (float) ty / 2.0f;
        float v1 = flipV ? (float) ty / 2.0f : (float) (ty + 1) / 2.0f;

        addSkyVertex(buffer, pose, -SKYBOX_DISTANCE, -SKYBOX_DISTANCE, -SKYBOX_DISTANCE, u0, v0, color);
        addSkyVertex(buffer, pose, -SKYBOX_DISTANCE, -SKYBOX_DISTANCE,  SKYBOX_DISTANCE, u0, v1, color);
        addSkyVertex(buffer, pose,  SKYBOX_DISTANCE, -SKYBOX_DISTANCE,  SKYBOX_DISTANCE, u1, v1, color);
        addSkyVertex(buffer, pose,  SKYBOX_DISTANCE, -SKYBOX_DISTANCE, -SKYBOX_DISTANCE, u1, v0, color);
    }

    private static void addSkyVertex(
        VertexConsumer buffer,
        PoseStack.Pose pose,
        float x,
        float y,
        float z,
        float u,
        float v,
        int color
    ) {
        buffer.addVertex(pose, x, y, z)
            .setUv(u, v)
            .setColor(color);
    }
}
