-- Luanti WebView and Termux Modding API (Server-Side)
-- SPDX-License-Identifier: LGPL-2.1-or-later

-- Check if we're on Android (APIs will be nil if not)
local is_android = core.webview_create ~= nil

if not is_android then
    core.log("info", "[modding_api] Not on Android, WebView/Termux APIs disabled")
    core.webview = {
        create = function() return nil end,
        load_html = function() end,
        load_file = function() end,
        load_url = function() end,
        execute_js = function() end,
        set_position = function() end,
        set_size = function() end,
        set_visible = function() end,
        destroy = function() end,
        capture_texture = function() return nil end,
        needs_texture_update = function() return false end,
        get_texture_size = function() return 0, 0 end,
        on_message = function() end,
        get_all_ids = function() return {} end,
    }
    core.termux = {
        is_available = function() return false end,
        is_installed = function() return false end,
        execute = function() return -1 end,
        execute_shell = function() return -1 end,
        execute_script = function() return -1 end,
        add_output_hook = function() return -1 end,
        remove_output_hook = function() end,
        send_input = function() return -1 end,
        is_completed = function() return false end,
        get_paths = function() return {} end,
    }
    return
end

-- WebView API
core.webview = {}

local webviews = {}

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
    core.webview_load_file(id, path)
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

function core.webview.capture_texture(id)
    if not webviews[id] then return nil end
    return core.webview_capture_texture(id)
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

local function process_webview_messages()
    if not core.webview_has_messages then return end
    while core.webview_has_messages() do
        local msg = core.webview_pop_message()
        if msg and webviews[msg.webview_id] then
            local wv = webviews[msg.webview_id]
            local callbacks = wv.callbacks[msg.event]
            if callbacks then
                for _, callback in ipairs(callbacks) do
                    local success, err = pcall(callback, msg.data, msg.webview_id)
                    if not success then
                        core.log("error", "WebView callback error: " .. tostring(err))
                    end
                end
            end
            local all_callbacks = wv.callbacks["*"]
            if all_callbacks then
                for _, callback in ipairs(all_callbacks) do
                    local success, err = pcall(callback, msg.event, msg.data, msg.webview_id)
                    if not success then
                        core.log("error", "WebView callback error: " .. tostring(err))
                    end
                end
            end
        end
    end
end

-- Termux API
core.termux = {}

local pending_commands = {}
local output_hooks = {}

function core.termux.is_available()
    if not core.termux_is_installed then return false end
    return core.termux_is_installed() and core.termux_is_accessible()
end

function core.termux.is_installed()
    if not core.termux_is_installed then return false end
    return core.termux_is_installed()
end

function core.termux.execute(def)
    local executable = def.executable or def[1]
    local args = def.args or {}
    local workdir = def.workdir
    local background = def.background
    if background == nil then background = true end
    local stdin = def.stdin
    local callback = def.callback
    
    local cmd_id = core.termux_execute(executable, args, workdir, background, stdin)
    if cmd_id and cmd_id > 0 and callback then
        pending_commands[cmd_id] = callback
    end
    return cmd_id
end

function core.termux.execute_shell(command, background, callback)
    if background == nil then background = true end
    local cmd_id = core.termux_execute_shell(command, background)
    if cmd_id and cmd_id > 0 and callback then
        pending_commands[cmd_id] = callback
    end
    return cmd_id
end

function core.termux.execute_script(script, background, callback)
    if background == nil then background = true end
    local cmd_id = core.termux_execute_script(script, background)
    if cmd_id and cmd_id > 0 and callback then
        pending_commands[cmd_id] = callback
    end
    return cmd_id
end

function core.termux.add_output_hook(pattern, is_regex, callback)
    local hook_id = core.termux_add_hook(pattern, is_regex or false)
    if hook_id and hook_id > 0 and callback then
        output_hooks[hook_id] = callback
    end
    return hook_id
end

function core.termux.remove_output_hook(hook_id)
    output_hooks[hook_id] = nil
    core.termux_remove_hook(hook_id)
end

function core.termux.send_input(input)
    return core.termux_send_input(input)
end

function core.termux.is_completed(cmd_id)
    return core.termux_is_completed(cmd_id)
end

function core.termux.get_paths()
    return core.termux_get_paths()
end

local function process_termux_results()
    if not core.termux_has_results then return end
    while core.termux_has_results() do
        local result = core.termux_pop_result()
        if result then
            local callback = pending_commands[result.command_id]
            if callback then
                local success, err = pcall(callback, result)
                if not success then
                    core.log("error", "Termux callback error: " .. tostring(err))
                end
                pending_commands[result.command_id] = nil
            end
        end
    end
    
    if not core.termux_has_triggered_hooks then return end
    while core.termux_has_triggered_hooks() do
        local hook = core.termux_pop_triggered_hook()
        if hook then
            local callback = output_hooks[hook.hook_id]
            if callback then
                local success, err = pcall(callback, hook)
                if not success then
                    core.log("error", "Termux hook callback error: " .. tostring(err))
                end
            end
        end
    end
end

-- Register globalstep for processing messages
core.register_globalstep(function(dtime)
    process_webview_messages()
    process_termux_results()
end)

core.log("info", "[modding_api] WebView and Termux APIs initialized (Android)")
