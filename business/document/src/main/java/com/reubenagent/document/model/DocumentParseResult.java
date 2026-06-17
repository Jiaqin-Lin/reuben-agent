package com.reubenagent.document.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentParseResult {

    /** 解析后的纯文本内容（PDF/Word/Markdown 经过 Tika 提取后的全文，去除了格式标记） */
    private String parsedText;

    /** 字符总数，用于衡量文档规模 */
    private Integer charCount;

    /** Token 估算数量（中文按字数 + 英文按词数），用于计算 embedding 成本和 LLM 上下文窗口判断 */
    private Integer tokenCount;

    /** 文档结构层级深度（最大标题层级，如 H1→H2→H3 则为 3） */
    private Integer structureLevel;

    /** 内容质量等级：0=未知, 1=低, 2=中低, 3=中, 4=中高, 5=高。影响后续切块策略选择 */
    private Integer contentQualityLevel;

    /** 标题总数（H1~H6 各层级标题的累计数量） */
    private Integer headingCount;

    /** 段落总数 */
    private Integer paragraphCount;

    /** 最长段落的字符数 */
    private Integer maxParagraphLength;

    /** 初步识别的结构节点候选列表（解析器从文档中提取的标题、步骤、列表项等结构元素），后续会转换为正式的结构节点 */
    private List<DocumentIntermediateStructureNode> structureNodes = new ArrayList<>();
}
