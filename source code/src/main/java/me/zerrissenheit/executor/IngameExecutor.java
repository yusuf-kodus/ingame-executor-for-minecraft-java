package me.zerrissenheit.executor;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// main mod entry point - fabric calls this on startup
public class IngameExecutor implements ModInitializer {
	public static final String MOD_ID = "ingame-executor";

	// logger thing - whatever, needed for debug shit
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// finally loaded this shit
		LOGGER.info("Ingame Executor initialized");
		// nothing else to do here lol
	}
}
