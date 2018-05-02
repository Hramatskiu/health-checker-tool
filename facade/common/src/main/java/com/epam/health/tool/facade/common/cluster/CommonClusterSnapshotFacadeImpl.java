package com.epam.health.tool.facade.common.cluster;

import com.epam.facade.model.ClusterHealthSummary;
import com.epam.facade.model.HealthCheckActionType;
import com.epam.facade.model.ServiceStatus;
import com.epam.facade.model.accumulator.ClusterAccumulatorToken;
import com.epam.facade.model.accumulator.HealthCheckResultsAccumulator;
import com.epam.facade.model.projection.*;
import com.epam.health.tool.dao.cluster.*;
import com.epam.health.tool.facade.cluster.IClusterFacade;
import com.epam.health.tool.facade.cluster.IClusterSnapshotFacade;
import com.epam.health.tool.facade.cluster.IHealthCheckFacade;
import com.epam.facade.model.exception.InvalidResponseException;
import com.epam.health.tool.model.*;
import com.epam.health.tool.transfer.impl.SVTransfererManager;
import com.epam.util.common.CheckingParamsUtil;
import com.epam.util.common.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.*;
import java.util.function.BiConsumer;

public abstract class CommonClusterSnapshotFacadeImpl implements IClusterSnapshotFacade {

    @Autowired
    protected SVTransfererManager svTransfererManager;
    @Autowired
    protected IClusterFacade clusterFacade;
    @Autowired
    ClusterSnapshotDao clusterSnapshotDao;
    @Autowired
    ClusterServiceSnapshotDao clusterServiceSnapshotDao;
    @Autowired
    ClusterServiceDao clusterServiceDao;
    @Autowired
    ClusterDao clusterDao;
    @Autowired
    NodeSnapshotDao nodeSnapshotDao;
    @Autowired
    private IHealthCheckFacade healthCheckFacade;
    @Autowired
    private JobResultDao jobResultDao;

    private Logger logger = Logger.getLogger(CommonClusterSnapshotFacadeImpl.class);
    private Map<HealthCheckActionType, BiConsumer<ClusterSnapshotEntity, HealthCheckResultsAccumulator>> healthActionSavers = new HashMap<>();

    {
        healthActionSavers.put(HealthCheckActionType.FS, (clusterShapshotEntity, healthCheckResultsAccumulator) -> {
            List<? extends NodeSnapshotEntityProjection> nodes = healthCheckResultsAccumulator.getFsHealthCheckResult().getNodeSnapshotEntityProjections();
            if (nodes != null) {
                nodes.forEach(o -> nodeSnapshotDao.save(new NodeSnapshotEntity(new FsUsageEntity(o.getUsedGb(), o.getTotalGb()), o.getNode(), clusterShapshotEntity)));
            } else {
                logger.error("full statistics for nodes didn't contain needed info");
            }
        });
        healthActionSavers.put(HealthCheckActionType.HDFS_MEMORY, (clusterShapshotEntity, healthCheckResultsAccumulator) -> {
            HdfsUsageEntityProjection hdfsUsage = healthCheckResultsAccumulator.getFsHealthCheckResult().getHdfsUsageEntityProjection();
            HdfsUsageEntity hdfsUsageEntity = new HdfsUsageEntity(hdfsUsage.getUsedGb(), hdfsUsage.getTotalGb());
            clusterShapshotEntity.setHdfsUsageEntity(hdfsUsageEntity);
        });
        healthActionSavers.put(HealthCheckActionType.MEMORY, (clusterShapshotEntity, healthCheckResultsAccumulator) -> {
            MemoryUsageEntityProjection memoryUsage = healthCheckResultsAccumulator.getFsHealthCheckResult().getMemoryUsageEntityProjection();
            MemoryUsageEntity memoryUsageEntity = new MemoryUsageEntity(memoryUsage.getUsed(), memoryUsage.getTotal());
            clusterShapshotEntity.setMemoryUsageEntity(memoryUsageEntity);
        });
        healthActionSavers.put(HealthCheckActionType.OTHER_SERVICES, (clusterShapshotEntity, healthCheckResultsAccumulator) -> {
            saveCommonServicesSnapshots(healthCheckResultsAccumulator, clusterShapshotEntity);
        });
        healthActionSavers.put(HealthCheckActionType.YARN_SERVICE, (clusterShapshotEntity, healthCheckResultsAccumulator) -> {
            saveServiceInfo(clusterShapshotEntity, healthCheckResultsAccumulator, ServiceTypeEnum.YARN);
        });
        healthActionSavers.put(HealthCheckActionType.HDFS_SERVICE, (clusterShapshotEntity, healthCheckResultsAccumulator) -> {
            saveServiceInfo(clusterShapshotEntity, healthCheckResultsAccumulator, ServiceTypeEnum.HDFS);
        });
    }

