package com.yizhaoqi.smartpai.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class VectorizationManualTest {

    @Autowired
    private VectorizationService vectorizationService;

    @Test
    public void testVectorizeWithRealData(){
        //替换成一个真实存在于数据库中的fileMd5
        String fileMd5= "b7219e5b1f9c34348a1293238c30ce53";

        vectorizationService.vectorize(fileMd5,"testUser", "testOrg",false);

        //运行后观察控制台日志
        System.out.println("向量化执行完成，请检查日志");
    }
}
