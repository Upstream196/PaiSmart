import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import com.yizhaoqi.smartpai.service.ElasticsearchService;
import com.yizhaoqi.smartpai.service.VectorizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class VectorizationServiceUnitTest {

    @Mock
    private EmbeddingClient embeddingClient;

    @Mock
    private ElasticsearchService elasticsearchService;

    @Mock
    private DocumentVectorRepository documentVectorRepository;

    @InjectMocks
    private VectorizationService vectorizationService;

    @Test
    void testVectorize_Success() {
        // 1. 准备测试数据
        String fileMd5 = "test-md5-123";
        String userId = "testUser";
        String orgTag = "testOrg";
        boolean isPublic = false;

        // 模拟数据库返回的分块数据
        List<DocumentVector> mockDocumentVectors = List.of(
                createDocumentVector(1, "这是第一个文本块"),
                createDocumentVector(2, "这是第二个文本块"),
                createDocumentVector(3, "这是第三个文本块")
        );

        // 模拟 Embedding 返回的向量（每个文本块对应一个2048维向量）
        List<float[]> mockVectors = List.of(
                new float[2048],  // 实际测试中不需要填真实值
                new float[2048],
                new float[2048]
        );

        // 2. 设置Mock行为
        when(documentVectorRepository.findByFileMd5(fileMd5))
                .thenReturn(mockDocumentVectors);
        when(embeddingClient.embed(anyList()))
                .thenReturn(mockVectors);

        // 3. 执行被测方法
        vectorizationService.vectorize(fileMd5, userId, orgTag, isPublic);

        // 4. 验证依赖被正确调用
        // 4.1 验证从数据库查询了分块
        verify(documentVectorRepository).findByFileMd5(fileMd5);

        // 4.2 验证调用了 Embedding API，且传入的文本数量=3
        ArgumentCaptor<List<String>> textsCaptor = ArgumentCaptor.forClass(List.class);
        verify(embeddingClient).embed(textsCaptor.capture());
        List<String> capturedTexts = textsCaptor.getValue();
        assertEquals(3, capturedTexts.size());
        assertEquals("这是第一个文本块", capturedTexts.get(0));
        assertEquals("这是第二个文本块", capturedTexts.get(1));

        // 4.3 验证调用了 ES 批量存储
        verify(elasticsearchService).bulkIndex(anyList());

        // 4.4 验证没有多余调用
        verifyNoMoreInteractions(documentVectorRepository, embeddingClient, elasticsearchService);
    }


    @Test
    void testVectorize_WhenNoChunks_ShouldNotCallEmbedding() {
        // 模拟数据库返回空列表
        when(documentVectorRepository.findByFileMd5(anyString()))
                .thenReturn(List.of());

        // 执行
        vectorizationService.vectorize("empty-md5", "user", "org", false);

        // 验证：没有调用 Embedding API
        verify(embeddingClient, never()).embed(anyList());
        // 验证：没有调用 ES 存储
        verify(elasticsearchService, never()).bulkIndex(anyList());
    }

    @Test
    void testVectorize_WhenEmbeddingFails_ShouldThrowException() {
        // 准备数据
        List<DocumentVector> mockDocumentVectors = List.of(
                createDocumentVector(1, "内容1")
        );
        when(documentVectorRepository.findByFileMd5(anyString()))
                .thenReturn(mockDocumentVectors);

        // 模拟 Embedding API 失败
        when(embeddingClient.embed(anyList()))
                .thenThrow(new RuntimeException("Embedding API 调用失败"));

        // 执行并验证异常
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            vectorizationService.vectorize("md5", "user", "org", false);
        });

        assertTrue(exception.getMessage().contains("向量化失败"));

        // 验证 ES 没有被调用
        verify(elasticsearchService, never()).bulkIndex(anyList());
    }

    @Test
    void testVectorize_WhenRepositoryThrowsException_ShouldPropagate() {
        // 模拟数据库查询失败
        when(documentVectorRepository.findByFileMd5(anyString()))
                .thenThrow(new RuntimeException("数据库连接失败"));

        // 执行并验证异常
        assertThrows(RuntimeException.class, () -> {
            vectorizationService.vectorize("md5", "user", "org", false);
        });

        // 验证 Embedding 和 ES 都没有被调用
        verify(embeddingClient, never()).embed(anyList());
        verify(elasticsearchService, never()).bulkIndex(anyList());
    }

    // 辅助方法：创建测试用的 DocumentVector 对象
    private DocumentVector createDocumentVector(int chunkId, String content) {
        DocumentVector dv = new DocumentVector();
        dv.setChunkId(chunkId);
        dv.setTextContent(content);
        // 其他字段可以不用设置，因为 VectorizationService 只用到这两个
        return dv;
    }
}
