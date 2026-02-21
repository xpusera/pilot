# Luanti Android Modding API

This document describes the new modding APIs added to Luanti Android for HTML/CSS/JS embedding and Termux integration.

## WebView API

The WebView API allows mods to embed HTML/CSS/JS content either as an overlay on screen or as a texture on entities/blocks.

### Creating a WebView

```lua
local id = core.webview.create({
    x = 100,           -- X position on screen (pixels)
    y = 100,           -- Y position on screen (pixels)
    width = 400,       -- Width (pixels)
    height = 300,      -- Height (pixels)
    texture_mode = false  -- true for texture rendering, false for overlay
})
```

### Loading Content

```lua
-- Load HTML string
core.webview.load_html(id, [[
<!DOCTYPE html>
<html>
<head>
    <style>
        body { background: rgba(0,0,0,0.8); color: white; }
        button { padding: 10px 20px; font-size: 16px; }
    </style>
</head>
<body>
    <h1>Hello from WebView!</h1>
    <button onclick="luanti.send('action', 'teleport')">Teleport</button>
</body>
</html>
]])

-- Load from file (relative to mod folder)
core.webview.load_file(id, "ui/menu.html")

-- Load from URL
core.webview.load_url(id, "https://example.com")
```

### Positioning and Sizing

```lua
core.webview.set_position(id, 200, 150)
core.webview.set_size(id, 500, 400)
core.webview.set_visible(id, true)
```

### Executing JavaScript

```lua
core.webview.execute_js(id, "document.getElementById('score').innerText = '100'")
```

### Receiving Messages from JavaScript

JavaScript in the WebView can send messages to Lua using:
```javascript
luanti.send("eventType", "data");
// or with object data
luanti.send("eventType", { action: "teleport", x: 0, y: 100, z: 0 });
```

Lua can receive these messages:
```lua
core.webview.on_message(id, "action", function(data, webview_id)
    if data == "teleport" then
        local player = core.localplayer
        player:set_pos({x=0, y=100, z=0})
    end
end)

-- Or listen to all events
core.webview.on_message(id, "*", function(event, data, webview_id)
    core.log("info", "WebView event: " .. event .. " data: " .. data)
end)
```

### Texture Mode (for blocks/entities)

When `texture_mode = true`, the WebView is rendered offscreen and can be captured as a texture:

```lua
local id = core.webview.create({
    width = 256,
    height = 256,
    texture_mode = true
})

core.webview.load_html(id, "<html>...</html>")

-- Check if texture needs update
if core.webview.needs_texture_update(id) then
    local pixels = core.webview.capture_texture(id)
    -- pixels is raw RGBA data that can be used as a texture
end

-- Get texture dimensions
local width, height = core.webview.get_texture_size(id)
```

### Cleanup

```lua
core.webview.destroy(id)
```

## Termux API

The Termux API allows mods to execute shell commands in Termux without modifying Termux itself. This enables AI agents and automation.

### Prerequisites

1. Termux must be installed on the device
2. In Termux, run: `echo "allow-external-apps=true" >> ~/.termux/termux.properties`
3. Restart Termux
4. Grant Luanti the RUN_COMMAND permission via Android settings

### Checking Availability

```lua
if core.termux.is_available() then
    core.log("info", "Termux integration is available!")
end
```

### Executing Commands

```lua
-- Execute a single command
local cmd_id = core.termux.execute({
    executable = "python",
    args = {"script.py", "--arg1", "value"},
    workdir = "/data/data/com.termux/files/home",
    background = true,
    stdin = "input data",
    callback = function(result)
        core.log("info", "stdout: " .. result.stdout)
        core.log("info", "stderr: " .. result.stderr)
        core.log("info", "exit_code: " .. result.exit_code)
    end
})

-- Execute shell command (shorthand)
core.termux.execute_shell("ls -la && pwd", true, function(result)
    core.log("info", result.stdout)
end)

-- Execute a script via stdin
core.termux.execute_script([[
#!/bin/bash
echo "Hello from script"
for i in 1 2 3; do
    echo "Count: $i"
done
]], true, function(result)
    core.log("info", result.stdout)
end)
```

### Output Hooks

Monitor command output for specific patterns:

