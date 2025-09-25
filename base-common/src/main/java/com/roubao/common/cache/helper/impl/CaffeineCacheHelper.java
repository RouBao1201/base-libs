
package com.roubao.common.cache.helper.impl;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.roubao.common.cache.helper.CacheHelper;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * @author SongYanBin
 * @since 2025/9/20
 **/
public class CaffeineCacheHelper<K, V> implements CacheHelper<K, V> {

    private final LoadingCache<K, V> cache;

    // 私有构造函数，使用 Caffeine 配置
    protected CaffeineCacheHelper(Caffeine<Object, Object> caffeine, Function<K, V> loader) {
        this.cache = caffeine.build(loader != null ? loader::apply : key -> null);
    }

    /**
     * 创建带加载器的缓存提供者
     *
     * @param maxSize                 最大条目数
     * @param expireAfterWriteSeconds 过期时间（秒）
     * @param loader                  缓存未命中时的加载器
     */
    public static <K, V> CacheHelper<K, V> createWithLoader(long maxSize, long expireAfterWriteSeconds, Function<K, V> loader) {
        return new CaffeineCacheHelper<>(
                Caffeine.newBuilder()
                        .maximumSize(maxSize)
                        .expireAfterWrite(expireAfterWriteSeconds, TimeUnit.SECONDS),
                loader
        );
    }

    public static <K, V> CacheHelper<K, V> createWithLoader(long maxSize,
                                                            long expireAfterWriteSeconds,
                                                            long refreshAfterWriteSeconds,
                                                            Function<K, V> loader) {
        return new CaffeineCacheHelper<>(
                Caffeine.newBuilder()
                        .maximumSize(maxSize)
                        .refreshAfterWrite(refreshAfterWriteSeconds, TimeUnit.SECONDS)
                        .expireAfterWrite(expireAfterWriteSeconds, TimeUnit.SECONDS),
                loader
        );
    }

    public static <K, V> CacheHelper<K, V> createWithLoader(long maxSize,
                                                            long expireAfterWriteSeconds,
                                                            long expireAfterAccessSeconds,
                                                            long refreshAfterWriteSeconds,
                                                            Function<K, V> loader) {
        return new CaffeineCacheHelper<>(
                Caffeine.newBuilder()
                        .maximumSize(maxSize)
                        .expireAfterAccess(expireAfterAccessSeconds, TimeUnit.SECONDS)
                        .refreshAfterWrite(refreshAfterWriteSeconds, TimeUnit.SECONDS)
                        .expireAfterWrite(expireAfterWriteSeconds, TimeUnit.SECONDS),
                loader
        );
    }

    @Override
    public void put(K key, V value) {
        cache.put(key, value);
    }

    @Override
    public V getIfPresent(K key) {
        return cache.getIfPresent(key);
    }

    @Override
    public V getOrDefault(K key, V defaultValue) {
        V value = cache.getIfPresent(key);
        return value != null ? value : defaultValue;
    }

    @Override
    public V get(K key) {
        return cache.get(key);
    }

    @Override
    public void remove(K key) {
        cache.invalidate(key);
    }

    @Override
    public void remove(Set<K> keys) {
        cache.invalidateAll(keys);
    }

    @Override
    public void clearAll() {
        cache.invalidateAll();
    }

    @Override
    public long size() {
        return cache.estimatedSize();
    }

    @Override
    public void cleanUp() {
        cache.cleanUp();
    }
}