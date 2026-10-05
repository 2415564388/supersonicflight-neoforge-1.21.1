package com.baranhan123.supersonicflight.client.vfx;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * The "paint pixel" shockwave geometry, shared by everything that draws the effect.
 *
 * <p>Moved out of {@code PunchVFXManager}, where this look originated. A ring is not a smooth band
 * but a grid of square cells whose world size grows as the ring expands, which is what makes it read
 * as chunky and hand-painted rather than as a sprite being scaled up.
 *
 * <p>Callers hand in a matrix mapping local grid space into whatever space their vertices are
 * emitted in. The ring always lies in the local XY plane with cells at
 * {@code (gridX * pixelSize, gridY * pixelSize, 0)}; {@link #facingTransform} builds the common case
 * of a ring facing a chosen direction.
 */
public final class ShockwaveGrid {

    /** Band thickness in grid cells, measured inwards from the ring's outer edge. */
    public static final float BAND_WIDTH_CELLS = 2.0f;
    /** Antialias feather in grid cells, applied to both edges of the band. */
    private static final float BAND_FEATHER = 0.3f;

    /** Band edges as fractions of the ring radius: a bright core from 85% out to a soft 112%. */
    public static final float BAND_INNER_SCALE = 0.85f;
    public static final float BAND_OUTER_SCALE = 1.12f;
    public static final int BAND_SEGMENTS = 48;

    private ShockwaveGrid() {
    }

    /**
     * Emits a smooth radial band rather than the grid.
     *
     * <p>Currently unused — kept because it is the A/B of a look decision still in flight: the punch's
     * paint-pixel style has to be re-scaled to work at the flight moments' 11–22 block radii, whereas
     * this band is what they draw. Delete it once that is settled.
     */
    public static void emitBand(VertexConsumer buffer, Matrix4f localToView, float radius,
                                int red, int green, int blue, int coreAlpha) {
        float innerR = radius * BAND_INNER_SCALE;
        float outerR = radius * BAND_OUTER_SCALE;

        for (int i = 0; i < BAND_SEGMENTS; i++) {
            double a1 = (i * 2.0 * Math.PI) / BAND_SEGMENTS;
            double a2 = ((i + 1) * 2.0 * Math.PI) / BAND_SEGMENTS;
            float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            float c2 = (float) Math.cos(a2), s2 = (float) Math.sin(a2);

            // Inner edge lit, outer edge transparent — a soft outward glow.
            buffer.addVertex(localToView, c1 * innerR, s1 * innerR, 0.0f).setColor(red, green, blue, coreAlpha);
            buffer.addVertex(localToView, c1 * outerR, s1 * outerR, 0.0f).setColor(red, green, blue, 0);
            buffer.addVertex(localToView, c2 * outerR, s2 * outerR, 0.0f).setColor(red, green, blue, 0);
            buffer.addVertex(localToView, c2 * innerR, s2 * innerR, 0.0f).setColor(red, green, blue, coreAlpha);
        }
    }

    /**
     * Emits one ring.
     *
     * <p>{@code progress} is deliberately <b>not</b> a parameter: radius, grid resolution and peak
     * alpha are already its complete projection, and passing it as well would leave two sources of
     * truth for one animation, free to drift apart.
     *
     * @param radius         world radius of the ring's outer edge
     * @param gridResolution radius measured in cells — the band's world thickness is
     *                       {@code BAND_WIDTH_CELLS * radius / gridResolution}, so this controls how
     *                       chunky the ring looks independently of how big it is
     */
    public static void emit(VertexConsumer buffer, Matrix4f localToView,
                            float radius, float gridResolution,
                            int red, int green, int blue, int peakAlpha) {
        emit(buffer, localToView, radius, gridResolution, red, green, blue, peakAlpha, BAND_WIDTH_CELLS);
    }

    /** Overload for callers that need a different band thickness. */
    public static void emit(VertexConsumer buffer, Matrix4f localToView,
                            float radius, float gridResolution,
                            int red, int green, int blue, int peakAlpha,
                            float bandWidthCells) {
        float resolution = Math.max(1.0f, gridResolution);
        float pixelWorldSize = radius / resolution;
        float outerRadius = resolution;
        float innerRadius = Math.max(0.0f, resolution - bandWidthCells);
        int loopRadius = (int) resolution + 1;

        // Only cells inside the band are lit. Each lit cell of the first quadrant is mirrored into
        // the other three, so the loop only does one quadrant's worth of the distance test.
        for (int x = 0; x <= loopRadius; x++) {
            for (int y = 0; y <= loopRadius; y++) {
                float dist = (float) Math.sqrt(x * x + y * y);
                if (dist > outerRadius + BAND_FEATHER || dist < innerRadius - BAND_FEATHER) continue;

                pixel(buffer, localToView, x, y, pixelWorldSize, red, green, blue, peakAlpha);
                if (x != 0) pixel(buffer, localToView, -x, y, pixelWorldSize, red, green, blue, peakAlpha);
                if (y != 0) pixel(buffer, localToView, x, -y, pixelWorldSize, red, green, blue, peakAlpha);
                if (x != 0 && y != 0) pixel(buffer, localToView, -x, -y, pixelWorldSize, red, green, blue, peakAlpha);
            }
        }
    }

    /** One grid cell, as a flat quad in the ring's local XY plane. */
    private static void pixel(VertexConsumer buffer, Matrix4f localToView, int gridX, int gridY,
                              float pixelWorldSize, int red, int green, int blue, int alpha) {
        float cx = gridX * pixelWorldSize;
        float cy = gridY * pixelWorldSize;
        float half = pixelWorldSize * 0.5f;

        buffer.addVertex(localToView, cx - half, cy - half, 0.0f).setColor(red, green, blue, alpha);
        buffer.addVertex(localToView, cx + half, cy - half, 0.0f).setColor(red, green, blue, alpha);
        buffer.addVertex(localToView, cx + half, cy + half, 0.0f).setColor(red, green, blue, alpha);
        buffer.addVertex(localToView, cx - half, cy + half, 0.0f).setColor(red, green, blue, alpha);
    }

    /**
     * A matrix placing a ring at {@code centerRelativeToCamera}, with its plane perpendicular to
     * {@code normal}, for a rotation-only view matrix such as the one at {@code AFTER_LEVEL}.
     *
     * <p>The in-plane rotation is arbitrary, deliberately. The emitted grid is radially symmetric
     * <em>and</em> mirrored into all four quadrants, so which way "right" points cannot be seen, and
     * the sign of {@code normal} does not matter either. That is what lets this avoid needing a
     * stable, continuously varying basis.
     */
    public static Matrix4f facingTransform(Matrix4f view, Vec3 centerRelativeToCamera, Vec3 normal) {
        Vec3 n = normal.normalize();
        // Vec3.normalize() hands back the zero vector when there is nothing to normalize from.
        if (n.lengthSqr() < 1.0E-6) n = new Vec3(0, 1, 0);

        Vec3 helper = Math.abs(n.y) > 0.99 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 right = cross(n, helper).normalize();
        if (right.lengthSqr() < 1.0E-6) right = new Vec3(1, 0, 0);
        Vec3 up = cross(right, n).normalize();

        // Columns, i.e. the images of the local x/y/z axes. JOML's mXY is ROW x, COLUMN y — note
        // this is the opposite of what the names suggest at a glance, and getting it wrong builds
        // the transpose, i.e. the inverse rotation: a ring asked to be horizontal comes out
        // vertical. Verified by transforming the unit axes through both forms.
        Matrix4f basis = new Matrix4f()
                .m00((float) right.x).m01((float) right.y).m02((float) right.z)
                .m10((float) up.x).m11((float) up.y).m12((float) up.z)
                .m20((float) n.x).m21((float) n.y).m22((float) n.z);

        // view * T(center) * basis. The offset is a camera-relative world vector, so it has to be
        // rotated by the view but NOT by the ring's own basis — hence translate before mul.
        return new Matrix4f(view)
                .translate((float) centerRelativeToCamera.x,
                        (float) centerRelativeToCamera.y,
                        (float) centerRelativeToCamera.z)
                .mul(basis);
    }

    private static Vec3 cross(Vec3 a, Vec3 b) {
        return new Vec3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x);
    }

    /**
     * ModelViewMat must be identity while the shared buffer flushes, so every caller brackets its
     * flush with these two.
     *
     * <p>Always pair them in a {@code try/finally}: an exception thrown mid-render would otherwise
     * leak an identity model-view into the held-item pass and the GUI, which is visible as the item
     * and the whole HUD shifting.
     */
    public static void pushIdentityModelView() {
        RenderSystem.getModelViewStack().pushMatrix();
        RenderSystem.getModelViewStack().identity();
        RenderSystem.applyModelViewMatrix();
    }

    /** Undoes {@link #pushIdentityModelView()}. */
    public static void popModelView() {
        RenderSystem.getModelViewStack().popMatrix();
        RenderSystem.applyModelViewMatrix();
    }
}
