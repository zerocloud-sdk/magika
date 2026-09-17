/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxTensorLike;
import ai.onnxruntime.OrtSession;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Test-only observation/fault fixture at ORT's public run(Map) boundary. No SDK
 * classes, fields or private native handles are inspected or modified. Normal
 * calls execute the real native model. A supplied wrong-shape tensor lets ORT
 * itself generate a real native failure, without closing an in-use SDK instance.
 */
public final class OrtProbeAgent {
    static boolean installed;
    static final List<OnnxTensor> inputs = new ArrayList<>();
    static final List<OrtSession.Result> outputs = new ArrayList<>();
    static OnnxTensor replacement;
    static int failOnRun = -1;
    private static final String RESULT = "Lai/onnxruntime/OrtSession$Result;";
    private static final String AGENT = "net/zerocloud/magika/OrtProbeAgent";

    public static void premain(String args, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                                               ProtectionDomain domain, byte[] bytes) {
                if (!name.equals("ai/onnxruntime/OrtSession")) { return null; }
                ClassReader reader = new ClassReader(bytes);
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                                 String signature, String[] exceptions) {
                        MethodVisitor original = super.visitMethod(access, name, descriptor, signature, exceptions);
                        if (!name.equals("run") || !descriptor.equals("(Ljava/util/Map;)" + RESULT)) { return original; }
                        installed = true;
                        return new MethodVisitor(Opcodes.ASM9, original) {
                            @Override public void visitCode() {
                                super.visitCode();
                                visitVarInsn(Opcodes.ALOAD, 1);
                                visitMethodInsn(Opcodes.INVOKESTATIC, AGENT, "beforeRun",
                                        "(Ljava/util/Map;)Ljava/util/Map;", false);
                                visitVarInsn(Opcodes.ASTORE, 1);
                            }
                            @Override public void visitInsn(int opcode) {
                                if (opcode == Opcodes.ARETURN) {
                                    super.visitInsn(Opcodes.DUP);
                                    visitMethodInsn(Opcodes.INVOKESTATIC, AGENT, "afterRun", "(" + RESULT + ")V", false);
                                }
                                super.visitInsn(opcode);
                            }
                        };
                    }
                }, 0);
                return writer.toByteArray();
            }
        });
    }

    public static Map<String, ? extends OnnxTensorLike> beforeRun(Map<String, ? extends OnnxTensorLike> values) {
        inputs.add((OnnxTensor) values.get("bytes"));
        return inputs.size() == failOnRun ? Collections.singletonMap("bytes", replacement) : values;
    }

    public static void afterRun(OrtSession.Result output) { outputs.add(output); }

    static void reset() {
        inputs.clear();
        outputs.clear();
        failOnRun = -1;
        replacement = null;
    }
}
