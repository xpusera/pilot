// Luanti
// SPDX-License-Identifier: LGPL-2.1-or-later
// Copyright (C) 2024 Luanti Contributors

#include "lua_api/l_termux.h"
#include "lua_api/l_internal.h"
#include "common/c_converter.h"
#include "log.h"

#ifdef __ANDROID__
#include "porting_android.h"
#include <jni.h>
#include <SDL.h>
#endif

#ifdef __ANDROID__
namespace {
	std::string readJavaString(JNIEnv *env, jstring j_str) {
		if (j_str == nullptr) return "";
		const char *c_str = env->GetStringUTFChars(j_str, nullptr);
		std::string str(c_str);
		env->ReleaseStringUTFChars(j_str, c_str);
		return str;
	}
}
#endif

int ModApiTermux::l_termux_is_installed(lua_State *L)
{
	NO_MAP_LOCK_REQUIRED;
#ifdef __ANDROID__
	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jobject activity = (jobject)SDL_AndroidGetActivity();
	jclass cls = env->GetObjectClass(activity);

	jmethodID method = env->GetMethodID(cls, "isTermuxInstalled", "()Z");
	if (method == nullptr) {
		lua_pushboolean(L, false);
		return 1;
	}

	jboolean result = env->CallBooleanMethod(activity, method);
	lua_pushboolean(L, result);
	return 1;
#else
	lua_pushboolean(L, false);
	return 1;
#endif
}

int ModApiTermux::l_termux_is_accessible(lua_State *L)
{
	NO_MAP_LOCK_REQUIRED;
#ifdef __ANDROID__
	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jobject activity = (jobject)SDL_AndroidGetActivity();
	jclass cls = env->GetObjectClass(activity);

	jmethodID method = env->GetMethodID(cls, "isTermuxAccessible", "()Z");
	if (method == nullptr) {
		lua_pushboolean(L, false);
		return 1;
	}

	jboolean result = env->CallBooleanMethod(activity, method);
	lua_pushboolean(L, result);
	return 1;
#else
	lua_pushboolean(L, false);
	return 1;
#endif
}

int ModApiTermux::l_termux_execute(lua_State *L)
{
	NO_MAP_LOCK_REQUIRED;
#ifdef __ANDROID__
	std::string executable = luaL_checkstring(L, 1);
	
	std::vector<std::string> args;
	if (lua_istable(L, 2)) {
		lua_pushnil(L);
		while (lua_next(L, 2) != 0) {
			if (lua_isstring(L, -1)) {
				args.push_back(lua_tostring(L, -1));
			}
			lua_pop(L, 1);
		}
	}
	
	std::string workDir = "";
	if (lua_isstring(L, 3)) {
		workDir = lua_tostring(L, 3);
	}
	
	bool background = true;
	if (lua_isboolean(L, 4)) {
		background = lua_toboolean(L, 4);
	}
	
	std::string stdin_str = "";
	if (lua_isstring(L, 5)) {
		stdin_str = lua_tostring(L, 5);
	}

	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jobject activity = (jobject)SDL_AndroidGetActivity();
	jclass cls = env->GetObjectClass(activity);

	jmethodID method = env->GetMethodID(cls, "termuxExecuteCommand", 
		"(Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;ZLjava/lang/String;)I");
	if (method == nullptr) {
		lua_pushinteger(L, -1);
		return 1;
	}

	jstring jexec = env->NewStringUTF(executable.c_str());
	
	jclass stringClass = env->FindClass("java/lang/String");
	jobjectArray jargs = env->NewObjectArray(args.size(), stringClass, nullptr);
	for (size_t i = 0; i < args.size(); i++) {
		jstring jarg = env->NewStringUTF(args[i].c_str());
		env->SetObjectArrayElement(jargs, i, jarg);
		env->DeleteLocalRef(jarg);
	}
	
	jstring jworkDir = env->NewStringUTF(workDir.c_str());
	jstring jstdin = env->NewStringUTF(stdin_str.c_str());

	jint commandId = env->CallIntMethod(activity, method, jexec, jargs, jworkDir, background, jstdin);

	env->DeleteLocalRef(jexec);
	env->DeleteLocalRef(jargs);
	env->DeleteLocalRef(jworkDir);
	env->DeleteLocalRef(jstdin);

	lua_pushinteger(L, commandId);
	return 1;
#else
	lua_pushinteger(L, -1);
	return 1;
#endif
}

