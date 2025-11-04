package com.atguigu.daijia.coupon.service.impl;

import com.atguigu.daijia.common.constant.RedisConstant;
import com.atguigu.daijia.common.execption.GuiguException;
import com.atguigu.daijia.common.result.ResultCodeEnum;
import com.atguigu.daijia.coupon.mapper.CouponInfoMapper;
import com.atguigu.daijia.coupon.mapper.CustomerCouponMapper;
import com.atguigu.daijia.coupon.service.CouponInfoService;
import com.atguigu.daijia.model.entity.coupon.CouponInfo;
import com.atguigu.daijia.model.entity.coupon.CustomerCoupon;
import com.atguigu.daijia.model.form.coupon.UseCouponForm;
import com.atguigu.daijia.model.vo.base.PageVo;
import com.atguigu.daijia.model.vo.coupon.AvailableCouponVo;
import com.atguigu.daijia.model.vo.coupon.NoReceiveCouponVo;
import com.atguigu.daijia.model.vo.coupon.NoUseCouponVo;
import com.atguigu.daijia.model.vo.coupon.UsedCouponVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class CouponInfoServiceImpl extends ServiceImpl<CouponInfoMapper, CouponInfo> implements CouponInfoService {

    @Autowired
    private CouponInfoMapper couponInfoMapper;

    @Autowired
    private CustomerCouponMapper customerCouponMapper;

    @Autowired
    private RedissonClient redissonClient;

    @Override
    public PageVo<NoReceiveCouponVo> findNoReceivePage(Long customerId, Long page, Long limit) {

        PageVo<NoReceiveCouponVo> noReceiveCouponVoPageVo = couponInfoMapper.findNoReceivePage(new Page<>(page, limit), customerId);
        noReceiveCouponVoPageVo.setPage(page);
        noReceiveCouponVoPageVo.setLimit(limit);

        return noReceiveCouponVoPageVo;
    }

    @Override
    public PageVo<NoUseCouponVo> findNoUsePage(Long customerId, Long page, Long limit) {
        PageVo<NoUseCouponVo> noUseCouponVoPageVo = couponInfoMapper.findNoUsePage(new Page<>(page, limit), customerId);
        noUseCouponVoPageVo.setPage(page);
        noUseCouponVoPageVo.setLimit(limit);

        return noUseCouponVoPageVo;
    }

    @Override
    public PageVo<UsedCouponVo> findUsedPage(Long customerId, Long page, Long limit) {
        PageVo<UsedCouponVo> usedCouponVoPageVo = couponInfoMapper.findUsedPage(new Page<>(page, limit), customerId);
        usedCouponVoPageVo.setPage(page);
        usedCouponVoPageVo.setLimit(limit);

        return usedCouponVoPageVo;
    }

    @Override
    public Boolean receiveCoupon(Long customerId, Long couponId) {
        // 基于优惠券ID加锁
        RLock lock = redissonClient.getLock(RedisConstant.COUPON_LOCK + couponId);

        try {
            // 尝试加锁，最多等待3秒，锁自动过期时间10秒
            if (lock.tryLock(3, 10, TimeUnit.SECONDS)) {

                // 1. 查询优惠券
                CouponInfo couponInfo = couponInfoMapper.selectById(couponId);
                if (couponInfo == null) {
                    throw new GuiguException(ResultCodeEnum.DATA_ERROR);
                }

                // 2. 判断是否过期
                if (couponInfo.getExpireTime().before(new Date())) {
                    throw new GuiguException(ResultCodeEnum.COUPON_EXPIRE);
                }

                // 3. 检查库存
                if (couponInfo.getPublishCount() != 0 && couponInfo.getReceiveCount() >= couponInfo.getPublishCount()) {
                    throw new GuiguException(ResultCodeEnum.COUPON_LESS);
                }

                // 4. 检查个人限领
                if (couponInfo.getPerLimit() > 0) {
                    Long received = customerCouponMapper.selectCount(
                            new LambdaQueryWrapper<CustomerCoupon>()
                                    .eq(CustomerCoupon::getCouponId, couponId)
                                    .eq(CustomerCoupon::getCustomerId, customerId)
                    );
                    if (received >= couponInfo.getPerLimit()) {
                        throw new GuiguException(ResultCodeEnum.COUPON_USER_LIMIT);
                    }
                }

                // 5. 扣减库存
                int row = couponInfoMapper.updateReceiveCount(couponId);
                if (row == 1) {
                    this.saveCustomerCoupon(customerId, couponId, couponInfo.getExpireTime());
                }

                return true;
            } else {
                throw new GuiguException(ResultCodeEnum.DATA_ERROR); // 获取锁超时
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("加锁中断", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    //获取未使用的最佳优惠券信息
    @Override
    public List<AvailableCouponVo> findAvailableCoupon(Long customerId, BigDecimal orderAmount) {
        //1、创建list集合，存储最终返回数据
        List<AvailableCouponVo> availableCouponVoList = new ArrayList<>();

        //2、根据乘客id，获取乘客已领取未使用的优惠券列表
        List<NoUseCouponVo> noUseList = couponInfoMapper.findNoUseList(customerId);

        //3、遍历乘客未使用优惠券列表，得到每个优惠券
        //3.1 判断优惠券类型：现金券 和 折扣券
        List<NoUseCouponVo> moneyList =
                noUseList.stream().filter(item -> item.getCouponType() == 1).toList();
        List<NoUseCouponVo> discountList =
                noUseList.stream().filter(item -> item.getCouponType() == 2).toList();


        //3.2 是现金券
        //判断现金券是否满足使用条件
        for (NoUseCouponVo noUseCouponVo : moneyList) {
            //判断是否有门槛
            //获取减免金额
            BigDecimal reduceAmount = noUseCouponVo.getAmount();

            //门槛
            BigDecimal threshold = noUseCouponVo.getConditionAmount();

            //1：没有门槛 == 0，只需订单金额 > 优惠的减免金额
            if (threshold.doubleValue() == 0 && orderAmount.compareTo(reduceAmount) > 0) {
                availableCouponVoList.add(this.buildBestNoUseCouponVo(noUseCouponVo, reduceAmount));
            }

            //2：有门槛，则需要订单金额 > 门槛金额
            if (threshold.doubleValue() > 0 && orderAmount.compareTo(threshold) > 0) {
                availableCouponVoList.add(this.buildBestNoUseCouponVo(noUseCouponVo, reduceAmount));
            }
        }
        //3.3是折扣券
        //判断折扣券是否满足使用条件
        for (NoUseCouponVo noUseCouponVo : discountList) {
            //打折之后金额
            BigDecimal afterDiscountAmount = orderAmount.multiply(noUseCouponVo.getDiscount()
                    .divide(BigDecimal.TEN, 2, RoundingMode.HALF_UP));
            BigDecimal reduceAmount = orderAmount.subtract(afterDiscountAmount);

            //门槛
            BigDecimal threshold = noUseCouponVo.getConditionAmount();

            //1：没有门槛 == 0，只需订单金额 > 优惠的减免金额
            if (threshold.doubleValue() == 0) {
                availableCouponVoList.add(this.buildBestNoUseCouponVo(noUseCouponVo, reduceAmount));
            }

            //2：有门槛，则需要订单金额 > 门槛金额

            if (threshold.doubleValue() > 0 && orderAmount.compareTo(threshold) > 0) {
                availableCouponVoList.add(this.buildBestNoUseCouponVo(noUseCouponVo, reduceAmount));
            }
        }

        //4、把满足条件的优惠券放到list集合
        //按优惠金额排序
        availableCouponVoList.sort(
                Comparator.comparing(
                        AvailableCouponVo::getReduceAmount,
                        Comparator.nullsLast(BigDecimal::compareTo)
                ).reversed()
        );

        return availableCouponVoList;
    }

    //使用优惠券
    @Override
    public BigDecimal useCoupon(UseCouponForm useCouponForm) {
        //1、根据乘客优惠券id获取乘客优惠券信息
        CustomerCoupon customerCoupon =
                customerCouponMapper.selectById(useCouponForm.getCustomerCouponId());
        if (customerCoupon == null) {
            throw new GuiguException(ResultCodeEnum.DATA_ERROR);
        }

        //2、根据优惠券id获取优惠券信息
        CouponInfo couponInfo =
                couponInfoMapper.selectById(customerCoupon.getCouponId());
        if (couponInfo == null) {
            throw new GuiguException(ResultCodeEnum.DATA_ERROR);
        }

        //3、判断优惠券是否是当前乘客所持有的优惠券
        if (!Objects.equals(customerCoupon.getCustomerId(), useCouponForm.getCustomerId())) {
            throw new GuiguException(ResultCodeEnum.ILLEGAL_REQUEST);
        }

        //4、判断是否具备优惠券使用条件
        //现金券 折扣券 根据使用门槛判断
        BigDecimal reduceAmount = null;

        BigDecimal orderAmount = useCouponForm.getOrderAmount();
        //1、现金券
        if (couponInfo.getCouponType() == 1) {
            //门槛
            BigDecimal threshold = couponInfo.getConditionAmount();

            //1：没有门槛 == 0，只需订单金额 > 优惠的减免金额
            if (threshold.doubleValue() == 0 && orderAmount.compareTo(couponInfo.getAmount()) > 0) {
                reduceAmount = couponInfo.getAmount();
            }

            //2：有门槛，则需要订单金额 > 门槛金额
            if (threshold.doubleValue() > 0 && orderAmount.compareTo(threshold) > 0) {
                reduceAmount = couponInfo.getAmount();
            }

        } else {//2、折扣券
            //打折之后金额
            BigDecimal afterDiscountAmount = orderAmount.multiply(couponInfo.getDiscount()
                    .divide(BigDecimal.TEN, 2, RoundingMode.HALF_UP));

            //门槛
            BigDecimal threshold = couponInfo.getConditionAmount();

            //1：没有门槛 == 0，只需订单金额 > 优惠的减免金额
            if (threshold.doubleValue() == 0) {
                reduceAmount = orderAmount.subtract(afterDiscountAmount);
            }

            //2：有门槛，则需要订单金额 > 门槛金额

            if (threshold.doubleValue() > 0 && orderAmount.compareTo(threshold) > 0) {
                reduceAmount = orderAmount.subtract(afterDiscountAmount);
            }
        }

        //5、如果满足条件，就更新两张表的数据
        if (reduceAmount != null) {

            //更新coupon_info使用数量
            //使用基于优惠券ID的分布式锁，保证并发安全
            RLock lock = redissonClient.getLock("lock:coupon:" + couponInfo.getId());

            try {
                if (lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                    int rows = couponInfoMapper.updateUseCount(couponInfo.getId());
                    if (rows == 0) {
                        throw new GuiguException(ResultCodeEnum.DATA_ERROR);
                    }
                    //更新customer_coupon表
                    customerCouponMapper.update(null,
                            new LambdaUpdateWrapper<CustomerCoupon>()
                                    .eq(CustomerCoupon::getId, customerCoupon.getId())
                                    .set(CustomerCoupon::getUsedTime, new Date())
                                    .set(CustomerCoupon::getStatus, 2)
                                    .set(CustomerCoupon::getOrderId, useCouponForm.getOrderId())
                    );

                    return reduceAmount;
                } else {
                    // 超时未拿到锁，可选择返回 null 或抛出异常
                    throw new GuiguException(ResultCodeEnum.SERVICE_ERROR);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GuiguException(ResultCodeEnum.SERVICE_ERROR);
            } finally {
                lock.unlock();
            }
        }
        return null;
    }

    private AvailableCouponVo buildBestNoUseCouponVo(NoUseCouponVo noUseCouponVo, BigDecimal reduceAmount) {
        AvailableCouponVo availableCouponVo = new AvailableCouponVo();
        BeanUtils.copyProperties(noUseCouponVo, availableCouponVo);
        availableCouponVo.setCouponId(noUseCouponVo.getId());
        availableCouponVo.setReduceAmount(reduceAmount);
        return availableCouponVo;
    }

    private void saveCustomerCoupon(Long customerId, Long couponId, Date expireTime) {
        CustomerCoupon customerCoupon = new CustomerCoupon();
        customerCoupon.setCustomerId(customerId);
        customerCoupon.setCouponId(couponId);
        customerCoupon.setExpireTime(expireTime);
        customerCoupon.setReceiveTime(new Date());
        customerCoupon.setStatus(1);
        customerCouponMapper.insert(customerCoupon);
    }
}
