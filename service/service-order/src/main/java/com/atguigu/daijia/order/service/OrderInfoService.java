package com.atguigu.daijia.order.service;

import com.atguigu.daijia.model.entity.order.OrderInfo;
import com.atguigu.daijia.model.form.order.OrderInfoForm;
import com.atguigu.daijia.model.form.order.UpdateOrderCartForm;
import com.atguigu.daijia.model.vo.order.CurrentOrderInfoVo;
import com.baomidou.mybatisplus.extension.service.IService;

public interface OrderInfoService extends IService<OrderInfo> {

    //乘客下单
    Long saveOrderInfo(OrderInfoForm orderInfoForm);

    //根据订单id获取订单状态
    Integer getOrderStatus(Long orderId);

    //司机抢单
    //乐观锁解决并发问题
    Boolean robNewOrderOptimisticLocking(Long driverId, Long orderId);


    //Redisson分布式锁
    //司机抢单
    Boolean robNewOrder(Long driverId, Long orderId);

    //乘客端查找当前订单
    CurrentOrderInfoVo searchCustomerCurrentOrder(Long customerId);

    //司机端查找当前订单
    CurrentOrderInfoVo searchDriverCurrentOrder(Long driverId);

    //根据订单id查找订单信息
    OrderInfo getOrderInfo(Long orderId);

    Boolean driverArriveStartLocation(Long orderId, Long driverId);

    Boolean updateOrderCart(UpdateOrderCartForm updateOrderCartForm);
}
