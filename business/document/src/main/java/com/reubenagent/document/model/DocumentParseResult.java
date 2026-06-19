package com.reubenagent.document.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentParseResult {

    /** Tika 提取后的纯文本 */
    private String parsedText;

    /** 总字符数 */
    private Integer charCount;

    /** 估算 Token 数（中文按字、英文按词） */
    private Integer tokenCount;

    /** 结构层级深度（最大标题层级数） */
    private Integer structureLevel;

    /** 内容质量：0=未知 1=低 2=中低 3=中 4=中高 5=高 */
    private Integer contentQualityLevel;

    /** 标题总数 */
    private Integer headingCount;

    /** 段落总数 */
    private Integer paragraphCount;

    /** 最长段落字符数 */
    private Integer maxParagraphLength;

    /** 结构节点列表 */
    private List<DocumentIntermediateStructureNode> structureNodes = new ArrayList<>();
}
