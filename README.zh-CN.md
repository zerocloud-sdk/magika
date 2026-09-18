# Magika Java SDK

[English](README.md) | **简体中文**

在 Java 应用中根据文件内容识别文件类型。本项目由 ZeroCloud SDK 独立维护，
内置 Google 的 `standard_v3_3` Magika 模型，可离线识别完整字节数组、普通文件、
输入流和有序文件批次，并支持多个请求并发复用同一实例。

[Maven Central](https://central.sonatype.com/artifact/net.zerocloud/magika/0.1.0) ·
[v0.1.0 发布记录](https://github.com/zerocloud-sdk/magika/releases/tag/v0.1.0) ·
[支持的类型](docs/supported-types.md) · [示例代码](examples/offline)

## 目录

- [运行环境](#运行环境)
- [安装依赖](#安装依赖)
- [快速开始](#快速开始)
- [选择输入方式](#选择输入方式)
- [配置选项](#配置选项)
- [预测模式](#预测模式)
- [理解识别结果](#理解识别结果)
- [处理异常](#处理异常)
- [实例复用与关闭](#实例复用与关闭)
- [离线部署与常见问题](#离线部署与常见问题)
- [运行示例项目](#运行示例项目)
- [版本与参考资料](#版本与参考资料)
- [许可证](#许可证)

## 运行环境

| 项目 | 要求 |
| --- | --- |
| 应用运行时 | 兼容 Java 8；已在 Java 8、17、21 上验证 |
| 已验证的平台 | Ubuntu 24.04 x64、CPU，使用 ONNX Runtime 1.30.0 |
| 运行时依赖 | 由 Maven 或 Gradle 自动解析传递依赖 |
| 网络 | 获取依赖时需要网络；依赖就绪后可离线识别 |
| 从源码构建本 SDK | JDK 21、Maven 3.8.7 或更高版本，详见[开发指南](docs/development.md) |

使用已发布的依赖无需自行构建 SDK，也无需安装 Python。本项目尚未验证其他操作系统、
Linux 发行版、处理器架构、libc 版本或 GPU 执行环境。

## 安装依赖

### Maven

在项目的 `<dependencies>` 中添加以下依赖。制品已发布至 Maven Central，无需配置额外仓库。

```xml
<dependency>
  <groupId>net.zerocloud</groupId>
  <artifactId>magika</artifactId>
  <version>0.1.0</version>
</dependency>
```

### Gradle

对于已应用 Java 插件的项目，在 `build.gradle`（Groovy DSL）中添加：

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation 'net.zerocloud:magika:0.1.0'
}
```

使用 `build.gradle.kts`（Kotlin DSL）时：

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("net.zerocloud:magika:0.1.0")
}
```

SDK JAR 已包含模型资产。运行时仍需保留传递依赖中的 ONNX Runtime 和 Gson；
仅复制 SDK JAR 不足以运行。

## 快速开始

添加依赖后，将以下代码保存为应用源码目录中的 `QuickStart.java`，
通过 IDE 或构建工具运行 `QuickStart.main`：

```java
import java.nio.charset.StandardCharsets;
import net.zerocloud.magika.DetectionResult;
import net.zerocloud.magika.Magika;

public class QuickStart {
    public static void main(String[] args) {
        byte[] content = "%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<<>>\n%%EOF\n"
                .getBytes(StandardCharsets.US_ASCII);
        DetectionResult result;
        try (Magika magika = Magika.create()) {
            result = magika.identify(content);
        }
        System.out.println(result.getLabel());
        System.out.println(result.getMimeType());
    }
}
```

预期输出：

```text
pdf
application/pdf
```

识别结果是不可变的 Java 值，关闭 SDK 后仍可使用。在服务中，应在启动时创建一个实例，
供多个请求复用，详见[实例复用与关闭](#实例复用与关闭)。

## 选择输入方式

| 输入 | API | 适用场景 |
| --- | --- | --- |
| 内存中的完整内容 | `identify(byte[])` | 已持有文件的完整字节数组 |
| 已保存的普通文件 | `identify(Path)` | 落盘后的上传文件或大文件；仅抽样读取首尾窗口 |
| 调用方持有的输入流 | `identify(InputStream)` | 请求体或其他流；从当前位置读取至 EOF |
| 惰性文件路径序列 | `identifyAll(Iterator<Path>, Consumer<BatchItemResult>)` | 大量文件；按输入顺序回调，工作存储受当前批次约束 |

后续 Java 示例列出了所需的 import，后面的执行语句应放在方法体内。
文件和流示例要求 `uploads/completed-upload.bin` 已存在，批量示例要求 `uploads` 目录已存在。
应用需自行捕获或声明 `Files` 操作产生的受检 I/O 异常。

### 完整字节数组

快速开始使用了 `identify(byte[])`。请传入包含尾部的**完整内容**，
并在调用返回前保持数组不变。SDK 不会修改或保留该数组，也不会复制整个输入。

### 已保存的文件

先完成上传文件的写入，再进行识别：

```java
import java.nio.file.Path;
import java.nio.file.Paths;
import net.zerocloud.magika.DetectionResult;
import net.zerocloud.magika.Magika;

Path savedUpload = Paths.get("uploads", "completed-upload.bin");
try (Magika magika = Magika.create()) {
    DetectionResult result = magika.identify(savedUpload);
    System.out.println(result.getLabel() + " " + result.getMimeType());
}
```

SDK 会跟随符号链接，且只接受普通文件。文件不存在、不可读或并非普通文件时，
抛出类别为 `INPUT` 的 `MagikaException`。SDK 自行打开并关闭文件句柄，失败时也会关闭。

文件默认没有大小上限。抽样使用首尾各最多 4096 字节的窗口，抽样内存不会随文件大小增长；
这一范围不包含模型、ORT 和应用缓冲区的内存。识别期间应保持文件、路径及符号链接目标稳定：
SDK 不创建快照，也无法检测所有并发修改。能够观察到的读取异常或大小变化会导致失败，不会重试。

[上传文件落盘示例](examples/offline/src/main/java/example/SavedUploadExample.java)
展示了如何将上传内容保存至临时文件、进行识别并清理文件。

### 输入流

```java
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import net.zerocloud.magika.DetectionResult;
import net.zerocloud.magika.Magika;

try (InputStream upload = Files.newInputStream(Paths.get("uploads", "completed-upload.bin"));
     Magika magika = Magika.builder().maxStreamBytes(128L * 1024 * 1024).build()) {
    DetectionResult result = magika.identify(upload);
    System.out.println(result.getLabel() + " " + result.getMimeType());
}
```

- 识别会**从流的当前位置读取至 EOF**。相同内容的结果与完整字节数组一致。
- SDK **从不关闭或重置输入流**，失败时也一样。上例由应用的资源管理代码关闭流。
  后续还需读取内容时，请提前安排保存或重新打开。
- 默认上限为 **64 MiB（67,108,864 字节）**，上例将其提高到 128 MiB。
  SDK 最多多读取一个字节来判断是否超限；超限后抛出 `INPUT` 异常，
  不会把截断后的前缀当作完整内容识别。
- 抽样使用两个各最多 4096 字节的窗口和一个 4096 字节工作缓冲区。
  SDK 不会缓存整个流或将其保存至临时文件；这一内存范围不包含模型、ORT 和调用方的缓冲区。
- 阻塞读取的超时应在输入源处配置，例如 HTTP 连接；SDK 不设置读取超时。

[上传流示例](examples/offline/src/main/java/example/StreamUploadExample.java)
展示了先读取应用协议头，再识别流中剩余内容的用法。

### 有序批量识别

`identifyAll` 在调用线程上同步执行。应用提供迭代器、处理每条结果，
并负责管理目录遍历所使用的资源：

```java
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import net.zerocloud.magika.BatchIdentificationException;
import net.zerocloud.magika.BatchSummary;
import net.zerocloud.magika.Magika;

try (Stream<Path> paths = Files.walk(Paths.get("uploads"));
     Magika magika = Magika.builder().batchSize(32).build()) {
    try {
        BatchSummary summary = magika.identifyAll(
                paths.filter(Files::isRegularFile).iterator(), item -> {
                    if (item.isSuccess()) {
                        System.out.println(item.getInputIndex() + ": "
                                + item.getResult().get().getLabel());
                    } else {
                        System.err.println(item.getPath() + ": "
                                + item.getError().get().getMessage());
                    }
                });
        System.out.println("Delivered " + summary.getDeliveredCount()
                + ", successful " + summary.getSuccessCount()
                + ", file failures " + summary.getFailureCount());
    } catch (BatchIdentificationException error) {
        System.err.println("Aborted at " + error.getStage()
                + ", delivered " + error.getDeliveredCount()
                + ", input index " + error.getInputIndex());
    }
}
```

回调按输入顺序执行，索引为从零开始的 `long`。重复路径作为不同输入分别处理。
`item.getResult()` 和 `item.getError()` 中恰有一个存在；
`empty` 和 `unknown` 都算成功。逐文件的 `INPUT` 错误通过回调交付，后续文件继续处理。
上例仅保留普通文件；若未过滤目录，目录会作为逐文件错误交付。

SDK 只保留当前批次，不创建工作线程池。回调较慢时，下一批输入的读取也会推迟。
模型、ORT 和应用自行保留的结果所用内存不计入批次工作存储。

迭代器失败、空元素、系统或推理失败、回调抛出异常，以及观察到线程中断，
都会终止本次调用并抛出 `BatchIdentificationException`：

| 方法 | 含义 |
| --- | --- |
| `getStage()` | 失败阶段：`ITERATION`、`IDENTIFICATION`、`CALLBACK` 或 `INTERRUPTED` |
| `getDeliveredCount()` | 终止前正常返回的回调次数 |
| `getInputIndex()` | 可选的失败输入索引；无法确定单个输入时为空 |

此前已交付的结果仍然有效。迭代器可能已读取超过交付进度的输入，
抛出异常的回调也可能已经产生副作用。恢复前应核对这些状态，SDK 不会重试或回滚。
线程中断在操作间的检查点被观察到，中断标记会保留，但无法强制终止阻塞读取、回调或原生推理。

[批量示例](examples/offline/src/main/java/example/BatchExample.java)
展示了缺失文件、重复路径，以及回调失败时的准确交付进度。

## 配置选项

`Magika.create()` 等价于 `Magika.builder().build()`。

| Builder 方法 | 默认值 | 取值与作用 |
| --- | --- | --- |
| `predictionMode(PredictionMode)` | `HIGH_CONFIDENCE` | 下文三种预测模式之一，不可为 null |
| `maxStreamBytes(long)` | `67108864`（64 MiB） | 非负数；0 仅接受剩余内容为空的流；支持 `Long.MAX_VALUE`；只限制流输入 |
| `batchSize(int)` | `32` | 每批 1–262143 个输入位置 |
| `intraOpThreads(int)` | `1` | 正整数，控制 ORT 算子内部线程数；计算图仍顺序执行 |

```java
import net.zerocloud.magika.Magika;
import net.zerocloud.magika.PredictionMode;

try (Magika magika = Magika.builder()
        .predictionMode(PredictionMode.HIGH_CONFIDENCE)
        .maxStreamBytes(128L * 1024 * 1024)
        .batchSize(32)
        .intraOpThreads(2)
        .build()) {
    System.out.println(magika.getModelInfo().getSdkVersion());
    System.out.println(magika.getModelInfo().getModelVersion());
}
```

Builder 可变且非线程安全。实例构建完成后保留独立配置，后续修改 Builder 不会影响已有实例。
非法配置抛出 `IllegalArgumentException`。构建过程会验证内置模型资产并初始化原生会话，
成功后才返回实例，期间不会下载任何内容。

## 预测模式

| 模式 | 何时保留映射后的模型预测 |
| --- | --- |
| `HIGH_CONFIDENCE`（默认） | 分数达到原始类型标签对应的阈值；未单独配置阈值时使用中等置信度阈值 |
| `MEDIUM_CONFIDENCE` | 分数达到统一阈值，当前为 0.5 |
| `BEST_GUESS` | 始终保留，包括低分预测 |

所有模式都会应用内置的类型映射，例如 `randomtxt` → `txt`、
`randombytes` → `unknown`。HIGH/MEDIUM 模式下，分数严格低于阈值时，
会根据映射后类型的文本属性回退为 `txt` 或 `unknown`；等于阈值时保留映射后的类型。
阈值不可自定义。空内容和短内容规则在三种模式下行为一致。

下面使用一个类型不明确的输入比较三种模式：

```java
import net.zerocloud.magika.DetectionResult;
import net.zerocloud.magika.Magika;
import net.zerocloud.magika.PredictionMode;

byte[] content = {0, 1, 2, 3, 4, 5, 6, 7};
for (PredictionMode mode : PredictionMode.values()) {
    try (Magika magika = Magika.builder().predictionMode(mode).build()) {
        DetectionResult result = magika.identify(content);
        System.out.println(mode + ": " + result.getLabel()
                + " (" + result.getOverwriteReason() + ")");
    }
}
```

```text
HIGH_CONFIDENCE: unknown (LOW_CONFIDENCE)
MEDIUM_CONFIDENCE: unknown (LOW_CONFIDENCE)
BEST_GUESS: wasm (NONE)
```

三种模式都保留相同的原始预测：类型标签为 `wasm`，分数约为 0.3142。

## 理解识别结果

| `DetectionResult` 方法 | 含义 |
| --- | --- |
| `getLabel()` | 最终类型标签，如 `pdf`、`txt`、`empty` 或 `unknown` |
| `getMimeType()` | 最终类型标签对应的 MIME 字符串，如 `application/pdf` |
| `getScore()` | 模型原始最高分；未执行推理而由规则直接返回时为 1.0 |
| `getRawPrediction()` | `Optional<RawPrediction>`；执行过模型时包含原始类型标签和分数 |
| `getOverwriteReason()` | 改写原因：`NONE`、`OVERWRITE_MAP` 或 `LOW_CONFIDENCE` |
| `isModelUsed()` | 是否执行了模型推理 |
| `getModelVersion()` | 模型版本，当前为 `standard_v3_3` |

**分数不是最终 MIME 类型的概率。** 类型映射和低置信度回退后仍保留原始最高分。
空内容和短内容规则返回的分数为 1.0，没有原始预测，
`isModelUsed() == false`，改写原因为 `NONE`。
读取原始预测前应检查 Optional 是否有值，或使用 `ifPresent`。

`OVERWRITE_MAP` 表示类型映射改变了标签；`LOW_CONFIDENCE` 表示因分数不足而改变了标签，
两者同时适用时以后者为准。最终类型标签与原始标签相同时，原因为 `NONE`。
`BEST_GUESS` 不会返回 `LOW_CONFIDENCE`。

`unknown` 表示识别正常完成，但无法给出更具体的类型；`empty` 表示零字节内容。
两者都是成功结果。完整词表及 MIME 字符串见
[214 种可能的最终类型标签](docs/supported-types.md)。
该词表不代表已经测量并保证每一种类型的识别准确率。

## 处理异常

`MagikaException` 是非受检异常，提供 `getCategory()`、`getContext()`，
以及存在时的原始 `getCause()`；清理资源时的异常可能出现在 `getSuppressed()` 中。
输入或推理失败会抛出异常，不会转换为 `unknown` 识别结果。

```java
import java.nio.file.Paths;
import net.zerocloud.magika.Magika;
import net.zerocloud.magika.MagikaException;

try (Magika magika = Magika.create()) {
    System.out.println(magika.identify(Paths.get("uploads", "completed-upload.bin")).getLabel());
} catch (MagikaException error) {
    System.err.println(error.getCategory() + ": " + error.getContext());
    error.printStackTrace();
}
```

| 失败情况 | 异常／类别 |
| --- | --- |
| 参数为 null 或 Builder 配置非法 | `IllegalArgumentException` |
| 关闭中或关闭后进行识别；在当前线程的活动调用内关闭实例 | `IllegalStateException` |
| 模型资产缺失、损坏或不一致 | `MagikaException` / `ASSET_VALIDATION` |
| 原生库加载、会话初始化或模型签名验证失败 | `MagikaException` / `MODEL_INITIALIZATION` |
| 文件或流读取失败、非普通文件、文件句柄关闭失败或流超限 | `MagikaException` / `INPUT` |
| 模型推理或输出失败 | `MagikaException` / `INFERENCE` |
| 原生资源释放失败 | `MagikaException` / `RESOURCE_RELEASE` |
| 批量调用终止 | `BatchIdentificationException` / `BATCH`，附带阶段和进度 |

批量识别应在回调中处理逐文件错误，在 `catch` 中处理整次调用终止，
见[有序批量识别](#有序批量识别)。

## 实例复用与关闭

多个请求和线程可共享同一个 `Magika` 实例。各识别方法共用原生会话，
每次调用独立管理抽样、张量和结果。任务调度、可变输入和回调状态由应用负责。
创建实例还会关闭 JVM 共享 ORT 环境的遥测，这也会影响该环境的其他使用方。

服务启动时创建实例；关闭时先停止接收应用请求，等待排队任务完成，再调用 `close()`。
任务进入应用队列不代表已被 SDK 接收，只有进入识别方法时才开始接收。
[共享实例示例](examples/offline/src/main/java/example/SharedInstanceExample.java)
展示了使用四个应用工作线程处理 16 个请求并关闭实例的过程。

生命周期为 **OPEN → CLOSING → CLOSED**。开始关闭后，新的识别调用会在读取输入
或消费迭代器之前被拒绝；已经接收的调用全部结束后，才释放会话及其配置资源。
已接收的批量调用包括其全部剩余输入和回调。结果与 `getModelInfo()` 在关闭后仍可使用。

`close()` 没有超时，不会强制终止阻塞输入、回调或原生推理。
请在输入源配置超时，并保证回调能够结束。
在当前线程的活动调用内执行 `close()` 会抛出 `IllegalStateException`，且不会改变生命周期。
回调也不能等待另一个线程关闭同一实例，否则会形成死锁。

重复或并发关闭只执行一次资源释放，JVM 共享的 ORT 环境保持可用。
关闭线程被中断时仍会等待完成，并在退出前恢复中断标记。
资源释放失败后，实例仍处于关闭状态，其他自有资源仍会尝试清理；
后续关闭调用会再次报告该失败，不会重试释放。

## 离线部署与常见问题

断网前请完成 SDK **及全部运行时依赖**的解析和打包。
主 JAR 包含模型、配置、类型元数据、资产清单及声明；
ONNX Runtime、Gson 和 Gson 引入的 Error Prone annotations 依赖仍是独立 JAR。
识别时不需要网络或 Python，也不会下载模型。

| 现象或问题 | 排查方式 |
| --- | --- |
| 原生库加载失败 | 查看 `MODEL_INITIALIZATION` 的底层原因。ORT 会将原生库解压到可写的临时目录，该目录必须允许加载原生库。 |
| 需要自行提供原生库 | `-Donnxruntime.native.path=/path/to/libraries` 指向已存在的兼容原生库目录，不用于指定解压目录。 |
| 流在约 64 MiB 时失败 | 检查 `maxStreamBytes`；提高上限，或先完整保存上传文件再传入 `Path`。 |
| 识别后无法再次读取内容 | 流已经被消费；请安排重放、重新打开输入源，或在识别前保存内容。 |
| 结果为 `txt` 或 `unknown` | 查看原始预测、预测模式和改写原因；空内容或短内容规则可能完全不执行模型。 |
| 关闭实例一直等待 | 检查仍在运行的阻塞输入和回调，在应用中安排超时和完成机制。 |

短内容规则采用严格 UTF-8 解码，因此非法 UTF-8 可能得到 `unknown`。
MIME 字符串保留内置元数据中的原值，例如 OCaml 对应 `text-ocaml`。

## 运行示例项目

克隆仓库后，在仓库根目录使用 JDK 21 和 Maven 执行以下命令。
独立示例项目会基于已发布的 SDK 依赖进行构建：

```sh
mvn -B -ntp -f examples/offline/pom.xml clean package
java -cp 'examples/offline/target/offline-byte-array-1.0.0.jar:examples/offline/target/dependency/*' \
  example.OfflineExample
```

上述 classpath 命令适用于已验证的 Linux 环境。示例会依次运行字节数组、
上传文件落盘、输入流、批量、共享实例和预测模式的用法。
如需使用其他已安装的 JVM，将 `java` 替换为该 JVM 的 `bin/java`。

## 版本与参考资料

`getModelInfo()` 提供 SDK 版本、模型版本、上游提交和不可变的资产摘要映射。
SDK **0.1.0** 内置模型 **standard_v3_3**，二者是独立的版本标识。
模型资产随 SDK 一起发布，模型或识别规则更新需通过兼容性验证并发布新的 SDK 版本。

问题修复递增补丁版本；新增能力、模型或规则更新，以及 0.x 阶段的破坏性 API 变更，
至少递增次版本。破坏性变更会附带迁移说明。已发布的制品坐标不会被覆盖。

- [支持的类型标签与 MIME](docs/supported-types.md)
- [开发、验证与模型来源](docs/development.md)（英文）
- [发布与分发指南](docs/releasing.md)（英文）
- [0.1.0 验收记录](docs/verification-issue-10.md)（英文）
- [性能测量](docs/performance/issue-8-v1.md)（英文；结果适用于记录中的环境和样本集）
- [报告问题](https://github.com/zerocloud-sdk/magika/issues)

公开 API 的 Javadoc 包含在已发布的 Javadoc JAR 中；
构建 SDK 时也会生成到本地的 `target/reports/apidocs/`。

## 许可证

项目自身代码及 Google Magika 的改编代码和资产采用 [Apache-2.0](LICENSE)。
上游归属声明见 [NOTICE](NOTICE)。本 SDK 由 ZeroCloud SDK 独立维护，并非 Google 产品。
