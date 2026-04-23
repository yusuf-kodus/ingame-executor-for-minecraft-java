package com.example.lua;

import java.util.List;

public record LuaExecutionResult(List<String> lines, int exitCode) {
}

