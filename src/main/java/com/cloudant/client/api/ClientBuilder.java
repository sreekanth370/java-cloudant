package com.cloudant.client.api;

import com.cloudant.client.api.model.Index;
import com.cloudant.client.api.model.Permissions;
import com.cloudant.client.api.model.Shard;
import com.cloudant.client.api.views.Key;
import com.cloudant.client.org.lightcouch.CouchDbException;
import com.cloudant.client.org.lightcouch.CouchDbProperties;
import com.cloudant.client.org.lightcouch.internal.URIBuilder;
import com.cloudant.http.HttpConnectionInterceptor;
import com.cloudant.http.HttpConnectionRequestInterceptor;
import com.cloudant.http.HttpConnectionResponseInterceptor;
import com.cloudant.http.interceptors.CookieInterceptor;
import com.cloudant.http.interceptors.ProxyAuthInterceptor;
import com.cloudant.http.interceptors.SSLCustomizerInterceptor;
import com.cloudant.http.interceptors.TimeoutCustomizationInterceptor;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocketFactory;

/**
 * This class builds new {@link CloudantClient} instances.
 *
 * <h2>Create a new CloudantClient instance for a Cloudant account</h2>
 * <pre>
 * {@code
 * CloudantClient client = ClientBuilder.account("yourCloudantAccount")
 *                          .username("yourUsername")
 *                          .password("yourPassword")
 *                          .build();
 * }
 * </pre>
 *
 * <h2>Create a new CloudantClient instance for a Cloudant Local</h2>
 * <pre>
 * {@code
 * CloudantClient client = ClientBuilder.url(new URL("https://yourCloudantLocalAddress.example"))
 *                          .username("yourUsername")
 *                          .password("yourPassword")
 *                          .build();
 * }
 * </pre>
 *
 * <h2>Examples creating instances with additional options</h2>
 * <h3>Configure a proxy server</h3>
 * <pre>
 * {@code
 * CloudantClient client = ClientBuilder.account("yourCloudantAccount")
 *                          .username("yourUsername")
 *                          .password("yourPassword")
 *                          .proxyURL(new URL("https://yourProxyServerAddress.example"))
 *                          .proxyUser(yourProxyUser)
 *                          .proxyPassword(yourProxyPass)
 *                          .build();
 * }
 * </pre>
 *
 * <h3>Client with a custom SSL socket factory</h3>
 * <pre>
 * {@code
 * CloudantClient client = ClientBuilder.account("yourCloudantAccount")
 *                          .username("yourUsername")
 *                          .password("yourPassword")
 *                          .customSSLSocketFactory(...)
 *                          .build();
 * }
 * </pre>
 *
 * <h3>Client with custom connection and read timeouts</h3>
 * <pre>
 * {@code
 * CloudantClient client = ClientBuilder.account("yourCloudantAccount")
 *                          .username("yourUsername")
 *                          .password("yourPassword")
 *                          .connectionTimeout(ClientBuilder.TimeoutOption.minutes(1))
 *                          .readTimeout(ClientBuilder.TimeoutOption.minutes(1))
 *                          .build();
 * }
 * </pre>
 *
 * @since 2.0.0
 */
public class ClientBuilder {

    /**
     * Default max of 6 connections
     **/
    public static final int DEFAULT_MAX_CONNECTIONS = 6;
    /**
     * Connection timeout defaults to 5 minutes
     **/
    public static final TimeoutOption DEFAULT_CONNECTION_TIMEOUT
            = TimeoutOption.minutes(5);
    /**
     * Read timeout defaults to 5 minutes
     **/
    public static final TimeoutOption DEFAULT_READ_TIMEOUT
            = TimeoutOption.minutes(5);

