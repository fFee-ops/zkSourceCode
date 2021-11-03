package com.itheima.car.controller;

import com.itheima.service.CarService;
import org.apache.dubbo.config.annotation.Reference;
import org.springframework.beans.factory.annotation.Autowired;
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
public class CarControllerV1 {

    @Reference
    private CarService carService;

    /***
     * 测试 localhost:18081/v1/BYD
     * @return
     */
    @GetMapping(value = "/v1/{name}")
    public String info(@PathVariable("name")String name){
        return carService.cartInfo(name);
    }
}