int ModApiTermux::l_termux_execute_shell(lua_State *L)
{
	NO_MAP_LOCK_REQUIRED;
#ifdef __ANDROID__
	std::string command = luaL_checkstring(L, 1);
	bool background = true;
	if (lua_isboolean(L, 2)) {
		background = lua_toboolean(L, 2);
	}

	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jobject activity = (jobject)SDL_AndroidGetActivity();
	jclass cls = env->GetObjectClass(activity);

	jmethodID method = env->GetMethodID(cls, "termuxExecuteShell", "(Ljava/lang/String;Z)I");
	if (method == nullptr) {
		lua_pushinteger(L, -1);
		return 1;
	}

	jstring jcmd = env->NewStringUTF(command.c_str());
	jint commandId = env->CallIntMethod(activity, method, jcmd, background);
	env->DeleteLocalRef(jcmd);

	lua_pushinteger(L, commandId);
	return 1;
#else
	lua_pushinteger(L, -1);
	return 1;
#endif
}

int ModApiTermux::l_termux_execute_script(lua_State *L)
{
	NO_MAP_LOCK_REQUIRED;
#ifdef __ANDROID__
	std::string script = luaL_checkstring(L, 1);
	bool background = true;
	if (lua_isboolean(L, 2)) {
		background = lua_toboolean(L, 2);
	}

	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jobject activity = (jobject)SDL_AndroidGetActivity();
	jclass cls = env->GetObjectClass(activity);

	jmethodID method = env->GetMethodID(cls, "termuxExecuteScript", "(Ljava/lang/String;Z)I");
	if (method == nullptr) {
		lua_pushinteger(L, -1);
		return 1;
	}

	jstring jscript = env->NewStringUTF(script.c_str());
	jint commandId = env->CallIntMethod(activity, method, jscript, background);
	env->DeleteLocalRef(jscript);

	lua_pushinteger(L, commandId);
	return 1;
#else
	lua_pushinteger(L, -1);
	return 1;
#endif
}

int ModApiTermux::l_termux_add_hook(lua_State *L)
{
	NO_MAP_LOCK_REQUIRED;
#ifdef __ANDROID__
	std::string pattern = luaL_checkstring(L, 1);
	bool isRegex = false;
	if (lua_isboolean(L, 2)) {
		isRegex = lua_toboolean(L, 2);
	}

	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jobject activity = (jobject)SDL_AndroidGetActivity();
	jclass cls = env->GetObjectClass(activity);

	jmethodID method = env->GetMethodID(cls, "termuxAddHook", "(Ljava/lang/String;Z)I");
	if (method == nullptr) {
		lua_pushinteger(L, -1);
		return 1;
	}

	jstring jpattern = env->NewStringUTF(pattern.c_str());
	jint hookId = env->CallIntMethod(activity, method, jpattern, isRegex);
	env->DeleteLocalRef(jpattern);

	lua_pushinteger(L, hookId);
	return 1;
#else
	lua_pushinteger(L, -1);
	return 1;
#endif
}

int ModApiTermux::l_termux_remove_hook(lua_State *L)
{
	NO_MAP_LOCK_REQUIRED;
#ifdef __ANDROID__
	int hookId = luaL_checkinteger(L, 1);

	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jobject activity = (jobject)SDL_AndroidGetActivity();
	jclass cls = env->GetObjectClass(activity);

	jmethodID method = env->GetMethodID(cls, "termuxRemoveHook", "(I)V");
	if (method == nullptr) return 0;

	env->CallVoidMethod(activity, method, hookId);
#endif
	return 0;
}

int ModApiTermux::l_termux_send_input(lua_State *L)
{
	NO_MAP_LOCK_REQUIRED;
#ifdef __ANDROID__
	std::string input = luaL_checkstring(L, 1);

	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jobject activity = (jobject)SDL_AndroidGetActivity();
	jclass cls = env->GetObjectClass(activity);

	jmethodID method = env->GetMethodID(cls, "termuxSendInput", "(Ljava/lang/String;)I");
	if (method == nullptr) {
		lua_pushinteger(L, -1);
		return 1;
	}

	jstring jinput = env->NewStringUTF(input.c_str());
	jint commandId = env->CallIntMethod(activity, method, jinput);
	env->DeleteLocalRef(jinput);

	lua_pushinteger(L, commandId);
	return 1;
#else
	lua_pushinteger(L, -1);
	return 1;
#endif
}

