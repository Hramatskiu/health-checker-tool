package com.epam.health.tool.facade.common.service.log;

import com.epam.facade.model.accumulator.HealthCheckResultsAccumulator;
import com.epam.facade.model.accumulator.LogLocation;
import com.epam.facade.model.exception.ImplementationNotResolvedException;
import com.epam.facade.model.projection.ServiceStatusHolder;
import com.epam.health.tool.authentication.ssh.SshAuthenticationClient;
import com.epam.health.tool.dao.cluster.ClusterDao;
import com.epam.health.tool.dao.cluster.ClusterServiceDao;
import com.epam.health.tool.facade.cluster.receiver.IRunningClusterParamReceiver;
import com.epam.health.tool.facade.common.service.action.other.CommonOtherServicesHealthCheckAction;
import com.epam.health.tool.facade.resolver.IFacadeImplResolver;
import com.epam.health.tool.facade.service.log.IServiceLogSearchFacade;
import com.epam.health.tool.facade.service.log.IServiceLogsSearcher;
import com.epam.health.tool.model.ClusterEntity;
import com.epam.health.tool.model.ClusterServiceEntity;
import com.epam.health.tool.model.ServiceTypeEnum;
import com.epam.util.common.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

public abstract class CommonServiceLogSearchFacade implements IServiceLogSearchFacade {
    public static final String HBASE_LOG_PROPERTY = "hbase.log.dir";
    public static final String ZOOKEEPER_LOG_PROPERTY = "zookeeper.log.dir";
    public static final String HIVE_LOG_PROPERTY = "hive.log.dir";
    public static final String OOZIE_LOG_PROPERTY = "oozie.log.dir";
    public static final String HDFS_LOG_PROPERTY = "hdfs.log.dir";
    public static final String SQOOP_LOG_PROPERTY = "sqoop.log.dir";
    private final static Logger logger = Logger.getLogger( CommonServiceLogSearchFacade.class );

    @Autowired
    private SshAuthenticationClient sshAuthenticationClient;
    @Autowired
    private ClusterDao clusterDao;
    @Autowired
    private IFacadeImplResolver<IRunningClusterParamReceiver> clusterParamReceiverIFacadeImplResolver;
    @Autowired
    private IFacadeImplResolver<IServiceLogSearchFacade> serviceLogSearchManagerImplResolver;
    @Autowired
    private ClusterServiceDao clusterServiceDao;

    @Override
    public LogLocation searchLogs(String clusterName, ServiceTypeEnum serviceType ) {
        return getLogSearchersMap().getOrDefault(serviceType, clusterName1 -> new LogLocation(StringUtils.EMPTY, StringUtils.EMPTY)).searchLogsLocation( clusterName );
    }

    protected IServiceLogsSearcher createServiceLogSearcher( String logProperty, String defaultPath ) {
        return new CommonServiceLogSearcher( sshAuthenticationClient, clusterDao, clusterParamReceiverIFacadeImplResolver ) {
            @Override
            protected String getLogPropertyName() {
                return logProperty;
            }

            @Override
            protected String getDefaultPath() {
                return defaultPath;
            }
        };
    }


    protected abstract Map<ServiceTypeEnum, IServiceLogsSearcher> getLogSearchersMap();

    public void addLogsPathToService(HealthCheckResultsAccumulator healthCheckResultsAccumulator, ServiceStatusHolder serviceStatus, ClusterEntity clusterEntity) {
        try {
            ClusterServiceEntity byClusterIdAndServiceType = clusterServiceDao.findByClusterIdAndServiceType(clusterEntity.getId(), serviceStatus.getType());
            String logPath = null;
            if (byClusterIdAndServiceType == null) {
                logger.error("Can't find cluster service entity for cluster: " + clusterEntity.getClusterName() + " service: " + serviceStatus.getType());
            } else {
                logPath = byClusterIdAndServiceType.getLogPath();
            }
            if (healthCheckResultsAccumulator.isFullCheck()) {
                LogLocation logLocation = serviceLogSearchManagerImplResolver.resolveFacadeImpl(clusterEntity.getClusterTypeEnum().name())
                        .searchLogs(clusterEntity.getClusterName(), serviceStatus.getType());
                String logDirectory = logLocation.getLogPath();
                logger.info("Logs for service " + serviceStatus.getDisplayName() + logDirectory);
                serviceStatus.setLogDirectory(logDirectory);
                serviceStatus.setClusterNode(logLocation.getClusterNode());
            }
        } catch (ImplementationNotResolvedException e) {
            logger.error(e.getMessage());
        }
    }
}
