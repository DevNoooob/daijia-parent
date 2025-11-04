package com.atguigu.daijia.payment.service.impl;

import com.alibaba.fastjson.JSON;
import com.atguigu.daijia.common.constant.MqConst;
import com.atguigu.daijia.common.execption.GuiguException;
import com.atguigu.daijia.common.result.ResultCodeEnum;
import com.atguigu.daijia.common.service.RabbitService;
import com.atguigu.daijia.common.util.RequestUtils;
import com.atguigu.daijia.driver.client.DriverAccountFeignClient;
import com.atguigu.daijia.model.entity.payment.PaymentInfo;
import com.atguigu.daijia.model.enums.TradeType;
import com.atguigu.daijia.model.form.driver.TransferForm;
import com.atguigu.daijia.model.form.payment.PaymentInfoForm;
import com.atguigu.daijia.model.vo.order.OrderRewardVo;
import com.atguigu.daijia.model.vo.payment.WxPrepayVo;
import com.atguigu.daijia.order.client.OrderInfoFeignClient;
import com.atguigu.daijia.payment.config.WxPayV3Properties;
import com.atguigu.daijia.payment.mapper.PaymentInfoMapper;
import com.atguigu.daijia.payment.service.WxPayService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wechat.pay.java.core.RSAAutoCertificateConfig;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import com.wechat.pay.java.service.payments.jsapi.model.*;
import com.wechat.pay.java.service.payments.model.Transaction;
import io.seata.spring.annotation.GlobalTransactional;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Date;

@Service
@Slf4j
public class WxPayServiceImpl implements WxPayService {

    @Autowired
    private PaymentInfoMapper paymentInfoMapper;

    @Autowired
    private WxPayV3Properties wxPayV3Properties;

    @Autowired
    private RSAAutoCertificateConfig rsaAutoCertificateConfig;

    @Autowired
    private RabbitService rabbitService;

    @Autowired
    private OrderInfoFeignClient orderInfoFeignClient;

    @Autowired
    private DriverAccountFeignClient driverAccountFeignClient;

    //正确做法，但因为无法认证企业微信支付，使得无法使用
    /*@Override
    public WxPrepayVo createWxPayment(PaymentInfoForm paymentInfoForm) {
        try {
            //1、添加支付记录到支付表里面
            //判断：如果表存在订单支付记录，不需要添加
            PaymentInfo paymentInfo = paymentInfoMapper.selectOne(new LambdaQueryWrapper<PaymentInfo>().eq(PaymentInfo::getOrderNo, paymentInfoForm.getOrderNo()));
            if (paymentInfo == null) {
                paymentInfo = new PaymentInfo();
                BeanUtils.copyProperties(paymentInfoForm, paymentInfo);
                paymentInfo.setPaymentStatus(0);
                paymentInfoMapper.insert(paymentInfo);
            }

            //2、创建微信支付对象
            JsapiServiceExtension jsapiServiceExtension =
                    new JsapiServiceExtension.Builder().config(rsaAutoCertificateConfig).build();

            //3、创建Request对象，封装微信支付需要参数
            PrepayRequest request = new PrepayRequest();
            Amount amount = new Amount();
            amount.setTotal(paymentInfoForm.getAmount().multiply(new BigDecimal(100)).intValue());
            request.setAmount(amount);
            request.setAppid(wxPayV3Properties.getAppid());
            request.setMchid(wxPayV3Properties.getMerchantid());
            String description = paymentInfo.getContent();
            if (description.length() > 127) {
                description = description.substring(0, 127);
            }
            request.setDescription(description);
            request.setNotifyUrl(wxPayV3Properties.getNotifyUrl());
            request.setOutTradeNo(paymentInfoForm.getOrderNo());

            Payer payer = new Payer();
            payer.setOpenid(paymentInfoForm.getCustomerOpenId());
            request.setPayer(payer);

            SettleInfo settleInfo = new SettleInfo();
            settleInfo.setProfitSharing(true);
            request.setSettleInfo(settleInfo);

            PrepayWithRequestPaymentResponse response = new PrepayWithRequestPaymentResponse();
            log.info("微信支付下单返回参数：{}", JSON.toJSONString(response));

            WxPrepayVo wxPrepayVo = new WxPrepayVo();
            BeanUtils.copyProperties(response, wxPrepayVo);
            wxPrepayVo.setTimeStamp(response.getTimeStamp());
            return wxPrepayVo;
        } catch (Exception e) {
            e.printStackTrace();
            throw new GuiguException(ResultCodeEnum.WX_CREATE_ERROR);
        }
    }*/

