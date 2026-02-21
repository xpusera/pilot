/*
Luanti Termux Bridge
SPDX-License-Identifier: LGPL-2.1-or-later
Copyright (C) 2024 Luanti Contributors

Provides Termux integration for Lua mods without modifying Termux.
Uses Termux RUN_COMMAND intent API for command execution and result capture.
*/

package net.minetest.minetest;

import android.app.Activity;
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
import java.util.concurrent.atomic.AtomicInteger;

@Keep
@SuppressWarnings("unused")
public class TermuxBridge {
    private static final String TAG = "TermuxBridge";
    
    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService";
    private static final String TERMUX_RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND";
    private static final String TERMUX_RUN_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH";
    private static final String TERMUX_RUN_COMMAND_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS";
    private static final String TERMUX_RUN_COMMAND_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    private static final String TERMUX_RUN_COMMAND_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";
    private static final String TERMUX_RUN_COMMAND_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION";
    private static final String TERMUX_RUN_COMMAND_STDIN = "com.termux.RUN_COMMAND_STDIN";
    private static final String TERMUX_RUN_COMMAND_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL";
    
    private static final String TERMUX_PREFIX_DIR = "/data/data/com.termux/files/usr";
    private static final String TERMUX_HOME_DIR = "/data/data/com.termux/files/home";
    private static final String TERMUX_BIN_DIR = TERMUX_PREFIX_DIR + "/bin";
    
    private static TermuxBridge instance;
    private final Context context;
    private final Handler mainHandler;
    private final ConcurrentHashMap<Integer, CommandExecution> pendingCommands;
    private final CopyOnWriteArrayList<CommandResult> completedResults;
    private final CopyOnWriteArrayList<OutputHook> outputHooks;
    private final AtomicInteger nextCommandId;
    private BroadcastReceiver resultReceiver;
    private boolean initialized = false;
    
    public static class CommandResult {
        public final int commandId;
        public final String stdout;
        public final String stderr;
        public final int exitCode;
        public final String error;
        public final long timestamp;
        
        public CommandResult(int commandId, String stdout, String stderr, int exitCode, String error) {
            this.commandId = commandId;
            this.stdout = stdout != null ? stdout : "";
            this.stderr = stderr != null ? stderr : "";
            this.exitCode = exitCode;
            this.error = error != null ? error : "";
            this.timestamp = System.currentTimeMillis();
        }
    }
    
    public static class CommandExecution {
        public final int id;
        public final String command;
        public final String[] args;
        public final boolean background;
        public final long startTime;
        public boolean completed;
        public CommandResult result;
        
        public CommandExecution(int id, String command, String[] args, boolean background) {
            this.id = id;
            this.command = command;
            this.args = args;
            this.background = background;
            this.startTime = System.currentTimeMillis();
            this.completed = false;
        }
    }
    
    public static class OutputHook {
        public final int id;
        public final String pattern;
        public final boolean isRegex;
        public boolean triggered;
        public String matchedOutput;
        public int sourceCommandId;
        
        public OutputHook(int id, String pattern, boolean isRegex) {
            this.id = id;
            this.pattern = pattern;
            this.isRegex = isRegex;
            this.triggered = false;
        }
    }
    
    private TermuxBridge(Context context) {
        this.context = context;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.pendingCommands = new ConcurrentHashMap<>();
        this.completedResults = new CopyOnWriteArrayList<>();
        this.outputHooks = new CopyOnWriteArrayList<>();
        this.nextCommandId = new AtomicInteger(1);
    }
    
    public static synchronized TermuxBridge getInstance(Context context) {
        if (instance == null) {
            instance = new TermuxBridge(context);
        }
        return instance;
    }
    
    public static TermuxBridge getInstance() {
        return instance;
    }
    
    public void initialize() {
        if (initialized) return;
        
        registerResultReceiver();
        initialized = true;
        Log.i(TAG, "TermuxBridge initialized");
    }
    
    public void shutdown() {
        if (resultReceiver != null) {
            try {
                context.unregisterReceiver(resultReceiver);
            } catch (Exception ignored) {}
            resultReceiver = null;
        }
        pendingCommands.clear();
        completedResults.clear();
        outputHooks.clear();
        initialized = false;
    }
    
