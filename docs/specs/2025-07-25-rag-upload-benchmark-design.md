# RAG 检索评估 & 文件上传对比 Benchmark 设计

## 概述

为 PaiSmart 项目设计数据测试方案，量化验证核心功能的性能与效果，用于简历项目描述的数据支撑。

## 测试方向

### 方向一：RAG 检索评估（核心）

对比纯语义检索（KNN Vector Search）与混合检索（KNN + BM25）在 Recall@K、Precision@K、检索延迟等指标上的表现。

### 方向二：文件上传对比（辅助）

对比分片上传 + 断点续传与直接上传在上传耗时、服务端内存占用、成功率等维度的表现。

## 测试数据集

- 50 组 QA 对，从项目文档中人工标注
- 每个 QA 对包含：问题、期望答案所在的 chunk_id 列表、所属文档 doc_id
- 覆盖多种文档类型（PDF、DOCX、TXT）

## 评估指标

| 指标 | 说明 |
|---|---|
| Recall@K | 前 K 个结果中命中 Ground Truth 的比例 |
| Precision@K | 前 K 个结果中真正相关的结果比例 |
| F1-Score@K | Recall 和 Precision 的调和平均 |
| 平均检索延迟 | 50 次查询的平均耗时（ms）|
| P95/P99 延迟 | 百分位延迟统计 |

## 输出产物

1. Markdown 报告（含表格、结论）
2. 图表（Recall 对比柱状图、Precision 对比柱状图、延迟分布图）

## 文件结构

```
src/test/java/com/yizhaoqi/smartpai/benchmark/
├── BenchmarkRunner.java           # 主入口
├── QADataset.java                 # 50 个 QA 数据集
├── QAPair.java                    # QA 数据结构
├── SearchStrategy.java            # 检索策略封装
├── MetricsCollector.java          # 指标收集
├── ReportGenerator.java           # Markdown 报告
└── ChartGenerator.java            # 图表生成
```

## 实施步骤

1. 构建测试数据集
2. 实现 Benchmark 框架
3. 运行测试收集数据
4. 生成报告和图表
5. 分析结果，撰写简历话术
