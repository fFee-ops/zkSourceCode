package com.itheima.car.controller;

import com.itheima.service.CarService;
import org.apache.dubbo.config.annotation.Reference;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*****
 * @Author:
 * @Description:
 ****/
@RestController
@RequestMapping(value = "/car")
public class CarControllerV2 {

    @Reference
    private CarService carService;

    /***
     * 测试
     * @return
     */
    @GetMapping(value = "/v2/{name}")
    public String info(@PathVariable("name")String name){
        return carService.cartInfo(name);
    }
}
