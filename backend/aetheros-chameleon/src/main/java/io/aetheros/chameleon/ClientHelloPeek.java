package io.aetheros.chameleon;

import io.netty.buffer.ByteBuf;

import java.util.Optional;

/**
 * Read-only TLS ClientHello peek. Extracts SNI from the first inbound flight
 * for observability and routing classification. Never mutates the buffer.
 *
 * <p>TLS record layout (RFC 8446 §5.1):
 * <pre>
 *   struct {
 *     ContentType type;              // 1 byte  — 0x16 handshake
 *     ProtocolVersion legacy_ver;    // 2 bytes — 0x0301..0x0303
 *     uint16 length;                 // 2 bytes
 *     opaque fragment[length];
 *   } TLSPlaintext;
 * </pre>
 *
 * <p>Handshake fragment for ClientHello:
 * <pre>
 *   HandshakeType msg_type = 0x01;   // 1 byte
 *   uint24 length;                   // 3 bytes
 *   ProtocolVersion legacy_version;  // 2 bytes
 *   Random random;                   // 32 bytes
 *   opaque legacy_session_id&lt;0..32&gt;;
 *   CipherSuite cipher_suites&lt;2..2^16-2&gt;;
 *   opaque legacy_compression&lt;1..2^8-1&gt;;
 *   Extension extensions&lt;8..2^16-1&gt;;
 * </pre>
 *
 * <p>SNI extension (RFC 6066 §3): extension_type = 0x0000, ServerNameList of
 * one or more ServerName entries; name_type 0x00 = host_name.
 *
 * <p>This implementation is intentionally defensive: any malformed offset
 * short-circuits to {@link Optional#empty()} without throwing.
 */
public final class ClientHelloPeek {

    private static final int TLS_HANDSHAKE = 0x16;
    private static final int CLIENT_HELLO  = 0x01;
    private static final int EXT_SNI       = 0x0000;
    private static final int SNI_HOSTNAME  = 0x00;

    private ClientHelloPeek() {}

    public static Optional<String> sni(ByteBuf in) {
        int start = in.readerIndex();
        try {
            if (in.readableBytes() < 5) return Optional.empty();
            int idx = start;
            if (in.getByte(idx) != TLS_HANDSHAKE) return Optional.empty();
            idx += 3;                                  // skip type + legacy_ver
            int recLen = in.getUnsignedShort(idx); idx += 2;
            if (recLen > in.readableBytes() - 5) return Optional.empty();

            if (in.getByte(idx) != CLIENT_HELLO) return Optional.empty();
            idx += 1;
            int hsLen = in.getUnsignedMedium(idx); idx += 3;
            int hsEnd = idx + hsLen;

            idx += 2 + 32;                             // legacy_version + random
            int sidLen = in.getUnsignedByte(idx); idx += 1 + sidLen;
            int csLen  = in.getUnsignedShort(idx); idx += 2 + csLen;
            int compLen = in.getUnsignedByte(idx); idx += 1 + compLen;

            if (idx + 2 > hsEnd) return Optional.empty();
            int extTotal = in.getUnsignedShort(idx); idx += 2;
            int extEnd = idx + extTotal;

            while (idx + 4 <= extEnd) {
                int type = in.getUnsignedShort(idx); idx += 2;
                int len  = in.getUnsignedShort(idx); idx += 2;
                if (type == EXT_SNI) {
                    int listEnd = idx + len;
                    idx += 2;                          // server_name_list length
                    while (idx + 3 <= listEnd) {
                        int nameType = in.getUnsignedByte(idx); idx += 1;
                        int nameLen  = in.getUnsignedShort(idx); idx += 2;
                        if (nameType == SNI_HOSTNAME && idx + nameLen <= listEnd) {
                            byte[] dst = new byte[nameLen];
                            in.getBytes(idx, dst);
                            return Optional.of(new String(dst, java.nio.charset.StandardCharsets.US_ASCII));
                        }
                        idx += nameLen;
                    }
                    return Optional.empty();
                }
                idx += len;
            }
            return Optional.empty();
        } catch (IndexOutOfBoundsException oob) {
            return Optional.empty();
        }
    }
}
