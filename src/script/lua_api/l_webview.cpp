// Luanti
// SPDX-License-Identifier: LGPL-2.1-or-later
// Copyright (C) 2024 Luanti Contributors

#include "lua_api/l_webview.h"
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

int ModApiWebView::l_webview_create(lua_State *L)
{
	NO_MAP_LOCK_REQUIRED;
#ifdef __ANDROID__
	int x = luaL_checkinteger(L, 1);
	int y = luaL_checkinteger(L, 2);
	int width = luaL_checkinteger(L, 3);
	int height = luaL_checkinteger(L, 4);
	bool textureMode = lua_toboolean(L, 5);

	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jobject activity = (jobject)SDL_AndroidGetActivity();
	jclass cls = env->GetObjectClass(activity);

	jmethodID method = env->GetMethodID(cls, "createWebView", "(IIIIZ)I");
	if (method == nullptr) {
		lua_pushnil(L);
		return 1;
	}

	jint id = env->CallIntMethod(activity, method, x, y, width, height, textureMode);
	lua_pushinteger(L, id);
	return 1;
#else
	lua_pushnil(L);
	return 1;
#endif
}

int ModApiWebView::l_webview_load_html(lua_State *L)
{
	NO_MAP_LOCK_REQUIRED;
#ifdef __ANDROID__
	int id = luaL_checkinteger(L, 1);
	std::string html = luaL_checkstring(L, 2);

	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jobject activity = (jobject)SDL_AndroidGetActivity();
	jclass cls = env->GetObjectClass(activity);

	jmethodID method = env->GetMethodID(cls, "webViewLoadHtml", "(ILjava/lang/String;)V");
	if (method == nullptr) return 0;

	jstring jhtml = env->NewStringUTF(html.c_str());
	env->CallVoidMethod(activity, method, id, jhtml);
	env->DeleteLocalRef(jhtml);
#endif
	return 0;
}

int ModApiWebView::l_webview_load_file(lua_State *L)
{
	NO_MAP_LOCK_REQUIRED;
#ifdef __ANDROID__
	int id = luaL_checkinteger(L, 1);
	std::string path = luaL_checkstring(L, 2);

	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jobject activity = (jobject)SDL_AndroidGetActivity();
	jclass cls = env->GetObjectClass(activity);

	jmethodID method = env->GetMethodID(cls, "webViewLoadFile", "(ILjava/lang/String;)V");
	if (method == nullptr) return 0;

	jstring jpath = env->NewStringUTF(path.c_str());
	env->CallVoidMethod(activity, method, id, jpath);
	env->DeleteLocalRef(jpath);
#endif
	return 0;
}

int ModApiWebView::l_webview_load_url(lua_State *L)
{
	NO_MAP_LOCK_REQUIRED;
#ifdef __ANDROID__
	int id = luaL_checkinteger(L, 1);
	std::string url = luaL_checkstring(L, 2);

	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jobject activity = (jobject)SDL_AndroidGetActivity();
	jclass cls = env->GetObjectClass(activity);

	jmethodID method = env->GetMethodID(cls, "webViewLoadUrl", "(ILjava/lang/String;)V");
	if (method == nullptr) return 0;

	jstring jurl = env->NewStringUTF(url.c_str());
	env->CallVoidMethod(activity, method, id, jurl);
	env->DeleteLocalRef(jurl);
#endif
	return 0;
}

int ModApiWebView::l_webview_execute_js(lua_State *L)
{
	NO_MAP_LOCK_REQUIRED;
#ifdef __ANDROID__
	int id = luaL_checkinteger(L, 1);
	std::string script = luaL_checkstring(L, 2);

	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jobject activity = (jobject)SDL_AndroidGetActivity();
	jclass cls = env->GetObjectClass(activity);

	jmethodID method = env->GetMethodID(cls, "webViewExecuteJs", "(ILjava/lang/String;)V");
	if (method == nullptr) return 0;

	jstring jscript = env->NewStringUTF(script.c_str());
	env->CallVoidMethod(activity, method, id, jscript);
	env->DeleteLocalRef(jscript);
#endif
	return 0;
}

