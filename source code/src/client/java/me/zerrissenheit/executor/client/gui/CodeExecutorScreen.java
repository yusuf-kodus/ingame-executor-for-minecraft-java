package me.zerrissenheit.executor.client.gui;

import java.io.IOException;
import java.io.PrintStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import jdk.jshell.Diag;
import jdk.jshell.JShell;
import jdk.jshell.Snippet;
import jdk.jshell.SnippetEvent;
import jdk.jshell.VarSnippet;
import jdk.jshell.execution.DirectExecutionControl;
import jdk.jshell.execution.LoaderDelegate;
import jdk.jshell.spi.ExecutionControl;
import jdk.jshell.spi.ExecutionControlProvider;
import jdk.jshell.spi.ExecutionEnv;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.URI;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import me.zerrissenheit.executor.lua.LuaExecutor;

// main gui screen for the code executor - this is where all the magic happens lol
public class CodeExecutorScreen extends Screen {
	// random ui constants idk
	private static final int PANEL_MARGIN = 16;
	private static final int PANEL_GAP = 10;
	private static final int LINE_HEIGHT = 10;
	private static final int MAX_LINES_DRAWN = 18;
	private static final int MAX_CODE_LENGTH = 8000;
	private static final int EXEC_TIMEOUT_SECONDS = 8; // whatever 8 secs is fine

	// state shit
	private final StringBuilder codeBuffer = new StringBuilder();
	private List<String> outputLines = new ArrayList<>(List.of("Output will appear here."));
	private ExecutorLanguage activeLanguage = ExecutorLanguage.PYTHON; // python is default whatever
	private CompletableFuture<ExecutionRun> runningTask;
	private long executionSequence;
	private long activeExecutionSequence;
	private boolean executing;
	private int cursorBlinkTicks; // blinky cursor thing

	// ai garbage - ollama or whatever
	private static boolean aiSettingsOpen = false;
	private static String aiUrlSetting = "http://localhost:11434/api/generate";
	private static String aiModelSetting = "llama2";
	private static int aiTimeoutSetting = 30;
	// input buffers for the settings ui thing
	private static String aiUrlInputBuffer = "http://localhost:11434/api/generate";
	private static String aiModelInputBuffer = "llama2";
	private static String aiTimeoutInputBuffer = "30";
	private static int aiSettingsFocus = 0; // 0=url,1=model,2=timeout,3=save
	private static final List<String> aiSavedScripts = new ArrayList<>(); // saved ai scripts or whatever
	private static int aiSavedIndex = -1;

	public CodeExecutorScreen() {
		super(Component.literal("Ingame Code Executor"));
		// nothing to do here lol
	}

	// draw the ai settings window thing
	private void drawAiSettings(GuiGraphicsExtractor guiGraphics) {
		int w = Math.min(600, this.width - 80);
		int h = 160;
		int x = (this.width - w) / 2;
		int y = (this.height - h) / 2;

		// dark background
		guiGraphics.fill(x, y, x + w, y + h, 0xEE101216);

		int line = y + 8;
		// title text
		guiGraphics.text(this.font, Component.literal("AI Settings (Ctrl+O to close)"), x + 10, line, 0xFFFFFFFF, false);
		line += 14;

		// url field
		String urlLine = "URL: " + aiUrlInputBuffer + (aiSettingsFocus == 0 ? "_" : "");
		int urlColor = aiSettingsFocus == 0 ? 0xFFFFFF00 : 0xFFD6E4FF;
		guiGraphics.text(this.font, Component.literal(urlLine), x + 10, line, urlColor, false);
		line += 12;

		// model field
		String modelLine = "Model: " + aiModelInputBuffer + (aiSettingsFocus == 1 ? "_" : "");
		int modelColor = aiSettingsFocus == 1 ? 0xFFFFFF00 : 0xFFD6E4FF;
		guiGraphics.text(this.font, Component.literal(modelLine), x + 10, line, modelColor, false);
		line += 12;

		// timeout field
		String timeoutLine = "Timeout (s): " + aiTimeoutInputBuffer + (aiSettingsFocus == 2 ? "_" : "");
		int timeoutColor = aiSettingsFocus == 2 ? 0xFFFFFF00 : 0xFFD6E4FF;
		guiGraphics.text(this.font, Component.literal(timeoutLine), x + 10, line, timeoutColor, false);
		line += 18;

		// help text thing
		guiGraphics.text(this.font, Component.literal("Tab: switch • Enter/Ctrl+S: save • Esc: cancel • Ctrl+L: load saved script"), x + 10, line, 0xFFB8D9FF, false);
		line += 14;

		// show how many saved scripts
		String savedSummary = "Saved scripts: " + aiSavedScripts.size();
		guiGraphics.text(this.font, Component.literal(savedSummary), x + 10, line, 0xFF9AD1FF, false);
		line += 12;

		// preview of current script if any
		if (!aiSavedScripts.isEmpty()) {
			int idx = Math.max(0, aiSavedIndex);
			String preview = aiSavedScripts.get(idx);
			String previewLine = "Preview (" + (idx + 1) + "): " + (preview.length() > 80 ? preview.substring(0, 80) + "..." : preview);
			guiGraphics.text(this.font, Component.literal(previewLine), x + 10, line, 0xFFD6E4FF, false);
		}
	}

