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
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Test-only observation/fault fixture at ORT's public run and resource boundaries. No SDK
 * classes, fields or private native handles are inspected or modified. Normal
 * calls execute the real native model. A supplied wrong-shape tensor lets ORT
 * itself generate a real native failure, without closing an in-use SDK instance.
 */
public final class OrtProbeAgent {
    static volatile boolean installed;
    static final List<OnnxTensor> inputs = Collections.synchronizedList(new ArrayList<>());
    static final List<OrtSession.Result> outputs = Collections.synchronizedList(new ArrayList<>());
    static final List<OrtSession> sessions = Collections.synchronizedList(new ArrayList<>());
    static final List<Object> closingResources = Collections.synchronizedList(new ArrayList<>());
    static final List<Object> closedResources = Collections.synchronizedList(new ArrayList<>());
    static volatile OnnxTensor replacement;
    static volatile int failOnRun = -1;
    static volatile BiConsumer<OrtSession, OnnxTensor> runHook;
    static volatile Consumer<OrtSession.Result> resultHook;
    static volatile Consumer<Object> beforeCloseHook;
    static volatile Consumer<Object> afterCloseHook;
    static volatile Runnable initializationHook;
    static volatile Runnable configurationHook;
    private static final String RESULT = "Lai/onnxruntime/OrtSession$Result;";
    private static final String AGENT = "net/zerocloud/magika/OrtProbeAgent";

    public static void premain(String args, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                                               ProtectionDomain domain, byte[] bytes) {
                if (!name.equals("ai/onnxruntime/OrtSession")
                        && !name.equals("ai/onnxruntime/OrtSession$SessionOptions")
                        && !name.equals("ai/onnxruntime/OrtEnvironment")) { return null; }
                final String owner = name;
                ClassReader reader = new ClassReader(bytes);
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                                 String signature, String[] exceptions) {
                        MethodVisitor original = super.visitMethod(access, name, descriptor, signature, exceptions);
                        if (name.equals("close") && descriptor.equals("()V")) {
                            return new MethodVisitor(Opcodes.ASM9, original) {
                                @Override public void visitCode() {
                                    super.visitCode();
                                    visitVarInsn(Opcodes.ALOAD, 0);
                                    visitMethodInsn(Opcodes.INVOKESTATIC, AGENT, "beforeClose", "(Ljava/lang/Object;)V", false);
                                }
                                @Override public void visitInsn(int opcode) {
                                    if (opcode == Opcodes.RETURN) {
                                        visitVarInsn(Opcodes.ALOAD, 0);
                                        visitMethodInsn(Opcodes.INVOKESTATIC, AGENT, "afterClose", "(Ljava/lang/Object;)V", false);
                                    }
                                    super.visitInsn(opcode);
                                }
                            };
                        }
                        boolean signatureCheck = owner.equals("ai/onnxruntime/OrtSession")
                                && name.equals("getInputInfo") && descriptor.equals("()Ljava/util/Map;");
                        boolean configuration = owner.endsWith("$SessionOptions")
                                && name.equals("setIntraOpNumThreads") && descriptor.equals("(I)V");
                        if (signatureCheck || configuration) {
                            return new MethodVisitor(Opcodes.ASM9, original) {
                                @Override public void visitCode() {
                                    super.visitCode();
                                    visitMethodInsn(Opcodes.INVOKESTATIC, AGENT,
                                            signatureCheck ? "beforeSignature" : "beforeConfiguration", "()V", false);
                                }
                            };
                        }
                        if (!name.equals("run") || !descriptor.equals("(Ljava/util/Map;)" + RESULT)) { return original; }
                        installed = true;
                        return new MethodVisitor(Opcodes.ASM9, original) {
                            @Override public void visitCode() {
                                super.visitCode();
                                visitVarInsn(Opcodes.ALOAD, 0);
                                visitVarInsn(Opcodes.ALOAD, 1);
                                visitMethodInsn(Opcodes.INVOKESTATIC, AGENT, "beforeRun",
                                        "(Ljava/lang/Object;Ljava/util/Map;)Ljava/util/Map;", false);
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

    // Object avoids eagerly loading OrtSession when the JVM resolves premain's
    // declaring class methods, before the transformer has been registered.
    public static Map<String, ? extends OnnxTensorLike> beforeRun(Object source,
                                                                Map<String, ? extends OnnxTensorLike> values) {
        OrtSession session = (OrtSession) source;
        OnnxTensor input = (OnnxTensor) values.get("bytes");
        boolean fail;
        synchronized (inputs) {
            inputs.add(input);
            sessions.add(session);
            fail = inputs.size() == failOnRun;
        }
        BiConsumer<OrtSession, OnnxTensor> hook = runHook;
        if (hook != null) { hook.accept(session, input); }
        return fail ? Collections.singletonMap("bytes", replacement) : values;
    }

    public static void afterRun(OrtSession.Result output) {
        outputs.add(output);
        Consumer<OrtSession.Result> hook = resultHook;
        if (hook != null) { hook.accept(output); }
    }

    public static void beforeClose(Object resource) {
        closingResources.add(resource);
        Consumer<Object> hook = beforeCloseHook;
        if (hook != null) { hook.accept(resource); }
    }

    public static void afterClose(Object resource) {
        closedResources.add(resource);
        Consumer<Object> hook = afterCloseHook;
        if (hook != null) { hook.accept(resource); }
    }

    public static void beforeSignature() {
        Runnable hook = initializationHook;
        if (hook != null) { hook.run(); }
    }

    public static void beforeConfiguration() {
        Runnable hook = configurationHook;
        if (hook != null) { hook.run(); }
    }

    static void reset() {
        inputs.clear();
        outputs.clear();
        sessions.clear();
        closingResources.clear();
        closedResources.clear();
        failOnRun = -1;
        replacement = null;
        runHook = null;
        resultHook = null;
        beforeCloseHook = null;
        afterCloseHook = null;
        initializationHook = null;
        configurationHook = null;
    }
}
