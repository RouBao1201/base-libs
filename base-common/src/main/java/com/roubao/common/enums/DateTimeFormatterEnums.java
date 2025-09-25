package com.roubao.common.enums;

import lombok.Getter;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * @author SongYanBin
 * @since 2025/9/26
 */
@Getter
public enum DateTimeFormatterEnums {

    // 标准日期时间格式：yyyy-MM-dd HH:mm:ss
    NORM_DATETIME("yyyy-MM-dd HH:mm:ss"),
    // 日期格式：yyyy-MM-dd
    NORM_DATE("yyyy-MM-dd"),
    // 时间格式：HH:mm:ss
    NORM_TIME("HH:mm:ss"),
    // 年月格式：yyyy-MM
    NORM_YEAR_MONTH("yyyy-MM"),
    // 日期时间带毫秒：yyyy-MM-dd HH:mm:ss.SSS
    NORM_DATETIME_MS("yyyy-MM-dd HH:mm:ss.SSS"),
    // ISO 8601 格式：yyyy-MM-dd'T'HH:mm:ss
    ISO_DATETIME("yyyy-MM-dd'T'HH:mm:ss"),
    // 紧凑格式：yyyyMMddHHmmss
    PURE_DATETIME("yyyyMMddHHmmss"),
    // 日期紧凑格式：yyyyMMdd
    PURE_DATE("yyyyMMdd");

    private final DateTimeFormatter formatter;
    private final String pattern;

    DateTimeFormatterEnums(String pattern) {
        this.pattern = pattern;
        this.formatter = DateTimeFormatter.ofPattern(pattern);
    }

    /**
     * 获取带自定义时区的 DateTimeFormatter
     *
     * @param zoneId 时区，如 "Asia/Shanghai"
     */
    public DateTimeFormatter withZone(String zoneId) {
        return DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.of(zoneId));
    }
}
