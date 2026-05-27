package io.aetheros.aegis;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Anti-Exfiltration Data & Clipboard Guard.
 *
 * <p>Deep-inspects outbound HTTP POST payloads and raw data buffers for
 * sensitive content patterns. When a theft signature is detected, the
 * connection is immediately and irrevocably terminated.
 *
 * <p>Detected patterns include:
 * <ul>
 *   <li>Private key blocks (PEM BEGIN RSA/EC PRIVATE KEY)</li>
 *   <li>AWS/GCP/Azure credential patterns</li>
 *   <li>Windows system paths (C:\Users\, APPDATA, etc.)</li>
 *   <li>SSH private key headers</li>
 *   <li>Environment variable dumps (PATH=, HOME=, etc.)</li>
 *   <li>Credit card number patterns</li>
 * </ul>
 *
 * <p>Only the first {@link #INSPECT_BYTES} bytes of each payload are scanned
 * to bound the per-packet CPU cost — exfiltration payloads typically have
 * sensitive headers near the start of the POST body.
 */
public class ExfiltrationGuardHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOG = System.getLogger(ExfiltrationGuardHandler.class.getName());

    /** Maximum bytes to inspect per message. Beyond this, content is passed through. */
    private static final int INSPECT_BYTES = 8192;

    /** Sensitive patterns that indicate likely data exfiltration. */
    private static final List<Pattern> THREAT_PATTERNS = List.of(
        // PEM private keys
        Pattern.compile("-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----", Pattern.CASE_INSENSITIVE),
        // AWS credentials
        Pattern.compile("AKIA[0-9A-Z]{16}", Pattern.CASE_INSENSITIVE),
        Pattern.compile("aws_secret_access_key\\s*=\\s*[A-Za-z0-9/+=]{40}", Pattern.CASE_INSENSITIVE),
        // Windows paths
        Pattern.compile("C:\\\\Users\\\\[^\\\\\\s]+\\\\(Desktop|Documents|Downloads)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("%APPDATA%|%USERPROFILE%|%SYSTEMROOT%", Pattern.CASE_INSENSITIVE),
        // SSH headers
        Pattern.compile("ssh-rsa AAAA[A-Za-z0-9+/=]+", Pattern.CASE_INSENSITIVE),
        // Credit card patterns (16-digit)
        Pattern.compile("\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|3[47][0-9]{13})\\b"),
        // Generic API key patterns
        Pattern.compile("(api[-_]?key|apikey|bearer|authorization)\\s*[:=]\\s*[A-Za-z0-9\\-_.]{20,}", Pattern.CASE_INSENSITIVE),
        // Environment variable dumps
        Pattern.compile("(PATH|HOME|SHELL|USER)=[^&\\n]{10,}", Pattern.CASE_INSENSITIVE),
        // Password fields
        Pattern.compile("(password|passwd|secret|credential)\\s*[:=]\\s*[^&\\s]{8,}", Pattern.CASE_INSENSITIVE)
    );

    private final AtomicLong inspected     = new AtomicLong(0);
    private final AtomicLong exfilBlocked  = new AtomicLong(0);

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof ByteBuf buf) {
            int readableBytes = Math.min(buf.readableBytes(), INSPECT_BYTES);
            if (readableBytes > 0) {
                // Extract a slice for inspection without consuming the buf
                byte[] sample = new byte[readableBytes];
                buf.getBytes(buf.readerIndex(), sample);
                String text = new String(sample, StandardCharsets.ISO_8859_1);

                inspected.incrementAndGet();

                for (Pattern pattern : THREAT_PATTERNS) {
                    if (pattern.matcher(text).find()) {
                        exfilBlocked.incrementAndGet();
                        LOG.log(Level.WARNING,
                                "[EXFIL-GUARD] BLOCKED potential exfiltration — pattern={0} channel={1}",
                                pattern.pattern(), ctx.channel().remoteAddress());
                        ctx.channel().close().addListener(ChannelFutureListener.CLOSE);
                        return; // drop the buffer — do NOT call release here, channel.close() handles it
                    }
                }
            }
        }
        super.channelRead(ctx, msg);
    }

    public long getInspectedCount()    { return inspected.get(); }
    public long getExfilBlockedCount() { return exfilBlocked.get(); }
}
