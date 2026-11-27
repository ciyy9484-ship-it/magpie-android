package ai.magpie.android;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Everything that touches the magpie binary on disk lives here. */
public final class Core {

    public static final String PKG = "ai.magpie.android";
    public static final int GW_PORT = 3425;
    public static final int WEB_PORT = 3430;
    public static final String REMOTE_DIR = "/data/local/tmp/magpie";
    public static final String ADB_DIR = "/data/adb/magpie";

    private Core() {}

    static {
        startProber();
    }

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("magpie", Context.MODE_PRIVATE);
    }

    public static File home(Context c) {
        return new File(c.getFilesDir(), "home");
    }

    public static File logFile(Context c) {
        return new File(c.getFilesDir(), "daemon.log");
    }

    public static File scriptFile(Context c) {
        return new File(c.getFilesDir(), "run-magpie.sh");
    }

    public static File pidFile(Context c) {
        return new File(c.getFilesDir(), "magpie.pid");
    }

    public static File wrapperPidFile(Context c) {
        return new File(c.getFilesDir(), "wrapper.pid");
    }

    public static File stopFlag(Context c) {
        return new File(c.getFilesDir(), "stop.flag");
    }

    public static File autoFlag(Context c) {
        return new File(c.getFilesDir(), "autostart_enabled");
    }

    public static File localBin(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir, "libmagpie-core.so");
    }

    public static String remoteBin() {
        return REMOTE_DIR + "/magpie";
    }

    /** The bundled magpie version, shown in the UI. */
    public static String versionName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    // ---------------------------------------------------------------- web key

    public static String webKey(Context c) {
        SharedPreferences p = prefs(c);
        String k = p.getString("webKey", null);
        if (k == null || k.length() < 16) {
            byte[] raw = new byte[16];
            new SecureRandom().nextBytes(raw);
            StringBuilder sb = new StringBuilder();
            for (byte b : raw) sb.append(String.format(Locale.US, "%02x", b));
            k = sb.toString();
            p.edit().putString("webKey", k).apply();
        }
        return k;
    }

    /** Optional HTTP proxy (the phone's local clash etc.): used by the daemon and by updates. */
    public static String proxy(Context c) {
        return prefs(c).getString("proxy", "");
    }

    public static boolean lan(Context c) {
        return prefs(c).getBoolean("lan", false);
    }

    public static String webUrl(Context c) {
        String host = lan(c) ? lanHost() : "127.0.0.1";
        return "http://" + host + ":" + WEB_PORT + "/?k=" + webKey(c);
    }

    public static String gatewayUrl(Context c) {
        return (lan(c) ? lanHost() : "127.0.0.1") + ":" + GW_PORT;
    }

    private static String lanHost() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> e =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (e.hasMoreElements()) {
                java.net.NetworkInterface nif = e.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                String n = nif.getName();
                if (n == null || !(n.startsWith("wlan") || n.startsWith("eth"))) continue;
                java.util.Enumeration<java.net.InetAddress> a = nif.getInetAddresses();
                while (a.hasMoreElements()) {
                    java.net.InetAddress ia = a.nextElement();
                    if (ia instanceof java.net.Inet4Address && !ia.isLoopbackAddress()) {
                        return ia.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    // ------------------------------------------------------------- run script

    /** The shell script that keeps magpie alive; regenerated on every change. */
    public static String buildScript(Context c, boolean rootMode) {
        boolean lan = lan(c);
        String bin = rootMode ? remoteBin() : localBin(c).getAbsolutePath();
        String webAddr = (lan ? "0.0.0.0" : "127.0.0.1") + ":" + WEB_PORT;
        String gwAddr = (lan ? "0.0.0.0" : "127.0.0.1") + ":" + GW_PORT;
        String dir = c.getFilesDir().getAbsolutePath();

        StringBuilder s = new StringBuilder();
        s.append("#!/system/bin/sh\n");
        s.append("# generated by magpie for android, do not edit\n");
        s.append("PKG_DIR=").append(dir).append("\n");
        s.append("export HOME=\"$PKG_DIR/home\"\n");
        s.append("export XDG_CONFIG_HOME=\"$HOME/.config\"\n");
        s.append("export XDG_CACHE_HOME=\"$HOME/.cache\"\n");
        s.append("export MAGPIE_WEB_KEY=").append(webKey(c)).append("\n");
        s.append("export MAGPIE_ADDR=").append(gwAddr).append("\n");
        s.append("export MAGPIE_PROXY=").append(shq(safeProxy(c))).append("\n");
        s.append("export DO_NOT_TRACK=");
        s.append(prefs(c).getBoolean("telemetry", true) ? "0" : "1");
        s.append("\n");
        s.append("export PATH=/system/bin:/system/xbin:/data/adb/magisk:/data/adb/ksu/bin:$PATH\n");
        s.append("export TMPDIR=\"$PKG_DIR/tmp\"\n");
        if (!safeProxy(c).isEmpty()) {
            s.append("export HTTP_PROXY=\"$MAGPIE_PROXY\"\n");
            s.append("export HTTPS_PROXY=\"$MAGPIE_PROXY\"\n");
        }
        // 守护以 root 跑：它写出来的东西绝不能落在别人能改的目录里
        s.append("umask 077\n");
        s.append("mkdir -p \"$XDG_CONFIG_HOME\" \"$XDG_CACHE_HOME\" \"$TMPDIR\"\n");
        s.append("LOG=\"$PKG_DIR/daemon.log\"\n");
        if (rootMode) {
            s.append("BIN=").append(ADB_DIR).append("/magpie\n");
            s.append("[ -x \"$BIN\" ] || BIN=\"").append(bin).append("\"\n");
        } else {
            s.append("BIN=\"").append(bin).append("\"\n");
        }
        s.append("echo $$ > \"$PKG_DIR/wrapper.pid\"\n");
        s.append("rm -f \"$PKG_DIR/stop.flag\"\n");
        s.append("echo \"[$(date '+%F %T')] starting magpie ($BIN)\" >> \"$LOG\"\n");
        s.append("[ -x \"$BIN\" ] || echo \"[$(date '+%F %T')] $BIN is not executable\" >> \"$LOG\"\n");
        s.append("while true; do\n");
        s.append("  \"$BIN\" serve --web=").append(webAddr).append(" >> \"$LOG\" 2>&1 &\n");
        s.append("  p=$!\n");
        s.append("  echo $p > \"$PKG_DIR/magpie.pid\"\n");
        s.append("  echo -1000 > /proc/$p/oom_score_adj 2>/dev/null\n");
        // root 建的文件归 root，把属主还回 App，App 才读得到自己的日志
        s.append("  app_uid=$(stat -c %u \"$PKG_DIR\" 2>/dev/null)\n");
        s.append("  [ -n \"$app_uid\" ] && chown -R \"$app_uid\":\"$app_uid\" \"$HOME\" 2>/dev/null\n");
        s.append("  [ -n \"$app_uid\" ] && chown \"$app_uid\":\"$app_uid\" \"$LOG\" 2>/dev/null\n");
        s.append("  chmod 0644 \"$LOG\" \"$PKG_DIR\"/*.pid 2>/dev/null\n");
        s.append("  wait $p\n");
        s.append("  if [ -f \"$PKG_DIR/stop.flag\" ]; then break; fi\n");
        s.append("  echo \"[$(date '+%F %T')] magpie exited ($?), restarting in 3s\" >> \"$LOG\"\n");
        s.append("  sleep 3\n");
        s.append("  if [ -f \"$PKG_DIR/stop.flag\" ]; then break; fi\n");
        s.append("done\n");
        s.append("rm -f \"$PKG_DIR/magpie.pid\" \"$PKG_DIR/wrapper.pid\"\n");
        s.append("echo \"[$(date '+%F %T')] magpie stopped\" >> \"$LOG\"\n");
        return s.toString();
    }

    public static void writeScript(Context c, boolean rootMode) throws IOException {
        writeFile(scriptFile(c), buildScript(c, rootMode), "0644");
    }

    // ------------------------------------------------------------------ root

    private static volatile Boolean rootCache;
    private static volatile boolean rootProbing;
    private static volatile long rootProbeAt;
    /** What `su -c id` last printed, for the self check. */
    public static volatile String lastRootReply = "";

    /** The current startup step, shown on the status card. */
    public static volatile String progress = "";
    public static volatile long progressAt;

    static void step(String s) {
        progress = s;
        progressAt = System.currentTimeMillis();
        android.util.Log.i("magpie", s);
    }

    /** True while the step text is younger than ms. */
    public static boolean progressFresh(long ms) {
        return System.currentTimeMillis() - progressAt < ms;
    }

    /** What the last probe said, or null while nothing is known yet. Never blocks. */
    public static Boolean rootKnown() {
        return rootCache;
    }

    /** Last known answer, false while unknown. Safe on the UI thread. */
    public static boolean rootCached() {
        return Boolean.TRUE.equals(rootCache);
    }

    /**
     * Blocking probe; the root manager's dialog can take a while, so: worker threads only.
     * A "no" is re-asked after a minute: the phone may have been locked, or the
     * root manager's dialog may simply have gone unanswered at the time.
     */
    public static boolean isRoot() {
        Boolean cached = rootCache;
        long now = System.currentTimeMillis();
        if (cached != null && (cached || now - rootProbeAt > 60000)) return cached;
        boolean ok = false;
        try {
            String out = exec(new String[]{"su", "-c", "id"}, 30000);
            ok = out != null && out.contains("uid=0");
            lastRootReply = out == null ? "null" : out.trim();
        } catch (Throwable t) {
            ok = false;
        }
        rootCache = ok;
        rootProbeAt = now;
        return ok;
    }

    /** Asks the root manager in the background so the UI never waits on it. */
    public static void probeRootAsync() {
        if (rootCache != null || rootProbing) return;
        rootProbing = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    isRoot();
                } finally {
                    rootProbing = false;
                }
            }
        }, "magpie-su").start();
    }

    /** Decides which mode to use. "auto" prefers root when it is there. Never blocks. */
    public static boolean useRoot(Context c) {
        String mode = prefs(c).getString("mode", "auto");
        if ("app".equals(mode)) return false;
        if ("root".equals(mode)) return true;
        return rootCached();
    }

    /**
     * Copy the bundled binary somewhere root can always execute it from:
     * /data/adb/magpie when a root manager is there (never cleaned), and
     * /data/local/tmp/magpie as the fallback.
     */
    public static boolean installRootBinary(Context c) {
        File bin = localBin(c);
        String src = "'" + bin.getAbsolutePath() + "'";

        // 更新过的守护二进制不能被 APK 里的旧版覆盖：只在内置版更新时才拷贝
        String remoteVer = exec(new String[]{"su", "-c", remoteBin() + " --version"}, 30000);
        String localVer = exec(new String[]{bin.getAbsolutePath(), "--version"}, 30000);
        if (remoteVer != null && localVer != null
                && versionNum(remoteVer) >= versionNum(localVer)) {
            return true;
        }

        String cmd = "mkdir -p " + REMOTE_DIR
                + " && cp -f " + src + " " + remoteBin()
                + " && chmod 0755 " + remoteBin()
                + "; if [ -d /data/adb ]; then mkdir -p " + ADB_DIR
                + " && cp -f " + src + " " + ADB_DIR + "/magpie"
                + " && chmod 0755 " + ADB_DIR + "/magpie; fi"
                + "; test -x " + remoteBin() + " && echo MAGPIE_OK";
        String out = exec(new String[]{"su", "-c", cmd}, 90000);
        return out != null && out.contains("MAGPIE_OK");
    }

    public static boolean rootBinaryReady() {
        String cmd = "test -x " + remoteBin() + " && echo MAGPIE_OK";
        String out = exec(new String[]{"su", "-c", cmd}, 15000);
        return out != null && out.contains("MAGPIE_OK");
    }

    // ------------------------------------------------------------ start/stop

    public static final int MODE_ROOT = 1;
    public static final int MODE_APP = 2;

    /** Starts the daemon detached. Returns the mode used, or 0 on failure. */
    public static synchronized int start(Context c, boolean wantRoot) {
        try {
            stop(c, false);
            stopFlag(c).delete();
        } catch (Exception ignored) {
        }
        step("判断运行方式…");
        boolean root = !"app".equals(prefs(c).getString("mode", "auto")) && isRoot();
        appendLog(c, "[start] mode=" + (root ? "root" : "app"));
        try {
            writeScript(c, root);
        } catch (IOException e) {
            appendLog(c, "[start] cannot write script: " + e);
            return 0;
        }

        if (root) step("安装 root 二进制…");
        if (root && installRootBinary(c)) {
            step("拉起 root 守护…");
            String script = scriptFile(c).getAbsolutePath();
            String log = logFile(c).getAbsolutePath();
            String inner = "setsid /system/bin/sh '" + script + "' </dev/null >>'" + log + "' 2>&1"
                    + " || /system/bin/sh '" + script + "' </dev/null >>'" + log + "' 2>&1";
            String cmd = "( " + inner + " ) &";
            exec(new String[]{"su", "-c", cmd}, 20000);
            return MODE_ROOT;
        } else if (root) {
            appendLog(c, "[start] root 模式装不上二进制，退回普通模式");
        }
        step("以普通前台服务方式启动…");

        File bin = localBin(c);
        if (!bin.exists()) {
            appendLog(c, "[start] bundled binary missing: " + bin);
            return 0;
        }
        bin.setExecutable(true, false);
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", scriptFile(c).getAbsolutePath());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile(c)));
            pb.start();
            return MODE_APP;
        } catch (IOException e) {
            appendLog(c, "[start] cannot start: " + e);
            return 0;
        }
    }

    public static synchronized void stop(Context c, boolean markStopped) {
        try {
            if (markStopped) writeFile(stopFlag(c), "stop", "0644");
        } catch (IOException ignored) {
        }
        // 只杀确认真的是我们的进程。绝不按 pid 文件盲杀：pid 会被系统复用，
        // root 下杀错一个就是整机重启（曾经拼出 `kill -9 -1`，等于杀光所有进程）。
        StringBuilder kills = new StringBuilder();
        for (int pid : new int[]{readInt(pidFile(c)), readInt(wrapperPidFile(c))}) {
            if (pid > 1 && isMagpiePid(pid)) {
                kills.append("kill -9 ").append(pid).append(" 2>/dev/null; ");
            }
        }
        // 方括号是为了让 pkill 自己的命令行不匹配这个正则，否则它会先杀掉自己的父 shell
        String byName = "pkill -9 -f 'libmagpie[-]core.so' 2>/dev/null; "
                + "pkill -9 -f 'magpie[/]magpie serve' 2>/dev/null; "
                + "pkill -9 -f 'run-magpie[.]sh' 2>/dev/null; true";
        String cmd = kills + byName;
        exec(new String[]{"/system/bin/sh", "-c", cmd}, 15000);
        if (isRoot()) {
            exec(new String[]{"su", "-c", cmd}, 15000);
        }
        pidFile(c).delete();
        wrapperPidFile(c).delete();
    }

    /** 这个 pid 真的是我们的进程吗？读不到 cmdline（比如系统进程）一律不碰。 */
    private static boolean isMagpiePid(int pid) {
        if (pid <= 1) return false;
        java.io.RandomAccessFile r = null;
        try {
            r = new java.io.RandomAccessFile("/proc/" + pid + "/cmdline", "r");
            byte[] buf = new byte[256];
            int n = r.read(buf);
            if (n <= 0) return false;
            return new String(buf, 0, n, "UTF-8").contains("magpie");
        } catch (Throwable t) {
            return false;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    // -------------------------------------------------------------- status

    public static volatile String lastProbe = "";

    public static boolean portOpen(int port) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", port), 900);
            lastProbe = "port " + port + ": 连上了";
            return true;
        } catch (Exception e) {
            lastProbe = "port " + port + ": " + e;
            return false;
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 探测细节：连接结果 + 守护进程是否真的活着，用于排查"明明在跑却探测不到"。 */
    public static String probeDetail(Context c) {
        StringBuilder sb = new StringBuilder();
        boolean a = portOpen(GW_PORT);
        String ga = lastProbe;
        boolean b = portOpen(WEB_PORT);
        String wb = lastProbe;
        sb.append("socket: ").append(ga).append(" / ").append(wb).append("\n");
        int p = readInt(pidFile(c));
        sb.append("pid=").append(p);
        if (p > 0) {
            File proc = new File("/proc/" + p);
            sb.append(" /proc存在=").append(proc.exists());
            try {
                java.io.RandomAccessFile r = new java.io.RandomAccessFile("/proc/" + p + "/cmdline", "r");
                byte[] buf = new byte[200];
                int n = r.read(buf);
                r.close();
                if (n > 0) {
                    String cmd = new String(buf, 0, n, "UTF-8").replace((char) 0, ' ');
                    sb.append(" cmdline=").append(cmd.trim());
                }
            } catch (Throwable t) {
                sb.append(" cmdline不可读(").append(t.getClass().getSimpleName()).append(")");
            }
        }
        sb.append("\n").append("running()=").append(a || b);
        return sb.toString();
    }


    /**
     * 主线程上连 socket 会抛 NetworkOnMainThreadException（localhost 也不例外），
     * 所以探测由后台线程常驻执行，这里只读它的最新结论。
     */
    private static volatile boolean lastRunning;
    private static volatile boolean proberUp;

    public static void startProber() {
        if (proberUp) return;
        proberUp = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        lastRunning = portOpen(WEB_PORT) || portOpen(GW_PORT);
                    } catch (Throwable ignored) {
                        lastRunning = false;
                    }
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                }
            }
        }, "magpie-probe");
        t.setDaemon(true);
        t.start();
    }

    /** Latest probe verdict, refreshed every 2s by the prober. Safe on any thread. */
    public static boolean running() {
        return lastRunning;
    }

    /** 单引号包一个字符串给 shell 用。 */
    public static String shq(String v) {
        if (v == null) return "''";
        return "'" + v.replace("'", "'" + (char) 92 + "''") + "'";
    }

    /** 代理只允许这些字符，别的一律当没填（防止被当成 shell 命令执行）。 */
    public static String safeProxy(Context c) {
        String p = proxy(c);
        if (p.isEmpty()) return "";
        return p.matches("[A-Za-z0-9:/._@%+\\-\\[\\]]+") ? p : "";
    }

    /** One HTTP request with its answer summarised, for the self check. */
    public static String httpProbe(String url) {
        try {
            java.net.HttpURLConnection conn =
                    (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            int len = conn.getContentLength();
            String loc = conn.getHeaderField("Location");
            String text = code + (len >= 0 ? ", " + len + " bytes" : "")
                    + (loc != null ? " -> " + loc : "");
            conn.disconnect();
            return text;
        } catch (Throwable t) {
            return "失败: " + t;
        }
    }

    /** "magpie 0.1.4868" -> 14868, for comparing builds. */
    public static long versionNum(String text) {
        if (text == null) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+)\\.(\\d+)\\.(\\d+)").matcher(text);
        if (!m.find()) return -1;
        return Long.parseLong(m.group(1)) * 1000000L
                + Long.parseLong(m.group(2)) * 1000L
                + Long.parseLong(m.group(3));
    }

    /** Newest magpie version from the official feed, or an error note. */
    public static String latestVersion() {
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                    new java.net.URL("https://usemagpie.ai/api/latest?channel=every").openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
                    int at = Math.max(0, sb.indexOf("version"));
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("(\\d+)\\.(\\d+)\\.(\\d+)").matcher(sb.toString().substring(at));
            return m.find() ? m.group(0) : "未拿到版本号";
        } catch (Throwable t) {
            return "检查失败: " + t.getMessage();
        }
    }

    /** Runs magpie's own updater as root; returns its output, null when impossible. */
    public static String runUpdate(Context c) {
        if (!isRoot()) return null;
        prefs(c).edit().putBoolean("updating", true).apply();
        try {
            String proxy = safeProxy(c);
        String before = exec(new String[]{"su", "-c", remoteBin() + " --version"}, 30000);
        step("正在更新 magpie（先停掉守护）…");
        // 先停掉守护再更新：避免更新过程中 wrapper 又把它拉起来
        stop(c, true);
        String cmd = remoteBin() + " update";
            if (!proxy.isEmpty()) cmd += " --proxy " + shq(proxy);
        String out = exec(new String[]{"su", "-c", cmd}, 600000);
        String after = exec(new String[]{"su", "-c", remoteBin() + " --version"}, 30000);
        long a = versionNum(before), b = versionNum(after);
        String verdict;
        if (b < 0) {
            verdict = "更新后读不到版本号：" + (after == null ? "无输出" : after.trim());
        } else if (b > a) {
            verdict = "已从 " + before.trim() + " 更新到 " + after.trim();
        } else {
            verdict = "没有可更新的：仍是 " + after.trim() + "（更新输出见日志）";
        }
        appendLog(c, "[update] " + verdict + "\n[update 输出] " + (out == null ? "无" : out.trim()));
        step("重启守护…");
        start(c, true);
        return verdict;
    } finally {
            prefs(c).edit().putBoolean("updating", false).apply();
        }
    }

    // ------------------------------------------------------------ autostart

    /** Root: a Magisk/KernelSU/APatch boot script. Otherwise BOOT_COMPLETED. */
    public static String autostartPath() {
        return "/data/adb/service.d/99-magpie.sh";
    }

    public static boolean setRootAutostart(Context c, boolean on) {
        if (on) {
            try {
                writeFile(autoFlag(c), "1", "0644");
            } catch (IOException ignored) {
            }
            String dir = c.getFilesDir().getAbsolutePath();
            String body = "#!/system/bin/sh\n"
                    + "# magpie autostart (root)\n"
                    + "sleep 15\n"
                    + "[ -f '" + autoFlag(c).getAbsolutePath() + "' ] || exit 0\n"
                    + "( setsid /system/bin/sh '" + dir + "/run-magpie.sh' </dev/null >>'"
                    + dir + "/boot.log' 2>&1 || /system/bin/sh '" + dir + "/run-magpie.sh' </dev/null >>'"
                    + dir + "/boot.log' 2>&1 ) &\n";
            try {
                File tmp = new File(c.getFilesDir(), "boot.sh");
                writeRaw(tmp, body);
                String cmd = "mkdir -p /data/adb/service.d"
                        + " && cp -f '" + tmp.getAbsolutePath() + "' " + autostartPath()
                        + " && chmod 0755 " + autostartPath()
                        + " && echo MAGPIE_OK";
                String out = exec(new String[]{"su", "-c", cmd}, 20000);
                return out != null && out.contains("MAGPIE_OK");
            } catch (IOException e) {
                return false;
            }
        }
        autoFlag(c).delete();
        String cmd = "rm -f " + autostartPath() + "; echo MAGPIE_OK";
        String out = exec(new String[]{"su", "-c", cmd}, 15000);
        return out != null && out.contains("MAGPIE_OK");
    }

    // ---------------------------------------------------------------- files

    public static void writeFile(File f, String text, String mode) throws IOException {
        writeRaw(f, text);
        exec(new String[]{"chmod", mode, f.getAbsolutePath()}, 5000);
    }

    public static void writeRaw(File f, String text) throws IOException {
        FileOutputStream out = new FileOutputStream(f, false);
        try {
            out.write(text.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    public static void appendLog(Context c, String line) {
        if (c == null) return;
        try {
            FileOutputStream out = new FileOutputStream(logFile(c), true);
            try {
                out.write((line + "\n").getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (IOException ignored) {
        }
    }

    public static int readInt(File f) {
        try {
            String s = tail(f, 1).trim();
            return Integer.parseInt(s);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Last n lines of a text file, cheaply. */
    public static String tail(File f, int n) {
        if (!f.exists()) return "";
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            long len = raf.length();
            int max = 96 * 1024;
            long start = Math.max(0, len - max);
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            String all = new String(buf, "UTF-8");
            String[] lines = all.split("\n");
            StringBuilder sb = new StringBuilder();
            int from = Math.max(0, lines.length - n);
            for (int i = from; i < lines.length; i++) {
                if (i > from) sb.append('\n');
                sb.append(lines[i]);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    // ---------------------------------------------------------------- shell

    /** Runs a command and collects its output; never throws. */
    public static String exec(String[] cmd, int timeoutMs) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            p = pb.start();
            final StringBuilder sb = new StringBuilder();
            final java.io.InputStream in = p.getInputStream();
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                        String line;
                        while ((line = r.readLine()) != null) {
                            sb.append(line).append('\n');
                        }
                    } catch (IOException ignored) {
                    }
                }
            });
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroy();
            }
            reader.join(2000);
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }
}
