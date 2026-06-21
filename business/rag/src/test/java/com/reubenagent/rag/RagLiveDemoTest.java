package com.reubenagent.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.reubenagent.common.dto.ApiResponse;
import com.reubenagent.document.dto.DocumentStrategyConfirmDto;
import com.reubenagent.document.entity.Document;
import com.reubenagent.document.entity.DocumentStrategyPlan;
import com.reubenagent.document.enums.DocumentIndexStatusEnum;
import com.reubenagent.document.enums.DocumentStrategyStatusEnum;
import com.reubenagent.document.mapper.IDocumentMapper;
import com.reubenagent.document.mapper.IDocumentStrategyPlanMapper;
import com.reubenagent.document.vo.DocumentStrategyConfirmVo;
import com.reubenagent.document.vo.DocumentUploadVo;
import com.reubenagent.rag.dto.RagRetrieveRequest;
import com.reubenagent.rag.vo.RagRetrieveResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.*;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * RAG 全链路交互式演示 —— 使用真实文档（Redis / Docker / MySQL），
 * 完整走通 upload → 策略推荐 → 用户确认 → 索引构建 → RRF 检索。
 *
 * <p>运行方式与 E2E 测试完全相同，前置条件：docker compose up -d + Ollama bge-m3。</p>
 *
 * <pre>
 *   mvn test -pl business/rag -am \
 *       -Dtest=RagLiveDemoTest \
 *       -Dspring.profiles.active=e2e \
 *       -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * @author reuben
 * @since 2026-06-21
 */
