package com.atguigu.daijia.customer.controller;

import com.atguigu.daijia.common.constant.RedisConstant;
import com.atguigu.daijia.common.execption.GuiguException;
import com.atguigu.daijia.common.login.MaYueLogin;
import com.atguigu.daijia.common.result.Result;
import com.atguigu.daijia.common.result.ResultCodeEnum;
import com.atguigu.daijia.common.util.AuthContextHolder;
import com.atguigu.daijia.customer.client.CustomerInfoFeignClient;
import com.atguigu.daijia.customer.service.CustomerService;
import com.atguigu.daijia.model.form.customer.UpdateWxPhoneForm;
import com.atguigu.daijia.model.vo.customer.CustomerLoginVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@Slf4j
@Tag(name = "客户API接口管理")
@RestController
@RequestMapping("/customer")
@SuppressWarnings({"unchecked", "rawtypes"})
public class CustomerController {

    @Autowired
    private CustomerService customerService;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private CustomerInfoFeignClient client;

    @Operation(summary = "小程序授权登录")
    @GetMapping("/login/{code}")
    public Result<String> login(@PathVariable String code) {
        return Result.ok(customerService.login(code));
    }

    @Operation(summary = "获取客户登录信息")
    @MaYueLogin
    @GetMapping("/getCustomerLoginInfo")
    public Result<CustomerLoginVo> getCustomerLoginInfo() {
        //1、从ThreadLocal获取用户id
        Long customerId = AuthContextHolder.getUserId();

        //调用service实现
        //5、返回用户信息
        return Result.ok(customerService.getCustomerInfo(customerId));

    }


    @Operation(summary = "更新用户微信手机号")
    @MaYueLogin
    @PostMapping("/updateWxPhone")
    public Result<Boolean> updateWxPhone(@RequestBody UpdateWxPhoneForm updateWxPhoneForm) {
        updateWxPhoneForm.setCustomerId(AuthContextHolder.getUserId());
        return Result.ok(customerService.updateWxPhoneNumber(updateWxPhoneForm));
    }


}