    @Override
    public WxPrepayVo createWxPayment(PaymentInfoForm paymentInfoForm) {
        try {
            // 判断：如果表里存在订单支付记录，不需要添加
            LambdaQueryWrapper<PaymentInfo> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(PaymentInfo::getOrderNo, paymentInfoForm.getOrderNo());
            PaymentInfo paymentInfo = paymentInfoMapper.selectOne(wrapper);
            if (paymentInfo == null) {
                paymentInfo = new PaymentInfo();
                BeanUtils.copyProperties(paymentInfoForm, paymentInfo);
                paymentInfo.setPaymentStatus(0);
                paymentInfoMapper.insert(paymentInfo);
            }

            // 假数据返回，跳过真实微信请求
            WxPrepayVo wxPrepayVo = new WxPrepayVo();
            wxPrepayVo.setAppId(wxPayV3Properties.getAppid());
            wxPrepayVo.setTimeStamp(String.valueOf(System.currentTimeMillis()));
            wxPrepayVo.setNonceStr("abc123def456ghi789");
            wxPrepayVo.setPackageVal("prepay_id=wx1234567890abcdef1234567890abcdef");
            wxPrepayVo.setSignType("MD5");
            wxPrepayVo.setPaySign("3f61fa0a320f9a0c44e927f04cd1245a");
            return wxPrepayVo;
        } catch (Exception e) {
            throw new GuiguException(ResultCodeEnum.DATA_ERROR);
        }
    }

