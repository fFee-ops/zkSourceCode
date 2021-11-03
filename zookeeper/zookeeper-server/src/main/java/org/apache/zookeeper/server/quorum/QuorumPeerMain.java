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

package org.apache.zookeeper.server.quorum;

import java.io.IOException;
import javax.management.JMException;
import javax.security.sasl.SaslException;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.audit.ZKAuditProvider;
import org.apache.zookeeper.jmx.ManagedUtil;
import org.apache.zookeeper.metrics.MetricsProvider;
import org.apache.zookeeper.metrics.MetricsProviderLifeCycleException;
import org.apache.zookeeper.metrics.impl.MetricsProviderBootstrap;
import org.apache.zookeeper.server.DatadirCleanupManager;
import org.apache.zookeeper.server.ExitCode;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ServerMetrics;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.ZooKeeperServerMain;
import org.apache.zookeeper.server.admin.AdminServer.AdminServerException;
import org.apache.zookeeper.server.auth.ProviderRegistry;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog.DatadirException;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig.ConfigException;
import org.apache.zookeeper.server.util.JvmPauseMonitor;
import org.apache.zookeeper.util.ServiceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * <h2>Configuration file</h2>
 *
 * When the main() method of this class is used to start the program, the first
 * argument is used as a path to the config file, which will be used to obtain
 * configuration information. This file is a Properties file, so keys and
 * values are separated by equals (=) and the key/value pairs are separated
 * by new lines. The following is a general summary of keys used in the
 * configuration file. For full details on this see the documentation in
 * docs/index.html
 * <ol>
 * <li>dataDir - The directory where the ZooKeeper data is stored.</li>
 * <li>dataLogDir - The directory where the ZooKeeper transaction log is stored.</li>
 * <li>clientPort - The port used to communicate with clients.</li>
 * <li>tickTime - The duration of a tick in milliseconds. This is the basic
 * unit of time in ZooKeeper.</li>
 * <li>initLimit - The maximum number of ticks that a follower will wait to
 * initially synchronize with a leader.</li>
 * <li>syncLimit - The maximum number of ticks that a follower will wait for a
 * message (including heartbeats) from the leader.</li>
 * <li>server.<i>id</i> - This is the host:port[:port] that the server with the
 * given id will use for the quorum protocol.</li>
 * </ol>
 * In addition to the config file. There is a file in the data directory called
 * "myid" that contains the server id as an ASCII decimal value.
 *
 */
@InterfaceAudience.Public
public class QuorumPeerMain {

    private static final Logger LOG = LoggerFactory.getLogger(QuorumPeerMain.class);

    private static final String USAGE = "Usage: QuorumPeerMain configfile";

    protected QuorumPeer quorumPeer;

    /**
     * Zookeeper启动主方法
     */
    public static void main(String[] args) {
        QuorumPeerMain main = new QuorumPeerMain();
        try {
            //初始化配置，并启动服务
            main.initializeAndRun(args);
        } catch (Exception e) {
            //发生异常
        }
        //退出
        ServiceUtils.requestSystemExit(ExitCode.EXECUTION_FINISHED.getValue());
    }

    /**
     * 初始化并启动ZK服务
     *  1.解析配置文件
     *  2.启动清理计划
     *  3.调用 ZooKeeperServerMain.main(args);【如果是单机版启动】
     * @param args
     * @throws ConfigException
     * @throws IOException
     * @throws AdminServerException
     */
    protected void initializeAndRun(String[] args) throws ConfigException, IOException, AdminServerException {
        QuorumPeerConfig config = new QuorumPeerConfig();
        if (args.length == 1) {
            config.parse(args[0]);
        }

        // 启动数据清理计划
        DatadirCleanupManager purgeMgr = new DatadirCleanupManager(
            config.getDataDir(),        //数据存储目录
            config.getDataLogDir(),     //事务日志目录，默认为dataDir
            config.getSnapRetainCount(),//保留多少个快照数据，默认为3个
            config.getPurgeInterval()); //多少小时清理1次数据，默认不清理，在zoo.cfg中autopurge.purgeInterval=1设置时间
        purgeMgr.start();

        //集群模式运行
        if (args.length == 1 && config.isDistributed()) {
            runFromConfig(config);
        } else {
            //单机模式运行
            ZooKeeperServerMain.main(args);
        }
    }

