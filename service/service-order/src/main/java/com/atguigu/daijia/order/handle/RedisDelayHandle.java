package com.atguigu.daijia.order.handle;

import com.alibaba.cloud.commons.lang.StringUtils;
import com.atguigu.daijia.order.service.OrderInfoService;
import jakarta.annotation.PostConstruct;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 监听延迟队列方法
 */
@Component
public class RedisDelayHandle {

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private OrderInfoService orderInfoService;


    @PostConstruct
    public void listener() {
        new Thread(() -> {
            while (true) {
                //获取延迟队列的阻塞队列
                RBlockingQueue<String> blockingQueue =
                        redissonClient.getBlockingQueue("queue_cancel");
                try {
                    String orderId = blockingQueue.take();
                    if (!StringUtils.isEmpty(orderId)) {
                        orderInfoService.orderCancel(Long.parseLong(orderId));
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }

            }
        });
    }
}


