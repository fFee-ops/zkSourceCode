/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.management.JMException;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.audit.ZKAuditProvider;
import org.apache.zookeeper.jmx.ManagedUtil;
import org.apache.zookeeper.metrics.MetricsProvider;
import org.apache.zookeeper.metrics.MetricsProviderLifeCycleException;
import org.apache.zookeeper.metrics.impl.MetricsProviderBootstrap;
import org.apache.zookeeper.server.admin.AdminServer;
import org.apache.zookeeper.server.admin.AdminServer.AdminServerException;
import org.apache.zookeeper.server.admin.AdminServerFactory;
import org.apache.zookeeper.server.auth.ProviderRegistry;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog.DatadirException;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig.ConfigException;
import org.apache.zookeeper.server.util.JvmPauseMonitor;
import org.apache.zookeeper.util.ServiceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class starts and runs a standalone ZooKeeperServer.
 */
@InterfaceAudience.Public
public class ZooKeeperServerMain {

    private static final Logger LOG = LoggerFactory.getLogger(ZooKeeperServerMain.class);

    private static final String USAGE = "Usage: ZooKeeperServerMain configfile | port datadir [ticktime] [maxcnxns]";

    // ZooKeeper server supports two kinds of connection: unencrypted and encrypted.
    private ServerCnxnFactory cnxnFactory;
    private ServerCnxnFactory secureCnxnFactory;
    private ContainerManager containerManager;
    private MetricsProvider metricsProvider;
    private AdminServer adminServer;

    /*
     * 启动Zookeeper服务
     * @param args the configfile or the port datadir [ticktime]
     */
    public static void main(String[] args) {
        ZooKeeperServerMain main = new ZooKeeperServerMain();
        try {
            main.initializeAndRun(args);
        } catch (Exception e) {
            //发生异常
        }
        //正常退出
        ServiceUtils.requestSystemExit(ExitCode.EXECUTION_FINISHED.getValue());
    }

    //初始化配置，并运行Zookeeper服务
    protected void initializeAndRun(String[] args) throws ConfigException, IOException, AdminServerException {
        //注册JMX
        try {
            ManagedUtil.registerLog4jMBeans();
        } catch (JMException e) {
            LOG.warn("Unable to register log4j JMX control", e);
        }

        //解析配置文件
        ServerConfig config = new ServerConfig();
        if (args.length == 1) {
            config.parse(args[0]);
        } else {
            config.parse(args);
        }

        //根据解析的配置运行Zookeeper服务
        runFromConfig(config);
    }

