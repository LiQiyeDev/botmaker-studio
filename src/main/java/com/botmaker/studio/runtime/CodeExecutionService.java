package com.botmaker.studio.runtime;

import com.botmaker.shared.ipc.IpcEnv;
import com.botmaker.shared.ipc.TelemetryServer;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.FileRole;
import com.botmaker.studio.project.LockedRegions;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.util.ClassPathManager;
import com.botmaker.studio.validation.DiagnosticsManager;
import javafx.application.Platform;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class CodeExecutionService {

    private final DiagnosticsManager diagnosticsManager;
    private final ProjectConfig config;
    private final ProjectState state;
    private final EventBus eventBus;

    private volatile Process currentRunningProcess;
    private volatile ScheduledExecutorService activeUiUpdater;
    private volatile TelemetryServer telemetryServer;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    private static final int MAX_UI_BUFFER_SIZE = 4096;
    private static final int UI_UPDATE_RATE_MS = 100;

    public CodeExecutionService(
            DiagnosticsManager diagnosticsManager,
            ProjectConfig config,
            ProjectState state,
            EventBus eventBus) {
        this.diagnosticsManager = diagnosticsManager;
        this.config = config;
        this.state = state;
        this.eventBus = eventBus;
        setupEventHandlers();
    }

    private void setupEventHandlers() {
        eventBus.subscribe(CoreApplicationEvents.CompilationRequestedEvent.class,
                e -> compileCode(state.getCurrentCode()), false);
        eventBus.subscribe(CoreApplicationEvents.ExecutionRequestedEvent.class,
                e -> runCode(state.getCurrentCode()), false);
        eventBus.subscribe(CoreApplicationEvents.StopRunRequestedEvent.class,
                e -> stopRunningProgram(), false);
        eventBus.subscribe(CoreApplicationEvents.SendInputEvent.class,
                e -> sendInput(e.text()), false);
    }

    /** Writes a line to the running program's stdin (used by the input popup). Echoes it to the console too. */
    public void sendInput(String line) {
        Process process = currentRunningProcess;
        if (process == null || !process.isAlive()) return;
        try {
            OutputStream stdin = process.getOutputStream();
            stdin.write((line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            stdin.flush();
            Platform.runLater(() -> eventBus.publish(new CoreApplicationEvents.OutputAppendedEvent(line + "\n")));
        } catch (IOException ignored) {}
    }

    private void status(String message) {
        Platform.runLater(() -> eventBus.publish(new CoreApplicationEvents.StatusMessageEvent(message)));
    }

    public void runCode(String currentEditorCode) {
        // Pre-compile block validation: an unfilled argument/condition (a red "Select Expression…" slot) would
        // only surface as a raw javac error. Detect it via BlockValidator, surface it in the Errors panel, and
        // abort before compiling. Always publish (empty list clears any previously shown empty-slot errors).
        List<org.eclipse.lsp4j.Diagnostic> emptySlotIssues = diagnosticsManager.validateBlocks();
        eventBus.publish(new CoreApplicationEvents.DiagnosticsUpdatedEvent(emptySlotIssues));
        if (!emptySlotIssues.isEmpty()) {
            status("Run aborted: fill in the highlighted empty value(s) — see the Errors tab.");
            return;
        }
        if (diagnosticsManager.hasErrors()) {
            status("Run aborted due to errors.");
            return;
        }
        if (isRunning.get()) {
            status("Program is already running. Stop it first.");
            return;
        }

        new Thread(() -> {
            try {
                status("Compiling...");
                Platform.runLater(() -> eventBus.publish(new CoreApplicationEvents.OutputClearedEvent()));

                if (!compileAndWait(currentEditorCode, config.compiledOutputPath())) {
                    status("Run aborted due to build failure.");
                    return;
                }

                status("Running... (Press Stop to terminate)");
                isRunning.set(true);

                // Tell UI the program has started so the Stop button becomes clickable.
                eventBus.publish(new CoreApplicationEvents.ProgramStartedEvent());

                // Run the compiled main class directly: java -cp <classes:deps> <mainClass>
                ProcessBuilder pb = new ProcessBuilder(
                        config.javaExecutable(),
                        "-cp", buildRuntimeClasspath(),
                        config.mainClassName())
                        .directory(config.projectPath().toFile());

                startTelemetry(pb);
                currentRunningProcess = pb.start();

                OutputPump pump = startProcessOutputReaders(currentRunningProcess);

                int exitCode = currentRunningProcess.waitFor();

                // Drain the readers and flush anything still buffered before the updater is torn down,
                // otherwise short-lived programs lose all output that arrived in the last <100ms.
                for (Thread reader : pump.readers()) reader.join();
                pump.flushRemaining().run();

                if (exitCode == 0) status("Program completed successfully.");
                else if (exitCode == 143 || exitCode == 130 || exitCode == 1 || exitCode == -1)
                    status("Program stopped.");
                else status("Program exited with code: " + exitCode);

            } catch (InterruptedException e) {
                status("Program stopped by user.");
            } catch (Exception e) {
                e.printStackTrace();
                status("Error: " + e.getMessage());
            } finally {
                isRunning.set(false);
                currentRunningProcess = null;
                stopUiUpdater();
                stopTelemetry();
                // Tell UI the program has finished so the Stop button disables.
                eventBus.publish(new CoreApplicationEvents.ProgramStoppedEvent());
            }
        }, "CodeRunner").start();
    }

    /** Builds {@code <compiledOutput><sep><resources><sep><dep jars...>} for launching/compiling the project. */
    private String buildRuntimeClasspath() {
        StringBuilder cp = new StringBuilder(config.compiledOutputPath().toString());
        // Put src/main/resources on the classpath so generated code can read /activities.json (and other
        // bundled resources) at runtime, mirroring Maven's resource-on-classpath semantics.
        cp.append(java.io.File.pathSeparator).append(config.resourcesRoot().toString());
        if (state.getResolvedClasspath() != null) {
            for (String jar : state.getResolvedClasspath()) {
                cp.append(java.io.File.pathSeparator).append(jar);
            }
        }
        return cp.toString();
    }

    public void compileCode(String code) {
        new Thread(() -> {
            try {
                Platform.runLater(() -> eventBus.publish(new CoreApplicationEvents.OutputClearedEvent()));
                status("Compiling...");
                if (compileAndWait(code, config.compiledOutputPath())) {
                    status("Compilation successful.");
                }
            } catch (IOException | InterruptedException e) {
                status("Compilation Error: " + e.getMessage());
            }
        }).start();
    }

    public boolean compileAndWait(String currentActiveCode, Path compiledOutputPath) throws IOException, InterruptedException {
        state.setCurrentCode(currentActiveCode);

        // This loop is the ONLY place edited source reaches disk — the editor keeps every change in memory
        // (ProjectFile.setContent) and never writes as you type.
        //
        // It used to skip GENERATED files wholesale. That is now wrong in both directions: a generated file can
        // hold an editable method (GameLoop.run is the file's whole reason to exist), so skipping it silently
        // throws away the user's game-loop body on every compile. What must not reach disk is a change to the
        // *locked parts* — so that, and only that, is what's checked. This is defense in depth: CodeEditor
        // already refuses those edits, so a refusal here means something upstream let one through.
        for (ProjectFile file : state.getAllFiles()) {
            Path path = file.getPath();
            if (path == null) continue;

            FileRole role = FileRole.of(config, state.getTemplate(), path);
            if (role == FileRole.LIBRARY) continue;   // never ours to write

            if (role.isReadOnly() && !lockedPartsUnchanged(path, file)) {
                eventBus.publish(new CoreApplicationEvents.StatusMessageEvent(
                        "Refused to save " + path.getFileName() + ": it changes code BotMaker generates."));
                continue;
            }

            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getContent());
        }

        Files.createDirectories(compiledOutputPath);
        return compileSources(compiledOutputPath);
    }

    /**
     * True when {@code file}'s in-memory content changes nothing outside the methods the user owns, so it is
     * safe to write over the scaffolding on disk. A file not yet on disk has no locked parts to protect.
     */
    private boolean lockedPartsUnchanged(Path path, ProjectFile file) {
        try {
            if (!Files.exists(path)) return true;
            return LockedRegions.lockedPartsMatch(
                    config, state.getTemplate(), path, Files.readString(path), file.getContent());
        } catch (IOException e) {
            return false;   // can't prove it's safe: don't write
        }
    }

    private boolean compileSources(Path compiledOutputPath) throws IOException, InterruptedException {

        List<String> sourceFiles = ClassPathManager.findJavaFiles(config.sourceRoot());
        if (sourceFiles.isEmpty()) {
            Platform.runLater(() -> eventBus.publish(new CoreApplicationEvents.OutputAppendedEvent("No source files to compile.\n")));
            return false;
        }

        List<String> command = new ArrayList<>(List.of(
                config.javacExecutable(),
                "-cp", buildRuntimeClasspath(),
                "-d", compiledOutputPath.toString()));
        command.addAll(sourceFiles);

        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(config.projectPath().toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String finalLine = line + "\n";
                Platform.runLater(() -> eventBus.publish(new CoreApplicationEvents.OutputAppendedEvent(finalLine)));
            }
        }

        int exitCode = process.waitFor();
        return exitCode == 0;
    }

    public void stopRunningProgram() {
        if (currentRunningProcess != null && currentRunningProcess.isAlive()) {
            currentRunningProcess.destroyForcibly();

            // Also attempt to kill child processes (like the actual Java process spawned by Gradle)
            currentRunningProcess.descendants().forEach(ProcessHandle::destroyForcibly);
        }
        stopUiUpdater();
        stopTelemetry();
        // Force state update immediately on hard kill
        isRunning.set(false);
        eventBus.publish(new CoreApplicationEvents.ProgramStoppedEvent());
    }

    /**
     * Starts the loopback telemetry server (for the live window-preview panel) and passes its ephemeral port
     * + a random session token to the bot via environment. Best-effort: if it fails to bind, the bot still
     * runs, just without a preview (and the SDK opens no socket when the env vars are absent).
     */
    private void startTelemetry(ProcessBuilder pb) {
        stopTelemetry();
        try {
            String token = java.util.UUID.randomUUID().toString();
            TelemetryServer server = new TelemetryServer(token,
                    feedback -> Platform.runLater(() ->
                            eventBus.publish(new CoreApplicationEvents.ViewFeedbackEvent(feedback))),
                    reason -> Platform.runLater(() -> eventBus.publish(new CoreApplicationEvents.OutputAppendedEvent(
                            "⚠ Live preview/overlays unavailable: the bot's SDK sends telemetry this Studio can't "
                            + "read (" + reason + "). Pick a current SDK build in Project ▸ Manage Libraries and re-run.\n"))));
            this.telemetryServer = server;
            pb.environment().put(IpcEnv.PORT, String.valueOf(server.port()));
            pb.environment().put(IpcEnv.TOKEN, token);
        } catch (IOException e) {
            System.err.println("Telemetry server failed to start: " + e.getMessage());
        }
    }

    private void stopTelemetry() {
        TelemetryServer server = telemetryServer;
        if (server != null) {
            server.close();
            telemetryServer = null;
        }
    }

    private void stopUiUpdater() {
        if (activeUiUpdater != null) {
            activeUiUpdater.shutdownNow();
            activeUiUpdater = null;
        }
    }

    public boolean isRunning() { return isRunning.get(); }

    /**
     * The OS pid of the running bot JVM, if one is alive. The bot is launched directly as
     * {@code java -cp … <mainClass>} (no wrapper process), so the launched process <em>is</em> the bot —
     * usable for out-of-band control such as the pilot's {@code SIGSTOP}/{@code SIGCONT} pause/resume.
     */
    public java.util.OptionalLong runningBotPid() {
        Process p = currentRunningProcess;
        return (p != null && p.isAlive()) ? java.util.OptionalLong.of(p.pid()) : java.util.OptionalLong.empty();
    }

    /** Handle over the live output readers and a final synchronous flush of whatever they buffered. */
    private record OutputPump(List<Thread> readers, Runnable flushRemaining) {}

    private OutputPump startProcessOutputReaders(Process process) {
        final StringBuilder buffer = new StringBuilder();

        stopUiUpdater();
        activeUiUpdater = Executors.newSingleThreadScheduledExecutor();

        activeUiUpdater.scheduleAtFixedRate(() -> {
            flushBuffer(buffer);
            if (!isRunning.get()) activeUiUpdater.shutdown();
        }, UI_UPDATE_RATE_MS, UI_UPDATE_RATE_MS, TimeUnit.MILLISECONDS);

        // Only stdout carries the BM-INPUT marker the SDK emits before blocking on a read.
        Thread out = new Thread(() -> readStream(process.getInputStream(), buffer, true), "Leaky-Reader-Out");
        Thread err = new Thread(() -> readStream(process.getErrorStream(), buffer, false), "Leaky-Reader-Err");
        out.start();
        err.start();

        return new OutputPump(List.of(out, err), () -> flushBuffer(buffer));
    }

    private void readStream(InputStream stream, StringBuilder buffer, boolean detectMarkers) {
        byte[] readBuf = new byte[1024];
        int len;
        StringBuilder carry = new StringBuilder();
        try {
            while ((len = stream.read(readBuf)) != -1) {
                String text = new String(readBuf, 0, len, StandardCharsets.UTF_8);
                if (detectMarkers) text = extractInputMarkers(text, carry);
                appendToBuffer(buffer, text);
            }
        } catch (IOException ignored) {}
    }

    private void appendToBuffer(StringBuilder buffer, String text) {
        if (text.isEmpty()) return;
        synchronized (buffer) {
            if (buffer.length() < MAX_UI_BUFFER_SIZE) {
                buffer.append(text);
            } else {
                String warning = "\n[output truncated...]\n";
                if (buffer.length() < MAX_UI_BUFFER_SIZE + warning.length()) {
                    buffer.append(warning);
                }
            }
        }
    }

    /**
     * Strips {@code \u0001BM-INPUT:<type>\u0001} markers the SDK prints right before a blocking read, publishing an
     * {@link CoreApplicationEvents.InputRequestedEvent} for each. {@code carry} holds a trailing partial marker that
     * was split across reads so it can be completed by the next chunk; everything else is returned for the console.
     */
    private String extractInputMarkers(String text, StringBuilder carry) {
        String s = carry.toString() + text;
        carry.setLength(0);
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int start = s.indexOf('\u0001', i);
            if (start < 0) { out.append(s, i, s.length()); break; }
            out.append(s, i, start);
            int end = s.indexOf('\u0001', start + 1);
            if (end < 0) { carry.append(s, start, s.length()); break; } // incomplete marker — hold for next chunk
            String token = s.substring(start + 1, end);
            i = end + 1;
            if (token.startsWith("BM-INPUT:")) {
                String type = token.substring("BM-INPUT:".length());
                Platform.runLater(() -> eventBus.publish(new CoreApplicationEvents.InputRequestedEvent(type)));
                if (i < s.length() && s.charAt(i) == '\n') i++; // swallow the marker's own newline
            } else {
                out.append('\u0001').append(token).append('\u0001'); // not ours — leave untouched
            }
        }
        return out.toString();
    }

    private void flushBuffer(StringBuilder buffer) {
        String textToSend;
        synchronized (buffer) {
            if (buffer.isEmpty()) return;
            textToSend = buffer.toString();
            buffer.setLength(0);
        }
        Platform.runLater(() -> eventBus.publish(new CoreApplicationEvents.OutputAppendedEvent(textToSend)));
    }
}
