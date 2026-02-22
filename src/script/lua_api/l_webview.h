// Luanti
// SPDX-License-Identifier: LGPL-2.1-or-later
// Copyright (C) 2024 Luanti Contributors

#pragma once

#include "lua_api/l_base.h"

class ModApiWebView : public ModApiBase
{
private:
	static int l_webview_create(lua_State *L);
	static int l_webview_load_html(lua_State *L);
	static int l_webview_load_file(lua_State *L);
	static int l_webview_load_url(lua_State *L);
	static int l_webview_execute_js(lua_State *L);
	static int l_webview_set_position(lua_State *L);
	static int l_webview_set_size(lua_State *L);
	static int l_webview_set_visible(lua_State *L);
	static int l_webview_destroy(lua_State *L);
	static int l_webview_close(lua_State *L);
	static int l_webview_set_fullscreen(lua_State *L);
	static int l_webview_get_screen_info(lua_State *L);
	static int l_webview_register_content(lua_State *L);
	static int l_webview_register_html(lua_State *L);
	static int l_webview_unregister_content(lua_State *L);
	static int l_webview_capture_texture(lua_State *L);
	static int l_webview_capture_png(lua_State *L);
	static int l_webview_set_background_color(lua_State *L);
	static int l_webview_needs_texture_update(lua_State *L);
	static int l_webview_has_messages(lua_State *L);
	static int l_webview_pop_message(lua_State *L);
	static int l_webview_get_ids(lua_State *L);
	static int l_webview_get_texture_size(lua_State *L);

public:
	static void Initialize(lua_State *L, int top);
};
