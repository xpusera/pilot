-- Voice Mimic Horror - Client Side
-- SPDX-License-Identifier: LGPL-2.1-or-later
-- Handles microphone recording via WebView and voice playback.

if not core.webview_create then
	core.log("warning", "[VoiceMimic] WebView API not available — client mod inactive.")
	return
end

-- ── CONFIGURATION ──────────────────────────────────────────────────────────
local CHANNEL          = "voice_mimic_horror"
local RECORDER_PATH    = "/voice_mimic_recorder"
local MAX_CLIPS        = 8       -- max stored voice clips
local PLAYBACK_PITCH   = 0.82    -- slightly lower pitch for horror effect
local DEBUG            = false

-- ── STATE ──────────────────────────────────────────────────────────────────
local recorder_id   = nil   -- WebView id
local voice_clips   = {}    -- list of base64 audio strings
local mic_ready     = false
local is_playing    = false
local server_port   = 0
local pending_play  = false

-- ── HTML: Voice Activity Detection + Recorder ──────────────────────────────
-- Served via the local HTTP server at /voice_mimic_recorder
local RECORDER_HTML = [[<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<style>
  body { margin:0; background:transparent; overflow:hidden; }
  #status { position:fixed; top:2px; left:2px; font:10px monospace;
			color:#0f0; opacity:0.5; pointer-events:none; }
</style>
</head>
<body>
<div id="status">mic: init</div>
<script>
'use strict';

const VOL_THRESHOLD  = 0.018;   // RMS threshold to detect voice
const SILENCE_MS     = 1400;    // ms of silence before saving clip
const MIN_RECORD_MS  = 400;     // min clip length to save
const MAX_RECORD_MS  = 9000;    // max clip length

let mediaRecorder = null;
let analyser      = null;
let chunks        = [];
let isRecording   = false;
let recordStart   = 0;
let silenceStart  = 0;
const st = document.getElementById('status');

async function initMic() {
  try {
	const stream = await navigator.mediaDevices.getUserMedia({
	  audio: {
		echoCancellation: false,
		noiseSuppression: false,
		autoGainControl: false,
		sampleRate: 22050
	  },
	  video: false
	});

	const ctx = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 22050 });
	const src = ctx.createMediaStreamSource(stream);
	analyser = ctx.createAnalyser();
	analyser.fftSize = 256;
	analyser.smoothingTimeConstant = 0.85;
	src.connect(analyser);

	// Pick best supported format
	const mimeType = ['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg;codecs=opus']
	  .find(t => MediaRecorder.isTypeSupported(t)) || '';
	mediaRecorder = new MediaRecorder(stream, mimeType ? {mimeType} : {});
	mediaRecorder.ondataavailable = e => { if (e.data.size > 0) chunks.push(e.data); };
	mediaRecorder.onstop = saveClip;

	st.textContent = 'mic: ready';
	window.luanti.send('ready', 'mic_ok');
	requestAnimationFrame(vad);
  } catch (e) {
	st.textContent = 'mic: ERROR';
	window.luanti.send('error', String(e));
  }
}

function getRMS() {
  const data = new Uint8Array(analyser.frequencyBinCount);
  analyser.getByteFrequencyData(data);
  let sum = 0;
  for (let i = 0; i < data.length; i++) sum += (data[i] / 255) * (data[i] / 255);
  return Math.sqrt(sum / data.length);
}

function vad() {
  const vol = getRMS();
  const now = Date.now();

  if (vol > VOL_THRESHOLD) {
	silenceStart = 0;
	if (!isRecording) {
	  isRecording   = true;
	  recordStart   = now;
	  chunks        = [];
	  try { mediaRecorder.start(80); } catch(e) {}
	  st.textContent = 'mic: REC';
	} else if ((now - recordStart) > MAX_RECORD_MS) {
	  mediaRecorder.stop();
	  isRecording = false;
	  st.textContent = 'mic: saving(max)';
	}
  } else {
	if (isRecording) {
	  if (!silenceStart) {
		silenceStart = now;
	  } else if ((now - silenceStart) > SILENCE_MS &&
				 (now - recordStart)  > MIN_RECORD_MS) {
		mediaRecorder.stop();
		isRecording  = false;
		silenceStart = 0;
		st.textContent = 'mic: saving...';
	  }
	} else {
	  st.textContent = 'mic: listening';
	}
  }
  requestAnimationFrame(vad);
}

function saveClip() {
  if (!chunks.length) return;
  const mimeType = chunks[0].type || 'audio/webm';
  const blob = new Blob(chunks, {type: mimeType});
  chunks = [];
  const reader = new FileReader();
  reader.onload = () => {
	const b64 = reader.result.split(',')[1];
	window.luanti.send('voice_clip', b64);
	st.textContent = 'mic: clip saved';
  };
  reader.readAsDataURL(blob);
}

