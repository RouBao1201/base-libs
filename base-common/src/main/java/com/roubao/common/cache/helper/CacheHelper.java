package com.roubao.common.cache.helper;

import java.util.Set;

/**
 * @author SongYanBin
 * @since 2025/9/20
 **/
public interface CacheHelper<K, V> {

    /**
     * 存入缓存
     *
     * @param key   键
     * @param value 值
     */
    void put(K key, V value);

    /**
     * 获取缓存值
     *
     * @param key 键
     * @return 缓存值，若不存在返回 null
     */
    V getIfPresent(K key);

    /**
     * 获取缓存值，若不存在返回默认值
     *
     * @param key          键
     * @param defaultValue 默认值
     * @return 缓存值或默认值
     */
    V getOrDefault(K key, V defaultValue);

    /**
     * 获取缓存值，若不存在则使用加载器加载
     *
     * @param key 键
     * @return 缓存值或加载的值
     */
    V get(K key);

    /**
     * 删除指定键的缓存
     *
     * @param key 键
     */
    void remove(K key);

    /**
     * 删除指定多个键的缓存
     *
     * @param keys 键
     */
    void remove(Set<K> keys);

    /**
     * 清除所有缓存
     */
    void clearAll();

    /**
     * 获取当前缓存条目数
     *
     * @return 缓存条目数
     */
    long size();

    /**
     * 清理并优化缓存
     */
    void cleanUp();
}