-- html_render_test/init.lua
-- Tests two things:
--   1. Node "html_render_test:screen" - right-click opens a WebView OVERLAY (like a GUI)
--   2. Entity "html_render_test:billboard" - renders HTML *as its texture* (texture_mode)
--      faces the player always (upright_sprite), does not move
--
-- How to use:
--   /give <player> html_render_test:screen       → place it, right-click it
--   /give <player> html_render_test:spawner      → place it, punch it to spawn billboard

-----------------------------------------------------------------------
-- 1. NODE: HTML SCREEN INTERFACE (overlay WebView on right-click)
-----------------------------------------------------------------------
-- Right-clicking this node opens a full-screen HTML WebView overlay.
-- The HTML is a mock computer terminal with a working clock and button.
-- This tests the basic overlay path, NOT texture_mode.

local SCREEN_HTML = [[
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body {
	background: #0a0a0a;
	color: #00ff41;
	font-family: 'Courier New', monospace;
	min-height: 100vh;
	display: flex;
	flex-direction: column;
	align-items: center;
	justify-content: center;
}
.terminal {
	border: 2px solid #00ff41;
	border-radius: 8px;
	padding: 24px;
	width: 90vw;
	max-width: 500px;
	box-shadow: 0 0 30px rgba(0,255,65,0.3);
}
h1 { font-size: 1.2em; margin-bottom: 16px; text-align: center; }
.line { margin: 6px 0; font-size: 0.9em; }
.ok { color: #00ff41; }
.warn { color: #ffcc00; }
#clock { font-size: 2em; text-align: center; color: #0ff; margin: 16px 0; }
#counter { font-size: 1.4em; text-align: center; margin: 10px 0; }
.btn-row { display: flex; gap: 12px; margin-top: 20px; justify-content: center; }
button {
	background: transparent;
	border: 1px solid #00ff41;
	color: #00ff41;
	padding: 10px 22px;
	font-family: monospace;
	font-size: 1em;
	border-radius: 4px;
	cursor: pointer;
}
button:active { background: rgba(0,255,65,0.2); }
button.red { border-color: #ff4444; color: #ff4444; }
button.red:active { background: rgba(255,68,68,0.2); }
</style>
</head>
<body>
<div class="terminal">
	<h1>[ LUANTI HTML NODE INTERFACE ]</h1>
	<div class="line ok">&#x2713; WebView overlay: WORKING</div>
	<div class="line ok">&#x2713; HTML + CSS: WORKING</div>
	<div class="line ok">&#x2713; JavaScript: WORKING</div>
	<div id="clock">--:--:--</div>
	<div class="line" style="text-align:center">Button presses:</div>
	<div id="counter" class="ok">0</div>
	<div class="btn-row">
		<button onclick="inc()">PRESS ME</button>
		<button class="red" onclick="luanti.close()">[ CLOSE ]</button>
	</div>
</div>
<script>
var n = 0;
function inc() {
	document.getElementById('counter').textContent = ++n;
	luanti.send('button_press', String(n));
}
function tick() {
	document.getElementById('clock').textContent = new Date().toLocaleTimeString();
}
setInterval(tick, 1000);
tick();
</script>
</body>
</html>
]]

core.register_node("html_render_test:screen", {
	description = "HTML Screen\n(Right-click to open HTML interface)",
	tiles = {
		"html_screen_top.png",
		"html_screen_top.png",
		"html_screen_side.png",
		"html_screen_side.png",
		"html_screen_back.png",
		"html_screen_front.png",
	},
	groups = {cracky = 3},
	sounds = {},

	on_rightclick = function(pos, node, clicker, itemstack, pointed_thing)
		local name = clicker and clicker:get_player_name() or "?"

		-- WebView API is Android-only. Gracefully skip on PC.
		if not core.webview_create then
			core.chat_send_player(name, "[HTML Test] WebView API not available (Android only)")
			return itemstack
		end

		local wv_id = core.webview.create({
			x = 0,
			y = 0,
			width = -1,   -- -1 = MATCH_PARENT (fullscreen)
			height = -1,
			texture_mode = false,
		})

		if not wv_id then
			core.chat_send_player(name, "[HTML Test] Failed to create WebView")
			return itemstack
		end

		core.webview.load_html(wv_id, SCREEN_HTML)

		-- Listen for messages from JS
		core.webview.on_message(wv_id, "button_press", function(data)
			core.chat_send_player(name, "[HTML Screen] Button pressed " .. tostring(data) .. " times")
		end)

		core.webview.on_message(wv_id, "close", function()
			core.webview.destroy(wv_id)
		end)

		return itemstack
	end,
})

-----------------------------------------------------------------------
-- 2. ENTITY: HTML BILLBOARD (texture_mode WebView rendered on entity)
-----------------------------------------------------------------------
-- This entity:
--   * Has visual = "upright_sprite" so it always faces the player
--   * Does NOT move (physical = false, static_save = false)
--   * Creates a WebView in texture_mode at 128×128
--   * The WebView renders a live clock + status HTML page
--   * Every second (when texture is dirty), captures raw RGBA bytes,
--     encodes to PNG, base64-encodes it, and applies via [png:base64 modifier
--   * This tests the full: WebView → raw pixels → PNG → entity texture pipeline

local BILLBOARD_HTML = [[
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body {
	background: #000080;
	color: #ffffff;
	font-family: 'Courier New', monospace;
	width: 128px;
	height: 128px;
	overflow: hidden;
	display: flex;
	flex-direction: column;
	align-items: center;
	justify-content: center;
	border: 3px solid #00ffff;
	font-size: 10px;
}
h2 { color: #00ffff; font-size: 11px; margin-bottom: 6px; text-align: center; }
#t  { color: #00ff00; font-size: 16px; font-weight: bold; margin: 4px 0; }
.ok { color: #00ff00; font-size: 9px; margin: 2px 0; }
.lbl { color: #aaaaaa; font-size: 8px; }
</style>
</head>
<body>
<h2>HTML ENTITY</h2>
<div class="lbl">live clock:</div>
<div id="t">--:--:--</div>
<div class="ok">&#x2713; texture_mode OK</div>
<div class="ok">&#x2713; WebView entity</div>
<script>
function tick() {
	document.getElementById('t').textContent = new Date().toLocaleTimeString();
	if (typeof LuantiBridge !== 'undefined') {
		LuantiBridge.requestTextureUpdate();
	}
}
setInterval(tick, 1000);
tick();
</script>
</body>
</html>
]]

core.register_entity("html_render_test:billboard", {
	initial_properties = {
		visual = "upright_sprite",
		visual_size = {x = 2, y = 2},
		-- Blank placeholder until WebView texture is captured
		textures = {"blank.png"},
		physical = false,
		collide_with_objects = false,
		static_save = false,
		is_visible = true,
		makes_footstep_sound = false,
	},

	webview_id = nil,
	tex_timer = 0,
	tex_ready = false,

	on_activate = function(self, staticdata, dtime_s)
		if not core.webview_create then
			core.log("warning", "[html_render_test] WebView API not available — entity texture mode skipped")
			return
		end

		self.webview_id = core.webview.create({
			width = 128,
			height = 128,
			texture_mode = true,
		})

		if not self.webview_id then
			core.log("error", "[html_render_test] Failed to create texture-mode WebView for entity")
			return
		end

		core.webview.load_html(self.webview_id, BILLBOARD_HTML)
		core.log("action", "[html_render_test] Billboard entity activated, webview_id=" .. tostring(self.webview_id))
	end,

	on_step = function(self, dtime, moveresult)
		if not self.webview_id then return end

		self.tex_timer = (self.tex_timer or 0) + dtime
		-- Throttle: only try once per second maximum
		if self.tex_timer < 1.0 then return end
		self.tex_timer = 0

		-- Only capture when WebView signals a content change
		if not core.webview.needs_texture_update(self.webview_id) then return end

		local raw = core.webview.capture_texture(self.webview_id)
		if not raw or #raw == 0 then
			core.log("warning", "[html_render_test] capture_texture returned empty")
			return
		end

		local w, h = core.webview.get_texture_size(self.webview_id)
		if w <= 0 or h <= 0 then
			core.log("warning", "[html_render_test] texture size is 0x0")
			return
		end

		-- Raw bytes from Android Bitmap are RGBA (Skia kRGBA_8888).
		-- encode_png expects RGBA too, so no channel swap needed.
		local ok_png, png = pcall(core.encode_png, w, h, raw, 1)
		if not ok_png or not png then
			core.log("error", "[html_render_test] encode_png failed: " .. tostring(png))
			return
		end

		local ok_b64, b64 = pcall(core.encode_base64, png)
		if not ok_b64 or not b64 then
			core.log("error", "[html_render_test] encode_base64 failed: " .. tostring(b64))
			return
		end

		-- Apply as entity texture via [png:base64 modifier
		self.object:set_properties({
			textures = {"[png:" .. b64},
		})

		if not self.tex_ready then
			self.tex_ready = true
			core.log("action", "[html_render_test] First HTML texture applied to entity! size=" .. w .. "x" .. h)
		end
	end,

	on_deactivate = function(self, removal)
		if self.webview_id then
			core.webview.destroy(self.webview_id)
			self.webview_id = nil
			core.log("action", "[html_render_test] Billboard deactivated, WebView destroyed")
		end
	end,

	on_punch = function(self, puncher, time_from_last_punch, tool_capabilities, dir)
		-- Punching the billboard removes it
		if puncher and puncher:is_player() then
			core.chat_send_player(puncher:get_player_name(), "[HTML Test] Removing billboard entity")
		end
		self.object:remove()
	end,
})

-----------------------------------------------------------------------
-- 3. SPAWNER NODE (punch to spawn a billboard entity above it)
-----------------------------------------------------------------------
core.register_node("html_render_test:spawner", {
	description = "HTML Billboard Spawner\n(Punch to spawn HTML entity above)",
	tiles = {"default_mese_block.png"},
	groups = {cracky = 3},
	sounds = {},

	on_punch = function(pos, node, puncher, pointed_thing)
		if not puncher or not puncher:is_player() then return end
		local name = puncher:get_player_name()

		if not core.webview_create then
			core.chat_send_player(name, "[HTML Test] WebView API not available (Android only)")
			return
		end

		local spawn_pos = {x = pos.x, y = pos.y + 2, z = pos.z}
		core.add_entity(spawn_pos, "html_render_test:billboard")
		core.chat_send_player(name, "[HTML Test] Billboard spawned at " ..
			core.pos_to_string(spawn_pos) ..
			" — should show HTML texture in ~1s. Punch billboard to remove.")
	end,
})

-----------------------------------------------------------------------
-- 4. CRAFT RECIPES (simple, no optional deps)
-----------------------------------------------------------------------
-- Screen: 4 glass panes in a square → not everyone has glass, use stone
core.register_craft({
	output = "html_render_test:screen",
	recipe = {
		{"", "group:stone", ""},
		{"group:stone", "group:stone", "group:stone"},
		{"", "group:stone", ""},
	},
})

-- Spawner: just 3 stone in a row (easy to get for testing)
core.register_craft({
	output = "html_render_test:spawner",
	recipe = {
		{"group:stone", "group:stone", "group:stone"},
	},
})

core.log("action", "[html_render_test] mod loaded — node:screen + entity:billboard ready")
