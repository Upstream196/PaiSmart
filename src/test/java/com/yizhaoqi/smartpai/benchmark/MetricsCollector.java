package com.yizhaoqi.smartpai.benchmark;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 指标收集器 —— 负责记录单次查询的结果并汇总统计。
 *
 * 支持的指标：
 * - Recall@K：前 K 个结果中命中 Ground Truth 的比例
 * - Precision@K：前 K 个结果中相关结果的比例
 * - F1-Score@K：Recall 和 Precision 的调和平均
 * - 延迟统计（平均 / P95 / P99）
 */
public class MetricsCollector {

    private final List<QueryMetrics> records = new ArrayList<>();

    /** 记录一次查询的结果 */
    public void record(String query, long latencyMs, List<Integer> topKChunkIds,
                       List<Integer> expectedChunkIds, List<String> topKFileMd5s,
                       List<String> expectedFileMd5s) {
        QueryMetrics m = new QueryMetrics();
        m.query = query;
        m.latencyMs = latencyMs;
        m.expectedChunkIds = expectedChunkIds;
        m.expectedFileMd5s = expectedFileMd5s;

        // 判断 top-5 和 top-10 的命中情况
        Set<Integer> top5Set = new HashSet<>(topKChunkIds.subList(0, Math.min(5, topKChunkIds.size())));
        Set<Integer> top10Set = new HashSet<>(topKChunkIds.subList(0, Math.min(10, topKChunkIds.size())));
        Set<Integer> expectedSet = new HashSet<>(expectedChunkIds);

        // Recall: 期望结果是否被检索到
        m.hitAt5 = top5Set.stream().anyMatch(expectedSet::contains);
        m.hitAt10 = top10Set.stream().anyMatch(expectedSet::contains);

        // 额外校验 fileMd5（可选辅助）
        if (!expectedFileMd5s.isEmpty()) {
            Set<String> top5Md5Set = new HashSet<>(topKFileMd5s.subList(0, Math.min(5, topKFileMd5s.size())));
            Set<String> top10Md5Set = new HashSet<>(topKFileMd5s.subList(0, Math.min(10, topKFileMd5s.size())));
            Set<String> expectedMd5Set = new HashSet<>(expectedFileMd5s);
            // 如果 chunkId 未命中但 fileMd5 命中，也算辅助命中
            if (!m.hitAt5 && top5Md5Set.stream().anyMatch(expectedMd5Set::contains)) {
                m.hitAt5 = true;
            }
            if (!m.hitAt10 && top10Md5Set.stream().anyMatch(expectedMd5Set::contains)) {
                m.hitAt10 = true;
            }
        }

        // Precision: 前 K 个结果中有多少是相关的
        if (!topKChunkIds.isEmpty()) {
            int relevantInTop5 = (int) topKChunkIds.subList(0, Math.min(5, topKChunkIds.size())).stream()
                    .filter(expectedSet::contains).count();
            int relevantInTop10 = (int) topKChunkIds.subList(0, Math.min(10, topKChunkIds.size())).stream()
                    .filter(expectedSet::contains).count();
            m.precisionAt5 = relevantInTop5 / (double) Math.min(5, topKChunkIds.size());
            m.precisionAt10 = relevantInTop10 / (double) Math.min(10, topKChunkIds.size());
        }

        records.add(m);
    }

    /** 计算汇总统计 */
    public Summary summarize() {
        Summary s = new Summary();
        s.totalQueries = records.size();

        // Recall
        s.recallAt5 = records.stream().filter(r -> r.hitAt5).count() / (double) s.totalQueries;
        s.recallAt10 = records.stream().filter(r -> r.hitAt10).count() / (double) s.totalQueries;

        // Precision（取均值）
        s.precisionAt5 = records.stream().mapToDouble(r -> r.precisionAt5).average().orElse(0);
        s.precisionAt10 = records.stream().mapToDouble(r -> r.precisionAt10).average().orElse(0);

        // F1-Score
        s.f1At5 = f1(s.recallAt5, s.precisionAt5);
        s.f1At10 = f1(s.recallAt10, s.precisionAt10);

        // 延迟统计
        List<Long> latencies = records.stream().mapToLong(r -> r.latencyMs).sorted().boxed().toList();
        s.avgLatencyMs = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
        s.minLatencyMs = latencies.isEmpty() ? 0 : latencies.get(0);
        s.maxLatencyMs = latencies.isEmpty() ? 0 : latencies.get(latencies.size() - 1);
        s.p95LatencyMs = percentile(latencies, 95);
        s.p99LatencyMs = percentile(latencies, 99);

        // 按分类统计 Recall
        s.recallByCategory = new LinkedHashMap<>();
        records.stream()
                .filter(r -> r.category != null)
                .collect(Collectors.groupingBy(r -> r.category))
                .forEach((cat, list) -> {
                    long hits = list.stream().filter(r -> r.hitAt10).count();
                    s.recallByCategory.put(cat, hits / (double) list.size());
                });

        return s;
    }

    /** 为每条记录补充分类信息（从 QAPair 中获取） */
    public void setCategory(int index, String category) {
        if (index >= 0 && index < records.size()) {
            records.get(index).category = category;
        }
    }

    public List<QueryMetrics> getRecords() {
        return records;
    }

    // =================================================================
    // 内部数据结构
    // =================================================================

    /** 单次查询的原始记录 */
    public static class QueryMetrics {
        String query;
        long latencyMs;
        List<Integer> expectedChunkIds;
        List<String> expectedFileMd5s;
        boolean hitAt5;
        boolean hitAt10;
        double precisionAt5;
        double precisionAt10;
        String category;
    }

    /** 汇总统计结果 */
    public static class Summary {
        public int totalQueries;
        public double recallAt5;
        public double recallAt10;
        public double precisionAt5;
        public double precisionAt10;
        public double f1At5;
        public double f1At10;
        public double avgLatencyMs;
        public double minLatencyMs;
        public double maxLatencyMs;
        public double p95LatencyMs;
        public double p99LatencyMs;
        public Map<String, Double> recallByCategory;
    }

    // =================================================================
    // 工具方法
    // =================================================================

    private static double f1(double recall, double precision) {
        if (recall + precision == 0) return 0;
        return 2 * recall * precision / (recall + precision);
    }

    private static long percentile(List<Long> sorted, int pct) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }
}
