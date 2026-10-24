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
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Long distance placement inspired by Trouser-Streak's Infinite Reach,
 * LiquidBounce's TpAura and SlimefunHelper's faraway movement.
 *
 * <p>The client position is moved together with the server position. The move
 * itself uses status-only filler packets followed by one destination packet,
 * matching SlimefunHelper's farawayMoveFromTo. A short watch window detects the
 * case where the outbound teleport was accepted but the return packet was not,
 * and restores the original position.</p>
 */
public final class RemotePlacementHelper {
    private static final String PATH_HIGHLIGHT_ID = "remote_place_path";
    private static final String TARGET_HIGHLIGHT_ID = "remote_place_target";
    private static final double SEARCH_RADIUS = 4.0D;
    private static final double SEARCH_STEP = 0.5D;
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
    private static long pendingReturnCheckTick;
    private static boolean pathRendered;

    private RemotePlacementHelper() {
    }

    public static boolean isEnabled() {
        return Configs.Placement.REMOTE_PLACE.getBooleanValue();
    }

    public static boolean shouldTeleport(@Nullable LocalPlayer player, @Nullable BlockPos target) {
        return isEnabled()
                && player != null
                && target != null
                && !PlayerUtils.isWithinBlockInteractionRange(player, target, 0.0D);
    }

    public static InteractionResult useItemOn(
            Minecraft minecraft,
            BlockPos target,
            Supplier<InteractionResult> localAction
    ) {
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return InteractionResult.FAIL;
        }
        Vec3 teleportPos = findTeleportPos(level, player, target);
        if (teleportPos == null) {
            return InteractionResult.FAIL;
        }
        Vec3 originalPos = player.position();
        pendingReturnPos = originalPos;
        pendingTeleportPos = teleportPos;
        pendingReturnCheckTick = level.getGameTime() + RETURN_CHECK_DELAY_TICKS;
        List<Vec3> renderPath = new ArrayList<>();
        try {
            sendMove(player, originalPos, teleportPos, renderPath);
            return localAction.get();
        } finally {
            sendMove(player, teleportPos, originalPos, renderPath);
            updatePathRender(renderPath, teleportPos);
        }
    }

    /** Restores the original position when the outbound teleport was not undone. */
    public static void tick(Minecraft minecraft) {
        if (!Configs.Placement.REMOTE_PLACE_RENDER_PATH.getBooleanValue()) {
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
        Vec3 teleportPos = pendingTeleportPos;
        if (teleportPos != null && currentPos.distanceToSqr(teleportPos) <= TELEPORT_DISTANCE_SQR) {
            restorePosition(player, originalPos, teleportPos);
        }
    }

    private static void restorePosition(LocalPlayer player, Vec3 originalPos, Vec3 teleportPos) {
        player.setPos(originalPos.x, originalPos.y, originalPos.z);
        sendMove(player, teleportPos, originalPos, null);
        clearPending();
    }

    /**
     * Status-only filler packets followed by one destination packet. This mirrors
     * SlimefunHelper's farawayMoveFromTo and avoids the per-packet distance
     * rejection that splitting a long move into many position packets can cause.
     */
    private static void sendMove(LocalPlayer player, Vec3 from, Vec3 to, @Nullable List<Vec3> renderPath) {
        double distance = from.distanceTo(to);
        if (distance < 1.0E-4D) {
            return;
        }
        int fillerPackets = (int) ((distance + 1.0D) / 10.0D);
        for (int i = 0; i < fillerPackets; i++) {
            NetworkUtils.sendPacket(new ServerboundMovePlayerPacket.StatusOnly(
                    player.onGround(), player.horizontalCollision));
        }
        NetworkUtils.sendPacket(new ServerboundMovePlayerPacket.Pos(
                to.x, to.y, to.z, player.onGround(), player.horizontalCollision));
        // Keep the client in sync with the position packets. Otherwise the next
        // client move packet snaps the server back and the placement is rejected.
        player.setPos(to.x, to.y, to.z);
        if (renderPath != null) {
            int renderSteps = Math.max(1, (int) Math.ceil(distance));
            for (int i = 1; i <= renderSteps; i++) {
                renderPath.add(from.add(to.subtract(from).scale((double) i / renderSteps)));
            }
        }
    }

    private static void updatePathRender(List<Vec3> path, Vec3 target) {
        if (!Configs.Placement.REMOTE_PLACE_RENDER_PATH.getBooleanValue()) {
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

    private static void clearPending() {
        pendingReturnPos = null;
        pendingTeleportPos = null;
    }

    @Nullable
    private static Vec3 findTeleportPos(ClientLevel level, LocalPlayer player, BlockPos target) {
        Vec3 center = Vec3.atCenterOf(target);
        Vec3 playerPos = player.position();
        AABB playerBox = player.getBoundingBox();

        // Prefer a spot between the player and the target. Walls are common around
        // schematic blocks; the player-side air pocket keeps the target inside the
        // server interaction range without putting the player inside a block.
        Vec3 towardPlayer = playerPos.subtract(center);
        if (towardPlayer.lengthSqr() > 1.0E-6D) {
            Vec3 direction = towardPlayer.normalize();
            for (double distance = 2.0D; distance <= 4.0D; distance += 0.5D) {
                Vec3 candidate = center.add(direction.scale(distance));
                if (isValidTeleportPos(level, player, playerBox, playerPos, target, candidate)) {
                    return candidate;
                }
            }
        }

        for (Vec3 offset : SEARCH_OFFSETS) {
            Vec3 candidate = center.add(offset);
            if (isValidTeleportPos(level, player, playerBox, playerPos, target, candidate)) {
                return candidate;
            }
        }

        // Last resort: directly above the target. Position packets are not
        // collision-validated by the server, and the target stays in range.
        BlockPos above = target.above();
        if (level.hasChunkAt(above)) {
            return new Vec3(center.x, target.getY() + 1.0D, center.z);
        }
        return null;
    }

    private static boolean isValidTeleportPos(
            ClientLevel level,
            LocalPlayer player,
            AABB playerBox,
            Vec3 playerPos,
            BlockPos target,
            Vec3 candidate
    ) {
        BlockPos candidateBlock = BlockPos.containing(candidate);
        if (candidateBlock.equals(target) || !level.hasChunkAt(candidateBlock)) {
            return false;
        }
        if (!level.getFluidState(candidateBlock).isEmpty()) {
            return false;
        }
        return level.noCollision(player, playerBox.move(candidate.subtract(playerPos)));
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
}
//#endif
