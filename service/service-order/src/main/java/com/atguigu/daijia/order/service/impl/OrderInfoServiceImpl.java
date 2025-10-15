package com.atguigu.daijia.order.service.impl;

import com.atguigu.daijia.common.constant.RedisConstant;
import com.atguigu.daijia.common.execption.GuiguException;
import com.atguigu.daijia.common.result.ResultCodeEnum;
import com.atguigu.daijia.model.entity.order.OrderInfo;
import com.atguigu.daijia.model.entity.order.OrderStatusLog;
import com.atguigu.daijia.model.enums.OrderStatus;
import com.atguigu.daijia.model.form.order.OrderInfoForm;
import com.atguigu.daijia.model.vo.order.CurrentOrderInfoVo;
import com.atguigu.daijia.order.mapper.OrderInfoMapper;
import com.atguigu.daijia.order.mapper.OrderStatusLogMapper;
import com.atguigu.daijia.order.service.OrderInfoService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class OrderInfoServiceImpl extends ServiceImpl<OrderInfoMapper, OrderInfo> implements OrderInfoService {

    @Autowired
    private OrderInfoMapper orderInfoMapper;

    @Autowired
    private OrderStatusLogMapper orderStatusLogMapper;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    @Override
    public Long saveOrderInfo(OrderInfoForm orderInfoForm) {
        OrderInfo orderInfo = new OrderInfo();
        BeanUtils.copyProperties(orderInfoForm, orderInfo);

        //订单号
        String orderNo = UUID.randomUUID().toString().replaceAll("-", "");
        orderInfo.setOrderNo(orderNo);

        //订单状态
        orderInfo.setStatus(OrderStatus.WAITING_ACCEPT.getStatus());
        orderInfoMapper.insert(orderInfo);

        //记录日志
        this.log(orderInfo.getId(), orderInfo.getStatus());

        //向redis中添加标识
        redisTemplate.opsForValue().set(RedisConstant.ORDER_ACCEPT_MARK + orderInfo.getId(),
                "0", RedisConstant.ORDER_ACCEPT_MARK_EXPIRES_TIME, TimeUnit.MINUTES);

        return orderInfo.getId();
    }

    @Override
    public Integer getOrderStatus(Long orderId) {
        //sql语句：select status from order_info where id = ?

        //构造条件
        LambdaQueryWrapper<OrderInfo> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(OrderInfo::getId, orderId);
        queryWrapper.select(OrderInfo::getStatus);

        //调用mapper
        OrderInfo orderInfo = orderInfoMapper.selectOne(queryWrapper);

        //订单不存在
        if (orderInfo == null) {
            return OrderStatus.NULL_ORDER.getStatus();
        }

        return orderInfo.getStatus();
    }

    //Redisson分布式锁
    //司机抢单
    @Override
    public Boolean robNewOrder(Long driverId, Long orderId) {
        String acceptMarkKey = RedisConstant.ORDER_ACCEPT_MARK + orderId;
        String lockKey = RedisConstant.ROB_NEW_ORDER_LOCK + orderId;
        RLock rLock = redissonClient.getLock(lockKey);

        try {
            // 1. 判断订单是否仍存在可接单标识
            if (!redisTemplate.hasKey(acceptMarkKey)) {
                throw new GuiguException(ResultCodeEnum.COB_NEW_ORDER_FAIL);
            }

            // 2. 获取分布式锁（带等待与租约时间）
            boolean locked = rLock.tryLock(
                    RedisConstant.ROB_NEW_ORDER_LOCK_WAIT_TIME,
                    RedisConstant.ROB_NEW_ORDER_LOCK_LEASE_TIME,
                    TimeUnit.SECONDS
            );

            if (!locked) {
                throw new GuiguException(ResultCodeEnum.COB_NEW_ORDER_FAIL);
            }

            // 3. 二次校验（防止并发时标识被删除）
            if (!redisTemplate.hasKey(acceptMarkKey)) {
                throw new GuiguException(ResultCodeEnum.COB_NEW_ORDER_FAIL);
            }

            // 4. 更新数据库状态：等待接单 -> 已接单
            int rows = orderInfoMapper.update(null,
                    new LambdaUpdateWrapper<OrderInfo>()
                            .eq(OrderInfo::getId, orderId)
                            .eq(OrderInfo::getStatus, OrderStatus.WAITING_ACCEPT.getStatus())
                            .set(OrderInfo::getDriverId, driverId)
                            .set(OrderInfo::getStatus, OrderStatus.ACCEPTED.getStatus())
                            .set(OrderInfo::getAcceptTime, new Date()));

            if (rows != 1) {
                throw new GuiguException(ResultCodeEnum.COB_NEW_ORDER_FAIL);
            }

            // 5. 抢单成功，删除Redis抢单标识
            redisTemplate.delete(acceptMarkKey);
            return Boolean.TRUE;

        } catch (Exception e) {
            log.error("司机抢单失败, driverId={}, orderId={}", driverId, orderId, e);
            throw new GuiguException(ResultCodeEnum.COB_NEW_ORDER_FAIL);
        } finally {
            // 6. 释放锁（确保只释放当前线程持有的锁）
            if (rLock.isHeldByCurrentThread()) {
                rLock.unlock();
            }
        }
    }

    //乘客端查找当前订单
    @Override
    public CurrentOrderInfoVo searchCustomerCurrentOrder(Long customerId) {
        //封装需要的条件
        Integer[] statusArray = {
                OrderStatus.ACCEPTED.getStatus(),
                OrderStatus.DRIVER_ARRIVED.getStatus(),
                OrderStatus.UPDATE_CART_INFO.getStatus(),
                OrderStatus.START_SERVICE.getStatus(),
                OrderStatus.END_SERVICE.getStatus(),
                OrderStatus.UNPAID.getStatus()
        };

        OrderInfo orderInfo = orderInfoMapper.selectOne(new LambdaQueryWrapper<OrderInfo>()
                .eq(OrderInfo::getCustomerId, customerId)
                .in(OrderInfo::getStatus, statusArray)
                .orderByDesc(OrderInfo::getId)
                .last("limit 1")
        );

        CurrentOrderInfoVo currentOrderInfoVo = new CurrentOrderInfoVo();

        if (orderInfo != null) {
            currentOrderInfoVo.setOrderId(orderInfo.getId());
            currentOrderInfoVo.setStatus(orderInfo.getStatus());
            currentOrderInfoVo.setIsHasCurrentOrder(true);
        } else {
            currentOrderInfoVo.setIsHasCurrentOrder(false);
        }
        return currentOrderInfoVo;
    }

    @Override
    public CurrentOrderInfoVo searchDriverCurrentOrder(Long driverId) {
        Integer[] statusArray = {
                OrderStatus.ACCEPTED.getStatus(),
                OrderStatus.DRIVER_ARRIVED.getStatus(),
                OrderStatus.UPDATE_CART_INFO.getStatus(),
                OrderStatus.START_SERVICE.getStatus(),
                OrderStatus.END_SERVICE.getStatus(),
                OrderStatus.UNPAID.getStatus()
        };

        OrderInfo orderInfo = orderInfoMapper.selectOne(new LambdaQueryWrapper<OrderInfo>()
                .eq(OrderInfo::getDriverId, driverId)
                .in(OrderInfo::getStatus, statusArray)
                .orderByDesc(OrderInfo::getId)
                .last("limit 1")
        );
        CurrentOrderInfoVo currentOrderInfoVo = new CurrentOrderInfoVo();
        if (orderInfo != null) {
            currentOrderInfoVo.setOrderId(orderInfo.getId());
            currentOrderInfoVo.setStatus(orderInfo.getStatus());
            currentOrderInfoVo.setIsHasCurrentOrder(true);
        } else {
            currentOrderInfoVo.setIsHasCurrentOrder(false);
        }
        return currentOrderInfoVo;
    }

    @Override
    public OrderInfo getOrderInfo(Long orderId) {
        return orderInfoMapper.selectById(orderId);
    }


    //乐观锁解决并发问题
    @Override
    public Boolean robNewOrderOptimisticLocking(Long driverId, Long orderId) {
        //判断订单是否存在
        if (!redisTemplate.hasKey(RedisConstant.ORDER_ACCEPT_MARK + orderId)) {
            throw new GuiguException(ResultCodeEnum.COB_NEW_ORDER_FAIL);
        }

        //司机抢单
        //修改order_info表订单状态 2 ，已经接单 + 司机id + 司机接单时间
        //修改条件：根据订单id

        int rows = orderInfoMapper.update(null,
                new LambdaUpdateWrapper<OrderInfo>()
                        .eq(OrderInfo::getId, orderId)
                        .eq(OrderInfo::getStatus, OrderStatus.WAITING_ACCEPT.getStatus())
                        .set(OrderInfo::getDriverId, driverId)
                        .set(OrderInfo::getStatus, OrderStatus.ACCEPTED.getStatus())
                        .set(OrderInfo::getAcceptTime, new Date()));

        //抢单失败
        if (rows != 1) throw new GuiguException(ResultCodeEnum.COB_NEW_ORDER_FAIL);

        //抢单成功，删除抢单标识
        redisTemplate.delete(RedisConstant.ORDER_ACCEPT_MARK + orderId);

        return Boolean.TRUE;

    }

    public void log(Long orderId, Integer orderStatus) {
        OrderStatusLog orderStatusLog = new OrderStatusLog();
        orderStatusLog.setOrderId(orderId);
        orderStatusLog.setOrderStatus(orderStatus);
        orderStatusLog.setOperateTime(new Date());
        orderStatusLogMapper.insert(orderStatusLog);
    }
}
