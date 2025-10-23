package com.atguigu.daijia.map.service.impl;

import com.atguigu.daijia.common.constant.RedisConstant;
import com.atguigu.daijia.common.constant.SystemConstant;
import com.atguigu.daijia.common.execption.GuiguException;
import com.atguigu.daijia.common.result.ResultCodeEnum;
import com.atguigu.daijia.common.util.LocationUtil;
import com.atguigu.daijia.driver.client.DriverInfoFeignClient;
import com.atguigu.daijia.map.repository.OrderServiceLocationRepository;
import com.atguigu.daijia.map.service.LocationService;
import com.atguigu.daijia.model.entity.driver.DriverSet;
import com.atguigu.daijia.model.entity.map.OrderServiceLocation;
import com.atguigu.daijia.model.form.map.OrderServiceLocationForm;
import com.atguigu.daijia.model.form.map.SearchNearByDriverForm;
import com.atguigu.daijia.model.form.map.UpdateDriverLocationForm;
import com.atguigu.daijia.model.form.map.UpdateOrderLocationForm;
import com.atguigu.daijia.model.vo.map.NearByDriverVo;
import com.atguigu.daijia.model.vo.map.OrderLocationVo;
import com.atguigu.daijia.model.vo.map.OrderServiceLastLocationVo;
import com.atguigu.daijia.order.client.OrderInfoFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.geo.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

