package com.emptyquit;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class EmptyQuitClient implements ClientModInitializer {

	/** How long you may see no zombified piglin before quitting, in seconds. */
	private static final int SECONDS_ALONE = 10;

	/**
	 * true  = a piglin only counts if you can actually see it (in front of you, no wall in the way).
	 * false = any loaded zombified piglin counts, even behind walls or behind you.
	 */
	private static final boolean REQUIRE_LINE_OF_SIGHT = true;

	/** How wide your view is, measured from the center of the screen outward. */
	private static final double VIEW_HALF_ANGLE_DEGREES = 60.0;

	/** Piglins further away than this many blocks never count. */
	private static final double MAX_DISTANCE = 128.0;

	private static final int TICKS_ALONE = SECONDS_ALONE * 20;

	private static final double COS_HALF_ANGLE = Math.cos(Math.toRadians(VIEW_HALF_ANGLE_DEGREES));

	private int emptyTicks = 0;

	@Override
	public void onInitializeClient() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> emptyTicks = 0);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> emptyTicks = 0);

		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
	}

	private void onTick(Minecraft mc) {
		if (mc.level == null || mc.player == null || mc.getConnection() == null) {
			emptyTicks = 0;
			return;
		}

		// Singleplayer / LAN host: never kick yourself out of your own world.
		if (mc.hasSingleplayerServer() || mc.getCurrentServer() == null) {
			emptyTicks = 0;
			return;
		}

		boolean seesPiglin = false;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (entity.getType() != EntityType.ZOMBIFIED_PIGLIN) {
				continue;
			}
			if (!REQUIRE_LINE_OF_SIGHT || canSee(mc, entity)) {
				seesPiglin = true;
				break;
			}
		}

		if (seesPiglin) {
			emptyTicks = 0;
			return;
		}

		emptyTicks++;
		if (emptyTicks >= TICKS_ALONE) {
			emptyTicks = 0;
			mc.getConnection().getConnection().disconnect(
					Component.literal("Empty Quit: no zombified piglins for " + SECONDS_ALONE + " seconds."));
		}
	}

	private boolean canSee(Minecraft mc, Entity entity) {
		Vec3 eye = mc.player.getEyePosition();
		Vec3 center = entity.getBoundingBox().getCenter();
		Vec3 toEntity = center.subtract(eye);
		double distance = toEntity.length();

		if (distance > MAX_DISTANCE) {
			return false;
		}

		if (distance > 0.5) {
			Vec3 look = mc.player.getViewVector(1.0F);
			double dot = look.dot(toEntity.scale(1.0 / distance));
			if (dot < COS_HALF_ANGLE) {
				return false;
			}
		}

		return lineIsClear(mc, eye, center) || lineIsClear(mc, eye, entity.getEyePosition());
	}

	private boolean lineIsClear(Minecraft mc, Vec3 from, Vec3 to) {
		HitResult hit = mc.level.clip(new ClipContext(
				from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
		return hit.getType() == HitResult.Type.MISS;
	}
}
