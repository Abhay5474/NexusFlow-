import io.aetheros.sentinel.*;
import java.net.InetSocketAddress;
import java.time.Duration;

public class TestDns {
    public static void main(String[] args) {
        try (var q = new NettyProviderQuery()) {
            var p = new DnsProvider("google", new InetSocketAddress("8.8.8.8", 53));
            var ans = q.query(p, "google.com", Duration.ofSeconds(5)).toCompletableFuture().join();
            System.out.println("Success: " + ans);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