    private void saveServiceInfo(ClusterSnapshotEntity clusterShapshotEntity, HealthCheckResultsAccumulator healthCheckResultsAccumulator, ServiceTypeEnum serviceTypeEnum) {
        try {
            ServiceStatusHolder yarnHealthCheckResult = healthCheckResultsAccumulator.getServiceHealthCheckResult(serviceTypeEnum);
            //find yarn service, save job results
            ClusterServiceSnapshotEntity clusterServiceSnapshotEntity = clusterServiceSnapshotDao.findByClusterSnapshotIdServiceId(clusterShapshotEntity.getId(), serviceTypeEnum);
            if (clusterServiceSnapshotEntity == null) {
                //save service status
                clusterServiceSnapshotEntity = saveClusterServiceSnapshot(clusterShapshotEntity, yarnHealthCheckResult);
            }
            //save job results to db
            List<JobResultProjection> jobResults = yarnHealthCheckResult.getJobResults();
            ClusterServiceSnapshotEntity finalClusterServiceSnapshotEntity = clusterServiceSnapshotEntity;
            if (jobResults != null) {
                jobResults.forEach(yarnJob -> jobResultDao.save(new JobResultEntity(yarnJob.getName(), new Date(), yarnJob.isSuccess(),
                        finalClusterServiceSnapshotEntity, yarnJob.getAlerts())));
            }
        }
        catch ( InvalidResponseException ex ) {
            logger.error( ex.getMessage() );
        }
    }

    @Override
    public List<ClusterHealthSummary> getClusterSnapshotHistory(String clusterName, int count) throws InvalidResponseException {
        Pageable top30 = new PageRequest(0, count);
        List<ClusterSnapshotEntityProjection> top30ClusterName = clusterSnapshotDao.findTop10ClusterName(clusterName, top30);
        ArrayList<ClusterHealthSummary> clusterHealthSummaries = new ArrayList<>();
        top30ClusterName.forEach(clusterSnapshotEntityProjection -> clusterHealthSummaries.add(new ClusterHealthSummary(clusterSnapshotEntityProjection,
                clusterServiceSnapshotDao.findServiceProjectionsBy(clusterSnapshotEntityProjection.getId()))));
        return clusterHealthSummaries;
    }

    @Override
    public HealthCheckResultsAccumulator makeClusterSnapshot(ClusterAccumulatorToken clusterAccumulatorToken) throws InvalidResponseException {
        HealthCheckResultsAccumulator healthCheckResultsAccumulatorNotFull = healthCheckFacade.askForClusterSnapshot(clusterAccumulatorToken);
        ClusterSnapshotEntity clusterSnapshotEntity = getOrCreateClusterSnapshot( clusterAccumulatorToken );

        List<HealthCheckActionType> passedActionTypes = clusterAccumulatorToken.getPassedActionTypes();
        passedActionTypes.forEach(healthCheckActionType -> {
            BiConsumer<ClusterSnapshotEntity, HealthCheckResultsAccumulator> actionConsumer = healthActionSavers.get(healthCheckActionType);
            if (actionConsumer != null) {
                actionConsumer.accept( clusterSnapshotEntity, healthCheckResultsAccumulatorNotFull );
            } else {
                logger.error("action type " + healthCheckActionType + " can't be handled, no implementation found");
            }
        });

        //refresh
        HealthCheckResultsAccumulator healthCheckResultsAccumulatorDb = recreateHealthCheckResultFromDB( clusterSnapshotDao.save(clusterSnapshotEntity),
                healthCheckResultsAccumulatorNotFull );

        if (HealthCheckActionType.containAllActionTypes(passedActionTypes)) {
            clusterSnapshotEntity.setFull(true);
            //here - clean previous for last hour
            //clusterSnapshotDao.clearForLastHour();
        }

        return healthCheckResultsAccumulatorDb;
    }

    @Override
    public HealthCheckResultsAccumulator getLatestClusterSnapshot(ClusterAccumulatorToken clusterAccumulatorToken) throws InvalidResponseException {
        List<ClusterHealthSummary> clusterSnapshotHistory = getClusterSnapshotHistory(clusterAccumulatorToken.getClusterName(), 1);
        if (clusterSnapshotHistory.size() == 0 || isTokenNotEmpty(clusterAccumulatorToken)) {
            return makeClusterSnapshot(clusterAccumulatorToken);
        } else {
            return HealthCheckResultsAccumulator.HealthCheckResultsModifier.get().setClusterInfoFromClusterSnapshot( clusterSnapshotHistory.get(0).getCluster() )
                    .setFsResultFromClusterSnapshot( clusterSnapshotHistory.get(0).getCluster() )
                    .setServiceStatusList( clusterSnapshotHistory.get(0).getServiceStatusList() ).modify();
        }
    }

    private ClusterSnapshotEntity getOrCreateClusterSnapshot( ClusterAccumulatorToken clusterAccumulatorToken ) {
        if (clusterAccumulatorToken.getToken() != null) {
            ClusterSnapshotEntity clusterSnapshotEntity = clusterSnapshotDao.findByToken(clusterAccumulatorToken.getToken());

            return clusterSnapshotEntity != null ? clusterSnapshotEntity : createClusterSnapshot( clusterAccumulatorToken );
        } else  {
            //Empty token for new check??
            return createClusterSnapshot( clusterAccumulatorToken );
        }
    }

