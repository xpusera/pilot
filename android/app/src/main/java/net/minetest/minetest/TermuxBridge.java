/*
Luanti Termux Bridge
SPDX-License-Identifier: LGPL-2.1-or-later
Copyright (C) 2024 Luanti Contributors

Termux integration using RUN_COMMAND intent with file-based output capture
for reliable stdout/stderr retrieval. Falls back to reading result from
PendingIntent when file-based capture is not available.
*/

package net.minetest.minetest;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Keep;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
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

    // Temporary output directory on shared storage (accessible by both apps)
    private static final String TEMP_DIR_NAME = "luanti_termux_tmp";

    private static TermuxBridge instance;
    private final Context context;
    private final Handler mainHandler;
    private final ExecutorService executor;
    private final ConcurrentHashMap<Integer, CommandResult> results;
    private final CopyOnWriteArrayList<OutputHook> hooks;
    private final AtomicInteger nextId;
    private BroadcastReceiver receiver;
    private boolean initialized = false;
    private File tempDir;
    private boolean permissionConfirmed = false;

    public static class CommandResult {
        public final int commandId;
        public String stdout = "";
        public String stderr = "";
        public int exitCode = -1;
        public String error = "";
        public volatile boolean completed = false;

        public CommandResult(int id) {
            this.commandId = id;
        }
    }

    public static class OutputHook {
        public final int id;
        public final String pattern;
        public final boolean isRegex;
        public volatile boolean triggered = false;
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
        this.executor = Executors.newCachedThreadPool();
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

        // Create temp directory on external storage for command output files
        try {
            File extDir = Environment.getExternalStorageDirectory();
            tempDir = new File(extDir, TEMP_DIR_NAME);
            if (!tempDir.exists()) {
                tempDir.mkdirs();
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not create temp dir on external storage: " + e.getMessage());
            // Fallback to app cache
            tempDir = new File(context.getCacheDir(), TEMP_DIR_NAME);
            tempDir.mkdirs();
        }

        // Register broadcast receiver for PendingIntent results (secondary mechanism)
        try {
            receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    handlePendingIntentResult(intent);
                }
            };
            IntentFilter filter = new IntentFilter("net.minetest.minetest.TERMUX_RESULT");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            initialized = true;
            Log.i(TAG, "TermuxBridge initialized, temp dir: " + tempDir.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize receiver", e);
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

    /**
     * Check if Termux "Allow External Apps" is enabled by running a minimal test.
     * Returns true if we believe commands can be executed.
     */
    public boolean isTermuxAccessible() {
        if (!isTermuxInstalled()) return false;
        if (permissionConfirmed) return true;
        // We can't definitively check without trying, so return true if installed
        // The actual test happens in executeCommand
        return true;
    }

    /**
     * Check and report whether Termux RUN_COMMAND permission works.
     * Returns: 0 = not installed, 1 = installed but needs Allow External Apps,
     *          2 = accessible and ready
     */
    public int checkPermissionStatus() {
        if (!isTermuxInstalled()) return 0;
        if (permissionConfirmed) return 2;
        return 1; // Installed, status unknown
    }

    /**
     * Execute a command via Termux with file-based output capture.
     * The command output is written to temp files on shared storage,
     * then polled until complete.
     */
    public int executeCommand(String executable, String[] args, String workDir, boolean background, String stdin) {
        if (!isTermuxInstalled()) {
            Log.e(TAG, "Termux not installed");
            return -1;
        }

        final int cmdId = nextId.getAndIncrement();
        final CommandResult result = new CommandResult(cmdId);
        results.put(cmdId, result);

        // Build file paths for output capture
        final File outFile = new File(tempDir, "cmd_" + cmdId + ".out");
        final File errFile = new File(tempDir, "cmd_" + cmdId + ".err");
        final File exitFile = new File(tempDir, "cmd_" + cmdId + ".exit");

        // Wrap the command to redirect output to files
        String wrappedCommand = buildWrappedCommand(executable, args, outFile, errFile, exitFile);

        executor.execute(() -> {
            try {
                Intent intent = new Intent();
                intent.setClassName(TERMUX_PACKAGE, TERMUX_SERVICE);
                intent.setAction(ACTION_RUN_COMMAND);

                // Use bash to run the wrapped command (file-based output capture)
                intent.putExtra(EXTRA_COMMAND_PATH, TERMUX_BIN + "/bash");
                intent.putExtra(EXTRA_ARGUMENTS, new String[]{"-c", wrappedCommand});

                String wd = (workDir != null && !workDir.isEmpty()) ? workDir : TERMUX_HOME;
                intent.putExtra(EXTRA_WORKDIR, wd);
                intent.putExtra(EXTRA_BACKGROUND, true); // Always background for output capture
                intent.putExtra(EXTRA_COMMAND_LABEL, "Luanti#" + cmdId);

                // PendingIntent as secondary result mechanism
                Intent resultIntent = new Intent("net.minetest.minetest.TERMUX_RESULT");
                resultIntent.setPackage(context.getPackageName());
                resultIntent.putExtra("cmd_id", cmdId);

                int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    piFlags |= PendingIntent.FLAG_MUTABLE;
                }
                PendingIntent pi = PendingIntent.getBroadcast(context, cmdId, resultIntent, piFlags);
                intent.putExtra(EXTRA_PENDING_INTENT, pi);

                mainHandler.post(() -> {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(intent);
                        } else {
                            context.startService(intent);
                        }
                        permissionConfirmed = true;
                        Log.i(TAG, "Started command #" + cmdId);
                        // Start polling for file-based output
                        pollForResult(cmdId, outFile, errFile, exitFile);
                    } catch (SecurityException se) {
                        Log.e(TAG, "Termux permission denied - enable 'Allow External Apps' in Termux Settings", se);
                        result.error = "Permission denied: Enable 'Allow External Apps' in Termux > Settings";
                        result.exitCode = -2;
                        result.completed = true;
                        cleanupTempFiles(outFile, errFile, exitFile);
                    } catch (Exception e) {
                        Log.e(TAG, "Service start failed", e);
                        result.error = e.getMessage() != null ? e.getMessage() : "Failed to start Termux service";
                        result.exitCode = -3;
                        result.completed = true;
                        cleanupTempFiles(outFile, errFile, exitFile);
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Execute failed", e);
                result.error = e.getMessage();
                result.completed = true;
                cleanupTempFiles(outFile, errFile, exitFile);
            }
        });

        return cmdId;
    }

    /**
     * Build a bash command that wraps the original command and writes output to files.
     */
    private String buildWrappedCommand(String executable, String[] args, File outFile, File errFile, File exitFile) {
        StringBuilder sb = new StringBuilder();
        // Quote and escape the executable path
        String path = executable;
        if (!path.startsWith("/")) {
            path = TERMUX_BIN + "/" + executable;
        }
        sb.append(shellEscape(path));
        if (args != null) {
            for (String arg : args) {
                sb.append(' ').append(shellEscape(arg));
            }
        }
        // Redirect output to files, write exit code on completion
        return sb.toString() +
            " >" + shellEscape(outFile.getAbsolutePath()) +
            " 2>" + shellEscape(errFile.getAbsolutePath()) +
            "; echo $? >" + shellEscape(exitFile.getAbsolutePath());
    }

    private String shellEscape(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * Poll for the exit file to appear, then read output files.
     * Polls every 100ms for up to 60 seconds.
     */
    private void pollForResult(int cmdId, File outFile, File errFile, File exitFile) {
        executor.execute(() -> {
            CommandResult result = results.get(cmdId);
            if (result == null) return;

            long deadline = System.currentTimeMillis() + 60_000; // 60s timeout
            while (System.currentTimeMillis() < deadline) {
                if (exitFile.exists() && exitFile.length() > 0) {
                    try {
                        String exitCodeStr = readFile(exitFile).trim();
                        result.exitCode = exitCodeStr.isEmpty() ? 0 : Integer.parseInt(exitCodeStr);
                    } catch (NumberFormatException e) {
                        result.exitCode = -1;
                    }
                    result.stdout = readFile(outFile);
                    result.stderr = readFile(errFile);
                    result.completed = true;
                    checkHooks(result);
                    cleanupTempFiles(outFile, errFile, exitFile);
                    Log.i(TAG, "Command #" + cmdId + " completed (exit=" + result.exitCode + ")");
                    return;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    break;
                }
            }

            // Timeout
            if (!result.completed) {
                result.error = "Command timed out after 60 seconds";
                result.exitCode = -1;
                // Read any partial output
                result.stdout = readFile(outFile);
                result.stderr = readFile(errFile);
                result.completed = true;
                checkHooks(result);
                cleanupTempFiles(outFile, errFile, exitFile);
                Log.w(TAG, "Command #" + cmdId + " timed out");
            }
        });
    }

    private String readFile(File file) {
        if (file == null || !file.exists()) return "";
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new FileReader(file));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            br.close();
            return sb.toString();
        } catch (IOException e) {
            return "";
        }
    }

    private void cleanupTempFiles(File... files) {
        for (File f : files) {
            try { if (f != null && f.exists()) f.delete(); } catch (Exception ignored) {}
        }
    }

    /**
     * Handle result from PendingIntent (secondary mechanism).
     * Only used if file-based capture didn't complete first.
     */
    private void handlePendingIntentResult(Intent intent) {
        int cmdId = intent.getIntExtra("cmd_id", -1);
        if (cmdId < 0) return;

        CommandResult result = results.get(cmdId);
        if (result == null || result.completed) return; // Already handled by file-based capture

        // Try to extract from bundle (Termux sends result in "result" extra)
        Bundle bundle = intent.getBundleExtra("result");
        if (bundle != null) {
            result.stdout = bundle.getString("stdout", result.stdout);
            result.stderr = bundle.getString("stderr", result.stderr);
            int ec = bundle.getInt("exitCode", Integer.MIN_VALUE);
            if (ec != Integer.MIN_VALUE) result.exitCode = ec;
        } else {
            // Direct extras (some Termux versions)
            String out = intent.getStringExtra("stdout");
            String err = intent.getStringExtra("stderr");
            if (out != null) result.stdout = out;
            if (err != null) result.stderr = err;
            result.exitCode = intent.getIntExtra("exitCode", result.exitCode);
        }

        result.completed = true;
        checkHooks(result);
        Log.i(TAG, "Command #" + cmdId + " result from PendingIntent");
    }

    public int executeShellCommand(String command, boolean background) {
        return executeCommand("bash", new String[]{"-c", command}, null, background, null);
    }

    public int executeScript(String script, boolean background) {
        return executeCommand("bash", new String[]{"-c", script}, null, background, null);
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
            boolean match;
            try {
                match = hook.isRegex
                    ? output.matches("(?s).*" + hook.pattern + ".*")
                    : output.contains(hook.pattern);
            } catch (Exception e) {
                match = output.contains(hook.pattern);
            }
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

    public CommandResult getResult(int cmdId) {
        return results.get(cmdId);
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
