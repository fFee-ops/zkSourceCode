package com.itheima;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryNTimes;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class ZkApplication18082 {

    public static void main(String[] args) {
        SpringApplication.run(ZkApplication18082.class,args);
    }

   //@Bean
   //public CuratorFramework curatorFramework() {
   //    return CuratorFrameworkFactory.newClient("127.0.0.1:2181", new RetryNTimes(5, 1000));
   //}
}