	@Override
	public void tick() {
		// cursor blink thing
		this.cursorBlinkTicks++;
		// check if the damn execution finished
		if (this.runningTask != null && this.runningTask.isDone()) {
			try {
				ExecutionRun run = this.runningTask.get();
				// only update if this is the latest one (ignore old shit)
				if (run.sequence() == this.activeExecutionSequence) {
					this.outputLines = new ArrayList<>(run.result().lines());
					String injected = run.result().injectedScript();
					// if ai injected something replace the buffer
					if (injected != null) {
						this.codeBuffer.setLength(0);
						this.codeBuffer.append(injected);
					}
				}
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
				this.outputLines = new ArrayList<>(List.of("Execution interrupted."));
			} catch (ExecutionException ignored) {
				this.outputLines = new ArrayList<>(List.of("Execution failed."));
			}
			this.executing = false;
			this.runningTask = null;
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
		// dark background overlay
		guiGraphics.fill(0, 0, this.width, this.height, 0xCC0E121B);

		// if ai settings is open just render that and skip the rest
		if (aiSettingsOpen) {
			drawAiSettings(guiGraphics);
			return;
		}

		// calculate panel positions whatever
		int panelTop = PANEL_MARGIN + 28;
		int panelHeight = this.height - panelTop - PANEL_MARGIN;
		int panelWidth = (this.width - PANEL_MARGIN * 2 - PANEL_GAP) / 2;
		int leftX = PANEL_MARGIN;
		int rightX = leftX + panelWidth + PANEL_GAP;

		// header text
		guiGraphics.text(this.font, Component.literal("Code Executor (N to open, Esc to close)"), PANEL_MARGIN, 8, 0xFFFFFFFF, false);
		guiGraphics.text(this.font, Component.literal("Mode: " + this.activeLanguage.label + " | Ctrl+Enter or F5 to run | Tab to switch"), PANEL_MARGIN, 18, 0xFFB8D9FF, false);

		// panel backgrounds
		guiGraphics.fill(leftX, panelTop, leftX + panelWidth, panelTop + panelHeight, 0xAA10161F);
		guiGraphics.fill(rightX, panelTop, rightX + panelWidth, panelTop + panelHeight, 0xAA10161F);
		// labels
		guiGraphics.text(this.font, Component.literal("Code"), leftX + 6, panelTop + 4, 0xFF9AD1FF, false);
		guiGraphics.text(this.font, Component.literal(this.executing ? "Output (running...)" : "Output"), rightX + 6, panelTop + 4, 0xFF9AD1FF, false);

		// render the actual stuff
		this.drawCode(guiGraphics, leftX + 6, panelTop + 18, panelWidth - 12, panelHeight - 24);
		this.drawOutput(guiGraphics, rightX + 6, panelTop + 18, panelWidth - 12, panelHeight - 24);
	}

	private void drawCode(GuiGraphicsExtractor guiGraphics, int x, int y, int width, int height) {
		// get code text and add cursor if blinking
		String codeText = this.codeBuffer.toString();
		if ((this.cursorBlinkTicks / 6) % 2 == 0) {
			codeText = codeText + "_";
		}
		List<String> lines = this.wrapText(codeText, width);
		this.drawLines(guiGraphics, lines, x, y, height, 0xFFFFFFFF);
	}

	private void drawOutput(GuiGraphicsExtractor guiGraphics, int x, int y, int width, int height) {
		// wrap output lines
		List<String> lines = new ArrayList<>();
		for (String line : this.outputLines) {
			lines.addAll(this.wrapText(line, width));
		}
		this.drawLines(guiGraphics, lines, x, y, height, 0xFFD6E4FF);
	}

	private void drawLines(GuiGraphicsExtractor guiGraphics, List<String> lines, int x, int y, int height, int color) {
		// figure out max visible lines
		int maxVisible = Math.min(MAX_LINES_DRAWN, Math.max(1, height / LINE_HEIGHT));
		// show the last n lines (scroll to bottom)
		int start = Math.max(0, lines.size() - maxVisible);
		int lineY = y;
		for (int i = start; i < lines.size(); i++) {
			guiGraphics.text(this.font, Component.literal(lines.get(i)), x, lineY, color, false);
			lineY += LINE_HEIGHT;
		}
	}

	private List<String> wrapText(String text, int maxWidth) {
		// simple text wrapping - not perfect whatever
		List<String> wrapped = new ArrayList<>();
		String[] rawLines = text.replace("\r", "").split("\n", -1);
		for (String raw : rawLines) {
			if (raw.isEmpty()) {
				wrapped.add("");
				continue;
			}

			StringBuilder current = new StringBuilder();
			for (int i = 0; i < raw.length(); i++) {
				char c = raw.charAt(i);
				current.append(c);
				// wrap if too long
				if (this.font.width(current.toString()) >= maxWidth) {
					int cut = Math.max(1, current.length() - 1);
					wrapped.add(current.substring(0, cut));
					String tail = current.substring(cut);
					current = new StringBuilder(tail);
				}
			}
			wrapped.add(current.toString());
		}
		return wrapped;
	}

	@Override
	public boolean keyPressed(KeyEvent keyEvent) {
		int keyCode = keyEvent.key();

		// handle ai settings overlay first if open
		if (aiSettingsOpen) {
			// escape closes this shit
			if (keyEvent.isEscape() || keyCode == GLFW.GLFW_KEY_ESCAPE) {
				aiSettingsOpen = false;
				// reset buffers to saved values
				aiUrlInputBuffer = aiUrlSetting;
				aiModelInputBuffer = aiModelSetting;
				aiTimeoutInputBuffer = String.valueOf(aiTimeoutSetting);
				return true;
			}

			// paste from clipboard
			if (keyEvent.isPaste()) {
				if (this.minecraft != null) {
					String clipboard = this.minecraft.keyboardHandler.getClipboard();
					if (clipboard != null && !clipboard.isEmpty()) {
						// paste into the focused field
						switch (aiSettingsFocus) {
							case 0 -> aiUrlInputBuffer += clipboard;
							case 1 -> aiModelInputBuffer += clipboard;
							case 2 -> aiTimeoutInputBuffer += clipboard;
						}
					}
				}
				return true;
			}

			// tab cycles through fields
			if (keyCode == GLFW.GLFW_KEY_TAB) {
				aiSettingsFocus = (aiSettingsFocus + 1) % 4;
				return true;
			}

			// backspace deletes from focused field
			if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
				switch (aiSettingsFocus) {
					case 0 -> { if (!aiUrlInputBuffer.isEmpty()) aiUrlInputBuffer = aiUrlInputBuffer.substring(0, aiUrlInputBuffer.length() - 1); }
					case 1 -> { if (!aiModelInputBuffer.isEmpty()) aiModelInputBuffer = aiModelInputBuffer.substring(0, aiModelInputBuffer.length() - 1); }
					case 2 -> { if (!aiTimeoutInputBuffer.isEmpty()) aiTimeoutInputBuffer = aiTimeoutInputBuffer.substring(0, aiTimeoutInputBuffer.length() - 1); }
				}
				return true;
			}

			// ctrl+s or enter saves the settings
			if ((keyEvent.hasControlDown() && keyCode == GLFW.GLFW_KEY_S) || keyCode == GLFW.GLFW_KEY_ENTER) {
				aiUrlSetting = aiUrlInputBuffer.strip();
				aiModelSetting = aiModelInputBuffer.strip();
				try {
					aiTimeoutSetting = Integer.parseInt(aiTimeoutInputBuffer.strip());
				} catch (NumberFormatException ignored) {
					// if parse fails keep old value whatever
				}
				aiSettingsOpen = false;
				this.outputLines = new ArrayList<>(List.of("AI settings saved."));
				return true;
			}

			return true;
		}

		// ctrl+o toggles ai settings
		if (keyCode == GLFW.GLFW_KEY_O && keyEvent.hasControlDown()) {
			aiSettingsOpen = !aiSettingsOpen;
			if (aiSettingsOpen) {
				// reset buffers to saved values
				aiUrlInputBuffer = aiUrlSetting;
				aiModelInputBuffer = aiModelSetting;
				aiTimeoutInputBuffer = String.valueOf(aiTimeoutSetting);
				aiSettingsFocus = 0;
			}
			return true;
		}

		// ctrl+l loads next saved ai script
		if (keyEvent.hasControlDown() && keyCode == GLFW.GLFW_KEY_L) {
			if (!aiSavedScripts.isEmpty()) {
				aiSavedIndex = (aiSavedIndex + 1) % aiSavedScripts.size();
				this.codeBuffer.setLength(0);
				this.codeBuffer.append(aiSavedScripts.get(aiSavedIndex));
				this.outputLines = new ArrayList<>(List.of("Loaded AI script " + (aiSavedIndex + 1) + "/" + aiSavedScripts.size()));
			} else {
				this.outputLines = new ArrayList<>(List.of("No saved AI scripts."));
			}
			return true;
		}

		// tab switches languages
		if (keyCode == GLFW.GLFW_KEY_TAB) {
			this.activeLanguage = this.activeLanguage.next();
			return true;
		}

		// ctrl+k asks ai to generate code
		if (keyCode == GLFW.GLFW_KEY_K && keyEvent.hasControlDown()) {
			long runSequence = ++this.executionSequence;
			this.activeExecutionSequence = runSequence;
			this.executing = true;
			this.outputLines = new ArrayList<>(List.of("Asking AI..."));
			String prompt = this.codeBuffer.toString();
			this.runningTask = CompletableFuture.supplyAsync(() -> new ExecutionRun(runSequence, invokeAiFromPrompt(prompt, this.activeLanguage)));
			return true;
		}

		// f5 or ctrl+enter runs the code
		if (keyCode == GLFW.GLFW_KEY_F5 || (keyCode == GLFW.GLFW_KEY_ENTER && keyEvent.hasControlDown())) {
			this.executeCode();
			return true;
		}

		// backspace deletes last char
		if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
			if (!this.codeBuffer.isEmpty()) {
				this.codeBuffer.deleteCharAt(this.codeBuffer.length() - 1);
			}
			return true;
		}

