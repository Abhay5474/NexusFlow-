package io.aetheros.ironclad;

import io.netty.util.AttributeKey;

/**
 * Netty {@link AttributeKey} constants for IRONCLAD raw packet metadata
 * attached to channels during IP header parsing.
 */
public final class IroncladAttributeKeys {

    private IroncladAttributeKeys() {}

    public static final AttributeKey<String>  SRC_IP    = AttributeKey.valueOf("ironclad.srcIp");
    public static final AttributeKey<String>  DST_IP    = AttributeKey.valueOf("ironclad.dstIp");
    public static final AttributeKey<Integer> IP_PROTO  = AttributeKey.valueOf("ironclad.ipProto");
    public static final AttributeKey<Integer> SRC_PORT  = AttributeKey.valueOf("ironclad.srcPort");
    public static final AttributeKey<Integer> DST_PORT  = AttributeKey.valueOf("ironclad.dstPort");
}
