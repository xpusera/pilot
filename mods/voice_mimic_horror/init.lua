-- Voice Mimic Horror - Server Side
-- SPDX-License-Identifier: LGPL-2.1-or-later
-- A horror entity that stalks players and mimics their recorded voice.

local CHANNEL = "voice_mimic_horror"
local mod_channel = minetest.mod_channel_join(CHANNEL)

-- Entity states
local STATE_LURKING    = 1  -- Far away, barely visible, just watching
local STATE_STALKING   = 2  -- Getting closer, moves when not watched
local STATE_APPROACHING = 3 -- Very close, about to mimic
local STATE_MIMICKING  = 4  -- Playing back voice, frozen in place
local STATE_FLEEING    = 5  -- Player looked directly at it, teleporting away

-- Distances (in nodes)
local LURK_DIST_MIN   = 30
local LURK_DIST_MAX   = 55
local STALK_DIST_MIN  = 12
local STALK_DIST_MAX  = 28
local APPROACH_DIST   = 6
local LOOK_FOV_COS    = 0.93   -- ~21 deg cone for "looking at entity"

-- Timing (seconds)
local LURK_DURATION_MIN  = 40
local LURK_DURATION_MAX  = 80
local STALK_DURATION_MIN = 20
local STALK_DURATION_MAX = 45
local MIMIC_DURATION     = 6
local FLEE_DISTANCE      = 35  -- How far to teleport when seen

-- Sound and visual
local SHADOW_SPEED    = 1.8    -- Walk speed
local APPROACH_SPEED  = 2.8

-- Utility: find a valid spawn position near player
local function find_spawn_pos(player_pos, min_dist, max_dist)
	for _ = 1, 20 do
		local angle = math.random() * math.pi * 2
		local dist = min_dist + math.random() * (max_dist - min_dist)
		local x = player_pos.x + math.cos(angle) * dist
		local z = player_pos.z + math.sin(angle) * dist
		-- Try to find ground
		local surface = minetest.find_node_near(
			{x = x, y = player_pos.y + 5, z = z}, 10, {"group:solid"}, true)
		if surface then
			return {x = x, y = surface.y + 1.5, z = z}
		end
		-- Fallback: same Y as player
		local node_above = minetest.get_node({x = x, y = player_pos.y, z = z})
		if node_above.name == "air" then
			return {x = x, y = player_pos.y, z = z}
		end
	end
	-- Last resort: directly behind player
	local yaw = math.random() * math.pi * 2
	return {
		x = player_pos.x + math.cos(yaw) * max_dist,
		y = player_pos.y,
		z = player_pos.z + math.sin(yaw) * max_dist
	}
end

-- Utility: is player looking at a position?
local function player_is_looking_at(player, target_pos)
	local player_pos = player:get_pos()
	player_pos.y = player_pos.y + 1.6  -- eye height
	local look_dir = player:get_look_dir()
	local to_target = vector.subtract(target_pos, player_pos)
	local dist = vector.length(to_target)
	if dist < 0.5 then return true end
	local to_norm = vector.divide(to_target, dist)
	local dot = look_dir.x * to_norm.x + look_dir.y * to_norm.y + look_dir.z * to_norm.z
	return dot > LOOK_FOV_COS
end

-- Send a signal to the client mod via mod channel
local function signal_client(player_name, signal, data)
	mod_channel:send_all(signal .. "|" .. (player_name or "") .. "|" .. (data or ""))
end

