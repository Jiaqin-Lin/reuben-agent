# 表格识别测试

## Markdown 表格

以下是一个标准的 Markdown 表格：

| 模块名称 | 状态 | 负责人 | 优先级 |
|---------|------|--------|-------|
| document | 开发中 | 张三 | P0 |
| agent | 待开发 | 李四 | P1 |
| auth | 待开发 | 王五 | P1 |
| chat | 待开发 | 赵六 | P2 |
| memory | 待开发 | 钱七 | P2 |
| rag | 待开发 | 孙八 | P3 |

上述表格行应全部被分类为 TABLE_ROW。

## Tab 分隔的表格

字段名	类型	长度	说明
node_no	INT	11	节点编号，主键
node_type	INT	2	节点类型 1=ROOT 2=CHAPTER 3=STEP 4=LIST_ITEM
title	VARCHAR	512	节点标题
depth	INT	2	树深度
created_at	DATETIME	-	创建时间

## 表格与普通文本混合

以下是一个混合了表格和普通文本的内容：

在数据库设计中，我们定义了以下核心表结构：

| 表名 | 说明 |
|------|-----|
| reuben_agent_document | 文档主表 |

每个表都包含 create_time、update_time 和 is_deleted 字段。