int ModApiTermux::l_termux_has_results(lua_State *L)
{
	NO_MAP_LOCK_REQUIRED;
#ifdef __ANDROID__
	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jobject activity = (jobject)SDL_AndroidGetActivity();
	jclass cls = env->GetObjectClass(activity);

	jmethodID method = env->GetMethodID(cls, "termuxHasResults", "()Z");
	if (method == nullptr) {
		lua_pushboolean(L, false);
		return 1;
	}

	jboolean result = env->CallBooleanMethod(activity, method);
	lua_pushboolean(L, result);
	return 1;
#else
	lua_pushboolean(L, false);
	return 1;
#endif
}

int ModApiTermux::l_termux_pop_result(lua_State *L)
{
	NO_MAP_LOCK_REQUIRED;
#ifdef __ANDROID__
	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jobject activity = (jobject)SDL_AndroidGetActivity();
	jclass cls = env->GetObjectClass(activity);

	jmethodID method = env->GetMethodID(cls, "termuxPopResult", "()[Ljava/lang/String;");
	if (method == nullptr) {
		lua_pushnil(L);
		return 1;
	}

	jobjectArray result = (jobjectArray)env->CallObjectMethod(activity, method);
	if (result == nullptr) {
		lua_pushnil(L);
		return 1;
	}

	jsize len = env->GetArrayLength(result);
	if (len < 5) {
		lua_pushnil(L);
		return 1;
	}

	lua_newtable(L);

	jstring jCommandId = (jstring)env->GetObjectArrayElement(result, 0);
	jstring jStdout = (jstring)env->GetObjectArrayElement(result, 1);
	jstring jStderr = (jstring)env->GetObjectArrayElement(result, 2);
	jstring jExitCode = (jstring)env->GetObjectArrayElement(result, 3);
	jstring jError = (jstring)env->GetObjectArrayElement(result, 4);

	std::string commandId = readJavaString(env, jCommandId);
	std::string stdout_str = readJavaString(env, jStdout);
	std::string stderr_str = readJavaString(env, jStderr);
	std::string exitCode = readJavaString(env, jExitCode);
	std::string error = readJavaString(env, jError);

	lua_pushinteger(L, std::stoi(commandId));
	lua_setfield(L, -2, "command_id");

	lua_pushstring(L, stdout_str.c_str());
	lua_setfield(L, -2, "stdout");

	lua_pushstring(L, stderr_str.c_str());
	lua_setfield(L, -2, "stderr");

	lua_pushinteger(L, std::stoi(exitCode));
	lua_setfield(L, -2, "exit_code");

	lua_pushstring(L, error.c_str());
	lua_setfield(L, -2, "error");

	env->DeleteLocalRef(jCommandId);
	env->DeleteLocalRef(jStdout);
	env->DeleteLocalRef(jStderr);
	env->DeleteLocalRef(jExitCode);
	env->DeleteLocalRef(jError);
	env->DeleteLocalRef(result);

	return 1;
#else
	lua_pushnil(L);
	return 1;
#endif
}

int ModApiTermux::l_termux_is_completed(lua_State *L)
{
	NO_MAP_LOCK_REQUIRED;
#ifdef __ANDROID__
	int commandId = luaL_checkinteger(L, 1);

	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jobject activity = (jobject)SDL_AndroidGetActivity();
	jclass cls = env->GetObjectClass(activity);

	jmethodID method = env->GetMethodID(cls, "termuxIsCommandCompleted", "(I)Z");
	if (method == nullptr) {
		lua_pushboolean(L, false);
		return 1;
	}

	jboolean result = env->CallBooleanMethod(activity, method, commandId);
	lua_pushboolean(L, result);
	return 1;
#else
	lua_pushboolean(L, false);
	return 1;
#endif
}

int ModApiTermux::l_termux_has_triggered_hooks(lua_State *L)
{
	NO_MAP_LOCK_REQUIRED;
#ifdef __ANDROID__
	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jobject activity = (jobject)SDL_AndroidGetActivity();
	jclass cls = env->GetObjectClass(activity);

	jmethodID method = env->GetMethodID(cls, "termuxHasTriggeredHooks", "()Z");
	if (method == nullptr) {
		lua_pushboolean(L, false);
		return 1;
	}

	jboolean result = env->CallBooleanMethod(activity, method);
	lua_pushboolean(L, result);
	return 1;
#else
	lua_pushboolean(L, false);
	return 1;
#endif
}

