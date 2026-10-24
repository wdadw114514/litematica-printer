//#if MC >= 12111
package me.aleksilassila.litematica.printer.printer;

import me.aleksilassila.litematica.printer.config.Configs;
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
import java.util.List;
import java.util.function.Supplier;

/**
 * Long distance placement inspired by Trouser-Streak's Infinite Reach and
 * LiquidBounce's TpAura.
 *
 * <p>The client never moves locally. Before the use packet we send a few status
 * packets and one position packet that puts the server-side player next to the
 * target, then we send the use packet and finally send the original position
 * back. A short watch window detects the case where the outbound teleport was
 * accepted but the return packet was not, and restores the original position.</p>
 */
public final class RemotePlacementHelper {
    private static final int STATUS_PACKETS = 4;
    private static final double MAX_STEP_DISTANCE = 8.0D;
    private static final double SEARCH_RADIUS = 3.0D;
    private static final double SEARCH_STEP = 0.5D;
    private static final double RETURN_DISTANCE_SQR = 16.0D;
    private static final double TELEPORT_DISTANCE_SQR = 64.0D;
    private static final int RETURN_CHECK_DELAY_TICKS = 1;
    private static final int RETURN_TIMEOUT_TICKS = 20;
    private static final List<Vec3> SEARCH_OFFSETS = buildSearchOffsets();

    @Nullable
    private static Vec3 pendingReturnPos;
    @Nullable
    private static Vec3 pendingTeleportPos;
    private static long pendingReturnCheckTick;

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
        try {
            for (int i = 0; i < STATUS_PACKETS; i++) {
                NetworkUtils.sendPacket(new ServerboundMovePlayerPacket.StatusOnly(
                        player.onGround(), player.horizontalCollision));
            }
            sendMovePath(player, originalPos, teleportPos);
            return localAction.get();
        } finally {
            sendMovePath(player, teleportPos, originalPos);
        }
    }

    /** Restores the original position when the outbound teleport was not undone. */
    public static void tick(Minecraft minecraft) {
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
        sendMovePath(player, teleportPos, originalPos);
        clearPending();
    }

    /**
     * Splits a long move into sub-10 block steps. Vanilla rejects a single
     * move larger than 10 blocks ("moved too quickly"); stepping keeps every
     * packet inside that limit while still ending at the destination.
     */
    private static void sendMovePath(LocalPlayer player, Vec3 from, Vec3 to) {
        double distance = from.distanceTo(to);
        int steps = Math.max(1, (int) Math.ceil(distance / MAX_STEP_DISTANCE));
        for (int i = 1; i <= steps; i++) {
            Vec3 point = from.add(to.subtract(from).scale((double) i / steps));
            NetworkUtils.sendPacket(new ServerboundMovePlayerPacket.Pos(
                    point.x, point.y, point.z,
                    i == steps && player.onGround(), player.horizontalCollision));
        }
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
        for (Vec3 offset : SEARCH_OFFSETS) {
            Vec3 candidate = center.add(offset);
            BlockPos candidateBlock = BlockPos.containing(candidate);
            if (candidateBlock.equals(target) || !level.hasChunkAt(candidateBlock)) {
                continue;
            }
            if (!level.getFluidState(candidateBlock).isEmpty()) {
                continue;
            }
            if (!level.noCollision(player, playerBox.move(candidate.subtract(playerPos)))) {
                continue;
            }
            return candidate;
        }
        return null;
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
