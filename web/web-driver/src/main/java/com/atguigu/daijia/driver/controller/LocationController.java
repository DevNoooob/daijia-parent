package com.atguigu.daijia.driver.controller;

import com.atguigu.daijia.common.login.MaYueLogin;
import com.atguigu.daijia.common.result.Result;
import com.atguigu.daijia.common.util.AuthContextHolder;
import com.atguigu.daijia.driver.service.LocationService;
import com.atguigu.daijia.model.form.map.OrderServiceLocationForm;
import com.atguigu.daijia.model.form.map.UpdateDriverLocationForm;
import com.atguigu.daijia.model.form.map.UpdateOrderLocationForm;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.units.qual.A;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@Tag(name = "位置API接口管理")
@RestController
@RequestMapping(value="/location")
@SuppressWarnings({"unchecked", "rawtypes"})
public class LocationController {

    @Autowired
    private LocationService locationService;


    @Operation(summary = "更新司机位置信息")
    @MaYueLogin
    @PostMapping("/updateDriverLocation")
    public Result<Boolean> updateDriverLocation (@RequestBody UpdateDriverLocationForm updateDriverLocationForm){
        Long driverId = AuthContextHolder.getUserId();//司机id
        updateDriverLocationForm.setDriverId(driverId);
        return Result.ok(locationService.updateDriverLocation(updateDriverLocationForm));
    }

    @Operation(summary = "删除司机位置信息")
    @MaYueLogin
    @DeleteMapping("/removeDriverLocation/{driverId}")
    public Result<Boolean> removeDriverLocation(@PathVariable("driverId") Long driverId){
        return Result.ok(locationService.removeDriverLocation(driverId));
    }

    @Operation(summary = "司机赶往代驾起始点：更新订单位置到Redis缓存")
    @MaYueLogin
    @PostMapping("/updateOrderLocationToCache")
    public Result<Boolean> updateOrderLocationToCache(@RequestBody UpdateOrderLocationForm updateOrderLocationForm){
        return Result.ok(locationService.updateOrderLocationToCache(updateOrderLocationForm));
    }

    @Operation(summary = "开始代驾服务：保存代驾服务订单位置")
    @PostMapping("/saveOrderServiceLocation")
    public Result<Boolean> saveOrderServiceLocation(@RequestBody List<OrderServiceLocationForm> orderServiceLocationFormList) {
        return Result.ok(locationService.saveOrderServiceLocation(orderServiceLocationFormList));
    }
}