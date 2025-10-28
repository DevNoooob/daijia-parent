package com.atguigu.daijia.rules.service.impl;

import com.atguigu.daijia.model.form.rules.FeeRuleRequest;
import com.atguigu.daijia.model.form.rules.FeeRuleRequestForm;
import com.atguigu.daijia.model.vo.rules.FeeRuleResponse;
import com.atguigu.daijia.model.vo.rules.FeeRuleResponseVo;
import com.atguigu.daijia.rules.mapper.FeeRuleMapper;
import com.atguigu.daijia.rules.service.FeeRuleService;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class FeeRuleServiceImpl implements FeeRuleService {

    @Autowired
    private KieContainer kieContainer;


    @Override
    public FeeRuleResponseVo calculateOrderFee(FeeRuleRequestForm feeRuleRequestForm) {
        //封装输入对象
        FeeRuleRequest feeRuleRequest = new FeeRuleRequest();
        feeRuleRequest.setDistance(feeRuleRequestForm.getDistance());
        feeRuleRequest.setStartTime(new DateTime(feeRuleRequestForm.getStartTime()).toString("HH:mm:ss"));
        feeRuleRequest.setWaitMinute(feeRuleRequestForm.getWaitMinute());
        //Drools使用
        //开启
        KieSession kieSession = kieContainer.newKieSession();
        FeeRuleResponse feeRuleResponse = new FeeRuleResponse();
        //设置全局变量
        kieSession.setGlobal("feeRuleResponse", feeRuleResponse);
        //设置订单对象
        kieSession.insert(feeRuleRequest);
        //触发规则
        kieSession.fireAllRules();
        //终止会话
        kieSession.dispose();

        //封装数据到FeeRuleResponseVo返回
        FeeRuleResponseVo feeRuleResponseVo = new FeeRuleResponseVo();
        BeanUtils.copyProperties(feeRuleResponse, feeRuleResponseVo);
        feeRuleResponseVo.setFeeRuleId(1L);

        return feeRuleResponseVo;
    }
}
