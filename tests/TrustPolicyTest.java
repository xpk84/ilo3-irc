import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

public final class TrustPolicyTest {
    static final String A=String.join("",java.util.Collections.nCopies(32,"01"));
    static final String B=String.join("",java.util.Collections.nCopies(32,"02"));
    public static void main(String[] args) throws Exception {
        AtomicInteger questions=new AtomicInteger();
        try {
            TrustPolicy.authorize(null,A,"",() -> {questions.incrementAndGet();return false;});
            throw new AssertionError("Declined first-use certificate was authorized");
        } catch(TrustPolicy.Declined expected) { }
        if(questions.get()!=1)throw new AssertionError("First-use question was not asked once");
        System.out.println("PASS first-use decline blocks authorization");
        if(!A.equals(TrustPolicy.authorize(null,A,"",() -> true)))throw new AssertionError("Accepted first pin changed");
        System.out.println("PASS explicit first-use acceptance authorizes only observed pin");
        if(!A.equals(TrustPolicy.authorize(A,A,"",() -> {throw new AssertionError("Known controller prompted again");})))throw new AssertionError();
        System.out.println("PASS known matching controller does not prompt");
        try {
            TrustPolicy.authorize(A,B,"",() -> {throw new AssertionError("Changed certificate must never get first-use prompt");});
            throw new AssertionError("Changed certificate authorized");
        }catch(IOException expected) { }
        System.out.println("PASS changed certificate blocked without acceptance callback");
        try {
            TrustPolicy.authorize(null,A,B,() -> {throw new AssertionError("Independent mismatch must not prompt");});
            throw new AssertionError("Independent fingerprint mismatch authorized");
        }catch(IOException expected) { }
        System.out.println("PASS optional independent mismatch blocks first use");
        String lowerColon=String.join(":",java.util.Collections.nCopies(32,"ab"));
        String plain=String.join("",java.util.Collections.nCopies(32,"AB"));
        if(!plain.equals(TrustPolicy.authorize(lowerColon,plain,lowerColon,() -> {throw new AssertionError();})))throw new AssertionError();
        System.out.println("PASS fingerprints normalized consistently");
        try {TrustPolicy.authorize(null,"invalid","",() -> true);throw new AssertionError("Malformed observation accepted");}catch(IllegalArgumentException expected){}
        System.out.println("PASS malformed fingerprint rejected");
        System.out.println("TRUST POLICY TESTS: 7 PASS");
    }
}
