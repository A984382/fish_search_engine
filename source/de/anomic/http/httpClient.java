// JakartaCommonsHttpClient.java
// (C) 2008 by Daniel Raap; danielr@users.berlios.de
// first published 2.4.2008 on http://yacy.net
//
// This is a part of YaCy, a peer-to-peer based web search engine
//
// $LastChangedDate: 2008-03-14 01:16:04 +0100 (Fr, 14 Mrz 2008) $
// $LastChangedRevision: 4558 $
// $LastChangedBy: orbiter $
//
// LICENSE
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA

package de.anomic.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.httpclient.ConnectMethod;
import org.apache.commons.httpclient.DefaultHttpMethodRetryHandler;
import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HostConfiguration;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.MultiThreadedHttpConnectionManager;
import org.apache.commons.httpclient.URIException;
import org.apache.commons.httpclient.cookie.CookiePolicy;
import org.apache.commons.httpclient.methods.ByteArrayRequestEntity;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.HeadMethod;
import org.apache.commons.httpclient.methods.InputStreamRequestEntity;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.RequestEntity;
import org.apache.commons.httpclient.methods.multipart.ByteArrayPartSource;
import org.apache.commons.httpclient.methods.multipart.FilePart;
import org.apache.commons.httpclient.methods.multipart.MultipartRequestEntity;
import org.apache.commons.httpclient.methods.multipart.Part;
import org.apache.commons.httpclient.params.DefaultHttpParams;
import org.apache.commons.httpclient.params.HttpMethodParams;
import org.apache.commons.httpclient.protocol.Protocol;
import org.apache.commons.httpclient.protocol.ProtocolSocketFactory;

import de.anomic.crawler.Latency;
import de.anomic.kelondro.order.Base64Order;
import de.anomic.kelondro.util.Log;
import de.anomic.yacy.yacyURL;

/**
 * HttpClient implementation which uses Jakarta Commons HttpClient 3.x {@link http://hc.apache.org/httpclient-3.x/}
 * 
 * @author danielr
 * 
 */
public class httpClient {

    /**
     * "the HttpClient instance and connection manager should be shared among all threads for maximum efficiency."
     * (Concurrent execution of HTTP methods, http://hc.apache.org/httpclient-3.x/performance.html)
     */
    private static MultiThreadedHttpConnectionManager conManager = null;
    private static HttpClient apacheHttpClient = null;

    // last ; must be before location (this is parsed)
    private final static String jakartaUserAgent = " " +
            ((String) DefaultHttpParams.getDefaultParams().getParameter(HttpMethodParams.USER_AGENT)).replace(';', ':');

    static {
        /**
         * set options for client
         */
        initConnectionManager();
        
        // accept self-signed or untrusted certificates
        Protocol.registerProtocol("https", new Protocol("https",
                (ProtocolSocketFactory) new AcceptEverythingSSLProtcolSocketFactory(), 443));

        /**
         * set network timeout properties. see: http://java.sun.com/j2se/1.5.0/docs/guide/net/properties.html These
         * properties specify the default connect and read timeout (resp.) for the protocol handler used by
         * java.net.URLConnection. the java.net.URLConnection is also used by JakartaCommons HttpClient, see
         * http://hc.apache.org/httpclient-3.x/apidocs/org/apache/commons/httpclient/util/HttpURLConnection.html
         */
        // specify the timeout, in milliseconds, to establish the connection to the host.
        // For HTTP connections, it is the timeout when establishing the connection to the HTTP server.
        System.setProperty("sun.net.client.defaultConnectTimeout", "10000");

        // specify the response timeout, in milliseconds, when reading from an input stream
        // after a connection is established with a resource
        System.setProperty("sun.net.client.defaultReadTimeout", "60000");
    }

