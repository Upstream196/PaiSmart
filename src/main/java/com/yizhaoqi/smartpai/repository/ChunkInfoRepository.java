package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.ChunkInfo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChunkInfoRepository extends JpaRepository<ChunkInfo, Long> {
    /**
     * 根据文件MD5值查询分块信息，并按照分块索引升序排列
     *
     * @param fileMd5 文件的MD5值，用于唯一标识一个文件
     * @return 返回按照分块索引升序排列的分块信息列表，每个分块信息包含分块的各种属性
     */
    List<ChunkInfo> findByFileMd5OrderByChunkIndexAsc(String fileMd5);
}
