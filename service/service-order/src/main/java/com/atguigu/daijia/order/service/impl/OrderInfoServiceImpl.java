package com.atguigu.daijia.order.service.impl;

import com.atguigu.daijia.common.constant.RedisConstant;
import com.atguigu.daijia.common.execption.GuiguException;
import com.atguigu.daijia.common.result.ResultCodeEnum;
import com.atguigu.daijia.model.entity.order.*;
import com.atguigu.daijia.model.enums.OrderStatus;
import com.atguigu.daijia.model.form.order.OrderInfoForm;
import com.atguigu.daijia.model.form.order.StartDriveForm;
import com.atguigu.daijia.model.form.order.UpdateOrderBillForm;
import com.atguigu.daijia.model.form.order.UpdateOrderCartForm;
import com.atguigu.daijia.model.vo.base.PageVo;
import com.atguigu.daijia.model.vo.order.*;
import com.atguigu.daijia.order.mapper.OrderBillMapper;
import com.atguigu.daijia.order.mapper.OrderInfoMapper;
import com.atguigu.daijia.order.mapper.OrderProfitsharingMapper;
import com.atguigu.daijia.order.mapper.OrderStatusLogMapper;
import com.atguigu.daijia.order.service.OrderInfoService;
import com.atguigu.daijia.order.service.OrderMonitorService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.*;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Date;
import java.util.Objects;
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

    @Autowired
    private OrderMonitorService orderMonitorService;

    @Autowired
    private OrderBillMapper orderBillMapper;

    @Autowired
    private OrderProfitsharingMapper orderProfitsharingMapper;

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

        //生成订单之后，发送延迟消息
        this.sendDelayMessage(orderInfo.getId());

        //记录日志
        this.log(orderInfo.getId(), orderInfo.getStatus());

        //向redis中添加标识
        redisTemplate.opsForValue().set(RedisConstant.ORDER_ACCEPT_MARK + orderInfo.getId(),
                "0", RedisConstant.ORDER_ACCEPT_MARK_EXPIRES_TIME, TimeUnit.MINUTES);

        return orderInfo.getId();
    }

    private void sendDelayMessage(Long orderId) {
        try {
            //1、创建一个队列
            RBlockingQueue<Object> blockingQueue =
                    redissonClient.getBlockingQueue("queue_cancel");
            //2、把创建的队列放到延迟队列
            RDelayedQueue<Object> delayedQueue =
                    redissonClient.getDelayedQueue(blockingQueue);
            //3、发送消息到延迟队列，并且设置超时时间
            delayedQueue.offer(orderId.toString(), 15, TimeUnit.MINUTES);
        } catch (Exception e) {
            e.printStackTrace();
            throw new GuiguException(ResultCodeEnum.DATA_ERROR);
        }
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

        String key = RedisConstant.ORDER_ACCEPT_MARK + orderId;
        //判断订单是否存在，通过Redis，减少数据库压力
        if (!redisTemplate.hasKey(key)) {
            //抢单失败
            throw new GuiguException(ResultCodeEnum.COB_NEW_ORDER_FAIL);
        }

        //创建锁
        RLock lock = redissonClient.getLock(RedisConstant.ROB_NEW_ORDER_LOCK + orderId);

        try {
            //获取锁
            boolean flag = lock.tryLock(RedisConstant.ROB_NEW_ORDER_LOCK_WAIT_TIME,
                    RedisConstant.ROB_NEW_ORDER_LOCK_LEASE_TIME, TimeUnit.SECONDS);
            if (flag) {
                if (!redisTemplate.hasKey(key)) {
                    //抢单失败
                    throw new GuiguException(ResultCodeEnum.COB_NEW_ORDER_FAIL);
                }
                //司机抢单
                //修改order_info表订单状态值2：已经接单 + 司机id + 司机接单时间
                //修改条件：根据订单id
                LambdaQueryWrapper<OrderInfo> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(OrderInfo::getId, orderId);
                OrderInfo orderInfo = orderInfoMapper.selectOne(wrapper);
                //设置
                orderInfo.setStatus(OrderStatus.ACCEPTED.getStatus());
                orderInfo.setDriverId(driverId);
                orderInfo.setAcceptTime(new Date());
                //调用方法修改
                int rows = orderInfoMapper.updateById(orderInfo);
                if (rows != 1) {
                    //抢单失败
                    throw new GuiguException(ResultCodeEnum.COB_NEW_ORDER_FAIL);
                }

                //删除抢单标识
                redisTemplate.delete(key);
            }
        } catch (Exception e) {
            //抢单失败
            throw new GuiguException(ResultCodeEnum.COB_NEW_ORDER_FAIL);
        } finally {
            //释放
            if (lock.isLocked()) {
                lock.unlock();
            }
        }
        return true;
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
                OrderStatus.END_SERVICE.getStatus()
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
    public Boolean driverArriveStartLocation(Long orderId, Long driverId) {
        //更新订单状态和司机到达时间，条件 ： orderId + driverId

        int rows = orderInfoMapper.update(null, new LambdaUpdateWrapper<OrderInfo>()
                .eq(OrderInfo::getId, orderId)
                .eq(OrderInfo::getDriverId, driverId)
                .set(OrderInfo::getStatus, OrderStatus.DRIVER_ARRIVED.getStatus())
                .set(OrderInfo::getArriveTime, new Date())
        );

        if (rows == 1) {
            this.log(orderId, OrderStatus.DRIVER_ARRIVED.getStatus());
            return true;
        } else {
            throw new GuiguException(ResultCodeEnum.UPDATE_ERROR);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public Boolean updateOrderCart(UpdateOrderCartForm updateOrderCartForm) {
        LambdaQueryWrapper<OrderInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderInfo::getId, updateOrderCartForm.getOrderId());
        wrapper.eq(OrderInfo::getDriverId, updateOrderCartForm.getDriverId());

        OrderInfo orderInfo = new OrderInfo();
        BeanUtils.copyProperties(updateOrderCartForm, orderInfo);
        orderInfo.setStatus(OrderStatus.UPDATE_CART_INFO.getStatus());

        int rows = orderInfoMapper.update(orderInfo, wrapper);
        if (rows == 1) {
            return true;
        } else {
            throw new GuiguException(ResultCodeEnum.UPDATE_ERROR);
        }
    }

    @Override
    public Boolean startDrive(StartDriveForm startDriveForm) {


        int rows = orderInfoMapper.update(null, new LambdaUpdateWrapper<OrderInfo>()
                .eq(OrderInfo::getDriverId, startDriveForm.getDriverId())
                .eq(OrderInfo::getId, startDriveForm.getOrderId())
                .set(OrderInfo::getStatus, OrderStatus.START_SERVICE.getStatus())
                .set(OrderInfo::getStartServiceTime, new Date())
        );
        if (rows == 1) {
            //初始化订单监控统计数据
            OrderMonitor orderMonitor = new OrderMonitor();
            orderMonitor.setOrderId(startDriveForm.getOrderId());
            orderMonitorService.saveOrderMonitor(orderMonitor);
            return true;
        } else {
            throw new GuiguException(ResultCodeEnum.UPDATE_ERROR);
        }
    }

    @Override
    public Long getOrderNumByTime(Long driverId, String startTime, String endTime) {
        Long count = orderInfoMapper.selectCount(new LambdaQueryWrapper<OrderInfo>()
                .ge(OrderInfo::getStartServiceTime, startTime)
                .lt(OrderInfo::getStartServiceTime, endTime)
                .eq(OrderInfo::getDriverId, driverId)
        );
        log.info("Driver {} 的订单数：{}", driverId, count);
        return count;
    }


    @Override
    public Boolean endDrive(UpdateOrderBillForm updateOrderBillForm) {
        //1、更新订单信息
        // update order_info set ... where id=? and driver_id=?

        int rows = orderInfoMapper.update(null, new LambdaUpdateWrapper<OrderInfo>()
                .eq(OrderInfo::getId, updateOrderBillForm.getOrderId())
                .eq(OrderInfo::getDriverId, updateOrderBillForm.getDriverId())
                .set(OrderInfo::getStatus, OrderStatus.END_SERVICE.getStatus())
                .set(OrderInfo::getRealAmount, updateOrderBillForm.getTotalAmount())
                .set(OrderInfo::getFavourFee, updateOrderBillForm.getFavourFee())
                .set(OrderInfo::getRealDistance, updateOrderBillForm.getRealDistance())
                .set(OrderInfo::getEndServiceTime, new Date())
        );

        if (rows == 1) {
            //添加账单信息
            OrderBill orderBill = new OrderBill();
            BeanUtils.copyProperties(updateOrderBillForm, orderBill);
            orderBill.setPayAmount(updateOrderBillForm.getOrderAmount());
            orderBill.setRewardFee(updateOrderBillForm.getRewardAmount());
            orderBillMapper.insert(orderBill);

            //添加分账信息
            OrderProfitsharing orderProfitsharing = new OrderProfitsharing();
            BeanUtils.copyProperties(updateOrderBillForm, orderProfitsharing);
            orderProfitsharing.setOrderId(updateOrderBillForm.getOrderId());
            orderProfitsharing.setRuleId(updateOrderBillForm.getProfitsharingRuleId());
            orderProfitsharing.setStatus(1);
            orderProfitsharingMapper.insert(orderProfitsharing);
        } else {
            throw new GuiguException(ResultCodeEnum.UPDATE_ERROR);
        }
        return true;
    }

    @Override
    public PageVo findCustomerOrderPage(Long customerId, Long page, Long limit) {

        Page<OrderInfo> pageParam = new Page<>(page, limit);
        IPage<OrderListVo> orderListVoIPage = orderInfoMapper.selectCustomerOrderPage(pageParam, customerId);

        PageVo pageVo = new PageVo<>(orderListVoIPage.getRecords(), orderListVoIPage.getPages(), orderListVoIPage.getTotal());
        pageVo.setPage(page);
        pageVo.setLimit(limit);

        return pageVo;
    }

    @Override
    public PageVo findDriverOrderPage(Long driverId, Long page, Long limit) {
        Page<OrderInfo> pageParam = new Page<>(page, limit);
        IPage<OrderListVo> orderListVoIPage = orderInfoMapper.selectDriverOrderPage(pageParam, driverId);

        PageVo pageVo = new PageVo<>(orderListVoIPage.getRecords(), orderListVoIPage.getPages(), orderListVoIPage.getTotal());
        pageVo.setPage(page);
        pageVo.setLimit(limit);

        return pageVo;
    }

    @Override
    public OrderBillVo getOrderBillInfo(Long orderId) {

        OrderBill orderBill = orderBillMapper.selectOne(new LambdaQueryWrapper<OrderBill>().eq(OrderBill::getOrderId, orderId));
        OrderBillVo orderBillVo = new OrderBillVo();
        BeanUtils.copyProperties(orderBill, orderBillVo);

        return orderBillVo;
    }

    @Override
    public OrderProfitsharingVo getOrderProfitsharing(Long orderId) {
        OrderProfitsharing orderProfitsharing =
                orderProfitsharingMapper.selectOne(new LambdaQueryWrapper<OrderProfitsharing>().eq(OrderProfitsharing::getOrderId, orderId));
        OrderProfitsharingVo orderProfitsharingVo = new OrderProfitsharingVo();
        BeanUtils.copyProperties(orderProfitsharing, orderProfitsharingVo);
        return orderProfitsharingVo;
    }

    @Override
    public Boolean sentOrderBillInfo(Long orderId, Long driverId) {

        int rows = orderInfoMapper.update(null, new LambdaUpdateWrapper<OrderInfo>()
                .eq(OrderInfo::getId, orderId)
                .eq(OrderInfo::getDriverId, driverId)
                .eq(OrderInfo::getStatus, 6)
                .set(OrderInfo::getStatus, OrderStatus.UNPAID.getStatus())
        );

        if (rows == 1) {
            this.log(orderId, OrderStatus.UNPAID.getStatus());
        } else {
            throw new GuiguException(ResultCodeEnum.UPDATE_ERROR);
        }
        return true;
    }

    @Override
    public OrderPayVo getOrderPayVo(String orderNo, Long customerId) {
        OrderPayVo orderPayVo = orderInfoMapper.selectOrderPayVo(orderNo, customerId);
        if (orderPayVo != null) {
            orderPayVo.setContent(orderPayVo.getStartLocation() + " 到 " + orderPayVo.getEndLocation());
        }
        return orderPayVo;
    }

    @Override
    public Boolean updateOrderPayStatus(String orderNo) {
        //1、根据订单编号查询，判断订单状态
        OrderInfo orderInfo = orderInfoMapper.selectOne(new LambdaQueryWrapper<OrderInfo>().eq(OrderInfo::getOrderNo, orderNo));
        if (orderInfo == null || Objects.equals(orderInfo.getStatus(), OrderStatus.PAID.getStatus())) {
            return true;
        }

        int rows = orderInfoMapper.update(null, new LambdaUpdateWrapper<OrderInfo>()
                .eq(OrderInfo::getOrderNo, orderNo)
                .set(OrderInfo::getStatus, OrderStatus.PAID.getStatus())
                .set(OrderInfo::getPayTime, new Date())
        );

        if (rows == 1) {
            return true;
        } else {
            throw new GuiguException(ResultCodeEnum.UPDATE_ERROR);
        }
    }

    @Override
    public OrderRewardVo getOrderRewardFee(String orderNo) {
        OrderInfo orderInfo =
                orderInfoMapper.selectOne(new LambdaQueryWrapper<OrderInfo>()
                        .eq(OrderInfo::getOrderNo, orderNo)
                        .select(OrderInfo::getId, OrderInfo::getDriverId)
                );
        OrderBill orderBill =
                orderBillMapper.selectOne(new LambdaQueryWrapper<OrderBill>()
                        .eq(OrderBill::getOrderId, orderInfo.getId())
                        .select(OrderBill::getRewardFee)
                );

        OrderRewardVo orderRewardVo = new OrderRewardVo();
        orderRewardVo.setOrderId(orderInfo.getId());
        orderRewardVo.setDriverId(orderInfo.getDriverId());
        orderRewardVo.setRewardFee(orderBill.getRewardFee());

        return orderRewardVo;
    }

    @Override
    public void orderCancel(long orderId) {
        //orderId查询订单信息
        OrderInfo orderInfo =
                orderInfoMapper.selectById(orderId);
        //判断
        if (orderInfo != null && Objects.equals(orderInfo.getStatus(), OrderStatus.WAITING_ACCEPT.getStatus())) {
            //修改订单状态
            orderInfo.setStatus(OrderStatus.CANCEL_ORDER.getStatus());
            int rows = orderInfoMapper.updateById(orderInfo);
            if (rows == 1) {
                //删除接单标识
                redisTemplate.delete(RedisConstant.ORDER_ACCEPT_MARK +  orderId);
            }
        }
    }

    @Override
    public Boolean updateCouponAmount(Long orderId, BigDecimal couponAmount) {
        orderBillMapper.updateCouponAmount(orderId, couponAmount);
        return true;
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