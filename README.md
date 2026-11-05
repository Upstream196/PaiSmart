# PaiSmart 派聪明

企业级 AI 知识库问答系统，基于检索增强生成（RAG）：用户上传文档 → 解析分块 → 向量化入库 → 用自然语言提问，系统检索相关片段并交由大模型生成有据可依的回答。

- 后端：Spring Boot 3.4.2 / Java 17
- 前端：Vue 3 + TypeScript + Naive UI
- 检索：Elasticsearch 8.10（dense_vector 向量召回 + IK 分词关键词检索，混合排序）
- 异步流水线：Kafka + Apache Tika + MinIO
- 大模型：DeepSeek（chat），DashScope `text-embedding-v4`（2048 维）

> 本项目为学习用途的二次开发，遵循仓库根目录 `LICENSE`（Apache-2.0）。

## 功能特性

- **文档管理**：分片上传、MD5 秒传去重、按组织标签归类，文档可设为公开或仅组织内可见
- **文档解析**：Tika 解析 PDF / Word / TXT 等格式，按字符窗口切块后送入向量化流程
- **混合检索**：ES `script_score` 计算余弦相似度，与文本匹配结果经 `HybridSearchService` 合并排序
- **流式问答**：WebSocket 逐 token 推送，支持中途停止生成，会话历史写入 Redis
- **多租户**：组织标签为树形结构（`organization_tags`），用户拥有主组织与多个标签，数据按组织 + 公开可见性过滤
- **鉴权**：Spring Security + JWT，双过滤器链（`JwtAuthenticationFilter` → `OrgTagAuthorizationFilter`），支持令牌自动续期；WebSocket 走短时 token 校验

## 系统架构

```
浏览器 ── Vue 3 (Naive UI) ── REST / WebSocket
                                  │
                                  ▼
              Spring Security（JWT + 组织标签）
                                  │
      Controller ── Service ── Repository (JPA) ── MySQL
                       │
        ┌──────────────┼───────────────┬──────────────┐
        ▼              ▼               ▼              ▼
      MinIO         Kafka          Redis        Elasticsearch
     (原始文件)   (解析/向量化)   (会话/缓存)    (向量 + 全文索引)
                       │
                       ▼
              Tika 解析 ── 分块 ── Embedding（DashScope）── 写入 ES
```

**上传到可检索的完整链路**

1. 前端分片上传，服务端合并后写 MinIO 并落库
2. 发送消息到 Kafka `file-processing` topic
3. `FileProcessingConsumer` 调用 `ParseService` 解析文本并分块
4. `VectorizationService` 按批（≤10 条）调用 Embedding 接口生成 2048 维向量
5. `ElasticsearchService` 批量写入索引（IK 分词字段 + dense_vector 字段）

**问答链路**

1. 前端通过 `GET /api/chat/websocket-token` 换取短时 token，建立 WebSocket 连接
2. `ChatHandler` 取会话历史（Redis，最近 20 条）
3. `HybridSearchService.searchWithPermission()` 检索 top-5 片段
4. `DeepSeekClient.streamResponse()` 流式返回，结束后持久化会话

## 目录结构

```
PaiSmart/
├── src/main/java/com/yizhaoqi/smartpai/
│   ├── client/        # DeepSeek、Embedding 等外部 API 客户端
│   ├── config/        # 安全、Kafka、ES、MinIO 等配置
│   ├── consumer/      # Kafka 消费者
│   ├── controller/    # REST 接口
│   ├── entity/        # JPA 实体
│   ├── exception/     # 全局异常处理
│   ├── handler/       # WebSocket 处理器
│   ├── model/         # 领域模型 / DTO
│   ├── repository/    # 数据访问层
│   ├── service/       # 业务逻辑
│   └── utils/         # 工具类
├── src/main/resources/
│   ├── es-mappings/   # 索引 mapping
│   └── logback-spring.xml
├── frontend/          # Vue 3 前端（pnpm workspace）
├── docs/
│   ├── docker-compose.yaml   # 本地中间件编排
│   └── databases/ddl.sql     # 建表语句
└── pom.xml
```

