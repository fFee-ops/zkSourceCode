package com.itheima.car.service.impl;

import com.itheima.service.CarService;
import org.apache.dubbo.config.annotation.Service;

/*****
 * @Author:
 * @Description:
 ****/
@Service
public class CarServiceImpl implements CarService {

    /******
     * 车辆信息获取
     * @return
     */
    @Override
    public String cartInfo(String name){
        String carInfo = "Version2-比亚迪 唐 白色  车牌："+name;
        return carInfo;
    }
}
