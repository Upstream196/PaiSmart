package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import com.hankcs.hanlp.seg.common.Term;
import com.hankcs.hanlp.tokenizer.StandardTokenizer;

@Service
public class ParseService {

    private static final Logger logger = LoggerFactory.getLogger(ParseService.class);

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Value("${file.parsing.chunk-size}")
    private int chunkSize;

    @Value("${file.parsing.parent-chunk-size:1048576}")
    private int parentChunkSize;
    
    @Value("${file.parsing.buffer-size:8192}")
    private int bufferSize;
    
    @Value("${file.parsing.max-memory-threshold:0.8}")
    private double maxMemoryThreshold;
    
    public ParseService() {
        // 无需初始化，StandardTokenizer是静态方法
    }

    /**
     * 以流式方式解析文件，将内容分块并保存到数据库，以避免OOM。
     * 采用"父文档-子切片"策略。
     *
     * @param fileMd5    文件的MD5哈希值，用于唯一标识文件
     * @param fileStream 文件输入流，用于读取文件内容
     * @param userId     上传用户ID
     * @param orgTag     组织标签
     * @param isPublic   是否公开
     * @throws IOException   如果文件读取过程中发生错误
     * @throws TikaException 如果文件解析过程中发生错误
     */
    public void parseAndSave(String fileMd5, InputStream fileStream,
            String userId, String orgTag, boolean isPublic) throws IOException, TikaException {
        logger.info("开始流式解析文件，fileMd5: {}, userId: {}, orgTag: {}, isPublic: {}",
                fileMd5, userId, orgTag, isPublic);
        //检查内存是否达到阈值
        checkMemoryThreshold();

        // 使用try-with-resources语句创建一个BufferedInputStream对象，它会自动在try块结束后关闭
        try (BufferedInputStream bufferedStream = new BufferedInputStream(fileStream, bufferSize)) { // BufferedInputStream是一个输入流缓冲类，可以提高读取效率
            // 创建一个流式处理器，它会在内部处理父块的切分和子块的保存
            StreamingContentHandler handler = new StreamingContentHandler(fileMd5, userId, orgTag, isPublic);
            // 创建Metadata对象，用于存储文档的元数据信息
            Metadata metadata = new Metadata();
            // 创建ParseContext对象，用于解析过程中的上下文信息管理
            ParseContext context = new ParseContext();
            // 创建AutoDetectParser对象，用于自动检测文档类型并解析文档内容
            AutoDetectParser parser = new AutoDetectParser();

            // Tika的parse方法会驱动整个流式处理过程
            // 当handler的characters方法接收到足够数据时，会触发分块、切片和保存
            parser.parse(bufferedStream, handler, metadata, context);

            logger.info("文件流式解析和入库完成，fileMd5: {}", fileMd5);

        } catch (SAXException e) {
            logger.error("文档解析失败，fileMd5: {}", fileMd5, e);
            throw new RuntimeException("文档解析失败", e);
        }
    }

    /**
     * 兼容旧版本的解析方法
     */
    public void parseAndSave(String fileMd5, InputStream fileStream) throws IOException, TikaException {
        // 使用默认值调用新方法
        parseAndSave(fileMd5, fileStream, "unknown", "DEFAULT", false);
    }

    /**
     * 检查内存是否达到阈值
     * 该方法用于监控系统内存使用情况，当内存使用达到预设阈值时触发相应的处理逻辑
     */
    private void checkMemoryThreshold() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        
        double memoryUsage = (double) usedMemory / maxMemory;
        
