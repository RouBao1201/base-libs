package com.roubao.spring.common.utils;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * Spring 上下文工具类，提供静态方法获取 ApplicationContext 和 Bean
 *
 * @author SongYanBin
 * @date 2025-09-25
 */
@Component
public class SpringContextHolder implements ApplicationContextAware {
    private static volatile ApplicationContext applicationContext;

    /**
     * 注入 Spring 上下文
     */
    @Override
    public void setApplicationContext(ApplicationContext context) {
        SpringContextHolder.applicationContext = context;
    }

    /**
     * 获取 ApplicationContext
     *
     * @return ApplicationContext
     * @throws IllegalStateException 如果上下文未初始化
     */
    public static ApplicationContext getApplicationContext() {
        checkApplicationContext();
        return applicationContext;
    }

    /**
     * 通过 Bean 类型获取实例
     *
     * @param beanClass Bean 类型
     * @param <T>       泛型
     * @return Bean 实例
     */
    public static <T> T getBean(Class<T> beanClass) {
        checkApplicationContext();
        return applicationContext.getBean(beanClass);
    }

    /**
     * 通过 Bean 名称和类型获取实例
     *
     * @param name      Bean 名称
     * @param beanClass Bean 类型
     * @param <T>       泛型
     * @return Bean 实例
     */
    public static <T> T getBean(String name, Class<T> beanClass) {
        checkApplicationContext();
        return applicationContext.getBean(name, beanClass);
    }

    /**
     * 检查 ApplicationContext 是否初始化
     */
    private static void checkApplicationContext() {
        if (applicationContext == null) {
            throw new IllegalStateException("ApplicationContext not initialized. Ensure Spring context is loaded.");
        }
    }

    /**
     * 清理上下文（容器销毁时调用）
     */
    public static void clear() {
        applicationContext = null;
    }
}