    public static void initConnectionManager() {
        MultiThreadedHttpConnectionManager.shutdownAll();
        conManager = new MultiThreadedHttpConnectionManager();
        apacheHttpClient = new HttpClient(conManager);
        
        /**
         * set options for connection manager
         */
        // conManager.getParams().setDefaultMaxConnectionsPerHost(4); // default 2
        HostConfiguration localHostConfiguration = new HostConfiguration();
        conManager.getParams().setMaxTotalConnections(200); // Proxy may need many connections
        conManager.getParams().setConnectionTimeout(60000); // set a default timeout
        conManager.getParams().setDefaultMaxConnectionsPerHost(10);
        localHostConfiguration.setHost("localhost");
        conManager.getParams().setMaxConnectionsPerHost(localHostConfiguration, 100);
        localHostConfiguration.setHost("127.0.0.1");
        conManager.getParams().setMaxConnectionsPerHost(localHostConfiguration, 100);
        
        // only one retry
        apacheHttpClient.getParams().setParameter(HttpMethodParams.RETRY_HANDLER,
                                                  new DefaultHttpMethodRetryHandler(1, false));
        // simple user agent
        setUserAgent("yacy (www.yacy.net; " + getSystemOST() + ")");
        
    }
    
    /**
     * every x milliseconds do a cleanup (close old connections)
     * 
     * minimal intervall the cleanUp is done (in this time after a cleanup no second one is done)
     * 
     * this is the time the method is callable, not the time it is called
     */
    private static final int cleanupIntervall = 60000;
    /**
     * close connections when they are not used for this time
     * 
     * or otherwise: hold connections this time open to reuse them
     */
    private static final long closeConnectionsAfterMillis = 120000;
    /**
     * time the last cleanup was started
     */
    private static long lastCleanup = 0;

    private Header[] headers = new Header[0];
    private httpRemoteProxyConfig proxyConfig = null;
    private boolean useGlobalProxyConfig = true;
    private boolean followRedirects = true;
    private boolean ignoreCookies = false;

    /**
     * creates a new JakartaCommonsHttpClient with given timeout using global remoteProxyConfig
     *
     * @param timeout in milliseconds
     */
    public httpClient(final int timeout) {
        this(timeout, null);
    }

    /**
     * creates a new JakartaCommonsHttpClient with given timeout and requestHeader using global remoteProxyConfig
     *
     * @param timeout in milliseconds
     * @param header header options to send
     */
    public httpClient(final int timeout, final httpRequestHeader header) {
        super();
        setTimeout(timeout);
        setHeader(header);
    }

    /**
     * creates a new JakartaCommonsHttpClient with given timeout and requestHeader using given remoteProxyConfig
     * 
     * if proxyConfig is null, then no proxy is used
     * 
     * @param timeout in milliseconds
     * @param header header options to send
     * @param proxyConfig
     */
    public httpClient(final int timeout, final httpRequestHeader header, final httpRemoteProxyConfig proxyConfig) {
        super();
        setTimeout(timeout);
        setHeader(header);
        setProxy(proxyConfig);
    }

    /*
     * (non-Javadoc)
     * @see de.anomic.http.HttpClient#setProxy(de.anomic.http.httpRemoteProxyConfig)
     */
    public void setProxy(final httpRemoteProxyConfig proxyConfig) {
        this.useGlobalProxyConfig = false;
        this.proxyConfig = proxyConfig;
    }

    /*
     * (non-Javadoc)
     * @see de.anomic.http.HttpClient#setHeader(de.anomic.http.httpHeader)
     */
    public void setHeader(final httpRequestHeader header) {
        headers = convertHeaders(header);
    }

    /*
     * (non-Javadoc)
     * @see de.anomic.http.HttpClient#setTimeout(int)
     */
    public void setTimeout(final int timeout) {
        apacheHttpClient.getParams().setIntParameter(HttpMethodParams.SO_TIMEOUT, timeout);
    }

    /**
     * should redirects automatically be followed?
     * 
     * @param follow
     */
    public void setFollowRedirects(final boolean follow) {
        followRedirects = follow;
    }

