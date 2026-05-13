package io.aetheros.chameleon;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClientHelloPeekTest {

    @Test
    void parsesExampleComSni() {
        ByteBuf buf = Unpooled.wrappedBuffer(syntheticClientHello("example.com"));
        assertThat(ClientHelloPeek.sni(buf)).contains("example.com");
    }

    @Test
    void emptyOnGarbage() {
        ByteBuf buf = Unpooled.wrappedBuffer(new byte[]{0, 0, 0, 0, 0});
        assertThat(ClientHelloPeek.sni(buf)).isEmpty();
    }

    @Test
    void emptyOnNonHandshake() {
        byte[] b = new byte[64];
        b[0] = 0x17; // ApplicationData, not Handshake
        assertThat(ClientHelloPeek.sni(Unpooled.wrappedBuffer(b))).isEmpty();
    }

    /** Build a minimal-but-valid TLS 1.2 ClientHello containing one SNI. */
    private static byte[] syntheticClientHello(String host) {
        byte[] hostBytes = host.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int sniListLen = 3 + hostBytes.length;       // name_type(1) + name_len(2) + name
        int sniExtLen  = 2 + sniListLen;             // list_len(2) + list
        int extsLen    = 4 + sniExtLen;              // ext_type(2) + ext_len(2) + ext_body
        int hsBodyLen  = 2 + 32 + 1 + 2 + 2 + 1 + 1 + 2 + extsLen;
        // legacy_version + random + sid_len + ciphers_len + ciphers(1) + comp_len + comp(1) + exts_len + exts
        int hsLen = 4 + hsBodyLen;                   // hs_type(1) + hs_len(3) + body
        int recLen = hsLen;

        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(5 + recLen);
        bb.put((byte) 0x16);                          // ContentType Handshake
        bb.put((byte) 0x03).put((byte) 0x03);         // legacy_record_version = TLS 1.2
        bb.putShort((short) recLen);

        bb.put((byte) 0x01);                          // ClientHello
        bb.put((byte) 0).putShort((short) hsBodyLen); // uint24 length

        bb.put((byte) 0x03).put((byte) 0x03);         // legacy_version
        bb.put(new byte[32]);                          // random
        bb.put((byte) 0);                              // session_id len = 0
        bb.putShort((short) 2).put(new byte[]{0x00, 0x35}); // 1 cipher
        bb.put((byte) 1).put((byte) 0);               // 1 compression = null

        bb.putShort((short) extsLen);                 // extensions length
        bb.putShort((short) 0x0000);                  // ext_type = SNI
        bb.putShort((short) sniExtLen);
        bb.putShort((short) sniListLen);              // server_name_list length
        bb.put((byte) 0x00);                          // name_type = host_name
        bb.putShort((short) hostBytes.length);
        bb.put(hostBytes);

        return bb.array();
    }
}
