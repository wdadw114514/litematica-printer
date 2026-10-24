//#if MC >= 12111
package me.aleksilassila.litematica.printer.printer;

import fi.dy.masa.malilib.config.options.ConfigColor;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.integration.quickshulker.HighlightBlockRenderer;
import me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.PlayerUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * TpAura style long distance actions for the printer.
 *
 * <p>The route is sampled at one block intervals and every sample must be free
 * of collision for the player box. Moves are sent in at most five block steps so
 * no individual position packet can be rejected as a long jump. Both the client
 * and server positions are kept in sync. If the server pulls the client back
 * after the action, the saved original position is restored on the next tick.</p>
 */
public final class RemotePlacementHelper {
    private static final String PATH_HIGHLIGHT_ID = "remote_place_path";
    private static final String TARGET_HIGHLIGHT_ID = "remote_place_target";
    private static final double SEARCH_RADIUS = 4.0D;
    private static final double SEARCH_STEP = 0.5D;
    private static final double PACKET_STEP = 5.0D;
    private static final double RETURN_DISTANCE_SQR = 16.0D;
    private static final double TELEPORT_DISTANCE_SQR = 64.0D;
    private static final int RETURN_CHECK_DELAY_TICKS = 1;
    private static final int RETURN_TIMEOUT_TICKS = 20;
    private static final List<Vec3> SEARCH_OFFSETS = buildSearchOffsets();

    static {
        HighlightBlockRenderer.createHighlightBlockList(
                PATH_HIGHLIGHT_ID, new ConfigColor(PATH_HIGHLIGHT_ID, "#33FF33AA", ""));
        HighlightBlockRenderer.createHighlightBlockList(
                TARGET_HIGHLIGHT_ID, new ConfigColor(TARGET_HIGHLIGHT_ID, "#FFD700AA", ""));
    }

    @Nullable
    private static Vec3 pendingReturnPos;
    @Nullable
    private static Vec3 pendingTeleportPos;
    @Nullable
    private static Vec3 expectedPos;
    private static long pendingReturnCheckTick;
    private static boolean pathRendered;

    private RemotePlacementHelper() {
    }

    public static boolean isEnabled() {
        return Configs.Special.REMOTE_PLACE.getBooleanValue();
    }

    public static boolean shouldUseRemote(@Nullable BlockPos target) {
        Minecraft minecraft = Minecraft.getInstance();
        return isEnabled()
                && target != null
                && minecraft.player != null
                && !PlayerUtils.isWithinBlockInteractionRange(minecraft.player, target, 0.0D);
    }

    public static InteractionResult useItemOn(
            Minecraft minecraft,
            BlockPos target,
            Supplier<InteractionResult> localAction
    ) {
        return runRemoteAction(minecraft, target, localAction, InteractionResult.FAIL);
    }

