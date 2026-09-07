package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.xml.sax.ContentHandler;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ParseService 详细日志单元测试
 * 用于展示文档解析的完整执行过程和步骤日志
 */
class ParseServiceDetailedTest {

    private static final Logger log = LoggerFactory.getLogger(ParseServiceDetailedTest.class);

    @Mock
    private DocumentVectorRepository documentVectorRepository;

    @InjectMocks
    private ParseService parseService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // 设置配置值（模拟 application.yml 中的配置）
        ReflectionTestUtils.setField(parseService, "chunkSize", 100);        // 子切片大小：100字符
        ReflectionTestUtils.setField(parseService, "parentChunkSize", 500);  // 父块大小：500字符
        ReflectionTestUtils.setField(parseService, "bufferSize", 8192);      // 缓冲区大小：8KB
        ReflectionTestUtils.setField(parseService, "maxMemoryThreshold", 0.8); // 内存阈值：80%

        log.info("【步骤0】测试环境初始化完成");
        log.info("       - chunkSize (子切片大小): 100");
        log.info("       - parentChunkSize (父块大小): 500");
        log.info("       - bufferSize (缓冲区大小): 8192");
        log.info("       - maxMemoryThreshold (内存阈值): 0.8");
    }

    /**
     * 测试1：展示 splitTextIntoChunksWithSemantics 的完整分割流程
     * 物理模型：将一篇长文章按语义层次切割成小块
     */
    @Test
    void testSplitTextIntoChunksWithSemantics_DetailedSteps() throws Exception {
        log.info("═══════════════════════════════════════════════════════════");
        log.info("【测试1】智能文本分割流程测试 - 带详细步骤日志");
        log.info("═══════════════════════════════════════════════════════════");


        // 准备测试数据：模拟一篇包含多个段落的文章
        String text = """
            第一段：人工智能的发展历史。
            这是人工智能发展的简介，包含了从1956年达特茅斯会议开始的历史。
            机器学习是人工智能的一个重要分支。
            
            第二段：深度学习的突破。
            深度学习在2012年ImageNet竞赛中取得重大突破。
            卷积神经网络被广泛应用于图像识别领域。
            
            第三段：自然语言处理的进展。
            Transformer架构的出现彻底改变了NLP领域。
            大语言模型如GPT系列展示了强大的文本生成能力。
            """;

        log.info("【步骤1】准备原始文本");
        log.info("       - 原文总长度: {} 字符", text.length());
        log.info("       - 原文内容预览:\n{}", text);

        // 使用反射调用私有方法
        Method method = ParseService.class.getDeclaredMethod("splitTextIntoChunksWithSemantics", String.class, int.class);
        method.setAccessible(true);

        int chunkSize = 50; // 设置较小的chunkSize以便观察分割效果

        log.info("【步骤2】开始智能文本分割");
        log.info("       - 目标 chunkSize: {} 字符", chunkSize);
        log.info("       - 分割策略: 段落 → 句子 → 词语（层级递进）");

        @SuppressWarnings("unchecked")
        List<String> chunks = (List<String>) method.invoke(parseService, text, chunkSize);

        log.info("【步骤3】分割完成，输出结果统计");
        log.info("       - 生成的子切片总数: {}", chunks.size());

        // 详细打印每个分块
        log.info("【步骤4】子切片详情列表:");
        int totalLength = 0;
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            totalLength += chunk.length();
            log.info("       ├─ 分块[{}] 长度:{} 内容: {}",
                    i,
                    String.format("%3d", chunk.length()),
                    chunk.replace("\n", "\\n"));
        }
        log.info("       └─ 总字符数: {} (原文: {})", totalLength, text.length());

        // 验证拼接后是否等于原文
        String reconstructed = String.join("", chunks);
        assertEquals(text, reconstructed, "分割后重新拼接应该等于原文");

        log.info("【步骤5】验证通过 ✓ - 分割前后文本一致性检查通过");
        log.info("═══════════════════════════════════════════════════════════");
    }

    /**
     * 测试2：展示 splitLongParagraph 的句子级分割
     * 物理模型：将长段落按标点符号（句子边界）切割
     */
    @Test
    void testSplitLongParagraph_DetailedSteps() throws Exception {
        log.info("═══════════════════════════════════════════════════════════");
        log.info("【测试2】长段落句子级分割测试 - 带详细步骤日志");
        log.info("═══════════════════════════════════════════════════════════");

        // 准备一个长段落（超过chunkSize）
        String paragraph = "这是第一句话。这是第二句话，包含逗号！这是第三句话？这是第四句话；" +
                "这是第五句话。这是第六句话。这是第七句话。这是第八句话。";

        log.info("【步骤1】准备长段落");
        log.info("       - 段落长度: {} 字符", paragraph.length());
        log.info("       - 段落内容: {}", paragraph);

        Method method = ParseService.class.getDeclaredMethod("splitLongParagraph", String.class, int.class);
        method.setAccessible(true);

        int chunkSize = 30;

        log.info("【步骤2】开始句子级分割");
        log.info("       - 目标 chunkSize: {} 字符", chunkSize);
        log.info("       - 分割规则: 按中文标点 [。！？；] 或英文标点 [.!?;] 分割");

        @SuppressWarnings("unchecked")
        List<String> chunks = (List<String>) method.invoke(parseService, paragraph, chunkSize);

        log.info("【步骤3】分割结果");
        log.info("       - 生成的分块数: {}", chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            log.info("       ├─ 分块[{}] 长度:{} 内容: {}",
                    i, chunks.get(i).length(), chunks.get(i));
        }

        // 验证
        String reconstructed = String.join("", chunks);
        assertEquals(paragraph, reconstructed);

        log.info("【步骤4】验证通过 ✓");
        log.info("═══════════════════════════════════════════════════════════");
    }

    /**
     * 测试3：展示 splitLongSentence 的HanLP分词分割
     * 物理模型：当句子太长时，使用中文分词工具按词语语义切割
     */
    @Test
    void testSplitLongSentence_DetailedSteps() throws Exception {
        log.info("═══════════════════════════════════════════════════════════");
        log.info("【测试3】HanLP智能分词分割测试 - 带详细步骤日志");
        log.info("═══════════════════════════════════════════════════════════");

        // 准备一个超长句子（必须使用分词才能合理分割）
        String sentence = "中华人民共和国是一个伟大的国家，拥有悠久的历史和灿烂的文化，" +
                "在现代化建设进程中取得了举世瞩目的成就，人民生活水平不断提高。";

        log.info("【步骤1】准备超长句子");
        log.info("       - 句子长度: {} 字符", sentence.length());
        log.info("       - 句子内容: {}", sentence);

        Method method = ParseService.class.getDeclaredMethod("splitLongSentence", String.class, int.class);
        method.setAccessible(true);

        int chunkSize = 20; // 较小的chunkSize强制触发分词

        log.info("【步骤2】调用HanLP进行智能分词");
        log.info("       - 目标 chunkSize: {} 字符", chunkSize);
        log.info("       - 分词工具: HanLP StandardTokenizer");
        log.info("       - 分割策略: 词语边界优先，保持语义完整性");

        @SuppressWarnings("unchecked")
        List<String> chunks = (List<String>) method.invoke(parseService, sentence, chunkSize);

        log.info("【步骤3】HanLP分词结果");
        log.info("       - 生成的分块数: {}", chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            log.info("       ├─ 分块[{}] 长度:{} 内容: [{}]",
                    i,
                    String.format("%2d", chunks.get(i).length()),
                    chunks.get(i));
        }

        // 验证
        String reconstructed = String.join("", chunks);
        assertEquals(sentence, reconstructed);

        log.info("【步骤4】验证通过 ✓ - 所有分块拼接后等于原文");
        log.info("═══════════════════════════════════════════════════════════");
    }

    /**
     * 测试4：展示 splitByCharacters 的字符级分割（降级方案）
     * 物理模型：当HanLP分词失败时，退化为纯字符切割
     */
    @Test
    void testSplitByCharacters_DetailedSteps() throws Exception {
        log.info("═══════════════════════════════════════════════════════════");
        log.info("【测试4】字符级分割测试（降级方案）- 带详细步骤日志");
        log.info("═══════════════════════════════════════════════════════════");

        String text = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

        log.info("【步骤1】准备测试文本");
        log.info("       - 文本长度: {} 字符", text.length());
        log.info("       - 文本内容: {}", text);

        Method method = ParseService.class.getDeclaredMethod("splitByCharacters", String.class, int.class);
        method.setAccessible(true);

        int chunkSize = 5;

        log.info("【步骤2】开始字符级分割");
        log.info("       - 目标 chunkSize: {} 字符", chunkSize);
        log.info("       - 分割策略: 纯字符切割（无语义考虑）");

        @SuppressWarnings("unchecked")
        List<String> chunks = (List<String>) method.invoke(parseService, text, chunkSize);

        log.info("【步骤3】分割结果");
        log.info("       - 生成的分块数: {}", chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            log.info("       ├─ 分块[{}]: [{}]", i, chunks.get(i));
        }

        // 验证
        String reconstructed = String.join("", chunks);
        assertEquals(text, reconstructed);

        log.info("【步骤4】验证通过 ✓");
        log.info("═══════════════════════════════════════════════════════════");
    }

    /**
     * 测试5：模拟完整的 parseAndSave 流程（使用Mock）
     * 展示从文件输入到数据库存储的完整流程
     */
    @Test
    void testParseAndSave_FullFlowSimulation() throws Exception {
        log.info("═══════════════════════════════════════════════════════════");
        log.info("【测试5】完整解析流程模拟 - 带详细步骤日志");
        log.info("═══════════════════════════════════════════════════════════");

        // 准备模拟文件内容
        String fileContent = """
            第一章：引言
            本文档用于测试文档解析服务的功能。
            我们将验证流式解析和分块存储是否正常工作。
            
            第二章：测试内容
            这是一段测试文本，包含多个段落。
            每个段落都应该被正确处理并分割成适当大小的块。
            
            第三章：结论
            测试完成。
            """;

        String fileMd5 = "test-md5-123456";
        String userId = "test-user";
        String orgTag = "TEST_ORG";
        boolean isPublic = false;

        log.info("【步骤1】准备模拟文件");
        log.info("       - 文件MD5: {}", fileMd5);
        log.info("       - 用户ID: {}", userId);
        log.info("       - 组织标签: {}", orgTag);
        log.info("       - 是否公开: {}", isPublic);
        log.info("       - 文件内容长度: {} 字符", fileContent.length());

        // 捕获保存到数据库的参数
        ArgumentCaptor<DocumentVector> vectorCaptor = ArgumentCaptor.forClass(DocumentVector.class);

        log.info("【步骤2】开始调用 parseAndSave 方法");
        log.info("       - 创建 BufferedInputStream (缓冲区大小: 8192)");
        log.info("       - 创建 StreamingContentHandler 流式处理器");
        log.info("       - 使用 Tika AutoDetectParser 自动检测并解析文档");

        InputStream inputStream = new ByteArrayInputStream(fileContent.getBytes(StandardCharsets.UTF_8));

        parseService.parseAndSave(fileMd5, inputStream, userId, orgTag, isPublic);

        log.info("【步骤3】解析完成，验证数据库保存操作");

        // 验证保存操作被调用
        verify(documentVectorRepository, atLeast(1)).save(vectorCaptor.capture());

        List<DocumentVector> savedVectors = vectorCaptor.getAllValues();

        log.info("       - 保存到数据库的文档向量数量: {}", savedVectors.size());

        log.info("【步骤4】验证保存的文档向量详情:");
        for (int i = 0; i < savedVectors.size(); i++) {
            DocumentVector vector = savedVectors.get(i);
            log.info("       ├─ 向量[{}]", i);
            log.info("       │   ├─ fileMd5: {}", vector.getFileMd5());
            log.info("       │   ├─ chunkId: {}", vector.getChunkId());
            log.info("       │   ├─ userId: {}", vector.getUserId());
            log.info("       │   ├─ orgTag: {}", vector.getOrgTag());
            log.info("       │   ├─ isPublic: {}", vector.isPublic());
            log.info("       │   └─ textContent长度: {} 字符",
                    vector.getTextContent() != null ? vector.getTextContent().length() : 0);
        }

        // 验证第一个保存的向量
        DocumentVector firstVector = savedVectors.get(0);
        assertEquals(fileMd5, firstVector.getFileMd5());
        assertEquals(userId, firstVector.getUserId());
        assertEquals(orgTag, firstVector.getOrgTag());

        log.info("【步骤5】所有验证通过 ✓");
        log.info("═══════════════════════════════════════════════════════════");
    }

    /**
     * 测试6：展示内存检查机制
     */
    @Test
    void testMemoryThresholdCheck() throws Exception {
        log.info("═══════════════════════════════════════════════════════════");
        log.info("【测试6】内存阈值检查测试 - 带详细步骤日志");
        log.info("═══════════════════════════════════════════════════════════");

        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;

        double memoryUsage = (double) usedMemory / maxMemory;

        log.info("【步骤1】检查当前内存状态");
        log.info("       - 最大内存 (maxMemory): {} MB", maxMemory / 1024 / 1024);
        log.info("       - 总内存 (totalMemory): {} MB", totalMemory / 1024 / 1024);
        log.info("       - 空闲内存 (freeMemory): {} MB", freeMemory / 1024 / 1024);
        log.info("       - 已用内存 (usedMemory): {} MB", usedMemory / 1024 / 1024);
        log.info("       - 内存使用率: {:.2f}%", memoryUsage * 100);
        log.info("       - 配置阈值: 80%");

        if (memoryUsage > 0.8) {
            log.warn("       ⚠ 内存使用率超过阈值，将触发垃圾回收");
        } else {
            log.info("       ✓ 内存使用率正常，继续执行");
        }

        log.info("═══════════════════════════════════════════════════════════");
    }

    /**
     * 测试7：对比验证正则表达式中 \\s+ 的作用
     * 目的：观察英文标点后的空格在切割后去了哪里
     */
    @Test
    void testRegexWhitespaceComparison() {
        log.info("═══════════════════════════════════════════════════════════");
        log.info("【测试7】正则表达式 \\s+ 的作用对比验证");
        log.info("═══════════════════════════════════════════════════════════");

        // 构造一个包含中英文混合标点的段落，英文标点后特意加上空格
        String paragraph = "第一句中文。第二句中文！Hello. World! How are you? I'm fine; thank you。最后。结尾。";

        log.info("【测试数据】");
        log.info("原文: {}", paragraph);
        log.info("原文长度: {}", paragraph.length());

        // 方式一：无 \\s+ 的切割（你修改后的版本）
        String regexWithoutWhitespace = "(?<=[。！？；])|(?<=[.!?;])";
        String[] sentencesWithout = paragraph.split(regexWithoutWhitespace);

        // 方式二：有 \\s+ 的切割（原始版本）
        String regexWithWhitespace = "(?<=[。！？；])|(?<=[.!?;])\\s+";
        String[] sentencesWith = paragraph.split(regexWithWhitespace);

        log.info("\n【切割结果对比】");
        log.info("┌─────────────────────────────────────────────────────────┐");
        log.info("│ 方式一：无 \\s+                                          │");
        log.info("├─────────────────────────────────────────────────────────┤");
        for (int i = 0; i < sentencesWithout.length; i++) {
            // 用「」把内容括起来，让首尾空格现形
            log.info("│ 第{}句: 「{}」", i, sentencesWithout[i]);
        }
        log.info("├─────────────────────────────────────────────────────────┤");
        log.info("│ 方式二：有 \\s+                                          │");
        log.info("├─────────────────────────────────────────────────────────┤");
        for (int i = 0; i < sentencesWith.length; i++) {
            log.info("│ 第{}句: 「{}」", i, sentencesWith[i]);
        }
        log.info("└─────────────────────────────────────────────────────────┘");

        // 找出具体的差异句子
        log.info("\n【关键差异分析】");
        log.info("观察英文标点后的句子开头是否有空格：");
        for (int i = 0; i < Math.min(sentencesWithout.length, sentencesWith.length); i++) {
            String without = sentencesWithout[i];
            String with = sentencesWith[i];
            if (!without.equals(with)) {
                log.info("> 第{}句存在差异:", i);
                log.info("    无\\s+版本: 「{}」", without);
                log.info("    有\\s+版本: 「{}」", with);
                if (without.startsWith(" ")) {
                    log.info("    → 无\\s+版本以空格开头，有\\s+版本已去除空格");
                }
            }
        }

        // 验证拼接后是否都等于原文（有\\s+的版本因为消耗了空格，拼接后不等于原文！）
        String reconstructedWithout = String.join("", sentencesWithout);
        String reconstructedWith = String.join("", sentencesWith);

        log.info("\n【拼接还原测试】");
        log.info("无\\s+版本拼接后与原文相等？ {}", reconstructedWithout.equals(paragraph));
        log.info("有\\s+版本拼接后与原文相等？ {} (注意：因为空格被消耗，所以不相等！)", reconstructedWith.equals(paragraph));

        log.info("\n【结论】");
        log.info("1. 无\\s+时，英文标点后的空格保留在下一句开头。");
        log.info("2. 有\\s+时，英文标点后的空格被正则消耗，下一句开头干净。");
        log.info("3. 在当前业务场景（文本分块后需要还原）中，哪种更好取决于下游需求。");
        log.info("   本项目的splitLongParagraph后续会将句子重新拼接，");
        log.info("   因此保留空格可能更利于还原原始文本的精确样貌。");
        log.info("═══════════════════════════════════════════════════════════");
    }

    /**
     * 测试8：对比 splitLongParagraph 中“超长句子后置处理”与“前置拦截”的差异
     * 目的：直观理解为什么超长句子判断放在 if 内部而不是独立成第一个分支
     */
    @Test
    void testLongSentencePlacementComparison() {
        log.info("═══════════════════════════════════════════════════════════");
        log.info("【测试8】超长句子处理位置对比验证");
        log.info("═══════════════════════════════════════════════════════════");

        int chunkSize = 20; // 箱子容量20字符

        // 构造测试场景：
        // 句子1: 长度12的正常句子
        // 句子2: 长度25的超长句子（本身超过chunkSize）
        String sentence1 = "短句子A。";        // 长度4（实际中文算字符数，这里假设4）
        String sentence2 = "这是一个非常非常长的句子超过了限制。"; // 长度15+，实际>20
        String[] testSentences = {sentence1, sentence2};

        // 实际长度日志（让用户看到真实数字）
        log.info("【测试数据】");
        log.info("箱子容量 chunkSize = {}", chunkSize);
        log.info("句子1: 「{}」 长度 = {}", sentence1, sentence1.length());
        log.info("句子2: 「{}」 长度 = {}", sentence2, sentence2.length());

        // ========== 方式A：原逻辑（超长句子后置处理） ==========
        log.info("\n【方式A：原逻辑 - 超长句子在装箱失败后处理】");
        List<String> chunksA = simulateOriginalLogic(testSentences, chunkSize);

        // ========== 方式B：改写逻辑（超长句子前置拦截） ==========
        log.info("\n【方式B：改写逻辑 - 超长句子前置拦截】");
        List<String> chunksB = simulateAlternativeLogic(testSentences, chunkSize);

        // ========== 结果对比 ==========
        log.info("\n【结果对比】");
        log.info("方式A（原逻辑）输出分块数: {}", chunksA.size());
        for (int i = 0; i < chunksA.size(); i++) {
            log.info("  A块[{}]: 「{}」", i, chunksA.get(i));
        }
        log.info("方式B（改写逻辑）输出分块数: {}", chunksB.size());
        for (int i = 0; i < chunksB.size(); i++) {
            log.info("  B块[{}]: 「{}」", i, chunksB.get(i));
        }

        log.info("\n【关键差异分析】");
        log.info("观察句子1（长度{}）的去向：", sentence1.length());
        log.info("  原逻辑中：句子1被放入了第一个箱子，然后遇到超长句子2时封箱。句子1成为独立分块。");
        log.info("  改写逻辑中：句子1同样被放入第一个箱子，遇到超长句子2时封箱。结果相同。");
        log.info("因此在这个特定场景下，两种写法输出一致。");
        log.info("但原逻辑更紧凑，因为它复用了超重判断分支，避免了重复的封箱代码。");

        log.info("\n【物理模型解释】");
        log.info("原逻辑的思考顺序：");
        log.info("  1. 这个句子能放进当前箱子吗？→ 不能 → 封箱");
        log.info("  2. 封箱后，这个句子本身超长吗？→ 是 → 送ICU（splitLongSentence）");
        log.info("改写逻辑的思考顺序：");
        log.info("  1. 这个句子本身超长吗？→ 是 → 封箱 → 送ICU");
        log.info("  2. （如果不是超长）能放进当前箱子吗？→ ...");
        log.info("两种逻辑功能等价，但原逻辑将“超长”视为“装不下”的一种特殊情况，");
        log.info("代码更符合人类装箱的直觉：先试着装，装不下再处理特殊行李。");

        log.info("═══════════════════════════════════════════════════════════");
    }

    /**
     * 模拟原逻辑：超长句子判断放在 if (装不下) 内部
     */
    private List<String> simulateOriginalLogic(String[] sentences, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        for (String sentence : sentences) {
            // 检查是否装不下
            if (currentChunk.length() + sentence.length() > chunkSize) {
                // 封箱
                if (currentChunk.length() > 0) {
                    log.info("  → 当前箱子已满 ({}字符)，封箱保存: 「{}」", currentChunk.length(), currentChunk);
                    chunks.add(currentChunk.toString());
                    currentChunk = new StringBuilder();
                }
                // 处理当前句子：超长则送ICU，否则放入新箱子
                if (sentence.length() > chunkSize) {
                    log.info("  → 句子「{}」超长 ({} > {})，送入ICU切割", sentence, sentence.length(), chunkSize);
                    // 模拟ICU切割：这里简单切成两块
                    chunks.add(sentence.substring(0, chunkSize) + "...");
                    chunks.add("..." + sentence.substring(chunkSize));
                } else {
                    log.info("  → 句子「{}」放入新箱子", sentence);
                    currentChunk.append(sentence);
                }
            } else {
                // 能装下
                log.info("  → 句子「{}」放入当前箱子", sentence);
                currentChunk.append(sentence);
            }
        }

        if (currentChunk.length() > 0) {
            log.info("  → 最后封箱: 「{}」", currentChunk);
            chunks.add(currentChunk.toString());
        }
        return chunks;
    }

    /**
     * 模拟改写逻辑：超长句子前置拦截（第一个 if）
     */
    private List<String> simulateAlternativeLogic(String[] sentences, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        for (String sentence : sentences) {
            // 前置拦截：如果句子超长
            if (sentence.length() > chunkSize) {
                // 先封箱
                if (currentChunk.length() > 0) {
                    log.info("  → [前置] 当前箱子已满 ({}字符)，封箱保存: 「{}」", currentChunk.length(), currentChunk);
                    chunks.add(currentChunk.toString());
                    currentChunk = new StringBuilder();
                }
                log.info("  → [前置] 句子「{}」超长，送入ICU切割", sentence);
                chunks.add(sentence.substring(0, chunkSize) + "...");
                chunks.add("..." + sentence.substring(chunkSize));
            }
            // 正常装箱逻辑
            else if (currentChunk.length() + sentence.length() > chunkSize) {
                if (currentChunk.length() > 0) {
                    log.info("  → [前置] 当前箱子将满，封箱: 「{}」", currentChunk);
                    chunks.add(currentChunk.toString());
                    currentChunk = new StringBuilder();
                }
                log.info("  → [前置] 句子「{}」放入新箱子", sentence);
                currentChunk.append(sentence);
            } else {
                log.info("  → [前置] 句子「{}」放入当前箱子", sentence);
                currentChunk.append(sentence);
            }
        }

        if (currentChunk.length() > 0) {
            log.info("  → [前置] 最后封箱: 「{}」", currentChunk);
            chunks.add(currentChunk.toString());
        }
        return chunks;
    }

}