// Called from Lua: playClip(base64String, pitchMultiplier)
window.playClip = function(b64, pitch) {
  pitch = parseFloat(pitch) || 0.82;
  try {
	const bin = atob(b64);
	const arr = new Uint8Array(bin.length);
	for (let i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
	const blob = new Blob([arr], {type: 'audio/webm'});
	const url  = URL.createObjectURL(blob);
	const audio = new Audio(url);
	audio.playbackRate = pitch;
	audio.volume       = 1.0;
	audio.play()
	  .then(() => {
		st.textContent = 'PLAYING BACK...';
		audio.onended = () => {
		  URL.revokeObjectURL(url);
		  window.luanti.send('played', '');
		  st.textContent = 'mic: listening';
		};
	  })
	  .catch(err => {
		URL.revokeObjectURL(url);
		window.luanti.send('play_error', String(err));
	  });
  } catch(e) {
	window.luanti.send('play_error', String(e));
  }
};

window.addEventListener('load', initMic);
</script>
</body>
</html>]]

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
	-- Escape the base64 string safely for JS
	local js = string.format("window.playClip(%q, %.2f);", clip, pitch)
	core.webview_execute_js(recorder_id, js)
	log("Triggered voice playback (pitch=" .. pitch .. ")")
end

-- ── CREATE RECORDER WEBVIEW ────────────────────────────────────────────────

local function init_recorder()
	-- Get local server port
	if core.webview_get_screen_info then
		local info = core.webview_get_screen_info()
		server_port = info and info.server_port or 0
	end

	if server_port == 0 then
		core.log("error", "[VoiceMimic] Local HTTP server not running, cannot load recorder.")
		return
	end

	-- Register the recorder HTML with the local server
	if core.webview_register_html then
		core.webview_register_html(RECORDER_PATH, RECORDER_HTML)
		log("Registered recorder HTML at " .. RECORDER_PATH)
	else
		core.log("error", "[VoiceMimic] webview_register_html not available.")
		return
	end

	-- Create a tiny invisible WebView for audio (0x0 off-screen)
	recorder_id = core.webview_create(0, 0, 1, 1, false)
	if not recorder_id or recorder_id <= 0 then
		core.log("error", "[VoiceMimic] Failed to create recorder WebView.")
		return
	end

	-- Keep it invisible and off-screen
	core.webview_set_visible(recorder_id, false)

	-- Load the recorder HTML via local server
	local url = "http://127.0.0.1:" .. server_port .. RECORDER_PATH
	core.webview_load_url(recorder_id, url)
	log("Recorder WebView created (id=" .. recorder_id .. "), loading " .. url)
end

-- ── EVENT HANDLERS ─────────────────────────────────────────────────────────

-- Poll WebView messages every step (the core modding_api already calls
-- process_webview_messages, but we also handle events here for direct reaction)
local msg_poll_accum = 0

core.register_globalstep(function(dtime)
	-- Drain message queue
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
				-- Store clip (ring buffer)
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

-- ── MOD CHANNEL LISTENER (server entity signals) ───────────────────────────

local mod_channel = core.mod_channel_join(CHANNEL)

core.register_on_modchannel_message(function(channel_name, sender, message)
	if channel_name ~= CHANNEL then return end

	-- Message format: "signal|player_name|data"
	local signal, pname, data = message:match("^([^|]+)|([^|]*)|(.*)$")
	if not signal then return end

	local local_player = core.localplayer
	local local_name   = local_player and local_player:get_name() or ""

	-- Only act on signals for us (or broadcast)
	if pname ~= "" and pname ~= local_name then return end

	if signal == "entity_stalk" then
		-- Entity started stalking — eerie ambient cue
		log("Shadow is stalking...")

	elseif signal == "entity_mimic" then
		-- Entity is close and wants to mimic — play a clip!
		log("Shadow is mimicking (trigger #" .. (data or "?") .. ")")
		play_mimic(PLAYBACK_PITCH)

	elseif signal == "entity_mimic_done" then
		log("Shadow mimic done.")
	end
end)

-- ── CHAT COMMANDS (client-side) ────────────────────────────────────────────

core.register_chatcommand("mimic_test", {
	description = "Play back your last recorded voice clip now",
	func = function(_)
		if #voice_clips == 0 then
			return false, "No voice clips recorded yet. Speak near your device!"
		end
		play_mimic(PLAYBACK_PITCH)
		return true, "Playing back your voice..."
	end
})

core.register_chatcommand("mimic_info", {
	description = "Show Voice Mimic status",
	func = function(_)
		local info = string.format(
			"Voice Mimic: mic_ready=%s | clips=%d/%d | playing=%s | server=:%d",
			tostring(mic_ready), #voice_clips, MAX_CLIPS,
			tostring(is_playing), server_port
		)
		return true, info
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

-- Delay init to let the game fully load
core.after(2.5, function()
	local ok, err = pcall(init_recorder)
	if not ok then
		core.log("error", "[VoiceMimic] Init failed: " .. tostring(err))
	end
end)

core.log("action", "[VoiceMimic] Client mod loaded.")
