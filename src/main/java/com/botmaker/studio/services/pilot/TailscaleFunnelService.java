package com.botmaker.studio.services.pilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper over the local {@code tailscale} CLI that exposes {@link PilotServer}'s loopback HTTP port to
 * the public internet as {@code https://<machine>.<tailnet>.ts.net} via <b>Tailscale Funnel</b>, with a valid
 * Let's Encrypt certificate managed by Tailscale.
 *
 * <p>Why Funnel (not tailnet-only "serve"): the phone reaching the pilot needs <em>nothing</em> installed —
 * no Tailscale, no VPN — it just opens the public HTTPS URL. Tailscale terminates TLS and proxies to
 * {@code http://127.0.0.1:<port>}, so {@code PilotServer} can stay plain-HTTP on loopback and the
 * {@code ?token=} query param is the only guard (hence it must stay secret).
 *
 * <p>All methods are best-effort and never throw: a missing CLI, a Funnel not enabled in the tailnet ACL, or a
 * logged-out node all surface as {@link Result#ok()}{@code == false} with the CLI's stderr in
 * {@link Result#error()} so the UI can tell the user <em>why</em>.
 *
 * <p>Prerequisites (one-time, machine/admin side; documented in botmaker-pilot/README.md): Tailscale installed
 * and logged in, HTTPS certificates enabled for the tailnet, and the {@code funnel} node-attribute granted in
 * the tailnet ACL policy. Without the ACL grant {@code tailscale funnel} errors and we fall back to a direct
 * bind.
 */
public final class TailscaleFunnelService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Outcome of {@link #enable(int)}: the public {@code https://…} base on success, else the CLI error. */
    public record Result(boolean ok, String publicBase, String error) {
        static Result ok(String publicBase) { return new Result(true, publicBase, null); }
        static Result fail(String error) { return new Result(false, null, error); }
    }

    /** {@code true} if the {@code tailscale} CLI is present and responds to {@code version}. */
    public boolean isAvailable() {
        return run(List.of("tailscale", "version"), 5).exit == 0;
    }

    /**
     * The node's fully-qualified tailnet DNS name (e.g. {@code machine.tailnet-1234.ts.net}) parsed from
     * {@code tailscale status --json} → {@code Self.DNSName} (trailing dot trimmed), or empty if the CLI is
     * unavailable / the node is logged out / the field is missing.
     */
    public Optional<String> dnsName() {
        Exec e = run(List.of("tailscale", "status", "--json"), 8);
        if (e.exit != 0 || e.out.isBlank()) return Optional.empty();
        try {
            JsonNode name = JSON.readTree(e.out).path("Self").path("DNSName");
            if (name.isMissingNode() || !name.isTextual()) return Optional.empty();
            String dns = name.asText().trim();
            if (dns.endsWith(".")) dns = dns.substring(0, dns.length() - 1);
            return dns.isBlank() ? Optional.empty() : Optional.of(dns);
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    /**
     * Exposes {@code http://127.0.0.1:<localPort>} publicly on the tailnet's {@code :443} Funnel. On success
     * returns the {@code https://<dnsName>} base (no trailing slash); on failure returns the CLI stderr (e.g.
     * "Funnel is not enabled on your tailnet").
     */
    public Result enable(int localPort) {
        Optional<String> dns = dnsName();
        if (dns.isEmpty()) return Result.fail("Tailscale node has no DNS name (logged out?).");
        // `--bg` detaches the funnel so it keeps running after this CLI call returns; 443 → localhost:<port>.
        // A properly enabled+authorized funnel returns in well under a second. When Funnel isn't enabled on the
        // tailnet, `tailscale funnel` prints the reason and then blocks polling — so abort the moment a known
        // failure line appears (fail-fast markers) rather than waiting out the timeout, keeping the fallback
        // to a direct bind near-instant.
        Exec e = run(List.of("tailscale", "funnel", "--bg", String.valueOf(localPort)), 12,
                "is not enabled on your tailnet", "Access denied", "not logged in");
        if (e.exit != 0) {
            String msg = e.err.isBlank() ? e.out : e.err;
            return Result.fail(msg.isBlank() ? "tailscale funnel exited " + e.exit : msg.trim());
        }
        return Result.ok("https://" + dns.get());
    }

    /**
     * Tears down the Funnel started by {@link #enable(int)} via {@code tailscale funnel reset}. Best-effort,
     * never throws. (Note: the older {@code funnel --https=443 off} form <em>hangs</em> on current Tailscale
     * — it parses {@code off} as a serve target — so {@code reset} is used instead.)
     */
    public void disable() {
        run(List.of("tailscale", "funnel", "reset"), 10);
    }

    // --- process plumbing ---

    private record Exec(int exit, String out, String err) {}

    /**
     * Runs {@code cmd}, capturing stdout/stderr, bounded three ways: normal exit, any {@code failFast} marker
     * appearing in the output (kill immediately — for commands that print a reason then block), or the
     * timeout. The timeout/kill is enforced by polling + {@code destroyForcibly}, never by reading a stream
     * (an inline read would itself hang on a blocking child).
     */
    private static Exec run(List<String> cmd, int timeoutSeconds, String... failFast) {
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).start();
            // Close the child's stdin so a command that would prompt gets EOF instead of blocking on input.
            p.getOutputStream().close();
            StringBuffer out = new StringBuffer();   // thread-safe: pumped by one thread, polled by this one
            StringBuffer err = new StringBuffer();
            Thread outPump = pump(p.getInputStream(), out, "tailscale-stdout");
            Thread errPump = pump(p.getErrorStream(), err, "tailscale-stderr");

            long deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L;
            boolean exited = false;
            boolean failedFast = false;
            while (System.nanoTime() < deadline) {
                if (!p.isAlive()) { exited = true; break; }
                if (containsAny(out, err, failFast)) { failedFast = true; break; }
                Thread.sleep(100);
            }
            if (!exited) {
                p.destroyForcibly();
                p.waitFor(3, TimeUnit.SECONDS);
            }
            outPump.join(1000);
            errPump.join(1000);
            if (exited) return new Exec(p.exitValue(), out.toString(), err.toString());

            // Killed early (marker) or timed out: report a non-zero exit with the captured reason.
            String captured = (out + "\n" + err).trim();
            String reason = failedFast
                    ? (captured.isBlank() ? "funnel unavailable" : captured)
                    : (captured.isBlank() ? "" : captured + " — ") + "timed out after " + timeoutSeconds + "s";
            return new Exec(-1, out.toString(), reason);
        } catch (Exception ex) {
            if (p != null) p.destroyForcibly();
            // CLI missing (IOException) or interrupted → treat as unavailable.
            return new Exec(-1, "", ex.getMessage() == null ? ex.toString() : ex.getMessage());
        }
    }

    private static boolean containsAny(CharSequence out, CharSequence err, String[] markers) {
        if (markers.length == 0) return false;
        String o = out.toString(), e = err.toString();
        for (String m : markers) {
            if (o.contains(m) || e.contains(m)) return true;
        }
        return false;
    }

    /** Reads {@code in} line-by-line into {@code sink} so partial output is visible to the fail-fast poll. */
    private static Thread pump(InputStream in, StringBuffer sink, String name) {
        Thread t = new Thread(() -> {
            try (var br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sink.append(line).append('\n');
            } catch (Exception ignored) {
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }
}