    private ClusterSnapshotEntity createClusterSnapshot( ClusterAccumulatorToken clusterAccumulatorToken ) {
        ClusterSnapshotEntity clusterSnapshotEntity = new ClusterSnapshotEntity();

        clusterSnapshotEntity.setDateOfSnapshot(new Date());
        clusterSnapshotEntity.setClusterEntity( clusterDao.findByClusterName(clusterAccumulatorToken.getClusterName()) );
        clusterSnapshotEntity.setToken(clusterAccumulatorToken.getToken());

        return clusterSnapshotDao.save(clusterSnapshotEntity);
    }

    private HealthCheckResultsAccumulator recreateHealthCheckResultFromDB(ClusterSnapshotEntity clusterSnapshotEntity, HealthCheckResultsAccumulator healthCheckResultsAccumulator) {
        ClusterSnapshotEntityProjection clusterSnapshotEntityProjection = clusterSnapshotDao.findClusterSnapshotById(clusterSnapshotEntity.getId());

        return HealthCheckResultsAccumulator.HealthCheckResultsModifier.get( healthCheckResultsAccumulator )
                .setClusterInfoFromClusterSnapshot( clusterSnapshotEntityProjection ).modify();
    }

    private void saveCommonServicesSnapshots(HealthCheckResultsAccumulator healthCheckResultsAccumulator, ClusterSnapshotEntity clusterSnapshotEntity) {
        healthCheckResultsAccumulator.getServiceStatusList().forEach(serviceStatus -> {
            saveClusterServiceSnapshot(clusterSnapshotEntity, serviceStatus);
        });
    }

    private ClusterServiceSnapshotEntity saveClusterServiceSnapshot(ClusterSnapshotEntity clusterSnapshotEntity, ServiceStatusHolder serviceStatus) {
        ClusterEntity clusterEntity = clusterSnapshotEntity.getClusterEntity();
        ClusterServiceEntity clusterServiceEntity = clusterServiceDao.findByClusterIdAndServiceType(clusterEntity.getId(), serviceStatus.getType());
        ClusterServiceSnapshotEntity clusterServiceSnapshotEntity = svTransfererManager.<ServiceStatus, ClusterServiceSnapshotEntity>getTransferer(ServiceStatus.class, ClusterServiceSnapshotEntity.class)
                .transfer((ServiceStatus) serviceStatus, ClusterServiceSnapshotEntity.class);
        clusterServiceSnapshotEntity = mergeEntityServiceStatusHolder(clusterServiceSnapshotDao.findByClusterSnapshotIdServiceId(clusterSnapshotEntity.getId(), serviceStatus.getType()),
                clusterServiceSnapshotEntity, clusterSnapshotEntity);

        if (clusterServiceEntity == null) {
            clusterServiceEntity = clusterServiceSnapshotEntity.getClusterServiceEntity();
            clusterServiceEntity.setClusterEntity(clusterEntity);
            clusterServiceDao.save(clusterServiceEntity);
        } else {
            clusterServiceSnapshotEntity.setClusterServiceEntity(clusterServiceEntity);
        }
        updateLogDirectory(serviceStatus, clusterServiceEntity);
        clusterServiceSnapshotDao.save(clusterServiceSnapshotEntity);
        return clusterServiceSnapshotEntity;
    }

    private void updateLogDirectory(ServiceStatusHolder serviceStatus, ClusterServiceEntity clusterServiceEntity) {
        if (!StringUtils.isEmpty(serviceStatus.getLogDirectory())) {
            clusterServiceEntity.setLogPath(serviceStatus.getLogDirectory());
            clusterServiceEntity.setClusterNode(serviceStatus.getClusterNode());
            clusterServiceDao.save(clusterServiceEntity);
        }
    }

    private ClusterServiceSnapshotEntity mergeEntityServiceStatusHolder(ClusterServiceSnapshotEntity db, ClusterServiceSnapshotEntity check, ClusterSnapshotEntity clusterSnapshotEntity) {
        if (db == null) {
            check.setClusterSnapshotEntity(clusterSnapshotEntity);
            return check;
        }
        if (check.getJobResults() != null && !check.getJobResults().isEmpty()) {
            //full check was done, mandatory
            db.setHealthStatus(check.getHealthStatus());
        }
        return db;
    }

    private boolean isTokenNotEmpty(ClusterAccumulatorToken clusterAccumulatorToken) {
        return CheckingParamsUtil.isParamsNotNullOrEmpty(clusterAccumulatorToken.getToken()) && !clusterAccumulatorToken.getToken().trim().equals("empty");
    }

    public void setClusterFacade(IClusterFacade clusterFacade) {
        this.clusterFacade = clusterFacade;
    }
}
