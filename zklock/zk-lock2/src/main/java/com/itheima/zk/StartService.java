package com.itheima.zk;

import org.apache.curator.framework.CuratorFramework;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/****
 * 容器加载完毕后会执行run方法
 */
@Component
public class StartService implements ApplicationRunner {

    @Autowired
    private CuratorFramework curatorFramework;

    /****
     * 启动zk客户端
     * @param args
     * @throws Exception
     */
    @Override
    public void run(ApplicationArguments args) throws Exception {
        // 启动客户端
        curatorFramework.start();
    }
}
