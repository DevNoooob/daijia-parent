package com.atguigu.daijia.dispatch.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.atguigu.daijia.common.constant.RedisConstant;
import com.atguigu.daijia.dispatch.mapper.OrderJobMapper;
import com.atguigu.daijia.dispatch.service.NewOrderService;
import com.atguigu.daijia.dispatch.xxl.client.XxlJobClient;
import com.atguigu.daijia.map.client.LocationFeignClient;
import com.atguigu.daijia.model.entity.dispatch.OrderJob;
import com.atguigu.daijia.model.enums.OrderStatus;
import com.atguigu.daijia.model.form.map.SearchNearByDriverForm;
import com.atguigu.daijia.model.vo.dispatch.NewOrderTaskVo;
import com.atguigu.daijia.model.vo.map.NearByDriverVo;
import com.atguigu.daijia.model.vo.order.NewOrderDataVo;
import com.atguigu.daijia.order.client.OrderInfoFeignClient;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class NewOrderServiceImpl implements NewOrderService {

    @Autowired
    XxlJobClient xxlJobClient;

    @Autowired
    OrderJobMapper orderJobMapper;

    @Autowired
    private LocationFeignClient locationFeignClient;

    @Autowired
    private OrderInfoFeignClient orderInfoFeignClient;

    @Autowired
    private RedisTemplate redisTemplate;

    @Override
    public Long addAndStartTask(NewOrderTaskVo newOrderTaskVo) {
        //1、判断当前订单是否启动任务调度
        //根据订单id查询
        OrderJob orderJob = orderJobMapper.selectOne(new LambdaQueryWrapper<OrderJob>().eq(OrderJob::getOrderId, newOrderTaskVo.getOrderId()));

        //2、没有启动，进行操作
        if (orderJob == null) {
            //创建并启动任务调度
            Long jobId = xxlJobClient.addAndStart("newOrderTaskHandler", "", "0 */1 * * * ?", "新创建一个订单任务调度:" + newOrderTaskVo.getOrderId());

            //记录任务调度信息
            orderJob = new OrderJob();
            orderJob.setOrderId(newOrderTaskVo.getOrderId());
            orderJob.setJobId(jobId);
            orderJob.setParameter(JSONObject.toJSONString(newOrderTaskVo));
            orderJobMapper.insert(orderJob);
        }


        return orderJob.getJobId();
    }

    @Override
    public void executeTask(long jobId) {
        //1、根据jobId查询数据库表，当前任务是否已经创建
        //如果没有创建，不往下执行
        OrderJob orderJob = orderJobMapper.selectOne(new LambdaQueryWrapper<OrderJob>().eq(OrderJob::getJobId, jobId));
        if (orderJob == null) {
            return;
        }
        //2、查询订单状态，如果当前订单接单状态，继续执行。如果当前订单不是接单状态，停止任务调度
        Integer status = orderInfoFeignClient.getOrderStatus(orderJob.getOrderId()).getData();
        if (status != OrderStatus.WAITING_ACCEPT.getStatus().intValue()) {
            //停止调度任务
            xxlJobClient.stopJob(jobId);
            return;
        }

        //3、远程调用：搜索符合条件的可以接单的司机
        NewOrderTaskVo newOrderTaskVo = JSONObject.parseObject(orderJob.getParameter(), NewOrderTaskVo.class);

        SearchNearByDriverForm searchNearByDriverForm = new SearchNearByDriverForm();
        searchNearByDriverForm.setLatitude(newOrderTaskVo.getStartPointLatitude());
        searchNearByDriverForm.setLongitude(newOrderTaskVo.getStartPointLongitude());
        searchNearByDriverForm.setMileageDistance(newOrderTaskVo.getExpectDistance());

        List<NearByDriverVo> nearByDriverVoList =
                locationFeignClient.searchNearByDriver(searchNearByDriverForm).getData();

        //5、遍历司机集合，得到每个司机，为每个司机创建临时队列，存储新订单信息
        nearByDriverVoList.forEach(driver -> {
            //使用redis的set类型
            //根据订单id生成key
            String repeatKey =
                    RedisConstant.DRIVER_ORDER_REPEAT_LIST + newOrderTaskVo.getOrderId();
            //记录司机id，防止重复推送
            Boolean isMember = redisTemplate.opsForSet().isMember(repeatKey, driver.getDriverId());
            if (Boolean.FALSE.equals(isMember)) {
                //把订单信息推送给司机
                redisTemplate.opsForSet().add(repeatKey, driver.getDriverId());
                //过期时间，15分钟，超过15分钟没人接单自动取消
                redisTemplate.expire(repeatKey,
                        RedisConstant.DRIVER_ORDER_REPEAT_LIST_EXPIRES_TIME,
                        TimeUnit.MINUTES);

                NewOrderDataVo newOrderDataVo = new NewOrderDataVo();
                newOrderDataVo.setOrderId(newOrderTaskVo.getOrderId());
                newOrderDataVo.setStartLocation(newOrderTaskVo.getStartLocation());
                newOrderDataVo.setEndLocation(newOrderTaskVo.getEndLocation());
                newOrderDataVo.setExpectAmount(newOrderTaskVo.getExpectAmount());
                newOrderDataVo.setExpectDistance(newOrderTaskVo.getExpectDistance());
                newOrderDataVo.setExpectTime(newOrderTaskVo.getExpectTime());
                newOrderDataVo.setFavourFee(newOrderTaskVo.getFavourFee());
                newOrderDataVo.setDistance(driver.getDistance());
                newOrderDataVo.setCreateTime(newOrderTaskVo.getCreateTime());
                //新订单保存司机的临时队列，Redis里面List集合
                String key = RedisConstant.DRIVER_ORDER_TEMP_LIST + driver.getDriverId();
                redisTemplate.opsForList().leftPush(key, JSONObject.toJSONString(newOrderDataVo));
                //过期时间：1分钟
                redisTemplate.expire(key, RedisConstant.DRIVER_ORDER_TEMP_LIST_EXPIRES_TIME, TimeUnit.MINUTES);
            }
        });
    }

    @Override
    public List<NewOrderDataVo> findNewOrderQueueData(Long driverId) {
        List<NewOrderDataVo> list = new ArrayList<>();
        String key = RedisConstant.DRIVER_ORDER_TEMP_LIST + driverId;
        Long size = redisTemplate.opsForList().size(key);
        if (size != null && size > 0) {
            for (int i = 0; i < size; i++) {
                String content = (String) redisTemplate.opsForList().leftPop(key);
                NewOrderDataVo newOrderDataVo = JSONObject.parseObject(content, NewOrderDataVo.class);
                list.add(newOrderDataVo);
            }
        }
        return list;
    }

    @Override
    public Boolean clearNewOrderQueueData(Long driverId) {
        String key = RedisConstant.DRIVER_ORDER_TEMP_LIST + driverId;
        redisTemplate.delete(key);
        return Boolean.TRUE;
    }
}
