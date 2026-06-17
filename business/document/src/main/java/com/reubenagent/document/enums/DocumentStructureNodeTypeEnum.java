package com.reubenagent.document.enums;

import lombok.Getter;

/**
 * 文档结构节点类型 —— 从信号（{@link DocumentStructureNodeSignalEnum}）归并后的最终节点分类。
 *
 * <p>用于 {@code DocumentIntermediateStructureNode.nodeType} 持久化。</p>
 *
 * @author reuben
 * @since 2026-06-14
 */
public enum DocumentStructureNodeTypeEnum {
    /** 文档根节点，一个文档仅一个 */
    ROOT(1, "文档根节点"),
    /** 章节（标题）节点 */
    CHAPTER(2, "章节节点"),
    /** 步骤节点 */
    STEP(3, "步骤节点"),
    /** 列表项节点 */
    LIST_ITEM(4, "列表项节点");

    @Getter
    private final Integer code;
    private final String msg;

    DocumentStructureNodeTypeEnum(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return msg == null ? "" : msg;
    }

    /**
     * 通过 code 查找枚举常量。
     *
     * @param code 节点类型编码，可为 null
     * @return 匹配的枚举，未找到或 code 为 null 时返回 null
     */
    public static DocumentStructureNodeTypeEnum getFromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (DocumentStructureNodeTypeEnum item : values()) {
            if (item.code.intValue() == code.intValue()) {
                return item;
            }
        }
        return null;
    }
}