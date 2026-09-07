package com.yizhaoqi.smartpai.benchmark;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.service.HybridSearchService;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索策略封装 —— 提供「纯语义检索」和「混合检索」两种实现。
 *
 * 纯语义检索：仅使用 KNN 向量相似度匹配，不加 BM25 rescore。
 * 混合检索：KNN + BM25 rescore（复用项目中 HybridSearchService 的逻辑）。
 */
public interface SearchStrategy {

    /** 策略名称，用于报告和日志 */
    String name();

    /** 执行搜索，返回 topK 个结果 */
    List<SearchResult> search(String query, int topK);

    // =================================================================
    // 策略实现
    // =================================================================

    /** 纯语义检索：仅 KNN 向量相似度 */
    class PureSemantic implements SearchStrategy {
        private final ElasticsearchClient esClient;
        private final EmbeddingClient embeddingClient;

        public PureSemantic(ElasticsearchClient esClient, EmbeddingClient embeddingClient) {
            this.esClient = esClient;
            this.embeddingClient = embeddingClient;
        }

        @Override
        public String name() {
            return "纯语义检索 (KNN)";
        }

        @Override
        public List<SearchResult> search(String query, int topK) {
            try {
                // 生成查询向量
                List<float[]> vecs = embeddingClient.embed(List.of(query));
                if (vecs == null || vecs.isEmpty()) {
                    return List.of();
                }
                float[] raw = vecs.get(0);
                List<Float> queryVector = new ArrayList<>(raw.length);
                for (float v : raw) {
                    queryVector.add(v);
                }

                // 纯 KNN 检索：只做向量相似度，不加 match 过滤，不加 rescore
                int recallK = topK * 30;
                SearchResponse<EsDocument> response = esClient.search(s -> s
                        .index("knowledge_base")
                        .knn(kn -> kn
                                .field("vector")
                                .queryVector(queryVector)
                                .k(recallK)
                                .numCandidates(recallK)
                        )
                        .size(topK),
                        EsDocument.class
                );

                return response.hits().hits().stream()
                        .map(hit -> {
                            assert hit.source() != null;
                            return new SearchResult(
                                    hit.source().getFileMd5(),
                                    hit.source().getChunkId(),
                                    hit.source().getTextContent(),
                                    hit.score()
                            );
                        })
                        .toList();
            } catch (Exception e) {
                throw new RuntimeException("纯语义检索失败: " + query, e);
            }
        }
    }

    /** 混合检索：KNN + BM25 rescore */
    class Hybrid implements SearchStrategy {
        private final HybridSearchService hybridSearchService;

        public Hybrid(HybridSearchService hybridSearchService) {
            this.hybridSearchService = hybridSearchService;
        }

        @Override
        public String name() {
            return "混合检索 (KNN + BM25)";
        }

        @Override
        public List<SearchResult> search(String query, int topK) {
            return hybridSearchService.search(query, topK);
        }
    }
}
