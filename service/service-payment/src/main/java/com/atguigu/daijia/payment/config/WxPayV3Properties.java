package com.atguigu.daijia.payment.config;

import com.wechat.pay.java.core.RSAAutoCertificateConfig;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "wx.v3pay")
@Data
public class WxPayV3Properties {

    private String appid;
    public String merchantid;
    public String privateKeyPath;
    public String merchantSerialNumber;
    public String apiv3key;
    public String notifyUrl;

    @Bean
    public RSAAutoCertificateConfig getConfig() {
        return new RSAAutoCertificateConfig.Builder()
                .merchantId(this.getMerchantid())
                .privateKeyFromPath(this.privateKeyPath)
                .merchantSerialNumber(this.getMerchantSerialNumber())
                .apiV3Key(this.getApiv3key())
                .build();
    }
}
