/*
Luanti Termux Bridge
SPDX-License-Identifier: LGPL-2.1-or-later
Copyright (C) 2024 Luanti Contributors

Proper Termux integration using RUN_COMMAND intent.
Based on Termux source code analysis.
*/

package net.minetest.minetest;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Keep;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Keep
@SuppressWarnings("unused")
public class TermuxBridge {
    private static final String TAG = "TermuxBridge";
    
    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String TERMUX_SERVICE = "com.termux.app.RunCommandService";
    private static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";
    
    private static final String EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH";
    private static final String EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS";
    private static final String EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    private static final String EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";
    private static final String EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION";
    private static final String EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL";
    private static final String EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT";
    
    private static final String TERMUX_BIN = "/data/data/com.termux/files/usr/bin";
    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    
    private static TermuxBridge instance;
    private final Context context;
    private final Handler mainHandler;
    private final ExecutorService executor;
    private final ConcurrentHashMap<Integer, CommandResult> results;
    private final CopyOnWriteArrayList<OutputHook> hooks;
    private final AtomicInteger nextId;
    private BroadcastReceiver receiver;
    private boolean initialized = false;
    
    public static class CommandResult {
        public final int commandId;
        public String stdout = "";
        public String stderr = "";
        public int exitCode = -1;
        public String error = "";
        public boolean completed = false;
        
        public CommandResult(int id) {
            this.commandId = id;
        }
    }
    
    public static class OutputHook {
        public final int id;
        public final String pattern;
        public final boolean isRegex;
        public boolean triggered = false;
        public String output = "";
        public int sourceId = 0;
        
        public OutputHook(int id, String pattern, boolean isRegex) {
            this.id = id;
            this.pattern = pattern;
            this.isRegex = isRegex;
        }
    }
    
    private TermuxBridge(Context ctx) {
        this.context = ctx.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executor = Executors.newSingleThreadExecutor();
        this.results = new ConcurrentHashMap<>();
        this.hooks = new CopyOnWriteArrayList<>();
        this.nextId = new AtomicInteger(1);
    }
    
    public static synchronized TermuxBridge getInstance(Context ctx) {
        if (instance == null) {
            instance = new TermuxBridge(ctx);
        }
        return instance;
    }
    
    public static TermuxBridge getInstance() {
        return instance;
    }
    
    public void initialize() {
        if (initialized) return;
        
        try {
            receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    handleResult(intent);
                }
            };
            
