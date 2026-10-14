package incari.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/**
 * Entity occlusion culling (ADR 0024): an entity in the view frustum is still skipped when
 * none of the sections it's in can be seen from the camera, by the same graph that decides which
 * terrain sections are drawn ({@code SectionOcclusionGraph}): its sections were never reached
 * through open faces from the camera's. Mobs in caves under the player, items behind walls and the
 * crowd on the far side of a hill cost nothing to extract, animate or draw.
 *
 * <p>What the game draws through walls is never culled: glowing entities (their outline shows
 * through terrain), entities whose names show (a name tag draws over terrain), players, leashes,
 * and anything the camera rides or is. Neither is an entity the graph can't place: outside the view
 * area, or bigger than a few sections.
 *
 * <p>Render thread, once per entity per frame: a handful of array lookups, nothing allocated.
 */
public final class EntityOcclusion {

    /** Entities spanning more sections than this across are never culled (the ender dragon). */
    private static final int MAX_SECTIONS_ACROSS = 3;

    private static boolean enabled = true;

    /** Render thread only. */
    private static final BlockPos.MutableBlockPos SCRATCH = new BlockPos.MutableBlockPos();

    private EntityOcclusion() {}

    /** Turns the culling on or off (the page's {@code ?entityculling=off}, to compare). */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /** Hook: whether an entity that passed the game's own visibility checks can be seen. */
    public static boolean visible(Entity entity, LevelRenderer renderer, float partialTicks) {
        if (!enabled || exempt(entity)) {
            return true;
        }
        ViewArea viewArea = renderer.viewArea();
        if (viewArea == null) {
            return true;
        }
        SectionOcclusionGraph graph = renderer.sectionOcclusionGraph();
        AABB box = entity.getInterpolatedBoundingBox(partialTicks).inflate(0.5);
        int minX = SectionPos.blockToSectionCoord(box.minX);
        int minY = SectionPos.blockToSectionCoord(box.minY);
        int minZ = SectionPos.blockToSectionCoord(box.minZ);
        int maxX = SectionPos.blockToSectionCoord(box.maxX);
        int maxY = SectionPos.blockToSectionCoord(box.maxY);
        int maxZ = SectionPos.blockToSectionCoord(box.maxZ);
        if (maxX - minX >= MAX_SECTIONS_ACROSS
                || maxY - minY >= MAX_SECTIONS_ACROSS
                || maxZ - minZ >= MAX_SECTIONS_ACROSS) {
            return true;
        }
        BlockPos.MutableBlockPos origin = SCRATCH;
        for (int y = minY; y <= maxY; y++) {
            if (y < viewArea.minSectionY() || y > viewArea.maxSectionY()) {
                // Above or below the world's sections: nothing there to hide it.
                return true;
            }
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    long node = SectionPos.asLong(x, y, z);
                    origin.set(
                            SectionPos.sectionToBlockCoord(x),
                            SectionPos.sectionToBlockCoord(y),
                            SectionPos.sectionToBlockCoord(z));
                    SectionRenderDispatcher.RenderSection section = viewArea.getRenderSectionAt(origin);
                    if (section == null || section.getSectionNode() != node) {
                        // Outside the view area: the graph says nothing about it.
                        return true;
                    }
                    if (graph.getNode(section) != null) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Entities the game draws through walls, or that the camera is tied to. */
    private static boolean exempt(Entity entity) {
        // Text displays may draw through walls (their see-through style); there are never many.
        if (entity instanceof Player
                || entity instanceof Display.TextDisplay
                || entity.isCurrentlyGlowing()
                || entity.shouldShowName()) {
            return true;
        }
        if (entity instanceof Leashable leashable && (leashable.isLeashed() || leashable.getLeashHolder() != null)) {
            return true;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Entity camera = minecraft.getCameraEntity();
        return camera != null
                && (entity.equals(camera)
                        || entity.hasIndirectPassenger(camera)
                        || camera.hasIndirectPassenger(entity));
    }
}
