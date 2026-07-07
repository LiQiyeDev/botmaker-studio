package com.botmaker.studio.services;

import com.botmaker.shared.ipc.IpcEnv;
import com.botmaker.shared.ipc.TelemetryServer;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.config.Constants;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.StatementBlock;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.runtime.CodeExecutionService;
import com.botmaker.studio.project.ProjectState;
import com.sun.jdi.*;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.event.*;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.StepRequest;
import javafx.application.Platform;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/**
 * Handles the entire debugging lifecycle:
 * 1. Mapping AST nodes to line numbers.
 * 2. Launching the JVM in debug mode.
 * 3. Attaching via JDI (Java Debug Interface).
 * 4. Managing Breakpoints, Stepping, and Resuming.
 */
public class DebuggingService {

    // Console Coloring for internal logs
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_BLUE = "\u001B[34m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_RED = "\u001B[31m";

    private final ProjectState state;
    private final EventBus eventBus;
    private final CodeExecutionService codeExecutionService;
    private final ProjectConfig config;

    // Debug Session State
    private volatile Process currentProcess;
    private VirtualMachine vm;
    private ThreadReference currentDebugThread;
    private Map<Integer, CodeBlock> lineToBlockMap;
    private volatile TelemetryServer telemetryServer;

    // "Follow" (trace) mode: attach like debug but auto-resume past every block, highlighting each live.
    private volatile boolean traceMode;

    // Highlight throttle (trace mode only). The JDI side resumes immediately; highlight repaints are
    // coalesced to at most one every HIGHLIGHT_THROTTLE_MS (trailing edge), so a tight loop pulses softly
    // instead of strobing. Mirrors the coalescing pattern in CodeExecutionService's activeUiUpdater.
    private static final long HIGHLIGHT_THROTTLE_MS = 130;
    private volatile CodeBlock pendingHighlight;
    private final java.util.concurrent.atomic.AtomicBoolean flushScheduled =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private java.util.concurrent.ScheduledExecutorService highlightScheduler;

    public DebuggingService(
            ProjectState state,
            EventBus eventBus,
            CodeExecutionService codeExecutionService,
            ProjectConfig config) {
        this.state = state;
        this.eventBus = eventBus;
        this.codeExecutionService = codeExecutionService;
        this.config = config;

        setupEventHandlers();
    }

    private void setupEventHandlers() {
        eventBus.subscribe(CoreApplicationEvents.DebugControlRequest.class, e -> {
            switch (e) {
                case CoreApplicationEvents.DebugStartRequestedEvent ignored -> startDebugging(false);
                case CoreApplicationEvents.FollowStartRequestedEvent ignored -> startDebugging(true);
                case CoreApplicationEvents.DebugStepOverRequestedEvent ignored -> stepOver();
                case CoreApplicationEvents.DebugContinueRequestedEvent ignored -> continueExecution();
                case CoreApplicationEvents.DebugStopRequestedEvent ignored -> stopDebugging();
            }
        }, false);
        eventBus.subscribe(CoreApplicationEvents.SendInputEvent.class, e -> sendInput(e.text()), false);
    }

    private static final java.util.regex.Pattern INPUT_MARKER =
            java.util.regex.Pattern.compile("\u0001BM-INPUT:([a-zA-Z]+)\u0001");

    /** Writes a line to the debuggee's stdin (used by the input popup) and echoes it to the console. */
    public void sendInput(String line) {
        Process process = currentProcess;
        if (process == null || !process.isAlive()) return;
        try {
            java.io.OutputStream stdin = process.getOutputStream();
            stdin.write((line + System.lineSeparator()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            stdin.flush();
            Platform.runLater(() -> eventBus.publish(new CoreApplicationEvents.OutputAppendedEvent(line + "\n")));
        } catch (IOException ignored) {}
    }

    /** Removes BM-INPUT markers from a line, publishing an input request for each; returns null if nothing remains. */
    private String stripInputMarkers(String line) {
        java.util.regex.Matcher m = INPUT_MARKER.matcher(line);
        StringBuffer sb = new StringBuffer();
        boolean found = false;
        while (m.find()) {
            found = true;
            String type = m.group(1);
            Platform.runLater(() -> eventBus.publish(new CoreApplicationEvents.InputRequestedEvent(type)));
            m.appendReplacement(sb, "");
        }
        m.appendTail(sb);
        return found && sb.length() == 0 ? null : sb.toString();
    }

    /**
     * Kicks off a debug ({@code trace=false}) or follow/trace ({@code trace=true}) session on a
     * separate thread. In trace mode user breakpoints are ignored and every mapped block is observed
     * and immediately resumed, so execution is followed live without ever pausing.
     */
    public void startDebugging(boolean trace) {
        this.traceMode = trace;
        new Thread(() -> {
            try {
                String code = state.getCurrentCode();

                // 1. Compile
                // FIXED: Removed config.mainSourceFile() argument to match new signature
                if (!codeExecutionService.compileAndWait(code, config.compiledOutputPath())) {
                    eventBus.publish(new CoreApplicationEvents.StatusMessageEvent("Debug aborted due to compilation failure."));
                    return;
                }

                // 2. Map Breakpoints (AST -> Line Numbers)
                CompilationUnit cu = state.getCompilationUnit().orElse(null);
                // Note: getNodeToBlockMap only refers to the ACTIVE file.
                // Multi-file debugging requires mapping logic expansion, but this works for the active file.
                if (cu == null || state.getNodeToBlockMap().isEmpty()) {
                    eventBus.publish(new CoreApplicationEvents.StatusMessageEvent("Error: Could not parse code to get breakpoints."));
                    return;
                }

                this.lineToBlockMap = new HashMap<>();
                List<Integer> activeBreakpointLines = new ArrayList<>();

                for (CodeBlock block : state.getNodeToBlockMap().values()) {
                    int line = block.getBreakpointLine(cu);
                    if (line > 0) {
                        // Only map StatementBlocks (executable lines)
                        if (!lineToBlockMap.containsKey(line) || block instanceof StatementBlock) {
                            lineToBlockMap.put(line, block);
                        }
                        // Trace mode ignores user breakpoints (following never stops); collect them only for debug.
                        if (!trace && block.isBreakpoint()) {
                            activeBreakpointLines.add(line);
                        }
                    }
                }

                if (trace) {
                    // Follow mode: observe EVERY mapped line so each executed block is highlighted, then resumed.
                    activeBreakpointLines.addAll(lineToBlockMap.keySet());
                } else if (activeBreakpointLines.isEmpty() && !lineToBlockMap.isEmpty()) {
                    // If no breakpoints, add one at start so it doesn't just run to finish immediately
                    lineToBlockMap.keySet().stream().min(Integer::compareTo).ifPresent(firstLine -> {
                        activeBreakpointLines.add(firstLine);
                        eventBus.publish(new CoreApplicationEvents.StatusMessageEvent("No breakpoints set. Pausing at start (Line " + firstLine + ")."));
                    });
                }

                // 3. Find Free Port
                int freePort;
                try (ServerSocket socket = new ServerSocket(0)) {
                    freePort = socket.getLocalPort();
                }

                // 4. Launch Target Process
                eventBus.publish(new CoreApplicationEvents.StatusMessageEvent("Starting debugger on port " + freePort + "..."));
                eventBus.publish(new CoreApplicationEvents.DebugSessionStartedEvent());
                Platform.runLater(() -> eventBus.publish(new CoreApplicationEvents.OutputClearedEvent()));

                String classPath = config.compiledOutputPath().toString();
                // We debug the Main class defined in config
                String className = config.mainClassName();
                String javaExecutable = config.javaExecutable();

                // Suspend=y waits for us to attach before running main()
                String debugAgent = String.format("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=%d", freePort);
                StringBuilder fullClassPath = new StringBuilder();
                fullClassPath.append(config.compiledOutputPath().toString());

                // src/main/resources on the classpath so generated code finds /activities.json at runtime.
                fullClassPath.append(java.io.File.pathSeparator).append(config.resourcesRoot().toString());

                // 2. Add all resolved dependency JARs from Gradle
                if (state.getResolvedClasspath() != null) {
                    for (String jarPath : state.getResolvedClasspath()) {
                        fullClassPath.append(java.io.File.pathSeparator).append(jarPath);
                    }
                }

                // Use the full classpath here
                ProcessBuilder pb = new ProcessBuilder(
                        javaExecutable,
                        debugAgent,
                        "-cp", fullClassPath.toString(),
                        className
                );
                startTelemetry(pb);
                this.currentProcess = pb.start();

                // Redirect output to UI
                redirectStream(currentProcess.getInputStream(), true);
                redirectStream(currentProcess.getErrorStream(), false);

                // 5. Attach JDI
                attachJdi(className, freePort, activeBreakpointLines);

            } catch (Exception e) {
                eventBus.publish(new CoreApplicationEvents.StatusMessageEvent("Debugger Error: " + e.getMessage()));
                e.printStackTrace();
                stopDebugging(); // Cleanup if fail
            }
        }).start();
    }

    /**
     * Connects the JDI VirtualMachine to the running process.
     */
    private void attachJdi(String mainClassName, int port, List<Integer> breakpointLines) throws Exception {
        VirtualMachineManager vmMgr = Bootstrap.virtualMachineManager();
        AttachingConnector connector = vmMgr.attachingConnectors().stream()
                .filter(c -> c.transport().name().equals("dt_socket"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Socket attaching connector not found"));

        Map<String, Connector.Argument> arguments = connector.defaultArguments();
        arguments.get("port").setValue(String.valueOf(port));
        arguments.get("hostname").setValue("localhost");

        // Retry logic for connection
        int maxRetries = Constants.DEBUGGER_MAX_CONNECT_RETRIES;
        for (int i = 0; i < maxRetries; i++) {
            try {
                vm = connector.attach(arguments);
                System.out.println(ANSI_BLUE + "Attached to VM: " + vm.name() + ANSI_RESET);
                break;
            } catch (IOException e) {
                if (i == maxRetries - 1) throw e;
                Thread.sleep(Constants.DEBUGGER_RETRY_DELAY_MS);
            }
        }

        EventRequestManager erm = vm.eventRequestManager();

        // Handle Breakpoints
        List<ReferenceType> classes = vm.classesByName(mainClassName);
        if (!classes.isEmpty()) {
            applyBreakpointsToClass(classes.getFirst(), breakpointLines);
        } else {
            ClassPrepareRequest classPrepareRequest = erm.createClassPrepareRequest();
            classPrepareRequest.addClassFilter(mainClassName);
            classPrepareRequest.enable();
        }

        // Start Event Loop
        CountDownLatch listenerReadyLatch = new CountDownLatch(1);
        new Thread(() -> jdiEventLoop(listenerReadyLatch, mainClassName, breakpointLines)).start();

        listenerReadyLatch.await();
        vm.resume();
    }

    private void jdiEventLoop(CountDownLatch listenerReadyLatch, String mainClassName, List<Integer> breakpointLines) {
        EventQueue eventQueue = vm.eventQueue();
        listenerReadyLatch.countDown();

        while (true) {
            try {
                EventSet eventSet = eventQueue.remove();
                boolean shouldResume = true;

                for (Event event : eventSet) {
                    if (event instanceof VMDisconnectEvent) {
                        handleDisconnect();
                        return;
                    }

                    if (event instanceof ClassPrepareEvent) {
                        ClassPrepareEvent cpe = (ClassPrepareEvent) event;
                        if (cpe.referenceType().name().equals(mainClassName)) {
                            applyBreakpointsToClass(cpe.referenceType(), breakpointLines);
                        }
                    }
                    else if (event instanceof LocatableEvent) {
                        handleLocatableEvent((LocatableEvent) event);
                        // Trace mode never pauses: fall through to eventSet.resume() and keep following.
                        if (!traceMode) shouldResume = false;
                    }
                }

                if (shouldResume) {
                    eventSet.resume();
                }
            } catch (InterruptedException | VMDisconnectedException e) {
                handleDisconnect();
                return;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void applyBreakpointsToClass(ReferenceType refType, List<Integer> lines) {
        if (lines == null || lines.isEmpty()) return;
        try {
            EventRequestManager erm = vm.eventRequestManager();
            for (int lineNumber : lines) {
                List<Location> locations = refType.locationsOfLine(lineNumber);
                if (!locations.isEmpty()) {
                    BreakpointRequest bpReq = erm.createBreakpointRequest(locations.getFirst());
                    bpReq.enable();
                }
            }
        } catch (AbsentInformationException e) {
            System.err.println("No debug info available (compiled without -g?).");
        }
    }

    private void handleLocatableEvent(LocatableEvent event) {
        this.currentDebugThread = event.thread();

        if (event instanceof StepEvent) {
            vm.eventRequestManager().deleteEventRequest(event.request());
        }

        int lineNumber = event.location().lineNumber();
        CodeBlock block = lineToBlockMap.get(lineNumber);
        CodeBlock target = (block != null) ? block.getHighlightTarget() : null;

        if (traceMode) {
            // Follow mode: highlight the block (throttled), never pause the UI. The event loop resumes.
            scheduleHighlight(target);
            return;
        }

        eventBus.publish(new CoreApplicationEvents.DebugSessionPausedEvent(lineNumber, target));
        eventBus.publish(new CoreApplicationEvents.BlockHighlightEvent(target));
        eventBus.publish(new CoreApplicationEvents.StatusMessageEvent("Paused at line: " + lineNumber));
    }

    /**
     * Coalesces highlight repaints in trace mode: keeps only the latest target and publishes a single
     * {@link CoreApplicationEvents.BlockHighlightEvent} per {@link #HIGHLIGHT_THROTTLE_MS} (trailing edge),
     * so a hot loop pulses softly instead of strobing. The JDI side has already resumed by the time this fires.
     */
    private void scheduleHighlight(CodeBlock target) {
        this.pendingHighlight = target;
        if (flushScheduled.compareAndSet(false, true)) {
            highlightScheduler().schedule(() -> {
                CodeBlock latest = this.pendingHighlight;
                flushScheduled.set(false);
                Platform.runLater(() -> eventBus.publish(new CoreApplicationEvents.BlockHighlightEvent(latest)));
            }, HIGHLIGHT_THROTTLE_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    /** Starts the loopback telemetry server for the preview panel and passes port+token to the debuggee. */
    private void startTelemetry(ProcessBuilder pb) {
        stopTelemetry();
        try {
            String token = java.util.UUID.randomUUID().toString();
            TelemetryServer server = new TelemetryServer(token, feedback ->
                    Platform.runLater(() -> eventBus.publish(new CoreApplicationEvents.ViewFeedbackEvent(feedback))));
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

    private synchronized java.util.concurrent.ScheduledExecutorService highlightScheduler() {
        if (highlightScheduler == null || highlightScheduler.isShutdown()) {
            highlightScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "follow-highlight-throttle");
                t.setDaemon(true);
                return t;
            });
        }
        return highlightScheduler;
    }

    private void handleDisconnect() {
        this.currentDebugThread = null;
        this.vm = null;
        this.currentProcess = null;

        synchronized (this) {
            if (highlightScheduler != null) {
                highlightScheduler.shutdownNow();
                highlightScheduler = null;
            }
        }
        flushScheduled.set(false);
        pendingHighlight = null;
        stopTelemetry();

        eventBus.publish(new CoreApplicationEvents.DebugSessionFinishedEvent());
        eventBus.publish(new CoreApplicationEvents.StatusMessageEvent("Debug session finished."));
        eventBus.publish(new CoreApplicationEvents.BlockHighlightEvent(null));
    }

    public void stepOver() {
        if (vm == null || currentDebugThread == null) return;
        try {
            eventBus.publish(new CoreApplicationEvents.DebugSessionResumedEvent());
            EventRequestManager erm = vm.eventRequestManager();

            erm.stepRequests().stream()
                    .filter(r -> r.thread().equals(currentDebugThread))
                    .forEach(erm::deleteEventRequest);

            StepRequest request = erm.createStepRequest(currentDebugThread, StepRequest.STEP_LINE, StepRequest.STEP_OVER);
            request.addCountFilter(1);
            request.enable();

            vm.resume();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void continueExecution() {
        if (vm != null) {
            eventBus.publish(new CoreApplicationEvents.DebugSessionResumedEvent());
            vm.resume();
        }
    }

    public void stopDebugging() {
        if (vm != null) {
            try {
                vm.dispose();
            } catch (VMDisconnectedException ignored) {
            } catch (Exception e) { e.printStackTrace(); }
        }

        if (currentProcess != null && currentProcess.isAlive()) {
            try {
                currentProcess.destroyForcibly();
                eventBus.publish(new CoreApplicationEvents.StatusMessageEvent("Debug process terminated."));
            } catch (Exception e) { e.printStackTrace(); }
        }

        handleDisconnect();
    }

    private void redirectStream(InputStream stream, boolean detectMarkers) {
        new Thread(() -> {
            try (Scanner s = new Scanner(stream)) {
                while (s.hasNextLine()) {
                    String line = s.nextLine();
                    if (detectMarkers) {
                        String cleaned = stripInputMarkers(line);
                        if (cleaned == null) continue; // pure marker line — nothing to show
                        line = cleaned;
                    }
                    String out = line + "\n";
                    Platform.runLater(() -> eventBus.publish(new CoreApplicationEvents.OutputAppendedEvent(out)));
                }
            }
        }).start();
    }
}