int ModApiWebView::l_webview_set_position(lua_State *L)
{
	NO_MAP_LOCK_REQUIRED;
#ifdef __ANDROID__
	int id = luaL_checkinteger(L, 1);
	int x = luaL_checkinteger(L, 2);
	int y = luaL_checkinteger(L, 3);

	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jobject activity = (jobject)SDL_AndroidGetActivity();
	jclass cls = env->GetObjectClass(activity);

	jmethodID method = env->GetMethodID(cls, "webViewSetPosition", "(III)V");
	if (method == nullptr) return 0;

	env->CallVoidMethod(activity, method, id, x, y);
#endif
	return 0;
}

int ModApiWebView::l_webview_set_size(lua_State *L)
{
	NO_MAP_LOCK_REQUIRED;
#ifdef __ANDROID__
	int id = luaL_checkinteger(L, 1);
	int width = luaL_checkinteger(L, 2);
	int height = luaL_checkinteger(L, 3);

	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jobject activity = (jobject)SDL_AndroidGetActivity();
	jclass cls = env->GetObjectClass(activity);

	jmethodID method = env->GetMethodID(cls, "webViewSetSize", "(III)V");
	if (method == nullptr) return 0;

	env->CallVoidMethod(activity, method, id, width, height);
#endif
	return 0;
}

int ModApiWebView::l_webview_set_visible(lua_State *L)
{
	NO_MAP_LOCK_REQUIRED;
#ifdef __ANDROID__
	int id = luaL_checkinteger(L, 1);
	bool visible = lua_toboolean(L, 2);

	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jobject activity = (jobject)SDL_AndroidGetActivity();
	jclass cls = env->GetObjectClass(activity);

	jmethodID method = env->GetMethodID(cls, "webViewSetVisible", "(IZ)V");
	if (method == nullptr) return 0;

	env->CallVoidMethod(activity, method, id, visible);
#endif
	return 0;
}

int ModApiWebView::l_webview_destroy(lua_State *L)
{
	NO_MAP_LOCK_REQUIRED;
#ifdef __ANDROID__
	int id = luaL_checkinteger(L, 1);

	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jobject activity = (jobject)SDL_AndroidGetActivity();
	jclass cls = env->GetObjectClass(activity);

	jmethodID method = env->GetMethodID(cls, "webViewDestroy", "(I)V");
	if (method == nullptr) return 0;

	env->CallVoidMethod(activity, method, id);
#endif
	return 0;
}

int ModApiWebView::l_webview_capture_texture(lua_State *L)
{
	NO_MAP_LOCK_REQUIRED;
#ifdef __ANDROID__
	int id = luaL_checkinteger(L, 1);

	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jobject activity = (jobject)SDL_AndroidGetActivity();
	jclass cls = env->GetObjectClass(activity);

	jmethodID method = env->GetMethodID(cls, "webViewCaptureTexture", "(I)[B");
	if (method == nullptr) {
		lua_pushnil(L);
		return 1;
	}

	jbyteArray result = (jbyteArray)env->CallObjectMethod(activity, method, id);
	if (result == nullptr) {
		lua_pushnil(L);
		return 1;
	}

	jsize len = env->GetArrayLength(result);
	jbyte *bytes = env->GetByteArrayElements(result, nullptr);
	
	lua_pushlstring(L, (const char *)bytes, len);
	
	env->ReleaseByteArrayElements(result, bytes, 0);
	env->DeleteLocalRef(result);
	return 1;
#else
	lua_pushnil(L);
	return 1;
#endif
}

int ModApiWebView::l_webview_needs_texture_update(lua_State *L)
{
	NO_MAP_LOCK_REQUIRED;
#ifdef __ANDROID__
	int id = luaL_checkinteger(L, 1);

	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jobject activity = (jobject)SDL_AndroidGetActivity();
	jclass cls = env->GetObjectClass(activity);

	jmethodID method = env->GetMethodID(cls, "webViewNeedsTextureUpdate", "(I)Z");
	if (method == nullptr) {
		lua_pushboolean(L, false);
		return 1;
	}

	jboolean result = env->CallBooleanMethod(activity, method, id);
	lua_pushboolean(L, result);
	return 1;
#else
	lua_pushboolean(L, false);
	return 1;
#endif
}

