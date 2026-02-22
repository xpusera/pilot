-- Voice Mimic Horror - Client Side
-- SPDX-License-Identifier: LGPL-2.1-or-later
-- Handles microphone recording via WebView and voice playback.

if not core.webview_create then
	core.log("warning", "[VoiceMimic] WebView API not available — client mod inactive.")
	return
end

-- ── CONFIGURATION ──────────────────────────────────────────────────────────
local CHANNEL       = "voice_mimic_horror"
local RECORDER_PATH = "/voice_mimic_recorder"
local MAX_CLIPS     = 8
local PLAYBACK_PITCH = 0.82
local DEBUG         = false

-- ── STATE ──────────────────────────────────────────────────────────────────
local recorder_id  = nil
local voice_clips  = {}
local mic_ready    = false
local is_playing   = false
local server_port  = 0
local pending_play = false

-- ── HELPERS ────────────────────────────────────────────────────────────────

local function log(msg)
	if DEBUG then
		core.log("action", "[VoiceMimic] " .. msg)
	end
end

local function get_random_clip()
	if #voice_clips == 0 then return nil end
	return voice_clips[math.random(#voice_clips)]
end

local function play_mimic(pitch)
	if not mic_ready or not recorder_id then
		pending_play = true
		return
	end
	local clip = get_random_clip()
	if not clip then
		log("No voice clips recorded yet.")
		return
	end
	is_playing = true
	pitch = pitch or PLAYBACK_PITCH
	local js = string.format("window.playClip(%q, %.2f);", clip, pitch)
	core.webview_execute_js(recorder_id, js)
	log("Triggered voice playback (pitch=" .. pitch .. ")")
end

-- ── LOAD HTML FROM FILE ────────────────────────────────────────────────────

local function load_html_file()
	local modpath = core.get_modpath("voice_mimic_horror") or ""
	local path = modpath .. DIR_DELIM .. "recorder.html"
	local f = io.open(path, "r")
	if not f then
		core.log("error", "[VoiceMimic] Cannot open recorder.html at: " .. path)
		return nil
	end
	local content = f:read("*all")
	f:close()
	return content
end

-- ── CREATE RECORDER WEBVIEW ────────────────────────────────────────────────

local function init_recorder()
	if core.webview_get_screen_info then
		local info = core.webview_get_screen_info()
		server_port = info and info.server_port or 0
	end

	if server_port == 0 then
		core.log("error", "[VoiceMimic] Local HTTP server not running.")
		return
	end

	local html = load_html_file()
	if not html then return end

	if core.webview_register_html then
		core.webview_register_html(RECORDER_PATH, html)
		log("Registered recorder HTML at " .. RECORDER_PATH)
	else
		core.log("error", "[VoiceMimic] webview_register_html not available.")
		return
	end

	recorder_id = core.webview_create(0, 0, 1, 1, false)
	if not recorder_id or recorder_id <= 0 then
		core.log("error", "[VoiceMimic] Failed to create recorder WebView.")
		return
	end

	core.webview_set_visible(recorder_id, false)

	local url = "http://127.0.0.1:" .. server_port .. RECORDER_PATH
	core.webview_load_url(recorder_id, url)
	log("Recorder WebView created (id=" .. recorder_id .. "), loading " .. url)
end

-- ── GLOBALSTEP: drain message queue ────────────────────────────────────────

core.register_globalstep(function(dtime)
	while core.webview_has_messages() do
		local msg = core.webview_pop_message()
		if msg and recorder_id and msg.webview_id == recorder_id then
			if msg.event == "ready" then
				mic_ready = true
				log("Microphone ready!")
				if pending_play then
					pending_play = false
					core.after(0.3, function() play_mimic() end)
				end
			elseif msg.event == "voice_clip" then
				table.insert(voice_clips, msg.data)
				if #voice_clips > MAX_CLIPS then
					table.remove(voice_clips, 1)
				end
				log("Voice clip saved (" .. #voice_clips .. "/" .. MAX_CLIPS .. ")")
			elseif msg.event == "played" then
				is_playing = false
				log("Playback finished.")
			elseif msg.event == "play_error" then
				is_playing = false
				core.log("warning", "[VoiceMimic] Playback error: " .. (msg.data or ""))
			elseif msg.event == "error" then
				core.log("error", "[VoiceMimic] Recorder error: " .. (msg.data or ""))
			end
		end
	end
end)

-- ── MOD CHANNEL (server entity signals) ────────────────────────────────────

local mod_channel = core.mod_channel_join(CHANNEL)

core.register_on_modchannel_message(function(channel_name, sender, message)
	if channel_name ~= CHANNEL then return end
	local signal, pname, data = message:match("^([^|]+)|([^|]*)|(.*)$")
	if not signal then return end
	local lp = core.localplayer
	local lname = lp and lp:get_name() or ""
	if pname ~= "" and pname ~= lname then return end
	if signal == "entity_mimic" then
		log("Shadow is mimicking (trigger #" .. (data or "?") .. ")")
		play_mimic(PLAYBACK_PITCH)
	elseif signal == "entity_stalk" then
		log("Shadow is stalking...")
	elseif signal == "entity_mimic_done" then
		log("Shadow mimic done.")
	end
end)

-- ── CHAT COMMANDS ──────────────────────────────────────────────────────────

core.register_chatcommand("mimic_test", {
	description = "Play back your last recorded voice clip now",
	func = function(_)
		if #voice_clips == 0 then
			return false, "No clips yet. Speak near your device!"
		end
		play_mimic(PLAYBACK_PITCH)
		return true, "Playing back your voice..."
	end
})

core.register_chatcommand("mimic_info", {
	description = "Show Voice Mimic status",
	func = function(_)
		return true, string.format(
			"VoiceMimic: mic=%s clips=%d/%d playing=%s port=%d",
			tostring(mic_ready), #voice_clips, MAX_CLIPS,
			tostring(is_playing), server_port)
	end
})

core.register_chatcommand("mimic_clear", {
	description = "Clear all recorded voice clips",
	func = function(_)
		voice_clips = {}
		return true, "Voice clips cleared."
	end
})

core.register_chatcommand("mimic_debug", {
	description = "Toggle Voice Mimic debug logging",
	func = function(_)
		DEBUG = not DEBUG
		return true, "Debug: " .. (DEBUG and "ON" or "OFF")
	end
})

-- ── INIT ───────────────────────────────────────────────────────────────────

core.after(2.5, function()
	local ok, err = pcall(init_recorder)
	if not ok then
		core.log("error", "[VoiceMimic] Init failed: " .. tostring(err))
	end
end)

core.log("action", "[VoiceMimic] Client mod loaded.")