    /**
     * 根据解析的配置运行Zookeeper服务
     * @param config 当前使用的配置
     * @throws IOException
     * @throws AdminServerException
     */
    public void runFromConfig(ServerConfig config) throws IOException, AdminServerException {
        FileTxnSnapLog txnLog = null;
        try {
            try {
                //创建MetricsProvider，它是一个收集指标并将当前值发布到外部设施的系统，与选举有关。
                metricsProvider = MetricsProviderBootstrap.startMetricsProvider(
                    config.getMetricsProviderClassName(),
                    config.getMetricsProviderConfiguration());
            } catch (MetricsProviderLifeCycleException error) {
                throw new IOException("Cannot boot MetricsProvider " + config.getMetricsProviderClassName(), error);
            }
            ServerMetrics.metricsProviderInitialized(metricsProvider);
            ProviderRegistry.initialize();
            // 根据参数创建日志目录（事务日志目录、快照日志目录） FileTxnSnapLogL:事务数据操作
            txnLog = new FileTxnSnapLog(config.dataLogDir, config.dataDir);
            //JvmPauseMonitor监控
            JvmPauseMonitor jvmPauseMonitor = null;
            if (config.jvmPauseMonitorToRun) {
                jvmPauseMonitor = new JvmPauseMonitor(config);
            }
            //创建ZookeeperServer服务实例，并初始化参数
            final ZooKeeperServer zkServer = new ZooKeeperServer(jvmPauseMonitor, txnLog, config.tickTime, config.minSessionTimeout, config.maxSessionTimeout, config.listenBacklog, null, config.initialConfig);
            txnLog.setServerStats(zkServer.serverStats());

            //注册服务关闭程序
            final CountDownLatch shutdownLatch = new CountDownLatch(1);
            zkServer.registerServerShutdownHandler(new ZooKeeperServerShutdownHandler(shutdownLatch));

            // 启动服务管理器(JettyAdminServer)-启动Jetty
            adminServer = AdminServerFactory.createAdminServer();
            adminServer.setZooKeeperServer(zkServer);
            adminServer.start();

            boolean needStartZKServer = true;
            if (config.getClientPortAddress() != null) {
                //cnxnFactory负责zk的网络请求，createFactory中
                //从系统配置中读取ZOOKEEPER_SERVER_CNXN_FACTORY，默认是没有这个配置的，因此默认是使用NIOServerCnxnFactory，基于java的NIO实现，
                cnxnFactory = ServerCnxnFactory.createFactory();
                //0.0.0.0/0.0.0.0:2181、单个客户端连接数超过限制、请求的传入连接队列的最大长度，-1不限制
                cnxnFactory.configure(config.getClientPortAddress(), config.getMaxClientCnxns(), config.getClientPortListenBacklog(), false);
                //启动Zookeeper服务
                cnxnFactory.startup(zkServer);
                // 是否需要启动Zookeeper服务，zookeeper服务已经启动了，不需要再次启动，设置为false
                needStartZKServer = false;
            }
            //采用了SSL（我们没有使用，不做讲解）
            if (config.getSecureClientPortAddress() != null) {
                secureCnxnFactory = ServerCnxnFactory.createFactory();
                secureCnxnFactory.configure(config.getSecureClientPortAddress(), config.getMaxClientCnxns(), config.getClientPortListenBacklog(), true);
                secureCnxnFactory.startup(zkServer, needStartZKServer);
            }

            //创建ContainerManager，它只由Leader调用，通过Timer定时执行,清理过期的容器节点和TTL节点,执行周期为分钟级别
            containerManager = new ContainerManager(
                zkServer.getZKDatabase(),
                zkServer.firstProcessor,
                Integer.getInteger("znode.container.checkIntervalMs", (int) TimeUnit.MINUTES.toMillis(1)),
                Integer.getInteger("znode.container.maxPerMinute", 10000),
                Long.getLong("znode.container.maxNeverUsedIntervalMs", 0)
            );
            containerManager.start();
            //为服务器启动和注册服务器停止日志添加审计日志。
            ZKAuditProvider.addZKStartStopAuditLog();

            //服务器正常启动时,运行到此处阻塞,只有server的state变为ERROR或SHUTDOWN时继续运行后面的代码
            shutdownLatch.await();

            shutdown();

            if (cnxnFactory != null) {
                cnxnFactory.join();
            }
            if (secureCnxnFactory != null) {
                secureCnxnFactory.join();
            }
            if (zkServer.canShutdown()) {
                zkServer.shutdown(true);
            }
        } catch (InterruptedException e) {
            // warn, but generally this is ok
            LOG.warn("Server interrupted", e);
        } finally {
            if (txnLog != null) {
                txnLog.close();
            }
            if (metricsProvider != null) {
                try {
                    metricsProvider.stop();
                } catch (Throwable error) {
                    LOG.warn("Error while stopping metrics", error);
                }
            }
        }
    }

    /**
     * Shutdown the serving instance
     */
    protected void shutdown() {
        if (containerManager != null) {
            containerManager.stop();
        }
        if (cnxnFactory != null) {
            cnxnFactory.shutdown();
        }
        if (secureCnxnFactory != null) {
            secureCnxnFactory.shutdown();
        }
        try {
            if (adminServer != null) {
                adminServer.shutdown();
            }
        } catch (AdminServerException e) {
            LOG.warn("Problem stopping AdminServer", e);
        }
    }

    // VisibleForTesting
    ServerCnxnFactory getCnxnFactory() {
        return cnxnFactory;
    }

    // VisibleForTesting
    ServerCnxnFactory getSecureCnxnFactory() {
        return secureCnxnFactory;
    }

    /**
     * Shutdowns properly the service, this method is not a public API.
     */
    public void close() {
        ServerCnxnFactory primaryCnxnFactory = this.cnxnFactory;
        if (primaryCnxnFactory == null) {
            // in case of pure TLS we can hook into secureCnxnFactory
            primaryCnxnFactory = secureCnxnFactory;
        }
        if (primaryCnxnFactory == null || primaryCnxnFactory.getZooKeeperServer() == null) {
            return;
        }
        ZooKeeperServerShutdownHandler zkShutdownHandler = primaryCnxnFactory.getZooKeeperServer().getZkShutdownHandler();
        zkShutdownHandler.handle(ZooKeeperServer.State.SHUTDOWN);
        try {
            // ServerCnxnFactory will call the shutdown
            primaryCnxnFactory.join();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

}
