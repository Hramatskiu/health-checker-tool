package com.epam.health.tool.facade.hdp.service.status;

import com.epam.facade.model.ServiceStatus;
import com.epam.facade.model.projection.ServiceStatusHolder;
import com.epam.health.tool.authentication.exception.AuthenticationRequestException;
import com.epam.health.tool.facade.resolver.ClusterSpecificComponent;
import com.epam.health.tool.facade.common.service.status.CommonServiceStatusReceiver;
import com.epam.facade.model.exception.InvalidResponseException;
import com.epam.health.tool.facade.hdp.cluster.ServiceStateEnumMapper;
import com.epam.health.tool.facade.hdp.cluster.HdpServiceStatusDTO;
import com.epam.health.tool.model.ClusterEntity;
import com.epam.health.tool.model.ClusterTypeEnum;
import com.epam.health.tool.model.ServiceTypeEnum;
import com.epam.util.common.CommonUtilException;
import com.epam.util.common.json.CommonJsonHandler;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Created by Vasilina_Terehova on 4/24/2018.
 */
@Component
@ClusterSpecificComponent(ClusterTypeEnum.HDP)
public class HdpServiceStatusReceiver extends CommonServiceStatusReceiver {
    @Override
    public List<ServiceStatusHolder> getServiceStatusList(ClusterEntity clusterEntity) throws InvalidResponseException {
        try {
            return Arrays.stream(ServiceTypeEnum.values())
                    .map(serviceTypeEnum -> getServiceStatus(clusterEntity, serviceTypeEnum))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (RuntimeException ex) {
            throw new InvalidResponseException(ex);
        }
    }

    private String makeHttpRequest(String clusterName, String url, boolean useSpnego) {
        try {
            return httpAuthenticationClient.makeAuthenticatedRequest(clusterName, url, useSpnego);
        } catch (AuthenticationRequestException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public ServiceStatusHolder getServiceStatus(ClusterEntity clusterEntity, ServiceTypeEnum serviceTypeEnum) throws RuntimeException {
        String url = "http://" + clusterEntity.getHost() + ":8080/api/v1/clusters/" + clusterEntity.getClusterName() + "/services/" + serviceTypeEnum.toString();
        try {
            return readFromJson(makeHttpRequest(clusterEntity.getClusterName(), url, false));
        } catch (CommonUtilException e) {
            throw new RuntimeException(e);
        }
    }

    private ServiceStatusHolder readFromJson(String json) throws CommonUtilException {
        HdpServiceStatusDTO hdpServiceStatusDTO = CommonJsonHandler.get().getTypedValueFromInnerField(json, HdpServiceStatusDTO.class, "ServiceInfo");
        if (hdpServiceStatusDTO == null) {
            //impala doesn't exist for hdp
            return null;
        }
        hdpServiceStatusDTO.setHealthStatus(ServiceStateEnumMapper.get().mapStringStateToEnum(hdpServiceStatusDTO.getHealthStatus()).toString());
        return svTransfererManager.<HdpServiceStatusDTO, ServiceStatus>getTransferer(HdpServiceStatusDTO.class, ServiceStatus.class)
                .transfer(hdpServiceStatusDTO, ServiceStatus.class);
    }
}
