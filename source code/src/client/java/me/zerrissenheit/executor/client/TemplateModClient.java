package me.zerrissenheit.executor.client;

import me.zerrissenheit.executor.IngameExecutor;
import me.zerrissenheit.executor.client.gui.CodeExecutorScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

// client-side entry point - fabric calls this on client startup
public class TemplateModClient implements ClientModInitializer {
	// fuck this shit - why do i need to track key state manually
	private static boolean executorPressedLastTick;

	@Override
	public void onInitializeClient() {
		// just register the damn tick handler already
		ClientTickEvents.END_CLIENT_TICK.register(this::templateMod$onClientTick);
		// that's all we need here lol
	}

	private void templateMod$onClientTick(Minecraft client) {
		// wtf if no player then just gtfo
		if (client.player == null) {
			executorPressedLastTick = false;
			return;
		}

		// check if the damn N key is pressed
		boolean isDown = InputConstants.isKeyDown(client.getWindow(), GLFW.GLFW_KEY_N);
		// open the shit if pressed but not if already open (that would be annoying af)
		if (isDown && !executorPressedLastTick && !(client.screen instanceof CodeExecutorScreen)) {
			try {
				client.setScreen(new CodeExecutorScreen());
			} catch (Throwable t) {
				// something broke lol
				IngameExecutor.LOGGER.error("Failed to open Code Executor screen", t);
				client.player.sendSystemMessage(Component.literal("Executor open failed: " + t.getClass().getSimpleName()));
			}
		}
		executorPressedLastTick = isDown;
	}
}
