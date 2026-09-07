package com.yizhaoqi.smartpai.benchmark;

import java.util.List;

/**
 * 120 组 QA 测试数据集（v2 - 扩展版）。
 *
 * 每一条记录包含：
 * - question: 搜索查询语句
 * - expectedChunkIds: 期望命中的 chunkId 列表（Ground Truth）
 * - expectedFileMd5s: 期望命中的文件 MD5（辅助验证）
 * - category: 问题分类
 *
 * ⚠️ 基于已上传到系统的 5 份 PDF 文档标注。
 */
public class QADataset {

    private static final String MYSQL = "b7219e5b1f9c34348a1293238c30ce53";
    private static final String JVM = "e00faf967f4b6027a5e896584918e1aa";
    private static final String COLLECTION = "e28bbf87a98ab8aebfb32c66b2a08908";
    private static final String CONCURRENT = "06508d6dfe2f5477485e898f0a46240d";
    private static final String JAVA_BASIC = "d98fd52fcf48cec21c4046bb2abee72b";

    public static List<QAPair> getAll() {
        return List.of(
                // ==================== MySQL 篇（35条）====================
                pair("两张表怎么进行连接", 6, MYSQL, "MySQL-连接查询"),
                pair("什么是内连接", 9, MYSQL, "MySQL-连接查询"),
                pair("什么是外连接", 9, MYSQL, "MySQL-连接查询"),
                pair("左连接和右连接有什么区别", 11, MYSQL, "MySQL-连接查询"),
                pair("什么是交叉连接", 10, MYSQL, "MySQL-连接查询"),
                pair("介绍一下数据库的三大范式", 14, MYSQL, "MySQL-设计"),
                pair("建表的时候需要考虑哪些问题", 14, MYSQL, "MySQL-设计"),
                pair("varchar 与 char 的区别", 15, MYSQL, "MySQL-数据类型"),
                pair("redo log 的写入过程了解吗", 68, MYSQL, "MySQL-Redo Log"),
                pair("慢查询怎么分析", 88, MYSQL, "MySQL-性能优化"),
                pair("如何优化慢查询语句", 88, MYSQL, "MySQL-性能优化"),
                pair("联合索引最左前缀法则", 92, MYSQL, "MySQL-索引"),
                pair("子查询和 JOIN 有什么区别", 100, MYSQL, "MySQL-性能优化"),
                pair("SQL 优化的一般步骤是什么", 325, MYSQL, "MySQL-性能优化"),
                pair("能简单说一下索引的分类吗", 132, MYSQL, "MySQL-索引"),
                pair("主键索引了解多少", 132, MYSQL, "MySQL-索引"),
                pair("唯一索引和主键索引有什么区别", 135, MYSQL, "MySQL-索引"),
                pair("全文索引了解多少", 139, MYSQL, "MySQL-索引"),
                pair("一棵B+树能存储多少条数据", 167, MYSQL, "MySQL-B+树"),
                pair("叶子节点中存的是什么数据", 187, MYSQL, "MySQL-B+树"),
                pair("什么是覆盖索引", 120, MYSQL, "MySQL-索引"),
                pair("索引下推是什么", 220, MYSQL, "MySQL-索引"),
                pair("select for update 有什么需要注意的", 230, MYSQL, "MySQL-锁"),
                pair("怎么更改事务的隔离级别", 276, MYSQL, "MySQL-事务"),
                pair("MVCC 的实现原理是什么", 295, MYSQL, "MySQL-事务"),
                pair("MySQL 分片有哪几种方式", 310, MYSQL, "MySQL-分片"),
                pair("什么是深分页", 358, MYSQL, "MySQL-分页"),
                pair("MySQL 是先建立索引好还是先插入数据好", 358, MYSQL, "MySQL-索引优化"),
                pair("MySQL 导致 CPU 飙升怎么处理", 324, 325, MYSQL, "MySQL-性能优化"),
                pair("怎么删除创建一张表和设定主键", 6, 7, MYSQL, "MySQL-DDL"),
                pair("千万级大表如何添加字段", 324, MYSQL, "MySQL-性能优化"),
                pair("1000W 数据表关联查询如何优化", 330, MYSQL, "MySQL-性能优化"),
                pair("找到每个班年龄排前两名的SQL怎么写", 328, MYSQL, "MySQL-SQL题"),
                pair("MySQL 出现性能差的原因有哪些", 5, MYSQL, "MySQL-性能优化"),
                pair("MySQL 8.0 如何添加字段", 324, MYSQL, "MySQL-性能优化"),

                // ==================== JVM 篇（20条）====================
                pair("安全点和安全区域是什么", 40, JVM, "JVM-GC"),
                pair("如何排查 OOM", 54, JVM, "JVM-内存"),
                pair("遇到内存泄露和溢出的情况怎么处理", 54, JVM, "JVM-内存"),
                pair("内存溢出和内存泄漏有什么区别", 45, JVM, "JVM-内存"),
                pair("如何判断一个对象是否存活", 60, JVM, "JVM-GC"),
                pair("垃圾回收器的作用是什么", 80, JVM, "JVM-GC"),
                pair("ZGC 的特点是什么", 80, JVM, "JVM-GC"),
                pair("什么是三色标记法", 85, JVM, "JVM-GC"),
                pair("怎么查看当前 JVM 的垃圾收集器", 90, JVM, "JVM-GC"),
                pair("配置堆内存大小的参数有哪些", 95, JVM, "JVM-配置"),
                pair("配置 GC 收集器的参数有哪些", 95, JVM, "JVM-配置"),
                pair("频繁 minor gc 怎么办", 100, JVM, "JVM-GC"),
                pair("类加载器有哪几种", 105, JVM, "JVM-类加载"),
                pair("什么是双亲委派模型", 110, JVM, "JVM-类加载"),
                pair("Java SPI 机制了解吗", 110, JVM, "JVM-类加载"),
                pair("说说 Java 解释执行的流程", 120, JVM, "JVM-执行引擎"),
                pair("线上内存问题怎么定位", 100, JVM, "JVM-内存"),
                pair("运行时常量池了解吗", 70, JVM, "JVM-内存"),
                pair("Java 运行时数据区有哪些", 45, 54, JVM, "JVM-内存"),
                pair("什么是 JIT 编译", 120, JVM, "JVM-执行引擎"),

                // ==================== 集合框架篇（18条）====================
                pair("集合框架有哪些", 4, COLLECTION, "集合-概述"),
                pair("用过的集合类有哪些", 7, COLLECTION, "集合-概述"),
                pair("ArrayList 和 LinkedList 有什么区别", 7, 11, COLLECTION, "集合-List"),
                pair("ArrayList 的扩容机制了解吗", 7, 11, COLLECTION, "集合-List"),
                pair("LinkedList 的实现原理", 7, COLLECTION, "集合-List"),
                pair("队列和栈的区别", 8, COLLECTION, "集合-队列"),
                pair("哪些集合类是线程安全的", 8, COLLECTION, "集合-线程安全"),
                pair("Collection 继承了哪些接口", 8, COLLECTION, "集合-概述"),
                pair("HashMap 的 put 流程知道吗", 85, COLLECTION, "集合-HashMap"),
                pair("HashMap 怎么查找元素", 85, COLLECTION, "集合-HashMap"),
                pair("HashMap 的 hash 函数是怎么设计的", 85, COLLECTION, "集合-HashMap"),
                pair("为什么 HashMap 的容量是 2 的幂次方", 85, COLLECTION, "集合-HashMap"),
                pair("HashMap 是线程安全的吗", 86, COLLECTION, "集合-HashMap"),
                pair("JDK 8 对 HashMap 做了哪些优化", 86, COLLECTION, "集合-HashMap"),
                pair("HashSet 怎么判断元素重复", 86, COLLECTION, "集合-Set"),
                pair("红黑树怎么保持平衡的", 85, COLLECTION, "集合-树"),
                pair("解决哈希冲突有哪些方法", 85, COLLECTION, "集合-HashMap"),
                pair("LinkedHashMap 怎么实现有序的", 85, 86, COLLECTION, "集合-HashMap"),

                // ==================== 并发编程篇（20条）====================
                pair("进程和线程的区别", 853, CONCURRENT, "并发-线程"),
                pair("创建线程的三种方式", 851, CONCURRENT, "并发-线程"),
                pair("为什么要重写 run 方法", 851, CONCURRENT, "并发-线程"),
                pair("run 方法和 start 方法有什么区别", 851, CONCURRENT, "并发-线程"),
                pair("线程的优先级可靠吗", 59, CONCURRENT, "并发-线程"),
                pair("什么是上下文切换", 85, CONCURRENT, "并发-线程"),
                pair("什么是共享变量", 854, CONCURRENT, "并发-JMM"),
                pair("内存可见性问题是如何发生的", 854, CONCURRENT, "并发-JMM"),
                pair("如何保证内存可见性", 854, CONCURRENT, "并发-JMM"),
                pair("为什么指令重排可以提高性能", 854, CONCURRENT, "并发-JMM"),
                pair("重排序有哪几种", 854, CONCURRENT, "并发-JMM"),
                pair("什么是 CAS", 687, CONCURRENT, "并发-原子类"),
                pair("CAS 的三大问题是什么", 856, CONCURRENT, "并发-原子类"),
                pair("AtomicBoolean 类是怎样实现更新的", 687, CONCURRENT, "并发-原子类"),
                pair("说说 AQS 的原理", 308, CONCURRENT, "并发-AQS"),
                pair("ThreadLocal 的原理", 575, CONCURRENT, "并发-ThreadLocal"),
                pair("Semaphore 的使用场景", 741, CONCURRENT, "并发-工具类"),
                pair("ScheduledExecutorService 怎么用", 644, CONCURRENT, "并发-线程池"),
                pair("什么是 CopyOnWrite", 859, CONCURRENT, "并发-容器"),
                pair("CopyOnWriteArrayList 的原理", 859, CONCURRENT, "并发-容器"),

                // ==================== Java 基础篇（30条）====================
                pair("什么是抽象方法", 369, JAVA_BASIC, "Java基础-OOP"),
                pair("抽象类和接口的区别", 453, JAVA_BASIC, "Java基础-OOP"),
                pair("什么是多态", 514, JAVA_BASIC, "Java基础-OOP"),
                pair("什么是继承", 426, JAVA_BASIC, "Java基础-OOP"),
                pair("String 和 StringBuilder 有什么区别", 262, 264, JAVA_BASIC, "Java基础-String"),
                pair("StringBuffer 和 StringBuilder 有什么区别", 262, JAVA_BASIC, "Java基础-String"),
                pair("StringBuilder 底层是怎么扩容的", 266, JAVA_BASIC, "Java基础-String"),
                pair("String 不可变的好处是什么", 266, JAVA_BASIC, "Java基础-String"),
                pair("什么是泛型", 775, 776, JAVA_BASIC, "Java基础-泛型"),
                pair("泛型类型擦除是什么", 1886, JAVA_BASIC, "Java基础-泛型"),
                pair("什么是反射", 1898, 1905, JAVA_BASIC, "Java基础-反射"),
                pair("反射中 Method 的 invoke 方法原理", 1898, JAVA_BASIC, "Java基础-反射"),
                pair("字节流和字符流的区别", 1014, JAVA_BASIC, "Java基础-IO"),
                pair("Java IO 的核心抽象类有哪些", 1014, JAVA_BASIC, "Java基础-IO"),
                pair("checked 异常和 unchecked 异常有什么区别", 1248, JAVA_BASIC, "Java基础-异常"),
                pair("final 关键字的作用是什么", 209, JAVA_BASIC, "Java基础-关键字"),
                pair("static 关键字的作用是什么", 369, JAVA_BASIC, "Java基础-关键字"),
                pair("Java 命名规范有哪些", 1801, JAVA_BASIC, "Java基础-规范"),
                pair("什么是字符编码", 1973, JAVA_BASIC, "Java基础-编码"),
                pair("中文乱码是如何出现的", 1973, JAVA_BASIC, "Java基础-编码"),
                pair("LinkedList 的底层实现", 1956, JAVA_BASIC, "Java基础-集合"),
                pair("ArrayList 是如何实现的", 1956, JAVA_BASIC, "Java基础-集合"),
                pair("什么是 NIO", 1971, JAVA_BASIC, "Java基础-NIO"),
                pair("NIO 比 IO 强在哪", 1971, JAVA_BASIC, "Java基础-NIO"),
                pair("LinkedHashMap 如何维持访问顺序", 929, JAVA_BASIC, "Java基础-集合"),
                pair("EnumMap 和 EnumSet 了解吗", 622, JAVA_BASIC, "Java基础-枚举"),
                pair("Objects.equals 和 a.equals 有什么区别", 286, JAVA_BASIC, "Java基础-工具类"),
                pair("什么是 try-with-resources", 593, JAVA_BASIC, "Java基础-异常"),
                pair("什么是 Unicode", 1973, JAVA_BASIC, "Java基础-编码"),
                pair("抽象类和普通类的区别", 369, JAVA_BASIC, "Java基础-OOP"),

                // ==================== 交叉混合（2条）====================
                pair("ConcurrentHashMap 怎么保证线程安全", 435, CONCURRENT, "并发-容器"),
                QAPair.of("HashMap 和 ConcurrentHashMap 的区别", List.of(86, 435), List.of(COLLECTION, CONCURRENT), "集合-并发"),

                // ==================== v3 新增：JVM 篇（15条）====================
                pair("内存泄漏可能由哪些原因导致", 48, JVM, "JVM-内存"),
                pair("jstat 命令怎么用", 52, JVM, "JVM-调优"),
                pair("jmap 命令怎么用", 52, JVM, "JVM-调优"),
                pair("对象一定分配在堆上吗", 42, JVM, "JVM-内存"),
                pair("说说标记整理算法", 72, JVM, "JVM-GC"),
                pair("说说分代收集算法", 72, JVM, "JVM-GC"),
                pair("说说 ParNew 收集器", 78, JVM, "JVM-GC"),
                pair("说说 Parallel Scavenge 收集器", 78, JVM, "JVM-GC"),
                pair("说说 CMS 收集器", 82, JVM, "JVM-GC"),
                pair("G1 垃圾回收器的原理", 88, JVM, "JVM-GC"),
                pair("CPU 飙升怎么排查", 98, JVM, "JVM-调优"),
                pair("jstack 命令怎么用", 98, JVM, "JVM-调优"),
                pair("热部署怎么实现", 112, JVM, "JVM-类加载"),
                pair("自定义类加载器实现热替换", 118, JVM, "JVM-类加载"),
                pair("Minor GC 和 Full GC 有什么区别", 128, JVM, "JVM-GC"),

                // ==================== v3 新增：并发篇（12条）====================
                pair("AtomicInteger 的实现原理", 195, 238, CONCURRENT, "并发-原子类"),
                pair("AQS 的数据结构", 206, CONCURRENT, "并发-AQS"),
                pair("读写锁 ReentrantReadWriteLock", 265, CONCURRENT, "并发-锁"),
                pair("CyclicBarrier 和 CountDownLatch 的区别", 294, 752, CONCURRENT, "并发-工具类"),
                pair("ReentrantLock 和 synchronized 怎么选择", 315, CONCURRENT, "并发-锁"),
                pair("CopyOnWriteArrayList 的 get 方法原理", 556, CONCURRENT, "并发-容器"),
                pair("Semaphore 的 acquire 方法", 745, CONCURRENT, "并发-工具类"),
                pair("CountDownLatch 的使用场景", 752, CONCURRENT, "并发-工具类"),
                pair("CyclicBarrier 的使用场景", 759, CONCURRENT, "并发-工具类"),
                pair("阻塞队列 BlockingQueue 的原理", 513, 523, CONCURRENT, "并发-队列"),
                pair("volatile 和 synchronized 的区别", 114, 118, CONCURRENT, "并发-关键字"),
                pair("ConcurrentHashMap 的 size 方法", 465, CONCURRENT, "并发-容器"),

                // ==================== v3 新增：Java基础篇（15条）====================
                pair("Arrays.asList 有什么坑", 185, JAVA_BASIC, "Java基础-工具类"),
                pair("什么是序列化", 1177, 1193, JAVA_BASIC, "Java基础-IO"),
                pair("transient 关键字的作用", 1203, JAVA_BASIC, "Java基础-关键字"),
                pair("serialVersionUID 有什么用", 1218, JAVA_BASIC, "Java基础-IO"),
                pair("Externalizable 和 Serializable 的区别", 1210, JAVA_BASIC, "Java基础-IO"),
                pair("浅克隆和深克隆的区别", 1837, JAVA_BASIC, "Java基础-OOP"),
                pair("Comparator 和 Comparable 的区别", 957, JAVA_BASIC, "Java基础-工具类"),
                pair("ObjectOutputStream 怎么用", 1051, JAVA_BASIC, "Java基础-IO"),
                pair("Lambda 表达式是什么", 1359, JAVA_BASIC, "Java基础-Lambda"),
                pair("如何获取本机 IP", 1467, JAVA_BASIC, "Java基础-网络"),
                pair("Socket 编程基础", 1640, JAVA_BASIC, "Java基础-网络"),
                pair("BufferedOutputStream 的作用", 1044, JAVA_BASIC, "Java基础-IO"),
                pair("DataInputStream 和 DataOutputStream", 1039, JAVA_BASIC, "Java基础-IO"),
                pair("ByteArrayOutputStream 怎么用", 1051, JAVA_BASIC, "Java基础-IO"),
                pair("FileOutputStream 如何追加数据", 1025, JAVA_BASIC, "Java基础-IO"),

                // ==================== v3 新增：MySQL 篇（8条）====================
                pair("MySQL 的连接有哪些类型", 11, MYSQL, "MySQL-连接查询"),
                pair("什么是数据库三范式", 14, MYSQL, "MySQL-设计"),
                pair("什么是 MySQL 的 ICP", 220, MYSQL, "MySQL-索引"),
                pair("MySQL 怎么分析慢查询", 325, MYSQL, "MySQL-性能优化"),
                pair("Explain 命令怎么看", 325, MYSQL, "MySQL-性能优化"),
                pair("MySQL 8.0 的 INSTANT 算法", 324, MYSQL, "MySQL-性能优化"),
                pair("深分页怎么优化", 336, MYSQL, "MySQL-分页"),
                pair("表数据的导入导出怎么优化", 321, 322, MYSQL, "MySQL-性能优化")
        );
    }

    // ==================== 快捷构造 ====================

    private static QAPair pair(String question, int chunkId, String md5, String category) {
        return QAPair.of(question, List.of(chunkId), List.of(md5), category);
    }

    private static QAPair pair(String question, int chunkId1, int chunkId2, String md5, String category) {
        return QAPair.of(question, List.of(chunkId1, chunkId2), List.of(md5), category);
    }

    private static QAPair pair(String question, int chunkId, String md5a, String md5b, String category) {
        return QAPair.of(question, List.of(chunkId), List.of(md5a, md5b), category);
    }
}
