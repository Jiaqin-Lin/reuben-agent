package com.reubenagent.document.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentUploadVo {

    /** 文档ID */
    private Long documentId;

    /** 任务ID */
    private Long taskId;

    /** 文档名称 */
    private String documentName;

    /** 解析状态 1=待解析, 2=解析中, 3=解析成功, 4=解析失败 */
    private Integer parseStatus;

    /** 策略状态 1=待推荐, 2=已推荐, 3=已确认, 4=已失效 */
    private Integer strategyStatus;

    /** 索引状态 1=待构建, 2=构建中, 3=构建成功, 4=构建失败 */
    private Integer indexStatus;

}