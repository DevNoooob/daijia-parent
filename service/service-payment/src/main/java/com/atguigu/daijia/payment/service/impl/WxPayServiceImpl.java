package com.atguigu.daijia.payment.service.impl;

import com.alibaba.fastjson.JSON;
import com.atguigu.daijia.common.execption.GuiguException;
import com.atguigu.daijia.common.result.ResultCodeEnum;
import com.atguigu.daijia.model.entity.payment.PaymentInfo;
import com.atguigu.daijia.model.form.payment.PaymentInfoForm;
import com.atguigu.daijia.model.vo.payment.WxPrepayVo;
import com.atguigu.daijia.payment.config.WxPayV3Properties;
import com.atguigu.daijia.payment.mapper.PaymentInfoMapper;
import com.atguigu.daijia.payment.service.WxPayService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wechat.pay.java.core.RSAAutoCertificateConfig;
import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import com.wechat.pay.java.service.payments.jsapi.model.*;
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
