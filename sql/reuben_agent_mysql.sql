-- =============================================================================
-- reuben-agent MySQL DDL
-- 数据库: reuben_agent (utf8mb4)
-- =============================================================================

CREATE DATABASE IF NOT EXISTS `reuben_agent` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `reuben_agent`;

-- ---------------------------------------------------------------------------
-- 文档主表
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `reuben_agent_document` (
    `id` BIGINT NOT NULL COMMENT '主键',
    `document_name` VARCHAR(512) DEFAULT NULL COMMENT '文档名称',
    `original_file_name` VARCHAR(512) DEFAULT NULL COMMENT '原始文件名',
    `file_type` TINYINT DEFAULT NULL COMMENT '文件类型: 1=PDF 2=DOC 3=DOCX 4=TXT 5=MD 6=HTML',
    `media_type` VARCHAR(128) DEFAULT NULL COMMENT 'MIME类型',
    `file_size` BIGINT DEFAULT NULL COMMENT '文件大小(byte)',
    `storage_type` TINYINT DEFAULT NULL COMMENT '存储类型: 1=MinIO',
    `bucket_name` VARCHAR(128) DEFAULT NULL COMMENT 'Bucket名称',
    `object_name` VARCHAR(512) DEFAULT NULL COMMENT '对象名称',
    `object_url` VARCHAR(1024) DEFAULT NULL COMMENT '文件访问地址',
    `parse_status` TINYINT DEFAULT 1 COMMENT '解析状态: 1=待解析 2=解析中 3=解析成功 4=解析失败',
    `strategy_status` TINYINT DEFAULT 1 COMMENT '策略状态: 1=待推荐 2=已推荐 3=已确认 4=已失效',
    `index_status` TINYINT DEFAULT 1 COMMENT '索引状态: 1=待构建 2=构建中 3=构建成功 4=构建失败',
    `char_count` INT DEFAULT NULL COMMENT '解析后字符数',
    `token_count` INT DEFAULT NULL COMMENT '解析后token估算数',
    `structure_level` TINYINT DEFAULT 0 COMMENT '结构层级: 0=未知 1=低 2=中 3=高',
    `content_quality_level` TINYINT DEFAULT 0 COMMENT '内容质量: 0=未知 1=低 2=中 3=高',
    `parse_success_text_path` VARCHAR(512) DEFAULT NULL COMMENT '解析文本存储路径',
    `parse_error_msg` VARCHAR(1024) DEFAULT NULL COMMENT '解析失败原因',
    `knowledge_scope_code` VARCHAR(128) DEFAULT NULL COMMENT '业务知识域编码',
    `knowledge_scope_name` VARCHAR(256) DEFAULT NULL COMMENT '业务知识域名称',
    `business_category` VARCHAR(256) DEFAULT NULL COMMENT '业务分类',
    `document_tags` VARCHAR(1024) DEFAULT NULL COMMENT '逗号分隔标签快照',
    `current_strategy_plan_id` BIGINT DEFAULT NULL COMMENT '当前策略方案id',
    `latest_parse_task_id` BIGINT DEFAULT NULL COMMENT '最近一次成功解析任务id',
    `structure_node_count` INT DEFAULT NULL COMMENT '结构化解析生成的节点数',
    `latest_index_task_id` BIGINT DEFAULT NULL COMMENT '最近一次索引任务id',
    `create_time` DATETIME DEFAULT NULL COMMENT '创建时间',
    `update_time` DATETIME DEFAULT NULL COMMENT '修改时间',
    `is_deleted` TINYINT DEFAULT 0 COMMENT '0=正常 1=逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_object_name` (`object_name`(128)),
    KEY `idx_parse_status` (`parse_status`),
    KEY `idx_strategy_status` (`strategy_status`),
    KEY `idx_index_status` (`index_status`),
    KEY `idx_knowledge_scope_code` (`knowledge_scope_code`),
    KEY `idx_current_strategy_plan_id` (`current_strategy_plan_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档主表';

-- ---------------------------------------------------------------------------
-- 文档策略方案
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `reuben_agent_document_strategy_plan` (
    `id` BIGINT NOT NULL COMMENT '主键',
    `document_id` BIGINT NOT NULL COMMENT '文档id',
    `plan_version` INT DEFAULT 1 COMMENT '方案版本号',
    `plan_source` TINYINT DEFAULT 1 COMMENT '方案来源: 1=系统推荐 2=用户调整',
    `plan_status` TINYINT DEFAULT 1 COMMENT '方案状态: 1=待确认 2=已确认 3=已执行 4=已废弃',
    `strategy_count` INT DEFAULT NULL COMMENT '策略数量',
    `strategy_snapshot` VARCHAR(256) DEFAULT NULL COMMENT '策略快照, 例: 1,2,3',
    `recommend_reason` VARCHAR(1024) DEFAULT NULL COMMENT '推荐原因',
    `adjust_note` VARCHAR(1024) DEFAULT NULL COMMENT '调整说明',
    `confirm_user_id` BIGINT DEFAULT NULL COMMENT '确认人id',
    `confirm_time` DATETIME DEFAULT NULL COMMENT '确认时间',
    `create_time` DATETIME DEFAULT NULL,
    `edit_time` DATETIME DEFAULT NULL,
    `status` TINYINT DEFAULT 1,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_document_version` (`document_id`, `plan_version`),
    KEY `idx_plan_status` (`plan_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档策略方案';

-- ---------------------------------------------------------------------------
-- 文档策略步骤
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `reuben_agent_document_strategy_step` (
    `id` BIGINT NOT NULL COMMENT '主键',
    `plan_id` BIGINT NOT NULL COMMENT '方案id',
    `document_id` BIGINT NOT NULL COMMENT '文档id',
    `step_no` INT NOT NULL COMMENT '执行顺序',
    `pipeline_type` VARCHAR(16) NOT NULL COMMENT '流水线类型: PARENT=父块 CHILD=子块',
    `strategy_type` TINYINT DEFAULT NULL COMMENT '策略类型: 1=结构 2=递归 3=语义 4=LLM',
    `strategy_role` TINYINT DEFAULT NULL COMMENT '策略角色: 1=主策略 2=优化 3=兜底 4=增强',
    `source_type` TINYINT DEFAULT 1 COMMENT '来源类型: 1=系统 2=用户新增 3=用户保留',
    `execute_status` TINYINT DEFAULT 1 COMMENT '执行状态',
    `recommend_reason` VARCHAR(1024) DEFAULT NULL COMMENT '推荐原因',
    `create_time` DATETIME DEFAULT NULL,
    `edit_time` DATETIME DEFAULT NULL,
    `status` TINYINT DEFAULT 1,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_plan_pipeline_step_no` (`plan_id`, `pipeline_type`, `step_no`),
    KEY `idx_document_id` (`document_id`),
    KEY `idx_strategy_type` (`strategy_type`),
    KEY `idx_pipeline_type` (`pipeline_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档策略步骤';

-- ---------------------------------------------------------------------------
-- 文档任务
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `reuben_agent_document_task` (
    `id` BIGINT NOT NULL COMMENT '主键',
    `document_id` BIGINT NOT NULL COMMENT '文档id',
    `strategy_plan_id` BIGINT DEFAULT NULL COMMENT '执行方案id',
    `task_type` TINYINT DEFAULT NULL COMMENT '任务类型: 1=解析路由 2=构建索引',
    `task_status` TINYINT DEFAULT 1 COMMENT '任务状态: 1=新建 2=进行中 3=成功 4=失败 5=已取消',
    `current_stage` TINYINT DEFAULT NULL COMMENT '当前阶段',
    `trigger_source` TINYINT DEFAULT 1 COMMENT '触发来源: 1=系统 2=用户',
    `strategy_snapshot` VARCHAR(256) DEFAULT NULL COMMENT '执行时策略快照',
    `retry_count` INT DEFAULT 0 COMMENT '重试次数',
    `start_time` DATETIME DEFAULT NULL COMMENT '开始时间',
    `finish_time` DATETIME DEFAULT NULL COMMENT '结束时间',
    `cost_millis` BIGINT DEFAULT NULL COMMENT '耗时毫秒',
    `error_code` VARCHAR(64) DEFAULT NULL COMMENT '错误码',
    `error_msg` VARCHAR(1024) DEFAULT NULL COMMENT '错误信息',
    `ext_json` TEXT DEFAULT NULL COMMENT '扩展信息JSON',
    `create_time` DATETIME DEFAULT NULL,
    `update_time` DATETIME DEFAULT NULL,
    `is_deleted` TINYINT DEFAULT 0 COMMENT '0=正常 1=逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_document_task` (`document_id`),
    KEY `idx_task_status` (`task_status`),
    KEY `idx_strategy_plan_id` (`strategy_plan_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档任务';

-- ---------------------------------------------------------------------------
-- 文档任务日志
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `reuben_agent_document_task_log` (
    `id` BIGINT NOT NULL COMMENT '主键',
    `task_id` BIGINT NOT NULL COMMENT '任务id',
    `document_id` BIGINT NOT NULL COMMENT '文档id',
    `stage_type` TINYINT DEFAULT NULL COMMENT '阶段类型',
    `event_type` TINYINT DEFAULT NULL COMMENT '事件类型: 1=开始 2=完成 3=失败 4=推荐 5=调整 6=确认',
    `log_level` TINYINT DEFAULT 1 COMMENT '日志级别: 1=INFO 2=WARN 3=ERROR',
    `operator_type` TINYINT DEFAULT 1 COMMENT '操作人类型: 1=系统 2=用户 3=管理员',
    `operator_id` BIGINT DEFAULT NULL COMMENT '操作人id',
    `content` VARCHAR(2048) DEFAULT NULL COMMENT '日志内容',
    `detail_json` TEXT DEFAULT NULL COMMENT '日志明细JSON',
    `create_time` DATETIME DEFAULT NULL,
    `update_time` DATETIME DEFAULT NULL,
    `is_deleted` TINYINT DEFAULT 0 COMMENT '0=正常 1=逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_task_id` (`task_id`),
    KEY `idx_document_id` (`document_id`),
    KEY `idx_stage_type` (`stage_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档任务日志';

-- ---------------------------------------------------------------------------
-- 文档结构节点
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `reuben_agent_document_structure_node` (
    `id` BIGINT NOT NULL COMMENT '主键',
    `document_id` BIGINT NOT NULL COMMENT '文档id',
    `parse_task_id` BIGINT NOT NULL COMMENT '解析任务id',
    `node_no` INT NOT NULL COMMENT '节点序号',
    `node_type` TINYINT DEFAULT NULL COMMENT '节点类型: 1=根 2=章节 3=步骤 4=列表项',
    `parent_node_id` BIGINT DEFAULT NULL COMMENT '父节点id',
    `prev_sibling_node_id` BIGINT DEFAULT NULL COMMENT '上一个同级节点id',
    `next_sibling_node_id` BIGINT DEFAULT NULL COMMENT '下一个同级节点id',
    `depth` INT DEFAULT 0 COMMENT '节点深度',
    `node_code` VARCHAR(128) DEFAULT NULL COMMENT '节点编码',
    `title` VARCHAR(1024) DEFAULT NULL COMMENT '节点标题',
    `anchor_text` VARCHAR(512) DEFAULT NULL COMMENT '锚文本',
    `canonical_path` VARCHAR(1024) DEFAULT NULL COMMENT '节点稳定路径',
    `section_path` VARCHAR(1024) DEFAULT NULL COMMENT '章节路径文本',
    `content_text` MEDIUMTEXT DEFAULT NULL COMMENT '节点正文',
    `item_index` INT DEFAULT NULL COMMENT '列表项/步骤项序号',
    `create_time` DATETIME DEFAULT NULL COMMENT '创建时间',
    `update_time` DATETIME DEFAULT NULL COMMENT '修改时间',
    `is_deleted` TINYINT DEFAULT 0 COMMENT '0=正常 1=逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_parse_task_node_no` (`parse_task_id`, `node_no`),
    KEY `idx_document_id` (`document_id`),
    KEY `idx_parse_task_id` (`parse_task_id`),
    KEY `idx_parent_node_id` (`parent_node_id`),
    KEY `idx_node_type` (`node_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档结构节点';

-- ---------------------------------------------------------------------------
-- 文档父块
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `reuben_agent_document_parent_block` (
    `id` BIGINT NOT NULL COMMENT '主键',
    `document_id` BIGINT NOT NULL COMMENT '文档id',
    `task_id` BIGINT NOT NULL COMMENT '索引任务id',
    `plan_id` BIGINT DEFAULT NULL COMMENT '策略方案id',
    `parent_no` INT NOT NULL COMMENT '父块序号',
    `source_type` TINYINT DEFAULT 1 COMMENT '来源类型: 1=原文 2=后处理',
    `section_path` VARCHAR(1024) DEFAULT NULL COMMENT '章节路径',
    `structure_node_id` BIGINT DEFAULT NULL COMMENT '关联结构节点id',
    `structure_node_type` TINYINT DEFAULT NULL COMMENT '关联结构节点类型',
    `canonical_path` VARCHAR(1024) DEFAULT NULL COMMENT '结构节点稳定路径',
    `item_index` INT DEFAULT NULL COMMENT '列表项序号',
    `parent_text` MEDIUMTEXT DEFAULT NULL COMMENT '父块完整内容',
    `char_count` INT DEFAULT NULL COMMENT '字符数',
    `token_count` INT DEFAULT NULL COMMENT 'token数',
    `child_count` INT DEFAULT NULL COMMENT 'child数量',
    `start_chunk_no` INT DEFAULT NULL COMMENT '第一个child序号',
    `end_chunk_no` INT DEFAULT NULL COMMENT '最后一个child序号',
    `create_time` DATETIME DEFAULT NULL,
    `edit_time` DATETIME DEFAULT NULL,
    `status` TINYINT DEFAULT 1,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_parent_no` (`task_id`, `parent_no`),
    KEY `idx_document_id` (`document_id`),
    KEY `idx_task_id` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档父块';

-- ---------------------------------------------------------------------------
-- 文档切块
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `reuben_agent_document_chunk` (
    `id` BIGINT NOT NULL COMMENT '主键',
    `document_id` BIGINT NOT NULL COMMENT '文档id',
    `task_id` BIGINT NOT NULL COMMENT '索引任务id',
    `plan_id` BIGINT DEFAULT NULL COMMENT '策略方案id',
    `parent_block_id` BIGINT DEFAULT NULL COMMENT '所属父块id',
    `chunk_no` INT NOT NULL COMMENT '块序号',
    `source_type` TINYINT DEFAULT 1 COMMENT '来源类型: 1=原文切块 2=后处理补全',
    `section_path` VARCHAR(1024) DEFAULT NULL COMMENT '章节路径',
    `structure_node_id` BIGINT DEFAULT NULL COMMENT '关联结构节点id',
    `structure_node_type` TINYINT DEFAULT NULL COMMENT '关联结构节点类型',
    `canonical_path` VARCHAR(1024) DEFAULT NULL COMMENT '结构节点稳定路径',
    `item_index` INT DEFAULT NULL COMMENT '列表项序号',
    `chunk_text` MEDIUMTEXT DEFAULT NULL COMMENT '切块内容',
    `char_count` INT DEFAULT NULL COMMENT '字符数',
    `token_count` INT DEFAULT NULL COMMENT 'token数',
    `vector_status` TINYINT DEFAULT 1 COMMENT '向量状态: 1=待向量化 2=向量化中 3=成功 4=失败',
    `vector_store_type` TINYINT DEFAULT NULL COMMENT '向量库类型: 1=Milvus 2=PGVector 3=Elasticsearch',
    `vector_id` VARCHAR(128) DEFAULT NULL COMMENT '向量库主键',
    `create_time` DATETIME DEFAULT NULL,
    `edit_time` DATETIME DEFAULT NULL,
    `status` TINYINT DEFAULT 1,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_chunk_no` (`task_id`, `chunk_no`),
    KEY `idx_document_id` (`document_id`),
    KEY `idx_parent_block_id` (`parent_block_id`),
    KEY `idx_vector_status` (`vector_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档切块';

-- ---------------------------------------------------------------------------
-- 文档画像
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `reuben_agent_document_profile` (
    `id` BIGINT NOT NULL COMMENT '主键',
    `document_id` BIGINT NOT NULL COMMENT '文档id',
    `profile_version` INT DEFAULT 1 COMMENT '画像版本',
    `document_summary` TEXT DEFAULT NULL COMMENT '文档摘要',
    `document_type` VARCHAR(64) DEFAULT NULL COMMENT '文档类型',
    `core_topics` TEXT DEFAULT NULL COMMENT '核心主题 JSON',
    `example_questions` TEXT DEFAULT NULL COMMENT '典型问题 JSON',
    `graph_friendly` TINYINT DEFAULT 0 COMMENT '是否适合图结构问答',
    `supports_graph_outline` TINYINT DEFAULT 0 COMMENT '支持章节列表',
    `supports_item_lookup` TINYINT DEFAULT 0 COMMENT '支持步骤查询',
    `supports_graph_assist` TINYINT DEFAULT 0 COMMENT '支持图辅助检索',
    `profile_source` VARCHAR(32) DEFAULT 'auto' COMMENT '画像来源: auto/manual/mixed',
    `profile_status` TINYINT DEFAULT 1 COMMENT '画像状态: 1=待生成 2=成功 3=失败 4=人工确认',
    `error_msg` VARCHAR(1024) DEFAULT NULL COMMENT '失败原因',
    `create_time` DATETIME DEFAULT NULL COMMENT '创建时间',
    `update_time` DATETIME DEFAULT NULL COMMENT '修改时间',
    `is_deleted` TINYINT DEFAULT 0 COMMENT '0=正常 1=逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_document_id` (`document_id`),
    KEY `idx_profile_status` (`profile_status`),
    KEY `idx_document_type` (`document_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档画像';

-- ---------------------------------------------------------------------------
-- 知识范围节点
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `reuben_agent_knowledge_scope_node` (
    `id` BIGINT NOT NULL COMMENT '主键',
    `scope_code` VARCHAR(128) NOT NULL COMMENT '知识范围编码',
    `scope_name` VARCHAR(256) DEFAULT NULL COMMENT '知识范围名称',
    `parent_scope_code` VARCHAR(128) DEFAULT NULL COMMENT '父级知识范围编码',
    `description` VARCHAR(1024) DEFAULT NULL COMMENT '范围描述',
    `aliases` VARCHAR(1024) DEFAULT NULL COMMENT '别名, 逗号分隔',
    `examples` TEXT DEFAULT NULL COMMENT '典型问题 JSON',
    `sort_order` INT DEFAULT 0 COMMENT '排序值',
    `create_time` DATETIME DEFAULT NULL,
    `edit_time` DATETIME DEFAULT NULL,
    `status` TINYINT DEFAULT 1,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_scope_code` (`scope_code`),
    KEY `idx_parent_scope_code` (`parent_scope_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识范围节点';

-- ---------------------------------------------------------------------------
-- 知识主题节点
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `reuben_agent_knowledge_topic_node` (
    `id` BIGINT NOT NULL COMMENT '主键',
    `topic_code` VARCHAR(128) NOT NULL COMMENT '主题编码',
    `topic_name` VARCHAR(256) DEFAULT NULL COMMENT '主题名称',
    `scope_code` VARCHAR(128) DEFAULT NULL COMMENT '所属知识范围编码',
    `description` VARCHAR(1024) DEFAULT NULL COMMENT '主题描述',
    `aliases` VARCHAR(1024) DEFAULT NULL COMMENT '别名, 逗号分隔',
    `examples` TEXT DEFAULT NULL COMMENT '典型问题 JSON',
    `answer_shape` VARCHAR(64) DEFAULT NULL COMMENT '建议回答形态',
    `execution_preference` VARCHAR(64) DEFAULT NULL COMMENT '执行偏好',
    `sort_order` INT DEFAULT 0 COMMENT '排序值',
    `create_time` DATETIME DEFAULT NULL,
    `edit_time` DATETIME DEFAULT NULL,
    `status` TINYINT DEFAULT 1,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_topic_code` (`topic_code`),
    KEY `idx_scope_code` (`scope_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识主题节点';

-- ---------------------------------------------------------------------------
-- 主题文档关联
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `reuben_agent_topic_document_relation` (
    `id` BIGINT NOT NULL COMMENT '主键',
    `topic_code` VARCHAR(128) NOT NULL COMMENT '主题编码',
    `document_id` BIGINT NOT NULL COMMENT '文档id',
    `relation_score` DECIMAL(8,4) DEFAULT NULL COMMENT '关联分数',
    `relation_source` VARCHAR(32) DEFAULT 'auto' COMMENT '关联来源: auto/manual/mixed',
    `reason` VARCHAR(1024) DEFAULT NULL COMMENT '关联原因',
    `create_time` DATETIME DEFAULT NULL,
    `edit_time` DATETIME DEFAULT NULL,
    `status` TINYINT DEFAULT 1,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_topic_document` (`topic_code`, `document_id`),
    KEY `idx_document_id` (`document_id`),
    KEY `idx_topic_code` (`topic_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='主题文档关联';

-- ---------------------------------------------------------------------------
-- 知识路由追踪
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `reuben_agent_knowledge_route_trace` (
    `id` BIGINT NOT NULL COMMENT '主键',
    `conversation_id` VARCHAR(128) DEFAULT NULL COMMENT '会话id',
    `exchange_id` BIGINT DEFAULT NULL COMMENT '轮次id',
    `question` TEXT DEFAULT NULL COMMENT '原始问题',
    `rewrite_question` TEXT DEFAULT NULL COMMENT '改写问题',
    `mode` VARCHAR(32) DEFAULT NULL COMMENT '运行模式: shadow/auto',
    `top_scopes_json` TEXT DEFAULT NULL COMMENT '候选知识范围 JSON',
    `top_topics_json` TEXT DEFAULT NULL COMMENT '候选主题 JSON',
    `top_documents_json` TEXT DEFAULT NULL COMMENT '候选文档 JSON',
    `selected_document_id` BIGINT DEFAULT NULL COMMENT '实际使用文档id',
    `hit_selected_document` TINYINT DEFAULT NULL COMMENT '候选是否命中',
    `confidence` DECIMAL(8,4) DEFAULT NULL COMMENT '整体置信度',
    `route_status` TINYINT DEFAULT NULL COMMENT '路由状态: 1=成功 2=低置信 3=失败',
    `error_msg` VARCHAR(1024) DEFAULT NULL COMMENT '失败原因',
    `create_time` DATETIME DEFAULT NULL,
    `edit_time` DATETIME DEFAULT NULL,
    `status` TINYINT DEFAULT 1,
    PRIMARY KEY (`id`),
    KEY `idx_conversation_exchange` (`conversation_id`, `exchange_id`),
    KEY `idx_selected_document_id` (`selected_document_id`),
    KEY `idx_route_status` (`route_status`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识路由追踪';

-- =============================================================================
-- UID Generator WORKER_NODE (if using baidu/uid-generator)
-- =============================================================================
CREATE TABLE IF NOT EXISTS `WORKER_NODE` (
    `ID` BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增ID',
    `HOST_NAME` VARCHAR(64) NOT NULL COMMENT '主机名',
    `PORT` VARCHAR(64) NOT NULL COMMENT '端口',
    `TYPE` INT NOT NULL COMMENT '节点类型',
    `LAUNCH_DATE` DATE NOT NULL COMMENT '启动日期',
    `MODIFIED` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `CREATED` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='DB WorkerID Assigner for UID Generator';
