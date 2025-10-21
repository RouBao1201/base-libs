package com.roubao.common;

import com.roubao.common.thread.utils.VirtualThreadUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * @Author: SongYanBin
 * @Date: 2025/10/21
 */
@Slf4j
public class VirtualThreadTest {


    public static void main(String[] args) throws InterruptedException {
        Integer calcResult = VirtualThreadUtil.submitAndGet(() -> {
            log.info("start...");
            return 1 / 0;
        });
        log.info("calcResult:{}", calcResult);
    }
}
