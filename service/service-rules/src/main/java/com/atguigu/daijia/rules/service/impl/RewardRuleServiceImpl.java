package com.atguigu.daijia.rules.service.impl;

import com.atguigu.daijia.model.form.rules.RewardRuleRequest;
import com.atguigu.daijia.model.form.rules.RewardRuleRequestForm;
import com.atguigu.daijia.model.vo.rules.RewardRuleResponse;
import com.atguigu.daijia.model.vo.rules.RewardRuleResponseVo;
import com.atguigu.daijia.rules.mapper.RewardRuleMapper;
import com.atguigu.daijia.rules.service.RewardRuleService;
import com.atguigu.daijia.rules.utils.DroolsHelper;
import lombok.extern.slf4j.Slf4j;
import org.kie.api.event.rule.AfterMatchFiredEvent;
import org.kie.api.event.rule.DefaultAgendaEventListener;
import org.kie.api.runtime.KieSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;

@Slf4j
@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class RewardRuleServiceImpl implements RewardRuleService {

    private static final String RULES_Driver_RULES_DRL = "rules/RewardRule.drl";


    @Override
    public RewardRuleResponseVo calculateOrderRewardFee(RewardRuleRequestForm rewardRuleRequestForm) {
        //封装传入的参数对象
        RewardRuleRequest rewardRuleRequest = new RewardRuleRequest();
        rewardRuleRequest.setOrderNum(rewardRuleRequestForm.getOrderNum());
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        rewardRuleRequest.setStartTime(sdf.format(rewardRuleRequestForm.getStartTime()));

        //创建规则引擎对象
        KieSession kieSession = DroolsHelper.loadForRule(RULES_Driver_RULES_DRL);

        //封装返回对象
        RewardRuleResponse rewardRuleResponse = new RewardRuleResponse();
        kieSession.setGlobal("rewardRuleResponse", rewardRuleResponse);

        // ✅ 创建集合用于记录被触发的规则ID
        final StringBuilder ruleIdHolder = new StringBuilder();

        // ✅ 添加监听器，捕获触发规则
        kieSession.addEventListener(new DefaultAgendaEventListener() {
            @Override
            public void afterMatchFired(AfterMatchFiredEvent event) {
                String ruleId = event.getMatch().getRule().getPackageName() + "." +
                        event.getMatch().getRule().getName();
                ruleIdHolder.append(ruleId);
            }
        });

        //设置对象，触发规则
        kieSession.insert(rewardRuleRequest);
        kieSession.fireAllRules();

        //终止会话
        kieSession.dispose();

        //封装VO对象
        RewardRuleResponseVo rewardRuleResponseVo = new RewardRuleResponseVo();
        rewardRuleResponseVo.setRewardAmount(rewardRuleResponse.getRewardAmount());
        return rewardRuleResponseVo;

    }
}