int ModApiWebView::l_webview_has_messages(lua_State *L)
{
	NO_MAP_LOCK_REQUIRED;
#ifdef __ANDROID__
	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jobject activity = (jobject)SDL_AndroidGetActivity();
	jclass cls = env->GetObjectClass(activity);

	jmethodID method = env->GetMethodID(cls, "webViewHasMessages", "()Z");
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

int ModApiWebView::l_webview_pop_message(lua_State *L)
{
	NO_MAP_LOCK_REQUIRED;
#ifdef __ANDROID__
	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jobject activity = (jobject)SDL_AndroidGetActivity();
	jclass cls = env->GetObjectClass(activity);

	jmethodID method = env->GetMethodID(cls, "webViewPopMessage", "()[Ljava/lang/String;");
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
	if (len < 3) {
		lua_pushnil(L);
		return 1;
	}

	lua_newtable(L);

	jstring jWebViewId = (jstring)env->GetObjectArrayElement(result, 0);
	jstring jEventType = (jstring)env->GetObjectArrayElement(result, 1);
	jstring jData = (jstring)env->GetObjectArrayElement(result, 2);

	std::string webViewId = readJavaString(env, jWebViewId);
	std::string eventType = readJavaString(env, jEventType);
	std::string data = readJavaString(env, jData);

	lua_pushinteger(L, std::stoi(webViewId));
	lua_setfield(L, -2, "webview_id");

	lua_pushstring(L, eventType.c_str());
	lua_setfield(L, -2, "event");

	lua_pushstring(L, data.c_str());
	lua_setfield(L, -2, "data");

	env->DeleteLocalRef(jWebViewId);
	env->DeleteLocalRef(jEventType);
	env->DeleteLocalRef(jData);
	env->DeleteLocalRef(result);

	return 1;
#else
	lua_pushnil(L);
	return 1;
#endif
}

int ModApiWebView::l_webview_get_ids(lua_State *L)
{
	NO_MAP_LOCK_REQUIRED;
#ifdef __ANDROID__
	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jobject activity = (jobject)SDL_AndroidGetActivity();
	jclass cls = env->GetObjectClass(activity);

	jmethodID method = env->GetMethodID(cls, "webViewGetIds", "()[I");
	if (method == nullptr) {
		lua_newtable(L);
		return 1;
	}

	jintArray result = (jintArray)env->CallObjectMethod(activity, method);
	if (result == nullptr) {
		lua_newtable(L);
		return 1;
	}

	jsize len = env->GetArrayLength(result);
	jint *ids = env->GetIntArrayElements(result, nullptr);

	lua_newtable(L);
	for (jsize i = 0; i < len; i++) {
		lua_pushinteger(L, ids[i]);
		lua_rawseti(L, -2, i + 1);
	}

	env->ReleaseIntArrayElements(result, ids, 0);
	env->DeleteLocalRef(result);
	return 1;
#else
	lua_newtable(L);
	return 1;
#endif
}

int ModApiWebView::l_webview_get_texture_size(lua_State *L)
{
	NO_MAP_LOCK_REQUIRED;
#ifdef __ANDROID__
	int id = luaL_checkinteger(L, 1);

	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jobject activity = (jobject)SDL_AndroidGetActivity();
	jclass cls = env->GetObjectClass(activity);

	jmethodID getWidth = env->GetMethodID(cls, "webViewGetTextureWidth", "(I)I");
	jmethodID getHeight = env->GetMethodID(cls, "webViewGetTextureHeight", "(I)I");
	
	if (getWidth == nullptr || getHeight == nullptr) {
		lua_pushinteger(L, 0);
		lua_pushinteger(L, 0);
		return 2;
	}

	jint width = env->CallIntMethod(activity, getWidth, id);
	jint height = env->CallIntMethod(activity, getHeight, id);

	lua_pushinteger(L, width);
	lua_pushinteger(L, height);
	return 2;
#else
	lua_pushinteger(L, 0);
	lua_pushinteger(L, 0);
	return 2;
#endif
}

void ModApiWebView::Initialize(lua_State *L, int top)
{
	API_FCT(webview_create);
	API_FCT(webview_load_html);
	API_FCT(webview_load_file);
	API_FCT(webview_load_url);
	API_FCT(webview_execute_js);
	API_FCT(webview_set_position);
	API_FCT(webview_set_size);
	API_FCT(webview_set_visible);
	API_FCT(webview_destroy);
	API_FCT(webview_capture_texture);
	API_FCT(webview_needs_texture_update);
	API_FCT(webview_has_messages);
	API_FCT(webview_pop_message);
	API_FCT(webview_get_ids);
	API_FCT(webview_get_texture_size);
}
