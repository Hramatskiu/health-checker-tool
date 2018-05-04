package com.epam.health.tool.facade.common.service.action.yarn;

import com.epam.facade.model.HealthCheckActionType;
import com.epam.facade.model.ServiceStatus;
import com.epam.facade.model.accumulator.HealthCheckResultsAccumulator;
import com.epam.facade.model.accumulator.results.impl.JobResultImpl;
import com.epam.facade.model.cluster.receiver.InvalidBuildParamsException;
import com.epam.facade.model.projection.JobResultProjection;
import com.epam.facade.model.projection.ServiceStatusHolder;
import com.epam.health.tool.authentication.exception.AuthenticationRequestException;
import com.epam.health.tool.context.holder.StringContextHolder;
import com.epam.health.tool.facade.common.service.action.CommonActionNames;
import com.epam.health.tool.facade.common.service.action.CommonSshHealthCheckAction;
import com.epam.facade.model.exception.ImplementationNotResolvedException;
import com.epam.facade.model.exception.InvalidResponseException;
import com.epam.health.tool.facade.common.service.action.other.CommonOtherServicesHealthCheckAction;
import com.epam.health.tool.facade.common.service.action.yarn.searcher.JarSearchingManager;
import com.epam.health.tool.facade.context.IApplicationContext;
import com.epam.health.tool.facade.resolver.IFacadeImplResolver;
import com.epam.health.tool.facade.resolver.action.HealthCheckAction;
import com.epam.health.tool.facade.service.log.IServiceLogSearchFacade;
import com.epam.health.tool.facade.service.status.IServiceStatusReceiver;
import com.epam.health.tool.model.ClusterEntity;
import com.epam.health.tool.model.ServiceStatusEnum;
import com.epam.health.tool.model.ServiceTypeEnum;
import com.epam.util.common.CheckingParamsUtil;
import com.epam.util.common.StringUtils;
import com.epam.util.ssh.delegating.SshExecResult;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.stream.Collectors;

@Component(CommonActionNames.YARN_EXAMPLES)
@HealthCheckAction(HealthCheckActionType.YARN_SERVICE)
public class CommonYarnServiceHealthCheckActionImpl extends CommonSshHealthCheckAction {
    private final static String EXAMPLES_HADOOP_JAR_MASK = "hadoop-mapreduce-examples";
    private final static String ERROR_REGEXP = "Exception";
    private final static String IS_SUCCESS_REGEXP = ".*Job .* completed.*";
    private final static String EXAMPLES_JAR_PATH_CACHE = "EXAMPLES_JAR_PATH_CACHE";
    @Autowired
    private IFacadeImplResolver<IServiceStatusReceiver> serviceStatusReceiverIFacadeImplResolver;
    @Autowired
    private IApplicationContext applicationContext;
    @Autowired
    private JarSearchingManager jarSearchingManager;
    @Autowired
    private IFacadeImplResolver<IServiceLogSearchFacade> serviceLogSearchManagerImplResolver;
    private final static Logger logger = Logger.getLogger( CommonYarnServiceHealthCheckActionImpl.class );

    @Override
    public void performHealthCheck(String clusterName, HealthCheckResultsAccumulator healthCheckResultsAccumulator) throws InvalidResponseException {

        ClusterEntity clusterEntity = clusterDao.findByClusterName(clusterName);
        try {
            ServiceStatusHolder serviceStatus = getServiceStatus(clusterEntity);
            serviceStatus.setJobResults(Collections.singletonList(runExamplesJob(clusterEntity, "pi", "5", "10")));
            serviceStatus.setHealthSummary(mergeJobResultsWithRestStatus(serviceStatus.getHealthSummary(), getYarnServiceStatus(serviceStatus)));
            addLogDirectory(clusterEntity, healthCheckResultsAccumulator, serviceStatus);
            healthCheckResultsAccumulator.addServiceStatus(serviceStatus);
        } catch (ImplementationNotResolvedException e) {
            throw new InvalidResponseException("Can't find according implementation for vendor " + clusterEntity.getClusterTypeEnum(), e);
        }
    }

    private void addLogDirectory(ClusterEntity clusterEntity, HealthCheckResultsAccumulator healthCheckResultsAccumulator, ServiceStatusHolder serviceStatus) {
        String clusterType = clusterEntity.getClusterTypeEnum().name();
        try {
            serviceLogSearchManagerImplResolver.resolveFacadeImpl(clusterType).
                    addLogsPathToService(healthCheckResultsAccumulator, serviceStatus, clusterEntity);
        } catch (ImplementationNotResolvedException e) {
            logger.error("can't find implementation for " + clusterType + " for log service", e);
            throw new RuntimeException(e);
        }
    }