    public static <T> T runRemoteAction(
            Minecraft minecraft,
            BlockPos target,
            Supplier<T> action,
            T fallback
    ) {
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return fallback;
        }
        TeleportPlan plan = findTeleportPlan(level, player, target);
        if (plan == null) {
            return fallback;
        }
        Vec3 originalPos = player.position();
        pendingReturnPos = originalPos;
        pendingTeleportPos = plan.destination();
        expectedPos = originalPos;
        pendingReturnCheckTick = level.getGameTime() + RETURN_CHECK_DELAY_TICKS;
        List<Vec3> renderPath = new ArrayList<>();
        try {
            sendPath(player, plan.path(), true, renderPath);
            expectedPos = plan.destination();
            return action.get();
        } finally {
            sendPath(player, plan.path(), false, renderPath);
            expectedPos = originalPos;
            updatePathRender(renderPath, plan.destination());
        }
    }

    /** Restores the original position when the server pulled the client away. */
    public static void tick(Minecraft minecraft) {
        if (!Configs.Special.REMOTE_PLACE_RENDER_PATH.getBooleanValue()) {
            clearPathRender();
        }
        Vec3 originalPos = pendingReturnPos;
        if (originalPos == null) {
            return;
        }
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            clearPending();
            return;
        }
        long now = level.getGameTime();
        if (now < pendingReturnCheckTick) {
            return;
        }
        if (now > pendingReturnCheckTick + RETURN_TIMEOUT_TICKS) {
            clearPending();
            return;
        }
        Vec3 currentPos = player.position();
        if (currentPos.distanceToSqr(originalPos) <= RETURN_DISTANCE_SQR) {
            clearPending();
            return;
        }
        Vec3 expected = expectedPos;
        if (expected != null && currentPos.distanceToSqr(expected) > RETURN_DISTANCE_SQR) {
            player.setPos(expected.x, expected.y, expected.z);
            NetworkUtils.sendPacket(new ServerboundMovePlayerPacket.Pos(
                    expected.x, expected.y, expected.z,
                    player.onGround(), player.horizontalCollision));
            return;
        }
        Vec3 teleportPos = pendingTeleportPos;
        if (teleportPos != null && currentPos.distanceToSqr(teleportPos) <= TELEPORT_DISTANCE_SQR) {
            restorePosition(player, originalPos);
        }
    }

    private static void restorePosition(LocalPlayer player, Vec3 originalPos) {
        player.setPos(originalPos.x, originalPos.y, originalPos.z);
        NetworkUtils.sendPacket(new ServerboundMovePlayerPacket.Pos(
                originalPos.x, originalPos.y, originalPos.z,
                player.onGround(), player.horizontalCollision));
        clearPending();
    }

    private static void clearPending() {
        pendingReturnPos = null;
        pendingTeleportPos = null;
        expectedPos = null;
    }

    /**
     * Sends the route in at most five block steps. Each step is a normal
     * position packet, which stays far below the server move limits.
     */
    private static void sendPath(
            LocalPlayer player,
            List<Vec3> path,
            boolean forward,
            @Nullable List<Vec3> renderPath
    ) {
        if (path.isEmpty()) {
            return;
        }
        List<Vec3> points = forward ? path : reversed(path);
        Vec3 lastSent = null;
        for (int i = 0; i < points.size(); i++) {
            Vec3 point = points.get(i);
            boolean last = i == points.size() - 1;
            if (last || lastSent == null || point.distanceTo(lastSent) >= PACKET_STEP) {
                NetworkUtils.sendPacket(new ServerboundMovePlayerPacket.Pos(
                        point.x, point.y, point.z,
                        player.onGround(), player.horizontalCollision));
                player.setPos(point.x, point.y, point.z);
                lastSent = point;
            }
            if (renderPath != null) {
                renderPath.add(point);
            }
        }
    }

    private static List<Vec3> reversed(List<Vec3> path) {
        List<Vec3> reversed = new ArrayList<>(path);
        Collections.reverse(reversed);
        return reversed;
    }

    @Nullable
    private static TeleportPlan findTeleportPlan(ClientLevel level, LocalPlayer player, BlockPos target) {
        Vec3 center = Vec3.atCenterOf(target);
        Vec3 playerPos = player.position();

        // Prefer the air pocket between the player and the target. Walls around
        // schematic blocks are common, and this side is usually open.
        Vec3 towardPlayer = playerPos.subtract(center);
        if (towardPlayer.lengthSqr() > 1.0E-6D) {
            Vec3 direction = towardPlayer.normalize();
            for (double distance = 2.0D; distance <= 4.0D; distance += 0.5D) {
                TeleportPlan plan = planFor(level, player, target, center.add(direction.scale(distance)));
                if (plan != null) {
                    return plan;
                }
            }
        }

        for (Vec3 offset : SEARCH_OFFSETS) {
            TeleportPlan plan = planFor(level, player, target, center.add(offset));
            if (plan != null) {
                return plan;
            }
        }

        BlockPos above = target.above();
        if (level.hasChunkAt(above)) {
            return planFor(level, player, target, new Vec3(center.x, target.getY() + 1.0D, center.z));
        }
        return null;
    }

    @Nullable
    private static TeleportPlan planFor(ClientLevel level, LocalPlayer player, BlockPos target, Vec3 destination) {
        BlockPos destinationBlock = BlockPos.containing(destination);
        if (destinationBlock.equals(target) || !level.hasChunkAt(destinationBlock)) {
            return null;
        }
        if (!level.getFluidState(destinationBlock).isEmpty()) {
            return null;
        }
        Vec3 playerPos = player.position();
        if (!level.noCollision(player, player.getBoundingBox().move(destination.subtract(playerPos)))) {
            return null;
        }
        List<Vec3> path = buildPath(level, player, playerPos, destination);
        if (path == null) {
            return null;
        }
        return new TeleportPlan(destination, path);
    }

    /**
     * Samples the route at one block intervals. Any collision along the route
     * invalidates it, which prevents the server from pulling the player back
     * because the route crossed a wall.
     */
    @Nullable
    private static List<Vec3> buildPath(ClientLevel level, LocalPlayer player, Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        double distance = delta.length();
        if (distance < 1.0E-4D) {
            return List.of();
        }
        int samples = Math.max(1, (int) Math.ceil(distance));
        AABB baseBox = player.getBoundingBox();
        Vec3 basePos = player.position();
        List<Vec3> path = new ArrayList<>(samples);
        for (int i = 1; i <= samples; i++) {
            Vec3 point = from.add(delta.scale((double) i / samples));
            if (!level.noCollision(player, baseBox.move(point.subtract(basePos)))) {
                return null;
            }
            path.add(point);
        }
        return path;
    }

    private static void updatePathRender(List<Vec3> path, Vec3 target) {
        if (!Configs.Special.REMOTE_PLACE_RENDER_PATH.getBooleanValue()) {
            clearPathRender();
            return;
        }
        Set<BlockPos> pathBlocks = new LinkedHashSet<>();
        for (Vec3 point : path) {
            pathBlocks.add(BlockPos.containing(point));
        }
        HighlightBlockRenderer.setPos(PATH_HIGHLIGHT_ID, pathBlocks);
        HighlightBlockRenderer.setPos(TARGET_HIGHLIGHT_ID, Set.of(BlockPos.containing(target)));
        pathRendered = true;
    }

    private static void clearPathRender() {
        if (!pathRendered) {
            return;
        }
        HighlightBlockRenderer.clear(PATH_HIGHLIGHT_ID);
        HighlightBlockRenderer.clear(TARGET_HIGHLIGHT_ID);
        pathRendered = false;
    }

    private static List<Vec3> buildSearchOffsets() {
        int steps = (int) Math.round(SEARCH_RADIUS / SEARCH_STEP);
        List<Vec3> offsets = new ArrayList<>();
        for (int dx = -steps; dx <= steps; dx++) {
            for (int dy = -steps; dy <= steps; dy++) {
                for (int dz = -steps; dz <= steps; dz++) {
                    Vec3 offset = new Vec3(dx * SEARCH_STEP, dy * SEARCH_STEP, dz * SEARCH_STEP);
                    if (offset.lengthSqr() <= SEARCH_RADIUS * SEARCH_RADIUS) {
                        offsets.add(offset);
                    }
                }
            }
        }
        offsets.sort(Comparator.comparingDouble(Vec3::lengthSqr));
        return List.copyOf(offsets);
    }

    private record TeleportPlan(Vec3 destination, List<Vec3> path) {
    }
}
//#endif
