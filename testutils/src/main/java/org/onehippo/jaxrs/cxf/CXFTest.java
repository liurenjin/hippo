/*
 *  Copyright 2015 Hippo B.V. (http://www.onehippo.com)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.onehippo.jaxrs.cxf;

import java.util.HashSet;
import java.util.Set;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Invocation.Builder;
import javax.ws.rs.core.Application;

import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;
import com.jayway.restassured.RestAssured;

import org.apache.cxf.endpoint.Server;
import org.apache.cxf.jaxrs.JAXRSServerFactoryBean;
import org.apache.cxf.jaxrs.utils.ResourceUtils;
import org.apache.cxf.testutil.common.TestUtil;
import org.junit.After;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * Utility class for unit testing of JAXRS endpoints; for examples how to use this class, see the unit tests.
 *
 * <p>The smallest example can be found in the test class {@code org.onehippo.jaxrs.cxf.TestSingleEndpoint}. Most tests
 * use <a href="https://github.com/jayway/rest-assured">REST-assured</a> to write the test conditions. See their
 * <a href="https://github.com/jayway/rest-assured">GitHub page</a> or
 * <a href="https://github.com/jayway/rest-assured/wiki/Usage">user guide</a> for more information. Be sure to check
 * the <a href="https://github.com/jayway/rest-assured/wiki/Usage#examples">examples</a> section.</p>
 *
 * <p>Next to testing of the plain JSON output, it is also possible to test using a JAXRS client, see the test class
 * {@code org.onehippo.jaxrs.cxf.TestJaxrsClient}.</p>
 *
 * <p>In general the CXFTest has been tested to be compatible with the following frameworks found in the Hippo stack:
 * <ul>
 *     <li>
 *         Spring: see test class {@code org.onehippo.jaxrs.cxf.TestCompatibilityWithSpring}
 *     </li>
 *     <li>
 *         PowerMock: see test class {@code org.onehippo.jaxrs.cxf.TestCompatibilityWithPowerMock}, do note the three
 *         class level annotations
 *         ({@literal@RunWith(PowerMockRunner.class) @PowerMockIgnore("javax.net.ssl.*") @PrepareForTest(Class<?>)})
 *         that are all necessary to make the test succeed.
 *     </li>
 * </ul>
 * </p>
 */
public class CXFTest {

    private Class<?> defaultSerializer = JacksonJsonProvider.class;

    private String address;
    private Server server;
    private Set<Class<?>> clientClasses;
    private Set<Object> clientSingletons;

    protected Class<?> getDefaultSerializer() {
        return defaultSerializer;
    }

    @SuppressWarnings("unused")
    protected void setDefaultSerializer(Class<?> defaultSerializer) {
        this.defaultSerializer = defaultSerializer;
    }

    @SuppressWarnings("unused")
    protected Builder createJaxrsClient() {
        return createJaxrsClient("", APPLICATION_JSON);
    }

    protected Builder createJaxrsClient(final String url) {
        return createJaxrsClient(url, APPLICATION_JSON);
    }

    protected Builder createJaxrsClient(final String url, final String mediaType) {
        Client client = ClientBuilder.newClient();
        for (Class<?> cls: clientClasses) {
            client.register(cls);
        }
        for (Object obj: clientSingletons) {
            client.register(obj);
        }
        return client.target(address).path(url).request(mediaType);
    }

    public class Config {
        private final Set<Class<?>> clientClasses = new HashSet<>();
        private final Set<Object>   clientSingletons = new HashSet<>();
        private final Set<Class<?>> serverClasses = new HashSet<>();
        private final Set<Object>   serverSingletons = new HashSet<>();

        public Config addClientClass(final Class<?> clientClass) {
            clientClasses.add(clientClass);
            return this;
        }
        @SuppressWarnings("unused")
        public Config addClientSingleton(final Object singleton) {
            clientSingletons.add(singleton);
            return this;
        }
        public Config addServerClass(final Class<?> serverClass) {
            serverClasses.add(serverClass);
            return this;
        }
        public Config addServerSingleton(final Object singleton) {
            serverSingletons.add(singleton);
            return this;
        }
        public Set<Class<?>> getClientClasses() {
            return clientClasses;
        }
        public Set<Object> getClientSingletons() {
            return clientSingletons;
        }
        public Set<Class<?>> getServerClasses() {
            return serverClasses;
        }
        public Set<Object> getServerSingletons() {
            return serverSingletons;
        }
    }

    protected Config createDefaultConfig() {
        return new Config()
                .addClientClass(getDefaultSerializer())
                .addServerClass(getDefaultSerializer());
    }
    
    protected void setup(Object endpointSingleton) {
        Config config = createDefaultConfig()
                .addServerSingleton(endpointSingleton);
        setup(config);
    }

    protected void setup(Object endpointSingleton, Class<?> objectMapper) {
        Config config = createDefaultConfig()
                .addServerSingleton(endpointSingleton)
                .addServerClass(objectMapper)
                .addClientClass(objectMapper);
        setup(config);
    }

    protected void setup(Class<?> endpointClass) {
        Config config = createDefaultConfig()
                .addServerClass(endpointClass);
        setup(config);
    }

    protected void setup(Class<?> endpointClass, Class<?> objectMapper) {
        Config config = createDefaultConfig()
                .addServerClass(endpointClass)
                .addServerClass(objectMapper)
                .addClientClass(objectMapper);
        setup(config);
    }

    protected String getServerHost() {
        return "http://localhost";
    }

    protected int getServerPort() {
        return Integer.parseInt(TestUtil.getPortNumber(getClass()));
    }

    protected String getServerAddress() {
        return getServerHost() + ":" + getServerPort();
    }

    protected void setup(Config config) {
        address = getServerAddress();

        Application application = new Application() {
            public Set<Class<?>> getClasses() {
                return config.getServerClasses();
            }

            public Set<Object> getSingletons() {
                return config.getServerSingletons();
            }
        };
        JAXRSServerFactoryBean endpointFactory = ResourceUtils.createApplication(application, true);
        endpointFactory.setAddress(address);

        server = endpointFactory.create();
        server.start();

        clientClasses = config.getClientClasses();
        clientSingletons = config.getClientSingletons();

        RestAssured.baseURI = getServerHost();
        RestAssured.port = getServerPort();
    }

    @After
    public void tearDownBackend() {
        if (server != null) {
            server.destroy();
            server = null;
        }
    }
}