    private List<HttpConnectionRequestInterceptor> requestInterceptors = new ArrayList
            <HttpConnectionRequestInterceptor>();
    private List<HttpConnectionResponseInterceptor> responseInterceptors = new ArrayList
            <HttpConnectionResponseInterceptor>();
    private String password;
    private String username;
    private URL url;
    private GsonBuilder gsonBuilder;
    /**
     * Defaults to {@link #DEFAULT_MAX_CONNECTIONS}
     **/
    private int maxConnections = DEFAULT_MAX_CONNECTIONS;
    private URL proxyURL;
    private String proxyUser;
    private String proxyPassword;
    private boolean isSSLAuthenticationDisabled;
    private SSLSocketFactory authenticatedModeSSLSocketFactory;
    private TimeoutOption connectionTimeout = DEFAULT_CONNECTION_TIMEOUT;
    private TimeoutOption readTimeout = DEFAULT_READ_TIMEOUT;

    /**
     * Constructs a new ClientBuilder for building a CloudantClient instance to connect to the
     * Cloudant server with the specified account.
     *
     * @param account the Cloudant account name to connect to e.g. "example" is the account name
     *                for the "example.cloudant.com" endpoint
     * @return a new ClientBuilder for the account
     * @throws IllegalArgumentException if the specified account name forms an invalid endpoint URL
     */
    public static ClientBuilder account(String account) {
        try {
            URL url = new URL(String.format("https://%s.cloudant.com", account));
            return ClientBuilder.url(url);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Could not generate url from account name.", e);
        }
    }

    /**
     * Constructs a new ClientBuilder for building a CloudantClient instance to connect to the
     * Cloudant server with the specified URL.
     *
     * @param url server URL e.g. "https://yourCloudantLocalAddress.example"
     * @return a new ClientBuilder for the account
     */
    public static ClientBuilder url(URL url) {
        return new ClientBuilder(url);
    }


