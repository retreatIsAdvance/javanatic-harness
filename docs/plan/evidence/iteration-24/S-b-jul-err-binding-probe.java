import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.System.Logger.Level;

/**
 * it24 S-b 修正表缺陷的独立复现：JUL 后端在**首条记录发布时**一次性绑定彼时的 System.err，
 * 之后 System.setErr 不再生效——同进程内的 stderr 捕获式断言因此只在「自己第一条发布」时成立，
 * 嵌套/成对运行必有一方捕空。
 *
 * <p>单文件源码启动（未参与构建）：java --enable-native-access=ALL-UNNAMED S-b-jul-err-binding-probe.java
 */
public class JulErrBindingProbe {
    public static void main(String[] args) {
        System.Logger log = System.getLogger("jh.probe");
        ByteArrayOutputStream first = new ByteArrayOutputStream();
        ByteArrayOutputStream second = new ByteArrayOutputStream();
        System.setErr(new PrintStream(first, true));   // 首条发布前设流
        log.log(Level.WARNING, "published-before-swap");
        System.setErr(new PrintStream(second, true));  // 发布后再换流
        log.log(Level.WARNING, "published-after-swap");
        System.err.flush();
        System.out.println("first-captured=" + first.toString().contains("published-before-swap")
            + " second-captured=" + second.toString().contains("published-after-swap")
            + " second-received-first-line=" + second.toString().contains("published-before-swap"));
    }
}
