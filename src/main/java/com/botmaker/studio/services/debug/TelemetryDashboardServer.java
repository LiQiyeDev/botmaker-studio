package com.botmaker.studio.services.debug;

import com.botmaker.shared.ipc.TelemetryEvent;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.services.ProjectSettingsService;
import com.botmaker.studio.services.pilot.TargetCapture;
import com.botmaker.studio.services.pilot.TelemetrySerializer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A local, opt-in debug dashboard that streams <em>everything the running bot sends over telemetry</em>
 * (the decoded {@link TelemetryEvent}s Studio already republishes as
 * {@link CoreApplicationEvents.ViewFeedbackEvent}) to a browser page over Server-Sent Events, alongside a
 * periodically-captured live frame of the target surface with the same overlays drawn on it.
 *
 * <p>Started on demand (View ▸ Open Debug Dashboard), it binds an ephemeral loopback port and opens the
 * default browser. Serving is entirely self-contained — one HTML page, no external assets — via the JDK's
 * {@link HttpServer}; there is zero overhead until it is opened.
 */
public final class TelemetryDashboardServer {

    private static final int FRAME_FPS = 4;
    /** SSE comment ping cadence — keeps the browser EventSource from erroring/flapping between telemetry events. */
    private static final int KEEPALIVE_SECONDS = 15;

    private final EventBus eventBus;
    private final TargetCapture capture;
    private final List<OutputStream> clients = new CopyOnWriteArrayList<>();

    private HttpServer server;
    private ScheduledExecutorService frameExec;
    private volatile TelemetryEvent.Target lastTarget;
    private volatile int port;

    public TelemetryDashboardServer(EventBus eventBus, ProjectSettingsService settings) {
        this.eventBus = eventBus;
        this.capture = new TargetCapture(settings);
    }

    /** Starts the server (idempotent) and returns the base URL to open. */
    public synchronized String startAndGetUrl() throws IOException {
        if (server == null) {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handlePage);
            server.createContext("/events", this::handleEvents);
            server.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "telemetry-dashboard");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            port = server.getAddress().getPort();