    /****
     * 集群模式启动
     * @param config
     * @throws IOException
     * @throws AdminServerException
     */
    public void runFromConfig(QuorumPeerConfig config) throws IOException, AdminServerException {
        //注册JMX
        try {
            ManagedUtil.registerLog4jMBeans();
        } catch (JMException e) {
            LOG.warn("Unable to register log4j JMX control", e);
        }

        LOG.info("Starting quorum peer, myid=" + config.getServerId());
        MetricsProvider metricsProvider;
        try {
            //创建MetricsProvider，它是一个收集指标并将当前值发布到外部设施的系统，与选举有关。
            metricsProvider = MetricsProviderBootstrap.startMetricsProvider(
                config.getMetricsProviderClassName(),
                config.getMetricsProviderConfiguration());
        } catch (MetricsProviderLifeCycleException error) {
            throw new IOException("Cannot boot MetricsProvider " + config.getMetricsProviderClassName(), error);
        }
        try {
            //初始化监控参数
            ServerMetrics.metricsProviderInitialized(metricsProvider);
            //初始化授权模式
            ProviderRegistry.initialize();
            ServerCnxnFactory cnxnFactory = null;
            ServerCnxnFactory secureCnxnFactory = null;

            //设置网络通信方式
            if (config.getClientPortAddress() != null) {
                cnxnFactory = ServerCnxnFactory.createFactory();
                cnxnFactory.configure(config.getClientPortAddress(), config.getMaxClientCnxns(), config.getClientPortListenBacklog(), false);
            }
            //启用SSL
            if (config.getSecureClientPortAddress() != null) {
                secureCnxnFactory = ServerCnxnFactory.createFactory();
                secureCnxnFactory.configure(config.getSecureClientPortAddress(), config.getMaxClientCnxns(), config.getClientPortListenBacklog(), true);
            }

            //================================================初始化配置==================================================
            //创建QuorumPeer，该对象是zookeeper执行同步,选主过程的线程
            quorumPeer = getQuorumPeer();
            //设置事务日志持久化对象
            quorumPeer.setTxnFactory(new FileTxnSnapLog(config.getDataLogDir(), config.getDataDir()));
            //是否开启本地会话
            quorumPeer.enableLocalSessions(config.areLocalSessionsEnabled());
            //是否允许本地会话升级到全局会话
            quorumPeer.enableLocalSessionsUpgrading(config.isLocalSessionsUpgradingEnabled());
            //quorumPeer.setQuorumPeers(config.getAllMembers());
            //设置选举类型  3 = 基于 TCP 的 FastLeaderElection
            quorumPeer.setElectionType(config.getElectionAlg());
            //设置服务的id
            quorumPeer.setMyid(config.getServerId());
            //设置Zookeeper时间最小单位
            quorumPeer.setTickTime(config.getTickTime());
            //会话超时允许的最小时间，-1表示不设置
            quorumPeer.setMinSessionTimeout(config.getMinSessionTimeout());
            //会话超时允许的最大时间，-1表示不设置
            quorumPeer.setMaxSessionTimeout(config.getMaxSessionTimeout());
            //集群中的follower服务器(F)与leader服务器(L)之间 初始连接 时能容忍的最多心跳数（tickTime的数量） InitLimit * TickTime
            quorumPeer.setInitLimit(config.getInitLimit());
            //集群中的follower服务器(F)与leader服务器(L)之间 请求和应答 之间能容忍的最多心跳数（tickTime的数量）。
            quorumPeer.setSyncLimit(config.getSyncLimit());
            //在重新尝试连接到learner之前可以通过的时间
            quorumPeer.setConnectToLearnerMasterLimit(config.getConnectToLearnerMasterLimit());
            //观察者端口
            quorumPeer.setObserverMasterPort(config.getObserverMasterPort());
            //配置文件名字
            quorumPeer.setConfigFileName(config.getConfigFilename());
            //socket传输套接字监听长度
            quorumPeer.setClientPortListenBacklog(config.getClientPortListenBacklog());
            //设置zk节点的数据库
            quorumPeer.setZKDatabase(new ZKDatabase(quorumPeer.getTxnFactory()));
            //设置选举校验器
            quorumPeer.setQuorumVerifier(config.getQuorumVerifier(), false);
            if (config.getLastSeenQuorumVerifier() != null) {
                quorumPeer.setLastSeenQuorumVerifier(config.getLastSeenQuorumVerifier(), false);
            }
            //初始化数据库配置
            quorumPeer.initConfigInZKDatabase();
            //通信配置
            quorumPeer.setCnxnFactory(cnxnFactory);
            quorumPeer.setSecureCnxnFactory(secureCnxnFactory);
            quorumPeer.setSslQuorum(config.isSslQuorum());
            quorumPeer.setUsePortUnification(config.shouldUsePortUnification());
            quorumPeer.setLearnerType(config.getPeerType());
            quorumPeer.setSyncEnabled(config.getSyncEnabled());
            quorumPeer.setQuorumListenOnAllIPs(config.getQuorumListenOnAllIPs());
            if (config.sslQuorumReloadCertFiles) {
                quorumPeer.getX509Util().enableCertFileReloading();
            }
            quorumPeer.setMultiAddressEnabled(config.isMultiAddressEnabled());
            quorumPeer.setMultiAddressReachabilityCheckEnabled(config.isMultiAddressReachabilityCheckEnabled());
            quorumPeer.setMultiAddressReachabilityCheckTimeoutMs(config.getMultiAddressReachabilityCheckTimeoutMs());

            // sets quorum sasl authentication configurations
            quorumPeer.setQuorumSaslEnabled(config.quorumEnableSasl);
            if (quorumPeer.isQuorumSaslAuthEnabled()) {
                quorumPeer.setQuorumServerSaslRequired(config.quorumServerRequireSasl);
                quorumPeer.setQuorumLearnerSaslRequired(config.quorumLearnerRequireSasl);
                quorumPeer.setQuorumServicePrincipal(config.quorumServicePrincipal);
                quorumPeer.setQuorumServerLoginContext(config.quorumServerLoginContext);
                quorumPeer.setQuorumLearnerLoginContext(config.quorumLearnerLoginContext);
            }
            quorumPeer.setQuorumCnxnThreadsSize(config.quorumCnxnThreadsSize);
            quorumPeer.initialize();

            if (config.jvmPauseMonitorToRun) {
                quorumPeer.setJvmPauseMonitor(new JvmPauseMonitor(config));
            }

            //启动
            quorumPeer.start();
            ZKAuditProvider.addZKStartStopAuditLog();
            quorumPeer.join();
        } catch (InterruptedException e) {
            // warn, but generally this is ok
            LOG.warn("Quorum Peer interrupted", e);
        } finally {
            if (metricsProvider != null) {
                try {
                    metricsProvider.stop();
                } catch (Throwable error) {
                    LOG.warn("Error while stopping metrics", error);
                }
            }
        }
    }

    // @VisibleForTesting
    protected QuorumPeer getQuorumPeer() throws SaslException {
        return new QuorumPeer();
    }

    /**
     * Shutdowns properly the service, this method is not a public API.
     */
    public void close() {
        if (quorumPeer != null) {
            try {
                quorumPeer.shutdown();
            } finally {
                quorumPeer = null;
            }
        }
    }

    @Override
    public String toString() {
        QuorumPeer peer = quorumPeer;
        return peer == null ? "" : peer.toString();
    }

}
