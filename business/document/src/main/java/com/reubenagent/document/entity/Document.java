package com.reubenagent.document.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.reubenagent.common.data.BaseTableData;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("reuben_agent_document")
@EqualsAndHashCode(callSuper = true)
public class Document extends BaseTableData {

    /** 主键 id */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 文档名 */
    private String documentName;

    /** 原始上传时的文件名 */
    private String originalFileName;

    /** 文件类型 1:PDF 2:DOC 3:DOCX 4:TXT 5:MD 6:HTML */
    private Integer fileType;

    /** 如：application/pdf */
    private String mediaType;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 存储类型 1=MinIO */
    private Integer storageType;

    /** 对象存储的 bucket 名称 */
    private String bucketName;

    /** 对象存储的 object 名称 */
    private String objectName;

    /** 对象存储的访问 url */
    private String objectUrl;

    /** 解析状态 1=待解析, 2=解析中, 3=解析成功, 4=解析失败 */
    private Integer parseStatus;

    /** 策略状态 1=待推荐, 2=已推荐, 3=已确认, 4=已失效 */
    private Integer strategyStatus;

    /** 索引状态 1=待构建, 2=构建中, 3=构建成功, 4=构建失败 */
    private Integer indexStatus;

    /** 文档总字符数 */
    private Integer charCount;

    /** 文档总Token数（用于LLM计费估算） */
    private Integer tokenCount;

    /** 结构化程度：0=未知, 1=低, 2=中, 3=高 */
    private Integer structureLevel;

    /** 内容质量：0=未知, 1=低, 2=中, 3=高 */
    private Integer contentQualityLevel;

    /** 解析后纯文本的存储路径 */
    private String parseSuccessTextPath;

    /** 解析失败时的错误信息 */
    private String parseErrorMsg;

    /** 所属知识范围编码，关联 knowledge_scope_node 表 */
    private String knowledgeScopeCode;

    /** 所属知识范围名称（冗余字段，便于查询展示） */
    private String knowledgeScopeName;

    /** 业务分类标识 */
    private String businessCategory;

    /** 文档标签，多个以逗号分隔 */
    private String documentTags;

    /** 当前生效的策略方案ID，关联 document_strategy_plan 表 */
    private Long currentStrategyPlanId;

    /** 最近一次解析任务ID，关联 document_task 表 */
    private Long latestParseTaskId;

    /** 文档结构节点总数 */
    private Integer structureNodeCount;

    /** 最近一次索引任务ID，关联 document_task 表 */
    private Long latestIndexTaskId;


}
