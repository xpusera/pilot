-- Voice Mimic Horror - Server Mod (single file, no client mod needed)
-- SPDX-License-Identifier: LGPL-2.1-or-later
-- A horror entity that stalks players, records their voice via WebView,
-- and plays it back at them in a distorted form.

if not core.webview_create then
	minetest.log("warning", "[VoiceMimic] WebView API unavailable (not Android). Mod disabled.")
	return
end

-- ── CONFIG ─────────────────────────────────────────────────────────────────

local RECORDER_PATH  = "/voice_mimic_recorder"
local MAX_CLIPS      = 8
local PLAYBACK_PITCH = 0.82

local STATE_LURKING    = 1
local STATE_STALKING   = 2
local STATE_APPROACHING = 3
local STATE_MIMICKING  = 4
local STATE_FLEEING    = 5

local LURK_DIST_MIN  = 30
local LURK_DIST_MAX  = 55
local STALK_DIST_MIN = 12
local STALK_DIST_MAX = 28
local APPROACH_DIST  = 6
local LOOK_FOV_COS   = 0.93
local FLEE_DIST      = 35
local SHADOW_SPEED   = 1.8
local APPROACH_SPEED = 2.8
local LURK_TIME_MIN  = 40
local LURK_TIME_MAX  = 80
local STALK_TIME_MIN = 20
local STALK_TIME_MAX = 45
local MIMIC_DURATION = 6

-- ── VOICE RECORDER WEBVIEW ─────────────────────────────────────────────────

local recorder_id  = nil
local voice_clips  = {}
local mic_ready    = false
local server_port  = 0
local pending_play = false

local RECORDER_HTML_PATH = minetest.get_modpath("voice_mimic_horror") ..
	DIR_DELIM .. "recorder.html"

local function load_recorder_html()
	local f = io.open(RECORDER_HTML_PATH, "r")
	if not f then
		minetest.log("error", "[VoiceMimic] Cannot open recorder.html: " .. RECORDER_HTML_PATH)
		return nil
	end
	local html = f:read("*all")
	f:close()
	return html
end

