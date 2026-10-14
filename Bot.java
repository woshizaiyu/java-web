import java.io.File;
import java.util.Map;

/**
 * Bot —— web二进制启动器（Java版，与bot.py/bot.js同契约）。
 * 逻辑：CONFIG非空项注入子进程环境（面板环境变量优先）→ 定位web二进制 → chmod+x → 拉起（stdio继承）→
 * 转发SIGTERM/SIGINT → 透传退出码。
 * 零依赖，javac直编即可；GitHub Actions构建见 .github/workflows/build-botjar.yml。
 */
public class Bot {
    // CONFIG默认值表：{"键", "默认值"}。留空=不注入，部署时填或走面板环境变量。
    // GitHub手动构建烧入时，workflow按 {"KEY", ".*"} 逐行sed替换第二个字符串。
    static final String[][] DEFAULTS = {
        {"UUID", ""}, // 节点UUID，三协议共用
        {"ARGO_DOMAIN", ""}, // Argo固定隧道域名，空则订阅用占位域名
        {"ARGO_AUTH", ""}, // Argo隧道token，空则不起隧道
        {"ARGO_PORT", "8001"}, // 隧道源站监听端口，dashboard ingress指到本机此端口
        {"PORT", ""}, // HTTP订阅端口，填面板分配的端口
        {"SUB_PATH", "sub"}, // 订阅路径，访问 PORT/SUB_PATH 取订阅
        {"CFIP", ""}, // 优选IP/域名，节点地址用它
        {"CFPORT", "443"}, // 优选端口
        {"NAME", ""}, // 节点名前缀，空则用协议默认名
        {"SHOW_LOG", "no"}, // 日志总闸：no/false/disable=全静默
        {"DEBUG", ""}, // true=多打调试行，默认关闭
        {"WSPATH", ""}, // 三WS统一路径，空=默认UUID去横线前8
        {"DOMAIN", ""}, // 直连节点域名/IP，空则直连链接跳过
        {"SECURITY", ""}, // 直连WS用tls还是none；tls要求前面有TLS卸载点
        {"PUBLIC_PORT", ""}, // SECURITY=tls时直连三件套的公开端口，默认443
        {"SERVER_PORT", ""}, // 容器分配端口，优先于PORT做HTTP监听
        {"S5_PORT", ""}, // SOCKS5端口，空=不开启
        {"HY2_PORT", ""}, // HY2端口(UDP)，空=不开启
        {"REALITY_PORT", ""}, // Reality端口，空=不开启
        {"REALITY_SNI", "www.microsoft.com"}, // Reality伪装域名
        {"CFSM_URL", ""}, // CF探针面板地址，缺一即跳过探针
        {"CFSM_ID", ""}, // CF探针服务器ID
        {"CFSM_SECRET", ""}, // CF探针上报密钥
        {"CFSM_INTERVAL", "60"}, // 上报间隔秒，最小10
        {"GOMEMLIMIT", "90MiB"}, // Go堆上限，防小内存面板被顶满
        // {"ENV_FILE", ".env"}, // 额外env文件路径，一般不用动
        // {"BOT_BIN", ""}, // web二进制改名时填，空=同目录web
    };

    public static void main(String[] args) throws Exception {
        // jar所在目录 = 启动器家目录（BOT_BIN相对路径的基准）。
        String here;
        try {
            here = new File(Bot.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
        } catch (Exception e) {
            here = ".";
        }

        ProcessBuilder pb = new ProcessBuilder();
        Map<String, String> env = pb.environment(); // 已含本进程环境（面板变量优先）
        for (String[] kv : DEFAULTS) {
            if (!kv[1].isEmpty() && !env.containsKey(kv[0])) {
                env.put(kv[0], kv[1]);
            }
        }

        String target = env.getOrDefault("BOT_BIN", here + File.separator + "web");
        File targetFile = new File(target);
        if (!targetFile.isAbsolute()) {
            targetFile = new File(here + File.separator + target);
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) {
            try {
                targetFile.setExecutable(true, false); // 对应chmod a+x
            } catch (SecurityException ignored) {
                // 交由下方的存在性检查报错
            }
        }
        if (!targetFile.isFile()) {
            System.out.println("bot: target not found: " + targetFile);
            System.exit(1);
            return;
        }

        pb.command(targetFile.getAbsolutePath());
        pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        final Process proc = pb.start();

        // SIGTERM/SIGINT进来时JVM先跑hook：把信号转成子进程的destroy（Linux=SIGTERM），再等它收尾。
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            proc.destroy();
            try {
                proc.waitFor();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }));

        System.exit(proc.waitFor()); // 透传web退出码
    }
}
