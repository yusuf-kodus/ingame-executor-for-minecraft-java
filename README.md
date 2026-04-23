# Ingame Executor
**made by zerrissenheit**

A live scripting console built into Minecraft for developers and experimenters.
Open it, write code, run it – without tabbing out or restarting anything.

## Controls
| Key | Action |
|-----|--------|
| N | Open console |
| Esc | Close console |
| Tab | Switch runtime |
| F5 / Ctrl+Enter | Run code |
| Ctrl+O | AI settings |
| Ctrl+K | Generate code with AI |
| Ctrl+L | Load saved AI script |

## Features
- Java Ingame mode running inside the Minecraft JVM
- Lua Ingame mode with full scripting support
- Python local execution
- Java execution via external JShell
- C++ execution via local toolchain
- AI assistant via Ollama
- Ready bindings in Ingame modes: `mc`, `player`, `level`
- Live output panel for print, errors and execution status

## Installation
1. Install [Fabric Loader](https://fabricmc.net/)
2. Install [Fabric API](https://modrinth.com/mod/fabric-api)
3. Download the latest release and place the `.jar` in your `mods` folder

## Runtime Requirements
- **Python**: requires Python installed and available in PATH
- **Java (JShell)**: requires a JDK with JShell in PATH
- **Java/Lua (Ingame)**: no external runtime needed
- **C++**: requires a C++ compiler (e.g. g++) in PATH
- **AI (Ollama)**: requires a running Ollama instance (default: http://localhost:11434)

## License
This project is proprietary. You may not copy, modify, or redistribute 
this mod without explicit permission from the author.

## Warning
This scripting console is intended for development and experimentation only.
Any misuse for harmful actions, unauthorized access, or manipulation of 
third-party servers is strictly prohibited. Use at your own risk. The author 
accepts no liability for damage, data loss, account bans, or legal consequences.
