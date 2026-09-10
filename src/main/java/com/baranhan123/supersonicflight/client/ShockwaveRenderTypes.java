package com.baranhan123.supersonicflight.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/**
 * Render types shared by the in-world shockwave effects (Mach disks and speed lines).
 *
 * The rings/lines are drawn as flat {@link DefaultVertexFormat#POSITION_COLOR} quads with an
 * ADDITIVE blend, which makes the effect pop brightly against the sky instead of the standard
 * alpha blend used by vanilla translucent entity rendering.
 */
public final class ShockwaveRenderTypes {

    /** Additive blend: src alpha × color added onto the destination (glows brighter, no darkening). */
    private static final RenderStateShard.TransparencyStateShard ADDITIVE_TRANSPARENCY =
            new RenderStateShard.TransparencyStateShard("supersonicflight_additive",
                    () -> {
                        RenderSystem.enableBlend();
                        RenderSystem.blendFuncSeparate(
                                GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
                    },
                    () -> {
                        RenderSystem.disableBlend();
                        RenderSystem.defaultBlendFunc();
                    });

    private static RenderType additiveQuad;

    private ShockwaveRenderTypes() {
    }

    /** A no-texture, additive-blended quad render type for POSITION_COLOR geometry. */
    public static RenderType additiveQuad() {
        if (additiveQuad == null) {
            additiveQuad = RenderType.create(
                    "supersonicflight_additive_quad",
                    DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.QUADS,
                    786432, // large buffer: many ring segments + speed lines per frame
                    false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getPositionColorShader))
                            .setTransparencyState(ADDITIVE_TRANSPARENCY)
                            .setCullState(RenderType.NO_CULL)
                            .createCompositeState(false));
        }
        return additiveQuad;
    }
}
