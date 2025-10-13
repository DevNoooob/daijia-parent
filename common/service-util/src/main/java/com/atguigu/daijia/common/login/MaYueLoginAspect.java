package com.atguigu.daijia.common.login;

import com.atguigu.daijia.common.constant.RedisConstant;
import com.atguigu.daijia.common.execption.GuiguException;
import com.atguigu.daijia.common.result.ResultCodeEnum;
import com.atguigu.daijia.common.util.AuthContextHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;


@Component
@Aspect //切面类
public class MaYueLoginAspect {

    @Autowired
    private RedisTemplate redisTemplate;

    //环绕通知，登录判断
    //切入点表达式
    @Around("execution(* com.atguigu.daijia.*.controller.*.*(..)) && @annotation(maYueLogin)")
    public Object login(ProceedingJoinPoint proceedingJoinPoint, MaYueLogin maYueLogin) throws Throwable {

        //1、获取request对象
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        ServletRequestAttributes sra = (ServletRequestAttributes) attributes;

        //2、从请求头中获取token
        String token = sra.getRequest().getHeader("token");

        //3、判断token是否为空，如果为空，返回登录提示
        if (!StringUtils.hasText(token)) {
            throw new GuiguException(ResultCodeEnum.LOGIN_AUTH);
        }

        //4、token不为空，查询redis
        String customerId = (String) redisTemplate.opsForValue().get(RedisConstant.USER_LOGIN_KEY_PREFIX + token);

        //5、查询redis对应的用户id，把用户id放到ThreadLocal里
        if (StringUtils.hasText(customerId)) {
            AuthContextHolder.setUserId(Long.parseLong(customerId));
        }

        //6、执行业务方法
        return proceedingJoinPoint.proceed();
    }
}
