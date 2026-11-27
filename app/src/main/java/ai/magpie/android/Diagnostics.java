package ai.magpie.android;

import android.content.Context;
import android.os.Build;

import java.io.File;

/** 自检：把可能出问题的每一环都问一遍，结果直接给用户复制。 */
public final class Diagnostics {

    private Diagnostics() {}

    public static String run(Context c) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n==== 自检 ").append(new java.util.Date().toString()).append(" ====\n");
        File bin = Core.localBin(c);
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
        sb.append("模式设置: ").append(Core.prefs(c).getString("mode", "auto")).append("\n");
        sb.append("代理: ").append(Core.proxy(c)).append("\n");
        sb.append("界面链接: ").append(Core.webUrl(c)).append("\n");
        String k = Core.webKey(c);
        sb.append("HTTP 界面: ").append(Core.httpProbe("http://127.0.0.1:" + Core.WEB_PORT + "/?k=" + k)).append("\n");
        sb.append("HTTP 网关: ").append(Core.httpProbe("http://127.0.0.1:" + Core.GW_PORT + "/")).append("\n");
        sb.append("结论: ").append(Core.running() ? "运行中" : "未运行").append("\n");

        sb.append("---- 直接执行（普通权限）----\n");
        String out = Core.exec(new String[]{Core.localBin(c).getAbsolutePath(), "--version"}, 20000);
        sb.append(out == null ? "起不来：进程根本没能启动（权限/格式问题）" : out.trim()).append("\n");
        if (Core.rootCached()) {
            sb.append("---- 直接执行（root）----\n");
            String ro = Core.exec(new String[]{"su", "-c", Core.remoteBin() + " --version"}, 25000);
            sb.append(ro == null ? "su 执行失败" : ro.trim()).append("\n");
        }
        Core.appendLog(c, sb.toString());
        return sb.toString();
    }
}
