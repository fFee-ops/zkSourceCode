package com.itheima.controller;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreMutex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.TimeUnit;

@RestController
public class LockController {

    @Autowired
    private CuratorFramework curatorFramework;

    /****
     * 分布式锁测试
     * @param name
     * @return
     */
    @GetMapping("/lock")
    public String save(String name){
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        // 获取锁
        InterProcessSemaphoreMutex balanceLock = new InterProcessSemaphoreMutex(curatorFramework, "/zktest" + name);
        try {
            // 执行加锁操作
            balanceLock.acquire();
            System.out.println(sdf.format(new Date()) +"：" + name+"  获取了锁！");
            if ("a".equals(name) || "b".equals(name)) {
                TimeUnit.SECONDS.sleep(10);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }finally {
            try {
                // 释放锁资源
                balanceLock.release();
                System.out.println(sdf.format(new Date()) +"：" + name+"  释放了锁！");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return "OK"+name;
    }
}
