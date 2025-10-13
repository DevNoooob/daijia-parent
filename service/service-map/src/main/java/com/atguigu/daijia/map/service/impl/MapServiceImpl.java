package com.atguigu.daijia.map.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.atguigu.daijia.common.execption.GuiguException;
import com.atguigu.daijia.common.result.ResultCodeEnum;
import com.atguigu.daijia.map.service.MapService;
import com.atguigu.daijia.model.form.map.CalculateDrivingLineForm;
import com.atguigu.daijia.model.vo.map.DrivingLineVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class MapServiceImpl implements MapService {

    @Value("${tencent.map.key}")
    private String key;

    @Autowired
    private RestTemplate restTemplate;


    @Override
    public DrivingLineVo calculateDrivingLine(CalculateDrivingLineForm calculateDrivingLineForm) {

        //Tencent`s API doc:https://lbs.qq.com/service/webService/webServiceGuide/route/webServiceRoute

        //定义调用腾讯地址
        String Url = "https://apis.map.qq.com/ws/direction/v1/driving/?from={from}&output=json&to={to}&key={key}";

        //封装传递参数
        Map<String, String> map = new HashMap();

        //开始位置经纬度
        map.put("from", calculateDrivingLineForm.getStartPointLatitude() + "," + calculateDrivingLineForm.getStartPointLongitude());
        //目的位置经纬度
        map.put("to", calculateDrivingLineForm.getEndPointLatitude() + "," + calculateDrivingLineForm.getEndPointLongitude());
        //key值
        map.put("key", key);

        //使用restTemplate给腾讯API发生GET请求
        JSONObject result = restTemplate.getForObject(Url, JSONObject.class, map);

        //处理返回结果

        //1、判断调用是否成功
        int status = result.getIntValue("status");
        if (status != 0) {//fail
            System.out.println(status);
            System.out.println(result.getString("message"));
            throw new GuiguException(ResultCodeEnum.MAP_FAIL);
        }

        //2、获取返回路线的相关信息
        JSONObject route =
                result.getJSONObject("result").getJSONArray("routes").getJSONObject(0);

        DrivingLineVo drivingLineVo = new DrivingLineVo();
        //预估时间
        drivingLineVo.setDuration(route.getBigDecimal("duration"));
        //路程
        BigDecimal distance = route.getBigDecimal("distance");
        int compare = distance.compareTo(BigDecimal.valueOf(1000));
        if (compare > 0) {
            distance = distance.divideToIntegralValue(BigDecimal.valueOf(1000)).setScale(2, RoundingMode.UP);
        }
        drivingLineVo.setDistance(distance);

        //路线
        drivingLineVo.setPolyline(route.getJSONArray("polyline"));
        return drivingLineVo;
    }
}
