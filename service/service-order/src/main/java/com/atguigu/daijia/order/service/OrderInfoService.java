package com.atguigu.daijia.order.service;

import com.atguigu.daijia.model.entity.order.OrderInfo;
import com.atguigu.daijia.model.form.order.OrderInfoForm;
import com.atguigu.daijia.model.form.order.StartDriveForm;
import com.atguigu.daijia.model.form.order.UpdateOrderBillForm;
import com.atguigu.daijia.model.form.order.UpdateOrderCartForm;
import com.atguigu.daijia.model.vo.base.PageVo;
import com.atguigu.daijia.model.vo.order.CurrentOrderInfoVo;
import com.atguigu.daijia.model.vo.order.OrderBillVo;
import com.atguigu.daijia.model.vo.order.OrderPayVo;
import com.atguigu.daijia.model.vo.order.OrderProfitsharingVo;
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


    Boolean driverArriveStartLocation(Long orderId, Long driverId);

    Boolean updateOrderCart(UpdateOrderCartForm updateOrderCartForm);

    Boolean startDrive(StartDriveForm startDriveForm);

    Long getOrderNumByTime(Long driverId, String startTime, String endtime);

    Boolean endDrive(UpdateOrderBillForm updateOrderBillForm);

    PageVo findCustomerOrderPage(Long customerId, Long page, Long limit);

    PageVo findDriverOrderPage(Long driverId, Long page, Long limit);

    OrderBillVo getOrderBillInfo(Long orderId);

    OrderProfitsharingVo getOrderProfitsharing(Long orderId);

    Boolean sentOrderBillInfo(Long orderId, Long driverId);

    OrderPayVo getOrderPayVo(String orderNo, Long customerId);
}