            IntentFilter filter = new IntentFilter("net.minetest.minetest.TERMUX_RESULT");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            
            initialized = true;
            Log.i(TAG, "TermuxBridge initialized");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize", e);
        }
    }
    
    public void shutdown() {
        try {
            if (receiver != null) {
                context.unregisterReceiver(receiver);
                receiver = null;
            }
            executor.shutdown();
        } catch (Exception ignored) {}
        initialized = false;
    }
    
    public boolean isTermuxInstalled() {
        try {
            context.getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
    
    public boolean isTermuxAccessible() {
        return isTermuxInstalled();
    }
    
    public int executeCommand(String executable, String[] args, String workDir, boolean background, String stdin) {
        if (!isTermuxInstalled()) {
            Log.e(TAG, "Termux not installed");
            return -1;
        }
        
        final int cmdId = nextId.getAndIncrement();
        final CommandResult result = new CommandResult(cmdId);
        results.put(cmdId, result);
        
        executor.execute(() -> {
            try {
                Intent intent = new Intent();
                intent.setClassName(TERMUX_PACKAGE, TERMUX_SERVICE);
                intent.setAction(ACTION_RUN_COMMAND);
                
                String path = executable;
                if (!path.startsWith("/")) {
                    path = TERMUX_BIN + "/" + executable;
                }
                intent.putExtra(EXTRA_COMMAND_PATH, path);
                
                if (args != null && args.length > 0) {
                    intent.putExtra(EXTRA_ARGUMENTS, args);
                }
                
                String wd = (workDir != null && !workDir.isEmpty()) ? workDir : TERMUX_HOME;
                intent.putExtra(EXTRA_WORKDIR, wd);
                intent.putExtra(EXTRA_BACKGROUND, background);
                
                if (!background) {
                    intent.putExtra(EXTRA_SESSION_ACTION, "0");
                }
                
                intent.putExtra(EXTRA_COMMAND_LABEL, "Luanti #" + cmdId);
                
                Intent resultIntent = new Intent("net.minetest.minetest.TERMUX_RESULT");
                resultIntent.setPackage(context.getPackageName());
                resultIntent.putExtra("cmd_id", cmdId);
                
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    flags |= PendingIntent.FLAG_MUTABLE;
                }
                PendingIntent pi = PendingIntent.getBroadcast(context, cmdId, resultIntent, flags);
                intent.putExtra(EXTRA_PENDING_INTENT, pi);
                
                mainHandler.post(() -> {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(intent);
                        } else {
                            context.startService(intent);
                        }
                        Log.i(TAG, "Started command #" + cmdId);
                    } catch (Exception e) {
                        Log.e(TAG, "Service start failed", e);
                        result.error = e.getMessage();
                        result.completed = true;
                    }
                });
                
            } catch (Exception e) {
                Log.e(TAG, "Execute failed", e);
                result.error = e.getMessage();
                result.completed = true;
            }
        });
        
        return cmdId;
    }
    
    public int executeShellCommand(String command, boolean background) {
        return executeCommand("bash", new String[]{"-c", command}, null, background, null);
    }
    
    public int executeScript(String script, boolean background) {
        return executeCommand("bash", new String[]{"-c", script}, null, background, null);
    }
    
    private void handleResult(Intent intent) {
        int cmdId = intent.getIntExtra("cmd_id", -1);
        if (cmdId < 0) return;
        
        CommandResult result = results.get(cmdId);
        if (result == null) return;
        
        Bundle bundle = intent.getBundleExtra("result");
        if (bundle != null) {
            result.stdout = bundle.getString("stdout", "");
            result.stderr = bundle.getString("stderr", "");
            result.exitCode = bundle.getInt("exitCode", -1);
        }
        
        result.completed = true;
        checkHooks(result);
        Log.i(TAG, "Command #" + cmdId + " completed");
    }
    
    public int addOutputHook(String pattern, boolean isRegex) {
        int id = nextId.getAndIncrement();
        hooks.add(new OutputHook(id, pattern, isRegex));
        return id;
    }
    
    public void removeOutputHook(int hookId) {
        hooks.removeIf(h -> h.id == hookId);
    }
    
    private void checkHooks(CommandResult result) {
        String output = result.stdout + "\n" + result.stderr;
        for (OutputHook hook : hooks) {
            if (hook.triggered) continue;
            boolean match = hook.isRegex ? 
                output.matches("(?s).*" + hook.pattern + ".*") : 
                output.contains(hook.pattern);
            if (match) {
                hook.triggered = true;
                hook.output = output;
                hook.sourceId = result.commandId;
            }
        }
    }
    
    public int sendInputToTermux(String input) {
        return executeShellCommand(input, true);
    }
    
    public boolean hasResults() {
        for (CommandResult r : results.values()) {
            if (r.completed) return true;
        }
        return false;
    }
    
    public CommandResult popResult() {
        for (Integer key : results.keySet()) {
            CommandResult r = results.get(key);
            if (r != null && r.completed) {
                results.remove(key);
                return r;
            }
        }
        return null;
    }
    
    public boolean isCommandCompleted(int cmdId) {
        CommandResult r = results.get(cmdId);
        return r != null && r.completed;
    }
    
    public boolean hasTriggeredHooks() {
        for (OutputHook h : hooks) {
            if (h.triggered) return true;
        }
        return false;
    }
    
    public OutputHook popTriggeredHook() {
        for (int i = 0; i < hooks.size(); i++) {
            OutputHook h = hooks.get(i);
            if (h.triggered) {
                hooks.remove(i);
                return h;
            }
        }
        return null;
    }
    
    public String getTermuxHomePath() { return TERMUX_HOME; }
    public String getTermuxBinPath() { return TERMUX_BIN; }
    public String getTermuxPrefixPath() { return "/data/data/com.termux/files/usr"; }
}
