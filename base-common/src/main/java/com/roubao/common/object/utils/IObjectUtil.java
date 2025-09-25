package com.roubao.common.object.utils;

import cn.hutool.core.util.ObjUtil;

import java.util.function.Function;

/**
 * @author SongYanBin
 * @since 2025/8/21
 */
public class IObjectUtil {

    public static <T, R> R extractFieldValue(T object, Function<T, R> fieldExtractor) {
        return extractFieldValue(object, fieldExtractor, null);
    }

    public static <T, R> R extractFieldValue(T object, Function<T, R> fieldExtractor, R defaultValue) {
        if (ObjUtil.isNull(object)) {
            return defaultValue;
        }
        R apply = fieldExtractor.apply(object);
        if (ObjUtil.isNull(apply)) {
            return defaultValue;
        }
        return apply;
    }
}
