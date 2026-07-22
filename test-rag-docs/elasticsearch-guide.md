# Elasticsearch 全文检索实践

## ES 基础概念

Elasticsearch 是一个基于 Lucene 的分布式搜索和分析引擎。

### 核心概念

- **Index**：文档集合，类似数据库中的表
- **Document**：JSON 格式的数据记录
- **Mapping**：定义字段类型和索引方式
- **Shard**：索引分片，支持水平扩展
- **Replica**：分片副本，提高可用性和查询吞吐

## 倒排索引原理

倒排索引是全文检索的基础数据结构。它将文档中的每个词映射到包含该词的文档列表：

```
"docker"    → [doc1, doc3, doc7]
"spring"    → [doc2, doc5, doc6]
"mysql"     → [doc4, doc8]
```

查询时直接通过词典定位词项，取文档列表的交集或并集，比逐文档扫描快几个数量级。

## 中文分词

中文文本没有天然的词边界，需要分词器处理。常用的中文分词插件包括：

- **IK Analyzer**：支持细粒度和智能分词两种模式
- **Jieba**：结巴分词 Elasticsearch 插件
- **HanLP**：基于深度学习的 NLP 分词

## 查询类型

ES 支持丰富的查询 DSL：

- **Match Query**：分词后匹配
- **Term Query**：精确值匹配
- **Bool Query**：组合多个条件（must/should/must_not）
- **Range Query**：范围查询
- **Fuzzy Query**：模糊匹配

## 性能优化

- 合理设置分片数（单分片 10-50GB 为宜）
- 禁用不需要的 `_source` 字段减少存储
- 使用 `filter` 而非 `must` 进行无评分过滤
- 批量索引使用 Bulk API
- 设置合理的 Refresh Interval
