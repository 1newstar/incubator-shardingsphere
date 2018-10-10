/*
 * Copyright 2016-2018 shardingsphere.io.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package io.shardingsphere.shardingproxy.backend.jdbc.datasource;

import io.shardingsphere.core.constant.ConnectionMode;
import io.shardingsphere.core.exception.ShardingException;
import io.shardingsphere.core.rule.DataSourceParameter;
import io.shardingsphere.shardingproxy.backend.BackendDataSource;
import io.shardingsphere.shardingproxy.runtime.GlobalRegistry;
import io.shardingsphere.shardingproxy.runtime.ShardingSchema;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Backend data source for JDBC.
 *
 * @author zhaojun
 * @author zhangliang
 * @author panjuan
 */
public final class JDBCBackendDataSource implements BackendDataSource, AutoCloseable {
    
    private final ShardingSchema shardingSchema;
    
    private final Map<String, DataSource> dataSourceMap;
    
    public JDBCBackendDataSource(final ShardingSchema shardingSchema) {
        this.shardingSchema = shardingSchema;
        dataSourceMap = createDataSourceMap();
    }
    
    private Map<String, DataSource> createDataSourceMap() {
        // TODO getCircuitDataSourceMap if getCircuitBreakerDataSourceNames() is not empty
        return getNormalDataSourceMap(shardingSchema.getDataSources());
    }
    
    private Map<String, DataSource> getNormalDataSourceMap(final Map<String, DataSourceParameter> dataSourceParameters) {
        Map<String, DataSource> result = new LinkedHashMap<>(dataSourceParameters.size());
        for (Entry<String, DataSourceParameter> entry : dataSourceParameters.entrySet()) {
            try {
                result.put(entry.getKey(), getBackendDataSourceFactory().build(entry.getKey(), entry.getValue()));
            // CHECKSTYLE:OFF
            } catch (final Exception ex) {
                // CHECKSTYLE:ON
                throw new ShardingException(String.format("Can not build data source, name is `%s`.", entry.getKey()), ex);
            }
        }
        return result;
    }
    
    private JDBCBackendDataSourceFactory getBackendDataSourceFactory() {
        switch (GlobalRegistry.getInstance().getTransactionType()) {
            case XA:
                return new JDBCXABackendDataSourceFactory();
            default:
                return new JDBCRawBackendDataSourceFactory();
        }
    }
    
    /**
     * Get connection.
     *
     * @param dataSourceName data source name
     * @return connection
     * @throws SQLException SQL exception
     */
    public Connection getConnection(final String dataSourceName) throws SQLException {
        return getConnections(ConnectionMode.MEMORY_STRICTLY, dataSourceName, 1).get(0);
    }
    
    /**
     * Get connections.
     *
     * @param connectionMode connection mode 
     * @param dataSourceName data source name
     * @param connectionSize size of connections to be get
     * @return connections
     * @throws SQLException SQL exception
     */
    @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
    public List<Connection> getConnections(final ConnectionMode connectionMode, final String dataSourceName, final int connectionSize) throws SQLException {
        DataSource dataSource = getDataSourceMap().get(dataSourceName);
        if (1 == connectionSize) {
            return Collections.singletonList(dataSource.getConnection());
        }
        if (ConnectionMode.CONNECTION_STRICTLY == connectionMode) {
            return createConnections(dataSource, connectionSize);
        }
        synchronized (dataSource) {
            return createConnections(dataSource, connectionSize);
        }
    }
    
    private Map<String, DataSource> getDataSourceMap() {
        return shardingSchema.getDisabledDataSourceNames().isEmpty() ? dataSourceMap : getAvailableDataSourceMap();
    }
    
    private Map<String, DataSource> getAvailableDataSourceMap() {
        Map<String, DataSource> result = new LinkedHashMap<>(dataSourceMap);
        for (String each : shardingSchema.getDisabledDataSourceNames()) {
            result.remove(each);
        }
        return result;
    }
    
    private List<Connection> createConnections(final DataSource dataSource, final int connectionSize) throws SQLException {
        List<Connection> result = new ArrayList<>(connectionSize);
        for (int i = 0; i < connectionSize; i++) {
            result.add(dataSource.getConnection());
        }
        return result;
    }
    
    @Override
    public void close() {
        closeOriginalDataSources();
    }
    
    private void closeOriginalDataSources() {
        for (DataSource each : dataSourceMap.values()) {
            try {
                Method method = each.getClass().getDeclaredMethod("close");
                method.invoke(each);
            } catch (final ReflectiveOperationException ignored) {
            }
        }
    }
}