package com.atguigu.daijia.dispatch.xxl.job;

import com.atguigu.daijia.dispatch.mapper.XxlJobLogMapper;
import com.atguigu.daijia.dispatch.service.NewOrderService;
import com.atguigu.daijia.model.entity.dispatch.XxlJobLog;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class JobHandler {

    @Autowired
    private XxlJobLogMapper xxlJobLogMapper;

    @Autowired
    private NewOrderService newOrderService;

    @XxlJob("newOrderTaskHandler")
    public void newOrderTaskHandler () {
        System.out.println("我被成功调用了" +
                "我被成功调用了" +
                "我被成功调用了" +
                "我被成功调用了" +
                "我被成功调用了" +
                "我被成功调用了");
        //记录任务调度日志
        XxlJobLog xxlJobLog = new XxlJobLog();
        xxlJobLog.setJobId(XxlJobHelper.getJobId());
        long beginTime = System.currentTimeMillis();
        try {
            //执行任务：搜索附近代驾司机
            //TODO
            newOrderService.executeTask(XxlJobHelper.getJobId());

            //成功状态值
            xxlJobLog.setStatus(1);
        } catch (Exception e) {
            //失败状态值
            xxlJobLog.setStatus(0);
            xxlJobLog.setError(e.getMessage());
            e.printStackTrace();
        } finally {
            long endTime = System.currentTimeMillis();
            xxlJobLog.setTimes(endTime - beginTime);
            xxlJobLogMapper.insert(xxlJobLog);
        }
    }
}