## 快速开始

### 1. 环境要求

| 组件 | 版本 |
|---|---|
| JDK | 17 |
| Maven | 3.8+ |
| Node.js | ≥ 18.20.0 |
| pnpm | ≥ 8.7.0 |
| Docker | 可选，用于一键启动中间件 |

### 2. 启动中间件

```bash
cd docs
docker-compose up -d
```

| 服务 | 地址 | 说明 |
|---|---|---|
| MySQL | `localhost:3306` | root 密码见 `docker-compose.yaml` |
| Redis | `localhost:6379` | 带密码，见 compose 配置 |
| Kafka | `localhost:9092` | 已自动创建 `file-processing`、`vectorization` topic |
| MinIO | `localhost:19000`（API）/ `19001`（控制台） | |
| Elasticsearch | `localhost:9200` | 启动时自动安装 IK 分词插件 |

> ES 首次启动会下载 IK 插件并分配 2G 堆内存，请预留足够资源。不用 Docker 的可自行安装对应版本。

### 3. 准备配置文件

`application*.yml` 含密钥，**未纳入版本控制**。仓库提供了模板文件：

```bash
cp src/main/resources/application.example.yml src/main/resources/application.yml
```

再把占位符替换成你自己的值，至少需要填写以下项：

| 配置项 | 说明 |
|---|---|
| `spring.datasource.*` | MySQL 连接串与账号密码 |
| `spring.data.redis.*` | Redis 地址与密码 |
| `spring.kafka.bootstrap-servers` | Kafka 地址 |
| `minio.endpoint` / `accessKey` / `secretKey` / `bucketName` | 对象存储 |
| `elasticsearch.host` / `port` / `username` / `password` | 与你实际部署的 ES 保持一致 |
| `deepseek.api.url` / `model` / `key` | 大模型接口，可指向本地 Ollama（`http://localhost:11434/v1`） |
| `embedding.api.url` / `key` / `model` / `dimension` | 向量模型，默认 DashScope `text-embedding-v4`，2048 维 |
| `jwt.secret-key` | 至少 32 字节的随机串 |
| `admin.username` / `password` | 首次启动自动创建的管理员账号 |

> 索引维度必须与 `embedding.dimension` 一致；更换向量模型后需重建索引。

### 4. 启动后端

```bash
mvn spring-boot:run                                     # 默认配置
mvn spring-boot:run -Dspring-boot.run.profiles=dev      # dev 配置
mvn clean package                                       # 打包
```

服务运行在 `http://localhost:8081`。

### 5. 启动前端

```bash
cd frontend
pnpm install
pnpm dev
```

访问 `http://localhost:9527`，使用 `admin.*` 配置的账号登录（默认 `admin` / `admin123`，请及时修改）。

## 常用命令

| 场景 | 命令 |
|---|---|
| 后端测试 | `mvn test` |
| 单测类 | `mvn test -Dtest=UserServiceTest` |
| 前端类型检查 | `cd frontend && pnpm typecheck` |
| 前端构建 | `cd frontend && pnpm build` |
| 代码检查 | `cd frontend && pnpm lint` |

## 注意事项

- **密钥安全**：`application*.yml`、`.env.local`、证书文件均已在 `.gitignore` 中排除，提交前请用 `git status` 复核。
- **默认账号**：`AdminUserInitializer` 启动时会按 `admin.*` 创建管理员，生产环境务必修改密码。
- **索引重建**：修改分块大小（`file.parsing.chunk-size`）或向量维度后，需删除并重建 ES 索引，再重新上传文档。
- **WebSocket 鉴权**：连接不经过 JWT 过滤器，必须先调用 `/api/chat/websocket-token` 获取短时 token 并作为查询参数带上。

## License

[Apache-2.0](LICENSE)
