package com.reubenagent.document.dto;

import lombok.Data;

@Data
public class DocumentUploadDto {

    /** 文档名称，上传文件的原始名称或用户指定的别名 */
    private String documentName;

    /** 操作人ID，记录上载文档的用户 */
    private String operatorId;

    /** 知识范围编码，将文档归类到特定的知识领域（如：技术支持、产品手册、运维文档） */
    private String knowledgeScopeCode;

    /** 知识范围名称，知识范围编码对应的可读中文名称 */
    private String knowledgeScopeName;

    /** 业务分类，进一步细分文档所属的业务类型 */
    private String businessCategory;

    /** 文档标签，以逗号或JSON数组形式存储的多个标签，用于辅助检索和分类 */
    private String documentTags;

}