    /**
     * <p>by default Cookies are accepted and used autmatically</p>
     * 
     * <q cite="http://hc.apache.org/httpclient-3.x/cookies.html">HttpClient supports automatic management of cookies, including allowing the server to set cookies and
     * automatically return them to the server when required.</q>
     * <cite>HttpClient Cookie Guide</cite>
     * 
     * @param ignoreCookies
     */
    public void setIgnoreCookies(final boolean ignoreCookies) {
        this.ignoreCookies = ignoreCookies;
    }

    /*
     * (non-Javadoc)
     * @see de.anomic.http.HttpClient#getUserAgent()
     */
    public String getUserAgent() {
        return getCurrentUserAgent();
    }

    /**
     * This method GETs a page from the server.
     * 
     * @param uri The URI to the page which should be GET.
     * @return InputStream of content (body)
     * @throws IOException
     */
    public httpResponse GET(final String uri) throws IOException {
        final HttpMethod get = new GetMethod(uri);
        get.setFollowRedirects(followRedirects);
        return execute(get);
    }

    /**
     * This method gets only the header of a page.
     * 
     * @param uri The URI to the page whose header should be get.
     * @return Instance of response with the content.
     * @throws IOException
     */
    public httpResponse HEAD(final String uri) throws IOException {
        assert uri != null : "precondition violated: uri != null";
        final HttpMethod head = new HeadMethod(uri);
        head.setFollowRedirects(followRedirects);
        return execute(head);
    }

    /**
     * This method POSTs some data from an InputStream to a page.
     * 
     * This is for compatibility (an InputStream does not need to contain correct HTTP!)
     * 
     * @param uri The URI to the page which the post is sent to.
     * @param ins InputStream with the data to be posted to the server.
     * @return Instance of response with the content.
     * @throws IOException
     */
    public httpResponse POST(final String uri, final InputStream ins) throws IOException {
        assert uri != null : "precondition violated: uri != null";
        assert ins != null : "precondition violated: ins != null";
        final PostMethod post = new PostMethod(uri);
        post.setRequestEntity(new InputStreamRequestEntity(ins));
        // redirects in POST cause a "Entity enclosing requests cannot be redirected without user intervention" -
        // exception
        post.setFollowRedirects(false);
        return execute(post);
    }

    /**
     * This method sends several data at once via a POST request (multipart-message), maybe compressed
     * 
     * @param uri The URI to the page which the post is sent to.
     * @param multiparts {@link java.util.List} with the {@link Part}s of data
     * @param gzipBody should the body be compressed
     * @return Instance of response with the content.
     * @throws IOException
     */
    public httpResponse POST(final String uri, final List<Part> multiparts, final boolean gzipBody)
            throws IOException {
        assert uri != null : "precondition violated: uri != null";
        final PostMethod post = new PostMethod(uri);

        final Part[] parts;
        if (multiparts != null) {
            parts = multiparts.toArray(new Part[multiparts.size()]);
        } else {
            // nothing to POST
            parts = new Part[0];
        }
        RequestEntity data = new MultipartRequestEntity(parts, post.getParams());
        if (gzipBody) {
            data = zipRequest(data);

            post.setRequestHeader(httpResponseHeader.CONTENT_ENCODING, httpHeader.CONTENT_ENCODING_GZIP);
            post.setContentChunked(true);
        }
        post.setRequestEntity(data);
        // redirects in POST cause a "Entity enclosing requests cannot be redirected without user intervention" -
        // exception
        post.setFollowRedirects(false);
        return execute(post);
    }

