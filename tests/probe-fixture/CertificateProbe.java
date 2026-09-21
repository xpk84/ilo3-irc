import java.io.DataOutputStream;

/** Test-only replacement probe: malformed and excessive child output, no networking. */
public final class CertificateProbe {
    public static void main(String[] args) throws Exception {
        DataOutputStream out = new DataOutputStream(System.out);
        if ("overflow.invalid".equals(args[0])) {
            byte[] block = new byte[8192];
            for (int i = 0; i < 256; i++) out.write(block);
        } else if ("stderr.invalid".equals(args[0])) {
            byte[] block = new byte[8192];
            for (int i = 0; i < 256; i++) System.err.write(block);
        } else if ("truncated.invalid".equals(args[0])) {
            out.writeInt(0x494c4f31);
        } else if ("badmagic.invalid".equals(args[0])) {
            out.writeInt(42);
        } else if ("emptyerror.invalid".equals(args[0])) {
            out.writeInt(0x494c4f31); out.writeBoolean(false); out.writeUTF("");
        } else if ("controlerror.invalid".equals(args[0])) {
            out.writeInt(0x494c4f31); out.writeBoolean(false); out.writeUTF("unexpected\ncontrol text");
        } else {
            System.exit(7);
        }
        out.flush();
    }
}
