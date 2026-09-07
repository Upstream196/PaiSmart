package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.entity.TextChunk;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

// 向量化服务类
@Service
public class VectorizationService {

    private static final Logger logger = LoggerFactory.getLogger(VectorizationService.class);

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private ElasticsearchService elasticsearchService;

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    /**
     * 执行向量化操作
     * @param fileMd5 文件指纹
     * @param userId 上传用户ID
     * @param orgTag 组织标签
     * @param isPublic 是否公开
     */
    public void vectorize(String fileMd5, String userId, String orgTag, boolean isPublic) {
        try {
            logger.info("开始向量化文件，fileMd5: {}, userId: {}, orgTag: {}, isPublic: {}", 
                       fileMd5, userId, orgTag, isPublic);

            //*添加计时器
            long startTime = System.currentTimeMillis();
            logger.info("【步骤1】开始向量化 fileMd5={}", fileMd5);

            //*获取分块
            long step1 = System.currentTimeMillis();

            // 获取文件分块内容
            List<TextChunk> chunks = fetchTextChunks(fileMd5); //获取文件分开内容->调用fetchTextChunks方法->在数据库中查询某个文件的所有分块->转换为TextChunk列表
            if (chunks == null || chunks.isEmpty()) {
                logger.warn("未找到分块内容，fileMd5: {}", fileMd5);
                return;
            }

            //*日志2
            logger.info("【步骤2】获取分块完成，数量={},耗时={}ms",chunks==null ? 0 : chunks.size(), System.currentTimeMillis() -step1);
            //*提取文本
            long step2 = System.currentTimeMillis();

            // 提取文本内容
            List<String> texts = chunks.stream()
                    .map(TextChunk::getContent)
                    .toList();

            //*日志3
            logger.info("【步骤3】提取文本完成，文本总长度={},耗时={}ms",texts.stream().mapToInt(String::length).sum(), System.currentTimeMillis() -step2);

            //*生成向量
            long step3 = System.currentTimeMillis();

            // 调用外部模型生成向量
            List<float[]> vectors = embeddingClient.embed(texts);

            //*日志4
            logger.info("【步骤4】生成向量完成，向量数量={},耗时={}ms",vectors.size(), System.currentTimeMillis() -step3);

            //*构建ES文档
            long step4 = System.currentTimeMillis();

            // 构建 Elasticsearch 文档并存储
            List<EsDocument> esDocuments = IntStream.range(0, chunks.size())
                    .mapToObj(i -> new EsDocument(
                            UUID.randomUUID().toString(),
                            fileMd5,
                            chunks.get(i).getChunkId(),
                            chunks.get(i).getContent(),
                            vectors.get(i),
                            "deepseek-embed", // 更新为 DeepSeek 的模型版本
                            userId,
                            orgTag,
                            isPublic
                    ))
                    .toList();

            //*日志5
            logger.info("【步骤5】构建ES文档完成，数量={},耗时={}ms",esDocuments.size(), System.currentTimeMillis() -step4);

            //*批量存储
            long step5 = System.currentTimeMillis();

            elasticsearchService.bulkIndex(esDocuments); // 批量存储到 Elasticsearch

            //*日志6
            logger.info("【步骤6】批量存储完成，耗时={}ms", System.currentTimeMillis() -step5);
            logger.info("【完成】 总耗时={}ms", System.currentTimeMillis() - startTime);

            logger.info("向量化完成，fileMd5: {}", fileMd5);
        } catch (Exception e) {
            logger.error("向量化失败，fileMd5: {}", fileMd5, e);
            throw new RuntimeException("向量化失败", e);
        }
    }
    

    /**
     * 获取文件分块内容
     * @param fileMd5 文件指纹
     * @return 分块内容列表
     */
    // 从数据库获取分块内容
    private List<TextChunk> fetchTextChunks(String fileMd5) {
        // 调用 Repository 查询数据
        List<DocumentVector> vectors = documentVectorRepository.findByFileMd5(fileMd5); //在数据库中查询某个文件的所有分块

        // 转换为 TextChunk 列表
        return vectors.stream()
                .map(vector -> new TextChunk(
                        vector.getChunkId(),
                        vector.getTextContent()
                ))
                .toList();
    }
}