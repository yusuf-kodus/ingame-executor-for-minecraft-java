package me.zerrissenheit.executor.lua;

import java.util.List;

// just a record for lua execution results whatever - needed to return shit from lua
public record LuaExecutionResult(List<String> lines, int exitCode) {
	// yeah that's it, nothing special here lol
}