		// enter adds new line
		if (keyCode == GLFW.GLFW_KEY_ENTER) {
			if (this.codeBuffer.length() < MAX_CODE_LENGTH) {
				this.codeBuffer.append('\n');
			}
			return true;
		}

		// paste from clipboard
		if (keyEvent.isPaste()) {
			if (this.minecraft != null) {
				String clipboard = this.minecraft.keyboardHandler.getClipboard();
				if (clipboard != null && !clipboard.isEmpty()) {
					int available = MAX_CODE_LENGTH - this.codeBuffer.length();
					if (available > 0) {
						this.codeBuffer.append(clipboard, 0, Math.min(clipboard.length(), available));
					}
				}
			}
			return true;
		}

		// ctrl+c clears the buffer
		if (keyCode == GLFW.GLFW_KEY_C && keyEvent.hasControlDown()) {
			this.codeBuffer.setLength(0);
			return true;
		}

		return super.keyPressed(keyEvent);
	}

	@Override
	public boolean charTyped(CharacterEvent characterEvent) {
		if (!characterEvent.isAllowedChatCharacter()) {
			return false;
		}

		// if ai settings is open route chars to focused field
		if (aiSettingsOpen) {
			int codePoint = characterEvent.codepoint();
			if (!Character.isValidCodePoint(codePoint)) {
				return false;
			}
			char[] chars = Character.toChars(codePoint);
			String s = new String(chars);
			// add to focused field
			switch (aiSettingsFocus) {
				case 0 -> { // url field
					aiUrlInputBuffer += s;
				}
				case 1 -> { // model field
					aiModelInputBuffer += s;
				}
				case 2 -> { // timeout field - digits only
					if (Character.isDigit(s.charAt(0))) {
						aiTimeoutInputBuffer += s;
					}
				}
			}
			return true;
		}

		// otherwise add to code buffer
		int codePoint = characterEvent.codepoint();
		if (!Character.isValidCodePoint(codePoint)) {
			return false;
		}
		// check if at limit
		if (this.codeBuffer.length() >= MAX_CODE_LENGTH) {
			return true;
		}

		char[] chars = Character.toChars(codePoint);
		if (this.codeBuffer.length() + chars.length > MAX_CODE_LENGTH) {
			return true;
		}
		this.codeBuffer.append(chars);
		return true;
	}

	@Override
	public boolean isPauseScreen() {
		// don't pause the game when this is open
		return false;
	}

	private void executeCode() {
		// don't run if already running
		if (this.executing) {
			return;
		}

		String code = this.codeBuffer.toString().trim();
		if (code.isEmpty()) {
			this.outputLines = new ArrayList<>();
			this.outputLines = new ArrayList<>(List.of("No code to execute."));
			return;
		}

		long runSequence = ++this.executionSequence;
		this.activeExecutionSequence = runSequence;
		this.executing = true;
		this.outputLines = new ArrayList<>();

		// handle ingame java specially (different path)
		if (this.activeLanguage == ExecutorLanguage.INGAME_JAVA) {
			this.runningTask = CompletableFuture.supplyAsync(() -> new ExecutionRun(runSequence, this.activeLanguage.execute(code, this.minecraft)));
			return;
		}

		this.runningTask = CompletableFuture.supplyAsync(() -> new ExecutionRun(runSequence, this.activeLanguage.execute(code, this.minecraft)));
	}

	private enum ExecutorLanguage {
		PYTHON("Python (Local)"),
		C_PLUS_PLUS("C++ (Toolchain)"),
		JAVA("Java (External JShell)"),
		LUA("Lua (Ingame)"),
		INGAME_JAVA("Java (Ingame)");

		private final String label;

		ExecutorLanguage(String label) {
			this.label = label;
		}

		// cycle to next language
		private ExecutorLanguage next() {
			ExecutorLanguage[] values = values();
			return values[(this.ordinal() + 1) % values.length];
		}

		private ExecutionResult execute(String code, Minecraft minecraft) {
			// dispatch to the right execution method
			return switch (this) {
				case PYTHON -> executePython(code);
				case C_PLUS_PLUS -> executeCpp(code);
				case JAVA -> executeJava(code);
				case LUA -> {
					var luaResult = LuaExecutor.executeLua(code, minecraft);
					yield new ExecutionResult(luaResult.lines(), luaResult.exitCode(), null);
				}
				case INGAME_JAVA -> executeIngameJava(code, minecraft);
			};
		}

		private static ExecutionResult executeIngameJava(String code, Minecraft minecraft) {
			// need mc instance for this to work
			if (minecraft == null) {
				return new ExecutionResult(List.of("Minecraft client is not ready."), -1, null);
			}

			List<String> lines = new ArrayList<>();
			// capture stdout and stderr
			ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
			ByteArrayOutputStream errorBuffer = new ByteArrayOutputStream();
			String runtimeClasspath = System.getProperty("java.class.path", "");
			// save and set context classloader
			ClassLoader previousContextClassLoader = Thread.currentThread().getContextClassLoader();
			Thread.currentThread().setContextClassLoader(Minecraft.class.getClassLoader());
			IngameBindings.minecraft = minecraft;

			try (
				PrintStream shellOut = new PrintStream(outputBuffer, true, StandardCharsets.UTF_8);
				PrintStream shellErr = new PrintStream(errorBuffer, true, StandardCharsets.UTF_8);
				// build jshell with custom execution engine that can access mc classes
				JShell jshell = JShell.builder()
					.out(shellOut)
					.err(shellErr)
					.compilerOptions("--class-path", runtimeClasspath)
					.executionEngine(createIngameExecutionProvider(), Map.of())
					.build()
			) {
				// add all classpath entries to jshell
				for (String classpathEntry : splitClasspath(runtimeClasspath)) {
					jshell.addToClasspath(classpathEntry);
				}

				// setup mc bindings (mc, player, level)
				if (!evaluateSetup(jshell, lines)) {
					lines.add("Runtime classloader: " + String.valueOf(Minecraft.class.getClassLoader()));
					lines.add("Thread context classloader: " + String.valueOf(Thread.currentThread().getContextClassLoader()));
					return new ExecutionResult(lines, -1, null);
				}

				List<SnippetEvent> events;
				PrintStream previousOut = System.out;
				PrintStream previousErr = System.err;
				try {
					// redirect system out/err to our buffers
					System.setOut(shellOut);
					System.setErr(shellErr);
					events = jshell.eval(code);

					boolean[] usedMcExecute = new boolean[1];
					boolean ok = appendSnippetResult(jshell, events, lines, usedMcExecute);
					// if code used mc.execute() wait for main thread tasks
					if (usedMcExecute[0]) {
						waitForMainThreadTasks(minecraft, lines);
					}

					String streamText = outputBuffer.toString(StandardCharsets.UTF_8)
						+ System.lineSeparator()
						+ errorBuffer.toString(StandardCharsets.UTF_8);
					lines.addAll(cleanOutput(streamText));
					if (ok && lines.isEmpty()) {
						if (usedMcExecute[0]) {
							lines.add("Code executed. No immediate output captured.");
							lines.add("Tip: avoid redeclaring mc/player/level and print outside mc.execute(...) when possible.");
						} else {
							lines.add("Code executed (no textual output). Use System.out.println(...) or end with an expression like: result;");
						}
					}
					return new ExecutionResult(lines, ok ? 0 : -1, null);
				} finally {
					System.setOut(previousOut);
					System.setErr(previousErr);
				}
			} catch (Throwable t) {
				String message = t.getMessage() == null ? "Unknown error" : t.getMessage();
							return new ExecutionResult(List.of("Ingame Java execution failed: " + message), -1, null);
			} finally {
				Thread.currentThread().setContextClassLoader(previousContextClassLoader);
				IngameBindings.minecraft = null;
			}
		}

		private static void waitForMainThreadTasks(Minecraft minecraft, List<String> lines) {
			CountDownLatch completion = new CountDownLatch(1);
			try {
				minecraft.execute(completion::countDown);
				if (!completion.await(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
					lines.add("Timed out waiting for Minecraft main-thread tasks to finish.");
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				lines.add("Interrupted while waiting for Minecraft main-thread tasks.");
			}
		}

		private static ExecutionControlProvider createIngameExecutionProvider() {
			return new ExecutionControlProvider() {
				@Override
				public String name() {
					return "direct";
				}

				@Override
				public Map<String, String> defaultParameters() {
					return Map.of();
				}

				@Override
				public ExecutionControl generate(ExecutionEnv env, Map<String, String> parameters) {
					return new DirectExecutionControl(new IngameLoaderDelegate());
				}
			};
		}

		private static final class IngameLoaderDelegate implements LoaderDelegate {
			private final IngameSnippetClassLoader snippetClassLoader = new IngameSnippetClassLoader(Minecraft.class.getClassLoader());

			@Override
			public void load(ExecutionControl.ClassBytecodes[] cbcs)
				throws ExecutionControl.ClassInstallException, ExecutionControl.NotImplementedException, ExecutionControl.EngineTerminationException {
				boolean[] installed = new boolean[cbcs.length];
				String firstFailure = null;

				for (int i = 0; i < cbcs.length; i++) {
					ExecutionControl.ClassBytecodes cbc = cbcs[i];
					try {
						snippetClassLoader.defineSnippetClass(normalizeBinaryName(cbc.name()), cbc.bytecodes());
						installed[i] = true;
					} catch (Throwable t) {
						installed[i] = false;
						if (firstFailure == null) {
							firstFailure = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
						}
					}
				}

				if (firstFailure != null) {
					throw new ExecutionControl.ClassInstallException("Failed to install snippet classes: " + firstFailure, installed);
				}
			}

			@Override
			public void classesRedefined(ExecutionControl.ClassBytecodes[] cbcs) {
				// JShell notifies class redefinitions; snippet loader has no special action here.
			}

			@Override
			public void addToClasspath(String path) throws ExecutionControl.EngineTerminationException, ExecutionControl.InternalException {
				try {
					snippetClassLoader.addToClasspath(path);
				} catch (MalformedURLException e) {
					throw new ExecutionControl.InternalException("Invalid classpath entry: " + e.getMessage());
				}
			}

			@Override
			public Class<?> findClass(String name) throws ClassNotFoundException {
				try {
					return Class.forName(name, false, Minecraft.class.getClassLoader());
				} catch (ClassNotFoundException ignored) {
					return this.snippetClassLoader.loadClass(name);
				}
			}

			private static String normalizeBinaryName(String name) {
				return name == null ? "" : name.replace('/', '.');
			}
		}

		private static final class IngameSnippetClassLoader extends URLClassLoader {
			private final Map<String, byte[]> pendingDefinitions = new ConcurrentHashMap<>();

			private IngameSnippetClassLoader(ClassLoader parent) {
				super(new URL[0], parent);
			}

			private synchronized void defineSnippetClass(String className, byte[] bytecode) {
				if (className == null || className.isBlank() || bytecode == null) {
					return;
				}

				if (findLoadedClass(className) != null) {
					return;
				}

				this.pendingDefinitions.put(className, bytecode);
				loadKnownClass(className);
			}

			private synchronized void addToClasspath(String path) throws MalformedURLException {
				if (path == null || path.isBlank()) {
					return;
				}

				for (String raw : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
					String entry = raw.strip();
					if (!entry.isEmpty()) {
						this.addURL(Path.of(entry).toUri().toURL());
					}
				}
			}

			private Class<?> loadKnownClass(String className) {
				try {
					return loadClass(className);
				} catch (ClassNotFoundException e) {
					throw new IllegalStateException("Unable to load snippet class: " + className, e);
				}
			}

			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException {
				byte[] bytecode = this.pendingDefinitions.remove(name);
				if (bytecode != null) {
					return defineClass(name, bytecode, 0, bytecode.length);
				}
				throw new ClassNotFoundException(name);
			}
		}

		private static List<String> splitClasspath(String classpath) {
			if (classpath == null || classpath.isBlank()) {
				return List.of();
			}

			List<String> entries = new ArrayList<>();
			String separator = File.pathSeparator;
			for (String raw : classpath.split(java.util.regex.Pattern.quote(separator))) {
				String entry = raw.strip();
				if (!entry.isEmpty()) {
					entries.add(entry);
				}
			}
			return entries;
		}

		private static boolean evaluateSetup(JShell jshell, List<String> lines) {
			List<String> setupSnippets = List.of(
				"import net.minecraft.client.Minecraft;",
				"Minecraft mc = Minecraft.getInstance();",
				"var player = mc == null ? null : mc.player;",
				"var level = mc == null ? null : mc.level;"
			);

			for (String setupSnippet : setupSnippets) {
				List<SnippetEvent> events = jshell.eval(setupSnippet);
				if (!appendSetupErrors(jshell, events, lines)) {
					return false;
				}
			}
			return true;
		}

		private static boolean appendSetupErrors(JShell jshell, List<SnippetEvent> events, List<String> lines) {
			for (SnippetEvent event : events) {
				if (event.status() != Snippet.Status.REJECTED) {
					continue;
				}

				lines.add("Ingame setup failed.");
				List<Diag> diagnostics = jshell.diagnostics(event.snippet()).toList();
				if (diagnostics.isEmpty()) {
					lines.add("Unknown setup error.");
				} else {
					for (Diag diag : diagnostics) {
						lines.add(diag.getMessage(null));
					}
				}
				return false;
			}
			return true;
		}

		private static boolean appendSnippetResult(JShell jshell, List<SnippetEvent> events, List<String> lines, boolean[] usedMcExecute) {
			boolean success = true;
			for (SnippetEvent event : events) {
				Snippet snippet = event.snippet();
				if (snippet != null) {
					String source = snippet.source();
					if (source != null && source.contains("mc.execute(")) {
						usedMcExecute[0] = true;
					}
				}

				if (event.status() == Snippet.Status.REJECTED) {
					success = false;
					List<Diag> diagnostics = jshell.diagnostics(snippet).toList();
					if (diagnostics.isEmpty()) {
						lines.add("Snippet rejected.");
					} else {
						for (Diag diag : diagnostics) {
							lines.add(diag.getMessage(null));
						}
					}
					continue;
				}

				if (event.exception() != null) {
					success = false;
					Throwable exception = event.exception();
					String message = exception.getMessage();
					if (message == null || message.isBlank()) {
						lines.add(exception.getClass().getSimpleName());
					} else {
						lines.add(exception.getClass().getSimpleName() + ": " + message);
					}
					continue;
				}

				String value = event.value();
				if (value != null
					&& !value.isBlank()
					&& snippet != null
					&& (snippet.kind() == Snippet.Kind.EXPRESSION || snippet.kind() == Snippet.Kind.VAR)) {
					if (snippet.kind() == Snippet.Kind.VAR && shouldSkipVarEcho((VarSnippet) snippet, value)) {
						continue;
					}
					lines.add(value);
				}
			}
			return success;
		}

		private static boolean shouldSkipVarEcho(VarSnippet snippet, String value) {
			String name = snippet.name();
			if ("mc".equals(name) || "player".equals(name) || "level".equals(name)) {
				return true;
			}

			String source = snippet.source();
			if (source != null && source.contains("Minecraft.getInstance()") && value.startsWith("net.minecraft.client.Minecraft@")) {
				return true;
			}

			return false;
		}

		private static ExecutionResult executePython(String code) {
			Path scriptPath = null;
			try {
				scriptPath = Files.createTempFile("template-mod-python-", ".py");
				Files.writeString(scriptPath, code + System.lineSeparator(), StandardCharsets.UTF_8);

				List<List<String>> candidates = buildPythonCommands(scriptPath.toString());
				ExecutionResult lastFailure = null;
				String lastIoError = null;

				for (List<String> candidate : candidates) {
					try {
						ExecutionResult result = runCommand(candidate);
						if (result.exitCode() == 0) {
							return result;
						}

						lastFailure = result;
						if (!looksLikeMissingPythonRuntime(result.lines())) {
							return result;
						}
					} catch (IOException e) {
						lastIoError = e.getMessage();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return new ExecutionResult(List.of("Execution interrupted."), -1, null);
					}
				}

				List<String> lines = new ArrayList<>();
				lines.add("Python runtime not found. Install Python from python.org and disable the Windows Store python alias if needed.");
				if (lastFailure != null) {
					lines.addAll(lastFailure.lines());
				} else if (lastIoError != null && !lastIoError.isBlank()) {
					lines.add("Details: " + lastIoError);
				}
					return new ExecutionResult(lines, -1, null);
			} catch (IOException e) {
					return new ExecutionResult(List.of("Failed to prepare Python script: " + e.getMessage()), -1, null);
			} finally {
				if (scriptPath != null) {
					try {
						Files.deleteIfExists(scriptPath);
					} catch (IOException ignored) {
						// ignore cleanup failures
					}
				}
			}
		}

		private static List<List<String>> buildPythonCommands(String scriptPath) {
			List<List<String>> commands = new ArrayList<>();
			commands.add(List.of("python", scriptPath));
			commands.add(List.of("py", "-3", scriptPath));
			commands.add(List.of("python3", scriptPath));

			for (String executable : discoverPythonExecutables()) {
				commands.add(List.of(executable, scriptPath));
			}
			return commands;
		}

		private static List<String> discoverPythonExecutables() {
			Set<String> executables = new LinkedHashSet<>();

			String localAppData = System.getenv("LOCALAPPDATA");
			if (localAppData != null && !localAppData.isBlank()) {
				scanPythonInstallRoot(executables, Path.of(localAppData, "Programs", "Python"));
			}

			String programFiles = System.getenv("ProgramFiles");
			if (programFiles != null && !programFiles.isBlank()) {
				scanPythonInstallRoot(executables, Path.of(programFiles, "Python"));
			}

			String programFilesX86 = System.getenv("ProgramFiles(x86)");
			if (programFilesX86 != null && !programFilesX86.isBlank()) {
				scanPythonInstallRoot(executables, Path.of(programFilesX86, "Python"));
			}

			return new ArrayList<>(executables);
		}

		private static void scanPythonInstallRoot(Set<String> executables, Path root) {
			if (root == null || !Files.isDirectory(root)) {
				return;
			}

			addIfExecutable(executables, root.resolve("python.exe"));
			try (var stream = Files.list(root)) {
				for (Path child : stream.toList()) {
					if (Files.isDirectory(child)) {
						addIfExecutable(executables, child.resolve("python.exe"));
					}
				}
			} catch (IOException ignored) {
				// ignore probing failures
			}
		}

		private static void addIfExecutable(Set<String> executables, Path candidate) {
			if (candidate == null || !Files.isRegularFile(candidate)) {
				return;
			}
			String normalized = candidate.toString().toLowerCase();
			if (normalized.contains("windowsapps")) {
				return;
			}
			executables.add(candidate.toString());
		}

		private static boolean looksLikeMissingPythonRuntime(List<String> lines) {
			String combined = String.join(" ", lines).toLowerCase();
			return combined.contains("microsoft store")
				|| combined.contains("python was not found")
				|| combined.contains("wurde nicht gefunden")
				|| combined.contains("nicht als interner")
				|| combined.contains("is not recognized")
				|| combined.contains("can't open file")
				|| combined.contains("no such file or directory");
		}

		private static ExecutionResult executeJava(String code) {
			Path scriptPath = null;
			try {
				scriptPath = Files.createTempFile("template-mod-jshell-", ".jsh");
				Files.writeString(scriptPath, code + System.lineSeparator() + "/exit" + System.lineSeparator(), StandardCharsets.UTF_8);
				List<List<String>> candidates = List.of(
					List.of("jshell", "--execution", "local", scriptPath.toString()),
					List.of("jshell.exe", "--execution", "local", scriptPath.toString())
				);
				return runAnyCommand(candidates, "JShell runtime not found. Install a JDK with jshell and ensure it is in PATH.");
			} catch (IOException e) {
				return new ExecutionResult(List.of("Failed to prepare Java script: " + e.getMessage()), -1, null);
			} finally {
				if (scriptPath != null) {
					try {
						Files.deleteIfExists(scriptPath);
					} catch (IOException ignored) {
						// ignore cleanup failures
					}
				}
			}
		}

		private static ExecutionResult executeCpp(String code) {
			Path tempDirectory = null;
			try {
				tempDirectory = Files.createTempDirectory("template-mod-cpp-");
				String executableName = isWindows() ? "snippet.exe" : "snippet";
				Path sourcePath = tempDirectory.resolve("snippet.cpp");
				Path executablePath = tempDirectory.resolve(executableName);
				Files.writeString(sourcePath, buildCppSource(code), StandardCharsets.UTF_8);

				List<List<String>> candidates = buildCppCompileCommands(sourcePath.getFileName().toString(), executableName);
				String lastIoError = null;

				for (List<String> candidate : candidates) {
					try {
						ExecutionResult compileResult = runCommand(candidate, tempDirectory);
						if (compileResult.exitCode() != 0) {
							if (compileResult.lines().size() == 1 && "(no output)".equals(compileResult.lines().get(0))) {
								return new ExecutionResult(List.of("C++ compilation failed with exit code " + compileResult.exitCode() + "."), compileResult.exitCode(), null);
							}
							return compileResult;
						}

						List<String> lines = new ArrayList<>();
						appendMeaningfulLines(lines, compileResult.lines());

						ExecutionResult runResult;
						try {
							runResult = runCommand(List.of(executablePath.toString()), tempDirectory);
						} catch (IOException e) {
							return new ExecutionResult(List.of("C++ compilation succeeded, but failed to launch executable: " + e.getMessage()), -1, null);
						}

						appendMeaningfulLines(lines, runResult.lines());
						if (lines.isEmpty()) {
							lines.add("(no output)");
						}
						return new ExecutionResult(lines, runResult.exitCode(), null);
					} catch (IOException e) {
						lastIoError = e.getMessage();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return new ExecutionResult(List.of("Execution interrupted."), -1, null);
					}
				}

				List<String> lines = new ArrayList<>();
				lines.add("C++ toolchain not found. Install g++, clang++, or cl.exe and ensure it is in PATH.");
				if (lastIoError != null && !lastIoError.isBlank()) {
					lines.add("Details: " + lastIoError);
				}
				return new ExecutionResult(lines, -1, null);
			} catch (IOException e) {
				return new ExecutionResult(List.of("Failed to prepare C++ workspace: " + e.getMessage()), -1, null);
			} finally {
				deleteRecursively(tempDirectory);
			}
		}

		private static List<List<String>> buildCppCompileCommands(String sourceName, String executableName) {
			List<List<String>> commands = new ArrayList<>();
			commands.addAll(List.of(
				List.of("g++", "-std=c++20", "-O2", sourceName, "-o", executableName),
				List.of("clang++", "-std=c++20", "-O2", sourceName, "-o", executableName),
				List.of("c++", "-std=c++20", "-O2", sourceName, "-o", executableName),
				List.of("cl", "/nologo", "/EHsc", "/std:c++20", "/MT", "/O2", "/Fe:" + executableName, sourceName)
			));

			for (VisualStudioSetup setup : discoverVisualStudioSetups()) {
				commands.add(buildVisualStudioCompileCommand(setup, sourceName, executableName));
			}

			return commands;
		}

		private static List<String> buildVisualStudioCompileCommand(VisualStudioSetup setup, String sourceName, String executableName) {
			String setupCommand = buildVisualStudioSetupCommand(setup);
			String compileCommand = setupCommand + " && cl /nologo /EHsc /std:c++20 /MT /O2 /Fe:" + executableName + " " + sourceName;
			return List.of("cmd", "/c", compileCommand);
		}

		private static String buildVisualStudioSetupCommand(VisualStudioSetup setup) {
			String script = "\"" + setup.scriptPath().toString() + "\"";
			String arguments = setup.arguments();
			if (arguments == null || arguments.isBlank()) {
				return "call " + script + " >nul";
			}
			return "call " + script + " " + arguments + " >nul";
		}

		private static List<VisualStudioSetup> discoverVisualStudioSetups() {
			Set<VisualStudioSetup> setups = new LinkedHashSet<>();
			discoverVisualStudioSetupsFromVsWhere(setups);
			discoverCommonVisualStudioSetups(setups);
			return new ArrayList<>(setups);
		}

		private static void discoverVisualStudioSetupsFromVsWhere(Set<VisualStudioSetup> setups) {
			String programFilesX86 = System.getenv("ProgramFiles(x86)");
			if (programFilesX86 == null || programFilesX86.isBlank()) {
				return;
			}

			Path vswhere = Path.of(programFilesX86, "Microsoft Visual Studio", "Installer", "vswhere.exe");
			if (!Files.isRegularFile(vswhere)) {
				return;
			}

			try {
				ExecutionResult result = runCommand(List.of(
					vswhere.toString(),
					"-latest",
					"-products",
					"*",
					"-requires",
					"Microsoft.VisualStudio.Component.VC.Tools.x86.x64",
					"-property",
					"installationPath"
				));

				for (String line : result.lines()) {
					String trimmed = line.strip();
					if (trimmed.isEmpty() || trimmed.startsWith("Process exited") || trimmed.startsWith("(") ) {
						continue;
					}

					try {
						Path installationPath = Path.of(trimmed);
						if (Files.isDirectory(installationPath)) {
							addVisualStudioSetupScripts(setups, installationPath);
						}
					} catch (RuntimeException ignored) {
						// ignore malformed vswhere output
					}
				}
			} catch (IOException ignored) {
				// ignore discovery failures
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

		private static void discoverCommonVisualStudioSetups(Set<VisualStudioSetup> setups) {
			String programFilesX86 = System.getenv("ProgramFiles(x86)");
			if (programFilesX86 == null || programFilesX86.isBlank()) {
				return;
			}

			Path root = Path.of(programFilesX86, "Microsoft Visual Studio");
			for (String year : List.of("2026", "2025", "2022", "2019")) {
				Path yearRoot = root.resolve(year);
				for (String edition : List.of("Community", "Professional", "Enterprise", "BuildTools")) {
					addVisualStudioSetupScripts(setups, yearRoot.resolve(edition));
				}
			}
		}

		private static void addVisualStudioSetupScripts(Set<VisualStudioSetup> setups, Path installationRoot) {
			addVisualStudioSetup(setups, installationRoot.resolve("Common7").resolve("Tools").resolve("VsDevCmd.bat"), "-no_logo -arch=x64 -host_arch=x64");
			addVisualStudioSetup(setups, installationRoot.resolve("VC").resolve("Auxiliary").resolve("Build").resolve("vcvars64.bat"), "");
			addVisualStudioSetup(setups, installationRoot.resolve("VC").resolve("Auxiliary").resolve("Build").resolve("vcvarsall.bat"), "x64");
		}

		private static void addVisualStudioSetup(Set<VisualStudioSetup> setups, Path scriptPath, String arguments) {
			if (Files.isRegularFile(scriptPath)) {
				setups.add(new VisualStudioSetup(scriptPath.toAbsolutePath().normalize(), arguments));
			}
		}

		private static String buildCppSource(String code) {
			String normalizedCode = code.replace("\r", "").stripTrailing();
			if (looksLikeFullCppProgram(normalizedCode)) {
				return normalizedCode + System.lineSeparator();
			}

			return String.join(System.lineSeparator(),
				"#include <algorithm>",
				"#include <cmath>",
				"#include <cstdint>",
				"#include <cstdlib>",
				"#include <exception>",
				"#include <iostream>",
				"#include <map>",
				"#include <optional>",
				"#include <set>",
				"#include <string>",
				"#include <tuple>",
				"#include <unordered_map>",
				"#include <utility>",
				"#include <vector>",
				"using namespace std;",
				"",
				"int main() {",
				"    try {",
				normalizedCode.indent(8).stripTrailing(),
				"    } catch (const exception& e) {",
				"        cerr << e.what() << endl;",
				"        return 1;",
				"    } catch (...) {",
				"        cerr << \"Unknown C++ exception\" << endl;",
				"        return 1;",
				"    }",
				"    return 0;",
				"}",
				"");
		}

		private static boolean looksLikeFullCppProgram(String code) {
			String normalized = code.replace("\r", "");
			return normalized.contains("#include")
				|| normalized.contains("int main(")
				|| normalized.contains("auto main(")
				|| normalized.contains("main()")
				|| normalized.contains("namespace ")
				|| normalized.contains("class ")
				|| normalized.contains("struct ")
				|| normalized.contains("template<")
				|| normalized.contains("template <")
				|| normalized.contains("using namespace ");
		}

		private static boolean isWindows() {
			return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
		}

		private static void appendMeaningfulLines(List<String> target, List<String> source) {
			if (source == null || source.isEmpty()) {
				return;
			}

			if (source.size() == 1 && "(no output)".equals(source.get(0))) {
				return;
			}

			target.addAll(source);
		}

		private static void deleteRecursively(Path root) {
			if (root == null || !Files.exists(root)) {
				return;
			}

			try (var paths = Files.walk(root)) {
				paths.sorted(Comparator.reverseOrder()).forEach(path -> {
					try {
						Files.deleteIfExists(path);
					} catch (IOException ignored) {
						// ignore cleanup failures
					}
				});
			} catch (IOException ignored) {
				// ignore cleanup failures
			}
		}

		private record VisualStudioSetup(Path scriptPath, String arguments) {
		}

		private static ExecutionResult runAnyCommand(List<List<String>> commands, String unavailableMessage) {
			String lastError = null;
			for (List<String> command : commands) {
				try {
					return runCommand(command);
				} catch (IOException e) {
					lastError = e.getMessage();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return new ExecutionResult(List.of("Execution interrupted."), -1, null);
				}
			}

			if (lastError == null || lastError.isBlank()) {
				return new ExecutionResult(List.of(unavailableMessage), -1, null);
			}
			return new ExecutionResult(List.of(unavailableMessage, "Details: " + lastError), -1, null);
		}

		private static ExecutionResult runCommand(List<String> command) throws IOException, InterruptedException {
			return runCommand(command, null);
		}

		private static ExecutionResult runCommand(List<String> command, Path workingDirectory) throws IOException, InterruptedException {
			ProcessBuilder processBuilder = new ProcessBuilder(command);
			processBuilder.redirectErrorStream(true);
			if (workingDirectory != null) {
				processBuilder.directory(workingDirectory.toFile());
			}
			Process process = processBuilder.start();

			boolean finished = process.waitFor(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			if (!finished) {
				process.destroyForcibly();
				return new ExecutionResult(List.of("Execution timed out after " + EXEC_TIMEOUT_SECONDS + " seconds."), -1, null);
			}

			String rawOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			List<String> lines = cleanOutput(rawOutput);
			int exitCode = process.exitValue();
			if (exitCode != 0) {
				lines.add("Process exited with code " + exitCode + ".");
			}
			if (lines.isEmpty()) {
				lines.add("(no output)");
			}
			return new ExecutionResult(lines, exitCode, null);
		}

		private static List<String> cleanOutput(String output) {
			if (output == null || output.isBlank()) {
				return new ArrayList<>();
			}

			List<String> lines = new ArrayList<>();
			for (String line : Arrays.asList(output.replace("\r", "").split("\n"))) {
				String trimmed = line.stripTrailing();
				if (trimmed.startsWith("|  Welcome to JShell")) {
					continue;
				}
				if (trimmed.startsWith("|  For an introduction")) {
					continue;
				}
				if (trimmed.equals("jshell>")) {
					continue;
				}
				if (!trimmed.isEmpty()) {
					lines.add(trimmed);
				}
				if (lines.size() >= 120) {
					break;
				}
			}
			return lines;
		}
	}

	private static ExecutionResult invokeAiFromPrompt(String prompt, ExecutorLanguage language) {
		String url = aiUrlSetting;
		String model = aiModelSetting;
		int timeoutSeconds = aiTimeoutSetting;

		// Normalize URL: if user provided only a base (no path) append /api/generate
		String targetUrl = url;
		try {
			URI parsed = URI.create(url);
			String path = parsed.getPath();
			if (path == null || path.isEmpty() || "/".equals(path)) {
				String base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
				targetUrl = base + "/api/generate";
			} else {
				// if the user provided a full path that already contains /api/generate, keep it
				if (!url.contains("/api/generate")) {
					targetUrl = url;
				}
			}
		} catch (IllegalArgumentException ignored) {
			// invalid URI will be handled later when building the request
			targetUrl = url;
		}

		String[] parts = prompt == null ? new String[0] : prompt.replace("\r", "").split("\n", -1);
		StringBuilder bodyBuilder = new StringBuilder();
		Pattern urlPattern = Pattern.compile("^\\s*OLLAMA_URL:\\s*(\\S+)\\s*$", Pattern.CASE_INSENSITIVE);
		Pattern modelPattern = Pattern.compile("^\\s*OLLAMA_MODEL:\\s*(\\S+)\\s*$", Pattern.CASE_INSENSITIVE);
		Pattern timeoutPattern = Pattern.compile("^\\s*TIMEOUT:\\s*(\\d+)\\s*$", Pattern.CASE_INSENSITIVE);

		for (String line : parts) {
			Matcher m;
			m = urlPattern.matcher(line);
			if (m.find()) { url = m.group(1); continue; }
			m = modelPattern.matcher(line);
			if (m.find()) { model = m.group(1); continue; }
			m = timeoutPattern.matcher(line);
			if (m.find()) { try { timeoutSeconds = Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {} continue; }
			bodyBuilder.append(line).append("\n");
		}

		String userPrompt = bodyBuilder.toString().trim();
		String languageLabel = language == null ? "Unknown" : language.label;
		String finalPrompt = buildAiPrompt(languageLabel, userPrompt);
		if (finalPrompt.isEmpty()) {
			return new ExecutionResult(List.of("AI prompt empty."), -1, null);
		}

		String json = "{\"model\":\"" + escapeJson(model) + "\",\"prompt\":\"" + escapeJson(finalPrompt) + "\",\"stream\":false,\"options\":{\"temperature\":0.2,\"top_p\":0.8}}";

		HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds))).build();
		HttpRequest request;
		try {
			request = HttpRequest.newBuilder()
				.uri(URI.create(targetUrl))
				.timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
				.header("Content-Type", "application/json")
				.POST(BodyPublishers.ofString(json))
				.build();
		} catch (IllegalArgumentException e) {
			return new ExecutionResult(List.of("Invalid Ollama URL: " + e.getMessage(), "Try a full URL like http://localhost:11434/api/generate"), -1, null);
		}

		try {
			HttpResponse<String> resp = client.send(request, BodyHandlers.ofString());
			int status = resp.statusCode();
			String body = resp.body() == null ? "" : resp.body();
			if (status != 200) {
				return new ExecutionResult(List.of("AI request failed: HTTP " + status, body), -1, null);
			}
			String script = extractScriptFromResponse(body);
			if (script == null || script.trim().isEmpty()) {
				String raw = body == null ? "" : body;
				String snippet = raw.length() > 800 ? raw.substring(0, 800) + "...(truncated)" : raw;
				return new ExecutionResult(List.of("AI returned empty script.", "raw_response:", snippet), -1, null);
			}
			String injected = script;
			// save script for quick reuse
			synchronized (aiSavedScripts) {
				aiSavedScripts.add(script);
				if (aiSavedScripts.size() > 50) {
					aiSavedScripts.remove(0);
				}
				aiSavedIndex = aiSavedScripts.size() - 1;
			}
			return new ExecutionResult(List.of("AI provided a script for " + languageLabel + "."), 0, injected);
		} catch (Exception e) {
			return new ExecutionResult(List.of("AI request failed: " + e.getMessage()), -1, null);
		}
	}

	private static String buildAiPrompt(String languageLabel, String userPrompt) {
		StringBuilder prompt = new StringBuilder();
		prompt.append("You are a precise coding assistant for an in-game executor.\n");
		prompt.append("Current mode: ").append(languageLabel).append('\n');
		prompt.append("Return ONLY code/script. No markdown, no backticks, no explanation, no preface.\n");
		prompt.append("If the input is partial, continue it into a complete, syntactically valid result.\n");
		prompt.append("Keep it concise and specific.\n\n");
		prompt.append("User input:\n");
		prompt.append(userPrompt);
		return prompt.toString().trim();
	}

	private static String escapeJson(String s) {
		if (s == null) return "";
		return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
	}

	private static String extractScriptFromResponse(String body) {
		if (body == null) return null;
		String trimmed = body.trim();
		// If JSON-like, try to find a useful text field; avoid token arrays / metadata
		if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
			String direct = extractJsonField(body, "response");
			if (direct != null && !direct.isBlank()) {
				String cleaned = unwrapCodeFence(direct).trim();
				if (!cleaned.isBlank()) {
					return cleaned;
				}
			}
			String[] keys = {"\"text\"", "\"output_text\"", "\"output\"", "\"generated_text\"", "\"generated\"", "\"script\"", "\"content\"", "\"message\"", "\"response\"", "\"result\"", "\"results\"", "\"choices\""};
			String best = null;
			for (String key : keys) {
				int idx = body.indexOf(key);
				while (idx >= 0) {
					int colon = body.indexOf(':', idx + key.length());
					if (colon >= 0) {
						int quote = body.indexOf('"', colon + 1);
						if (quote >= 0) {
							String candidate = extractJsonStringAt(body, quote);
							if (candidate != null && !candidate.isBlank() && !isLikelyTokenArray(candidate) && !looksLikeMetadata(candidate)) {
								if (best == null || candidate.length() > best.length()) best = candidate;
							}
						} else {
							// maybe nested; try finding a nearby "text" inside this region
							int innerText = body.indexOf("\"text\"", colon + 1);
							if (innerText >= 0) {
								int q = body.indexOf('"', innerText + 6);
								if (q >= 0) {
									String candidate = extractJsonStringAt(body, q);
									if (candidate != null && !candidate.isBlank() && !isLikelyTokenArray(candidate) && !looksLikeMetadata(candidate)) {
										if (best == null || candidate.length() > best.length()) best = candidate;
									}
								}
							}
						}
					}
					idx = body.indexOf(key, idx + 1);
				}
			}
			if (best != null) return best;
			String longest = findLongestQuotedString(body);
			if (longest != null && !isLikelyTokenArray(longest) && !looksLikeMetadata(longest)) return longest;
			return null;
		}
		// plain text -> return as-is, but remove surrounding fences if present
		return unwrapCodeFence(body).trim();
	}

	private static String extractJsonField(String body, String fieldName) {
		if (body == null || fieldName == null) return null;
		String needle = "\"" + fieldName + "\"";
		int idx = body.indexOf(needle);
		if (idx < 0) return null;
		int colon = body.indexOf(':', idx + needle.length());
		if (colon < 0) return null;
		int quote = body.indexOf('"', colon + 1);
		if (quote < 0) return null;
		return extractJsonStringAt(body, quote);
	}

	private static String unwrapCodeFence(String text) {
		if (text == null) return null;
		String trimmed = text.trim();
		int firstFence = trimmed.indexOf("```");
		if (firstFence < 0) return trimmed;
		int secondFence = trimmed.indexOf("```", firstFence + 3);
		if (secondFence < 0) return trimmed.substring(firstFence + 3).trim();
		String inner = trimmed.substring(firstFence + 3, secondFence).trim();
		int newline = inner.indexOf('\n');
		if (newline >= 0) {
			String maybeLang = inner.substring(0, newline).trim();
			if (maybeLang.length() <= 12 && maybeLang.matches("[A-Za-z0-9_+-]*")) {
				inner = inner.substring(newline + 1).trim();
			}
		}
		return inner;
	}

	private static boolean looksLikeMetadata(String s) {
		if (s == null) return false;
		String lower = s.toLowerCase();
		return lower.contains("total_duration") || lower.contains("load_duration") || lower.contains("eval_duration") || lower.contains("prompt_eval");
	}

	private static boolean isLikelyTokenArray(String s) {
		if (s == null) return false;
		String t = s.replaceAll("\\s+", "");
		return t.matches("^[0-9,\\[\\]\\s]+$");
	}

	private static String extractJsonStringAt(String body, int quoteIndex) {
		if (body == null || quoteIndex < 0 || quoteIndex >= body.length()) return null;
		StringBuilder sb = new StringBuilder();
		for (int i = quoteIndex + 1; i < body.length(); i++) {
			char c = body.charAt(i);
			if (c == '"') {
				// check not escaped
				int backslashes = 0;
				int j = i - 1;
				while (j >= 0 && body.charAt(j) == '\\') { backslashes++; j--; }
				if (backslashes % 2 == 0) {
					return sb.toString();
				} else {
					// escaped quote
					sb.append('"');
					continue;
				}
			}
			if (c == '\\' && i + 1 < body.length()) {
				char next = body.charAt(i + 1);
				switch (next) {
					case 'n' -> sb.append('\n');
					case 'r' -> sb.append('\r');
					case 't' -> sb.append('\t');
					case '"' -> sb.append('"');
					case '\\' -> sb.append('\\');
					default -> sb.append(next);
				}
				i++;
				continue;
			}
			sb.append(c);
		}
		return sb.toString();
	}

	private static String findLongestQuotedString(String body) {
		if (body == null) return null;
		String best = null;
		int idx = 0;
		while (true) {
			int q = body.indexOf('"', idx);
			if (q < 0) break;
			String s = extractJsonStringAt(body, q);
			if (s != null && !s.isBlank() && (best == null || s.length() > best.length())) best = s;
			idx = q + 1;
		}
		return best;
	}

	private record ExecutionRun(long sequence, ExecutionResult result) {
	}

	public static final class IngameBindings {
		public static volatile Minecraft minecraft;

		private IngameBindings() {
		}
	}

	private record ExecutionResult(List<String> lines, int exitCode, String injectedScript) {
	}
}
