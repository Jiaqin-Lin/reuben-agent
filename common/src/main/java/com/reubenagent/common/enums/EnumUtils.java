package com.reubenagent.common.enums;

import java.util.Objects;

/**
 * 枚举工具类 —— 为 {@link BaseEnum} 实现类提供通用的 {@code getFromCode} 查找。
 *
 * <p>消除各模块枚举中重复的 {@code for (values()) if (code.equals(item.code)) return item; } 样板代码。</p>
 *
 * @author reuben
 * @since 2026-06-18
 */
public final class EnumUtils {

    private EnumUtils() { }

    /**
     * 按 code 查找枚举常量。
     *
     * @param <E>       实现 {@link BaseEnum} 的枚举类型
     * @param enumClass 枚举类
     * @param code      要匹配的编码值（可为 null）
     * @return 匹配的枚举常量，code 为 null 或无匹配时返回 null
     */
    public static <E extends Enum<E> & BaseEnum> E getFromCode(Class<E> enumClass, Integer code) {
        if (code == null || enumClass == null) {
            return null;
        }
        for (E constant : enumClass.getEnumConstants()) {
            if (Objects.equals(constant.getCode(), code)) {
                return constant;
            }
        }
        return null;
    }
}