```lua
-- Simple string match
local hook_id = core.termux.add_output_hook("ERROR:", false, function(hook)
    core.log("error", "Error detected in output: " .. hook.output)
end)

-- Regex pattern match
local hook_id2 = core.termux.add_output_hook("teleport\\s+(\\d+)", true, function(hook)
    core.log("info", "Teleport command detected!")
    -- hook.pattern, hook.output, hook.source_command_id available
end)

-- Remove hook when done
core.termux.remove_output_hook(hook_id)
```

### Sending Input

```lua
-- Send command to Termux
core.termux.send_input("echo 'Hello from Luanti'")
```

### Getting Termux Paths

```lua
local paths = core.termux.get_paths()
-- paths.home = "/data/data/com.termux/files/home"
-- paths.bin = "/data/data/com.termux/files/usr/bin"
-- paths.prefix = "/data/data/com.termux/files/usr"
```

## Example: AI Agent Integration

```lua
-- AI agent that responds to game events via Termux
local ai_active = false

local function start_ai_agent()
    if not core.termux.is_available() then
        core.log("error", "Termux not available")
        return
    end
    
    -- Start AI server in Termux
    core.termux.execute_shell("python ~/ai_agent.py --port 8080", false)
    ai_active = true
    
    -- Hook for AI responses
    core.termux.add_output_hook("AI_ACTION:", false, function(hook)
        local action = hook.output:match("AI_ACTION:(%w+)")
        if action == "JUMP" then
            -- Make player jump
        elseif action == "MOVE" then
            -- Move player
        end
    end)
end

-- Send game state to AI
local function update_ai(player_pos, nearby_entities)
    if not ai_active then return end
    local data = core.serialize({pos = player_pos, entities = nearby_entities})
    core.termux.send_input("echo '" .. data .. "' | nc localhost 8080")
end

core.register_globalstep(function(dtime)
    if ai_active then
        local player = core.localplayer
        if player then
            update_ai(player:get_pos(), {})
        end
    end
end)
```

## Example: Custom HUD with WebView

```lua
local hud_webview = nil

core.register_on_connect(function()
    -- Create HUD overlay
    hud_webview = core.webview.create({
        x = 10,
        y = 10,
        width = 300,
        height = 100,
        texture_mode = false
    })
    
    core.webview.load_html(hud_webview, [[
<!DOCTYPE html>
<html>
<head>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            background: linear-gradient(135deg, rgba(0,0,0,0.7), rgba(30,30,60,0.7));
            color: white;
            font-family: 'Segoe UI', Arial, sans-serif;
            padding: 10px;
            border-radius: 10px;
        }
        .stat { display: flex; align-items: center; margin: 5px 0; }
        .label { width: 80px; font-weight: bold; }
        .bar { flex: 1; height: 20px; background: #333; border-radius: 10px; overflow: hidden; }
        .fill { height: 100%; transition: width 0.3s ease; }
        .health .fill { background: linear-gradient(90deg, #ff4444, #ff8888); }
        .mana .fill { background: linear-gradient(90deg, #4444ff, #8888ff); }
    </style>
</head>
<body>
    <div class="stat health">
        <span class="label">Health</span>
        <div class="bar"><div class="fill" id="health" style="width: 100%"></div></div>
    </div>
    <div class="stat mana">
        <span class="label">Mana</span>
        <div class="bar"><div class="fill" id="mana" style="width: 100%"></div></div>
    </div>
</body>
</html>
    ]])
end)

-- Update HUD
local function update_hud(health, max_health, mana, max_mana)
    if not hud_webview then return end
    local health_pct = (health / max_health) * 100
    local mana_pct = (mana / max_mana) * 100
    core.webview.execute_js(hud_webview, string.format(
        "document.getElementById('health').style.width='%d%%';" ..
        "document.getElementById('mana').style.width='%d%%';",
        health_pct, mana_pct
    ))
end
```

## Permissions

The following Android permissions are enabled and can be used by WebView and Termux:

- Camera
- Microphone
- Storage (full access)
- Location
- Bluetooth
- Sensors
- Network

WebView pages can request these permissions through standard web APIs. Termux commands can use corresponding Termux-API tools.