    private ServiceStatusHolder getServiceStatus(ClusterEntity clusterEntity)
            throws InvalidResponseException, ImplementationNotResolvedException {
        return serviceStatusReceiverIFacadeImplResolver
                .resolveFacadeImpl(clusterEntity.getClusterTypeEnum()).getServiceStatus(clusterEntity, ServiceTypeEnum.YARN);
    }

    private JobResultProjection runExamplesJob(ClusterEntity clusterEntity, String jobName, String... jobParams) throws InvalidResponseException {
        kinitOnClusterIfNecessary(clusterEntity);
        String pathToExamplesJar = jarSearchingManager.findJobJarOnCluster(EXAMPLES_HADOOP_JAR_MASK,
                clusterEntity.getClusterName(), clusterEntity.getClusterTypeEnum(), getJarPathFromContext( clusterEntity.getClusterName() ));
        saveJarPathToContextIfNotExists( clusterEntity.getClusterName(), pathToExamplesJar );

        try {
            return CheckingParamsUtil.isParamsNotNullOrEmpty( pathToExamplesJar ) ? representResultStringAsYarnJobObject(jobName, sshAuthenticationClient
                    .executeCommand(clusterEntity, "yarn jar " + pathToExamplesJar + " " + jobName + " " + createJobParamsString(jobParams)))
                    : createFailedJob( jobName, "Can't find job jar on cluster!" );
        } catch (AuthenticationRequestException e) {
            throw new InvalidResponseException( e );
        }
    }

    private String getJarPathFromContext( String clusterName ) {
        return applicationContext.getFromContext( clusterName, EXAMPLES_JAR_PATH_CACHE, StringContextHolder.class, StringUtils.EMPTY );
    }

    private void saveJarPathToContextIfNotExists( String clusterName, String jarPath ) {
        if ( CheckingParamsUtil.isParamsNotNullOrEmpty( jarPath ) ) {

            applicationContext.addToContextIfAbsent( clusterName, EXAMPLES_JAR_PATH_CACHE, StringContextHolder.class, new StringContextHolder( jarPath ) );
        }
    }

    private String createJobParamsString(String... params) {
        return Arrays.stream(params).collect(Collectors.joining(" "));
    }

    private JobResultProjection representResultStringAsYarnJobObject(String jobName, SshExecResult result) {
        try {
            YarnJobBuilder yarnJobBuilder = YarnJobBuilder.get().withName(jobName);
            Arrays.stream(result.getOutMessage().concat(result.getErrMessage().trim()).split("\n"))
                    .filter(CheckingParamsUtil::isParamsNotNullOrEmpty).forEach(line -> this.setToYarnJob(yarnJobBuilder, line.trim()));

            return yarnJobBuilder.build();
        }
        catch ( InvalidBuildParamsException ex ) {
            return createFailedJob( jobName, ex.getMessage() );
        }
    }

    private void setToYarnJob(YarnJobBuilder yarnJobBuilder, String line) {
        if (line.contains(ERROR_REGEXP)) {
            yarnJobBuilder.withErrors(line);
        }

        if (line.matches(IS_SUCCESS_REGEXP)) {
            yarnJobBuilder.withSuccess(line.contains("successfully"));
        }
    }

    private boolean isAllYarnCheckSuccess(ServiceStatusHolder yarnHealthCheckResult) {
        return yarnHealthCheckResult.getJobResults().stream().allMatch(JobResultProjection::isSuccess);
    }

    private boolean isAnyYarnCheckSuccess(ServiceStatusHolder yarnHealthCheckResult) {
        return yarnHealthCheckResult.getJobResults().stream().anyMatch(JobResultProjection::isSuccess);
    }

    private boolean isNoneYarnCheckSuccess(ServiceStatusHolder yarnHealthCheckResult) {
        return yarnHealthCheckResult.getJobResults().stream().noneMatch(JobResultProjection::isSuccess);
    }

    public static ServiceStatusEnum mergeJobResultsWithRestStatus(ServiceStatusEnum restCheck, ServiceStatusEnum jobResults) {
        return (restCheck.equals(ServiceStatusEnum.BAD) || restCheck.equals(ServiceStatusEnum.CONCERNING) && !jobResults.equals(ServiceStatusEnum.BAD)) ?
                ServiceStatusEnum.CONCERNING : jobResults;
    }

    private ServiceStatusEnum getYarnServiceStatus(ServiceStatusHolder yarnHealthCheckResult) {
        return isAllYarnCheckSuccess(yarnHealthCheckResult) ? ServiceStatusEnum.GOOD
                : isNoneYarnCheckSuccess(yarnHealthCheckResult) ? ServiceStatusEnum.BAD
                : ServiceStatusEnum.CONCERNING;
    }

    private JobResultProjection createFailedJob( String jobName, String alertMessage ) {
        return new JobResultImpl( jobName, false, Collections.singletonList( alertMessage ) );
    }
}
