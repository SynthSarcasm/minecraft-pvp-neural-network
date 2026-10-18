package org.stepan1411.pvp_bot.bot;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class BotNavigation {

    private static final Map<String, NavigationState> navStates = new HashMap<>();

    public static class NavigationState {
        public int stuckTicks = 0;
        public Vec3 lastPosition = null;
        public int avoidDirection = 0;
        public int avoidTicks = 0;
        public int jumpCooldown = 0;

        public Vec3 spawnPosition = null;
        public Vec3 wanderTarget = null;
        public int wanderCooldown = 0;
        public int idleTicks = 0;

        public java.util.LinkedList<Vec3> pathHistory = new java.util.LinkedList<>();
        public static final int MAX_PATH_HISTORY = 15;

        // Strafe
        public int strafeDir = 1;
        public int strafeTicks = 0;

        // W-tap
        public boolean wtapActive = false;

        // Knockback
        public int knockbackTicks = 0;
    }

    public static NavigationState getState(String botName) {
        return navStates.computeIfAbsent(botName, k -> new NavigationState());
    }

    public static void removeState(String botName) {
        navStates.remove(botName);
    }

    public static void startWtap(String botName) {
        NavigationState state = navStates.get(botName);
        if (state != null) state.wtapActive = true;
    }

    public static void moveToward(ServerPlayer bot, Entity target, double speed) {
        NavigationState state = getState(bot.getName().getString());
        if (state.jumpCooldown > 0) state.jumpCooldown--;
        if (state.avoidTicks > 0) state.avoidTicks--;
        Vec3 targetPos = new Vec3(target.getX(), target.getY(), target.getZ());
        moveTowardPos(bot, targetPos, speed, state, false);
    }

    public static void moveAway(ServerPlayer bot, Entity target, double speed) {
        NavigationState state = getState(bot.getName().getString());
        if (state.jumpCooldown > 0) state.jumpCooldown--;
        if (state.avoidTicks > 0) state.avoidTicks--;
        double dx = bot.getX() - target.getX();
        double dz = bot.getZ() - target.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > 0) { dx /= dist; dz /= dist; }
        Vec3 awayPos = new Vec3(bot.getX() + dx * 10, bot.getY(), bot.getZ() + dz * 10);
        moveTowardPos(bot, awayPos, speed, state, false);
    }

    public static void moveTowardPosition(ServerPlayer bot, Vec3 targetPos, double speed) {
        NavigationState state = getState(bot.getName().getString());
        if (state.jumpCooldown > 0) state.jumpCooldown--;
        if (state.avoidTicks > 0) state.avoidTicks--;
        moveTowardPos(bot, targetPos, speed, state, false);
    }

    public static void moveTowardCombat(ServerPlayer bot, Vec3 targetPos, double speed, float strafeAngle) {
        NavigationState state = getState(bot.getName().getString());
        if (state.jumpCooldown > 0) state.jumpCooldown--;
        if (state.avoidTicks > 0) state.avoidTicks--;
        moveTowardPos(bot, targetPos, speed, state, true);
    }

    private static void updateStrafe(NavigationState state) {
        if (state.strafeTicks <= 0) {
            state.strafeDir = -state.strafeDir;
            state.strafeTicks = 8 + (int)(Math.random() * 11);
        }
        state.strafeTicks--;
    }

    private static boolean isKnockedBack(ServerPlayer bot) {
        Vec3 vel = bot.getDeltaMovement();
        double horiz = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        return horiz > 0.35;
    }

    private static void moveTowardPos(ServerPlayer bot, Vec3 targetPos, double speed, NavigationState state, boolean combat) {
        var world = bot.level();
        Vec3 botPos = new Vec3(bot.getX(), bot.getY(), bot.getZ());

        if (state.pathHistory.isEmpty() || botPos.distanceTo(state.pathHistory.getLast()) > 0.5) {
            state.pathHistory.add(botPos);
            if (state.pathHistory.size() > NavigationState.MAX_PATH_HISTORY)
                state.pathHistory.removeFirst();
        }

        // === KNOCKBACK ===
        if (isKnockedBack(bot) && state.knockbackTicks < 10) {
            state.knockbackTicks++;
            bot.setSprinting(false);
            BotMove.forward(bot, (float) (0));
            BotMove.strafe(bot, (float) (0));
            state.lastPosition = botPos;
            return;
        }
        state.knockbackTicks = 0;

        checkIfStuck(bot, state);

        double dx = targetPos.x - botPos.x;
        double dz = targetPos.z - botPos.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        if (horizontalDist > 0.1) { dx /= horizontalDist; dz /= horizontalDist; }

        // === WATER ===
        if (bot.isInWater() || bot.isUnderWater()) {
            handleWaterMovement(bot, targetPos, speed, state, dx, dz, horizontalDist, botPos);
            return;
        }

        // === AVOID (stuck) ===
        if (state.avoidTicks > 0) {
            double tempDx = dx;
            if (state.avoidDirection > 0) { dx = -dz; dz = tempDx; }
            else { dx = dz; dz = -tempDx; }
        }

        // === COMBAT STRAFE ===
        if (combat) {
            updateStrafe(state);
            double tempDx = dx;
            dx = dx + dz * state.strafeDir * 0.4;
            dz = dz - tempDx * state.strafeDir * 0.4;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 0) { dx /= len; dz /= len; }
        }

        // === OBSTACLE DETECTION ===
        BlockPos feetPos = new BlockPos((int) Math.floor(botPos.x + dx * 0.5), (int) Math.floor(botPos.y), (int) Math.floor(botPos.z + dz * 0.5));
        BlockPos headPos = feetPos.above();
        BlockPos aboveHeadPos = feetPos.above(2);
        boolean blockAtFeet = isBlockSolid(world, feetPos);
        boolean blockAtHead = isBlockSolid(world, headPos);
        boolean canJumpUp = blockAtFeet && !blockAtHead && !isBlockSolid(world, aboveHeadPos);
        boolean isWall = blockAtFeet && blockAtHead;
        boolean onLadder = isClimbable(world, bot.blockPosition()) || isClimbable(world, bot.blockPosition().above());
        BlockPos groundFront = new BlockPos((int) Math.floor(botPos.x + dx * 1.2), (int) Math.floor(botPos.y - 1), (int) Math.floor(botPos.z + dz * 1.2));
        boolean holeAhead = !isBlockSolid(world, groundFront) && !isBlockSolid(world, groundFront.below());

        BotSettings settings = BotSettings.get();

        // === PATH WALK TYPE ===
        String botName = bot.getName().getString();
        String pathWalkType = null;
        if (BotPath.isFollowing(botName)) {
            var follower = BotPath.getFollower(botName);
            if (follower != null) {
                var pathData = BotPath.getPath(follower.pathName);
                if (pathData != null) {
                    pathWalkType = pathData.walkType;
                }
            }
        }

        // === JUMPING ===
        if (bot.onGround() && state.jumpCooldown <= 0) {
            if (canJumpUp) { bot.jumpFromGround(); bot.push(dx * 0.2, 0, dz * 0.2); state.jumpCooldown = 8; }
            else if (onLadder) { bot.jumpFromGround(); state.jumpCooldown = 5; }
            else if (holeAhead) { bot.jumpFromGround(); bot.push(dx * 0.35, 0.05, dz * 0.35); state.jumpCooldown = 12; }
            else if (isWall && state.avoidTicks <= 0) {
                state.avoidDirection = (Math.random() > 0.5) ? 1 : -1;
                state.avoidTicks = 25;
                bot.jumpFromGround();
                state.jumpCooldown = 10;
            }
        }

        // === LADDER ===
        if (onLadder) {
            bot.push(0, 0.12, 0);
            bot.setSprinting(false);
            BotMove.forward(bot, (float) (1.0f));
            state.lastPosition = botPos;
            return;
        }

        // === BHOP ===
        boolean useBhop = (pathWalkType != null) ? pathWalkType.equals("bhop") : settings.isBhopEnabled();
        boolean shouldBhop = useBhop && speed >= 1.0 && !canJumpUp && !isWall && !holeAhead && state.jumpCooldown <= 0 && bot.onGround();
        if (shouldBhop) { bot.jumpFromGround(); state.jumpCooldown = 10; }

        // === W-TAP ===
        boolean stopSprint = false;
        if (state.wtapActive && bot.onGround()) {
            stopSprint = true;
            state.wtapActive = false;
        }

        // === APPLY MOVEMENT ===
        boolean shouldSprint = pathWalkType == null || !pathWalkType.equals("walk");
        bot.setSprinting(shouldSprint && !stopSprint);
        BotMove.forward(bot, (float) (stopSprint ? 0.5f : 1.0f));
        BotMove.strafe(bot, (float) (combat ? state.strafeDir * 0.3f : 0));

        double moveForce = bot.onGround() ? 0.1 : 0.02;
        bot.push(dx * speed * moveForce, 0, dz * speed * moveForce);

        state.lastPosition = botPos;
    }

    private static void handleWaterMovement(ServerPlayer bot, Vec3 targetPos, double speed, NavigationState state, double dx, double dz, double horizontalDist, Vec3 botPos) {
        boolean targetFar = horizontalDist > 8.0;
        double waterLevel = bot.getY();
        double targetLevel = targetPos.y;
        if (targetLevel > waterLevel + 0.5) bot.push(0, 0.08, 0);
        else if (targetLevel < waterLevel - 0.5) bot.push(0, -0.04, 0);
        if (targetFar) {
            bot.setSprinting(false);
            BotMove.forward(bot, (float) (0.8f));
            BotMove.strafe(bot, (float) (0));
            bot.push(dx * speed * 0.02, 0, dz * speed * 0.02);
            if (state.jumpCooldown <= 0) { bot.jumpFromGround(); state.jumpCooldown = 8; }
        } else {
            bot.setSprinting(false);
            BotMove.forward(bot, (float) (0.6f));
            BotMove.strafe(bot, (float) (0));
            bot.push(dx * speed * 0.015, 0, dz * speed * 0.015);
            if (state.jumpCooldown <= 0) { bot.jumpFromGround(); state.jumpCooldown = 10; }
        }
        state.lastPosition = botPos;
    }

    private static void checkIfStuck(ServerPlayer bot, NavigationState state) {
        Vec3 currentPos = new Vec3(bot.getX(), bot.getY(), bot.getZ());
        if (state.lastPosition == null) { state.lastPosition = currentPos; return; }
        double moved = currentPos.distanceTo(state.lastPosition);
        if (moved < 0.05 && bot.onGround()) {
            state.stuckTicks++;
            if (state.stuckTicks > 10) {
                if (state.avoidTicks <= 0) {
                    state.avoidDirection = (state.avoidDirection == 0) ? 1 : -state.avoidDirection;
                    state.avoidTicks = 30;
                }
                if (state.jumpCooldown <= 0) { bot.jumpFromGround(); state.jumpCooldown = 10; }
                state.stuckTicks = 0;
            }
        } else {
            state.stuckTicks = 0;
        }
    }

    private static boolean isBlockSolid(Level world, BlockPos pos) {
        BlockState bs = world.getBlockState(pos);
        return !bs.isAir() && bs.isRedstoneConductor(world, pos);
    }

    private static boolean isClimbable(Level world, BlockPos pos) {
        BlockState bs = world.getBlockState(pos);
        return bs.getBlock() instanceof LadderBlock || bs.getBlock() instanceof VineBlock ||
               bs.is(Blocks.SCAFFOLDING) || bs.is(Blocks.TWISTING_VINES) ||
               bs.is(Blocks.TWISTING_VINES_PLANT) || bs.is(Blocks.WEEPING_VINES) ||
               bs.is(Blocks.WEEPING_VINES_PLANT);
    }

    // === SMOOTH LOOKING ===

    private static float wrapDegrees(float angle) {
        angle %= 360.0F;
        if (angle >= 180.0F) angle -= 360.0F;
        if (angle < -180.0F) angle += 360.0F;
        return angle;
    }

    private static float smoothAngle(float current, float desired) {
        float maxRot = (float) BotSettings.get().getAimSpeed();
        float delta = wrapDegrees(desired - current);
        if (delta > maxRot) delta = maxRot;
        if (delta < -maxRot) delta = -maxRot;
        return current + delta;
    }

    public static void lookAt(ServerPlayer bot, Entity target) {
        Vec3 targetPos = target.getEyePosition();
        Vec3 botPos = bot.getEyePosition();
        double dx = targetPos.x - botPos.x;
        double dy = targetPos.y - botPos.y;
        double dz = targetPos.z - botPos.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float pitch = (float) -(Math.atan2(dy, horiz) * (180.0 / Math.PI));
        bot.setYRot(smoothAngle(bot.getYRot(), yaw));
        bot.setXRot(smoothAngle(bot.getXRot(), pitch));
        bot.setYHeadRot(smoothAngle(bot.getYHeadRot(), yaw));
    }

    public static void lookAtPosition(ServerPlayer bot, Vec3 targetPos) {
        Vec3 botPos = bot.getEyePosition();
        double dx = targetPos.x - botPos.x;
        double dy = targetPos.y - botPos.y;
        double dz = targetPos.z - botPos.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float pitch = (float) -(Math.atan2(dy, horiz) * (180.0 / Math.PI));
        bot.setYRot(smoothAngle(bot.getYRot(), yaw));
        bot.setXRot(smoothAngle(bot.getXRot(), pitch));
        bot.setYHeadRot(smoothAngle(bot.getYHeadRot(), yaw));
    }

    public static void lookAway(ServerPlayer bot, Entity target) {
        Vec3 targetPos = target.getEyePosition();
        Vec3 botPos = bot.getEyePosition();
        double dx = botPos.x - targetPos.x;
        double dz = botPos.z - targetPos.z;
        float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        bot.setYRot(smoothAngle(bot.getYRot(), yaw));
        bot.setXRot(smoothAngle(bot.getXRot(), 0));
        bot.setYHeadRot(smoothAngle(bot.getYHeadRot(), yaw));
    }

    public static void idleWander(ServerPlayer bot) {
        BotSettings settings = BotSettings.get();
        if (!settings.isIdleWanderEnabled()) return;
        NavigationState state = getState(bot.getName().getString());
        Vec3 botPos = new Vec3(bot.getX(), bot.getY(), bot.getZ());
        if (state.spawnPosition == null) state.spawnPosition = botPos;
        if (state.jumpCooldown > 0) state.jumpCooldown--;
        if (state.avoidTicks > 0) state.avoidTicks--;
        if (state.wanderCooldown > 0) state.wanderCooldown--;
        double radius = settings.getIdleWanderRadius();
        if (state.wanderTarget == null || state.wanderCooldown <= 0 || botPos.distanceTo(state.wanderTarget) < 1.5) {
            double angle = Math.random() * Math.PI * 2;
            double dist = Math.random() * radius;
            state.wanderTarget = new Vec3(
                state.spawnPosition.x + Math.cos(angle) * dist,
                state.spawnPosition.y,
                state.spawnPosition.z + Math.sin(angle) * dist
            );
            state.wanderCooldown = 60 + (int)(Math.random() * 100);
        }
        double dx = state.wanderTarget.x - botPos.x;
        double dz = state.wanderTarget.z - botPos.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > 0.5) {
            dx /= dist; dz /= dist;
            float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
            bot.setYRot(smoothAngle(bot.getYRot(), yaw));
            bot.setYHeadRot(smoothAngle(bot.getYHeadRot(), yaw));
            bot.setXRot(0);
            bot.setSprinting(false);
            BotMove.forward(bot, (float) (0.5f));
            bot.push(dx * 0.03, 0, dz * 0.03);
            var world = bot.level();
            BlockPos feetPos = new BlockPos((int) Math.floor(botPos.x + dx * 0.5), (int) Math.floor(botPos.y), (int) Math.floor(botPos.z + dz * 0.5));
            if (isBlockSolid(world, feetPos) && !isBlockSolid(world, feetPos.above()) && bot.onGround() && state.jumpCooldown <= 0) {
                bot.jumpFromGround();
                state.jumpCooldown = 10;
            }
        } else {
            BotMove.forward(bot, (float) (0));
            BotMove.strafe(bot, (float) (0));
        }
        state.lastPosition = botPos;
    }

    public static void resetIdle(String botName) {
        NavigationState state = navStates.get(botName);
        if (state != null) {
            state.wanderTarget = null;
            state.wanderCooldown = 0;
            state.idleTicks = 0;
        }
    }
}
