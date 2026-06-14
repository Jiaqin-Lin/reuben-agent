package com.reubenagent.framework.uid.utils;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 日期工具类 —— 为 UID 生成器提供日期解析与格式化。
 *
 * <p>用于 DefaultUidGenerator 解析 epoch 起始日期和格式化 parseUid 输出。</p>
 *
 * @author reuben
 * @since 2026-06-14
 */
public abstract class AbstractDateUtils {

    private static final String DAY_PATTERN = "yyyy-MM-dd";
    private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /**
     * 按日期格式解析字符串。
     *
     * @param dateStr "yyyy-MM-dd" 格式的日期字符串
     * @return Date 对象
     * @throws RuntimeException 解析失败时抛出
     */
    public static Date parseByDayPattern(String dateStr) {
        try {
            return new SimpleDateFormat(DAY_PATTERN).parse(dateStr);
        } catch (ParseException e) {
            throw new RuntimeException("Failed to parse date: " + dateStr, e);
        }
    }

    /**
     * 按日期时间格式化为字符串。
     *
     * @param date Date 对象
     * @return "yyyy-MM-dd HH:mm:ss" 格式的字符串
     */
    public static String formatByDateTimePattern(Date date) {
        return new SimpleDateFormat(DATE_TIME_PATTERN).format(date);
    }
}
