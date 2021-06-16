/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dtstack.flinkx.client.util;

import org.apache.flink.client.deployment.ClusterSpecification;
import org.apache.flink.client.program.ClusterClient;
import org.apache.flink.configuration.ConfigConstants;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.GlobalConfiguration;
import org.apache.flink.yarn.YarnClientYarnClusterInformationRetriever;
import org.apache.flink.yarn.YarnClusterDescriptor;
import org.apache.flink.yarn.configuration.YarnConfigOptions;
import org.apache.flink.yarn.configuration.YarnConfigOptionsInternal;
import org.apache.flink.yarn.configuration.YarnLogConfigUtil;

import com.dtstack.flinkx.client.YarnConfLoader;
import com.dtstack.flinkx.client.perJob.FlinkPerJobUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.conf.YarnConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Date: 2021/03/03
 * Company: www.dtstack.com
 *
 * start flink yarn session
 *
 * @author tudou
 */
public class YarnSessionClientUtil {

    /**
     * 启动一个flink yarn session
     * @param flinkConfDir flink配置文件路径
     * @param yarnConfDir Hadoop配置文件路径
     * @param flinkLibDir flink lib目录路径
     * @param flinkxPluginDir FlinkX插件包路径
     * @param queue yarn队列名称
     * @return
     * @throws Exception
     */
    public static ApplicationId startYarnSession(String flinkConfDir, String yarnConfDir, String flinkLibDir, String flinkxPluginDir, String queue) throws Exception{
        Configuration flinkConfig = GlobalConfiguration.loadConfiguration(flinkConfDir);
        flinkConfig.setString(YarnConfigOptions.APPLICATION_QUEUE, queue);
        flinkConfig.setString(ConfigConstants.PATH_HADOOP_CONFIG, yarnConfDir);
        boolean hdfsPath = false;
        if (StringUtils.isBlank(flinkLibDir)) {
            throw new IllegalArgumentException("The Flink jar path is null");
        } else {
            if (flinkLibDir.startsWith("hdfs://")) {
                hdfsPath = true;
                flinkConfig.set(
                        YarnConfigOptions.PROVIDED_LIB_DIRS,
                        Collections.singletonList(flinkLibDir));
            } else {
                if (!new File(flinkLibDir).exists()) {
                    throw new IllegalArgumentException("The Flink jar path is not exist");
                }
            }
        }
        File log4j = new File(flinkConfDir+ File.separator + YarnLogConfigUtil.CONFIG_FILE_LOG4J_NAME);
        if(log4j.exists()){
            flinkConfig.setString(YarnConfigOptionsInternal.APPLICATION_LOG_CONFIG_FILE, flinkConfDir + File.separator + YarnLogConfigUtil.CONFIG_FILE_LOG4J_NAME);
        } else{
            File logback = new File(flinkConfDir+ File.separator + YarnLogConfigUtil.CONFIG_FILE_LOGBACK_NAME);
            if(logback.exists()){
                flinkConfig.setString(YarnConfigOptionsInternal.APPLICATION_LOG_CONFIG_FILE, flinkConfDir+ File.separator + YarnLogConfigUtil.CONFIG_FILE_LOGBACK_NAME);
            }
        }

        YarnConfiguration yarnConf = YarnConfLoader.getYarnConf(yarnConfDir);
        yarnConf.set("HADOOP.USER.NAME", "root");
        YarnClient yarnClient = YarnClient.createYarnClient();
        yarnClient.init(yarnConf);
        yarnClient.start();

        YarnClusterDescriptor descriptor = new YarnClusterDescriptor(
                flinkConfig,
                yarnConf,
                yarnClient,
                YarnClientYarnClusterInformationRetriever.create(yarnClient),
                false);

        if(hdfsPath){
            descriptor.setLocalJarPath(new Path(flinkLibDir + "/flink-dist_2.12-1.12.2.jar"));
        }else{
            List<File> shipFiles = new ArrayList<>();
            File[] jars = new File(flinkLibDir).listFiles();
            if (jars != null) {
                for (File jar : jars) {
                    if (jar.toURI().toURL().toString().contains("flink-dist")) {
                        descriptor.setLocalJarPath(new Path(jar.toURI().toURL().toString()));
                    } else {
                        shipFiles.add(jar);
                    }
                }
            }
            descriptor.addShipFiles(shipFiles);
        }

        File syncFile = new File(flinkxPluginDir);
        List<File> pluginPaths = Arrays.stream(Objects.requireNonNull(syncFile.listFiles()))
                .filter(file -> !file.getName().endsWith("zip"))
                .collect(Collectors.toList());
        descriptor.addShipFiles(pluginPaths);
        ClusterSpecification clusterSpecification = FlinkPerJobUtil.createClusterSpecification(null);
        ClusterClient<ApplicationId>  clusterClient = descriptor.deploySessionCluster(clusterSpecification).getClusterClient();
        return clusterClient.getClusterId();
    }

    public static void main(String[] args) throws Exception {
        String flinkConfDir = "/opt/dtstack/flink-1.12.2/conf";
        String yarnConfDir = "/data/hadoop-2.7.7/etc/hadoop";
        String flinkLibDir = "hdfs://ns/flink-1.12.2/lib";
        String flinkxPluginDir = "/opt/dtstack/122_flinkplugin/syncplugin/";
        String queue = "a";

        ApplicationId applicationId = startYarnSession(
                flinkConfDir,
                yarnConfDir,
                flinkLibDir,
                flinkxPluginDir,
                queue);

        System.out.println("clusterId = " + applicationId);
    }
}
