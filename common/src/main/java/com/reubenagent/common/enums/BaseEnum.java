package com.reubenagent.common.enums;

/**
 * 通用枚举基类接口 —— 为所有 code/msg 枚举提供统一契约。
 *
 * <p>配合 {@link EnumUtils#getFromCode(Class, Integer)} 使用，
 * 各业务模块枚举只需实现此接口即可获得标准的按 code 查找能力，
 * 无需在每个枚举中重复编写 {@code getFromCode} 循环。</p>
 *
 * <h4>使用示例</h4>
 * <pre>{@code
 * public enum DocumentFileTypeEnum implements BaseEnum {
 *     PDF(1, "PDF"),
 *     DOC(2, "DOC");
 *
 *     @Getter
 *     private final Integer code;
 *     private final String msg;
 *
 *     DocumentFileTypeEnum(Integer code, String msg) { ... }
 *
 *     public String getMsg() { return msg; }
 *
 *     public static DocumentFileTypeEnum getFromCode(Integer code) {
 *         return EnumUtils.getFromCode(DocumentFileTypeEnum.class, code);
 *     }
 * }
 * }</pre>
 *
 * @author reuben
 * @since 2026-06-18
 */
public interface BaseEnum {

    /** 枚举编码（持久化到数据库的值）。 */
    Integer getCode();

    /** 枚举的可读描述文本。 */
    String getMsg();
}