            eventBus.subscribe(CoreApplicationEvents.ViewFeedbackEvent.class, e -> onEvent(e.feedback()), false);
            startFrameLoop();
        }
        return "http://127.0.0.1:" + port + "/";
    }

    public synchronized void stop() {
        if (frameExec != null) { frameExec.shutdownNow(); frameExec = null; }
        for (OutputStream os : clients) closeQuietly(os);
        clients.clear();
        if (server != null) { server.stop(0); server = null; }
    }

    // --- Event fan-out ---

    private void onEvent(TelemetryEvent te) {
        if (te == null) return;
        this.lastTarget = te.target();
        broadcast("telemetry", TelemetrySerializer.eventJson(te));
    }

    private void broadcast(String event, String data) {
        byte[] payload = ("event: " + event + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8);
        for (OutputStream os : clients) {
            try {
                os.write(payload);
                os.flush();
            } catch (IOException e) {
                clients.remove(os);
                closeQuietly(os);
            }
        }
    }

    // --- Frame loop (captures the current target + pushes as base64 JPEG) ---

    private void startFrameLoop() {
        frameExec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "telemetry-dashboard-frame");
            t.setDaemon(true);
            return t;
        });
        frameExec.scheduleAtFixedRate(this::pushFrame, 500, 1000 / FRAME_FPS, TimeUnit.MILLISECONDS);
        // Independent of frames (which can stall, e.g. Wayland whole-screen capture), keep the SSE stream warm
        // so the browser doesn't fire onerror and flip the dashboard to a spurious "disconnected".
        frameExec.scheduleAtFixedRate(this::sendKeepalive, KEEPALIVE_SECONDS, KEEPALIVE_SECONDS, TimeUnit.SECONDS);
    }

    /** Writes an SSE comment to every open client so the connection stays alive between events. */
    private void sendKeepalive() {
        if (clients.isEmpty()) return;
        byte[] ping = ": keepalive\n\n".getBytes(StandardCharsets.UTF_8);
        for (OutputStream os : clients) {
            try {
                os.write(ping);
                os.flush();
            } catch (IOException e) {
                clients.remove(os);
                closeQuietly(os);
            }
        }
    }

    private void pushFrame() {
        if (clients.isEmpty()) return;
        TargetCapture.Capture cap = capture.resolve(lastTarget);
        if (cap == null) return;
        String b64 = TargetCapture.base64Jpeg(cap.img());
        if (b64 == null) return;
        broadcast("frame", String.format(
                "{\"img\":\"%s\",\"sx\":%d,\"sy\":%d,\"sw\":%d,\"sh\":%d}",
                b64, cap.sx(), cap.sy(), cap.sw(), cap.sh()));
    }

    // --- HTTP handlers ---

    private void handleEvents(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.getResponseHeaders().add("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);
        OutputStream os = ex.getResponseBody();
        os.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
        os.flush();
        clients.add(os); // kept open; the frame loop / event fan-out write to it
    }

    private void handlePage(HttpExchange ex) throws IOException {
        byte[] body = PAGE.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private static void closeQuietly(OutputStream os) {
        try { os.close(); } catch (IOException ignored) {}
    }

    // --- The self-contained dashboard page ---

    private static final String PAGE = """
            <!doctype html><html><head><meta charset="utf-8"><title>BotMaker Telemetry</title>
            <style>
              :root{color-scheme:dark}
              body{margin:0;font:13px/1.4 system-ui,sans-serif;background:#0f1115;color:#e8eaed;display:flex;height:100vh}
              #left{flex:1;min-width:0;display:flex;flex-direction:column;border-right:1px solid #23262d}
              #right{width:44%;max-width:760px;display:flex;flex-direction:column}
              header{padding:8px 12px;background:#151821;border-bottom:1px solid #23262d;font-weight:600}
              #status{font-weight:400;color:#8b93a1;margin-left:8px}
              table{border-collapse:collapse;width:100%;font-variant-numeric:tabular-nums}
              th,td{text-align:left;padding:4px 8px;border-bottom:1px solid #1c1f27;white-space:nowrap}
              th{position:sticky;top:0;background:#151821;color:#9aa0a6;font-weight:600}
              #log{overflow:auto;flex:1}
              .k-Match{color:#2ecc71}.k-Match.miss{color:#e74c3c}.k-Click{color:#3498db}.k-Region{color:#f1c40f}
              #stage{flex:1;position:relative;background:#000;overflow:hidden}
              canvas{position:absolute;inset:0;width:100%;height:100%}
              #hint{padding:8px 12px;color:#8b93a1;border-top:1px solid #23262d}
              #ctl{display:flex;gap:6px;padding:6px 12px;background:#151821;border-bottom:1px solid #23262d}
              #ctl button{background:#222634;color:#e8eaed;border:1px solid #2c3140;border-radius:6px;
                padding:4px 11px;cursor:pointer;font-size:14px;line-height:1}
              #ctl button:hover{background:#2b3040}
              #ctl button.on{background:#2f6fed;border-color:#2f6fed;color:#fff}
            </style></head><body>
            <div id="left">
              <header>Telemetry <span id="status">connecting…</span></header>
              <div id="log"><table><thead><tr><th>time</th><th>kind</th><th>target</th><th>rect</th><th>conf</th></tr></thead><tbody id="rows"></tbody></table></div>
            </div>
            <div id="right">
              <header>Live target + overlays <span id="res" style="font-weight:400;color:#8b93a1;margin-left:8px"></span></header>
              <div id="ctl">
                <button id="zout" title="Zoom out">－</button>
                <button id="zin" title="Zoom in">＋</button>
                <button id="fit" title="Fit">⤢</button>
                <button id="follow" title="Follow found object">⌖</button>
              </div>
              <div id="stage"><canvas id="cv"></canvas></div>
              <div id="hint">Frames are captured non-intrusively and show the project's default target. Window targets need no permission; whole-screen capture may be limited on Wayland.</div>
            </div>
            <script>
              const rows=document.getElementById('rows'),status=document.getElementById('status');
              const cv=document.getElementById('cv'),ctx=cv.getContext('2d');
              let frame=null,overlays=[];
              // View state: zoom multiplier over fit, pan offset (canvas px), follow-last-match toggle.
              let zoom=1,panx=0,pany=0,follow=false,lastRect=null;
              const MAXZOOM=12;
              const es=new EventSource('/events');
              es.onopen=()=>status.textContent='connected';
              // EventSource auto-reconnects; a transient hiccup is 'reconnecting…', not a hard 'disconnected'.
              es.onerror=()=>status.textContent=(es.readyState===2?'disconnected':'reconnecting…');
              es.addEventListener('telemetry',ev=>{
                const d=JSON.parse(ev.data);
                addRow(d); pushOverlay(d);
                if(d.kind==='Match'&&d.found&&d.rect) lastRect=d.rect;
              });
              es.addEventListener('frame',ev=>{ const d=JSON.parse(ev.data); const im=new Image();
                im.onload=()=>{frame={im,sx:d.sx,sy:d.sy,sw:d.sw,sh:d.sh};
                  document.getElementById('res').textContent=d.sw+'×'+d.sh;draw();}; im.src='data:image/jpeg;base64,'+d.img; });
              // View controls (mirror the Studio preview: zoom out / in / fit / follow).
              const zoutB=document.getElementById('zout'),zinB=document.getElementById('zin'),
                    fitB=document.getElementById('fit'),followB=document.getElementById('follow');
              zoutB.onclick=()=>{zoom=Math.max(1,zoom*0.8);if(zoom===1){panx=0;pany=0;}draw();};
              zinB.onclick=()=>{zoom=Math.min(MAXZOOM,zoom*1.25);draw();};
              fitB.onclick=()=>{zoom=1;panx=0;pany=0;follow=false;followB.classList.remove('on');draw();};
              followB.onclick=()=>{follow=!follow;followB.classList.toggle('on',follow);
                if(!follow){zoom=1;panx=0;pany=0;}draw();};
              // Drag to pan when zoomed in.
              let drag=null;
              cv.addEventListener('mousedown',e=>{if(zoom>1)drag={x:e.clientX,y:e.clientY,px:panx,py:pany};});
              addEventListener('mouseup',()=>drag=null);
              addEventListener('mousemove',e=>{if(!drag)return;panx=drag.px+(e.clientX-drag.x);pany=drag.py+(e.clientY-drag.y);draw();});
              function addRow(d){
                const tr=document.createElement('tr');
                const cls='k-'+d.kind+(d.kind==='Match'&&!d.found?' miss':'');
                const rect=d.rect?`${d.rect.x},${d.rect.y} ${d.rect.w}×${d.rect.h}`:(d.x!=null?`@${d.x},${d.y}`:'');
                const tgt=d.target?(d.target.title||'screen'):'';
                tr.innerHTML=`<td>${new Date(d.ts).toLocaleTimeString()}</td><td class="${cls}">${d.kind}</td><td>${tgt}</td><td>${rect}</td><td>${d.confidence??''}</td>`;
                rows.prepend(tr); while(rows.children.length>400) rows.lastChild.remove();
              }
              function pushOverlay(d){ d._exp=Date.now()+1200; overlays.push(d); if(overlays.length>40)overlays.shift(); draw(); }
              function draw(){
                const r=cv.getBoundingClientRect(); cv.width=r.width; cv.height=r.height;
                ctx.clearRect(0,0,cv.width,cv.height);
                if(!frame)return;
                const base=Math.min(cv.width/frame.sw,cv.height/frame.sh),s=base*zoom;
                const dw=frame.sw*s,dh=frame.sh*s;
                let ox=(cv.width-dw)/2+panx,oy=(cv.height-dh)/2+pany;
                // Follow mode: center the last found rect in the view.
                if(follow&&lastRect){
                  const cx=(lastRect.x-frame.sx+lastRect.w/2)*s,cy=(lastRect.y-frame.sy+lastRect.h/2)*s;
                  ox=cv.width/2-cx; oy=cv.height/2-cy;
                }
                ctx.drawImage(frame.im,ox,oy,dw,dh);
                const now=Date.now();
                overlays=overlays.filter(o=>o._exp>now);
                for(const o of overlays){
                  const a=Math.max(0,(o._exp-now)/1200);
                  if(o.region){box(o.region,`rgba(241,196,15,${a*.7})`,1,s,ox,oy);}
                  if(o.rect){box(o.rect,o.kind==='Match'?(o.found?`rgba(46,204,113,${a})`:`rgba(231,76,60,${a})`):`rgba(241,196,15,${a})`,2,s,ox,oy);}
                  if(o.kind==='Click'&&o.x!=null){const px=ox+(o.x-frame.sx)*s,py=oy+(o.y-frame.sy)*s;
                    ctx.strokeStyle=`rgba(52,152,219,${a})`;ctx.lineWidth=2;ctx.beginPath();ctx.arc(px,py,8,0,7);ctx.stroke();}
                }
              }
              function box(rc,color,w,s,ox,oy){ctx.strokeStyle=color;ctx.lineWidth=w;
                ctx.strokeRect(ox+(rc.x-frame.sx)*s,oy+(rc.y-frame.sy)*s,rc.w*s,rc.h*s);}
              setInterval(draw,120); addEventListener('resize',draw);
            </script></body></html>
            """;
}
