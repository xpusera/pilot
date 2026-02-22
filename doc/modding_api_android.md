# Luanti Android Modding API

This document describes the Android-specific APIs available to mods running on
the Luanti Android build (this fork). All APIs live under `core.webview`.

## WebView Embed

Mods can embed HTML/CSS/JS pages as:
- **Screen overlays** (rendered on top of the game UI)
- **Textures on entities** (bind a live WebView to an entity's skin)
- **Textures on players** (bind a live WebView to a player's skin)
- **Textures on nodes** (via an entity positioned at the node)

### Constants

```lua
core.webview.SIZE_FULLSCREEN  -- pass as width/height to fill the whole axis (-1)
```

### core.webview.create(def)

Create a new WebView. Returns an integer `id` on success, `nil` on failure.

```lua
local id = core.webview.create({
    x = 0,           -- pixels from left (overlay only, ignored in texture_mode)
    y = 0,           -- pixels from top
    width  = 800,    -- pixels, or core.webview.SIZE_FULLSCREEN (-1)
    height = 600,    -- pixels, or core.webview.SIZE_FULLSCREEN (-1)
    texture_mode = false,  -- true = render offscreen for texture capture
})
```

`texture_mode = true` renders the WebView off-screen. Use `capture_png()` /
`make_texture_string()` to read its pixels as a Luanti texture.

### Loading content

```lua
core.webview.load_html(id, html_string)
core.webview.load_file(id, absolute_path)
core.webview.load_url(id, url)   -- supports http://, https://, file://
```

`load_url` bypasses SSL certificate errors so mods can load any HTTPS site.
External resources (cross-origin) are also allowed.

### Position / size / visibility

```lua
core.webview.set_position(id, x, y)
core.webview.set_size(id, width, height)    -- -1 = fullscreen on that axis
core.webview.set_fullscreen(id)             -- shortcut: set_size(id, -1, -1)
core.webview.set_visible(id, bool)
```

### Background color

```lua
core.webview.set_background(id, r, g, b, a)
-- r,g,b,a each 0-255.  a=0 = fully transparent (WebView over game world)
```

### JavaScript execution

```lua
core.webview.execute_js(id, "document.body.style.background='red'")
```

### Closing / destroying

```lua
core.webview.destroy(id)   -- remove WebView completely
core.webview.close(id)     -- alias for destroy
```

### Receiving messages from JavaScript

JavaScript inside the WebView can call:
```js
LuantiBridge.sendToLua("myEvent", JSON.stringify({foo: "bar"}));
```

Register a Lua callback with:
```lua
core.webview.on_message(id, "myEvent", function(data, wv_id)
    minetest.chat_send_all("Got: " .. data)
end)
-- Use "*" as event_type to catch all events:
core.webview.on_message(id, "*", function(event, data, wv_id) end)
```

Other JS bridge calls available inside the WebView:
```js
LuantiBridge.log("debug message");
LuantiBridge.requestTextureUpdate();   // signal Lua that pixels changed
LuantiBridge.close();                  // destroy the WebView from JS
LuantiBridge.getLocalServerPort();     // port of the built-in CORS-free HTTP server
LuantiBridge.getLocalServerUrl();      // full base URL of the local server
```

### Hardware permissions (camera, microphone, location)

The manifest declares camera, microphone, location, and storage permissions.
When a WebView page calls `getUserMedia()` or the Geolocation API, Luanti
automatically bridges the request to Android's permission system. A dialog
appears on first use if the permission has not been granted yet.

No special Lua code is needed — just write standard Web APIs in your HTML:
```js
navigator.mediaDevices.getUserMedia({audio: true, video: true})
    .then(stream => { /* ... */ });
navigator.geolocation.getCurrentPosition(pos => { /* ... */ });
```

### Local content server (CORS-free hosting)

Register content so it can be loaded by any WebView without CORS issues:
```lua
core.webview.register_html("/mypage.html", "<h1>Hello</h1>")
core.webview.register_content("/data.json",
    minetest.serialize(my_table), "application/json")
core.webview.unregister_content("/mypage.html")

-- Then load it:
core.webview.load_url(id, "http://127.0.0.1:" ..
    core.webview_get_screen_info()[4] .. "/mypage.html")
-- Or from JS: fetch(LuantiBridge.getLocalServerUrl() + "/data.json")
```

The local server responds with full CORS headers including `OPTIONS` preflight
support, so all fetch/XHR calls work without restrictions.

### Texture capture

#### `core.webview.capture_png(id)` → `string|nil`

Capture the WebView content as **PNG bytes** (fast path, no intermediate ARGB).
Returns a Lua string containing the raw PNG file bytes, or `nil` if not ready.

```lua
local png_bytes = core.webview.capture_png(id)
```

#### `core.webview.make_texture_string(id)` → `string|nil`

Capture and encode in one call. Returns a `"[png:base64...]"` texture string
ready to pass directly to `set_properties`, `get_texture_pack`, etc.

```lua
local tex = core.webview.make_texture_string(id)
if tex then
    entity:set_properties({textures = {tex}})
end
```

#### `core.webview.capture_texture(id)` → `string|nil`

Legacy path — returns raw ARGB bytes. Use `capture_png` instead.

#### `core.webview.needs_texture_update(id)` → `bool`

Returns `true` if the WebView content has changed since the last capture.
Call JavaScript `LuantiBridge.requestTextureUpdate()` to set this flag manually.

#### `core.webview.get_texture_size(id)` → `width, height`

### Binding WebViews to entities / players

#### `core.webview.bind_to_entity(wv_id, entity_obj, interval_secs)`

Automatically update an entity's texture from a WebView every `interval_secs`
seconds (default 0.1). Returns a binding handle.

```lua
local id = core.webview.create({
    width = 512, height = 512, texture_mode = true
})
core.webview.load_url(id, "https://example.com")

local binding = core.webview.bind_to_entity(id, some_entity, 0.2)

-- Later, stop the auto-update:
binding:stop()
```

#### `core.webview.bind_to_player(wv_id, player_name, interval_secs)`

Same as `bind_to_entity` but targets a player by name.

```lua
local binding = core.webview.bind_to_player(id, "singleplayer", 0.5)
```

#### Entity "computer screen" example

```lua
minetest.register_entity("mymod:screen", {
    initial_properties = {
        visual = "upright_sprite",
        visual_size = {x = 2, y = 2},
        textures = {"blank.png"},
        static_save = false,
    },
    on_activate = function(self, staticdata)
        local id = core.webview.create({
            width = 512, height = 512, texture_mode = true
        })
        core.webview.load_url(id, "https://example.com")
        self._binding = core.webview.bind_to_entity(id, self.object, 0.1)
        self._wv_id = id
    end,
    on_deactivate = function(self)
        if self._binding then self._binding:stop() end
        if self._wv_id then core.webview.destroy(self._wv_id) end
    end,
})
```

### Utility

```lua
core.webview.get_info(id)         -- returns the webviews[id] table or nil
core.webview.get_all_ids()        -- returns array of all active WebView ids
```

### Screen info

```lua
-- Returns {screenWidth, screenHeight, densityDpi, localServerPort}
local info = core.webview_get_screen_info()
```

## Permissions granted at startup

On first game launch a dialog asks for:
- Camera
- Microphone (RECORD_AUDIO)
- Location (fine + coarse)
- Storage (external read/write, media)

These cover all hardware APIs accessible from WebView pages. If a permission
was denied, the WebView will automatically re-request it the next time a page
calls a restricted API.