minetest.register_entity("voice_mimic_horror:shadow", {
	initial_properties = {
		physical        = true,
		collide_with_objects = false,
		visual          = "upright_sprite",
		visual_size     = {x = 0.9, y = 2.1},
		textures        = {"voice_mimic_shadow.png"},
		collisionbox    = {-0.35, -0.01, -0.35, 0.35, 1.9, 0.35},
		is_visible      = true,
		makes_footstep_sound = false,
		static_save     = false,
		hp_max          = 1,
		damage_per_second = 0,
	},

	-- Per-entity state
	_state          = STATE_LURKING,
	_state_timer    = 0,
	_state_deadline = 0,
	_target_player  = nil,
	_last_seen_pos  = nil,
	_mimic_count    = 0,
	_flicker_timer  = 0,
	_stalk_timer    = 0,

	on_activate = function(self, staticdata, dtime_s)
		self.object:set_armor_groups({immortal = 1})
		self.object:set_velocity({x = 0, y = 0, z = 0})
		-- Pick lurk duration randomly
		self._state_deadline = LURK_DURATION_MIN + math.random() *
			(LURK_DURATION_MAX - LURK_DURATION_MIN)
		self._flicker_timer = 0
	end,

	on_step = function(self, dtime)
		local pos = self.object:get_pos()
		if not pos then return end

		-- Find nearest player if no target
		if not self._target_player or not self._target_player:is_player() then
			local players = minetest.get_connected_players()
			if #players == 0 then return end
			self._target_player = players[math.random(#players)]
		end
		local player = self._target_player
		if not player:is_player() then
			self._target_player = nil
			return
		end

		local pname  = player:get_player_name()
		local ppos   = player:get_pos()
		local dist   = vector.distance(pos, ppos)

		self._state_timer    = (self._state_timer or 0) + dtime
		self._flicker_timer  = (self._flicker_timer or 0) + dtime
		self._stalk_timer    = (self._stalk_timer or 0) + dtime

		-- Flicker / breathing visual effect
		if self._flicker_timer > 0.6 + math.random() * 0.4 then
			self._flicker_timer = 0
			-- Occasionally make entity slightly transparent or flicker
			local visible = math.random() > 0.12  -- 12% chance to briefly vanish
			self.object:set_properties({is_visible = visible})
		end

		-- Face player always
		local dir_to_player = vector.subtract(ppos, pos)
		dir_to_player.y = 0
		if vector.length(dir_to_player) > 0.1 then
			local yaw = math.atan2(dir_to_player.x, dir_to_player.z)
			self.object:set_yaw(yaw + math.pi)
		end

		-- Check if player is looking directly at entity
		local being_watched = player_is_looking_at(player, pos)

		-- ── STATE MACHINE ──────────────────────────────────────────────
		if self._state == STATE_LURKING then
			-- Stay far away, barely move, occasionally shift position
			self.object:set_velocity({x = 0, y = 0, z = 0})
			self.object:set_properties({is_visible = true})

			if being_watched and dist < LURK_DIST_MAX then
				-- Sway slightly but don't flee from this distance
				local sway = {
					x = math.sin(self._state_timer * 0.3) * 0.2,
					y = 0,
					z = math.cos(self._state_timer * 0.3) * 0.2
				}
				self.object:set_velocity(sway)
			end

			if self._state_timer >= self._state_deadline then
				-- Transition to stalking
				self._state = STATE_STALKING
				self._state_timer = 0
				self._state_deadline = STALK_DURATION_MIN + math.random() *
					(STALK_DURATION_MAX - STALK_DURATION_MIN)
				signal_client(pname, "entity_stalk", "start")
			end

		elseif self._state == STATE_STALKING then
			-- Move toward player when not watched, freeze when watched
			if being_watched then
				self.object:set_velocity({x = 0, y = 0, z = 0})
				-- Stare back - extra creepy
				-- If watched for >3 consecutive seconds → flee
				self._stalk_timer = self._stalk_timer + dtime
				if self._stalk_timer > 3.0 then
					self._state = STATE_FLEEING
					self._state_timer = 0
					return
				end
			else
				self._stalk_timer = 0
				-- Move toward player to stalk distance
				if dist > STALK_DIST_MAX then
					local dir = vector.normalize(vector.subtract(ppos, pos))
					self.object:set_velocity({
						x = dir.x * SHADOW_SPEED,
						y = 0,
						z = dir.z * SHADOW_SPEED
					})
				elseif dist < STALK_DIST_MIN then
					-- Too close, back off
					local dir = vector.normalize(vector.subtract(pos, ppos))
					self.object:set_velocity({
						x = dir.x * SHADOW_SPEED,
						y = 0,
						z = dir.z * SHADOW_SPEED
					})
				else
					self.object:set_velocity({x = 0, y = 0, z = 0})
				end
			end

			if self._state_timer >= self._state_deadline then
				self._state = STATE_APPROACHING
				self._state_timer = 0
				self._stalk_timer = 0
			end

		elseif self._state == STATE_APPROACHING then
			-- Rush toward player, then mimic
			if being_watched then
				-- Flee immediately if caught approaching
				self._state = STATE_FLEEING
				self._state_timer = 0
				return
			end

			if dist > APPROACH_DIST + 1 then
				local dir = vector.normalize(vector.subtract(ppos, pos))
				self.object:set_velocity({
					x = dir.x * APPROACH_SPEED,
					y = 0,
					z = dir.z * APPROACH_SPEED
				})
			else
				-- Close enough → freeze and mimic
				self.object:set_velocity({x = 0, y = 0, z = 0})
				self._state = STATE_MIMICKING
				self._state_timer = 0
				self._mimic_count = (self._mimic_count or 0) + 1
				signal_client(pname, "entity_mimic", tostring(self._mimic_count))
			end

		elseif self._state == STATE_MIMICKING then
			-- Frozen in place while voice plays
			self.object:set_velocity({x = 0, y = 0, z = 0})
			-- Tilt head slightly (pitch oscillation)
			local tilt = math.sin(self._state_timer * 3.0) * 0.25
			self.object:set_rotation({x = tilt, y = self.object:get_yaw(), z = 0})

			if self._state_timer >= MIMIC_DURATION then
				-- Done mimicking, flee
				self._state = STATE_FLEEING
				self._state_timer = 0
				signal_client(pname, "entity_mimic_done", "")
			end

		elseif self._state == STATE_FLEEING then
			-- Teleport to a new lurk position
			self.object:set_velocity({x = 0, y = 0, z = 0})
			local new_pos = find_spawn_pos(ppos, LURK_DIST_MIN, LURK_DIST_MAX)
			if new_pos then
				self.object:set_pos(new_pos)
			end
			self._state = STATE_LURKING
			self._state_timer = 0
			self._state_deadline = LURK_DURATION_MIN + math.random() *
				(LURK_DURATION_MAX - LURK_DURATION_MIN)
			self.object:set_rotation({x = 0, y = 0, z = 0})
		end
	end,

	on_punch = function(self, puncher, time_from_last_punch, tool_capabilities, dir)
		-- Cannot be killed, just flees
		self._state = STATE_FLEEING
		self._state_timer = 0
	end,

	on_death = function(self, killer)
		-- Respawn nearby after a delay
		local pos = self.object:get_pos()
		minetest.after(5, function()
			if pos then
				local players = minetest.get_connected_players()
				if #players > 0 then
					local p = players[1]
					local spawn = find_spawn_pos(p:get_pos(), LURK_DIST_MIN, LURK_DIST_MAX)
					if spawn then
						minetest.add_entity(spawn, "voice_mimic_horror:shadow")
					end
				end
			end
		end)
	end,
})

-- ── SPAWNER ────────────────────────────────────────────────────────────────

local shadows_by_player = {}

local function get_shadow_count()
	local count = 0
	for _, ref in pairs(minetest.luaentities) do
		if ref.name == "voice_mimic_horror:shadow" then
			count = count + 1
		end
	end
	return count
end

minetest.register_globalstep(function(dtime)
	for _, player in ipairs(minetest.get_connected_players()) do
		local pname = player:get_player_name()
		if not shadows_by_player[pname] then
			shadows_by_player[pname] = 0
		end
	end
end)

-- Auto-spawn one shadow per connected player (up to 1 per player, max 3 total)
minetest.after(8, function()
	local function spawn_check()
		local players = minetest.get_connected_players()
		if #players == 0 then
			minetest.after(15, spawn_check)
			return
		end
		local total = get_shadow_count()
		if total < math.min(#players, 3) then
			local player = players[math.random(#players)]
			local ppos   = player:get_pos()
			local spawn  = find_spawn_pos(ppos, LURK_DIST_MIN, LURK_DIST_MAX)
			if spawn then
				minetest.add_entity(spawn, "voice_mimic_horror:shadow")
				minetest.log("action", "[VoiceMimic] Spawned shadow near " ..
					player:get_player_name())
			end
		end
		minetest.after(30, spawn_check)
	end
	spawn_check()
end)

-- ── CHAT COMMANDS ──────────────────────────────────────────────────────────

minetest.register_chatcommand("shadow_spawn", {
	description = "Spawn a Voice Mimic shadow near you",
	privs = {},
	func = function(name, _)
		local player = minetest.get_player_by_name(name)
		if not player then return false, "Not found" end
		local ppos  = player:get_pos()
		local spawn = find_spawn_pos(ppos, LURK_DIST_MIN, LURK_DIST_MAX)
		if spawn then
			minetest.add_entity(spawn, "voice_mimic_horror:shadow")
			return true, "Shadow spawned. It watches..."
		end
		return false, "No suitable spawn found."
	end
})

minetest.register_chatcommand("shadow_clear", {
	description = "Remove all Voice Mimic shadows",
	privs = {},
	func = function(name, _)
		local count = 0
		for id, ref in pairs(minetest.luaentities) do
			if ref.name == "voice_mimic_horror:shadow" then
				ref.object:remove()
				count = count + 1
			end
		end
		return true, "Removed " .. count .. " shadow(s)."
	end
})

minetest.register_chatcommand("shadow_mimic", {
	description = "Force the nearest shadow to mimic now",
	privs = {},
	func = function(name, _)
		local player = minetest.get_player_by_name(name)
		if not player then return false, "Not found" end
		local ppos = player:get_pos()
		local nearest, nearest_dist = nil, math.huge
		for _, ref in pairs(minetest.luaentities) do
			if ref.name == "voice_mimic_horror:shadow" then
				local d = vector.distance(ref.object:get_pos(), ppos)
				if d < nearest_dist then
					nearest_dist = d
					nearest = ref
				end
			end
		end
		if nearest then
			nearest._state = STATE_APPROACHING
			nearest._state_timer = 0
			return true, "Shadow is approaching to mimic..."
		end
		return false, "No shadow found. Use /shadow_spawn first."
	end
})

minetest.log("action", "[VoiceMimic] Server mod loaded.")
