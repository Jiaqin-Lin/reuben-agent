package com.reubenagent.framework.uid.utils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;

/**
 * 日期工具类 —— 为 UID 生成器提供日期解析与格式化。
 *
 * <p>使用方法：</p>
 * <ul>
 *   <li>{@link #parseEpochSeconds(String)} 解析 "yyyy-MM-dd" 为 epoch 秒数</li>
 *   <li>{@link #formatTimestamp(long)} 将 epoch 秒数格式化为 "yyyy-MM-dd HH:mm:ss"</li>
 * </ul>
 *
 * <p>线程安全：使用 {@link DateTimeFormatter}（不可变，天然线程安全），无需每次 new。</p>
 *
 * @author reuben
 * @since 2026-06-14
 */
public final class AbstractDateUtils {

    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai");

    private AbstractDateUtils() {
        // 工具类，禁止实例化
    }

    /**
     * 解析 "yyyy-MM-dd" 格式的日期字符串，返回该日期 00:00:00 对应的 epoch 秒数。
     *
     * @param dateStr "yyyy-MM-dd" 格式的日期字符串
     * @return epoch 秒数（Asia/Shanghai 时区）
     * @throws RuntimeException 解析失败时抛出
     */
    public static long parseEpochSeconds(String dateStr) {
        try {
            LocalDate localDate = LocalDate.parse(dateStr, DAY_FORMATTER);
            ZonedDateTime zdt = localDate.atStartOfDay(ZONE_SHANGHAI);
            return zdt.toEpochSecond();
        } catch (DateTimeParseException e) {
            throw new RuntimeException("Failed to parse date: " + dateStr, e);
        }
    }

    /**
     * 将 epoch 秒数格式化为 "yyyy-MM-dd HH:mm:ss" 字符串（Asia/Shanghai 时区）。
     *
     * @param epochSeconds epoch 秒数
     * @return "yyyy-MM-dd HH:mm:ss" 格式的字符串
     */
    public static String formatTimestamp(long epochSeconds) {
        ZonedDateTime zdt = Instant.ofEpochSecond(epochSeconds).atZone(ZONE_SHANGHAI);
        return zdt.format(DATE_TIME_FORMATTER);
    }

    /**
     * 按日期格式解析字符串，返回 {@link Date} 对象。
     *
     * @param dateStr "yyyy-MM-dd" 格式的日期字符串
     * @return Date 对象
     * @throws RuntimeException 解析失败时抛出
     * @deprecated 请使用 {@link #parseEpochSeconds(String)}；保留此方法仅为兼容旧调用方。
     */
    @Deprecated
    public static Date parseByDayPattern(String dateStr) {
        long epochSeconds = parseEpochSeconds(dateStr);
        return new Date(epochSeconds * 1000L);
    }

    /**
     * 按日期时间格式化为字符串。
     *
     * @param date Date 对象
     * @return "yyyy-MM-dd HH:mm:ss" 格式的字符串
     * @deprecated 请使用 {@link #formatTimestamp(long)}；保留此方法仅为兼容旧调用方。
     */
    @Deprecated
    public static String formatByDateTimePattern(Date date) {
        return formatTimestamp(date.getTime() / 1000L);
    }
}