    /**
     * <p>stores the data of the request in a new ByteArrayRequestEntity</p>
     * 
     * <p>when the request is send make sure to set content-encoding-header to gzip!</p>
     * 
     * @param data
     * @return a ByteArrayRequestEntitiy with gzipped data
     * @throws IOException
     */
    private RequestEntity zipRequest(final RequestEntity data) throws IOException {
        // cache data and gzip it
        final ByteArrayOutputStream zippedBytes = new ByteArrayOutputStream(512);
        final GZIPOutputStream toZip = new GZIPOutputStream(zippedBytes);
        data.writeRequest(toZip);
        toZip.finish();
        toZip.flush();
        // use compressed data as body (not setting content length according to RFC 2616 HTTP/1.1, section 4.4)
        return new ByteArrayRequestEntity(zippedBytes.toByteArray(), data.getContentType());
    }

    /*
     * (non-Javadoc)
     * @see de.anomic.http.HttpClient#CONNECT(java.lang.String, int, de.anomic.http.httpHeader)
     */
    public httpResponse CONNECT(final String host, final int port) throws IOException {
        final HostConfiguration hostConfig = new HostConfiguration();
        hostConfig.setHost(host, port);
        final HttpMethod connect = new ConnectMethod(hostConfig);
        connect.setFollowRedirects(false); // there are no redirects possible for CONNECT commands as far as I know.
        return execute(connect);
    }

    /**
     * adds the yacy-header to the method
     * 
     * @param requestHeader
     * @param method
     */
    public void addHeader(final httpRequestHeader requestHeader, final HttpMethod method) {
        assert method != null : "precondition violated: method != null";
        if (requestHeader != null) {
            addHeaders(convertHeaders(requestHeader), method);
        }
    }

    /**
     * adds every Header in the array to the method
     * 
     * @param requestHeaders
     * @param method must not be null
     */
    private static void addHeaders(final Header[] requestHeaders, final HttpMethod method) {
        if (method == null) {
            throw new NullPointerException("method not set");
        }
        if (requestHeaders != null) {
            for (final Header header : requestHeaders) {
                method.addRequestHeader(header);
            }
        }
    }

    /**
     * convert from yacy-header to apache.commons.httpclient.Header
     * 
     * @param requestHeader
     * @return
     */
    private static Header[] convertHeaders(final httpRequestHeader requestHeader) {
        final Header[] headers;
        if (requestHeader == null) {
            headers = new Header[0];
        } else {
            headers = new Header[requestHeader.size()];
            int i = 0;
            for (final Entry<String, String> header : requestHeader.entrySet()) {
                headers[i] = new Header(header.getKey(), header.getValue());
                i++;
            }
        }
        return headers;
    }

    /**
     * executes a method
     * 
     * @param method
     * @return
     * @throws IOException
     */
    private httpResponse execute(final HttpMethod method) throws IOException {
        assert method != null : "precondition violated: method != null";
        checkIgnoreCookies(method);
        setHeader(method);

        final httpRemoteProxyConfig hostProxyConfig = setProxy(method);
        final HostConfiguration hostConfig = getProxyHostConfig(hostProxyConfig);

        // statistics
        HttpConnectionInfo.addConnection(generateConInfo(method));

        // execute (send request)
        if (Log.isFine("HTTPC")) Log.logFine("HTTPC", "executing " + method.hashCode() + " " + method.getName() + " " + method.getURI());
        if (Log.isFinest("HTTPC")) Log.logFinest("HTTPC", "->" + method.hashCode() + " request headers " +
                Arrays.toString(method.getRequestHeaders()));
        try {
            if (hostConfig == null) {
                apacheHttpClient.executeMethod(method);
            } else {
                apacheHttpClient.executeMethod(hostConfig, method);
            }
        } catch (final IllegalThreadStateException e) {
        	// cleanUp statistics
            yacyURL url = new yacyURL(method.getURI().toString(), null);
            Latency.slowdown(url.hash().substring(6), url.getHost());
            HttpConnectionInfo.removeConnection(generateConInfo(method));
            throw e;
        } catch (final IOException e) {
            // cleanUp statistics
            yacyURL url = new yacyURL(method.getURI().toString(), null);
            Latency.slowdown(url.hash().substring(6), url.getHost());
            HttpConnectionInfo.removeConnection(generateConInfo(method));
            throw e;
        } catch (final IllegalStateException e) {
            // cleanUp statistics
            yacyURL url = new yacyURL(method.getURI().toString(), null);
            Latency.slowdown(url.hash().substring(6), url.getHost());
            HttpConnectionInfo.removeConnection(generateConInfo(method));
            throw new IOException(e.getMessage());
        }
        if (Log.isFinest("HTTPC")) Log.logFinest("HTTPC", "<-" + method.hashCode() + " response headers " +
                Arrays.toString(method.getResponseHeaders()));

        // return response
        return new httpResponse(method);
    }

