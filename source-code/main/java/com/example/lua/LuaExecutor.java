package com.example.lua;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class LuaExecutor {
    public static LuaExecutionResult executeLua(String code, Object minecraft) {
        List<String> lines = new ArrayList<>();
        int exitCode = 0;
        LuaValue result = LuaValue.NIL;
        Throwable failure = null;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errorOutput = new ByteArrayOutputStream();

        try (
            PrintStream shellOut = new PrintStream(output, true, StandardCharsets.UTF_8);
            PrintStream shellErr = new PrintStream(errorOutput, true, StandardCharsets.UTF_8)
        ) {
            Globals globals = JsePlatform.standardGlobals();
            globals.STDOUT = shellOut;
            globals.STDERR = shellErr;

            if (minecraft != null) {
                LuaValue minecraftValue = CoerceJavaToLua.coerce(minecraft);
                globals.set("minecraft", minecraftValue);
                globals.set("mc", minecraftValue);
                globals.set("player", readMinecraftField(minecraft, "player"));
                globals.set("level", readMinecraftField(minecraft, "level"));
            }

            LuaValue chunk = globals.load(code);
            result = chunk.call();

            shellOut.flush();
            shellErr.flush();
        } catch (Throwable e) {
            exitCode = -1;
            failure = e;
        }

        appendCapturedOutput(lines, output);
        appendCapturedOutput(lines, errorOutput);

        if (failure != null) {
            lines.add("Lua error: " + failure.getMessage());
        } else if (!result.isnil()) {
            lines.add(" -> " + result.tojstring());
        }

        if (lines.isEmpty()) {
            lines.add("Lua OK");
        }

        return new LuaExecutionResult(lines, exitCode);
    }

    private static LuaValue readMinecraftField(Object minecraft, String fieldName) {
        try {
            Field field = minecraft.getClass().getField(fieldName);
            Object value = field.get(minecraft);
            return value == null ? LuaValue.NIL : CoerceJavaToLua.coerce(value);
        } catch (ReflectiveOperationException ignored) {
            return LuaValue.NIL;
        }
    }

    private static void appendCapturedOutput(List<String> lines, ByteArrayOutputStream output) {
        String text = output.toString(StandardCharsets.UTF_8);
        if (text.isEmpty()) {
            return;
        }

        String normalized = text.replace("\r", "");
        String[] parts = normalized.split("\n", -1);
        int limit = parts.length;
        if (limit > 0 && parts[limit - 1].isEmpty()) {
            limit -= 1;
        }

        for (int i = 0; i < limit; i++) {
            lines.add(parts[i]);
        }
    }
}