local function play_mimic_for(player, pitch)
	if not mic_ready or not recorder_id or #voice_clips == 0 then
		if not mic_ready then pending_play = true end
		return
	end
	local clip = voice_clips[math.random(#voice_clips)]
	local js = string.format("window.playClip(%q, %.2f);", clip, pitch or PLAYBACK_PITCH)
	core.webview_execute_js(recorder_id, js)
end

local function init_recorder()
	if core.webview_get_screen_info then
		local info = core.webview_get_screen_info()
		server_port = info and info.server_port or 0
	end
	if server_port == 0 then
		minetest.log("error", "[VoiceMimic] Local HTTP server not available.")
		return
	end

	local html = load_recorder_html()
	if not html then return end

	if core.webview_register_html then
		core.webview_register_html(RECORDER_PATH, html)
	else
		minetest.log("error", "[VoiceMimic] webview_register_html missing.")
		return
	end

	recorder_id = core.webview_create(0, 0, 1, 1, false)
	if not recorder_id or recorder_id <= 0 then
		minetest.log("error", "[VoiceMimic] Failed to create recorder WebView.")
		return
	end

	core.webview_set_visible(recorder_id, false)
	local url = "http://127.0.0.1:" .. server_port .. RECORDER_PATH
	core.webview_load_url(recorder_id, url)
	minetest.log("action", "[VoiceMimic] Recorder WebView loaded from " .. url)
end

-- ── MESSAGE POLLING ────────────────────────────────────────────────────────

minetest.register_globalstep(function(dtime)
	if not recorder_id then return end
	while core.webview_has_messages() do
		local msg = core.webview_pop_message()
		if msg and msg.webview_id == recorder_id then
			if msg.event == "ready" then
				mic_ready = true
				minetest.log("action", "[VoiceMimic] Microphone ready!")
				if pending_play then
					pending_play = false
					minetest.after(0.3, function()
						local players = minetest.get_connected_players()
						if #players > 0 then
							play_mimic_for(players[1])
						end
					end)
				end
			elseif msg.event == "voice_clip" then
				table.insert(voice_clips, msg.data)
				if #voice_clips > MAX_CLIPS then
					table.remove(voice_clips, 1)
				end
				minetest.log("action", "[VoiceMimic] Clip saved (" ..
					#voice_clips .. "/" .. MAX_CLIPS .. ")")
			elseif msg.event == "played" then
				minetest.log("action", "[VoiceMimic] Playback done.")
			elseif msg.event == "play_error" then
				minetest.log("warning", "[VoiceMimic] Playback error: " .. (msg.data or ""))
			elseif msg.event == "error" then
				minetest.log("error", "[VoiceMimic] Recorder error: " .. (msg.data or ""))
			end
		end
	end
end)

-- ── UTILITY ────────────────────────────────────────────────────────────────

local function find_spawn_pos(player_pos, min_dist, max_dist)
	for _ = 1, 20 do
		local angle = math.random() * math.pi * 2
		local dist  = min_dist + math.random() * (max_dist - min_dist)
		local x = player_pos.x + math.cos(angle) * dist
		local z = player_pos.z + math.sin(angle) * dist
		local surface = minetest.find_node_near(
			{x = x, y = player_pos.y + 5, z = z}, 10, {"group:solid"}, true)
		if surface then
			return {x = x, y = surface.y + 1.5, z = z}
		end
		local node = minetest.get_node({x = x, y = player_pos.y, z = z})
		if node.name == "air" then
			return {x = x, y = player_pos.y, z = z}
		end
	end
	local yaw = math.random() * math.pi * 2
	return {
		x = player_pos.x + math.cos(yaw) * max_dist,
		y = player_pos.y,
		z = player_pos.z + math.sin(yaw) * max_dist,
	}
end

local function player_looking_at(player, target_pos)
	local eye = player:get_pos()
	eye.y = eye.y + 1.6
	local dir = player:get_look_dir()
	local to  = vector.subtract(target_pos, eye)
	local len = vector.length(to)
	if len < 0.5 then return true end
	to = vector.divide(to, len)
	return (dir.x * to.x + dir.y * to.y + dir.z * to.z) > LOOK_FOV_COS
end

-- ── ENTITY ─────────────────────────────────────────────────────────────────

minetest.register_entity("voice_mimic_horror:shadow", {
	initial_properties = {
		physical             = true,
		collide_with_objects = false,
		visual               = "upright_sprite",
		visual_size          = {x = 0.9, y = 2.1},
		textures             = {"voice_mimic_shadow.png"},
		collisionbox         = {-0.35, -0.01, -0.35, 0.35, 1.9, 0.35},
		is_visible           = true,
		makes_footstep_sound = false,
		static_save          = false,
		hp_max               = 1,
	},

	_state         = STATE_LURKING,
	_state_timer   = 0,
	_deadline      = 0,
	_target        = nil,
	_flicker_timer = 0,
	_stare_timer   = 0,
	_mimic_count   = 0,

	on_activate = function(self, staticdata, dtime_s)
		self.object:set_armor_groups({immortal = 1})
		self.object:set_velocity({x = 0, y = 0, z = 0})
		self._deadline = LURK_TIME_MIN + math.random() * (LURK_TIME_MAX - LURK_TIME_MIN)
	end,

	on_step = function(self, dtime)
		local pos = self.object:get_pos()
		if not pos then return end

		-- Acquire target player
		if not self._target or not self._target:is_player() then
			local players = minetest.get_connected_players()
			if #players == 0 then return end
			self._target = players[math.random(#players)]
		end
		local player = self._target
		if not player:is_player() then self._target = nil; return end

		local ppos  = player:get_pos()
		local dist  = vector.distance(pos, ppos)

		self._state_timer  = (self._state_timer or 0) + dtime
		self._flicker_timer = (self._flicker_timer or 0) + dtime

		-- Flicker effect every ~0.6–1.0s
		if self._flicker_timer > 0.6 + math.random() * 0.4 then
			self._flicker_timer = 0
			self.object:set_properties({is_visible = math.random() > 0.10})
		end

		-- Always face the player
		local dp = vector.subtract(ppos, pos)
		dp.y = 0
		if vector.length(dp) > 0.1 then
			self.object:set_yaw(math.atan2(dp.x, dp.z) + math.pi)
		end

		local watched = player_looking_at(player, pos)

		-- ── STATE MACHINE ─────────────────────────────────────────────

		if self._state == STATE_LURKING then
			self.object:set_velocity({x = 0, y = 0, z = 0})
			if self._state_timer >= self._deadline then
				self._state       = STATE_STALKING
				self._state_timer = 0
				self._deadline    = STALK_TIME_MIN + math.random() * (STALK_TIME_MAX - STALK_TIME_MIN)
			end

		elseif self._state == STATE_STALKING then
			if watched then
				self.object:set_velocity({x = 0, y = 0, z = 0})
				self._stare_timer = (self._stare_timer or 0) + dtime
				if self._stare_timer > 3.0 then
					self._state = STATE_FLEEING
					self._state_timer = 0
					self._stare_timer = 0
				end
			else
				self._stare_timer = 0
				if dist > STALK_DIST_MAX then
					local dir = vector.normalize(vector.subtract(ppos, pos))
					self.object:set_velocity({x = dir.x * SHADOW_SPEED, y = 0, z = dir.z * SHADOW_SPEED})
				elseif dist < STALK_DIST_MIN then
					local dir = vector.normalize(vector.subtract(pos, ppos))
					self.object:set_velocity({x = dir.x * SHADOW_SPEED, y = 0, z = dir.z * SHADOW_SPEED})
				else
					self.object:set_velocity({x = 0, y = 0, z = 0})
				end
			end
			if self._state_timer >= self._deadline then
				self._state       = STATE_APPROACHING
				self._state_timer = 0
				self._stare_timer = 0
			end

		elseif self._state == STATE_APPROACHING then
			if watched then
				self._state = STATE_FLEEING
				self._state_timer = 0
				return
			end
			if dist > APPROACH_DIST + 1 then
				local dir = vector.normalize(vector.subtract(ppos, pos))
				self.object:set_velocity({x = dir.x * APPROACH_SPEED, y = 0, z = dir.z * APPROACH_SPEED})
			else
				self.object:set_velocity({x = 0, y = 0, z = 0})
				self._state       = STATE_MIMICKING
				self._state_timer = 0
				self._mimic_count = (self._mimic_count or 0) + 1
				play_mimic_for(player, PLAYBACK_PITCH)
			end

		elseif self._state == STATE_MIMICKING then
			self.object:set_velocity({x = 0, y = 0, z = 0})
			-- Creepy head-tilt during playback
			local tilt = math.sin(self._state_timer * 3.0) * 0.25
			self.object:set_rotation({x = tilt, y = self.object:get_yaw(), z = 0})
			if self._state_timer >= MIMIC_DURATION then
				self._state = STATE_FLEEING
				self._state_timer = 0
				self.object:set_rotation({x = 0, y = 0, z = 0})
			end

		elseif self._state == STATE_FLEEING then
			self.object:set_velocity({x = 0, y = 0, z = 0})
			local new_pos = find_spawn_pos(ppos, LURK_DIST_MIN, LURK_DIST_MAX)
			if new_pos then self.object:set_pos(new_pos) end
			self._state       = STATE_LURKING
			self._state_timer = 0
			self._deadline    = LURK_TIME_MIN + math.random() * (LURK_TIME_MAX - LURK_TIME_MIN)
		end
	end,

	on_punch = function(self, puncher, time_from_last_punch, tool_capabilities, dir)
		self._state = STATE_FLEEING
		self._state_timer = 0
	end,

	on_death = function(self, killer)
		local pos = self.object:get_pos()
		minetest.after(5, function()
			local players = minetest.get_connected_players()
			if #players > 0 then
				local spawn = find_spawn_pos(players[1]:get_pos(), LURK_DIST_MIN, LURK_DIST_MAX)
				if spawn then minetest.add_entity(spawn, "voice_mimic_horror:shadow") end
			end
		end)
	end,
})

-- ── SPAWNER ────────────────────────────────────────────────────────────────

local function count_shadows()
	local n = 0
	for _, e in pairs(minetest.luaentities) do
		if e.name == "voice_mimic_horror:shadow" then n = n + 1 end
	end
	return n
end

minetest.after(8, function spawn_check()
	local players = minetest.get_connected_players()
	if #players > 0 and count_shadows() < math.min(#players, 3) then
		local p     = players[math.random(#players)]
		local spawn = find_spawn_pos(p:get_pos(), LURK_DIST_MIN, LURK_DIST_MAX)
		if spawn then
			minetest.add_entity(spawn, "voice_mimic_horror:shadow")
			minetest.log("action", "[VoiceMimic] Shadow spawned near " .. p:get_player_name())
		end
	end
	minetest.after(30, spawn_check)
end)

-- ── CHAT COMMANDS ──────────────────────────────────────────────────────────

minetest.register_chatcommand("shadow_spawn", {
	description = "Spawn a shadow near you",
	func = function(name, _)
		local p = minetest.get_player_by_name(name)
		if not p then return false, "Player not found." end
		local spawn = find_spawn_pos(p:get_pos(), LURK_DIST_MIN, LURK_DIST_MAX)
		if spawn then
			minetest.add_entity(spawn, "voice_mimic_horror:shadow")
			return true, "Shadow spawned. It watches..."
		end
		return false, "No spawn position found."
	end,
})

minetest.register_chatcommand("shadow_clear", {
	description = "Remove all shadows",
	func = function(_, _)
		local n = 0
		for _, e in pairs(minetest.luaentities) do
			if e.name == "voice_mimic_horror:shadow" then
				e.object:remove(); n = n + 1
			end
		end
		return true, "Removed " .. n .. " shadow(s)."
	end,
})

minetest.register_chatcommand("shadow_mimic", {
	description = "Force nearest shadow to approach and mimic now",
	func = function(name, _)
		local p = minetest.get_player_by_name(name)
		if not p then return false, "Player not found." end
		local ppos = p:get_pos()
		local best, best_d = nil, math.huge
		for _, e in pairs(minetest.luaentities) do
			if e.name == "voice_mimic_horror:shadow" then
				local d = vector.distance(e.object:get_pos(), ppos)
				if d < best_d then best_d = d; best = e end
			end
		end
		if best then
			best._state = STATE_APPROACHING
			best._state_timer = 0
			return true, "Shadow is approaching..."
		end
		return false, "No shadow nearby. Use /shadow_spawn first."
	end,
})

minetest.register_chatcommand("mimic_test", {
	description = "Manually trigger voice playback",
	func = function(name, _)
		local p = minetest.get_player_by_name(name)
		if #voice_clips == 0 then
			return false, "No clips yet. Speak near your device first!"
		end
		play_mimic_for(p, PLAYBACK_PITCH)
		return true, "Playing back your voice..."
	end,
})

minetest.register_chatcommand("mimic_info", {
	description = "Show voice mimic status",
	func = function(_, _)
		return true, string.format(
			"VoiceMimic: mic=%s clips=%d/%d server_port=%d shadows=%d",
			tostring(mic_ready), #voice_clips, MAX_CLIPS,
			server_port, count_shadows())
	end,
})

-- ── INIT ───────────────────────────────────────────────────────────────────

minetest.after(2, function()
	local ok, err = pcall(init_recorder)
	if not ok then
		minetest.log("error", "[VoiceMimic] Init failed: " .. tostring(err))
	end
end)

minetest.log("action", "[VoiceMimic] Server mod loaded.")