    /**
     * @param method
     * @return
     * @throws URIException
     */
    private httpRemoteProxyConfig setProxy(final HttpMethod method) throws URIException {
        String host = null;
        // set proxy
        try {
            host  = method.getURI().getHost();
        } catch (final URIException e) {
            Log.logWarning("HTTPC", "could not extract host of uri: "+ e.getMessage());
            throw e;
        }
        final httpRemoteProxyConfig hostProxyConfig = getProxyConfig(host);
        if(hostProxyConfig != null) {
            final String scheme = method.getURI().getScheme();
            if(scheme != null && scheme.toLowerCase().startsWith("https") && !hostProxyConfig.useProxy4SSL()) {
                // do not use proxy for HTTPS
                return null;
            }
            addProxyAuth(method, hostProxyConfig);
        }
        return hostProxyConfig;
    }

    /**
     * @param method
     */
    private void setHeader(final HttpMethod method) {
        // set header
        for (final Header header : headers) {
            method.setRequestHeader(header);
        }
    }

    /**
     * @param method
     */
    private void checkIgnoreCookies(final HttpMethod method) {
        // ignore cookies
        if(ignoreCookies) {
            method.getParams().setCookiePolicy(CookiePolicy.IGNORE_COOKIES);
        }
    }

    /**
     * @param method
     * @return
     */
    private HttpConnectionInfo generateConInfo(final HttpMethod method) {
        int port = 80;
        String host = null;
        String protocol = null;
        try {
            port = method.getURI().getPort();
            host = method.getURI().getHost();
            protocol = method.getURI().getScheme();
        } catch (final URIException e) {
            // should not happen, because method is already executed
        }
        final String query = method.getQueryString() != null ? "?" + method.getQueryString() : "";
        return new HttpConnectionInfo(protocol, port == -1 || port == 80 ? host : host + ":" + port, method.getName() +
                " " + method.getPath() + query, method.hashCode(), System.currentTimeMillis());
    }

    /**
     * if necessary adds a header for proxy-authentication
     * 
     * @param method
     * @param hostProxyConfig
     */
    private void addProxyAuth(final HttpMethod method, final httpRemoteProxyConfig hostProxyConfig) {
        if (hostProxyConfig != null && hostProxyConfig.useProxy()) {
            final String remoteProxyUser = hostProxyConfig.getProxyUser();
            if (remoteProxyUser != null && remoteProxyUser.length() > 0) {
                if (remoteProxyUser.contains(":")) {
                    Log.logWarning("HTTPC", "Proxy authentication contains invalid characters, trying anyway");
                }
                final String remoteProxyPwd = hostProxyConfig.getProxyPwd();
                final String credentials = Base64Order.standardCoder.encodeString(
                                        remoteProxyUser.replace(":", "") + ":" + remoteProxyPwd);
                method.setRequestHeader(httpRequestHeader.PROXY_AUTHORIZATION, "Basic " + credentials);
            }
        }
    }