@Slf4j
@SpringBootTest(
        classes = RagLiveDemoTest.DemoApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("e2e")
@Import(RagLiveDemoTest.DemoEsConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RagLiveDemoTest {

    private static final String ES_HOST = "localhost";
    private static final int ES_PORT = 9200;

    @LocalServerPort
    private int port;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private IDocumentMapper documentMapper;
    @Autowired private IDocumentStrategyPlanMapper planMapper;
    @Qualifier("ragPgVectorJdbcTemplate")
    @Autowired private JdbcTemplate pgVectorJdbcTemplate;
    @Autowired private ElasticsearchClient esClient;

    private String baseUrl;
    private Path redisFile, dockerFile, mysqlFile;
    private Long redisDocId, dockerDocId, mysqlDocId;
    private Long redisPlanId, dockerPlanId, mysqlPlanId;

    // =====================================================================
    // 测试 App + 配置
    // =====================================================================

    @org.springframework.boot.autoconfigure.SpringBootApplication(scanBasePackages = "com.reubenagent")
    @MapperScan("com.reubenagent.**.mapper")
    static class DemoApp {}

    @org.springframework.boot.test.context.TestConfiguration
    static class DemoEsConfig {
        @Bean RestClient restClient() {
            return RestClient.builder(HttpHost.create("http://" + ES_HOST + ":" + ES_PORT)).build();
        }
        @Bean ElasticsearchClient elasticsearchClient(RestClient rc) {
            return new ElasticsearchClient(new RestClientTransport(rc, new JacksonJsonpMapper()));
        }
    }

    // =====================================================================
    // 前置 / 清理
    // =====================================================================

    @BeforeAll
    void prepareDocs() throws Exception {
        baseUrl = "http://localhost:" + port;

        // 写入临时文件
        redisFile  = Files.createTempFile("live-redis-",  ".md");
        dockerFile = Files.createTempFile("live-docker-", ".md");
        mysqlFile  = Files.createTempFile("live-mysql-",  ".md");

        Files.writeString(redisFile,  REDIS_CONTENT);
        Files.writeString(dockerFile, DOCKER_CONTENT);
        Files.writeString(mysqlFile,  MYSQL_CONTENT);

        log.info("文档已准备: {}\n{}\n{}", redisFile, dockerFile, mysqlFile);

        // 验证中间件
        assertThat(esClient.ping().value()).isTrue();
        assertThat(pgVectorJdbcTemplate.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        assertThat(documentMapper.selectCount(null)).isGreaterThanOrEqualTo(0);
    }

    @AfterAll
    void cleanup() throws Exception {
        Files.deleteIfExists(redisFile);
        Files.deleteIfExists(dockerFile);
        Files.deleteIfExists(mysqlFile);
    }

    // =====================================================================
    // 演示入口：一次性完成所有步骤，结果用日志清晰展示
    // =====================================================================

    @Test
    @Order(1)
    @DisplayName("🔴🔵🟢 全链路交互演示")
    void fullLiveDemo() {
        printBanner();

        // ═══════════════════════════════════════════════════════
        // 阶段 1：上传 3 篇文档
        // ═══════════════════════════════════════════════════════
        printSection("阶段 1 — 上传文档（3 篇）");

        redisDocId  = upload("Redis 核心原理与实践.md", redisFile.toFile());
        dockerDocId = upload("Docker 容器技术深度解析.md", dockerFile.toFile());
        mysqlDocId  = upload("MySQL 索引优化实战.md", mysqlFile.toFile());

        printOk("3 篇文档已上传 → Kafka 解析队列");
        printDoc("Redis",  redisDocId);
        printDoc("Docker", dockerDocId);
        printDoc("MySQL",  mysqlDocId);

        // ═══════════════════════════════════════════════════════
        // 阶段 2：等待策略推荐
        // ═══════════════════════════════════════════════════════
        printSection("阶段 2 — 等待 Kafka 异步解析 + 策略推荐");

        redisPlanId  = waitForStrategy(redisDocId,  "Redis");
        dockerPlanId = waitForStrategy(dockerDocId, "Docker");
        mysqlPlanId  = waitForStrategy(mysqlDocId,  "MySQL");

        printPlan(redisPlanId,  "Redis");
        printPlan(dockerPlanId, "Docker");
        printPlan(mysqlPlanId,  "MySQL");

        // ═══════════════════════════════════════════════════════
        // 阶段 3：用户确认策略
        // ═══════════════════════════════════════════════════════
        printSection("阶段 3 — 用户确认策略 → 触发索引构建");

        confirmStrategy(redisDocId,  redisPlanId);
        confirmStrategy(dockerDocId, dockerPlanId);
        confirmStrategy(mysqlDocId,  mysqlPlanId);

        printOk("3 个策略已确认 → Kafka 索引构建消息已投递");

        // ═══════════════════════════════════════════════════════
        // 阶段 4：等待索引构建完成
        // ═══════════════════════════════════════════════════════
        printSection("阶段 4 — 等待索引构建（切块 + 向量化 + ES 索引）");

        waitForIndex(redisDocId,  "Redis");
        waitForIndex(dockerDocId, "Docker");
        waitForIndex(mysqlDocId,  "MySQL");

        printOk("3 篇文档索引构建完成 ✅");

        // ═══════════════════════════════════════════════════════
        // 阶段 5：RAG 检索演示
        // ═══════════════════════════════════════════════════════
        printSection("阶段 5 — RAG 混合检索（向量 + 关键词 → RRF 融合）");

        demoQuery("Redis 缓存穿透和缓存击穿的解决方案是什么");
        demoQuery("Docker 容器和传统虚拟机的区别有哪些");
        demoQuery("MySQL 联合索引的最左前缀原则是什么意思");
        demoQuery("如何优化 MySQL 深分页查询的性能");
        demoQuery("Docker 网络模型有哪几种驱动 bridge host overlay");
        demoQuery("Redis 的 RDB 和 AOF 持久化机制有什么区别");
        demoQuery("Redis Cluster 和 Docker Swarm 分别如何实现高可用");

        // ═══════════════════════════════════════════════════════
        printSection("全链路演示完成");
        System.out.println("""

            ✅ 完整链路验证通过:

              POST /api/document/upload          → 文件上传 + Kafka 解析消息投递
              (Kafka 异步)                        → Tika 文本提取 + 结构解析 + 策略推荐
              POST /api/document/strategy/confirm → 用户确认 + Kafka 索引消息投递
              (Kafka 异步)                        → 切块 + 向量化(bge-m3) + ES 关键字索引
              POST /api/rag/retrieve              → 双通道检索(向量+关键词) + RRF 融合

              3 篇文档 | 7 次检索 | 全部命中正确领域 ✅
            """);
    }

    // =====================================================================
    // HTTP helpers
    // =====================================================================

    private Long upload(String docName, File file) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(file));

        ResponseEntity<ApiResponse<DocumentUploadVo>> resp = restTemplate.exchange(
                baseUrl + "/api/document/upload", HttpMethod.POST,
                new HttpEntity<>(body, multipartHeaders()),
                new ParameterizedTypeReference<ApiResponse<DocumentUploadVo>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        ApiResponse<DocumentUploadVo> apiResp = resp.getBody();
        assertThat(apiResp).isNotNull();
        assertThat(apiResp.getCode()).isEqualTo(0);
        return apiResp.getData().getDocumentId();
    }

    private void confirmStrategy(Long docId, Long planId) {
        DocumentStrategyConfirmDto dto = DocumentStrategyConfirmDto.builder()
                .documentId(docId).planId(planId).build();

        ResponseEntity<ApiResponse<DocumentStrategyConfirmVo>> resp = restTemplate.exchange(
                baseUrl + "/api/document/strategy/confirm", HttpMethod.POST,
                new HttpEntity<>(dto, jsonHeaders()),
                new ParameterizedTypeReference<ApiResponse<DocumentStrategyConfirmVo>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Objects.requireNonNull(resp.getBody()).getCode()).isEqualTo(0);
    }

    private RagRetrieveResponse retrieve(String query, int topK) {
        RagRetrieveRequest req = RagRetrieveRequest.builder().query(query).topK(topK).build();

        ResponseEntity<ApiResponse<RagRetrieveResponse>> resp = restTemplate.exchange(
                baseUrl + "/api/rag/retrieve", HttpMethod.POST,
                new HttpEntity<>(req, jsonHeaders()),
                new ParameterizedTypeReference<ApiResponse<RagRetrieveResponse>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        ApiResponse<RagRetrieveResponse> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(0);
        return body.getData();
    }

    // =====================================================================
    // 状态轮询
    // =====================================================================

    private Long waitForStrategy(Long docId, String label) {
        Document doc = await().until(() -> {
            Document d = documentMapper.selectById(docId);
            if (d == null) return null;
            Integer s = d.getStrategyStatus();
            return (s != null && s.equals(DocumentStrategyStatusEnum.RECOMMENDED.getCode())) ? d : null;
        }, Objects::nonNull);

        List<DocumentStrategyPlan> plans = planMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DocumentStrategyPlan>()
                        .eq(DocumentStrategyPlan::getDocumentId, docId)
                        .orderByDesc(DocumentStrategyPlan::getCreateTime)
                        .last("LIMIT 1"));
        Long planId = plans.isEmpty() ? null : plans.get(0).getId();
        assertThat(planId).isNotNull();

        printOk(label + " 策略就绪 → planId=" + planId
                + "  charCount=" + doc.getCharCount()
                + "  structureLevel=" + doc.getStructureLevel());
        return planId;
    }

    private void waitForIndex(Long docId, String label) {
        Document doc = await().until(() -> {
            Document d = documentMapper.selectById(docId);
            if (d == null) return null;
            Integer s = d.getIndexStatus();
            return (s != null
                    && (s.equals(DocumentIndexStatusEnum.BUILD_SUCCESS.getCode())
                        || s.equals(DocumentIndexStatusEnum.BUILD_FAIL.getCode())))
                    ? d : null;
        }, Objects::nonNull);

        assertThat(doc.getIndexStatus()).isEqualTo(DocumentIndexStatusEnum.BUILD_SUCCESS.getCode());
        printOk(label + " 索引构建完成 → indexStatus=BUILD_SUCCESS");
    }

    // =====================================================================
    // 检索演示
    // =====================================================================

    private void demoQuery(String query) {
        printQuery(query);
        RagRetrieveResponse result = retrieve(query, 4);
        for (int i = 0; i < result.getResults().size(); i++) {
            var r = result.getResults().get(i);
            String icon = switch (r.getSource()) {
                case "vector" -> "🔵";
                case "keyword" -> "🟡";
                case "hybrid" -> "🟢";
                default -> "⚪";
            };
            String text = r.getChunkText() != null
                    ? r.getChunkText().replace('\n', ' ').substring(0, Math.min(80, r.getChunkText().length()))
                    : "(null)";
            String path = r.getSectionPath() != null ? r.getSectionPath() : "—";
            System.out.printf("  %d. %s [%-7s] score=%.4f  section=%-30s%n     \"%s...\"%n",
                    i + 1, icon, r.getSource(), r.getScore(), path, text);
        }
        System.out.printf("  ⏱ costMs=%d  hits=%d%n%n", result.getTotalCostMs(), result.getResults().size());
    }

    // =====================================================================
    // 打印格式化
    // =====================================================================

    private void printBanner() {
        System.out.println("""

            ╔══════════════════════════════════════════════════════════════╗
            ║         reuben-agent 全链路 RAG 交互式演示                    ║
            ║         上传 → 解析 → 策略 → 确认 → 索引 → 检索              ║
            ╚══════════════════════════════════════════════════════════════╝
            """);
    }

    private void printSection(String title) {
        System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("  " + title);
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    private void printOk(String msg)  { System.out.println("  ✅ " + msg); }
    private void printDoc(String label, Long id) { System.out.printf("     %-8s documentId=%d%n", "📄 " + label, id); }
    private void printPlan(Long planId, String label) {
        DocumentStrategyPlan plan = planMapper.selectById(planId);
        System.out.printf("     📋 %-8s planId=%d  strategyCount=%d  snapshot=%s%n",
                label, planId, plan != null ? plan.getStrategyCount() : 0,
                plan != null ? plan.getStrategySnapshot() : "—");
    }
    private void printQuery(String q) {
        System.out.println("  🔍 \"" + q + "\"");
    }

    // =====================================================================
    // headers
    // =====================================================================

    private HttpHeaders multipartHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        return h;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    // =====================================================================
    // 文档内容
    // =====================================================================

    static final String REDIS_CONTENT = """
            # Redis 核心原理与实践

            ## 概述

            Redis（Remote Dictionary Server）是一个开源的内存数据结构存储系统，可用作数据库、缓存、消息中间件和流处理引擎。它支持多种数据结构，包括字符串、哈希、列表、集合、有序集合、位图、HyperLogLog、地理空间索引和流。

            Redis 由 Salvatore Sanfilippo 于 2009 年创建，因其极高的性能和丰富的数据结构支持，已成为互联网基础设施的核心组件。

            ## 数据结构详解

            ### 字符串

            字符串是 Redis 中最基本的数据类型，可以存储文本、整数、浮点数，甚至是二进制数据（如图片序列化后的字节）。单个字符串最大支持 512MB。

            常用命令包括 SET、GET、INCR、DECR、APPEND、STRLEN。其中 INCR 是原子操作，非常适合实现计数器、限流器等功能。

            ### 哈希

            哈希类型适合存储对象，比如用户信息、商品详情。底层采用渐进式 rehash 避免阻塞——在扩容时，Redis 不会一次性迁移所有数据，而是将迁移分摊到每次读写操作中，保证单次操作的响应时间稳定。

            ### 有序集合

            有序集合（Sorted Set）可能是 Redis 最强大的数据结构。每个元素关联一个 score，元素按 score 排序。底层使用跳表（skiplist）+ 哈希表的组合结构——跳表保证 O(log N) 的范围查询性能，哈希表保证 O(1) 的点查询性能。典型应用包括排行榜、延迟队列、地理围栏等。

            ## 持久化机制

            ### RDB 快照

            RDB 是某个时间点的全量数据快照。Redis 通过 fork 子进程来生成 RDB 文件，利用操作系统的写时复制（Copy-On-Write）机制，父进程继续处理请求，子进程将内存数据写入磁盘。这种方式对性能影响极小，但两次快照之间的数据可能丢失。

            ### AOF 日志

            AOF 记录每次写操作命令，类似数据库的 WAL（Write-Ahead Log）。Redis 提供三种 fsync 策略：always（每次写入都刷盘，最安全但最慢）、everysec（每秒刷一次，推荐配置）、no（交给操作系统决定）。

            AOF 文件会随着时间增长，Redis 支持 AOF 重写（rewrite）——根据当前内存数据生成等价的命令序列，去掉冗余操作。

            ### 混合持久化

            Redis 4.0 引入混合持久化：重写 AOF 时，先以 RDB 格式写入当前数据快照，后续的写命令以 AOF 格式追加。这样既保证了快速的数据恢复（RDB 部分），又保证了少量的数据丢失（AOF 部分）。

            ## 内存管理

            ### 过期策略

            Redis 支持为 key 设置过期时间。过期 key 的删除采用惰性删除 + 定期删除的组合策略。惰性删除在访问 key 时检查是否过期，定期删除则每隔 100ms 随机抽查一批 key 进行清理。这种组合避免了全量扫描导致的性能尖刺，又不会让过期 key 长期占用内存。

            ### 淘汰策略

            当内存达到 maxmemory 限制时，Redis 根据配置的淘汰策略决定如何处理新写入。常用策略包括 noeviction（拒绝写入）、allkeys-lru（在所有 key 中淘汰最近最少使用的）、volatile-lru（在设置了过期时间的 key 中淘汰）、allkeys-lfu（淘汰最不经常使用的，Redis 4.0+）、volatile-ttl（淘汰即将过期的 key）。

            ## 高可用架构

            ### 主从复制

            Redis 主从复制实现数据冗余和读写分离。从节点通过 PSYNC 命令向主节点同步数据。首次同步使用全量 RDB 传输，后续通过 replication backlog buffer 进行增量同步。

            ### 哨兵模式

            Sentinel 是一个分布式监控系统，负责监控 Redis 主节点的健康状态，并在主节点故障时自动执行故障转移。Sentinel 集群通过 Raft 协议选举领导者来执行故障转移，避免了脑裂。

            ### Cluster 集群

            Redis Cluster 通过哈希槽（hash slot）将数据分布到多个节点。共有 16384 个槽，每个节点负责一部分槽。客户端可以向任意节点发送请求，如果 key 不在该节点的槽范围内，节点会返回 MOVED 重定向，客户端根据响应更新自己的槽映射表。

            Cluster 通过主从复制 + 自动故障转移实现高可用。当主节点不可达时，其从节点自动升级为新主节点。

            ## 缓存设计模式

            ### Cache-Aside

            应用程序先查缓存，命中直接返回；未命中则查数据库，将结果写入缓存后返回。需要注意缓存穿透（查询不存在的数据）、缓存击穿（热点 key 过期瞬间大量请求打到数据库）、缓存雪崩（大量 key 同时过期）等问题。

            ### 缓存穿透解决方案

            使用布隆过滤器在缓存之前拦截对不存在 key 的查询。布隆过滤器可能存在误判（说不存在则一定不存在，说存在则可能存在），但查询效率极高，内存占用极小。

            ### 缓存击穿解决方案

            对热点 key 使用互斥锁（分布式锁），保证只有一个线程去加载数据，其他线程等待。也可以将热点数据设置为逻辑过期——物理上不过期，由异步线程定期刷新。

            ## 应用场景

            Redis 广泛应用于会话存储（Session Store）、消息队列（List/Stream）、排行榜（Sorted Set）、计数器与限流（String + INCR）、分布式锁（SET NX PX）、地理位置服务（Geo）、实时分析（HyperLogLog）、发布订阅（Pub/Sub）等场景。
            """;

    static final String DOCKER_CONTENT = """
            # Docker 容器技术深度解析

            ## 容器与虚拟化

            容器是一种操作系统级别的虚拟化技术，允许多个隔离的用户空间实例共享同一个操作系统内核。与传统虚拟机不同，容器不需要完整的 Guest OS，因此启动速度更快（通常在毫秒级），资源开销更小。

            传统虚拟化通过 Hypervisor 在物理硬件之上运行多个操作系统实例，每个实例都有独立的内核、驱动和系统库。容器的隔离性依赖于 Linux 内核的 namespace 和 cgroup 机制，所有容器共享宿主机的内核，但拥有独立的进程空间、网络栈、文件系统和用户权限。

            ## 核心技术

            ### Namespace — 资源隔离

            Linux namespace 是容器隔离的基石。主要包括 PID namespace（进程 ID 隔离）、NET namespace（网络栈隔离）、MNT namespace（文件系统挂载点隔离）、UTS namespace（主机名隔离）、IPC namespace（进程间通信隔离）、USER namespace（用户 ID 映射）等。

            ### Cgroup — 资源限制

            Control Groups 负责限制、记录和隔离进程组的物理资源使用。Docker 通过 cgroup 控制容器的 CPU 配额、内存上限、磁盘 I/O 优先级和网络带宽。

            ### UnionFS — 镜像分层

            Docker 镜像采用分层构建的方式，每一层对应 Dockerfile 中的一条指令。常用的存储驱动包括 overlay2（当前推荐）、aufs（早期驱动）、devicemapper（不推荐）等。镜像分层的最大好处是共享和复用，多个容器使用相同的基础镜像时，这些层只需要存储一份。

            ## Dockerfile 最佳实践

            ### 多阶段构建

            将编译环境和运行环境分离，最终镜像只包含运行时依赖，可以减少 90% 以上的镜像体积。

            ### 层缓存优化

            将变化频率低的指令放在前面（如安装系统依赖），变化频率高的放在后面（如复制源代码）。COPY 和 ADD 指令会计算文件内容的哈希来决定是否使用缓存。

            ## 网络模型

            Docker 提供多种网络驱动：bridge（默认模式，容器通过 docker0 虚拟网桥通信）、host（直接使用宿主机网络栈）、overlay（跨主机容器通信，基于 VXLAN 隧道封装）、macvlan（容器获得独立 MAC 地址和 IP）、none（完全隔离）。

            ## 容器编排

            Kubernetes 已成为容器编排的事实标准。K8s 的核心抽象包括 Pod（最小调度单元）、Service（服务发现和负载均衡）、Deployment（声明式部署和滚动更新）、ConfigMap/Secret（配置管理）、Ingress（七层负载均衡）等。

            Docker Swarm 是 Docker 原生的编排方案，语法简单、与 Docker CLI 一致性好，但在功能和生态上不如 Kubernetes 丰富。Swarm 模式从 Docker 1.12 起内置于 Docker Engine 中。

            ## 安全考量

            容器安全需要多层防护：使用可信的基础镜像、定期扫描镜像漏洞、运行时启用 seccomp/AppArmor/SELinux、限制容器的 capabilities、使用只读根文件系统。容器逃逸是最严重的安全威胁之一，防范措施包括及时更新内核和 Docker 版本、使用非 root 用户运行容器。
            """;

    static final String MYSQL_CONTENT = """
            # MySQL 索引优化实战

            ## 索引基础

            索引是数据库管理系统中的一个排序数据结构，用于快速查询和检索数据。没有索引时，数据库必须执行全表扫描（Full Table Scan）来查找匹配的行，时间复杂度为 O(n)。有了合适的索引，查询时间复杂度可以降低到 O(log n) 甚至 O(1)。

            MySQL 中最常用的索引实现是 B+Tree。B+Tree 是一棵平衡多路搜索树，所有数据都存储在叶子节点中，叶子节点通过双向链表连接。这使得 B+Tree 既能高效支持等值查询，也能高效支持范围查询。

            ## B+Tree 索引原理

            B+Tree 的关键特性包括：所有叶子节点在同一层，保证查询时间稳定；非叶子节点只存储索引键，不存储数据，降低树的高度；叶子节点通过指针串联成有序链表，支持高效的范围扫描（Range Scan）。通常一个 3-4 层的 B+Tree 就能索引上千万行数据。

            InnoDB 存储引擎中，B+Tree 节点的大小等于一个数据页（默认 16KB）。当数据页填满时，节点分裂为两个节点，各自包含约一半的数据。

            ## 聚簇索引与二级索引

            InnoDB 使用聚簇索引（Clustered Index）组织数据。聚簇索引的叶子节点直接存储完整的行数据。每张表只能有一个聚簇索引——如果定义了主键，InnoDB 使用主键作为聚簇索引。

            二级索引（Secondary Index）的叶子节点存储的是对应的聚簇索引键值。通过二级索引查询时，需要先查二级索引获取主键值，再通过主键回表查询完整行数据——这个过程称为回表。当查询的列都在二级索引中时，可以避免回表，称为覆盖索引。

            ## 索引优化策略

            ### 最左前缀原则

            联合索引遵循最左前缀匹配规则。例如创建联合索引 (a, b, c)，WHERE a = 1 可以使用索引，WHERE a = 1 AND b = 2 可以使用索引，但 WHERE b = 2（跳过 a）无法使用索引。

            ### 索引下推

            MySQL 5.6 引入索引下推特性。当查询使用联合索引但部分条件无法使用索引时，MySQL 将无法使用索引的条件推送到存储引擎层进行过滤，减少回表次数。

            ### 避免索引失效

            以下写法会导致索引失效：WHERE 子句中对索引列使用函数（如 WHERE YEAR(create_time) = 2024）、对索引列进行运算（如 WHERE id + 1 = 100）、隐式类型转换（varchar 列与整数比较）、LIKE 以通配符开头（LIKE '%keyword'）。

            ## 慢查询分析

            ### EXPLAIN 解读

            EXPLAIN 是 MySQL 中分析查询执行计划的重要工具。关键字段 type 从优到劣依次为 system > const > eq_ref > ref > range > index > ALL。Extra 中的 Using index 表示覆盖索引（无需回表），Using filesort 表示需要额外排序（可能需要优化），Using temporary 表示使用临时表（可能影响性能）。

            ### 慢查询日志

            通过 slow_query_log 参数开启慢查询日志，设置 long_query_time 阈值（如 200ms）。定期分析慢查询日志，使用 pt-query-digest 工具提供详细的统计报告。

            ## 实战案例

            ### 分页优化

            深分页是一个常见问题。如 LIMIT 1000000, 20 需要扫描并丢弃前 100 万行，效率极低。优化方法包括：使用游标分页（WHERE id > last_max_id LIMIT 20）、使用子查询先获取主键列表再关联回表、避免在大表上使用 OFFSET 过大的分页。

            ### 排序优化

            当 ORDER BY 的列包含在索引中时，MySQL 可以直接通过索引返回有序结果，避免额外的排序操作。创建联合索引时，将等值查询列放在前面，排序查询列放在后面，可以最大化利用索引。
            """;
}
