import java.util.*;

/** No GUI/network: tests launcher serialization and runtime guards. */
public final class LauncherTest {
    public static void main(String[] args) throws Exception {
        String body=ILO3IRC.loginBody("u\"ser","p\\ass\n\t\r\b\f");
        String expected="{\"method\":\"login\",\"user_login\":\"u\\\"ser\",\"password\":\"p\\\\ass\\n\\t\\r\\b\\f\"}";
        if(!expected.equals(body)) throw new AssertionError("Credential JSON escaping mismatch");
        System.out.println("PASS launcher uses escaped credential JSON");
        String version=System.getProperty("java.specification.version");
        try {
            System.setProperty("java.specification.version","17");
            try {ILO3IRC.checkRuntime();throw new AssertionError("Java17 accepted");}
            catch(IllegalArgumentException expectedFailure){System.out.println("PASS non-Java8 rejected");}
        } finally {System.setProperty("java.specification.version",version);}
        String os=System.getProperty("os.name"),arch=System.getProperty("os.arch");
        try {
            System.setProperty("os.name","Mac OS X");System.setProperty("os.arch","x86_64");
            try {ILO3IRC.checkRuntime();throw new AssertionError("Intel macOS Java accepted");}
            catch(IllegalArgumentException expectedFailure){System.out.println("PASS Intel macOS JVM rejected");}
        } finally {System.setProperty("os.name",os);System.setProperty("os.arch",arch);}
        System.out.println("LAUNCHER UNIT TESTS: 3 PASS");
    }
}
