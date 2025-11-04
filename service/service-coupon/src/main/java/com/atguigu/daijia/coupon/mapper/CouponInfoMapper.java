package com.atguigu.daijia.coupon.mapper;

import com.atguigu.daijia.model.entity.coupon.CouponInfo;
import com.atguigu.daijia.model.vo.base.PageVo;
import com.atguigu.daijia.model.vo.coupon.NoReceiveCouponVo;
import com.atguigu.daijia.model.vo.coupon.NoUseCouponVo;
import com.atguigu.daijia.model.vo.coupon.UsedCouponVo;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CouponInfoMapper extends BaseMapper<CouponInfo> {

    PageVo<NoReceiveCouponVo> findNoReceivePage(Page<Object> objectPage, @Param("customerId") Long customerId);

    PageVo<NoUseCouponVo> findNoUsePage(Page<Object> objectPage, @Param("customerId") Long customerId);

    PageVo<UsedCouponVo> findUsedPage(Page<Object> objectPage, @Param("customerId") Long customerId);

    int updateReceiveCount(Long couponId);

    List<NoUseCouponVo> findNoUseList(@Param("customerId") Long customerId);

    int updateUseCount(@Param("id") Long id);
}
