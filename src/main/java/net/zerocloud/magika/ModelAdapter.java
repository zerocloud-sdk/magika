/*
 * Copyright 2026 ZeroCloud SDK contributors.
 * Prediction rules adapted from Magika, Copyright 2024 Google LLC.
 * SPDX-License-Identifier: Apache-2.0
 */
package net.zerocloud.magika;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import java.nio.IntBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/** Owns native instance resources and the fixed model's inference contract. */
final class ModelAdapter implements AutoCloseable {
    private final ModelAssets assets;
    private final PredictionMode predictionMode;
    private final OrtEnvironment environment;
    private final OrtSession session;
    private final OrtSession.SessionOptions options;

    private ModelAdapter(ModelAssets assets, OrtEnvironment environment, OrtSession session,
                         OrtSession.SessionOptions options, PredictionMode predictionMode) {
        this.assets = assets;
        this.predictionMode = predictionMode;
        this.environment = environment;
        this.session = session;
        this.options = options;
    }

    static ModelAdapter create(int threads, PredictionMode predictionMode) {
        ModelAssets assets = ModelAssets.load();
        OrtSession.SessionOptions options = null;
        OrtSession session = null;
        try {
            OrtEnvironment environment = OrtEnvironment.getEnvironment();
            // Match the pinned upstream implementation before creating any session.
            environment.setTelemetry(false);
            options = new OrtSession.SessionOptions();
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
            options.setIntraOpNumThreads(threads);
            session = environment.createSession(assets.model, options);
            validateTensor(session.getInputInfo(), "bytes", OnnxJavaType.INT32,
                    ModelAssets.HEAD_SIZE + ModelAssets.TAIL_SIZE);
            validateTensor(session.getOutputInfo(), "target_label", OnnxJavaType.FLOAT,
                    ModelAssets.LABEL_COUNT);
            return new ModelAdapter(assets, environment, session, options, predictionMode);
        } catch (OrtException | RuntimeException | LinkageError failure) {
            MagikaException error = new MagikaException(MagikaException.Category.MODEL_INITIALIZATION,
                    ModelAssets.MODEL_VERSION, "Cannot initialize ONNX Runtime model session", failure);
            suppressReleaseFailure(error, session, options);
            throw error;
        } catch (Error failure) {
            suppressReleaseFailure(failure, session, options);
            throw failure;
        }
    }

    private static void validateTensor(Map<String, NodeInfo> nodes, String name,
                                       OnnxJavaType type, int width) {
        NodeInfo node = nodes.get(name);
        if (nodes.size() != 1 || node == null || !(node.getInfo() instanceof TensorInfo)) {
            throw new IllegalStateException("Model signature requires exactly one tensor named " + name);
        }
        TensorInfo info = (TensorInfo) node.getInfo();
        long[] shape = info.getShape();
        if (info.type != type || shape.length != 2 || shape[0] != -1 || shape[1] != width) {
            throw new IllegalStateException("Model signature requires " + name + " " + type
                    + " [batch, " + width + "]; got " + info);
        }
    }

    ModelInfo modelInfo() { return assets.info; }

    DetectionResult identify(InputSample sample) {
        if (sample.length == 0) {
            return ruleResult("empty");
        }
        if (sample.length < ModelAssets.MIN_MODEL_BYTES) {
            return fewBytes(sample);
        }
        int[] features = Features.extract(sample, ModelAssets.HEAD_SIZE, ModelAssets.TAIL_SIZE, ModelAssets.PADDING);
        if (features[ModelAssets.MIN_MODEL_BYTES - 1] == ModelAssets.PADDING) {
            return fewBytes(sample);
        }
        try (OnnxTensor input = OnnxTensor.createTensor(environment, IntBuffer.wrap(features),
                     new long[] {1, features.length});
             OrtSession.Result output = session.run(Collections.singletonMap("bytes", input))) {
            float[][] rows = (float[][]) output.get(0).getValue();
            if (rows.length != 1 || rows[0].length != ModelAssets.LABEL_COUNT) {
                throw new IllegalStateException("Unexpected inference output shape");
            }
            float[] scores = rows[0];
            int top = 0;
            for (int i = 0; i < scores.length; i++) {
                if (Float.isNaN(scores[i]) || scores[i] < 0 || scores[i] > 1) {
                    throw new IllegalStateException("Invalid model score at index " + i);
                }
                if (scores[i] > scores[top]) {
                    top = i;
                }
            }
            return resultForPrediction(assets, new RawPrediction(assets.labels.get(top), scores[top]), predictionMode);
        } catch (OrtException | RuntimeException | LinkageError failure) {
            throw new MagikaException(MagikaException.Category.INFERENCE,
                    ModelAssets.MODEL_VERSION + ", byteLength=" + sample.length,
                    "Cannot execute model inference", failure);
        }
    }

    // Restricted adapter contract for deterministic threshold boundary tests.
    static DetectionResult resultForPrediction(ModelAssets assets, RawPrediction raw, PredictionMode mode) {
        String label = assets.mappedLabel(raw.getLabel());
        OverwriteReason reason = label.equals(raw.getLabel())
                ? OverwriteReason.NONE : OverwriteReason.OVERWRITE_MAP;
        if (mode != PredictionMode.BEST_GUESS && raw.getScore() < assets.threshold(raw.getLabel(), mode)) {
            label = assets.contentType(label).text ? "txt" : "unknown";
            reason = label.equals(raw.getLabel()) ? OverwriteReason.NONE : OverwriteReason.LOW_CONFIDENCE;
        }
        return new DetectionResult(label, assets.contentType(label).mimeType, raw, reason, ModelAssets.MODEL_VERSION);
    }

    private DetectionResult ruleResult(String label) {
        return new DetectionResult(label, assets.contentType(label).mimeType, null,
                OverwriteReason.NONE, ModelAssets.MODEL_VERSION);
    }

    private DetectionResult fewBytes(InputSample sample) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(sample.head.duplicate());
            return ruleResult("txt");
        } catch (CharacterCodingException e) {
            return ruleResult("unknown");
        }
    }

    @Override
    public void close() {
        Throwable failure = release(session, options);
        if (failure != null) {
            if (failure instanceof Error && !(failure instanceof LinkageError)) {
                throw (Error) failure;
            }
            throw new MagikaException(MagikaException.Category.RESOURCE_RELEASE,
                    ModelAssets.MODEL_VERSION, "Cannot release native instance resources", failure);
        }
    }

    private static void suppressReleaseFailure(Throwable primary, OrtSession session,
                                               OrtSession.SessionOptions options) {
        Throwable cleanup = release(session, options);
        if (cleanup != null) {
            primary.addSuppressed(cleanup);
        }
    }

    private static Throwable release(OrtSession session, OrtSession.SessionOptions options) {
        Throwable failure = null;
        try {
            if (session != null) {
                session.close();
            }
        } catch (Throwable e) {
            failure = e;
        } finally {
            try {
                if (options != null) {
                    options.close();
                }
            } catch (Throwable e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        return failure;
    }
}