    private ClientBuilder(URL url) {
        //Check if port exists
        int port = url.getPort();
        if (port < 0) {
            port = url.getDefaultPort();
        }
        if (url.getUserInfo() != null) {
            //Get username and password and replace credential variables
            this.username = url.getUserInfo().substring(0, url
                    .getUserInfo()
                    .indexOf(":"));
            this.password = url.getUserInfo().substring(url
                    .getUserInfo()
                    .indexOf(":") + 1);
        }

        try {
            //Remove user credentials from url
            this.url = URIBuilder.buildUri()
                    .scheme(url.getProtocol())
                    .host(url.getHost())
                    .port(port)
                    .build().toURL();
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Build the {@link CloudantClient} instance based on the endpoint used to construct this
     * client builder and the options that have been set on it before calling this method.
     *
     * @return the {@link CloudantClient} instance for the specified end point and options
     */
    public CloudantClient build() {

        //Build properties and couchdb client
        CouchDbProperties props = new CouchDbProperties(url.getProtocol(),
                url.getHost(), url.getPort());

        //Create cookie interceptor
        if (this.username != null && this.password != null) {
            //make interceptor if both username and password are not null

            //Create cookie interceptor and set in HttpConnection interceptors
            CookieInterceptor cookieInterceptor = new CookieInterceptor(username, password);

            props.addRequestInterceptors(cookieInterceptor);
            props.addResponseInterceptors(cookieInterceptor);
        } else {
            //If username or password is null, throw an exception
            if (username != null || password != null) {
                //Username and password both have to contain values
                throw new CouchDbException("Either a username and password must be provided, or " +
                        "both values must be null. Please check the credentials and try again.");
            }
        }


        //If setter methods for read and connection timeout are not called, default values are used.
        props.addRequestInterceptors(new TimeoutCustomizationInterceptor(connectionTimeout,
                readTimeout));

        //Set connect options
        props.setMaxConnections(maxConnections);
        props.setProxyURL(proxyURL);
        if (proxyUser != null) {
            //if there was proxy auth information create an interceptor for it
            props.addRequestInterceptors(new ProxyAuthInterceptor(proxyUser,
                    proxyPassword));
        }
        if (isSSLAuthenticationDisabled) {
            props.addRequestInterceptors(SSLCustomizerInterceptor
                    .SSL_AUTH_DISABLED_INTERCEPTOR);
        }
        if (authenticatedModeSSLSocketFactory != null) {
            props.addRequestInterceptors(new SSLCustomizerInterceptor(
                    authenticatedModeSSLSocketFactory
            ));
        }

        //Set http connection interceptors
        if (requestInterceptors != null) {
            for (HttpConnectionRequestInterceptor requestInterceptor : requestInterceptors) {
                props.addRequestInterceptors(requestInterceptor);
            }
        }
        if (responseInterceptors != null) {
            for (HttpConnectionResponseInterceptor responseInterceptor : responseInterceptors) {
                props.addResponseInterceptors(responseInterceptor);
            }
        }

        //if no gsonBuilder has been provided, create a new one
        if (gsonBuilder == null) {
            gsonBuilder = new GsonBuilder();
        }
        //always register additional TypeAdapaters for derserializing some Cloudant specific
        // types before constructing the CloudantClient
        gsonBuilder.registerTypeAdapter(new TypeToken<List<Shard>>() {
        }.getType(), new ShardDeserializer())
                .registerTypeAdapter(new TypeToken<List<Index>>() {
                }.getType(), new IndexDeserializer())
                .registerTypeAdapter(new TypeToken<Map<String, EnumSet<Permissions>>>() {
                        }.getType(),
                        new SecurityDeserializer())
                .registerTypeAdapter(Key.ComplexKey.class, new Key.ComplexKeyDeserializer());

        return new CloudantClient(props, gsonBuilder);
    }

    /**
     * Sets a username or API key for the client connection.
     *
     * @param username the user or API key for the session
     * @return this ClientBuilder object for setting additional options
     */
    public ClientBuilder username(String username) {
        this.username = username;
        return this;
    }

    /**
     * Sets the password for the client connection. The password is the one for the username or
     * API key set by the {@link #username(String)} method.
     *
     * @param password user password or API key passphrase
     * @return this ClientBuilder object for setting additional options
     */
    public ClientBuilder password(String password) {
        this.password = password;
        return this;
    }

    /**
     * Set a custom GsonBuilder to use when serializing and de-serializing requests and
     * responses between the CloudantClient and the server.
     * <P>
     * Note: the supplied GsonBuilder will be augmented with some internal TypeAdapters.
     * </P>
     *
     * @param gsonBuilder the custom GsonBuilder to use
     * @return this ClientBuilder object for setting additional options
     */
    public ClientBuilder gsonBuilder(GsonBuilder gsonBuilder) {
        this.gsonBuilder = gsonBuilder;
        return this;
    }

    /**
     * Set the maximum number of connections to maintain in the connection pool.
     * <P>
     * Note: this setting only applies if using the optional OkHttp dependency. If OkHttp is not
     * present then the JVM configuration is used for pooling. Consult the JVM documentation for
     * the {@code http.maxConnections} property for further details.
     * </P>
     * Defaults to {@link #DEFAULT_MAX_CONNECTIONS}
     *
     * @param maxConnections the maximum number of simultaneous connections to open to the server
     * @return this ClientBuilder object for setting additional options
     */
    public ClientBuilder maxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
        return this;
    }

    /**
     * Sets a proxy url for the client connection.
     *
     * @param proxyURL the URL of the proxy server
     * @return this ClientBuilder object for setting additional options
     */
    public ClientBuilder proxyURL(URL proxyURL) {
        this.proxyURL = proxyURL;
        return this;
    }

    /**
     * Sets an optional proxy username for the client connection.
     *
     * @param proxyUser username for the proxy server
     * @return this ClientBuilder object for setting additional options
     */
    public ClientBuilder proxyUser(String proxyUser) {
        this.proxyUser = proxyUser;
        return this;
    }

    /**
     * Sets an optional proxy password for the proxy user specified by
     * {@link #proxyUser(String)}.
     *
     * @param proxyPassword password for the proxy server user
     * @return this ClientBuilder object for setting additional options
     */
    public ClientBuilder proxyPassword(String proxyPassword) {
        this.proxyPassword = proxyPassword;
        return this;
    }

    /**
     * Flag to disable hostname verification and certificate chain validation. This is not
     * recommended, but for example could be useful for testing with a self-signed certificate.
     * <P>
     * The SSL authentication is enabled by default meaning that hostname verification
     * and certificate chain validation is done using the JVM default settings.
     * </P>
     *
     * @return this ClientBuilder object for setting additional options
     * @throws IllegalStateException if {@link #customSSLSocketFactory(SSLSocketFactory)}
     * has been called on this ClientBuilder
     * @see #customSSLSocketFactory(SSLSocketFactory)
     */
    public ClientBuilder disableSSLAuthentication() {
        if(authenticatedModeSSLSocketFactory == null) {
            this.isSSLAuthenticationDisabled = true;
        } else {
            throw new IllegalStateException("Cannot disable SSL authentication when a " +
                    "custom SSLSocketFactory has been set.");
        }
        return this;
    }
    

    /**
     * Specifies the custom SSLSocketFactory to use when connecting to Cloudant over a
     * <code>https</code> URL, when SSL authentication is enabled.
     *
     * @param factory An SSLSocketFactory, or <code>null</code> for the
     *                default SSLSocketFactory of the JRE.
     * @return this ClientBuilder object for setting additional options
     * @throws IllegalStateException if {@link #disableSSLAuthentication()}
     * has been called on this ClientBuilder
     * @see #disableSSLAuthentication()
     */
    public ClientBuilder customSSLSocketFactory(SSLSocketFactory factory) {
        if(!isSSLAuthenticationDisabled) {
            this.authenticatedModeSSLSocketFactory = factory;
        } else {
            throw new IllegalStateException("Cannot use a custom SSLSocketFactory when " +
                    "SSL authentication is disabled.");
        }
        return this;
    }

    /**
     * This method adds {@link HttpConnectionInterceptor}s to be used on the CloudantClient
     * connection. Interceptors can be used to modify the HTTP requests and responses between the
     * CloudantClient and the server.
     * <P>
     * An example interceptor use might be to apply a custom authorization mechanism. For
     * instance to use BasicAuth instead of CookieAuth it is possible to add a
     * {@link com.cloudant.http.interceptors.BasicAuthInterceptor}:
     * </P>
     * <pre>
     * {@code
     * CloudantClient client = ClientBuilder.account("yourCloudantAccount")
     *      .interceptors(new BasicAuthInterceptor("yourUsername:yourPassword"))
     *      .build()
     * }
     * </pre>
     *
     * @param interceptors one or more HttpConnectionInterceptor objects
     * @return this ClientBuilder object for setting additional options
     * @see HttpConnectionInterceptor
     */
    public ClientBuilder interceptors(HttpConnectionInterceptor... interceptors) {
        for (HttpConnectionInterceptor interceptor : interceptors) {
            if (interceptor instanceof HttpConnectionRequestInterceptor) {
                requestInterceptors.add((HttpConnectionRequestInterceptor) interceptor);
            }
            if (interceptor instanceof HttpConnectionResponseInterceptor) {
                responseInterceptors.add((HttpConnectionResponseInterceptor) interceptor);
            }
        }
        return this;
    }

    /**
     * Sets the specified timeout value when opening the client connection. If the timeout
     * expires before the connection can be established, a
     * {@link java.net.SocketTimeoutException} is raised.
     * <P>
     * Example creating a {@link CloudantClient} with a connection timeout of 2 seconds:
     * </P>
     * <pre>
     * {@code
     * CloudantClient client = ClientBuilder.account("yourCloudantAccount")
     *      .username("yourUsername")
     *      .password("yourPassword")
     *      .connectionTimeout(ClientBuilder.TimeoutOption.seconds(2))
     *      .build();
     * }
     * </pre>
     * Defaults to {@link #DEFAULT_CONNECTION_TIMEOUT}
     *
     * @param connectionTimeout TimeoutOption of the required duration
     * @return this ClientBuilder object for setting additional options
     * @see com.cloudant.client.api.ClientBuilder.TimeoutOption
     * @see java.net.HttpURLConnection#setConnectTimeout(int)
     **/
    public ClientBuilder connectionTimeout(TimeoutOption connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
        return this;
    }

    /**
     * Sets the specified timeout value when reading from a {@link java.io.InputStream} with an
     * established client connection. If the timeout expires before there is data available for
     * read, a {@link java.net.SocketTimeoutException} is raised.
     * <P>
     * Example creating a {@link CloudantClient} with a read timeout of 2 seconds:
     * </P>
     * <pre>
     * {@code
     * CloudantClient client = ClientBuilder.account("yourCloudantAccount")
     *      .username("yourUsername")
     *      .password("yourPassword")
     *      .readTimeout(ClientBuilder.TimeoutOption.seconds(2))
     *      .build();
     * }
     * </pre>
     * Defaults to {@link #DEFAULT_READ_TIMEOUT}
     *
     * @param readTimeout TimeoutOption of the required duration
     * @return this ClientBuilder object for setting additional options
     * @see com.cloudant.client.api.ClientBuilder.TimeoutOption
     * @see java.net.HttpURLConnection#setReadTimeout(int)
     **/
    public ClientBuilder readTimeout(TimeoutOption readTimeout) {
        this.readTimeout = readTimeout;
        return this;
    }

    /**
     * Class that holds the value of a timeout, bound by the range 0 to {@link Integer#MAX_VALUE}
     * milliseconds. If the specified timeout exceeds the range maximum it will be set to the
     * maximum value and likewise if the value is less than 0 then it will be set at 0.
     * <P>
     * Conveniently creates timeout values in minutes, seconds or using another {@link TimeUnit}.
     * </P>
     *
     * @see ClientBuilder#readTimeout(TimeoutOption)
     * @see ClientBuilder#connectionTimeout(TimeoutOption)
     */
    public final static class TimeoutOption {

        private int timeoutMillis;

        /**
         * Constructs a new TimeoutOption instance with the specified timeout duration.
         *
         * @param timeout     value of the duration of the timeout in the specified unit
         * @param timeoutUnit the {@link TimeUnit} of the timeout duration value
         */
        public TimeoutOption(long timeout, TimeUnit timeoutUnit) {
            Long to = timeoutUnit.toMillis(timeout);
            if (timeout < 0) {
                timeoutMillis = 0;
            } else if (timeout > Integer.MAX_VALUE) {
                timeoutMillis = Integer.MAX_VALUE;
            } else {
                timeoutMillis = to.intValue();
            }
        }

        /**
         * Constructs a new TimeoutOption instance with the specified duration in minutes.
         *
         * @param minutes duration of the timeout in minutes
         * @return a new TimeoutOption for the duration specified in minutes
         */
        public static TimeoutOption minutes(int minutes) {
            return new TimeoutOption(minutes, TimeUnit.MINUTES);
        }

        /**
         * Constructs a new TimeoutOption instance with the specified duration in seconds.
         * @param seconds duration of the timeout in seconds
         * @return a new TimeoutOption for the duration specified in seconds
         */
        public static TimeoutOption seconds(int seconds) {
            return new TimeoutOption(seconds, TimeUnit.SECONDS);
        }

        /**
         * @return the value of the timeout in milliseconds
         */
        public int asIntMillis() {
            return timeoutMillis;
        }
    }
}
