package com.epam.health.tool.facade.hdp.service.log;

import com.epam.health.tool.facade.common.service.log.CommonServiceLogSearchFacade;
import com.epam.health.tool.facade.resolver.ClusterSpecificComponent;
import com.epam.health.tool.facade.service.log.IServiceLogsSearcher;
import com.epam.health.tool.model.ClusterTypeEnum;
import com.epam.health.tool.model.ServiceTypeEnum;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ClusterSpecificComponent( ClusterTypeEnum.HDP )
public class HdpServiceLogSearchFacade extends CommonServiceLogSearchFacade {
    private static final String HBASE_DEFAULT_LOG_PATH = "/var/log/hbase1";
    private static final String HIVE_DEFAULT_LOG_PATH = "/var/log/hive1";
    private static final String OOZIE_DEFAULT_LOG_PATH = "/var/log/oozie1";
    private static final String SQOOP_DEFAULT_LOG_PATH = "/var/log/sqoop1";
    private static final String HDFS_DEFAULT_LOG_PATH = "/var/log/hdfs1";
    private static final String ZOOKEEPER_DEFAULT_LOG_PATH = "/var/log/zookeeper";

    @Override
    protected Map<ServiceTypeEnum, IServiceLogsSearcher> getLogSearchersMap() {
        Map<ServiceTypeEnum, IServiceLogsSearcher> serviceLogsSearcherMap = new HashMap<>();

        serviceLogsSearcherMap.put(ServiceTypeEnum.HBASE, createServiceLogSearcher( HBASE_LOG_PROPERTY, HBASE_DEFAULT_LOG_PATH ));
        serviceLogsSearcherMap.put(ServiceTypeEnum.HIVE, createServiceLogSearcher( HIVE_LOG_PROPERTY, HIVE_DEFAULT_LOG_PATH ));
        serviceLogsSearcherMap.put(ServiceTypeEnum.HDFS, createServiceLogSearcher( HDFS_LOG_PROPERTY, HDFS_DEFAULT_LOG_PATH ));
        serviceLogsSearcherMap.put(ServiceTypeEnum.SQOOP, createServiceLogSearcher( SQOOP_LOG_PROPERTY, SQOOP_DEFAULT_LOG_PATH ));
        serviceLogsSearcherMap.put(ServiceTypeEnum.OOZIE, createServiceLogSearcher( OOZIE_LOG_PROPERTY, OOZIE_DEFAULT_LOG_PATH ));
        serviceLogsSearcherMap.put(ServiceTypeEnum.ZOOKEEPER, createServiceLogSearcher( ZOOKEEPER_LOG_PROPERTY, ZOOKEEPER_DEFAULT_LOG_PATH ));

        return serviceLogsSearcherMap;
    }
}