    @Override
    public Boolean queryPayStatus(String orderNo) {
        //1、创建微信支付操作对象
        JsapiServiceExtension service =
                new JsapiServiceExtension.Builder().config(rsaAutoCertificateConfig).build();

        //2、封装查询支付状态需要参数
        QueryOrderByOutTradeNoRequest queryRequest = new QueryOrderByOutTradeNoRequest();
        queryRequest.setMchid(wxPayV3Properties.getMerchantid());
        queryRequest.setOutTradeNo(orderNo);

        //3、调用微信支付操作对象里面方法实现查询操作
        Transaction transaction = service.queryOrderByOutTradeNo(queryRequest);

        //4、查询返回结果，依据结果判断
        if (transaction != null && transaction.getTradeState() == Transaction.TradeStateEnum.SUCCESS) {
            //5、如果支付成功，调用其他方法实现支付后处理逻辑
            this.handlePayment(transaction);
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void wxnotipy(HttpServletRequest request) {
        //1.回调通知的验签与解密
        //从request头信息获取参数
        //HTTP 头 Wechatpay-Signature
        // HTTP 头 Wechatpay-Nonce
        //HTTP 头 Wechatpay-Timestamp
        //HTTP 头 Wechatpay-Serial
        //HTTP 头 Wechatpay-Signature-Type
        //HTTP 请求体 body。切记使用原始报文，不要用 JSON 对象序列化后的字符串，避免验签的 body 和原文不一致。
        String wechatPaySerial = request.getHeader("Wechatpay-Serial");
        String nonce = request.getHeader("Wechatpay-Nonce");
        String timestamp = request.getHeader("Wechatpay-Timestamp");
        String signature = request.getHeader("Wechatpay-Signature");
        String requestBody = RequestUtils.readData(request);

        //2.构造 RequestParam
        RequestParam requestParam = new RequestParam.Builder()
                .serialNumber(wechatPaySerial)
                .nonce(nonce)
                .signature(signature)
                .timestamp(timestamp)
                .body(requestBody)
                .build();

        //3.初始化 NotificationParser
        NotificationParser parser = new NotificationParser(rsaAutoCertificateConfig);
        //4.以支付通知回调为例，验签、解密并转换成 Transaction
        Transaction transaction = parser.parse(requestParam, Transaction.class);

        if (null != transaction && transaction.getTradeState() == Transaction.TradeStateEnum.SUCCESS) {
            //5.处理支付业务
            this.handlePayment(transaction);
        }
    }

    //支付成功后续处理
    @GlobalTransactional
    @Override
    public void handleOrder(String orderNo) {
        //1、远程调用：更新订单状态：已经支付
        Boolean update = orderInfoFeignClient.updateOrderPayStatus(orderNo).getData();

        //2、远程调用：获取系统奖励，打入到司机账户
        OrderRewardVo orderRewardVo =
                orderInfoFeignClient.getOrderRewardFee(orderNo).getData();

        if (orderRewardVo != null && orderRewardVo.getRewardFee().doubleValue() > 0.0){
            TransferForm transferForm = new TransferForm();
            transferForm.setTradeNo(orderNo);
            transferForm.setTradeType(TradeType.REWARD.getType());
            transferForm.setContent(TradeType.REWARD.getContent());
            transferForm.setDriverId(orderRewardVo.getDriverId());
            transferForm.setAmount(orderRewardVo.getRewardFee());
            driverAccountFeignClient.transfer(transferForm);
        }


        //3、TODO 其他
    }

    private void handlePayment(Transaction transaction) {
        //1、更新支付记录，状态修改为已支付
        //订单编号
        String orderNo = transaction.getOutTradeNo();
        //根据订单编号查询支付记录
        PaymentInfo paymentInfo =
                paymentInfoMapper.selectOne(new LambdaQueryWrapper<PaymentInfo>().eq(PaymentInfo::getOrderNo, orderNo));
        if (paymentInfo.getPaymentStatus() == 1) {
            return;
        }
        paymentInfo.setPaymentStatus(1);
        paymentInfo.setTransactionId(transaction.getTransactionId());
        paymentInfo.setCallbackTime(new Date());
        paymentInfo.setCallbackContent(JSON.toJSONString(transaction));
        paymentInfoMapper.updateById(paymentInfo);

        //2、发送端：发送MQ消息，传递订单编号
        //3、接收端：获取订单编号，完成后续处理
        rabbitService.sendMessage(MqConst.EXCHANGE_ORDER, MqConst.ROUTING_PAY_SUCCESS, orderNo);

    }

//    @Override
//    public Boolean queryPayStatus(String orderNo) {
//        this.handlePayment(orderNo);
//        return true;
//    }
//
//    @GlobalTransactional
//    @Override
//    public void handleOrder(String orderNo) {
//        orderInfoFeignClient.updateOrderPayStatus(orderNo);
//        OrderRewardVo orderRewardVo = orderInfoFeignClient.getOrderRewardFee(orderNo).getData();
//        if (orderRewardVo != null && orderRewardVo.getRewardFee().doubleValue() > 0) {
//            TransferForm transferForm = new TransferForm();
//            transferForm.setTradeNo(orderNo);
//            transferForm.setTradeType(TradeType.REWARD.getType());
//            transferForm.setContent(TradeType.REWARD.getContent());
//            transferForm.setAmount(orderRewardVo.getRewardFee());
//            transferForm.setDriverId(orderRewardVo.getDriverId());
//            driverAccountFeignClient.transfer(transferForm);
//        }
//    }
//
//    private void handlePayment(String orderNo) {
//        LambdaQueryWrapper<PaymentInfo> wrapper = new LambdaQueryWrapper<>();
//        wrapper.eq(PaymentInfo::getOrderNo, orderNo);
//        PaymentInfo paymentInfo = paymentInfoMapper.selectOne(wrapper);
//        if (paymentInfo.getPaymentStatus() == 1) return;
//
//        paymentInfo.setPaymentStatus(1);
//        paymentInfo.setOrderNo(orderNo);
//        paymentInfo.setTransactionId(orderNo);
//        paymentInfo.setCallbackTime(new Date());
//        paymentInfoMapper.updateById(paymentInfo);
//
//        rabbitService.sendMessage(
//                MqConst.EXCHANGE_ORDER,
//                MqConst.ROUTING_PAY_SUCCESS,
//                orderNo
//        );
//    }

}
