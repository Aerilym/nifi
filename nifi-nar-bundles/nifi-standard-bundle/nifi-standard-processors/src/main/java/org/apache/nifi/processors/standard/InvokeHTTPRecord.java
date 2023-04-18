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

package org.apache.nifi.processors.standard;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.apache.nifi.annotation.behavior.DynamicProperty;
import org.apache.nifi.annotation.behavior.EventDriven;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.InputRequirement.Requirement;
import org.apache.nifi.annotation.behavior.SideEffectFree;
import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.SeeAlso;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.components.AllowableValue;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.lookup.LookupFailureException;
import org.apache.nifi.lookup.LookupService;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.io.InputStreamCallback;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.record.path.FieldValue;
import org.apache.nifi.record.path.RecordPath;
import org.apache.nifi.record.path.RecordPathResult;
import org.apache.nifi.record.path.util.RecordPathCache;
import org.apache.nifi.record.path.validation.RecordPathValidator;
import org.apache.nifi.schema.access.SchemaNotFoundException;
import org.apache.nifi.serialization.MalformedRecordException;
import org.apache.nifi.serialization.RecordReader;
import org.apache.nifi.serialization.RecordReaderFactory;
import org.apache.nifi.serialization.RecordSetWriter;
import org.apache.nifi.serialization.RecordSetWriterFactory;
import org.apache.nifi.serialization.WriteResult;
import org.apache.nifi.serialization.record.DataType;
import org.apache.nifi.serialization.record.Record;
import org.apache.nifi.serialization.record.RecordField;
import org.apache.nifi.serialization.record.RecordFieldType;
import org.apache.nifi.serialization.record.RecordSchema;
import org.apache.nifi.serialization.record.util.DataTypeUtils;
import org.apache.nifi.util.Tuple;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.burgstaller.okhttp.AuthenticationCacheInterceptor;
import com.burgstaller.okhttp.CachingAuthenticatorDecorator;
import com.burgstaller.okhttp.digest.CachingAuthenticator;
import com.burgstaller.okhttp.digest.DigestAuthenticator;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.Proxy;
import java.net.Proxy.Type;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.Principal;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.ConnectionPool;
import okhttp3.Credentials;
import okhttp3.Handshake;
import okhttp3.Headers;
import okhttp3.JavaNetCookieJar;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.MultipartBody.Builder;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;
import okio.GzipSink;
import okio.Okio;
import okio.Source;
import org.apache.commons.io.input.TeeInputStream;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.annotation.behavior.DynamicProperties;
import org.apache.nifi.annotation.behavior.DynamicProperty;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.InputRequirement.Requirement;
import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.behavior.SupportsSensitiveDynamicProperties;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.components.AllowableValue;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.context.PropertyContext;
import org.apache.nifi.expression.AttributeExpression;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.oauth2.OAuth2AccessTokenProvider;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.DataUnit;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.processors.standard.http.ContentEncodingStrategy;
import org.apache.nifi.processors.standard.http.FlowFileNamingStrategy;
import org.apache.nifi.processors.standard.http.CookieStrategy;
import org.apache.nifi.processors.standard.http.HttpHeader;
import org.apache.nifi.processors.standard.http.HttpMethod;
import org.apache.nifi.processors.standard.util.ProxyAuthenticator;
import org.apache.nifi.processors.standard.util.SoftLimitBoundedByteArrayOutputStream;
import org.apache.nifi.proxy.ProxyConfiguration;
import org.apache.nifi.proxy.ProxySpec;
import org.apache.nifi.security.util.SslContextFactory;
import org.apache.nifi.security.util.TlsConfiguration;
import org.apache.nifi.security.util.TlsException;
import org.apache.nifi.ssl.SSLContextService;
import org.apache.nifi.stream.io.StreamUtils;

@EventDriven
@SideEffectFree
@SupportsBatching
@InputRequirement(Requirement.INPUT_REQUIRED)
@WritesAttributes({
    @WritesAttribute(attribute = "mime.type", description = "Sets the mime.type attribute to the MIME Type specified by the Record Writer"),
    @WritesAttribute(attribute = "record.count", description = "The number of records in the FlowFile")
})
@Tags({"lookup", "enrichment", "route", "record", "csv", "json", "avro", "database", "db", "logs", "convert", "filter"})
@CapabilityDescription("Extracts one or more fields from a Record and looks up a value for those fields in a LookupService. If a result is returned by the LookupService, "
    + "that result is optionally added to the Record. In this case, the processor functions as an Enrichment processor. Regardless, the Record is then "
    + "routed to either the 'matched' relationship or 'unmatched' relationship (if the 'Routing Strategy' property is configured to do so), "
    + "indicating whether or not a result was returned by the LookupService, allowing the processor to also function as a Routing processor. "
    + "The \"coordinates\" to use for looking up a value in the Lookup Service are defined by adding a user-defined property. Each property that is added will have an entry added "
    + "to a Map, where the name of the property becomes the Map Key and the value returned by the RecordPath becomes the value for that key. If multiple values are returned by the "
    + "RecordPath, then the Record will be routed to the 'unmatched' relationship (or 'success', depending on the 'Routing Strategy' property's configuration). "
    + "If one or more fields match the Result RecordPath, all fields "
    + "that match will be updated. If there is no match in the configured LookupService, then no fields will be updated. I.e., it will not overwrite an existing value in the Record "
    + "with a null value. Please note, however, that if the results returned by the LookupService are not accounted for in your schema (specifically, "
    + "the schema that is configured for your Record Writer) then the fields will not be written out to the FlowFile.")
@DynamicProperty(name = "Value To Lookup", value = "Valid Record Path", expressionLanguageScope = ExpressionLanguageScope.FLOWFILE_ATTRIBUTES,
                    description = "A RecordPath that points to the field whose value will be looked up in the configured Lookup Service")
@SeeAlso(value = {ConvertRecord.class, SplitRecord.class},
        classNames = {"org.apache.nifi.lookup.SimpleKeyValueLookupService", "org.apache.nifi.lookup.maxmind.IPLookupService", "org.apache.nifi.lookup.db.DatabaseRecordLookupService"})
public class InvokeHTTPRecord extends AbstractProcessor {

    public final static String STATUS_CODE = "invokehttp.status.code";
    public final static String STATUS_MESSAGE = "invokehttp.status.message";
    public final static String RESPONSE_BODY = "invokehttp.response.body";
    public final static String REQUEST_URL = "invokehttp.request.url";
    public final static String REQUEST_DURATION = "invokehttp.request.duration";
    public final static String RESPONSE_URL = "invokehttp.response.url";
    public final static String TRANSACTION_ID = "invokehttp.tx.id";
    public final static String REMOTE_DN = "invokehttp.remote.dn";
    public final static String EXCEPTION_CLASS = "invokehttp.java.exception.class";
    public final static String EXCEPTION_MESSAGE = "invokehttp.java.exception.message";

    public static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    protected static final String FORM_DATA_NAME_BASE = "post:form";
    private static final Pattern FORM_DATA_NAME_PARAMETER_PATTERN = Pattern.compile("post:form:(?<formDataName>.*)$");
    private static final String FORM_DATA_NAME_GROUP = "formDataName";

