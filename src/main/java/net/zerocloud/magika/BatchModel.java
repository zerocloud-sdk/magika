/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Equivalent reduction layout for the authenticated standard_v3_3 graph. ORT's
 * CPU ReduceSum changes accumulation order for [batch, 512, 256] when batch > 1;
 * the ensuing variance cancellation exceeds the upstream 1e-5 score tolerance.
 * Transpose to [512, batch, 256] and reduce axis 0, preserving [batch, 256].
 * Weights, input/output contracts and all other operators remain byte-for-byte
 * unchanged. This narrow protobuf wire edit runs only after asset authentication.
 */
final class BatchModel {
    private static final String PREFIX = "jax2tf_get_logits_/pjit_get_logits_/MagikaV2/LayerNorm_0/";

    private BatchModel() { }

    static byte[] withStableReductions(byte[] model) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(model.length + 1024);
        Cursor cursor = new Cursor(model);
        int graphs = 0;
        while (cursor.next()) {
            if (cursor.field == 7) { // ModelProto.graph
                writeBytes(output, 7, rewriteGraph(cursor.value()));
                graphs++;
            } else { cursor.copyTo(output); }
        }
        if (graphs != 1) { throw new IllegalStateException("Expected one authenticated model graph"); }
        return output.toByteArray();
    }

    private static byte[] rewriteGraph(byte[] graph) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(graph.length + 1024);
        Cursor cursor = new Cursor(graph);
        int changes = 0;
        while (cursor.next()) {
            if (cursor.field == 1) { // GraphProto.node
                byte[] node = cursor.value();
                String name = stringField(node, 3);
                if (name.equals(PREFIX + "Sum") || name.equals(PREFIX + "Sum_1")) {
                    if (!stringField(node, 4).equals("ReduceSum")) {
                        throw new IllegalStateException("Unexpected authenticated reduction operator");
                    }
                    String input = stringField(node, 1);
                    String transposed = name + "_batch_layout";
                    ByteArrayOutputStream transpose = new ByteArrayOutputStream();
                    writeString(transpose, 1, input);
                    writeString(transpose, 2, transposed);
                    writeString(transpose, 3, transposed);
                    writeString(transpose, 4, "Transpose");
                    ByteArrayOutputStream perm = new ByteArrayOutputStream();
                    writeString(perm, 1, "perm");
                    writeInteger(perm, 8, 1);
                    writeInteger(perm, 8, 0);
                    writeInteger(perm, 8, 2);
                    writeInteger(perm, 20, 7); // AttributeProto.INTS
                    writeBytes(transpose, 5, perm.toByteArray());
                    writeBytes(output, 1, transpose.toByteArray());

                    ByteArrayOutputStream reduction = new ByteArrayOutputStream();
                    Cursor fields = new Cursor(node);
                    int inputs = 0;
                    while (fields.next()) {
                        if (fields.field == 1) {
                            writeString(reduction, 1, inputs++ == 0 ? transposed : "const_axes__98");
                        } else { fields.copyTo(reduction); }
                    }
                    if (inputs != 2) { throw new IllegalStateException("Unexpected reduction inputs"); }
                    writeBytes(output, 1, reduction.toByteArray());
                    changes++;
                    continue;
                }
            }
            cursor.copyTo(output);
        }
        if (changes != 2) { throw new IllegalStateException("Expected both authenticated LayerNorm reductions"); }
        return output.toByteArray();
    }

    private static String stringField(byte[] message, int field) {
        Cursor cursor = new Cursor(message);
        while (cursor.next()) {
            if (cursor.field == field) { return new String(cursor.value(), StandardCharsets.UTF_8); }
        }
        return "";
    }

    private static void writeString(ByteArrayOutputStream out, int field, String value) {
        writeBytes(out, field, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(ByteArrayOutputStream out, int field, byte[] value) {
        writeVarint(out, (field << 3) | 2);
        writeVarint(out, value.length);
        out.write(value, 0, value.length);
    }

    private static void writeInteger(ByteArrayOutputStream out, int field, int value) {
        writeVarint(out, field << 3);
        writeVarint(out, value);
    }

    private static void writeVarint(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7f) != 0) { out.write((value & 0x7f) | 0x80); value >>>= 7; }
        out.write(value);
    }

    /** Copies untouched fields verbatim, including unknown protobuf fields. */
    private static final class Cursor {
        private final byte[] bytes;
        private int position;
        private int start;
        private int valueStart;
        private int field;
        private int wire;

        Cursor(byte[] bytes) { this.bytes = bytes; }

        boolean next() {
            if (position == bytes.length) { return false; }
            start = position;
            int key = Math.toIntExact(varint());
            field = key >>> 3;
            wire = key & 7;
            if (wire == 2) {
                int size = Math.toIntExact(varint());
                valueStart = position;
                position = Math.addExact(position, size);
            } else if (wire == 0) { varint(); }
            else if (wire == 1) { position = Math.addExact(position, 8); }
            else if (wire == 5) { position = Math.addExact(position, 4); }
            else { throw new IllegalStateException("Unsupported model protobuf wire type"); }
            if (position > bytes.length) { throw new IllegalStateException("Truncated model protobuf field"); }
            return true;
        }

        private long varint() {
            long result = 0;
            for (int shift = 0; shift < 64 && position < bytes.length; shift += 7) {
                int value = bytes[position++] & 255;
                result |= (long) (value & 127) << shift;
                if (value < 128) { return result; }
            }
            throw new IllegalStateException("Invalid model protobuf varint");
        }

        byte[] value() {
            if (wire != 2) { throw new IllegalStateException("Expected model protobuf message/string"); }
            return Arrays.copyOfRange(bytes, valueStart, position);
        }

        void copyTo(ByteArrayOutputStream out) { out.write(bytes, start, position - start); }
    }
}
