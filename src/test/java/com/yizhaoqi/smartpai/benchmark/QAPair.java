package com.yizhaoqi.smartpai.benchmark;

import java.util.List;

/**
 * QA 测试对 —— 描述一个搜索问题和它期望的正确答案位置。
 *
 * 在 benchmark 运行时，我们会用 question 分别对两种检索策略发起搜索，
 * 然后将返回结果与 expectedChunkIds 对比，计算 Recall / Precision 等指标。
 *
 * @param question           搜索查询语句（如 "如何上传文件？"）
 * @param expectedChunkIds   期望被检索到的 chunkId 列表（Ground Truth）
 * @param expectedFileMd5s   期望命中的文件 MD5（可选辅助字段）
 * @param category           问题分类（用于后续分析哪些类型的问题检索效果更好）
 */
public record QAPair(
        String question,
        List<Integer> expectedChunkIds,
        List<String> expectedFileMd5s,
        String category
) {

    /**
     * 快捷构造方法 —— 只传必要参数时使用
     */
    public static QAPair of(String question, List<Integer> expectedChunkIds) {
        return new QAPair(question, expectedChunkIds, List.of(), "未分类");
    }

    public static QAPair of(String question, List<Integer> expectedChunkIds, String category) {
        return new QAPair(question, expectedChunkIds, List.of(), category);
    }

    public static QAPair of(String question, List<Integer> expectedChunkIds, List<String> expectedFileMd5s, String category) {
        return new QAPair(question, expectedChunkIds, expectedFileMd5s, category);
    }
}
