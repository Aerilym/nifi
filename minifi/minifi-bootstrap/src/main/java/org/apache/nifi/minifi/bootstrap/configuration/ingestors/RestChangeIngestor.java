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

package org.apache.nifi.minifi.bootstrap.configuration.ingestors;

import static org.apache.nifi.minifi.bootstrap.configuration.ConfigurationChangeCoordinator.NOTIFIER_INGESTORS_KEY;
import static org.apache.nifi.minifi.bootstrap.configuration.differentiators.WholeConfigDifferentiator.WHOLE_CONFIG_KEY;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.security.Security;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.io.IOUtils;
import org.apache.nifi.jetty.configuration.connector.StandardServerConnectorFactory;
import org.apache.nifi.minifi.bootstrap.ConfigurationFileHolder;
import org.apache.nifi.minifi.bootstrap.configuration.ConfigurationChangeNotifier;
import org.apache.nifi.minifi.bootstrap.configuration.ListenerHandleResult;
import org.apache.nifi.minifi.bootstrap.configuration.differentiators.Differentiator;
import org.apache.nifi.minifi.bootstrap.configuration.differentiators.WholeConfigDifferentiator;
import org.apache.nifi.minifi.bootstrap.configuration.ingestors.interfaces.ChangeIngestor;
import org.apache.nifi.minifi.bootstrap.util.ConfigTransformer;
import org.apache.nifi.security.ssl.StandardKeyStoreBuilder;
import org.apache.nifi.security.ssl.StandardSslContextBuilder;
import org.apache.nifi.security.util.TlsPlatform;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class RestChangeIngestor implements ChangeIngestor {

    private static final Map<String, Supplier<Differentiator<ByteBuffer>>> DIFFERENTIATOR_CONSTRUCTOR_MAP;

    static {
        HashMap<String, Supplier<Differentiator<ByteBuffer>>> tempMap = new HashMap<>();
        tempMap.put(WHOLE_CONFIG_KEY, WholeConfigDifferentiator::getByteBufferDifferentiator);

        DIFFERENTIATOR_CONSTRUCTOR_MAP = Collections.unmodifiableMap(tempMap);
        Security.addProvider(new BouncyCastleProvider());
    }


    public static final String GET_TEXT = "This is a config change listener for an Apache NiFi - MiNiFi instance.\n" +
            "Use this rest server to upload a conf.yml to configure the MiNiFi instance.\n" +
            "Send a POST http request to '/' to upload the file.";
    public static final String OTHER_TEXT = "This is not a support HTTP operation. Please use GET to get more information or POST to upload a new config.yml file.\n";
    public static final String POST = "POST";
    public static final String GET = "GET";
    private final static Logger logger = LoggerFactory.getLogger(RestChangeIngestor.class);
    private static final String RECEIVE_HTTP_BASE_KEY = NOTIFIER_INGESTORS_KEY + ".receive.http";
    public static final String PORT_KEY = RECEIVE_HTTP_BASE_KEY + ".port";
    public static final String HOST_KEY = RECEIVE_HTTP_BASE_KEY + ".host";
    public static final String TRUSTSTORE_LOCATION_KEY = RECEIVE_HTTP_BASE_KEY + ".truststore.location";
    public static final String TRUSTSTORE_PASSWORD_KEY = RECEIVE_HTTP_BASE_KEY + ".truststore.password";
    public static final String TRUSTSTORE_TYPE_KEY = RECEIVE_HTTP_BASE_KEY + ".truststore.type";
    public static final String KEYSTORE_LOCATION_KEY = RECEIVE_HTTP_BASE_KEY + ".keystore.location";
    public static final String KEYSTORE_PASSWORD_KEY = RECEIVE_HTTP_BASE_KEY + ".keystore.password";
    public static final String KEYSTORE_TYPE_KEY = RECEIVE_HTTP_BASE_KEY + ".keystore.type";
    public static final String NEED_CLIENT_AUTH_KEY = RECEIVE_HTTP_BASE_KEY + ".need.client.auth";
    public static final String DIFFERENTIATOR_KEY = RECEIVE_HTTP_BASE_KEY + ".differentiator";
    private final Server jetty;

    private volatile Differentiator<ByteBuffer> differentiator;
    private volatile ConfigurationChangeNotifier configurationChangeNotifier;
    private volatile ConfigurationFileHolder configurationFileHolder;
    private volatile Properties properties;

    public RestChangeIngestor() {
        QueuedThreadPool queuedThreadPool = new QueuedThreadPool();
        queuedThreadPool.setDaemon(true);
        jetty = new Server(queuedThreadPool);
    }

    @Override
    public void initialize(Properties properties, ConfigurationFileHolder configurationFileHolder, ConfigurationChangeNotifier configurationChangeNotifier) {
        this.configurationFileHolder = configurationFileHolder;
        this.properties = properties;
        logger.info("Initializing");
        String differentiatorName = properties.getProperty(DIFFERENTIATOR_KEY);

        if (differentiatorName != null && !differentiatorName.isEmpty()) {
            Supplier<Differentiator<ByteBuffer>> differentiatorSupplier = DIFFERENTIATOR_CONSTRUCTOR_MAP.get(differentiatorName);
            if (differentiatorSupplier == null) {
                throw new IllegalArgumentException("Property, " + DIFFERENTIATOR_KEY + ", has value " + differentiatorName + " which does not " +
                        "correspond to any in the PullHttpChangeIngestor Map:" + DIFFERENTIATOR_CONSTRUCTOR_MAP.keySet());
            }
            differentiator = differentiatorSupplier.get();
        } else {
            differentiator = WholeConfigDifferentiator.getByteBufferDifferentiator();
        }
        differentiator.initialize(configurationFileHolder);

        // create the secure connector if keystore location is specified
        if (properties.getProperty(KEYSTORE_LOCATION_KEY) != null) {
            createSecureConnector(properties);
        } else {
            // create the unsecure connector otherwise
            createConnector(properties);
        }

        this.configurationChangeNotifier = configurationChangeNotifier;

        HandlerCollection handlerCollection = new HandlerCollection(true);
        handlerCollection.addHandler(new JettyHandler());
        jetty.setHandler(handlerCollection);
    }

    @Override
    public void start() {
        try {
            jetty.start();
            logger.info("RestChangeIngester has started and is listening on port {}.", getPort());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }


    @Override
    public void close() throws IOException {
        logger.warn("Shutting down the jetty server");
        try {
            jetty.stop();
            jetty.destroy();
        } catch (Exception e) {
            throw new IOException(e);
        }
        logger.warn("Done shutting down the jetty server");
    }

    public URI getURI() {
        return jetty.getURI();
    }

    public int getPort() {
        if (!jetty.isStarted()) {
            throw new IllegalStateException("Jetty server not started");
        }
        return ((ServerConnector) jetty.getConnectors()[0]).getLocalPort();
    }

    private void createConnector(Properties properties) {
        ServerConnector http = new ServerConnector(jetty);

        http.setPort(Integer.parseInt(properties.getProperty(PORT_KEY, "0")));
        http.setHost(properties.getProperty(HOST_KEY, "localhost"));

        // Severely taxed or distant environments may have significant delays when executing.
        http.setIdleTimeout(30000L);
        jetty.addConnector(http);

        logger.info("Added an http connector on the host '{}' and port '{}'", http.getHost(), http.getPort());
    }

    private void createSecureConnector(Properties properties) {
        KeyStore keyStore;
        KeyStore trustStore = null;

        try (FileInputStream keyStoreStream = new FileInputStream(properties.getProperty(KEYSTORE_LOCATION_KEY))) {
            keyStore = new StandardKeyStoreBuilder()
                .type(properties.getProperty(KEYSTORE_TYPE_KEY))
                .inputStream(keyStoreStream)
                .password(properties.getProperty(KEYSTORE_PASSWORD_KEY).toCharArray())
                .build();
        } catch (IOException ioe) {
            throw new UncheckedIOException("Key Store loading failed", ioe);
        }

        if (properties.getProperty(TRUSTSTORE_LOCATION_KEY) != null) {
            try (FileInputStream trustStoreStream = new FileInputStream(properties.getProperty(TRUSTSTORE_LOCATION_KEY))) {
                trustStore = new StandardKeyStoreBuilder()
                    .type(properties.getProperty(TRUSTSTORE_TYPE_KEY))
                    .inputStream(trustStoreStream)
                    .password(properties.getProperty(TRUSTSTORE_PASSWORD_KEY).toCharArray())
                    .build();
            } catch (IOException ioe) {
                throw new UncheckedIOException("Trust Store loading failed", ioe);
            }
        }

        SSLContext sslContext = new StandardSslContextBuilder()
            .keyStore(keyStore)
            .keyPassword(properties.getProperty(KEYSTORE_PASSWORD_KEY).toCharArray())
            .trustStore(trustStore)
            .build();

        StandardServerConnectorFactory serverConnectorFactory = new StandardServerConnectorFactory(jetty, Integer.parseInt(properties.getProperty(PORT_KEY, "0")));
        serverConnectorFactory.setNeedClientAuth(Boolean.parseBoolean(properties.getProperty(NEED_CLIENT_AUTH_KEY, "true")));
        serverConnectorFactory.setSslContext(sslContext);
        serverConnectorFactory.setIncludeSecurityProtocols(TlsPlatform.getPreferredProtocols().toArray(new String[0]));

        ServerConnector https = serverConnectorFactory.getServerConnector();
        https.setHost(properties.getProperty(HOST_KEY, "localhost"));

        // add the connector
        jetty.addConnector(https);

        logger.info("HTTPS Connector added for Host [{}] and Port [{}]", https.getHost(), https.getPort());
    }

    protected void setDifferentiator(Differentiator<ByteBuffer> differentiator) {
        this.differentiator = differentiator;
    }

    private class JettyHandler extends AbstractHandler {

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
                throws IOException {

            logRequest(request);

            baseRequest.setHandled(true);

            if (POST.equals(request.getMethod())) {
                int statusCode;
                String responseText;
                try {
                    ByteBuffer readOnlyNewConfig =
                        ConfigTransformer.overrideNonFlowSectionsFromOriginalSchema(
                            IOUtils.toByteArray(request.getInputStream()), configurationFileHolder.getConfigFileReference().get().duplicate(), properties);

                    if (differentiator.isNew(readOnlyNewConfig)) {

                        Collection<ListenerHandleResult> listenerHandleResults = configurationChangeNotifier.notifyListeners(readOnlyNewConfig);

                        statusCode = 200;
                        for (ListenerHandleResult result : listenerHandleResults) {
                            if (!result.succeeded()) {
                                statusCode = 500;
                                break;
                            }
                        }
                        responseText = getPostText(listenerHandleResults);
                    } else {
                        statusCode = 409;
                        responseText = "Request received but instance is already running this config.";
                    }
                } catch (Exception e) {
                    logger.error("Failed to override config file", e);
                    statusCode = 500;
                    responseText = "Failed to override config file";
                }

                writeOutput(response, responseText, statusCode);
            } else if (GET.equals(request.getMethod())) {
                writeOutput(response, GET_TEXT, 200);
            } else {
                writeOutput(response, OTHER_TEXT, 404);
            }
        }

        private String getPostText(Collection<ListenerHandleResult> listenerHandleResults) {
            StringBuilder postResult = new StringBuilder("The result of notifying listeners:\n");

            for (ListenerHandleResult result : listenerHandleResults) {
                postResult.append(result.toString());
                postResult.append("\n");
            }

            return postResult.toString();
        }

        private void writeOutput(HttpServletResponse response, String responseText, int responseCode) throws IOException {
            response.setStatus(responseCode);
            response.setContentType("text/plain");
            response.setContentLength(responseText.length());
            try (PrintWriter writer = response.getWriter()) {
                writer.print(responseText);
                writer.flush();
            }
        }

        private void logRequest(HttpServletRequest request) {
            logger.info(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>");
            logger.info("request method = " + request.getMethod());
            logger.info("request url = " + request.getRequestURL());
            logger.info("context path = " + request.getContextPath());
            logger.info("request content type = " + request.getContentType());
            logger.info("<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<");
        }

    }
}
