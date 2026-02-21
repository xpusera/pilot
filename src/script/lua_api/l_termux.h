// Luanti
// SPDX-License-Identifier: LGPL-2.1-or-later
// Copyright (C) 2024 Luanti Contributors

#pragma once

#include "lua_api/l_base.h"

class ModApiTermux : public ModApiBase
{
private:
	static int l_termux_is_installed(lua_State *L);
	static int l_termux_is_accessible(lua_State *L);
	static int l_termux_execute(lua_State *L);
	static int l_termux_execute_shell(lua_State *L);
	static int l_termux_execute_script(lua_State *L);
	static int l_termux_add_hook(lua_State *L);
	static int l_termux_remove_hook(lua_State *L);
	static int l_termux_send_input(lua_State *L);
	static int l_termux_has_results(lua_State *L);
	static int l_termux_pop_result(lua_State *L);
	static int l_termux_is_completed(lua_State *L);
	static int l_termux_has_triggered_hooks(lua_State *L);
	static int l_termux_pop_triggered_hook(lua_State *L);
	static int l_termux_get_paths(lua_State *L);

public:
	static void Initialize(lua_State *L, int top);
};
