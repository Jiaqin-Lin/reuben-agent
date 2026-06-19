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

    /** 主键 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 文档名 */
    private String documentName;

    /** 原始文件名 */
    private String originalFileName;

    /** 文件类型：1=PDF 2=DOC 3=DOCX 4=TXT 5=MD 6=HTML */
    private Integer fileType;

    /** MIME 类型（如 application/pdf） */
    private String mediaType;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 存储类型：1=MinIO */
    private Integer storageType;

    /** MinIO bucket */
    private String bucketName;

    /** MinIO object */
    private String objectName;

    /** 访问 URL */
    private String objectUrl;

    /** 解析状态：1=待解析 2=解析中 3=成功 4=失败 */
    private Integer parseStatus;

    /** 策略状态：1=待推荐 2=已推荐 3=已确认 4=已失效 */
    private Integer strategyStatus;

    /** 索引状态：1=待构建 2=构建中 3=成功 4=失败 */
    private Integer indexStatus;

    /** 总字符数 */
    private Integer charCount;

    /** 估算 Token 数 */
    private Integer tokenCount;

    /** 结构化程度：0=未知 1=低 2=中 3=高 */
    private Integer structureLevel;

    /** 内容质量：0=未知 1=低 2=中低 3=中 4=中高 5=高 */
    private Integer contentQualityLevel;

    /** 纯文本存储路径 */
    private String parseSuccessTextPath;

    /** 失败错误信息 */
    private String parseErrorMsg;

    /** 知识范围编码 */
    private String knowledgeScopeCode;

    /** 知识范围名称（冗余） */
    private String knowledgeScopeName;

    /** 业务分类 */
    private String businessCategory;

    /** 标签（逗号分隔） */
    private String documentTags;

    /** 当前策略方案ID */
    private Long currentStrategyPlanId;

    /** 最近解析任务ID */
    private Long latestParseTaskId;

    /** 结构节点总数 */
    private Integer structureNodeCount;

    /** 最近索引任务ID */
    private Long latestIndexTaskId;


}
