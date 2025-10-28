package com.atguigu.daijia.customer.client;

import com.atguigu.daijia.common.result.Result;
import com.atguigu.daijia.model.form.customer.UpdateWxPhoneForm;
import com.atguigu.daijia.model.vo.customer.CustomerLoginVo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(value = "service-customer")
public interface CustomerInfoFeignClient {

    @GetMapping("/customer/info/login/{code}")
    Result<Long> login(@PathVariable String code);

    @GetMapping("/customer/info/getCustomerLoginInfo/{customerId}")
    Result<CustomerLoginVo> getCustomerLoginInfo(@PathVariable("customerId") Long customerId);

    /**
     * 更新客户微信手机号
     *
     * @param updateWxPhoneForm
     * @return
     */
    @PostMapping("/customer/info/updateWxPhoneNumber")
    Result<Boolean> updateWxPhoneNumber(@RequestBody UpdateWxPhoneForm updateWxPhoneForm);

    /**
     * 根据id获取乘客openId
     * @param customerId
     * @return
     */
    @GetMapping("/customer/info/getCustomerOpenId/{customerId}")
    Result<String> getCustomerOpenId(@PathVariable("customerId") Long customerId);
}