@Slf4j
@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class LocationServiceImpl implements LocationService {

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private DriverInfoFeignClient driverInfoFeignClient;

    @Autowired
    private OrderInfoFeignClient orderInfoFeignClient;

    @Autowired
    private OrderServiceLocationRepository orderServiceLocationRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Override
    public Boolean updateDriverLocation(UpdateDriverLocationForm updateDriverLocationForm) {
        //把司机位置信息添加到redis里的geo
        Point point = new Point(updateDriverLocationForm.getLongitude().doubleValue(),
                updateDriverLocationForm.getLatitude().doubleValue());
        redisTemplate.opsForGeo().add(RedisConstant.DRIVER_GEO_LOCATION, point, updateDriverLocationForm.getDriverId().toString());
        return Boolean.TRUE;
    }

    @Override
    public Boolean removeDriverLocation(Long driverId) {
        redisTemplate.opsForGeo().remove(RedisConstant.DRIVER_GEO_LOCATION, driverId.toString());
        return Boolean.TRUE;
    }

    @Override
    public List<NearByDriverVo> searchNearByDriver(SearchNearByDriverForm searchNearByDriverForm) {

        Circle circle = new Circle(
                new Point(searchNearByDriverForm.getLongitude().doubleValue(), searchNearByDriverForm.getLatitude().doubleValue()),
                new Distance(SystemConstant.NEARBY_DRIVER_RADIUS, RedisGeoCommands.DistanceUnit.KILOMETERS));

        RedisGeoCommands.GeoRadiusCommandArgs args =
                RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                        .includeDistance()      //包含距离
                        .includeCoordinates()   //包含坐标
                        .sortAscending();       //升序


        GeoResults<RedisGeoCommands.GeoLocation<String>> results =
                redisTemplate.opsForGeo().radius(RedisConstant.DRIVER_GEO_LOCATION, circle, args);

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = results.getContent();

        List<NearByDriverVo> nearByDriverVoList = new ArrayList<>();
        if (!CollectionUtils.isEmpty(content)) {
            Iterator<GeoResult<RedisGeoCommands.GeoLocation<String>>> iterator = content.iterator();
            while (iterator.hasNext()) {
                GeoResult<RedisGeoCommands.GeoLocation<String>> item = iterator.next();

                Long driverId = Long.parseLong(item.getContent().getName());

                DriverSet driverSet = driverInfoFeignClient.getDriverSet(driverId).getData();
                BigDecimal orderDistance = driverSet.getOrderDistance();

                if (orderDistance.doubleValue() != 0 && orderDistance.subtract(searchNearByDriverForm.getMileageDistance()).doubleValue() < 0) {
                    continue;
                }

                BigDecimal currentDistance =
                        new BigDecimal(item.getDistance().getValue()).setScale(2, RoundingMode.HALF_UP);
                BigDecimal acceptDistance = driverSet.getAcceptDistance();
                if (acceptDistance.doubleValue() != 0 && acceptDistance.subtract(currentDistance).doubleValue() < 0) {
                    continue;
                }

                NearByDriverVo nearByDriverVo = new NearByDriverVo();
                nearByDriverVo.setDriverId(driverId);
                nearByDriverVo.setDistance(currentDistance);

                nearByDriverVoList.add(nearByDriverVo);
            }
        }


        return nearByDriverVoList;
    }

    //司机赶往代价起始点：更新订单地址到缓存
    @Override
    public Boolean updateOrderLocationToCache(UpdateOrderLocationForm updateOrderLocationForm) {
        String key = RedisConstant.UPDATE_ORDER_LOCATION + updateOrderLocationForm.getOrderId();

        OrderLocationVo orderLocationVo = new OrderLocationVo();
        orderLocationVo.setLongitude(updateOrderLocationForm.getLongitude());
        orderLocationVo.setLatitude(updateOrderLocationForm.getLatitude());

        redisTemplate.opsForValue().set(key, orderLocationVo);
        return Boolean.TRUE;
    }

    @Override
    public OrderLocationVo getCacheOrderLocation(Long orderId) {
        String key = RedisConstant.UPDATE_ORDER_LOCATION + orderId;
        OrderLocationVo orderLocationVo = (OrderLocationVo) redisTemplate.opsForValue().get(key);

        if (orderLocationVo == null) {
            throw new GuiguException(ResultCodeEnum.DATA_ERROR);
        }

        return orderLocationVo;
    }

    @Override
    public Boolean saveOrderServiceLocation(List<OrderServiceLocationForm> orderServiceLocationFormList) {

        List<OrderServiceLocation> orderServiceLocationList = new ArrayList<>();

        orderServiceLocationFormList.forEach(orderServiceLocationForm -> {
            OrderServiceLocation orderServiceLocation = new OrderServiceLocation();
            BeanUtils.copyProperties(orderServiceLocationForm, orderServiceLocation);
            orderServiceLocation.setId(ObjectId.get().toString());
            orderServiceLocation.setCreateTime(new Date());
            orderServiceLocationList.add(orderServiceLocation);
        });
        orderServiceLocationRepository.saveAll(orderServiceLocationList);
        return Boolean.TRUE;
    }

    @Override
    public OrderServiceLastLocationVo getOrderServiceLastLocation(Long orderId) {

        Query query = new Query();
        query.addCriteria(Criteria.where("orderId").is(orderId));
        query.with(Sort.by(Sort.Direction.DESC, "createTime"));
        query.limit(1);

        OrderServiceLocation orderServiceLocation = mongoTemplate.findOne(query, OrderServiceLocation.class);
        OrderServiceLastLocationVo orderServiceLastLocationVo = new OrderServiceLastLocationVo();

        if (orderServiceLocation != null) {
            BeanUtils.copyProperties(orderServiceLocation, orderServiceLastLocationVo);
            return orderServiceLastLocationVo;
        } else {
            throw new GuiguException(ResultCodeEnum.DATA_ERROR);
        }
    }

    @Override
    public BigDecimal calculateOrderRealDistance(Long orderId) {
        //1、根据订单id获取代驾订单位置信息，根据创建时间排序（升序）
        //查询MongoDB
        //第一种方式
//        OrderServiceLocation orderServiceLocation = new OrderServiceLocation();
//        orderServiceLocation.setOrderId(orderId);
//        orderServiceLocationRepository.findAll(Example.of(orderServiceLocation),Sort.by(Sort.Direction.ASC, "createTime"));
        //第二种方式
        List<OrderServiceLocation> list =
                orderServiceLocationRepository.findByOrderIdOrderByCreateTimeAsc(orderId);

        //2、第一步查询返回订单的位置信息list集合
        //把list集合遍历，得到每个位置信息，计算两个位置距离
        //把计算所有距离相加操作
        double totalDistance = 0;
        if (!CollectionUtils.isEmpty(list)) {
            for (int i = 0, size = list.size(); i < size; i++) {
                OrderServiceLocation location1 = list.get(i);
                OrderServiceLocation location2 = list.get(i + 1);

                double distance = LocationUtil.getDistance(
                        location1.getLatitude().doubleValue(), location1.getLongitude().doubleValue(),
                        location2.getLatitude().doubleValue(), location2.getLongitude().doubleValue()
                );
                totalDistance += distance;
            }
        }

        //TODO 为了测试，不好测试实际代驾距离，模拟数据 实际距离=预估距离+5km
        if (totalDistance == 0) {
            orderInfoFeignClient.getOrderInfo(orderId).getData().getExpectDistance().add(new BigDecimal(5));
        }


        return BigDecimal.valueOf(totalDistance);
    }
}
