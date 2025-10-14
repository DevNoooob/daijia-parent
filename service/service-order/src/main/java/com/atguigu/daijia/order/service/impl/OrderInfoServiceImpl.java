package com.atguigu.daijia.order.service.impl;

import com.atguigu.daijia.common.constant.RedisConstant;
import com.atguigu.daijia.common.execption.GuiguException;
import com.atguigu.daijia.common.result.ResultCodeEnum;
import com.atguigu.daijia.model.entity.order.OrderInfo;
import com.atguigu.daijia.model.entity.order.OrderStatusLog;
import com.atguigu.daijia.model.enums.OrderStatus;
import com.atguigu.daijia.model.form.order.OrderInfoForm;
import com.atguigu.daijia.order.mapper.OrderInfoMapper;
import com.atguigu.daijia.order.mapper.OrderStatusLogMapper;
import com.atguigu.daijia.order.service.OrderInfoService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class OrderInfoServiceImpl extends ServiceImpl<OrderInfoMapper, OrderInfo> implements OrderInfoService {

    @Autowired
    private OrderInfoMapper orderInfoMapper;

    @Autowired
    private OrderStatusLogMapper orderStatusLogMapper;

    @Autowired
    private RedisTemplate redisTemplate;

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

    @Override
    public Boolean robNewOrder(Long driverId, Long orderId) {
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
