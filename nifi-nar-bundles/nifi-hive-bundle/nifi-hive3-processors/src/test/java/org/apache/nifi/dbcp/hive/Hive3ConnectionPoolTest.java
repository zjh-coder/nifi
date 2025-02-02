/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.nifi.dbcp.hive;

import org.apache.commons.dbcp2.BasicDataSource;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.hadoop.KerberosProperties;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.util.MockConfigurationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.UndeclaredThrowableException;
import java.security.PrivilegedExceptionAction;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class Hive3ConnectionPoolTest {
    private UserGroupInformation userGroupInformation;
    private Hive3ConnectionPool hive3ConnectionPool;
    private BasicDataSource basicDataSource;
    private ComponentLog componentLog;
    private KerberosProperties kerberosProperties;
    private File krb5conf = new File("src/test/resources/krb5.conf");

    @BeforeEach
    public void setup() throws Exception {
        // have to initialize this system property before anything else
        System.setProperty("java.security.krb5.conf", krb5conf.getAbsolutePath());
        System.setProperty("java.security.krb5.realm", "nifi.com");
        System.setProperty("java.security.krb5.kdc", "nifi.kdc");

        userGroupInformation = mock(UserGroupInformation.class);
        basicDataSource = mock(BasicDataSource.class);
        componentLog = mock(ComponentLog.class);
        kerberosProperties = mock(KerberosProperties.class);

        when(userGroupInformation.doAs(isA(PrivilegedExceptionAction.class))).thenAnswer(invocation -> {
            try {
                return ((PrivilegedExceptionAction) invocation.getArguments()[0]).run();
            } catch (IOException | Error | RuntimeException | InterruptedException e) {
                throw e;
            } catch (Throwable e) {
                throw new UndeclaredThrowableException(e);
            }
        });

        when(kerberosProperties.getKerberosKeytab()).thenReturn(new PropertyDescriptor.Builder()
                .name("Kerberos Principal")
                .addValidator(StandardValidators.ATTRIBUTE_EXPRESSION_LANGUAGE_VALIDATOR)
                .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
                .build());

        when(kerberosProperties.getKerberosPrincipal()).thenReturn(new PropertyDescriptor.Builder()
                .name("Kerberos Keytab")
                .addValidator(StandardValidators.ATTRIBUTE_EXPRESSION_LANGUAGE_VALIDATOR)
                .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
                .build());

        initPool();
    }

    private void initPool() throws Exception {
        hive3ConnectionPool = new Hive3ConnectionPool();

        Field ugiField = Hive3ConnectionPool.class.getDeclaredField("ugi");
        ugiField.setAccessible(true);
        ugiField.set(hive3ConnectionPool, userGroupInformation);

        Field dataSourceField = Hive3ConnectionPool.class.getDeclaredField("dataSource");
        dataSourceField.setAccessible(true);
        dataSourceField.set(hive3ConnectionPool, basicDataSource);

        Field componentLogField = AbstractControllerService.class.getDeclaredField("logger");
        componentLogField.setAccessible(true);
        componentLogField.set(hive3ConnectionPool, componentLog);

        Field kerberosPropertiesField = Hive3ConnectionPool.class.getDeclaredField("kerberosProperties");
        kerberosPropertiesField.setAccessible(true);
        kerberosPropertiesField.set(hive3ConnectionPool, kerberosProperties);
    }

    @Test
    public void testGetConnectionSqlException() throws SQLException {
        SQLException sqlException = new SQLException("bad sql");
        when(basicDataSource.getConnection()).thenThrow(sqlException);
        ProcessException pe = assertThrows(ProcessException.class, () -> hive3ConnectionPool.getConnection());
        assertEquals(sqlException, pe.getCause());
    }

    @Test
    public void testExpressionLanguageSupport() throws Exception {
        final String URL = "jdbc:hive2://localhost:10000/default";
        final String USER = "user";
        final String PASS = "pass";
        final int MAX_CONN = 7;
        final int MIN_IDLE = 1;
        final int MAX_IDLE = 6;
        final String EVICTION_RUN_PERIOD = "10 mins";
        final String MIN_EVICTABLE_IDLE_TIME = "1 mins";
        final String SOFT_MIN_EVICTABLE_IDLE_TIME = "1 mins";
        final String MAX_CONN_LIFETIME = "1 min";
        final String MAX_WAIT = "10 sec"; // 10000 milliseconds
        final String CONF = "/path/to/hive-site.xml";
        hive3ConnectionPool = new Hive3ConnectionPool();

        Map<PropertyDescriptor, String> props = new HashMap<PropertyDescriptor, String>() {{
            put(Hive3ConnectionPool.DATABASE_URL, "${url}");
            put(Hive3ConnectionPool.DB_USER, "${username}");
            put(Hive3ConnectionPool.DB_PASSWORD, "${password}");
            put(Hive3ConnectionPool.MAX_TOTAL_CONNECTIONS, "${maxconn}");
            put(Hive3ConnectionPool.MAX_WAIT_TIME, "${maxwait}");
            put(Hive3ConnectionPool.MAX_CONN_LIFETIME, "${maxconnlifetime}");
            put(Hive3ConnectionPool.MIN_IDLE, "${min.idle}");
            put(Hive3ConnectionPool.MAX_IDLE, "${max.idle}");
            put(Hive3ConnectionPool.EVICTION_RUN_PERIOD, "${eviction.run}");
            put(Hive3ConnectionPool.MIN_EVICTABLE_IDLE_TIME, "${min.evictable.idle}");
            put(Hive3ConnectionPool.SOFT_MIN_EVICTABLE_IDLE_TIME, "${soft.min.evictable.idle}");
            put(Hive3ConnectionPool.HIVE_CONFIGURATION_RESOURCES, "${hiveconf}");
        }};

        Map<String,String> registry = new HashMap<String,String>();
        registry.put("url", URL);
        registry.put("username", USER);
        registry.put("password", PASS);
        registry.put("maxconn", Integer.toString(MAX_CONN));
        registry.put("maxwait", MAX_WAIT);
        registry.put("maxconnlifetime", MAX_CONN_LIFETIME);
        registry.put("min.idle", Integer.toString(MIN_IDLE));
        registry.put("max.idle", Integer.toString(MAX_IDLE));
        registry.put("eviction.run", EVICTION_RUN_PERIOD);
        registry.put("min.evictable.idle", MIN_EVICTABLE_IDLE_TIME);
        registry.put("soft.min.evictable.idle", SOFT_MIN_EVICTABLE_IDLE_TIME);
        registry.put("hiveconf", CONF);


        MockConfigurationContext context = new MockConfigurationContext(props, null, registry);
        hive3ConnectionPool.onConfigured(context);

        Field dataSourceField = Hive3ConnectionPool.class.getDeclaredField("dataSource");
        dataSourceField.setAccessible(true);
        basicDataSource = (BasicDataSource) dataSourceField.get(hive3ConnectionPool);
        assertEquals(URL, basicDataSource.getUrl());
        assertEquals(USER, basicDataSource.getUsername());
        assertEquals(PASS, basicDataSource.getPassword());
        assertEquals(MAX_CONN, basicDataSource.getMaxTotal());
        assertEquals(10000L, basicDataSource.getMaxWaitMillis());
        assertEquals(URL, hive3ConnectionPool.getConnectionURL());
    }

    @EnabledIfSystemProperty(
            named = "nifi.test.unstable",
            matches = "true",
            disabledReason = "Kerberos does not seem to be properly handled in Travis build, but, locally, this test should successfully run")
    @Test
    public void testKerberosAuthException() throws Exception {
        final String URL = "jdbc:hive2://localhost:10000/default";
        final String conf = "src/test/resources/hive-site-security.xml";
        final String ktab = "src/test/resources/fake.keytab";
        final String kprinc = "bad@PRINCIPAL.COM";

        KerberosProperties kerbProperties = new KerberosProperties(krb5conf);

        Map<PropertyDescriptor, String> props = new HashMap<PropertyDescriptor, String>() {{
            put(Hive3ConnectionPool.DATABASE_URL, "${url}");
            put(Hive3ConnectionPool.HIVE_CONFIGURATION_RESOURCES, "${conf}");
            put(kerbProperties.getKerberosKeytab(), "${ktab}");
            put(kerbProperties.getKerberosPrincipal(), "${kprinc}");
        }};

        Map<String,String> registry = new HashMap<String,String>();
        registry.put("url", URL);
        registry.put("conf", conf);
        registry.put("ktab", ktab);
        registry.put("kprinc", kprinc);

        MockConfigurationContext context = new MockConfigurationContext(props, null, registry);
        assertThrows(InitializationException.class, () -> hive3ConnectionPool.onConfigured(context));
    }
}