    private static final Set<String> IGNORED_REQUEST_ATTRIBUTES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            STATUS_CODE,
            STATUS_MESSAGE,
            RESPONSE_BODY,
            REQUEST_URL,
            RESPONSE_URL,
            TRANSACTION_ID,
            REMOTE_DN,
            EXCEPTION_CLASS,
            EXCEPTION_MESSAGE,
            CoreAttributes.UUID.key(),
            CoreAttributes.FILENAME.key(),
            CoreAttributes.PATH.key()
    )));

    private final RecordPathCache recordPathCache = new RecordPathCache(25);
    private volatile LookupService<?> lookupService;

    static final AllowableValue ROUTE_TO_SUCCESS = new AllowableValue("route-to-success", "Route to 'success'",
        "Records will be routed to a 'success' Relationship regardless of whether or not there is a match in the configured Lookup Service");
    static final AllowableValue ROUTE_TO_MATCHED_UNMATCHED = new AllowableValue("route-to-matched-unmatched", "Route to 'matched' or 'unmatched'",
        "Records will be routed to either a 'matched' or an 'unmatched' Relationship depending on whether or not there was a match in the configured Lookup Service. "
            + "A single input FlowFile may result in two different output FlowFiles.");

    static final AllowableValue RESULT_ENTIRE_RECORD = new AllowableValue("insert-entire-record", "Insert Entire Record",
        "The entire Record that is retrieved from the Lookup Service will be inserted into the destination path.");
    static final AllowableValue RESULT_RECORD_FIELDS = new AllowableValue("record-fields", "Insert Record Fields",
        "All of the fields in the Record that is retrieved from the Lookup Service will be inserted into the destination path.");

    static final AllowableValue USE_PROPERTY = new AllowableValue("use-property", "Use Property",
            "The \"Result RecordPath\" property will be used to determine which part of the record should be updated with the value returned by the Lookup Service");
    static final AllowableValue REPLACE_EXISTING_VALUES = new AllowableValue("replace-existing-values", "Replace Existing Values",
            "The \"Result RecordPath\" property will be ignored and the lookup service must be a single simple key lookup service. Every dynamic property value should "
            + "be a record path. For each dynamic property, the value contained in the field corresponding to the record path will be used as the key in the Lookup "
            + "Service and the value returned by the Lookup Service will be used to replace the existing value. It is possible to configure multiple dynamic properties "
            + "to replace multiple values in one execution. This strategy only supports simple types replacements (strings, integers, etc).");

    static final PropertyDescriptor RECORD_READER = new PropertyDescriptor.Builder()
        .name("record-reader")
        .displayName("Record Reader")
        .description("Specifies the Controller Service to use for reading incoming data")
        .identifiesControllerService(RecordReaderFactory.class)
        .required(true)
        .build();

    static final PropertyDescriptor RECORD_WRITER = new PropertyDescriptor.Builder()
        .name("record-writer")
        .displayName("Record Writer")
        .description("Specifies the Controller Service to use for writing out the records")
        .identifiesControllerService(RecordSetWriterFactory.class)
        .required(true)
        .build();

    static final PropertyDescriptor LOOKUP_SERVICE = new PropertyDescriptor.Builder()
        .name("lookup-service")
        .displayName("Lookup Service")
        .description("The Lookup Service to use in order to lookup a value in each Record")
        .identifiesControllerService(LookupService.class)
        .required(true)
        .build();

    static final PropertyDescriptor RESULT_RECORD_PATH = new PropertyDescriptor.Builder()
        .name("result-record-path")
        .displayName("Result RecordPath")
        .description("A RecordPath that points to the field whose value should be updated with whatever value is returned from the Lookup Service. "
            + "If not specified, the value that is returned from the Lookup Service will be ignored, except for determining whether the FlowFile should "
            + "be routed to the 'matched' or 'unmatched' Relationship.")
        .addValidator(new RecordPathValidator())
        .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
        .required(false)
        .build();

    static final PropertyDescriptor RESULT_CONTENTS = new PropertyDescriptor.Builder()
        .name("result-contents")
        .displayName("Record Result Contents")
        .description("When a result is obtained that contains a Record, this property determines whether the Record itself is inserted at the configured "
            + "path or if the contents of the Record (i.e., the sub-fields) will be inserted at the configured path.")
        .allowableValues(RESULT_ENTIRE_RECORD, RESULT_RECORD_FIELDS)
        .defaultValue(RESULT_ENTIRE_RECORD.getValue())
        .required(true)
        .build();

    static final PropertyDescriptor ROUTING_STRATEGY = new PropertyDescriptor.Builder()
        .name("routing-strategy")
        .displayName("Routing Strategy")
        .description("Specifies how to route records after a Lookup has completed")
        .expressionLanguageSupported(ExpressionLanguageScope.NONE)
        .allowableValues(ROUTE_TO_SUCCESS, ROUTE_TO_MATCHED_UNMATCHED)
        .defaultValue(ROUTE_TO_SUCCESS.getValue())
        .required(true)
        .build();

    static final PropertyDescriptor REPLACEMENT_STRATEGY = new PropertyDescriptor.Builder()
        .name("record-update-strategy")
        .displayName("Record Update Strategy")
        .description("This property defines the strategy to use when updating the record with the value returned by the Lookup Service.")
        .expressionLanguageSupported(ExpressionLanguageScope.NONE)
        .allowableValues(REPLACE_EXISTING_VALUES, USE_PROPERTY)
        .defaultValue(USE_PROPERTY.getValue())
        .required(true)
        .build();

    static final PropertyDescriptor CACHE_SIZE = new PropertyDescriptor.Builder()
        .name("record-path-lookup-miss-result-cache-size")
        .displayName("Cache Size")
        .description("Specifies how many lookup values/records should be cached."
                + "Setting this property to zero means no caching will be done and the table will be queried for each lookup value in each record. If the lookup "
                + "table changes often or the most recent data must be retrieved, do not use the cache.")
        .addValidator(StandardValidators.NON_NEGATIVE_INTEGER_VALIDATOR)
        .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
        .defaultValue("0")
        .required(true)
        .build();

    static final Relationship REL_MATCHED = new Relationship.Builder()
        .name("matched")
        .description("All records for which the lookup returns a value will be routed to this relationship")
        .build();
    static final Relationship REL_UNMATCHED = new Relationship.Builder()
        .name("unmatched")
        .description("All records for which the lookup does not have a matching value will be routed to this relationship")
        .build();
    static final Relationship REL_SUCCESS = new Relationship.Builder()
        .name("success")
        .description("All records will be sent to this Relationship if configured to do so, unless a failure occurs")
        .build();
    static final Relationship REL_FAILURE = new Relationship.Builder()
        .name("failure")
        .description("If a FlowFile cannot be enriched, the unchanged FlowFile will be routed to this relationship")
        .build();

        public static final PropertyDescriptor HTTP_METHOD = new PropertyDescriptor.Builder()
            .name("HTTP Method")
            .description("HTTP request method (GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS). Arbitrary methods are also supported. "
                    + "Methods other than POST, PUT and PATCH will be sent without a message body.")
            .required(true)
            .defaultValue(HttpMethod.GET.name())
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.createAttributeExpressionLanguageValidator(AttributeExpression.ResultType.STRING))
            .build();

    public static final PropertyDescriptor HTTP_URL = new PropertyDescriptor.Builder()
            .name("Remote URL")
            .displayName("HTTP URL")
            .description("HTTP remote URL including a scheme of http or https, as well as a hostname or IP address with optional port and path elements.")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.URL_VALIDATOR)
            .build();

    public static final PropertyDescriptor HTTP2_DISABLED = new PropertyDescriptor.Builder()
            .name("disable-http2")
            .displayName("HTTP/2 Disabled")
            .description("Disable negotiation of HTTP/2 protocol. HTTP/2 requires TLS. HTTP/1.1 protocol supported is required when HTTP/2 is disabled.")
            .required(true)
            .defaultValue("False")
            .allowableValues("True", "False")
            .build();

    public static final PropertyDescriptor SSL_CONTEXT_SERVICE = new PropertyDescriptor.Builder()
            .name("SSL Context Service")
            .description("SSL Context Service provides trusted certificates and client certificates for TLS communication.")
            .required(false)
            .identifiesControllerService(SSLContextService.class)
            .build();

    public static final PropertyDescriptor SOCKET_CONNECT_TIMEOUT = new PropertyDescriptor.Builder()
            .name("Connection Timeout")
            .displayName("Socket Connect Timeout")
            .description("Maximum time to wait for initial socket connection to the HTTP URL.")
            .required(true)
            .defaultValue("5 secs")
            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
            .build();

    public static final PropertyDescriptor SOCKET_READ_TIMEOUT = new PropertyDescriptor.Builder()
            .name("Read Timeout")
            .displayName("Socket Read Timeout")
            .description("Maximum time to wait for receiving responses from a socket connection to the HTTP URL.")
            .required(true)
            .defaultValue("15 secs")
            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
            .build();

    public static final PropertyDescriptor SOCKET_IDLE_TIMEOUT = new PropertyDescriptor.Builder()
            .name("idle-timeout")
            .displayName("Socket Idle Timeout")
            .description("Maximum time to wait before closing idle connections to the HTTP URL.")
            .required(true)
            .defaultValue("5 mins")
            .addValidator(StandardValidators.createTimePeriodValidator(1, TimeUnit.MILLISECONDS, Integer.MAX_VALUE, TimeUnit.SECONDS))
            .build();

    public static final PropertyDescriptor SOCKET_IDLE_CONNECTIONS = new PropertyDescriptor.Builder()
            .name("max-idle-connections")
            .displayName("Socket Idle Connections")
            .description("Maximum number of idle connections to the HTTP URL.")
            .required(true)
            .defaultValue("5")
            .addValidator(StandardValidators.INTEGER_VALIDATOR)
            .build();

    public static final PropertyDescriptor REQUEST_OAUTH2_ACCESS_TOKEN_PROVIDER = new PropertyDescriptor.Builder()
            .name("oauth2-access-token-provider")
            .displayName("Request OAuth2 Access Token Provider")
            .description("Enables managed retrieval of OAuth2 Bearer Token applied to HTTP requests using the Authorization Header.")
            .identifiesControllerService(OAuth2AccessTokenProvider.class)
            .required(false)
            .build();

    public static final PropertyDescriptor REQUEST_USERNAME = new PropertyDescriptor.Builder()
            .name("Basic Authentication Username")
            .displayName("Request Username")
            .description("The username provided for authentication of HTTP requests. Encoded using Base64 for HTTP Basic Authentication as described in RFC 7617.")
            .required(false)
            .addValidator(StandardValidators.createRegexMatchingValidator(Pattern.compile("^[\\x20-\\x39\\x3b-\\x7e\\x80-\\xff]+$")))
            .build();

    public static final PropertyDescriptor REQUEST_PASSWORD = new PropertyDescriptor.Builder()
            .name("Basic Authentication Password")
            .displayName("Request Password")
            .description("The password provided for authentication of HTTP requests. Encoded using Base64 for HTTP Basic Authentication as described in RFC 7617.")
            .required(false)
            .sensitive(true)
            .addValidator(StandardValidators.createRegexMatchingValidator(Pattern.compile("^[\\x20-\\x7e\\x80-\\xff]+$")))
            .build();

    public static final PropertyDescriptor REQUEST_DIGEST_AUTHENTICATION_ENABLED = new PropertyDescriptor.Builder()
            .name("Digest Authentication")
            .displayName("Request Digest Authentication Enabled")
            .description("Enable Digest Authentication on HTTP requests with Username and Password credentials as described in RFC 7616.")
            .required(false)
            .defaultValue("false")
            .allowableValues("true", "false")
            .dependsOn(REQUEST_USERNAME)
            .build();

    public static final PropertyDescriptor REQUEST_FAILURE_PENALIZATION_ENABLED = new PropertyDescriptor.Builder()
            .name("Penalize on \"No Retry\"")
            .displayName("Request Failure Penalization Enabled")
            .description("Enable penalization of request FlowFiles when receiving HTTP response with a status code between 400 and 499.")
            .required(false)
            .defaultValue(Boolean.FALSE.toString())
            .allowableValues(Boolean.TRUE.toString(), Boolean.FALSE.toString())
            .build();

    public static final PropertyDescriptor REQUEST_BODY_ENABLED = new PropertyDescriptor.Builder()
            .name("send-message-body")
            .displayName("Request Body Enabled")
            .description("Enable sending HTTP request body for PATCH, POST, or PUT methods.")
            .defaultValue(Boolean.TRUE.toString())
            .allowableValues(Boolean.TRUE.toString(), Boolean.FALSE.toString())
            .required(false)
            .dependsOn(HTTP_METHOD, HttpMethod.PATCH.name(), HttpMethod.POST.name(), HttpMethod.PUT.name())
            .build();

    public static final PropertyDescriptor REQUEST_FORM_DATA_NAME = new PropertyDescriptor.Builder()
            .name("form-body-form-name")
            .displayName("Request Multipart Form-Data Name")
            .description("Enable sending HTTP request body formatted using multipart/form-data and using the form name configured.")
            .required(false)
            .addValidator(
                    StandardValidators.createAttributeExpressionLanguageValidator(AttributeExpression.ResultType.STRING, true)
            )
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .dependsOn(REQUEST_BODY_ENABLED, Boolean.TRUE.toString())
            .build();

    public static final PropertyDescriptor REQUEST_FORM_DATA_FILENAME_ENABLED = new PropertyDescriptor.Builder()
            .name("set-form-filename")
            .displayName("Request Multipart Form-Data Filename Enabled")
            .description("Enable sending the FlowFile filename attribute as the filename parameter in the Content-Disposition Header for multipart/form-data HTTP requests.")
            .required(false)
            .defaultValue(Boolean.TRUE.toString())
            .allowableValues(Boolean.TRUE.toString(), Boolean.FALSE.toString())
            .dependsOn(REQUEST_FORM_DATA_NAME)
            .build();

    public static final PropertyDescriptor REQUEST_CHUNKED_TRANSFER_ENCODING_ENABLED = new PropertyDescriptor.Builder()
            .name("Use Chunked Encoding")
            .displayName("Request Chunked Transfer-Encoding Enabled")
            .description("Enable sending HTTP requests with the Transfer-Encoding Header set to chunked, and disable sending the Content-Length Header. " +
                    "Transfer-Encoding applies to the body in HTTP/1.1 requests as described in RFC 7230 Section 3.3.1")
            .required(true)
            .defaultValue(Boolean.FALSE.toString())
            .allowableValues(Boolean.TRUE.toString(), Boolean.FALSE.toString())
            .dependsOn(HTTP_METHOD, HttpMethod.PATCH.name(), HttpMethod.POST.name(), HttpMethod.PUT.name())
            .build();

    public static final PropertyDescriptor REQUEST_CONTENT_ENCODING = new PropertyDescriptor.Builder()
            .name("Content-Encoding")
            .displayName("Request Content-Encoding")
            .description("HTTP Content-Encoding applied to request body during transmission. The receiving server must support the selected encoding to avoid request failures.")
            .required(true)
            .defaultValue(ContentEncodingStrategy.DISABLED.getValue())
            .allowableValues(ContentEncodingStrategy.class)
            .dependsOn(HTTP_METHOD, HttpMethod.PATCH.name(), HttpMethod.POST.name(), HttpMethod.PUT.name())
            .build();

    public static final PropertyDescriptor REQUEST_CONTENT_TYPE = new PropertyDescriptor.Builder()
            .name("Content-Type")
            .displayName("Request Content-Type")
            .description("HTTP Content-Type Header applied to when sending an HTTP request body for PATCH, POST, or PUT methods. " +
                    String.format("The Content-Type defaults to %s when not configured.", DEFAULT_CONTENT_TYPE))
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .defaultValue("${" + CoreAttributes.MIME_TYPE.key() + "}")
            .addValidator(StandardValidators.createAttributeExpressionLanguageValidator(AttributeExpression.ResultType.STRING))
            .dependsOn(HTTP_METHOD, HttpMethod.PATCH.name(), HttpMethod.POST.name(), HttpMethod.PUT.name())
            .build();

    public static final PropertyDescriptor REQUEST_DATE_HEADER_ENABLED = new PropertyDescriptor.Builder()
            .name("Include Date Header")
            .displayName("Request Date Header Enabled")
            .description("Enable sending HTTP Date Header on HTTP requests as described in RFC 7231 Section 7.1.1.2.")
            .required(true)
            .defaultValue("True")
            .allowableValues("True", "False")
            .addValidator(StandardValidators.BOOLEAN_VALIDATOR)
            .build();

    public static final PropertyDescriptor REQUEST_HEADER_ATTRIBUTES_PATTERN = new PropertyDescriptor.Builder()
            .name("Attributes to Send")
            .displayName("Request Header Attributes Pattern")
            .description("Regular expression that defines which attributes to send as HTTP headers in the request. "
                    + "If not defined, no attributes are sent as headers. Dynamic properties will be sent as headers. "
                    + "The dynamic property name will be the header key and the dynamic property value will be interpreted as expression "
                    + "language will be the header value.")
            .required(false)
            .addValidator(StandardValidators.REGULAR_EXPRESSION_VALIDATOR)
            .build();

    public static final PropertyDescriptor REQUEST_USER_AGENT = new PropertyDescriptor.Builder()
            .name("Useragent")
            .displayName("Request User-Agent")
            .description("HTTP User-Agent Header applied to requests. RFC 7231 Section 5.5.3 describes recommend formatting.")
            .required(false)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor RESPONSE_BODY_ATTRIBUTE_NAME = new PropertyDescriptor.Builder()
            .name("Put Response Body In Attribute")
            .displayName("Response Body Attribute Name")
            .description("FlowFile attribute name used to write an HTTP response body for FlowFiles transferred to the Original relationship.")
            .addValidator(StandardValidators.createAttributeExpressionLanguageValidator(AttributeExpression.ResultType.STRING))
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .build();

    public static final PropertyDescriptor RESPONSE_BODY_ATTRIBUTE_SIZE = new PropertyDescriptor.Builder()
            .name("Max Length To Put In Attribute")
            .displayName("Response Body Attribute Size")
            .description("Maximum size in bytes applied when writing an HTTP response body to a FlowFile attribute. Attributes exceeding the maximum will be truncated.")
            .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
            .defaultValue("256")
            .dependsOn(RESPONSE_BODY_ATTRIBUTE_NAME)
            .build();

    public static final PropertyDescriptor RESPONSE_BODY_IGNORED = new PropertyDescriptor.Builder()
            .name("ignore-response-content")
            .displayName("Response Body Ignored")
            .description("Disable writing HTTP response FlowFiles to Response relationship")
            .required(true)
            .defaultValue(Boolean.FALSE.toString())
            .allowableValues(Boolean.TRUE.toString(), Boolean.FALSE.toString())
            .build();

    public static final PropertyDescriptor RESPONSE_CACHE_ENABLED = new PropertyDescriptor.Builder()
            .name("use-etag")
            .displayName("Response Cache Enabled")
            .description("Enable HTTP response caching described in RFC 7234. Caching responses considers ETag and other headers.")
            .required(true)
            .defaultValue(Boolean.FALSE.toString())
            .allowableValues(Boolean.TRUE.toString(), Boolean.FALSE.toString())
            .build();

    public static final PropertyDescriptor RESPONSE_CACHE_SIZE = new PropertyDescriptor.Builder()
            .name("etag-max-cache-size")
            .displayName("Response Cache Size")
            .description("Maximum size of HTTP response cache in bytes. Caching responses considers ETag and other headers.")
            .required(true)
            .defaultValue("10MB")
            .addValidator(StandardValidators.DATA_SIZE_VALIDATOR)
            .dependsOn(RESPONSE_CACHE_ENABLED, Boolean.TRUE.toString())
            .build();

    public static final PropertyDescriptor RESPONSE_COOKIE_STRATEGY = new PropertyDescriptor.Builder()
            .name("cookie-strategy")
            .description("Strategy for accepting and persisting HTTP cookies. Accepting cookies enables persistence across multiple requests.")
            .displayName("Response Cookie Strategy")
            .required(true)
            .defaultValue(CookieStrategy.DISABLED.name())
            .allowableValues(CookieStrategy.values())
            .build();

    public static final PropertyDescriptor RESPONSE_GENERATION_REQUIRED = new PropertyDescriptor.Builder()
            .name("Always Output Response")
            .displayName("Response Generation Required")
            .description("Enable generation and transfer of a FlowFile to the Response relationship regardless of HTTP response received.")
            .required(false)
            .defaultValue(Boolean.FALSE.toString())
            .allowableValues(Boolean.TRUE.toString(), Boolean.FALSE.toString())
            .build();

    public static final PropertyDescriptor RESPONSE_FLOW_FILE_NAMING_STRATEGY = new PropertyDescriptor.Builder()
            .name("flow-file-naming-strategy")
            .description("Determines the strategy used for setting the filename attribute of FlowFiles transferred to the Response relationship.")
            .displayName("Response FlowFile Naming Strategy")
            .required(true)
            .defaultValue(FlowFileNamingStrategy.RANDOM.name())
            .allowableValues(
                    Arrays.stream(FlowFileNamingStrategy.values()).map(strategy ->
                            new AllowableValue(strategy.name(), strategy.name(), strategy.getDescription())
                    ).toArray(AllowableValue[]::new)
            )
            .build();

    public static final PropertyDescriptor RESPONSE_HEADER_REQUEST_ATTRIBUTES_ENABLED = new PropertyDescriptor.Builder()
            .name("Add Response Headers to Request")
            .displayName("Response Header Request Attributes Enabled")
            .description("Enable adding HTTP response headers as attributes to FlowFiles transferred to the Original relationship.")
            .required(false)
            .defaultValue(Boolean.FALSE.toString())
            .allowableValues(Boolean.TRUE.toString(), Boolean.FALSE.toString())
            .build();

    public static final PropertyDescriptor RESPONSE_REDIRECTS_ENABLED = new PropertyDescriptor.Builder()
            .name("Follow Redirects")
            .displayName("Response Redirects Enabled")
            .description("Enable following HTTP redirects sent with HTTP 300 series responses as described in RFC 7231 Section 6.4.")
            .required(true)
            .defaultValue("True")
            .allowableValues("True", "False")
            .addValidator(StandardValidators.BOOLEAN_VALIDATOR)
            .build();

    private static final ProxySpec[] PROXY_SPECS = {ProxySpec.HTTP_AUTH, ProxySpec.SOCKS};

    private static final PropertyDescriptor PROXY_CONFIGURATION_SERVICE = ProxyConfiguration.createProxyConfigPropertyDescriptor(true, PROXY_SPECS);

    public static final List<PropertyDescriptor> PROPERTIES = Collections.unmodifiableList(Arrays.asList(
            HTTP_METHOD,
            HTTP_URL,
            HTTP2_DISABLED,
            SSL_CONTEXT_SERVICE,
            SOCKET_CONNECT_TIMEOUT,
            SOCKET_READ_TIMEOUT,
            SOCKET_IDLE_TIMEOUT,
            SOCKET_IDLE_CONNECTIONS,
            PROXY_CONFIGURATION_SERVICE,
            REQUEST_OAUTH2_ACCESS_TOKEN_PROVIDER,
            REQUEST_USERNAME,
            REQUEST_PASSWORD,
            REQUEST_DIGEST_AUTHENTICATION_ENABLED,
            REQUEST_FAILURE_PENALIZATION_ENABLED,
            REQUEST_BODY_ENABLED,
            REQUEST_FORM_DATA_NAME,
            REQUEST_FORM_DATA_FILENAME_ENABLED,
            REQUEST_CHUNKED_TRANSFER_ENCODING_ENABLED,
            REQUEST_CONTENT_ENCODING,
            REQUEST_CONTENT_TYPE,
            REQUEST_DATE_HEADER_ENABLED,
            REQUEST_HEADER_ATTRIBUTES_PATTERN,
            REQUEST_USER_AGENT,
            RESPONSE_BODY_ATTRIBUTE_NAME,
            RESPONSE_BODY_ATTRIBUTE_SIZE,
            RESPONSE_BODY_IGNORED,
            RESPONSE_CACHE_ENABLED,
            RESPONSE_CACHE_SIZE,
            RESPONSE_COOKIE_STRATEGY,
            RESPONSE_GENERATION_REQUIRED,
            RESPONSE_FLOW_FILE_NAMING_STRATEGY,
            RESPONSE_HEADER_REQUEST_ATTRIBUTES_ENABLED,
            RESPONSE_REDIRECTS_ENABLED
    ));

    public static final Relationship ORIGINAL = new Relationship.Builder()
            .name("Original")
            .description("Request FlowFiles transferred when receiving HTTP responses with a status code between 200 and 299.")
            .build();

    public static final Relationship RESPONSE = new Relationship.Builder()
            .name("Response")
            .description("Response FlowFiles transferred when receiving HTTP responses with a status code between 200 and 299.")
            .build();

    public static final Relationship RETRY = new Relationship.Builder()
            .name("Retry")
            .description("Request FlowFiles transferred when receiving HTTP responses with a status code between 500 and 599.")
            .build();

    public static final Relationship NO_RETRY = new Relationship.Builder()
            .name("No Retry")
            .description("Request FlowFiles transferred when receiving HTTP responses with a status code between 400 an 499.")
            .build();

    public static final Relationship FAILURE = new Relationship.Builder()
            .name("Failure")
            .description("Request FlowFiles transferred when receiving socket communication errors.")
            .build();

    public static final Set<Relationship> RELATIONSHIPS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            ORIGINAL,
            RESPONSE,
            RETRY,
            NO_RETRY,
            FAILURE
    )));

    // RFC 2616 Date Time Formatter with hard-coded GMT Zone and US Locale. RFC 2616 Section 3.3 indicates the header should not be localized
    private static final DateTimeFormatter RFC_2616_DATE_TIME = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);

    private static final String MULTIPLE_HEADER_DELIMITER = ", ";

    private volatile Set<String> dynamicPropertyNames = new HashSet<>();

    private volatile Pattern requestHeaderAttributesPattern = null;

    private volatile boolean chunkedTransferEncoding = false;

    private volatile Optional<OAuth2AccessTokenProvider> oauth2AccessTokenProviderOptional;

    private static final Set<Relationship> MATCHED_COLLECTION = Collections.singleton(REL_MATCHED);
    private static final Set<Relationship> UNMATCHED_COLLECTION = Collections.singleton(REL_UNMATCHED);
    private static final Set<Relationship> SUCCESS_COLLECTION = Collections.singleton(REL_SUCCESS);

    private volatile Set<Relationship> relationships = new HashSet<>(Arrays.asList(REL_SUCCESS, REL_FAILURE));
    private volatile boolean routeToMatchedUnmatched = false;

    private final AtomicReference<OkHttpClient> okHttpClientAtomicReference = new AtomicReference<>();

    @OnScheduled
    public void onScheduled(final ProcessContext context) {
        this.lookupService = context.getProperty(LOOKUP_SERVICE).asControllerService(LookupService.class);
    }

    @Override
    public Set<Relationship> getRelationships() {
        return relationships;
    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        final List<PropertyDescriptor> properties = new ArrayList<>();
        properties.add(RECORD_READER);
        properties.add(RECORD_WRITER);
        properties.add(LOOKUP_SERVICE);
        properties.add(RESULT_RECORD_PATH);
        properties.add(ROUTING_STRATEGY);
        properties.add(RESULT_CONTENTS);
        properties.add(REPLACEMENT_STRATEGY);
        properties.add(CACHE_SIZE);
        return properties;
    }

    @Override
    protected PropertyDescriptor getSupportedDynamicPropertyDescriptor(final String propertyDescriptorName) {
        return new PropertyDescriptor.Builder()
            .name(propertyDescriptorName)
            .description("A RecordPath that points to the field whose value will be looked up in the configured Lookup Service")
            .addValidator(new RecordPathValidator())
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .required(false)
            .dynamic(true)
            .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Collection<ValidationResult> customValidate(final ValidationContext validationContext) {
        final Set<String> dynamicPropNames = validationContext.getProperties().keySet().stream()
            .filter(PropertyDescriptor::isDynamic)
            .map(PropertyDescriptor::getName)
            .collect(Collectors.toSet());

        if (dynamicPropNames.isEmpty()) {
            return Collections.singleton(new ValidationResult.Builder()
                .subject("User-Defined Properties")
                .valid(false)
                .explanation("At least one user-defined property must be specified.")
                .build());
        }

        final Set<String> requiredKeys = validationContext.getProperty(LOOKUP_SERVICE).asControllerService(LookupService.class).getRequiredKeys();

        if(validationContext.getProperty(REPLACEMENT_STRATEGY).getValue().equals(REPLACE_EXISTING_VALUES.getValue())) {
            // it must be a single key lookup service
            if(requiredKeys.size() != 1) {
                return Collections.singleton(new ValidationResult.Builder()
                        .subject(LOOKUP_SERVICE.getDisplayName())
                        .valid(false)
                        .explanation("When using \"" + REPLACE_EXISTING_VALUES.getDisplayName() + "\" as Record Update Strategy, "
                                + "only a Lookup Service requiring a single key can be used.")
                        .build());
            }
        } else {
            final Set<String> missingKeys = requiredKeys.stream()
                .filter(key -> !dynamicPropNames.contains(key))
                .collect(Collectors.toSet());

            if (!missingKeys.isEmpty()) {
                final List<ValidationResult> validationResults = new ArrayList<>();
                for (final String missingKey : missingKeys) {
                    final ValidationResult result = new ValidationResult.Builder()
                        .subject(missingKey)
                        .valid(false)
                        .explanation("The configured Lookup Services requires that a key be provided with the name '" + missingKey
                            + "'. Please add a new property to this Processor with a name '" + missingKey
                            + "' and provide a RecordPath that can be used to retrieve the appropriate value.")
                        .build();
                    validationResults.add(result);
                }

                return validationResults;
            }
        }

        return Collections.emptyList();
    }

    @Override
    public void onPropertyModified(final PropertyDescriptor descriptor, final String oldValue, final String newValue) {
        if (ROUTING_STRATEGY.equals(descriptor)) {
            if (ROUTE_TO_MATCHED_UNMATCHED.getValue().equalsIgnoreCase(newValue)) {
                final Set<Relationship> matchedUnmatchedRels = new HashSet<>();
                matchedUnmatchedRels.add(REL_MATCHED);
                matchedUnmatchedRels.add(REL_UNMATCHED);
                matchedUnmatchedRels.add(REL_FAILURE);
                this.relationships = matchedUnmatchedRels;

                this.routeToMatchedUnmatched = true;
            } else {
                final Set<Relationship> successRels = new HashSet<>();
                successRels.add(REL_SUCCESS);
                successRels.add(REL_FAILURE);
                this.relationships = successRels;

                this.routeToMatchedUnmatched = false;
            }
        }
    }

    @OnScheduled
    public void setUpClient(final ProcessContext context) throws TlsException, IOException {
        okHttpClientAtomicReference.set(null);

        OkHttpClient.Builder okHttpClientBuilder = new OkHttpClient().newBuilder();

        final ProxyConfiguration proxyConfig = ProxyConfiguration.getConfiguration(context);

        final Proxy proxy = proxyConfig.createProxy();
        if (!Type.DIRECT.equals(proxy.type())) {
            okHttpClientBuilder.proxy(proxy);
            if (proxyConfig.hasCredential()) {
                ProxyAuthenticator proxyAuthenticator = new ProxyAuthenticator(proxyConfig.getProxyUserName(), proxyConfig.getProxyUserPassword());
                okHttpClientBuilder.proxyAuthenticator(proxyAuthenticator);
            }
        }

        // Configure caching
        final boolean cachingEnabled = context.getProperty(RESPONSE_CACHE_ENABLED).asBoolean();
        if (cachingEnabled) {
            final int maxCacheSizeBytes = context.getProperty(RESPONSE_CACHE_SIZE).asDataSize(DataUnit.B).intValue();

            // Import issue with other cache so this is the workaround
            okhttp3.Cache httpCache = new okhttp3.Cache(getResponseCacheDirectory(), maxCacheSizeBytes);
            okHttpClientBuilder.cache(httpCache);
        }

        if (context.getProperty(HTTP2_DISABLED).asBoolean()) {
            okHttpClientBuilder.protocols(Collections.singletonList(Protocol.HTTP_1_1));
        }

        okHttpClientBuilder.followRedirects(context.getProperty(RESPONSE_REDIRECTS_ENABLED).asBoolean());
        okHttpClientBuilder.connectTimeout((context.getProperty(SOCKET_CONNECT_TIMEOUT).asTimePeriod(TimeUnit.MILLISECONDS).intValue()), TimeUnit.MILLISECONDS);
        okHttpClientBuilder.readTimeout(context.getProperty(SOCKET_READ_TIMEOUT).asTimePeriod(TimeUnit.MILLISECONDS).intValue(), TimeUnit.MILLISECONDS);
        okHttpClientBuilder.connectionPool(
                new ConnectionPool(
                        context.getProperty(SOCKET_IDLE_CONNECTIONS).asInteger(),
                        context.getProperty(SOCKET_IDLE_TIMEOUT).asTimePeriod(TimeUnit.MILLISECONDS).intValue(), TimeUnit.MILLISECONDS
                )
        );

        final SSLContextService sslService = context.getProperty(SSL_CONTEXT_SERVICE).asControllerService(SSLContextService.class);
        if (sslService != null) {
            final SSLContext sslContext = sslService.createContext();
            final SSLSocketFactory socketFactory = sslContext.getSocketFactory();
            final TlsConfiguration tlsConfiguration = sslService.createTlsConfiguration();
            final X509TrustManager trustManager = Objects.requireNonNull(SslContextFactory.getX509TrustManager(tlsConfiguration), "Trust Manager not found");
            okHttpClientBuilder.sslSocketFactory(socketFactory, trustManager);
        }

        final CookieStrategy cookieStrategy = CookieStrategy.valueOf(context.getProperty(RESPONSE_COOKIE_STRATEGY).getValue());
        switch (cookieStrategy) {
            case DISABLED:
                break;
            case ACCEPT_ALL:
                final CookieManager cookieManager = new CookieManager();
                cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
                okHttpClientBuilder.cookieJar(new JavaNetCookieJar(cookieManager));
                break;
        }

        setAuthenticator(okHttpClientBuilder, context);

        chunkedTransferEncoding = context.getProperty(REQUEST_CHUNKED_TRANSFER_ENCODING_ENABLED).asBoolean();

        okHttpClientAtomicReference.set(okHttpClientBuilder.build());
    }

    @OnScheduled
    public void initOauth2AccessTokenProvider(final ProcessContext context) {
        if (context.getProperty(REQUEST_OAUTH2_ACCESS_TOKEN_PROVIDER).isSet()) {
            OAuth2AccessTokenProvider oauth2AccessTokenProvider = context.getProperty(REQUEST_OAUTH2_ACCESS_TOKEN_PROVIDER).asControllerService(OAuth2AccessTokenProvider.class);

            oauth2AccessTokenProvider.getAccessDetails();

            oauth2AccessTokenProviderOptional = Optional.of(oauth2AccessTokenProvider);
        } else {
            oauth2AccessTokenProviderOptional = Optional.empty();
        }
    }

    private void setAuthenticator(OkHttpClient.Builder okHttpClientBuilder, ProcessContext context) {
        final String authUser = StringUtils.trimToEmpty(context.getProperty(REQUEST_USERNAME).getValue());

        // If the username/password properties are set then check if digest auth is being used
        if (!authUser.isEmpty() && "true".equalsIgnoreCase(context.getProperty(REQUEST_DIGEST_AUTHENTICATION_ENABLED).getValue())) {
            final String authPass = StringUtils.trimToEmpty(context.getProperty(REQUEST_PASSWORD).getValue());

            final Map<String, CachingAuthenticator> authCache = new ConcurrentHashMap<>();
            com.burgstaller.okhttp.digest.Credentials credentials = new com.burgstaller.okhttp.digest.Credentials(authUser, authPass);
            final DigestAuthenticator digestAuthenticator = new DigestAuthenticator(credentials);

            okHttpClientBuilder.interceptors().add(new AuthenticationCacheInterceptor(authCache));
            okHttpClientBuilder.authenticator(new CachingAuthenticatorDecorator(digestAuthenticator, authCache));
        }
    }


    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {
        OkHttpClient okHttpClient = okHttpClientAtomicReference.get();

        FlowFile flowFile = session.get();
        if (flowFile == null) {
            return;
        }

        final RecordReaderFactory readerFactory = context.getProperty(RECORD_READER).asControllerService(RecordReaderFactory.class);
        final RecordSetWriterFactory writerFactory = context.getProperty(RECORD_WRITER).asControllerService(RecordSetWriterFactory.class);

        final FlowFile original = flowFile;
        final Map<String, String> originalAttributes = original.getAttributes();

        final LookupContext lookupContext = createLookupContext(flowFile, context, session, writerFactory);
        final ReplacementStrategy replacementStrategy = createReplacementStrategy(context);

        final RecordSchema enrichedSchema;
        try {
            enrichedSchema = replacementStrategy.determineResultSchema(readerFactory, writerFactory, context, session, flowFile, lookupContext);
        } catch (final Exception e) {
            getLogger().error("Could not determine schema to use for enriched FlowFiles", e);
            session.transfer(original, REL_FAILURE);
            return;
        }

        try {
            session.read(flowFile, new InputStreamCallback() {
                @Override
                public void process(final InputStream in) throws IOException {
                    try (final RecordReader reader = readerFactory.createRecordReader(originalAttributes, in, original.getSize(), getLogger())) {

                        final Map<Relationship, RecordSchema> writeSchemas = new HashMap<>();

                        Record record;
                        while ((record = reader.nextRecord()) != null) {
                            final Set<Relationship> relationships = replacementStrategy.lookup(record, context, lookupContext);

                            for (final Relationship relationship : relationships) {
                                // Determine the Write Schema to use for each relationship
                                RecordSchema writeSchema = writeSchemas.get(relationship);
                                if (writeSchema == null) {
                                    final RecordSchema outputSchema = enrichedSchema == null ? record.getSchema() : enrichedSchema;
                                    writeSchema = writerFactory.getSchema(originalAttributes, outputSchema);
                                    writeSchemas.put(relationship, writeSchema);
                                }

                                final RecordSetWriter writer = lookupContext.getRecordWriterForRelationship(relationship, writeSchema);
                                writer.write(record);
                            }
                        }
                    } catch (final SchemaNotFoundException | MalformedRecordException e) {
                        throw new ProcessException("Could not parse incoming data", e);
                    }
                }
            });

            for (final Relationship relationship : lookupContext.getRelationshipsUsed()) {
                final RecordSetWriter writer = lookupContext.getExistingRecordWriterForRelationship(relationship);
                FlowFile childFlowFile = lookupContext.getFlowFileForRelationship(relationship);

                final WriteResult writeResult = writer.finishRecordSet();

                try {
                    writer.close();
                } catch (final IOException ioe) {
                    getLogger().warn("Failed to close Writer for {}", new Object[] {childFlowFile});
                }

                final Map<String, String> attributes = new HashMap<>();
                attributes.put("record.count", String.valueOf(writeResult.getRecordCount()));
                attributes.put(CoreAttributes.MIME_TYPE.key(), writer.getMimeType());
                attributes.putAll(writeResult.getAttributes());

                childFlowFile = session.putAllAttributes(childFlowFile, attributes);
                session.transfer(childFlowFile, relationship);
                session.adjustCounter("Records Processed", writeResult.getRecordCount(), false);
                session.adjustCounter("Records Routed to " + relationship.getName(), writeResult.getRecordCount(), false);

                session.getProvenanceReporter().route(childFlowFile, relationship);
            }

        } catch (final Exception e) {
            getLogger().error("Failed to process {}", new Object[]{flowFile, e});

            for (final Relationship relationship : lookupContext.getRelationshipsUsed()) {
                final RecordSetWriter writer = lookupContext.getExistingRecordWriterForRelationship(relationship);
                final FlowFile childFlowFile = lookupContext.getFlowFileForRelationship(relationship);

                try {
                    writer.close();
                } catch (final Exception e1) {
                    getLogger().warn("Failed to close Writer for {}; some resources may not be cleaned up appropriately", writer);
                }

                session.remove(childFlowFile);
            }

            session.transfer(flowFile, REL_FAILURE);
            return;
        } finally {
            for (final Relationship relationship : lookupContext.getRelationshipsUsed()) {
                final RecordSetWriter writer = lookupContext.getExistingRecordWriterForRelationship(relationship);

                try {
                    writer.close();
                } catch (final Exception e) {
                    getLogger().warn("Failed to close Record Writer for {}; some resources may not be properly cleaned up", writer, e);
                }
            }
        }

        session.remove(flowFile);
        getLogger().info("Successfully processed {}, creating {} derivative FlowFiles and processing {} records",
            flowFile, lookupContext.getRelationshipsUsed().size(), replacementStrategy.getLookupCount());
    }

    private ReplacementStrategy createReplacementStrategy(final ProcessContext context) {
        final boolean isInPlaceReplacement = context.getProperty(REPLACEMENT_STRATEGY).getValue().equals(REPLACE_EXISTING_VALUES.getValue());

        if (isInPlaceReplacement) {
            return new InPlaceReplacementStrategy();
        } else {
            return new RecordPathReplacementStrategy(context);
        }
    }


    private class InPlaceReplacementStrategy implements ReplacementStrategy {
        private int lookupCount = 0;

        @Override
        public Set<Relationship> lookup(final Record record, final ProcessContext context, final LookupContext lookupContext) {
            lookupCount++;

            final Map<String, RecordPath> recordPaths = lookupContext.getRecordPathsByCoordinateKey();
            final Map<String, Object> lookupCoordinates = new HashMap<>(recordPaths.size());
            final String coordinateKey = lookupService.getRequiredKeys().iterator().next();
            final FlowFile flowFile = lookupContext.getOriginalFlowFile();

            boolean hasUnmatchedValue = false;
            for (final Map.Entry<String, RecordPath> entry : recordPaths.entrySet()) {
                final RecordPath recordPath = entry.getValue();

                final RecordPathResult pathResult = recordPath.evaluate(record);
                final List<FieldValue> lookupFieldValues = pathResult.getSelectedFields()
                    .filter(fieldVal -> fieldVal.getValue() != null)
                    .collect(Collectors.toList());

                if (lookupFieldValues.isEmpty()) {
                    final Set<Relationship> rels = routeToMatchedUnmatched ? UNMATCHED_COLLECTION : SUCCESS_COLLECTION;
                    getLogger().debug("RecordPath for property '{}' did not match any fields in a record for {}; routing record to {}", new Object[] {coordinateKey, flowFile, rels});
                    return rels;
                }

                for (final FieldValue fieldValue : lookupFieldValues) {
                    final Object coordinateValue = DataTypeUtils.convertType(fieldValue.getValue(), fieldValue.getField().getDataType(), null, null, null, fieldValue.getField().getFieldName());

                    lookupCoordinates.clear();
                    lookupCoordinates.put(coordinateKey, coordinateValue);

                    final Optional<?> lookupValueOption;
                    try {
                        lookupValueOption = lookupService.lookup(lookupCoordinates, flowFile.getAttributes());
                    } catch (final Exception e) {
                        throw new ProcessException("Failed to lookup coordinates " + lookupCoordinates + " in Lookup Service", e);
                    }

                    if (!lookupValueOption.isPresent()) {
                        hasUnmatchedValue = true;
                        continue;
                    }

                    final Object lookupValue = lookupValueOption.get();

                    final DataType inferredDataType = DataTypeUtils.inferDataType(lookupValue, RecordFieldType.STRING.getDataType());
                    fieldValue.updateValue(lookupValue, inferredDataType);
                }
            }

            if (hasUnmatchedValue) {
                return routeToMatchedUnmatched ? UNMATCHED_COLLECTION : SUCCESS_COLLECTION;
            } else {
                return routeToMatchedUnmatched ? MATCHED_COLLECTION : SUCCESS_COLLECTION;
            }
        }

        @Override
        public RecordSchema determineResultSchema(final RecordReaderFactory readerFactory, final RecordSetWriterFactory writerFactory, final ProcessContext context, final ProcessSession session,
                                                  final FlowFile flowFile, final LookupContext lookupContext) throws IOException, SchemaNotFoundException, MalformedRecordException {

            try (final InputStream in = session.read(flowFile);
                 final RecordReader reader = readerFactory.createRecordReader(flowFile, in, getLogger())) {

                return reader.getSchema();
            }
        }

        @Override
        public int getLookupCount() {
            return lookupCount;
        }
    }


    private class RecordPathReplacementStrategy implements ReplacementStrategy {
        private int lookupCount = 0;

        private volatile Cache<Map<String, Object>, Optional<?>> cache;

        public RecordPathReplacementStrategy(ProcessContext context) {

            final int cacheSize = context.getProperty(CACHE_SIZE).evaluateAttributeExpressions().asInteger();

            if (this.cache == null || cacheSize > 0) {
                this.cache = Caffeine.newBuilder()
                        .maximumSize(cacheSize)
                        .build();
            }
        }

        @Override
        public Set<Relationship> lookup(final Record record, final ProcessContext context, final LookupContext lookupContext) {
            lookupCount++;

            final Map<String, Object> lookupCoordinates = createLookupCoordinates(record, lookupContext, true);
            if (lookupCoordinates.isEmpty()) {
                final Set<Relationship> rels = routeToMatchedUnmatched ? UNMATCHED_COLLECTION : SUCCESS_COLLECTION;
                return rels;
            }

            final FlowFile flowFile = lookupContext.getOriginalFlowFile();
            final Optional<?> lookupValueOption;
            final Optional<?> lookupValueCacheOption;

            try {
                lookupValueCacheOption = (Optional<?>) cache.get(lookupCoordinates, k -> null);
                if (lookupValueCacheOption == null) {
                    lookupValueOption = lookupService.lookup(lookupCoordinates, flowFile.getAttributes());
                } else {
                    lookupValueOption = lookupValueCacheOption;
                }
            } catch (final Exception e) {
                throw new ProcessException("Failed to lookup coordinates " + lookupCoordinates + " in Lookup Service", e);
            }

            if (!lookupValueOption.isPresent()) {
                final Set<Relationship> rels = routeToMatchedUnmatched ? UNMATCHED_COLLECTION : SUCCESS_COLLECTION;
                return rels;
            }

            applyLookupResult(record, context, lookupContext, lookupValueOption.get());

            final Set<Relationship> rels = routeToMatchedUnmatched ? MATCHED_COLLECTION : SUCCESS_COLLECTION;
            return rels;
        }

        private void applyLookupResult(final Record record, final ProcessContext context, final LookupContext lookupContext, final Object lookupValue) {
            // Ensure that the Record has the appropriate schema to account for the newly added values
            final RecordPath resultPath = lookupContext.getResultRecordPath();
            if (resultPath != null) {
                final RecordPathResult resultPathResult = resultPath.evaluate(record);

                final String resultContentsValue = context.getProperty(RESULT_CONTENTS).getValue();
                if (RESULT_RECORD_FIELDS.getValue().equals(resultContentsValue) && lookupValue instanceof Record) {
                    final Record lookupRecord = (Record) lookupValue;

                    // User wants to add all fields of the resultant Record to the specified Record Path.
                    // If the destination Record Path returns to us a Record, then we will add all field values of
                    // the Lookup Record to the destination Record. However, if the destination Record Path returns
                    // something other than a Record, then we can't add the fields to it. We can only replace it,
                    // because it doesn't make sense to add fields to anything but a Record.
                    resultPathResult.getSelectedFields().forEach(fieldVal -> {
                        final Object destinationValue = fieldVal.getValue();

                        if (destinationValue instanceof Record) {
                            final Record destinationRecord = (Record) destinationValue;

                            for (final String fieldName : lookupRecord.getRawFieldNames()) {
                                final Object value = lookupRecord.getValue(fieldName);

                                final Optional<RecordField> recordFieldOption = lookupRecord.getSchema().getField(fieldName);
                                if (recordFieldOption.isPresent()) {
                                    // Even if the looked up field is not nullable, if the lookup key didn't match with any record,
                                    // and matched/unmatched records are written to the same FlowFile routed to 'success' relationship,
                                    // then enriched fields should be nullable to support unmatched records whose enriched fields will be null.
                                    RecordField field = recordFieldOption.get();
                                    if (!routeToMatchedUnmatched && !field.isNullable()) {
                                        field = new RecordField(field.getFieldName(), field.getDataType(), field.getDefaultValue(), field.getAliases(), true);
                                    }
                                    destinationRecord.setValue(field, value);
                                } else {
                                    destinationRecord.setValue(fieldName, value);
                                }
                            }
                        } else {
                            final Optional<Record> parentOption = fieldVal.getParentRecord();
                            parentOption.ifPresent(parent -> parent.setValue(fieldVal.getField(), lookupRecord));
                        }
                    });
                } else {
                    final DataType inferredDataType = DataTypeUtils.inferDataType(lookupValue, RecordFieldType.STRING.getDataType());
                    resultPathResult.getSelectedFields().forEach(fieldVal -> fieldVal.updateValue(lookupValue, inferredDataType));
                }

                record.incorporateInactiveFields();
            }
        }

        @Override
        public RecordSchema determineResultSchema(final RecordReaderFactory readerFactory, final RecordSetWriterFactory writerFactory, final ProcessContext context, final ProcessSession session,
                                                  final FlowFile flowFile, final LookupContext lookupContext)
                throws IOException, SchemaNotFoundException, MalformedRecordException, LookupFailureException {

            final Map<String, String> flowFileAttributes = flowFile.getAttributes();
            try (final InputStream in = session.read(flowFile);
                 final RecordReader reader = readerFactory.createRecordReader(flowFile, in, getLogger())) {

                Record record;
                while ((record = reader.nextRecord()) != null) {
                    final Map<String, Object> lookupCoordinates = createLookupCoordinates(record, lookupContext, false);
                    if (lookupCoordinates.isEmpty()) {
                        continue;
                    }

                    final Optional<?> lookupResult = lookupService.lookup(lookupCoordinates, flowFileAttributes);

                    cache.put(lookupCoordinates, lookupResult);

                    if (!lookupResult.isPresent()) {
                        continue;
                    }

                    applyLookupResult(record, context, lookupContext, lookupResult.get());
                    getLogger().debug("Found a Record for {} that returned a result from the LookupService. Will provide the following schema to the Writer: {}", flowFile, record.getSchema());
                    return record.getSchema();
                }

                getLogger().debug("Found no Record for {} that returned a result from the LookupService. Will provider Reader's schema to the Writer.", flowFile);
                return reader.getSchema();
            }
        }

        private Map<String, Object> createLookupCoordinates(final Record record, final LookupContext lookupContext, final boolean logIfNotMatched) {
            final Map<String, RecordPath> recordPaths = lookupContext.getRecordPathsByCoordinateKey();
            final Map<String, Object> lookupCoordinates = new HashMap<>(recordPaths.size());
            final FlowFile flowFile = lookupContext.getOriginalFlowFile();

            for (final Map.Entry<String, RecordPath> entry : recordPaths.entrySet()) {
                final String coordinateKey = entry.getKey();
                final RecordPath recordPath = entry.getValue();

                final RecordPathResult pathResult = recordPath.evaluate(record);
                final List<FieldValue> lookupFieldValues = pathResult.getSelectedFields()
                    .filter(fieldVal -> fieldVal.getValue() != null)
                    .collect(Collectors.toList());

                if (lookupFieldValues.isEmpty()) {
                    if (logIfNotMatched) {
                        final Set<Relationship> rels = routeToMatchedUnmatched ? UNMATCHED_COLLECTION : SUCCESS_COLLECTION;
                        getLogger().debug("RecordPath for property '{}' did not match any fields in a record for {}; routing record to {}", coordinateKey, flowFile, rels);
                    }

                    return Collections.emptyMap();
                }

                if (lookupFieldValues.size() > 1) {
                    if (logIfNotMatched) {
                        final Set<Relationship> rels = routeToMatchedUnmatched ? UNMATCHED_COLLECTION : SUCCESS_COLLECTION;
                        getLogger().debug("RecordPath for property '{}' matched {} fields in a record for {}; routing record to {}",
                            coordinateKey, lookupFieldValues.size(), flowFile, rels);
                    }

                    return Collections.emptyMap();
                }

                final FieldValue fieldValue = lookupFieldValues.get(0);
                final Object coordinateValue = DataTypeUtils.convertType(
                        fieldValue.getValue(),
                        Optional.ofNullable(fieldValue.getField())
                                .map(RecordField::getDataType)
                                .orElse(DataTypeUtils.inferDataType(fieldValue.getValue(), RecordFieldType.STRING.getDataType())),
                        null,
                        null,
                        null,
                        Optional.ofNullable(fieldValue.getField())
                                .map(RecordField::getFieldName)
                                .orElse(coordinateKey)
                );
                lookupCoordinates.put(coordinateKey, coordinateValue);
            }

            return lookupCoordinates;
        }

        @Override
        public int getLookupCount() {
            return lookupCount;
        }
    }


    protected LookupContext createLookupContext(final FlowFile flowFile, final ProcessContext context, final ProcessSession session, final RecordSetWriterFactory writerFactory) {
        final Map<String, RecordPath> recordPaths = new HashMap<>();
        for (final PropertyDescriptor prop : context.getProperties().keySet()) {
            if (!prop.isDynamic()) {
                continue;
            }

            final String pathText = context.getProperty(prop).evaluateAttributeExpressions(flowFile).getValue();
            final RecordPath lookupRecordPath = recordPathCache.getCompiled(pathText);
            recordPaths.put(prop.getName(), lookupRecordPath);
        }

        final RecordPath resultRecordPath;
        if (context.getProperty(RESULT_RECORD_PATH).isSet()) {
            final String resultPathText = context.getProperty(RESULT_RECORD_PATH).evaluateAttributeExpressions(flowFile).getValue();
            resultRecordPath = recordPathCache.getCompiled(resultPathText);
        } else {
            resultRecordPath = null;
        }

        return new LookupContext(recordPaths, resultRecordPath, session, flowFile, writerFactory, getLogger());
    }

    private interface ReplacementStrategy {
        Set<Relationship> lookup(Record record, ProcessContext context, LookupContext lookupContext);

        RecordSchema determineResultSchema(RecordReaderFactory readerFactory, RecordSetWriterFactory writerFactory, ProcessContext context, ProcessSession session, FlowFile flowFile,
                                           LookupContext lookupContext) throws IOException, SchemaNotFoundException, MalformedRecordException, LookupFailureException;

        int getLookupCount();
    }


    private static class LookupContext {
        private final Map<String, RecordPath> recordPathsByCoordinateKey;
        private final RecordPath resultRecordPath;
        private final ProcessSession session;
        private final FlowFile flowFile;
        private final RecordSetWriterFactory writerFactory;
        private final ComponentLog logger;

        private final Map<Relationship, Tuple<FlowFile, RecordSetWriter>> writersByRelationship = new HashMap<>();


        public LookupContext(final Map<String, RecordPath> recordPathsByCoordinateKey, final RecordPath resultRecordPath, final ProcessSession session, final FlowFile flowFile,
                             final RecordSetWriterFactory writerFactory, final ComponentLog logger) {
            this.recordPathsByCoordinateKey = recordPathsByCoordinateKey;
            this.resultRecordPath = resultRecordPath;
            this.session = session;
            this.flowFile = flowFile;
            this.writerFactory = writerFactory;
            this.logger = logger;
        }

        public Map<String, RecordPath> getRecordPathsByCoordinateKey() {
            return recordPathsByCoordinateKey;
        }

        public RecordPath getResultRecordPath() {
            return resultRecordPath;
        }

        public FlowFile getOriginalFlowFile() {
            return flowFile;
        }

        private Set<Relationship> getRelationshipsUsed() {
            return writersByRelationship.keySet();
        }

        public FlowFile getFlowFileForRelationship(final Relationship relationship) {
            final Tuple<FlowFile, RecordSetWriter> tuple = writersByRelationship.get(relationship);
            return tuple.getKey();
        }

        public RecordSetWriter getExistingRecordWriterForRelationship(final Relationship relationship) {
            final Tuple<FlowFile, RecordSetWriter> tuple = writersByRelationship.get(relationship);
            return tuple.getValue();
        }

        public RecordSetWriter getRecordWriterForRelationship(final Relationship relationship, final RecordSchema schema) throws IOException, SchemaNotFoundException {
            final Tuple<FlowFile, RecordSetWriter> tuple = writersByRelationship.get(relationship);
            if (tuple != null) {
                return tuple.getValue();
            }

            final FlowFile outFlowFile = session.create(flowFile);
            final OutputStream out = session.write(outFlowFile);
            try {
                final RecordSchema recordWriteSchema = writerFactory.getSchema(flowFile.getAttributes(), schema);
                final RecordSetWriter recordSetWriter = writerFactory.createWriter(logger, recordWriteSchema, out, outFlowFile);
                recordSetWriter.beginRecordSet();

                writersByRelationship.put(relationship, new Tuple<>(outFlowFile, recordSetWriter));
                return recordSetWriter;
            } catch (final Exception e) {
                try {
                    out.close();
                } catch (final Exception e1) {
                    e.addSuppressed(e1);
                }

                throw e;
            }
        }
    }

    private Request configureRequest(final ProcessContext context, final ProcessSession session, final FlowFile requestFlowFile, URL url) {
        final Request.Builder requestBuilder = new Request.Builder();

        requestBuilder.url(url);
        final String authUser = StringUtils.trimToEmpty(context.getProperty(REQUEST_USERNAME).getValue());

        // If the username/password properties are set then check if digest auth is being used
        if ("false".equalsIgnoreCase(context.getProperty(REQUEST_DIGEST_AUTHENTICATION_ENABLED).getValue())) {
            if (!authUser.isEmpty()) {
                final String authPass = StringUtils.trimToEmpty(context.getProperty(REQUEST_PASSWORD).getValue());

                String credential = Credentials.basic(authUser, authPass);
                requestBuilder.header(HttpHeader.AUTHORIZATION.getHeader(), credential);
            } else {
                oauth2AccessTokenProviderOptional.ifPresent(oauth2AccessTokenProvider ->
                    requestBuilder.addHeader(HttpHeader.AUTHORIZATION.getHeader(), "Bearer " + oauth2AccessTokenProvider.getAccessDetails().getAccessToken())
                );
            }
        }

        final String contentEncoding = context.getProperty(REQUEST_CONTENT_ENCODING).getValue();
        final ContentEncodingStrategy contentEncodingStrategy = ContentEncodingStrategy.valueOf(contentEncoding);
        if (ContentEncodingStrategy.GZIP == contentEncodingStrategy) {
            requestBuilder.addHeader(HttpHeader.CONTENT_ENCODING.getHeader(), ContentEncodingStrategy.GZIP.getValue().toLowerCase());
        }

        final String method = getRequestMethod(context, requestFlowFile);
        final Optional<HttpMethod> httpMethodFound = findRequestMethod(method);

        final RequestBody requestBody;
        if (httpMethodFound.isPresent()) {
            final HttpMethod httpMethod = httpMethodFound.get();
            if (httpMethod.isRequestBodySupported()) {
                requestBody = getRequestBodyToSend(session, context, requestFlowFile, contentEncodingStrategy);
            } else {
                requestBody = null;
            }
        } else {
            requestBody = null;
        }
        requestBuilder.method(method, requestBody);

        setHeaderProperties(context, requestBuilder, requestFlowFile);
        return requestBuilder.build();
    }

    private RequestBody getRequestBodyToSend(final ProcessSession session, final ProcessContext context,
                                             final FlowFile requestFlowFile,
                                             final ContentEncodingStrategy contentEncodingStrategy
    ) {
        boolean requestBodyEnabled = context.getProperty(REQUEST_BODY_ENABLED).asBoolean();

        String evalContentType = context.getProperty(REQUEST_CONTENT_TYPE)
                .evaluateAttributeExpressions(requestFlowFile).getValue();
        final String contentType = StringUtils.isBlank(evalContentType) ? DEFAULT_CONTENT_TYPE : evalContentType;
        String formDataName = context.getProperty(REQUEST_FORM_DATA_NAME).evaluateAttributeExpressions(requestFlowFile).getValue();

        // Check for dynamic properties for form components.
        // Even if the flowfile is not sent, we may still send form parameters.
        Map<String, PropertyDescriptor> propertyDescriptors = new HashMap<>();
        for (final Map.Entry<PropertyDescriptor, String> entry : context.getProperties().entrySet()) {
            Matcher matcher = FORM_DATA_NAME_PARAMETER_PATTERN.matcher(entry.getKey().getName());
            if (matcher.matches()) {
                propertyDescriptors.put(matcher.group(FORM_DATA_NAME_GROUP), entry.getKey());
            }
        }

        final boolean contentLengthUnknown = chunkedTransferEncoding || ContentEncodingStrategy.GZIP == contentEncodingStrategy;
        RequestBody requestBody = new RequestBody() {
            @Nullable
            @Override
            public MediaType contentType() {
                return MediaType.parse(contentType);
            }

            @Override
            public void writeTo(final BufferedSink sink) throws IOException {
                final BufferedSink outputSink = (ContentEncodingStrategy.GZIP == contentEncodingStrategy)
                        ? Okio.buffer(new GzipSink(sink))
                        : sink;

                session.read(requestFlowFile, inputStream -> {
                    final Source source = Okio.source(inputStream);
                    outputSink.writeAll(source);
                });

                // Close Output Sink for gzip to write trailing bytes
                if (ContentEncodingStrategy.GZIP == contentEncodingStrategy) {
                    outputSink.close();
                }
            }

            @Override
            public long contentLength() {
                return contentLengthUnknown ? -1 : requestFlowFile.getSize();
            }
        };

        if (propertyDescriptors.size() > 0 || StringUtils.isNotEmpty(formDataName)) {
            // we have form data
            MultipartBody.Builder builder = new Builder().setType(MultipartBody.FORM);
            boolean useFileName = context.getProperty(REQUEST_FORM_DATA_FILENAME_ENABLED).asBoolean();
            String contentFileName = null;
            if (useFileName) {
                contentFileName = requestFlowFile.getAttribute(CoreAttributes.FILENAME.key());
            }
            // loop through the dynamic form parameters
            for (final Map.Entry<String, PropertyDescriptor> entry : propertyDescriptors.entrySet()) {
                final String propValue = context.getProperty(entry.getValue().getName())
                        .evaluateAttributeExpressions(requestFlowFile).getValue();
                builder.addFormDataPart(entry.getKey(), propValue);
            }
            if (requestBodyEnabled) {
                builder.addFormDataPart(formDataName, contentFileName, requestBody);
            }
            return builder.build();
        } else if (requestBodyEnabled) {
            return requestBody;
        }
        return RequestBody.create(new byte[0], null);
    }

    private void setHeaderProperties(final ProcessContext context, final Request.Builder requestBuilder, final FlowFile requestFlowFile) {
        final String userAgent = StringUtils.trimToEmpty(context.getProperty(REQUEST_USER_AGENT).evaluateAttributeExpressions(requestFlowFile).getValue());
        requestBuilder.addHeader(HttpHeader.USER_AGENT.getHeader(), userAgent);

        if (context.getProperty(REQUEST_DATE_HEADER_ENABLED).asBoolean()) {
            final ZonedDateTime universalCoordinatedTimeNow = ZonedDateTime.now(ZoneOffset.UTC);
            requestBuilder.addHeader(HttpHeader.DATE.getHeader(), RFC_2616_DATE_TIME.format(universalCoordinatedTimeNow));
        }

        for (String headerKey : dynamicPropertyNames) {
            String headerValue = context.getProperty(headerKey).evaluateAttributeExpressions(requestFlowFile).getValue();

            // ignore form data name dynamic properties
            if (FORM_DATA_NAME_PARAMETER_PATTERN.matcher(headerKey).matches()) {
                continue;
            }

            requestBuilder.addHeader(headerKey, headerValue);
        }

        // iterate through the flowfile attributes, adding any attribute that
        // matches the attributes-to-send pattern. if the pattern is not set
        // (it's an optional property), ignore that attribute entirely
        if (requestHeaderAttributesPattern != null && requestFlowFile != null) {
            Map<String, String> attributes = requestFlowFile.getAttributes();
            Matcher m = requestHeaderAttributesPattern.matcher("");
            for (Map.Entry<String, String> entry : attributes.entrySet()) {
                String headerKey = StringUtils.trimToEmpty(entry.getKey());
                if (IGNORED_REQUEST_ATTRIBUTES.contains(headerKey)) {
                    continue;
                }

                // check if our attribute key matches the pattern
                // if so, include in the request as a header
                m.reset(headerKey);
                if (m.matches()) {
                    String headerVal = StringUtils.trimToEmpty(entry.getValue());
                    requestBuilder.addHeader(headerKey, headerVal);
                }
            }
        }
    }

    private void route(FlowFile request, FlowFile response, ProcessSession session, ProcessContext context, int statusCode) {
        // check if we should yield the processor
        if (!isSuccess(statusCode) && request == null) {
            context.yield();
        }

        // If the property to output the response flowfile regardless of status code is set then transfer it
        boolean responseSent = false;
        if (context.getProperty(RESPONSE_GENERATION_REQUIRED).asBoolean()) {
            session.transfer(response, RESPONSE);
            responseSent = true;
        }

        // transfer to the correct relationship
        // 2xx -> SUCCESS
        if (isSuccess(statusCode)) {
            // we have two flowfiles to transfer
            if (request != null) {
                session.transfer(request, ORIGINAL);
            }
            if (response != null && !responseSent) {
                session.transfer(response, RESPONSE);
            }

            // 5xx -> RETRY
        } else if (statusCode / 100 == 5) {
            if (request != null) {
                request = session.penalize(request);
                session.transfer(request, RETRY);
            }

            // 1xx, 3xx, 4xx -> NO RETRY
        } else {
            if (request != null) {
                if (context.getProperty(REQUEST_FAILURE_PENALIZATION_ENABLED).asBoolean()) {
                    request = session.penalize(request);
                }
                session.transfer(request, NO_RETRY);
            }
        }

    }

    private boolean isSuccess(int statusCode) {
        return statusCode / 100 == 2;
    }

    private void logRequest(ComponentLog logger, Request request) {
        if (logger.isDebugEnabled()) {
            logger.debug("\nRequest to remote service:\n\t{}\n{}",
                    request.url().url().toExternalForm(), getLogString(request.headers().toMultimap()));
        }
    }

    private void logResponse(ComponentLog logger, URL url, Response response) {
        if (logger.isDebugEnabled()) {
            logger.debug("\nResponse from remote service:\n\t{}\n{}",
                    url.toExternalForm(), getLogString(response.headers().toMultimap()));
        }
    }

    private String getLogString(Map<String, List<String>> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : map.entrySet()) {
            List<String> list = entry.getValue();
            if (!list.isEmpty()) {
                sb.append("\t");
                sb.append(entry.getKey());
                sb.append(": ");
                if (list.size() == 1) {
                    sb.append(list.get(0));
                } else {
                    sb.append(list);
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Returns a Map of flowfile attributes from the response http headers. Multivalue headers are naively converted to comma separated strings.
     */
    private Map<String, String> convertAttributesFromHeaders(final Response responseHttp) {
        // create a new hashmap to store the values from the connection
        final Map<String, String> attributes = new HashMap<>();
        final Headers headers = responseHttp.headers();
        headers.names().forEach((key) -> {
            final List<String> values = headers.values(key);
            // we ignore any headers with no actual values (rare)
            if (!values.isEmpty()) {
                // create a comma separated string from the values, this is stored in the map
                final String value = StringUtils.join(values, MULTIPLE_HEADER_DELIMITER);
                attributes.put(key, value);
            }
        });

        final Handshake handshake = responseHttp.handshake();
        if (handshake != null) {
            final Principal principal = handshake.peerPrincipal();
            if (principal != null) {
                attributes.put(REMOTE_DN, principal.getName());
            }
        }

        return attributes;
    }

    private Charset getCharsetFromMediaType(MediaType contentType) {
        return contentType != null ? contentType.charset(StandardCharsets.UTF_8) : StandardCharsets.UTF_8;
    }

    private static File getResponseCacheDirectory() throws IOException {
        return Files.createTempDirectory(InvokeHTTP.class.getSimpleName()).toFile();
    }

    private FlowFileNamingStrategy getFlowFileNamingStrategy(final ProcessContext context) {
        final String strategy = context.getProperty(RESPONSE_FLOW_FILE_NAMING_STRATEGY).getValue();
        return FlowFileNamingStrategy.valueOf(strategy);
    }

    private String getFileNameFromUrl(URL url) {
        String fileName = null;
        String path = StringUtils.removeEnd(url.getPath(), "/");

        if (!StringUtils.isEmpty(path)) {
            fileName = path.substring(path.lastIndexOf('/') + 1);
        }

        return fileName;
    }

    private Optional<HttpMethod> findRequestMethod(String method) {
        return Arrays.stream(HttpMethod.values())
                .filter(httpMethod -> httpMethod.name().equals(method))
                .findFirst();
    }

    private String getRequestMethod(final PropertyContext context, final FlowFile flowFile) {
        final String method = context.getProperty(HTTP_METHOD).evaluateAttributeExpressions(flowFile).getValue().toUpperCase();
        return StringUtils.trimToEmpty(method);
    }
    
}