int ModApiTermux::l_termux_pop_triggered_hook(lua_State *L)
{
	NO_MAP_LOCK_REQUIRED;
#ifdef __ANDROID__
	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jobject activity = (jobject)SDL_AndroidGetActivity();
	jclass cls = env->GetObjectClass(activity);

	jmethodID method = env->GetMethodID(cls, "termuxPopTriggeredHook", "()[Ljava/lang/String;");
	if (method == nullptr) {
		lua_pushnil(L);
		return 1;
	}

	jobjectArray result = (jobjectArray)env->CallObjectMethod(activity, method);
	if (result == nullptr) {
		lua_pushnil(L);
		return 1;
	}

	jsize len = env->GetArrayLength(result);
	if (len < 4) {
		lua_pushnil(L);
		return 1;
	}

	lua_newtable(L);

	jstring jHookId = (jstring)env->GetObjectArrayElement(result, 0);
	jstring jPattern = (jstring)env->GetObjectArrayElement(result, 1);
	jstring jOutput = (jstring)env->GetObjectArrayElement(result, 2);
	jstring jSourceCmdId = (jstring)env->GetObjectArrayElement(result, 3);

	std::string hookId = readJavaString(env, jHookId);
	std::string pattern = readJavaString(env, jPattern);
	std::string output = readJavaString(env, jOutput);
	std::string sourceCmdId = readJavaString(env, jSourceCmdId);

	lua_pushinteger(L, std::stoi(hookId));
	lua_setfield(L, -2, "hook_id");

	lua_pushstring(L, pattern.c_str());
	lua_setfield(L, -2, "pattern");

	lua_pushstring(L, output.c_str());
	lua_setfield(L, -2, "output");

	lua_pushinteger(L, std::stoi(sourceCmdId));
	lua_setfield(L, -2, "source_command_id");

	env->DeleteLocalRef(jHookId);
	env->DeleteLocalRef(jPattern);
	env->DeleteLocalRef(jOutput);
	env->DeleteLocalRef(jSourceCmdId);
	env->DeleteLocalRef(result);

	return 1;
#else
	lua_pushnil(L);
	return 1;
#endif
}

int ModApiTermux::l_termux_get_paths(lua_State *L)
{
	NO_MAP_LOCK_REQUIRED;
#ifdef __ANDROID__
	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jobject activity = (jobject)SDL_AndroidGetActivity();
	jclass cls = env->GetObjectClass(activity);

	lua_newtable(L);

	jmethodID getHome = env->GetMethodID(cls, "termuxGetHomePath", "()Ljava/lang/String;");
	jmethodID getBin = env->GetMethodID(cls, "termuxGetBinPath", "()Ljava/lang/String;");
	jmethodID getPrefix = env->GetMethodID(cls, "termuxGetPrefixPath", "()Ljava/lang/String;");

	if (getHome != nullptr) {
		jstring jpath = (jstring)env->CallObjectMethod(activity, getHome);
		std::string path = readJavaString(env, jpath);
		lua_pushstring(L, path.c_str());
		lua_setfield(L, -2, "home");
		env->DeleteLocalRef(jpath);
	}

	if (getBin != nullptr) {
		jstring jpath = (jstring)env->CallObjectMethod(activity, getBin);
		std::string path = readJavaString(env, jpath);
		lua_pushstring(L, path.c_str());
		lua_setfield(L, -2, "bin");
		env->DeleteLocalRef(jpath);
	}

	if (getPrefix != nullptr) {
		jstring jpath = (jstring)env->CallObjectMethod(activity, getPrefix);
		std::string path = readJavaString(env, jpath);
		lua_pushstring(L, path.c_str());
		lua_setfield(L, -2, "prefix");
		env->DeleteLocalRef(jpath);
	}

	return 1;
#else
	lua_newtable(L);
	return 1;
#endif
}

void ModApiTermux::Initialize(lua_State *L, int top)
{
	API_FCT(termux_is_installed);
	API_FCT(termux_is_accessible);
	API_FCT(termux_execute);
	API_FCT(termux_execute_shell);
	API_FCT(termux_execute_script);
	API_FCT(termux_add_hook);
	API_FCT(termux_remove_hook);
	API_FCT(termux_send_input);
	API_FCT(termux_has_results);
	API_FCT(termux_pop_result);
	API_FCT(termux_is_completed);
	API_FCT(termux_has_triggered_hooks);
	API_FCT(termux_pop_triggered_hook);
	API_FCT(termux_get_paths);
}
