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

/**
 * 文档画像实体 —— 映射 {@code reuben_agent_document_profile} 表。
 *
 * <p>文档画像是解析完成后自动生成的元数据快照，描述文档的类型、摘要、核心主题、
 * 示例问题及图结构适配能力。画像数据用于知识路由的语义匹配。</p>
 *
 * @author reuben
 * @since 2026-06-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("reuben_agent_document_profile")
@EqualsAndHashCode(callSuper = true)
public class DocumentProfile extends BaseTableData {

    /** 主键（雪花 ID） */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 所属文档 ID */
    private Long documentId;

    /** 画像版本号，每次重新生成递增 */
    private Integer profileVersion;

    /** 文档摘要 */
    private String documentSummary;

    /** 文档类型：faq / troubleshooting / rule / spec / manual / intro */
    private String documentType;

    /** 核心主题（JSON 数组字符串） */
    private String coreTopics;

    /** 示例问题（JSON 数组字符串） */
    private String exampleQuestions;

    /** 是否适合图结构展示：0=否 1=是 */
    private Integer graphFriendly;

    /** 是否支持章节列表：0=否 1=是 */
    private Integer supportsGraphOutline;

    /** 是否支持条目查找：0=否 1=是 */
    private Integer supportsItemLookup;

    /** 是否支持图辅助检索：0=否 1=是 */
    private Integer supportsGraphAssist;

    /** 画像来源：auto / manual */
    private String profileSource;

    /** 画像状态：1=待生成 2=成功 3=失败 4=人工确认 */
    private Integer profileStatus;

    /** 生成失败时的错误信息 */
    private String errorMsg;
}
