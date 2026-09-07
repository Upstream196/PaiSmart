package com.yizhaoqi.smartpai.benchmark;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.service.HybridSearchService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Benchmark 主测试类 —— 运行 RAG 检索策略对比测试（v2）。
 *
 * 执行流程：
 * 1. 加载 120+ 组 QA 测试数据集
 * 2. 分别使用「纯语义检索」和「混合检索」对每个 query 执行搜索
 * 3. 对比检索结果与 Ground Truth，计算 Recall/Precision/延迟等指标
 * 4. 分析 Bad Case：纯 KNN 答错但混合检索答对的 case
 * 5. 生成 Markdown 报告和对比图表
 */
@SpringBootTest
public class BenchmarkRunner {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkRunner.class);
    private static final int TOP_K = 10;

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private HybridSearchService hybridSearchService;

    @Test
    public void runBenchmark() throws Exception {
        log.info("============================================");
        log.info("  RAG 检索策略 Benchmark v2 开始");
        log.info("============================================");

        // ========== 1. 加载测试数据集 ==========
        List<QAPair> dataset = QADataset.getAll();
        log.info("加载测试数据集: {} 条 QA 对", dataset.size());

        // ========== 2. 初始化策略 ==========
        SearchStrategy pureStrategy = new SearchStrategy.PureSemantic(esClient, embeddingClient);
        SearchStrategy hybridStrategy = new SearchStrategy.Hybrid(hybridSearchService);
        log.info("策略1: {}", pureStrategy.name());
        log.info("策略2: {}", hybridStrategy.name());

        // ========== 3. 执行测试 ==========
        MetricsCollector pureMetrics = runStrategy(pureStrategy, dataset);
        MetricsCollector hybridMetrics = runStrategy(hybridStrategy, dataset);

        // ========== 4. 汇总统计 ==========
        MetricsCollector.Summary pureSummary = pureMetrics.summarize();
        MetricsCollector.Summary hybridSummary = hybridMetrics.summarize();

        printSummary(pureSummary, hybridSummary, pureStrategy.name(), hybridStrategy.name());

        // ========== 5. Bad Case 分析 ==========
        List<BadCase> badCases = analyzeBadCases(dataset, pureMetrics, hybridMetrics);
        printBadCases(badCases, pureStrategy.name(), hybridStrategy.name());

        // ========== 6. 生成报告 ==========
        Path reportPath = ReportGenerator.generate(pureSummary, hybridSummary,
                pureStrategy.name(), hybridStrategy.name());
        log.info("Markdown 报告已生成: {}", reportPath.toAbsolutePath());

        String chartMd = ChartGenerator.generateAll(pureSummary, hybridSummary,
                pureMetrics.getRecords(), hybridMetrics.getRecords(),
                pureStrategy.name(), hybridStrategy.name());
        log.info("图表已生成，嵌入 Markdown:\n{}", chartMd);

        log.info("============================================");
        log.info("  RAG 检索策略 Benchmark v2 完成！");
        log.info("============================================");
    }

    /**
     * 使用指定策略运行全部测试
     */
    private MetricsCollector runStrategy(SearchStrategy strategy, List<QAPair> dataset) {
        log.info("---- 执行策略: {} ----", strategy.name());
        MetricsCollector collector = new MetricsCollector();

        for (int i = 0; i < dataset.size(); i++) {
            QAPair pair = dataset.get(i);
            long start = System.currentTimeMillis();

            try {
                List<SearchResult> results = strategy.search(pair.question(), TOP_K);
                long elapsed = System.currentTimeMillis() - start;

                List<Integer> resultChunkIds = results.stream()
                        .map(SearchResult::getChunkId)
                        .toList();
                List<String> resultFileMd5s = results.stream()
                        .map(SearchResult::getFileMd5)
                        .toList();

                collector.record(pair.question(), elapsed,
                        resultChunkIds, pair.expectedChunkIds(),
                        resultFileMd5s, pair.expectedFileMd5s());
                collector.setCategory(i, pair.category());

                if ((i + 1) % 20 == 0) {
                    log.info("  [{}/{}] {} 完成", i + 1, dataset.size(), strategy.name());
                }
            } catch (Exception e) {
                log.error("  [{}/{}] 查询失败: question={}, error={}",
                        i + 1, dataset.size(), pair.question(), e.getMessage());
            }
        }

        log.info("---- 策略 {} 执行完成 ----", strategy.name());
        return collector;
    }

    /**
     * 分析 Bad Case：纯 KNN 在 top-5 内未命中，但混合检索命中的 case
     */
    private List<BadCase> analyzeBadCases(List<QAPair> dataset,
                                           MetricsCollector pureMetrics,
                                           MetricsCollector hybridMetrics) {
        List<BadCase> cases = new ArrayList<>();
        List<MetricsCollector.QueryMetrics> pureRecords = pureMetrics.getRecords();
        List<MetricsCollector.QueryMetrics> hybridRecords = hybridMetrics.getRecords();

        int limit = Math.min(pureRecords.size(), Math.min(hybridRecords.size(), dataset.size()));

        for (int i = 0; i < limit; i++) {
            QAPair pair = dataset.get(i);
            MetricsCollector.QueryMetrics pure = pureRecords.get(i);
            MetricsCollector.QueryMetrics hybrid = hybridRecords.get(i);

            // 条件：纯 KNN 在 top-5 未命中，但混合在 top-5 命中
            if (!pure.hitAt5 && hybrid.hitAt5) {
                cases.add(new BadCase(
                        pair.question(),
                        pair.category(),
                        pure.latencyMs,
                        hybrid.latencyMs,
                        "PureKNN top-5 miss, Hybrid top-5 hit"
                ));
            }
        }

        return cases;
    }

    /**
     * 打印 Bad Case 分析结果
     */
    private void printBadCases(List<BadCase> badCases, String pureName, String hybridName) {
        log.info("");
        log.info("========== Bad Case 分析 ==========");
        log.info("定义：纯 KNN 在 top-5 内未命中但混合检索命中的查询");
        log.info("共发现 {} 个 Bad Case", badCases.size());
        log.info("");

        if (badCases.isEmpty()) {
            log.info("未发现 Bad Case，两种策略在 top-5 命中率上表现一致。");
            log.info("");
            return;
        }

        // 最多展示 5 个
        int showCount = Math.min(5, badCases.size());
        log.info("--- Top {} Bad Case 详情 ---", showCount);
        log.info("");
        for (int i = 0; i < showCount; i++) {
            BadCase bc = badCases.get(i);
            log.info("Case #{} [{}]", i + 1, bc.category);
            log.info("  查询: {}", bc.query);
            log.info("  原因: {}", bc.reason);
            log.info("  {} 延迟: {}ms | {} 延迟: {}ms",
                    pureName, bc.pureLatencyMs, hybridName, bc.hybridLatencyMs);
            log.info("");
        }

        if (badCases.size() > showCount) {
            log.info("... 还有 {} 个 Bad Case 未展示", badCases.size() - showCount);
        }
    }

    /**
     * 控制台输出汇总对比
     */
    private void printSummary(MetricsCollector.Summary pure, MetricsCollector.Summary hybrid,
                              String pureName, String hybridName) {
        log.info("");
        log.info("========== Benchmark 结果汇总 ==========");
        log.info("测试集: {} 条 QA 对", pure.totalQueries);
        log.info("");
        log.info(String.format("%-20s | %-15s | %-15s | %s", "指标", pureName, hybridName, "提升"));
        log.info("----------------------------------------------------------");
        log.info(String.format("%-20s | %-15s | %-15s | %s",
                "Recall@5", fmt(pure.recallAt5), fmt(hybrid.recallAt5), fmt(hybrid.recallAt5 - pure.recallAt5)));
        log.info(String.format("%-20s | %-15s | %-15s | %s",
                "Recall@10", fmt(pure.recallAt10), fmt(hybrid.recallAt10), fmt(hybrid.recallAt10 - pure.recallAt10)));
        log.info(String.format("%-20s | %-15s | %-15s | %s",
                "Precision@5", fmt(pure.precisionAt5), fmt(hybrid.precisionAt5), fmt(hybrid.precisionAt5 - pure.precisionAt5)));
        log.info(String.format("%-20s | %-15s | %-15s | %s",
                "Precision@10", fmt(pure.precisionAt10), fmt(hybrid.precisionAt10), fmt(hybrid.precisionAt10 - pure.precisionAt10)));
        log.info(String.format("%-20s | %-15s | %-15s | %s",
                "F1-Score@5", fmt(pure.f1At5), fmt(hybrid.f1At5), fmt(hybrid.f1At5 - pure.f1At5)));
        log.info(String.format("%-20s | %-15s | %-15s | %s",
                "F1-Score@10", fmt(pure.f1At10), fmt(hybrid.f1At10), fmt(hybrid.f1At10 - pure.f1At10)));
        log.info("");
        log.info(String.format("%-20s | %-15s | %-15s", "平均延迟(ms)",
                String.format("%.1f", pure.avgLatencyMs), String.format("%.1f", hybrid.avgLatencyMs)));
        log.info(String.format("%-20s | %-15s | %-15s", "P95延迟(ms)",
                String.format("%.1f", pure.p95LatencyMs), String.format("%.1f", hybrid.p95LatencyMs)));
        log.info(String.format("%-20s | %-15s | %-15s", "P99延迟(ms)",
                String.format("%.1f", pure.p99LatencyMs), String.format("%.1f", hybrid.p99LatencyMs)));
        log.info("");
    }

    // =================================================================
    // Bad Case 数据结构
    // =================================================================

    record BadCase(String query, String category, long pureLatencyMs, long hybridLatencyMs, String reason) {}

    private static String fmt(double value) {
        return String.format("%.2f%%", value * 100);
    }
}