    /**
     * 
     * @param hostname
     * @return null if no proxy should be used
     */
    private httpRemoteProxyConfig getProxyConfig(final String hostname) {
        final httpRemoteProxyConfig hostProxyConfig;
        if (!useGlobalProxyConfig) {
            // client specific
            if(proxyConfig == null) {
                hostProxyConfig = null;
            } else {
                hostProxyConfig = proxyConfig.useForHost(hostname) ? proxyConfig : null;
            }
        } else {
            // default settings
            hostProxyConfig = httpRemoteProxyConfig.getProxyConfigForHost(hostname);
        }
        return hostProxyConfig;
    }

    /**
     * @param hostProxyConfig
     * @return current host-config with additional proxy set or null if no proxy should be used
     */
    private HostConfiguration getProxyHostConfig(final httpRemoteProxyConfig hostProxyConfig) {
        final HostConfiguration hostConfig;
        // generate http-configuration
        if (hostProxyConfig != null && hostProxyConfig.useProxy()) {
            // new config based on client (default)
            hostConfig = new HostConfiguration(apacheHttpClient.getHostConfiguration());
            // add proxy
            hostConfig.setProxy(hostProxyConfig.getProxyHost(), hostProxyConfig.getProxyPort());
        } else {
            hostConfig = null;
        }
        return hostConfig;
    }

    /**
     * close all connections
     */
    public static void closeAllConnections() {
        conManager.closeIdleConnections(1);
        conManager.shutdown();
    }

    /**
     * gets the maximum number of connections allowed
     * 
     * @return
     */
    public static int maxConnections() {
        return conManager.getParams().getMaxTotalConnections();
    }

    /**
     * test
     * 
     * @param args
     */
    public static void main(final String[] args) {
        httpResponse resp = null;
        String url = args[0];
        if (!url.toUpperCase().startsWith("HTTP://")) {
            url = "http://" + url;
        }
        try {
            if (args.length > 1 && "post".equals(args[1])) {
                // POST
                final ArrayList<Part> files = new ArrayList<Part>();
                files.add(new DefaultCharsetFilePart("myfile.txt", new ByteArrayPartSource("myfile.txt", "this is not a file ;)"
                        .getBytes())));
                files.add(new FilePart("anotherfile.raw", new ByteArrayPartSource("anotherfile.raw",
                        "this is not a binary file ;)".getBytes())));
                System.out.println("POST " + files.size() + " elements to " + url);
                final httpClient client = new httpClient(1000);
                resp = client.POST(url, files, false);
                System.out.println("----- Header: -----");
                System.out.println(resp.getResponseHeader().toString());
                System.out.println("----- Body:   -----");
                System.out.println(new String(resp.getData()));
            } else if (args.length > 1 && "head".equals(args[1])) {
                // whead
                System.out.println("whead " + url);
                System.out.println("--------------------------------------");
                System.out.println(whead(url).toString());
            } else {
                // wget
                System.out.println("wget " + url);
                System.out.println("--------------------------------------");
                System.out.println(new String(wget(url, null, 10000)));
            }
        } catch (final IOException e) {
            e.printStackTrace();
        } finally {
            if (resp != null) {
                // release connection
                resp.closeStream();
            }
        }
    }

    /**
     * @return
     */
    public static String getCurrentUserAgent() {
        return (String) apacheHttpClient.getParams().getParameter(HttpMethodParams.USER_AGENT);
    }

    /**
     * @param userAgent
     */
    public static void setUserAgent(final String userAgent) {
        apacheHttpClient.getParams().setParameter(HttpMethodParams.USER_AGENT, userAgent + jakartaUserAgent);
    }

    /**
     * remove unused connections
     */
    public static void cleanup() {
        // do it only once a while
        final long now = System.currentTimeMillis();
        if (now - lastCleanup > cleanupIntervall) {
            lastCleanup = now;
            conManager.closeIdleConnections(closeConnectionsAfterMillis);
            conManager.deleteClosedConnections();
            HttpConnectionInfo.cleanUp();
        }
    }

    /**
     * number of active connections
     * 
     * @return
     */
    public static int connectionCount() {
        return conManager.getConnectionsInPool();
    }
    
    

