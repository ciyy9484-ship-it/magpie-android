package ai.magpie.android;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

public class MainActivity extends android.app.Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView statusText;
    private TextView detailText;
    private TextView logText;
    private Switch autoSwitch;
    private Switch lanSwitch;
    private Switch rootSwitch;
    private Switch traceSwitch;
    private String updateInfo = "";

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        askNotificationPermission();
        Core.probeRootAsync();
        refreshStatus();
        refreshLog();
        // 打开 App 就等于要求 magpie 在跑
        if (!Core.running()) startDaemon();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        handler.postDelayed(tick, 2000);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------------- ui

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView label(String text, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(28));
        scroll.addView(root);

        root.addView(label("magpie", 26, true));
        root.addView(label("手机上的计划池代理 · v" + Core.versionName(this), 12, false));

        statusText = label("…", 18, true);
        root.addView(statusText);
        detailText = label("", 12, false);
        root.addView(detailText);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.addView(button("启动", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startDaemon();
            }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row1.addView(button("停止", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MagpieService.fire(MainActivity.this, false);
                toast("已停止");
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        refreshStatus();
                    }
                }, 1200);
            }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row1.addView(button("界面", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openUi();
            }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(button("检查更新", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkUpdate();
            }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row2.addView(button("更新 magpie", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doUpdate();
            }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row2.addView(button("复制界面链接", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copy(Core.webUrl(MainActivity.this));
            }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row2);

        bindSwitches(root);
        proxyInput(root);

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.addView(button("电池不限制", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                batteryExempt();
            }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row3.addView(button("自检并复制", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                diagnose();
            }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row3);

        LinearLayout row4 = new LinearLayout(this);
        row4.setOrientation(LinearLayout.HORIZONTAL);
        row4.addView(button("复制日志", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyLog();
            }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row4.addView(button("清空日志", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Core.logFile(MainActivity.this).delete();
                logText.setText("");
            }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row4);

        root.addView(label("插件 / MCP / 更新 · 说明", 13, true));
        root.addView(helpText());

        root.addView(label("日志", 13, true));
        ScrollView logScroll = new ScrollView(this);
        logScroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(240)));
        logText = new TextView(this);
        logText.setTextSize(10);
        logText.setTextIsSelectable(true);
        logText.setTypeface(android.graphics.Typeface.MONOSPACE);
        logScroll.addView(logText);
        root.addView(logScroll);

        return scroll;
    }

    private TextView helpText() {
        TextView t = new TextView(this);
        t.setTextSize(11);
        t.setText("· 插件和 MCP：直接在「界面 → 资源库 / 插件」里装，手机上不用装任何东西。"
                + "第一次用到时 magpie 会自己下载安卓版 Bun（约 90MB，走 GitHub，慢就多等会儿或给手机挂代理）。"
                + "\n· 更新：配置不会因为更新丢失。有 root 时点「更新 magpie」就够（magpie 自己下新版并替换守护二进制）；"
                + "没有 root 时更新 = 重新打包 APK 覆盖安装。"
                + "\n· 别的设备要连：打开「局域网可访问」，对方把 ANTHROPIC_BASE_URL 指到 http://手机IP:3425；"
                + "网页版界面用「复制界面链接」。链接自带密钥，别外传。");
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    /** Optional proxy for magpie's own outbound traffic and for updates. */
    private void proxyInput(LinearLayout root) {
        root.addView(label("网络代理（可选；手机上访问 GitHub/供应商走它）", 13, true));
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("留空 = 直连；例如 http://127.0.0.1:7890");
        input.setText(Core.proxy(this));
        input.setTextSize(12);
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c2) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c2) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                Core.prefs(MainActivity.this).edit()
                        .putString("proxy", s.toString().trim()).apply();
            }
        });
        root.addView(input);
    }

    private Switch switchRow(LinearLayout root, String text, boolean checked,
                             CompoundButton.OnCheckedChangeListener l) {
        Switch s = new Switch(this);
        s.setText(text);
        s.setChecked(checked);
        s.setPadding(0, dp(10), 0, dp(10));
        s.setOnCheckedChangeListener(l);
        root.addView(s);
        return s;
    }

    private void bindSwitches(LinearLayout root) {
        boolean rootOk = Core.rootCached();

        autoSwitch = switchRow(root, "开机自启", Core.prefs(this).getBoolean("autostart", false),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean on) {
                        Core.prefs(MainActivity.this).edit().putBoolean("autostart", on).apply();
                        final boolean wantOn = on;
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                final String msg;
                                if (Core.useRoot(MainActivity.this)) {
                                    final boolean ok = Core.setRootAutostart(MainActivity.this, wantOn);
                                    msg = wantOn
                                            ? (ok ? "已写入 /data/adb/service.d，开机自动拉起"
                                                  : "写入失败，检查 root 授权")
                                            : "已关闭开机自启";
                                } else {
                                    msg = wantOn ? "已开启（走系统开机广播）" : "已关闭开机自启";
                                }
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        toast(msg);
                                    }
                                });
                            }
                        }, "magpie-autostart").start();
                    }
                });

        lanSwitch = switchRow(root, "局域网可访问（其它设备可用这个网关）", Core.lan(this),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean on) {
                        Core.prefs(MainActivity.this).edit().putBoolean("lan", on).apply();
                        if (Core.running()) {
                            MagpieService.fire(MainActivity.this, true);
                            toast("已切换，正在重启");
                        }
                    }
                });

        rootSwitch = switchRow(root, "root 守护模式" + (rootOk ? "（已授权）" : "（检测中…）"),
                Core.useRoot(this), new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean on) {
                        Core.prefs(MainActivity.this).edit()
                                .putString("mode", on ? "root" : "app").apply();
                        if (on) Core.probeRootAsync();
                        if (Core.running()) {
                            MagpieService.fire(MainActivity.this, true);
                            toast("已切换模式，正在重启");
                        }
                    }
                });

        traceSwitch = switchRow(root, "上报匿名使用统计（magpie 官方的计数）",
                Core.prefs(this).getBoolean("telemetry", true),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean on) {
                        Core.prefs(MainActivity.this).edit().putBoolean("telemetry", on).apply();
                    }
                });
    }

    // -------------------------------------------------------------- actions

    private void startDaemon() {
        Core.prefs(this).edit().putBoolean("shouldRun", true).apply();
        MagpieService.fire(this, true);
        toast("正在启动…");
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshStatus();
                refreshLog();
            }
        }, 3000);
    }

    private void openUi() {
        if (!Core.running()) {
            toast("magpie 还没起来，先点启动");
            return;
        }
        Intent i = new Intent(this, WebActivity.class);
        i.putExtra("url", "http://127.0.0.1:" + Core.WEB_PORT + "/?k=" + Core.webKey(this));
        startActivity(i);
    }

    private void checkUpdate() {
        toast("查最新版本…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String latest = Core.latestVersion();
                final String mine = Core.versionName(MainActivity.this);
                long a = Core.versionNum(mine), b = Core.versionNum(latest);
                final String msg;
                if (b < 0) {
                    msg = latest;
                } else if (b > a) {
                    msg = "有新版：" + latest + "（现在是 " + mine + "）"
                            + (Core.rootCached() ? "，点「更新 magpie」直接升"
                                                 : "，没有 root 就重新打包 APK 覆盖安装");
                } else {
                    msg = "已是最新（" + mine + "）";
                }
                updateInfo = msg;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        toast(msg);
                        Core.appendLog(MainActivity.this, "[update] " + msg);
                        refreshStatus();
                    }
                });
            }
        }, "magpie-update-check").start();
    }

    private void doUpdate() {
        if (!Core.rootCached()) {
            toast("更新 magpie 需要 root；没有 root 就重新打包 APK 覆盖安装");
            return;
        }
        toast("开始更新，可能要一两分钟…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String verdict = Core.runUpdate(MainActivity.this);
                updateInfo = verdict == null ? "更新失败（root 不可用）" : verdict;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        toast(updateInfo);
                        refreshStatus();
                        refreshLog();
                    }
                });
            }
        }, "magpie-update").start();
    }

    private void copy(String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("magpie", s));
        toast("已复制");
    }

    private void copyLog() {
        String t = Core.tail(Core.logFile(this), 300);
        copy("---- magpie 日志 ----\n" + (t.length() == 0 ? "（空）" : t));
        toast("日志已复制");
    }

    private void batteryExempt() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                toast("这台机器没有这个设置页");
            }
        }
    }

    private void diagnose() {
        toast("自检中，稍等一下…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String report = diagnoseWorker();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        refreshLog();
                        copy(report);
                        toast("自检结果已复制，直接粘贴发给 Alma");
                    }
                });
            }
        }, "magpie-diag").start();
    }

    private String diagnoseWorker() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n==== 自检 ").append(new java.util.Date().toString()).append(" ====\n");
        File bin = Core.localBin(this);
        sb.append("内置二进制: ").append(bin.getAbsolutePath()).append("\n");
        sb.append("  存在: ").append(bin.exists())
                .append(" 大小: ").append(bin.exists() ? bin.length() : 0)
                .append(" 可执行: ").append(bin.canExecute()).append("\n");
        sb.append("root: ").append(Core.isRoot()).append("\n");
        sb.append("su 回应: ").append(Core.lastRootReply).append("\n");
        sb.append("root 二进制: ").append(Core.remoteBin()).append(" 就绪: ")
                .append(Core.rootCached() && Core.rootBinaryReady()).append("\n");
        sb.append("架构: ").append(Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
                ? Build.SUPPORTED_ABIS[0] : "?").append("\n");
        sb.append("安卓: ").append(Build.VERSION.RELEASE).append(" (API ")
                .append(Build.VERSION.SDK_INT).append(")\n");
        String wv = "?";
        try {
            android.content.pm.PackageInfo pi = android.webkit.WebView.getCurrentWebViewPackage();
            if (pi != null) wv = pi.packageName + " " + pi.versionName;
        } catch (Throwable t) {
            wv = "未知: " + t;
        }
        sb.append("WebView: ").append(wv).append("\n");
        sb.append("网关 ").append(Core.GW_PORT).append(": ").append(Core.portOpen(Core.GW_PORT)).append("\n");
        sb.append("界面 ").append(Core.WEB_PORT).append(": ").append(Core.portOpen(Core.WEB_PORT)).append("\n");
        sb.append("root 脚本: ").append(Core.autostartPath()).append(" 存在: ")
                .append(new File(Core.autostartPath()).exists()).append("\n");
        sb.append("模式设置: ").append(Core.prefs(this).getString("mode", "auto")).append("\n");
        sb.append("界面链接: ").append(Core.webUrl(this)).append("\n");
        String k = Core.webKey(this);
        sb.append("HTTP 界面: ").append(Core.httpProbe("http://127.0.0.1:" + Core.WEB_PORT + "/?k=" + k)).append("\n");
        sb.append("HTTP 网关: ").append(Core.httpProbe("http://127.0.0.1:" + Core.GW_PORT + "/")).append("\n");
        boolean r = Core.running();
        sb.append("结论: ").append(r ? "运行中" : "未运行").append("\n");

        sb.append("---- 直接执行（普通权限）----\n");
        String out = Core.exec(new String[]{Core.localBin(this).getAbsolutePath(), "--version"}, 20000);
        sb.append(out == null ? "起不来：进程根本没能启动（权限/格式问题）" : out.trim()).append("\n");
        if (Core.rootCached()) {
            sb.append("---- 直接执行（root）----\n");
            String ro = Core.exec(new String[]{"su", "-c", Core.remoteBin() + " --version"}, 25000);
            sb.append(ro == null ? "su 执行失败" : ro.trim()).append("\n");
        }
        Core.appendLog(this, sb.toString());
        return sb.toString();
    }

    // --------------------------------------------------------------- status

    private void refreshStatus() {
        boolean up = Core.running();
        boolean root = Core.useRoot(this);
        Boolean known = Core.rootKnown();
        boolean want = Core.prefs(this).getBoolean("shouldRun", false);

        String head;
        if (up) {
            head = "● 运行中";
        } else if (!want) {
            head = "○ 已停止";
        } else if (Core.progressFresh(30000) && Core.progress.length() > 0) {
            head = "◌ " + Core.progress;
        } else {
            head = "✕ 启动失败";
        }
        statusText.setText(head);

        StringBuilder d = new StringBuilder();
        d.append("模式: ").append(root ? "root 守护" : "普通前台服务");
        d.append("\nroot: ").append(known == null ? "检测中…"
                : (known ? "已授权" : "没有（su 被拒或没授权；解锁手机打开 App 再授权一次）"));
        d.append("\n网关: ").append(Core.gatewayUrl(this));
        d.append("\n界面: 127.0.0.1:").append(Core.WEB_PORT);
        if (updateInfo.length() > 0) d.append("\n更新: ").append(updateInfo);
        String last = lastLogLine();
        if (!last.isEmpty()) d.append("\n日志: ").append(last);
        detailText.setText(d.toString());

        if (rootSwitch != null) {
            rootSwitch.setText("root 守护模式" + (known == null ? "（检测中…）"
                    : (known ? "（已授权）" : "（无 root，会退回普通模式）")));
        }
        if (logText != null && up != lastUp) {
            lastUp = up;
            refreshLog();
        }
    }

    private boolean lastUp;

    /** The last non-empty line magpie wrote, so the status card says why. */
    private String lastLogLine() {
        String t = Core.tail(Core.logFile(this), 3);
        if (t.length() == 0) return "";
        String[] lines = t.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String l = lines[i].trim();
            if (l.length() == 0) continue;
            return l.length() > 160 ? l.substring(0, 160) + "…" : l;
        }
        return "";
    }

    private void refreshLog() {
        String t = Core.tail(Core.logFile(this), 200);
        logText.setText(t.length() == 0 ? "（还没有日志）" : t);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
