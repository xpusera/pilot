-- Luanti WebView Modding API (Client-Side)
-- SPDX-License-Identifier: LGPL-2.1-or-later
-- Copyright (C) 2024 Luanti Contributors

-- WebView API wrapper for HTML/CSS/JS embedding
core.webview = {}

local webviews = {}

-- Special size constant: pass this as width or height to use MATCH_PARENT (fullscreen on that axis)
core.webview.SIZE_FULLSCREEN = -1

function core.webview.create(def)
	local x = def.x or 0
	local y = def.y or 0
	local width = def.width or 400
	local height = def.height or 300
	local texture_mode = def.texture_mode or false

	local id = core.webview_create(x, y, width, height, texture_mode)
	if id and id > 0 then
		webviews[id] = {
			id = id,
			x = x,
			y = y,
			width = width,
			height = height,
			texture_mode = texture_mode,
			callbacks = {}
		}
		return id
	end
	return nil
end

function core.webview.load_html(id, html)
	if not webviews[id] then return false end
	core.webview_load_html(id, html)
	return true
end

function core.webview.load_file(id, path)
	if not webviews[id] then return false end
	local modpath = core.get_modpath(core.get_current_modname())
	local full_path = modpath .. DIR_DELIM .. path
	core.webview_load_file(id, full_path)
	return true
end

function core.webview.load_url(id, url)
	if not webviews[id] then return false end
	core.webview_load_url(id, url)
	return true
end

function core.webview.execute_js(id, script)
	if not webviews[id] then return false end
	core.webview_execute_js(id, script)
	return true
end

function core.webview.set_position(id, x, y)
	if not webviews[id] then return false end
	webviews[id].x = x
	webviews[id].y = y
	core.webview_set_position(id, x, y)
	return true
end

function core.webview.set_size(id, width, height)
	if not webviews[id] then return false end
	webviews[id].width = width
	webviews[id].height = height
	core.webview_set_size(id, width, height)
	return true
end

-- Convenience: set WebView to fill entire screen
function core.webview.set_fullscreen(id)
	return core.webview.set_size(id, -1, -1)
end

function core.webview.set_visible(id, visible)
	if not webviews[id] then return false end
	core.webview_set_visible(id, visible)
	return true
end

function core.webview.destroy(id)
	if not webviews[id] then return false end
	core.webview_destroy(id)
	webviews[id] = nil
	return true
end

-- Alias for destroy
function core.webview.close(id)
	return core.webview.destroy(id)
end

-- Set WebView background color (r, g, b, a all 0-255). Use a=0 for transparent.
function core.webview.set_background(id, r, g, b, a)
	if not webviews[id] then return false end
	a = a or 255
	core.webview_set_background_color(id, r or 255, g or 255, b or 255, a)
	return true
end

-- Raw ARGB capture (legacy)
function core.webview.capture_texture(id)
	if not webviews[id] then return nil end
	return core.webview_capture_texture(id)
end

-- Capture as PNG bytes directly (faster than raw ARGB + encode_png)
function core.webview.capture_png(id)
	if not webviews[id] then return nil end
	return core.webview_capture_png(id)
end

-- Capture PNG and return a "[png:base64...]" texture string ready for set_properties
function core.webview.make_texture_string(id)
	local png_data = core.webview.capture_png(id)
	if not png_data or #png_data == 0 then return nil end
	local b64 = core.encode_base64(png_data)
	if not b64 or #b64 == 0 then return nil end
	return "[png:" .. b64
end

function core.webview.needs_texture_update(id)
	if not webviews[id] then return false end
	return core.webview_needs_texture_update(id)
end

function core.webview.get_texture_size(id)
	if not webviews[id] then return 0, 0 end
	return core.webview_get_texture_size(id)
end

function core.webview.on_message(id, event_type, callback)
	if not webviews[id] then return false end
	if not webviews[id].callbacks[event_type] then
		webviews[id].callbacks[event_type] = {}
	end
	table.insert(webviews[id].callbacks[event_type], callback)
	return true
end

function core.webview.get_info(id)
	return webviews[id]
end

function core.webview.get_all_ids()
	return core.webview_get_ids()
end

function core.webview.register_content(path, data, mime_type)
	core.webview_register_content(path, data, mime_type)
end

function core.webview.register_html(path, html)
	core.webview_register_html(path, html)
end

function core.webview.unregister_content(path)
	core.webview_unregister_content(path)
end

-- Bind a texture-mode WebView to an entity (client-side use).
-- Returns a binding handle with a :stop() method.
function core.webview.bind_to_entity(wv_id, entity_obj, interval_secs)
	interval_secs = interval_secs or 0.1
	local running = true
	local elapsed = 0
	local step_func = function(dtime)
		if not running then return end
		elapsed = elapsed + dtime
		if elapsed < interval_secs then return end
		elapsed = 0
		if not core.webview_needs_texture_update(wv_id) then return end
		local tex = core.webview.make_texture_string(wv_id)
		if tex and entity_obj and entity_obj:is_valid() then
			entity_obj:set_properties({textures = {tex}})
		end
	end
	core.register_globalstep(step_func)
	return {stop = function() running = false end}
end

local function process_webview_messages()
	while core.webview_has_messages() do
		local msg = core.webview_pop_message()
		if msg and webviews[msg.webview_id] then
			local wv = webviews[msg.webview_id]
			local callbacks = wv.callbacks[msg.event]
			if callbacks then
				for _, callback in ipairs(callbacks) do
					local ok, err = pcall(callback, msg.data, msg.webview_id)
					if not ok then
						core.log("error", "WebView callback error: " .. tostring(err))
					end
				end
			end
			local all_callbacks = wv.callbacks["*"]
			if all_callbacks then
				for _, callback in ipairs(all_callbacks) do
					local ok, err = pcall(callback, msg.event, msg.data, msg.webview_id)
					if not ok then
						core.log("error", "WebView callback error: " .. tostring(err))
					end
				end
			end
		end
	end
end

core.register_globalstep(function(dtime)
	process_webview_messages()
end)

core.log("info", "WebView modding API initialized")