    /**
     * provide system information for client identification
     */
    private static final String systemOST = System.getProperty("os.arch", "no-os-arch") + " " +
            System.getProperty("os.name", "no-os-name") + " " + System.getProperty("os.version", "no-os-version") +
            "; " + "java " + System.getProperty("java.version", "no-java-version") + "; " + generateLocation();

    /**
     * generating the location string
     * 
     * @return
     */
    public static String generateLocation() {
        String loc = System.getProperty("user.timezone", "nowhere");
        final int p = loc.indexOf("/");
        if (p > 0) {
            loc = loc.substring(0, p);
        }
        loc = loc + "/" + System.getProperty("user.language", "dumb");
        return loc;
    }

    /**
     * @return the systemOST
     */
    public static String getSystemOST() {
        return systemOST;
    }
    
    /**
     * Gets a page (as raw bytes) addressing vhost at host in uri with specified header and timeout
     * 
     * @param uri
     * @param header
     * @param vhost
     * @param timeout in milliseconds
     * @return
     */
    public static byte[] wget(final String uri) {
        return wget(uri, new httpRequestHeader(), 10000, null);
    }
    public static byte[] wget(final String uri, final httpRequestHeader header, final int timeout) {
        return wget(uri, header, timeout, null);
    }
    public static byte[] wget(final String uri, final httpRequestHeader header, final int timeout, final String vhost) {
        assert uri != null : "precondition violated: uri != null";
        addHostHeader(header, vhost);
        final httpClient client = new httpClient(timeout, header);

        // do the request
        httpResponse response = null;
        try {
            response = client.GET(uri);
            return response.getData();
        } catch (final IOException e) {
            Log.logWarning("HTTPC", "wget(" + uri + ") failed: " + e.getMessage());
        } finally {
            // release connection
            if (response != null) {
                response.closeStream();
            }
        }
        return null;
    }
    public static Reader wgetReader(final String uri) {
        return wgetReader(uri, new httpRequestHeader(), 10000, null);
    }
    public static Reader wgetReader(final String uri, final httpRequestHeader header, final int timeout) {
        return wgetReader(uri, header, timeout, null);
    }
    public static Reader wgetReader(final String uri, final httpRequestHeader header, final int timeout, final String vhost) {
        assert uri != null : "precondition violated: uri != null";
        addHostHeader(header, vhost);
        final httpClient client = new httpClient(timeout, header);

        // do the request
        httpResponse response = null;
        try {
            response = client.GET(uri);
            Charset charset = response.getResponseHeader().getCharSet();
            return new InputStreamReader(response.getDataAsStream(), charset);
        } catch (final IOException e) {
            Log.logWarning("HTTPC", "wgetReader(" + uri + ") failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * adds a Host-header to the header if vhost is not null
     * 
     * @param header
     * @param vhost
     * @return
     */
    private static void addHostHeader(httpRequestHeader header, final String vhost) {
        if (vhost != null) {
            if (header != null) {
                header = new httpRequestHeader();
            }
            // set host-header
            header.add(httpRequestHeader.HOST, vhost);
        }
    }

    /**
     * Gets a page-header
     * 
     * @param uri
     * @return
     */
    public static httpResponseHeader whead(final String uri) {
        return whead(uri, null);
    }

    /**
     * Gets a page-header
     * 
     * @param uri
     * @param header request header
     * @return null on error
     */
    public static httpResponseHeader whead(final String uri, final httpRequestHeader header) {
        final httpClient client = new httpClient(10000, header);
        httpResponse response = null;
        try {
            response = client.HEAD(uri);
            return response.getResponseHeader();
        } catch (final IOException e) {
            Log.logWarning("HTTPC", "whead(" + uri + ") failed: " + e.getMessage());
            return null;
        } finally {
            if (response != null) {
                response.closeStream();
            }
        }
    }
}