    private void registerResultReceiver() {
        resultReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleCommandResult(intent);
            }
        };
        
        IntentFilter filter = new IntentFilter("net.minetest.minetest.TERMUX_RESULT");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(resultReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(resultReceiver, filter);
        }
    }
    
    public boolean isTermuxInstalled() {
        PackageManager pm = context.getPackageManager();
        try {
            pm.getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
    
    public boolean isTermuxAccessible() {
        if (!isTermuxInstalled()) return false;
        
        PackageManager pm = context.getPackageManager();
        Intent intent = new Intent();
        intent.setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE);
        return pm.queryIntentServices(intent, 0).size() > 0;
    }
    
    public int executeCommand(String executable, String[] args, String workDir, boolean background, String stdin) {
        if (!isTermuxInstalled()) {
            Log.e(TAG, "Termux is not installed");
            return -1;
        }
        
        int commandId = nextCommandId.getAndIncrement();
        CommandExecution execution = new CommandExecution(commandId, executable, args, background);
        pendingCommands.put(commandId, execution);
        
        Intent intent = new Intent();
        intent.setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE);
        intent.setAction(TERMUX_RUN_COMMAND_ACTION);
        
        String executablePath = executable;
        if (!executable.startsWith("/")) {
            executablePath = TERMUX_BIN_DIR + "/" + executable;
        }
        
        intent.putExtra(TERMUX_RUN_COMMAND_PATH, executablePath);
        
        if (args != null && args.length > 0) {
            intent.putExtra(TERMUX_RUN_COMMAND_ARGUMENTS, args);
        }
        
        String workingDir = workDir;
        if (workingDir == null || workingDir.isEmpty()) {
            workingDir = TERMUX_HOME_DIR;
        }
        intent.putExtra(TERMUX_RUN_COMMAND_WORKDIR, workingDir);
        
        intent.putExtra(TERMUX_RUN_COMMAND_BACKGROUND, background);
        
        if (!background) {
            intent.putExtra(TERMUX_RUN_COMMAND_SESSION_ACTION, "0");
        }
        
        if (stdin != null && !stdin.isEmpty()) {
            intent.putExtra(TERMUX_RUN_COMMAND_STDIN, stdin);
        }
        
        intent.putExtra(TERMUX_RUN_COMMAND_COMMAND_LABEL, "Luanti Command #" + commandId);
        
        Intent resultIntent = new Intent("net.minetest.minetest.TERMUX_RESULT");
        resultIntent.putExtra("command_id", commandId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, commandId, resultIntent, flags);
        intent.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pendingIntent);
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            Log.i(TAG, "Started Termux command #" + commandId + ": " + executable);
            return commandId;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start Termux command", e);
            pendingCommands.remove(commandId);
            completedResults.add(new CommandResult(commandId, "", "", -1, "Failed to start: " + e.getMessage()));
            return commandId;
        }
    }
    
    public int executeShellCommand(String command, boolean background) {
        return executeCommand("bash", new String[]{"-c", command}, null, background, null);
    }
    
    public int executeScript(String scriptContent, boolean background) {
        return executeCommand("bash", new String[]{}, null, background, scriptContent);
    }
    
    private void handleCommandResult(Intent intent) {
        int commandId = intent.getIntExtra("command_id", -1);
        if (commandId < 0) return;
        
        CommandExecution execution = pendingCommands.get(commandId);
        if (execution == null) return;
        
        Bundle resultBundle = intent.getBundleExtra("com.termux.TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE");
        
        String stdout = "";
        String stderr = "";
        int exitCode = -1;
        String error = "";
        
        if (resultBundle != null) {
            stdout = resultBundle.getString("com.termux.TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT", "");
            stderr = resultBundle.getString("com.termux.TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_STDERR", "");
            exitCode = resultBundle.getInt("com.termux.TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_EXIT_CODE", -1);
            error = resultBundle.getString("com.termux.TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_ERR", "");
        }
        
        CommandResult result = new CommandResult(commandId, stdout, stderr, exitCode, error);
        execution.result = result;
        execution.completed = true;
        completedResults.add(result);
        
        checkOutputHooks(result);
        
        Log.i(TAG, "Command #" + commandId + " completed with exit code " + exitCode);
    }
    
    public int addOutputHook(String pattern, boolean isRegex) {
        int hookId = nextCommandId.getAndIncrement();
        OutputHook hook = new OutputHook(hookId, pattern, isRegex);
        outputHooks.add(hook);
        return hookId;
    }
    
    public void removeOutputHook(int hookId) {
        outputHooks.removeIf(hook -> hook.id == hookId);
    }
    
    private void checkOutputHooks(CommandResult result) {
        String fullOutput = result.stdout + "\n" + result.stderr;
        
        for (OutputHook hook : outputHooks) {
            if (hook.triggered) continue;
            
            boolean matches = false;
            if (hook.isRegex) {
                try {
                    matches = fullOutput.matches("(?s).*" + hook.pattern + ".*");
                } catch (Exception ignored) {}
            } else {
                matches = fullOutput.contains(hook.pattern);
            }
            
            if (matches) {
                hook.triggered = true;
                hook.matchedOutput = fullOutput;
                hook.sourceCommandId = result.commandId;
            }
        }
    }
    
    public int sendInput(int commandId, String input) {
        CommandExecution execution = pendingCommands.get(commandId);
        if (execution == null || execution.completed) {
            Log.w(TAG, "Cannot send input to completed or non-existent command #" + commandId);
            return -1;
        }
        
        return executeCommand("bash", new String[]{"-c", "echo '" + input.replace("'", "'\\''") + "'"}, null, true, null);
    }
    
    public int sendInputToTermux(String input) {
        return executeCommand("bash", new String[]{"-c", input}, null, false, null);
    }
    
    public boolean hasResults() {
        return !completedResults.isEmpty();
    }
    
    public CommandResult popResult() {
        if (completedResults.isEmpty()) return null;
        return completedResults.remove(0);
    }
    
    public CommandResult getResult(int commandId) {
        for (CommandResult result : completedResults) {
            if (result.commandId == commandId) {
                return result;
            }
        }
        
        CommandExecution execution = pendingCommands.get(commandId);
        if (execution != null && execution.result != null) {
            return execution.result;
        }
        
        return null;
    }
    
    public boolean isCommandCompleted(int commandId) {
        CommandExecution execution = pendingCommands.get(commandId);
        return execution != null && execution.completed;
    }
    
    public boolean hasTriggeredHooks() {
        for (OutputHook hook : outputHooks) {
            if (hook.triggered) return true;
        }
        return false;
    }
    
    public OutputHook popTriggeredHook() {
        for (int i = 0; i < outputHooks.size(); i++) {
            OutputHook hook = outputHooks.get(i);
            if (hook.triggered) {
                outputHooks.remove(i);
                return hook;
            }
        }
        return null;
    }
    
    public void clearResults() {
        completedResults.clear();
    }
    
    public void clearHooks() {
        outputHooks.clear();
    }
    
    public String getTermuxHomePath() {
        return TERMUX_HOME_DIR;
    }
    
    public String getTermuxBinPath() {
        return TERMUX_BIN_DIR;
    }
    
    public String getTermuxPrefixPath() {
        return TERMUX_PREFIX_DIR;
    }
}
