package com.epam.health.tool.facade.service.log;

import com.epam.facade.model.accumulator.HealthCheckResultsAccumulator;
import com.epam.facade.model.accumulator.LogLocation;
import com.epam.facade.model.projection.ServiceStatusHolder;
import com.epam.health.tool.model.ClusterEntity;
import com.epam.health.tool.model.ServiceTypeEnum;

public interface IServiceLogSearchFacade {
    LogLocation searchLogs(String clusterName, ServiceTypeEnum serviceType);
    void addLogsPathToService(HealthCheckResultsAccumulator healthCheckResultsAccumulator, ServiceStatusHolder serviceStatus, ClusterEntity clusterEntity);
}
