package com.epam.health.tool.connector;

import com.epam.util.common.CheckingParamsUtil;
import com.epam.util.common.CommonUtilException;
import com.epam.util.common.json.CommonJsonHandler;
import com.epam.util.http.HttpRequestExecutor;
import org.apache.log4j.Logger;

public class HealthCheckingToolConnector {
    private static final Logger logger = Logger.getLogger(HealthCheckingToolConnector.class);
    public static void main( String... args ) {
        if ( isParamsValid( args ) ) {
            try {
                String hostWithPort = getHostWithPort(args);
                String urlGetClusterName = "http://" + hostWithPort + "/cluster/search?".concat(createRequestParamString(buildSearchParamFromArguments(args)));
                //logger.error("url:" + urlGetClusterName);
                String clusterName = HttpRequestExecutor.get()
                        .executeUrlRequest(urlGetClusterName);
                if ( !CheckingParamsUtil.isParamsNullOrEmpty( clusterName ) ) {
                    String urlGetClusterStatus = "http://" + hostWithPort + "/check/cluster/".concat(clusterName);
                    //logger.error("url cluster health: " + urlGetClusterStatus);
                    String jsonResult = HttpRequestExecutor.get().executeUrlRequest(urlGetClusterStatus);
                    HealthCheckResult healthCheckResult = CommonJsonHandler.get()
                            .getTypedValue( jsonResult, HealthCheckResult.class );
                    if ( healthCheckResult != null ) {
                        System.out.println( healthCheckResult.isClusterHealthy() );
                    }
                    else {
                        reportError( "Unexpected result - ".concat( jsonResult ) );
                    }
                }
                else {
                    reportError( "Unexpected cluster name for parameters - ".concat( createRequestParamString( buildSearchParamFromArguments( args ) ) ) );
                }
            } catch (CommonUtilException e) {
                reportError( e.getMessage() );
            }
        }
        else {
            reportError( "Not enough params!" );
        }
    }

    private static boolean isParamsValid( String... args ) {
        return !CheckingParamsUtil.isParamsNullOrEmpty( args ) && args.length > 3;
    }

    private static void reportError( String message ) {
        System.err.println( message );
    }

    private static ClusterSearchParam buildSearchParamFromArguments( String... args ) {
        return ClusterSearchParam.ClusterSearchParamBuilder.get().withNode( args[1] ).withShimName( args[2] ).withSecure( args[3] ).build();
    }

    private static String getHostWithPort(String... args) {
        return args[0];
    }

    private static String createRequestParamString( ClusterSearchParam clusterSearchParam ) {
        return ParamStringBuilder.get().buildFromSearchParams( clusterSearchParam );
    }
}