        if (memoryUsage > maxMemoryThreshold) {
            logger.warn("内存使用率过高: {:.2f}%, 触发垃圾回收", memoryUsage * 100);
            System.gc();
            
            // 重新检查
            usedMemory = runtime.totalMemory() - runtime.freeMemory();
            memoryUsage = (double) usedMemory / maxMemory;
            
            if (memoryUsage > maxMemoryThreshold) {
                throw new RuntimeException("内存不足，无法处理大文件。当前使用率: " + 
                    String.format("%.2f%%", memoryUsage * 100));
            }
        }
    }
    
    /**
     * 内部流式内容处理器，实现了父子文档切分策略的核心逻辑。
     * Tika解析器会调用characters方法，当累积的文本达到"父块"大小时，
     * 就触发processParentChunk方法，进行"子切片"的生成和入库。
     */
    private class StreamingContentHandler extends BodyContentHandler {
        private final StringBuilder buffer = new StringBuilder();
        private final String fileMd5;
        private final String userId;
        private final String orgTag;
        private final boolean isPublic;
        private int savedChunkCount = 0;

        public StreamingContentHandler(String fileMd5, String userId, String orgTag, boolean isPublic) {
            super(-1); // 禁用Tika的内部写入限制，我们自己管理缓冲区
            this.fileMd5 = fileMd5;
            this.userId = userId;
            this.orgTag = orgTag;
            this.isPublic = isPublic;
        }

        @Override
        /**
         * 处理XML解析过程中遇到的字符数据的方法
         * @param ch 包含XML文档中字符数据的字符数组
         * @param start 字符数组中字符数据的起始位置
         * @param length 字符数据的长度
         */
        public void characters(char[] ch, int start, int length) {
            buffer.append(ch, start, length);
            if (buffer.length() >= parentChunkSize) {
                processParentChunk();
            }
        }

        @Override
        public void endDocument() {
            // 处理文档末尾剩余的最后一部分内容
            if (buffer.length() > 0) {
                processParentChunk();
            }
        }

        /**
         * 处理父块的方法
         * 这个方法可能用于处理或分析父级数据块，具体功能需要根据上下文确定
         */
        private void processParentChunk() {
            // 获取缓冲区中的内容并转换为字符串
            String parentChunkText = buffer.toString();
            logger.debug("处理父文本块，大小: {} bytes", parentChunkText.length());

            // 1. 将父块分割成更小的、有语义的子切片
            List<String> childChunks = ParseService.this.splitTextIntoChunksWithSemantics(parentChunkText, chunkSize);

            // 2. 将子切片批量保存到数据库
            this.savedChunkCount = ParseService.this.saveChildChunks(fileMd5, childChunks, userId, orgTag, isPublic, this.savedChunkCount);

            // 3. 清空缓冲区，为下一个父块做准备
            buffer.setLength(0);
        }
    }

    /**
     * 将子切片列表保存到数据库。
     *
     * @param fileMd5         文件的 MD5 哈希值
     * @param chunks          子切片文本列表
     * @param userId          上传用户ID
     * @param orgTag          组织标签
     * @param isPublic        是否公开
     * @param startingChunkId 当前批次的起始分片ID
     * @return 保存后总的分片数量
     */
    private int saveChildChunks(String fileMd5, List<String> chunks,
            String userId, String orgTag, boolean isPublic, int startingChunkId) {
        int currentChunkId = startingChunkId;
        for (String chunk : chunks) {
            currentChunkId++;
            var vector = new DocumentVector();
            vector.setFileMd5(fileMd5);
            vector.setChunkId(currentChunkId);
            vector.setTextContent(chunk);
            vector.setUserId(userId);
            vector.setOrgTag(orgTag);
            vector.setPublic(isPublic);
            documentVectorRepository.save(vector);
        }
        logger.info("成功保存 {} 个子切片到数据库", chunks.size());
        return currentChunkId;
    }

    /**
     * 智能文本分割，保持语义完整性
     */
    private List<String> splitTextIntoChunksWithSemantics(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();

        // 按段落分割
//        String[] paragraphs = text.split("\n\n+");
        //测试改进
        String[] rawParts = text.split("(?=\n\n)"); //将字符串按照双换行符进行切割，并保留双换行符
        //新添过滤方法，保留内容段落过滤空字符串
        ArrayList<String> paragraphList = new ArrayList<>();
        for(String part : rawParts) {
            if(!part.isEmpty()){
                paragraphList.add(part);
            }
        }

        StringBuilder currentChunk = new StringBuilder();

        for (String paragraph : paragraphList) {
            // 如果单个段落超过chunk大小，需要进一步分割
            if (paragraph.length() > chunkSize) {
                // 先保存当前chunk
                if (currentChunk.length() > 0) {
//                    chunks.add(currentChunk.toString().trim());
                    //测试改进
                    chunks.add(currentChunk.toString());
                    currentChunk = new StringBuilder();
                }

                // 按句子分割长段落
                List<String> sentenceChunks = splitLongParagraph(paragraph, chunkSize);
                // 将句子块集合添加到现有的块集合中
                // sentenceChunks 是一个包含句子块的集合，通过 addAll 方法将其中的所有元素添加到 chunks 中
                chunks.addAll(sentenceChunks);
            }
            // 如果添加这个段落会超过chunk大小
            else if (currentChunk.length() + paragraph.length() > chunkSize) {
                // 保存当前chunk
                if (currentChunk.length() > 0) {
//                    chunks.add(currentChunk.toString().trim());
                    //测试改进
                    chunks.add(currentChunk.toString());
                }
                // 开始新chunk
                currentChunk = new StringBuilder(paragraph);
            }
            // 可以添加到当前chunk
            else {
                if (currentChunk.length() > 0) {
                    currentChunk.append("\n\n");
                }
                currentChunk.append(paragraph);
            }
        }

        // 添加最后一个chunk
        if (currentChunk.length() > 0) {
//            chunks.add(currentChunk.toString().trim());
            //测试改进
            chunks.add(currentChunk.toString());
        }

        return chunks;
    }

    /**
     * 分割长段落，按句子边界
     */
    private List<String> splitLongParagraph(String paragraph, int chunkSize) {
        List<String> chunks = new ArrayList<>();

        // 按句子分割
        String[] sentences = paragraph.split("(?<=[。！？；])|(?<=[.!?;])\\s+");

        StringBuilder currentChunk = new StringBuilder();

        for (String sentence : sentences) {
            //为什么不能向文本切割一样作为一个独立出来的情况进行考虑？->因为在通常情况下句子不会太长，因此无需单独单独设置，这会增加代码冗余
            //A：当前分块存储内容+新增句子长度>分块大小限制
            if (currentChunk.length() + sentence.length() > chunkSize) {
                //情况1：当前分块不为空+新增句子长度>分块大小限制
                if (currentChunk.length() > 0) {
//                    chunks.add(currentChunk.toString().trim());
                    //测试改进
                    chunks.add(currentChunk.toString());  //保存当前分块的内容
                    currentChunk = new StringBuilder();  //新建一个容器，存储新增句子
                }


                //情况2：当前分块为空+新增句子长度>分块大小限制
                if (sentence.length() > chunkSize) {    //新增句子的长度大于分块大小的临界值，将句子按词切分
                    chunks.addAll(splitLongSentence(sentence, chunkSize));
                } else {
                    currentChunk.append(sentence);  //新增句子的长度小于分块大小的临界值，直接添加到分块中
                }
            } else {    //B：当前分块的长度+新增句子的长度<分块大小的临界值时
                currentChunk.append(sentence);    //将句子直接添加到当前分块中
            }
        }

        if (currentChunk.length() > 0) {
//            chunks.add(currentChunk.toString().trim());
            //测试改进
            chunks.add(currentChunk.toString());
        }

        return chunks;  //返回分块
    }

    /**
     * 使用HanLP智能分割超长句子，中文按语义切割
     */
    private List<String> splitLongSentence(String sentence, int chunkSize) {   //输入字符串类型的句子与整型分块大小的临界值，输出字符串集合
        List<String> chunks = new ArrayList<>();
        
        try {
            // 使用HanLP StandardTokenizer进行分词
            List<Term> termList = StandardTokenizer.segment(sentence);  //将句子划分成一个个单词存储到集合中
            
            StringBuilder currentChunk = new StringBuilder();  //StringBuilder是一个字符串类型的接收容器吗？它的主要作用是什么？
            for (Term term : termList) {
                String word = term.word;   //遍历集合中每一个单词，将每个单词的词语赋值给word变量
                
                // 如果添加这个词会超过chunk大小限制，且当前chunk不为空
                if (currentChunk.length() + word.length() > chunkSize && !currentChunk.isEmpty()) {  //为什么添加不为空的判断？
                    chunks.add(currentChunk.toString());
                    currentChunk = new StringBuilder();
                }
                
                currentChunk.append(word);
            }
            //添加最后一个chunk
            if (!currentChunk.isEmpty()) {   //文本与段落进行最后分块添加时只需判断分块的长度大于0即可，为什么句子的划分需要进行非空判断
                chunks.add(currentChunk.toString());
            }
            
            logger.debug("HanLP智能分词成功，原文长度: {}, 分词数: {}, 分块数: {}", 
                    sentence.length(), termList.size(), chunks.size());
                    
        } catch (Exception e) {
            logger.warn("HanLP分词异常: {}, 使用字符分割作为备用方案", e.getMessage());
            chunks = splitByCharacters(sentence, chunkSize);
         }
        
        return chunks;
    }
    
    /**
     * 备用方案：按字符分割
     */
    private List<String> splitByCharacters(String sentence, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        for (int i = 0; i < sentence.length(); i++) {
            // 使用charAt方法获取字符串sentence中索引为i的字符
            char c = sentence.charAt(i);  //charAt()方法的作用是什么？为什么需要使用这个方法？

            if (currentChunk.length() + 1 > chunkSize && !currentChunk.isEmpty()) {  //+ 1 是因为每次循环只处理一个长度为 1 的 char
                chunks.add(currentChunk.toString());
                currentChunk = new StringBuilder();
            }

            currentChunk.append(c);
        }

        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk.toString());
        }

        return chunks;
    }
}
