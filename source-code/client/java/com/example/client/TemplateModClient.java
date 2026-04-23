package com.example.client;

import com.example.TemplateMod;
import com.example.client.gui.CodeExecutorScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class TemplateModClient implements ClientModInitializer {
	private static boolean executorPressedLastTick;

	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(this::templateMod$onClientTick);
	}

	private void templateMod$onClientTick(Minecraft client) {
		if (client.player == null) {
			executorPressedLastTick = false;
			return;
		}

		boolean isDown = InputConstants.isKeyDown(client.getWindow(), GLFW.GLFW_KEY_N);
		if (isDown && !executorPressedLastTick && !(client.screen instanceof CodeExecutorScreen)) {
			try {
				client.setScreen(new CodeExecutorScreen());
			} catch (Throwable t) {
				TemplateMod.LOGGER.error("Failed to open Code Executor screen", t);
				client.player.sendSystemMessage(Component.literal("Executor open failed: " + t.getClass().getSimpleName()));
			}
		}
		executorPressedLastTick = isDown;
	}
}