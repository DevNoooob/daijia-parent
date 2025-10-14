package com.atguigu.daijia.order.testLock;

import com.alibaba.cloud.commons.lang.StringUtils;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class TestServiceImpl implements TestService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    //Redisson实现
    @Override
    public void testLock() {
        //1、通过Redisson创建锁对象
        RLock rLock = redissonClient.getLock("lock1");
        try {


            //2、尝试获取锁
            //(1) 阻塞一直等待直到获取到，获取锁之后，默认过期时间30s
            rLock.lock();

            /*//(2) 获取到锁，锁过期时间为10s
            rLock.lock(10, TimeUnit.SECONDS);

            //(3) 第一个参数获取锁等待时间
            //    第二个参数获取到锁后，锁过期时间
            try {
                boolean tryLock = rLock.tryLock(30, 10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }*/

            //3、编写业务代码
            String value = stringRedisTemplate.opsForValue().get("num");
            if (StringUtils.isBlank(value)) {
                return;
            }
            int num = Integer.parseInt(value);
            stringRedisTemplate.opsForValue().set("num", String.valueOf(++num));
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            //4、释放锁
            rLock.unlock();
        }
        /*//1、通过Redisson创建锁对象
        RLock rLock = redissonClient.getLock("lock1");

        //2、尝试获取锁
        //(1) 阻塞一直等待直到获取到，获取锁之后，默认过期时间30s
        rLock.lock();

        //(2) 获取到锁，锁过期时间为10s
        rLock.lock(10, TimeUnit.SECONDS);

        //(3) 第一个参数获取锁等待时间
        //    第二个参数获取到锁后，锁过期时间
        try {
            boolean tryLock = rLock.tryLock(30, 10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        //3、编写业务代码
        String value = stringRedisTemplate.opsForValue().get("num");
        if (StringUtils.isBlank(value)) {
            return;
        }
        int num = Integer.parseInt(value);
        stringRedisTemplate.opsForValue().set("num", String.valueOf(++num));

        //4、释放锁
        rLock.unlock();*/
    }

    //lua脚本
    public void testLock3() {
        String uuid = UUID.randomUUID().toString();
        Boolean ifAbsent = stringRedisTemplate.opsForValue().setIfAbsent("lock", uuid, 10, TimeUnit.SECONDS);

        if (Boolean.TRUE.equals(ifAbsent)) {
            String value = stringRedisTemplate.opsForValue().get("num");
            if (StringUtils.isBlank(value)) {
                return;
            }
            int num = Integer.parseInt(value);
            stringRedisTemplate.opsForValue().set("num", String.valueOf(++num));

            //释放锁，用lua脚本实现
            DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();

            String script = "if redis.call(\"get\",KEYS[1]) == ARGV[1]\n" +
                    "then\n" +
                    "    return redis.call(\"del\",KEYS[1])\n)" +
                    "else\n" +
                    "    return 0\n" +
                    "end";
            redisScript.setScriptText(script);
            redisScript.setResultType(Long.class);
            stringRedisTemplate.execute(redisScript, Arrays.asList("lock"), uuid);

        } else {
            try {
                Thread.sleep(100);
                this.testLock();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public void testLock2() {
        String uuid = UUID.randomUUID().toString();
        Boolean ifAbsent = stringRedisTemplate.opsForValue().setIfAbsent("lock", uuid, 10, TimeUnit.SECONDS);

        if (ifAbsent) {
            String value = stringRedisTemplate.opsForValue().get("num");
            if (StringUtils.isBlank(value)) {
                return;
            }
            int num = Integer.parseInt(value);
            stringRedisTemplate.opsForValue().set("num", String.valueOf(++num));

            if (uuid.equals(stringRedisTemplate.opsForValue().get("lock"))) {
                stringRedisTemplate.delete("lock");
            }

        } else {
            try {
                Thread.sleep(100);
                this.testLock();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    //本地锁
    public synchronized void testLock1() {
        //从redis里面获取数据
        String value = stringRedisTemplate.opsForValue().get("num");
        if (StringUtils.isBlank(value)) {
            return;
        }

        //把从redis中获取的数据+1
        int num = Integer.parseInt(value);

        //数据加1后放回
        stringRedisTemplate.opsForValue().set("num", String.valueOf(++num));
    }
}
