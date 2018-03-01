package com.light.library;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSourceException;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.Predicate;
import com.google.android.exoplayer2.util.Util;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.ProtocolException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by Tujiong on 2018/2/10.
 */

public class OptimalCacheHttpDataSource implements HttpDataSource {

    public static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 8 * 1000;
    public static final int DEFAULT_READ_TIMEOUT_MILLIS = 8 * 1000;

    private static final String TAG = "CustomHttpDataSource";
    private static final int MAX_REDIRECTS = 20; // Same limit as okhttp.
    private static final long MAX_BYTES_TO_DRAIN = 2048;
    private static final Pattern CONTENT_RANGE_HEADER =
            Pattern.compile("^bytes (\\d+)-(\\d+)/(\\d+)$");
    private static final AtomicReference<byte[]> skipBufferReference = new AtomicReference<byte[]>();

    private final boolean allowCrossProtocolRedirects;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final String userAgent;
    private final Predicate<String> contentTypePredicate;
    private final RequestProperties defaultRequestProperties;
    private final RequestProperties requestProperties;
    private final TransferListener<? super DataSource> listener;

    private DataSpec dataSpec;
    private HttpURLConnection connection;
    private InputStream inputStream;
    private boolean opened;

    private long bytesToSkip;
    private long bytesToRead;

    private long bytesSkipped;
    private long bytesRead;

    private boolean cache;
    private String cachePath;

    public OptimalCacheHttpDataSource(boolean cache, String cachePath, String userAgent, Predicate<String> contentTypePredicate) {
        this(cache, cachePath, userAgent, contentTypePredicate, null);
    }

    public OptimalCacheHttpDataSource(boolean cache, String cachePath, String userAgent, Predicate<String> contentTypePredicate,
                                      TransferListener<? super DataSource> listener) {
        this(cache, cachePath, userAgent, contentTypePredicate, listener, DEFAULT_CONNECT_TIMEOUT_MILLIS,
                DEFAULT_READ_TIMEOUT_MILLIS);
    }

    public OptimalCacheHttpDataSource(boolean cache, String cachePath, String userAgent, Predicate<String> contentTypePredicate,
                                      TransferListener<? super DataSource> listener, int connectTimeoutMillis,
                                      int readTimeoutMillis) {
        this(cache, cachePath, userAgent, contentTypePredicate, listener, connectTimeoutMillis, readTimeoutMillis, false,
                null);
    }

    public OptimalCacheHttpDataSource(boolean cache, String cachePath, String userAgent, Predicate<String> contentTypePredicate,
                                      TransferListener<? super DataSource> listener, int connectTimeoutMillis,
                                      int readTimeoutMillis, boolean allowCrossProtocolRedirects,
                                      RequestProperties defaultRequestProperties) {
        this.cache = cache;
        this.cachePath = cachePath;
        this.userAgent = Assertions.checkNotEmpty(userAgent);
        this.contentTypePredicate = contentTypePredicate;
        this.listener = listener;
        this.requestProperties = new RequestProperties();
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        this.allowCrossProtocolRedirects = allowCrossProtocolRedirects;
        this.defaultRequestProperties = defaultRequestProperties;
    }

    @Override
    public Uri getUri() {
        return connection == null ? null : Uri.parse(connection.getURL().toString());
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return connection == null ? null : connection.getHeaderFields();
    }

    @Override
    public void setRequestProperty(String name, String value) {
        Assertions.checkNotNull(name);
        Assertions.checkNotNull(value);
        requestProperties.set(name, value);
    }

    @Override
    public void clearRequestProperty(String name) {
        Assertions.checkNotNull(name);
        requestProperties.remove(name);
    }

    @Override
    public void clearAllRequestProperties() {
        requestProperties.clear();
    }

    @Override
    public long open(DataSpec dataSpec) throws HttpDataSourceException {
        this.dataSpec = dataSpec;
        this.bytesRead = 0;
        this.bytesSkipped = 0;
        try {
            connection = makeConnection(dataSpec);
        } catch (IOException e) {
            throw new HttpDataSourceException("Unable to connect to " + dataSpec.uri.toString(), e,
                    dataSpec, HttpDataSourceException.TYPE_OPEN);
        }

        int responseCode;
        try {
            responseCode = connection.getResponseCode();
        } catch (IOException e) {
            closeConnectionQuietly();
            throw new HttpDataSourceException("Unable to connect to " + dataSpec.uri.toString(), e,
                    dataSpec, HttpDataSourceException.TYPE_OPEN);
        }

        // Check for a valid response code.
        if (responseCode < 200 || responseCode > 299) {
            Map<String, List<String>> headers = connection.getHeaderFields();
            closeConnectionQuietly();
            InvalidResponseCodeException exception =
                    new InvalidResponseCodeException(responseCode, headers, dataSpec);
            if (responseCode == 416) {
                exception.initCause(new DataSourceException(DataSourceException.POSITION_OUT_OF_RANGE));
            }
            throw exception;
        }

        // Check for a valid content type.
        String contentType = connection.getContentType();
        if (contentTypePredicate != null && !contentTypePredicate.evaluate(contentType)) {
            closeConnectionQuietly();
            throw new InvalidContentTypeException(contentType, dataSpec);
        }

        // If we requested a range starting from a non-zero position and received a 200 rather than a
        // 206, then the server does not support partial requests. We'll need to manually skip to the
        // requested position.
        bytesToSkip = responseCode == 200 && dataSpec.position != 0 ? dataSpec.position : 0;

        // Determine the length of the data to be read, after skipping.
        if (!dataSpec.isFlagSet(DataSpec.FLAG_ALLOW_GZIP)) {
            if (dataSpec.length != C.LENGTH_UNSET) {
                bytesToRead = dataSpec.length;
            } else {
                long contentLength = getContentLength(connection);
                bytesToRead = contentLength != C.LENGTH_UNSET ? (contentLength - bytesToSkip)
                        : C.LENGTH_UNSET;
            }
        } else {
            // Gzip is enabled. If the server opts to use gzip then the content length in the response
            // will be that of the compressed data, which isn't what we want. Furthermore, there isn't a
            // reliable way to determine whether the gzip was used or not. Always use the dataSpec length
            // in this case.
            bytesToRead = dataSpec.length;
        }

        try {
            if (cache) {
                InputStream stream = connection.getInputStream();
                File seedingDir = new File(cachePath);
                if (!seedingDir.exists() || seedingDir.isFile()) {
                    seedingDir.mkdirs();
                }
                String url = dataSpec.uri.toString();
                String name = url.substring(url.lastIndexOf("/"));
                File file = new File(seedingDir, name);
                FileOutputStream outputStream = new FileOutputStream(file);
                byte[] bytes = new byte[1024 * 8];
                int len;
                while ((len = stream.read(bytes)) != -1) {
                    outputStream.write(bytes, 0, len);
                }
                inputStream = new FileInputStream(file);
            } else {
                inputStream = connection.getInputStream();
            }
        } catch (IOException e) {
            closeConnectionQuietly();
            throw new HttpDataSourceException(e, dataSpec, HttpDataSourceException.TYPE_OPEN);
        }

        opened = true;
        if (listener != null) {
            listener.onTransferStart(this, dataSpec);
        }

        return bytesToRead;
    }

    @Override
    public int read(byte[] buffer, int offset, int readLength) throws HttpDataSourceException {
        try {
            skipInternal();
            return readInternal(buffer, offset, readLength);
        } catch (IOException e) {
            throw new HttpDataSourceException(e, dataSpec, HttpDataSourceException.TYPE_READ);
        }
    }

    @Override
    public void close() throws HttpDataSourceException {
        try {
            if (inputStream != null) {
                maybeTerminateInputStream(connection, bytesRemaining());
                try {
                    inputStream.close();
                } catch (IOException e) {
                    throw new HttpDataSourceException(e, dataSpec, HttpDataSourceException.TYPE_CLOSE);
                }
            }
        } finally {
            inputStream = null;
            closeConnectionQuietly();
            if (opened) {
                opened = false;
                if (listener != null) {
                    listener.onTransferEnd(this);
                }
            }
        }
    }

    protected final HttpURLConnection getConnection() {
        return connection;
    }

    protected final long bytesSkipped() {
        return bytesSkipped;
    }

    protected final long bytesRead() {
        return bytesRead;
    }

    protected final long bytesRemaining() {
        return bytesToRead == C.LENGTH_UNSET ? bytesToRead : bytesToRead - bytesRead;
    }

    private HttpURLConnection makeConnection(DataSpec dataSpec) throws IOException {
        URL url = new URL(dataSpec.uri.toString());
        byte[] postBody = dataSpec.postBody;
        long position = dataSpec.position;
        long length = dataSpec.length;
        boolean allowGzip = dataSpec.isFlagSet(DataSpec.FLAG_ALLOW_GZIP);

        if (!allowCrossProtocolRedirects) {
            // HttpURLConnection disallows cross-protocol redirects, but otherwise performs redirection
            // automatically. This is the behavior we want, so use it.
            return makeConnection(url, postBody, position, length, allowGzip, true /* followRedirects */);
        }

        // We need to handle redirects ourselves to allow cross-protocol redirects.
        int redirectCount = 0;
        while (redirectCount++ <= MAX_REDIRECTS) {
            HttpURLConnection connection = makeConnection(
                    url, postBody, position, length, allowGzip, false /* followRedirects */);
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_MULT_CHOICE
                    || responseCode == HttpURLConnection.HTTP_MOVED_PERM
                    || responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                    || responseCode == HttpURLConnection.HTTP_SEE_OTHER
                    || (postBody == null
                    && (responseCode == 307 /* HTTP_TEMP_REDIRECT */
                    || responseCode == 308 /* HTTP_PERM_REDIRECT */))) {
                // For 300, 301, 302, and 303 POST requests follow the redirect and are transformed into
                // GET requests. For 307 and 308 POST requests are not redirected.
                postBody = null;
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                url = handleRedirect(url, location);
            } else {
                return connection;
            }
        }

        // If we get here we've been redirected more times than are permitted.
        throw new NoRouteToHostException("Too many redirects: " + redirectCount);
    }

    private HttpURLConnection makeConnection(URL url, byte[] postBody, long position,
                                             long length, boolean allowGzip, boolean followRedirects) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(connectTimeoutMillis);
        connection.setReadTimeout(readTimeoutMillis);
        if (defaultRequestProperties != null) {
            for (Map.Entry<String, String> property : defaultRequestProperties.getSnapshot().entrySet()) {
                connection.setRequestProperty(property.getKey(), property.getValue());
            }
        }
        for (Map.Entry<String, String> property : requestProperties.getSnapshot().entrySet()) {
            connection.setRequestProperty(property.getKey(), property.getValue());
        }
        if (!(position == 0 && length == C.LENGTH_UNSET)) {
            String rangeRequest = "bytes=" + position + "-";
            if (length != C.LENGTH_UNSET) {
                rangeRequest += (position + length - 1);
            }
            connection.setRequestProperty("Range", rangeRequest);
        }
        connection.setRequestProperty("User-Agent", userAgent);
        if (!allowGzip) {
            connection.setRequestProperty("Accept-Encoding", "identity");
        }
        connection.setInstanceFollowRedirects(followRedirects);
        connection.setDoOutput(postBody != null);
        if (postBody != null) {
            connection.setRequestMethod("POST");
            if (postBody.length == 0) {
                connection.connect();
            } else {
                connection.setFixedLengthStreamingMode(postBody.length);
                connection.connect();
                OutputStream os = connection.getOutputStream();
                os.write(postBody);
                os.close();
            }
        } else {
            connection.connect();
        }
        return connection;
    }

    private static URL handleRedirect(URL originalUrl, String location) throws IOException {
        if (location == null) {
            throw new ProtocolException("Null location redirect");
        }
        // Form the new url.
        URL url = new URL(originalUrl, location);
        // Check that the protocol of the new url is supported.
        String protocol = url.getProtocol();
        if (!"https".equals(protocol) && !"http".equals(protocol)) {
            throw new ProtocolException("Unsupported protocol redirect: " + protocol);
        }
        // Currently this method is only called if allowCrossProtocolRedirects is true, and so the code
        // below isn't required. If we ever decide to handle redirects ourselves when cross-protocol
        // redirects are disabled, we'll need to uncomment this block of code.
        // if (!allowCrossProtocolRedirects && !protocol.equals(originalUrl.getProtocol())) {
        //   throw new ProtocolException("Disallowed cross-protocol redirect ("
        //       + originalUrl.getProtocol() + " to " + protocol + ")");
        // }
        return url;
    }

    private static long getContentLength(HttpURLConnection connection) {
        long contentLength = C.LENGTH_UNSET;
        String contentLengthHeader = connection.getHeaderField("Content-Length");
        if (!TextUtils.isEmpty(contentLengthHeader)) {
            try {
                contentLength = Long.parseLong(contentLengthHeader);
            } catch (NumberFormatException e) {
                Log.e(TAG, "Unexpected Content-Length [" + contentLengthHeader + "]");
            }
        }
        String contentRangeHeader = connection.getHeaderField("Content-Range");
        if (!TextUtils.isEmpty(contentRangeHeader)) {
            Matcher matcher = CONTENT_RANGE_HEADER.matcher(contentRangeHeader);
            if (matcher.find()) {
                try {
                    long contentLengthFromRange =
                            Long.parseLong(matcher.group(2)) - Long.parseLong(matcher.group(1)) + 1;
                    if (contentLength < 0) {
                        // Some proxy servers strip the Content-Length header. Fall back to the length
                        // calculated here in this case.
                        contentLength = contentLengthFromRange;
                    } else if (contentLength != contentLengthFromRange) {
                        // If there is a discrepancy between the Content-Length and Content-Range headers,
                        // assume the one with the larger value is correct. We have seen cases where carrier
                        // change one of them to reduce the size of a request, but it is unlikely anybody would
                        // increase it.
                        Log.w(TAG, "Inconsistent headers [" + contentLengthHeader + "] [" + contentRangeHeader
                                + "]");
                        contentLength = Math.max(contentLength, contentLengthFromRange);
                    }
                } catch (NumberFormatException e) {
                    Log.e(TAG, "Unexpected Content-Range [" + contentRangeHeader + "]");
                }
            }
        }
        return contentLength;
    }

    private void skipInternal() throws IOException {
        if (bytesSkipped == bytesToSkip) {
            return;
        }

        // Acquire the shared skip buffer.
        byte[] skipBuffer = skipBufferReference.getAndSet(null);
        if (skipBuffer == null) {
            skipBuffer = new byte[4096];
        }

        while (bytesSkipped != bytesToSkip) {
            int readLength = (int) Math.min(bytesToSkip - bytesSkipped, skipBuffer.length);
            int read = inputStream.read(skipBuffer, 0, readLength);
            if (Thread.interrupted()) {
                throw new InterruptedIOException();
            }
            if (read == -1) {
                throw new EOFException();
            }
            bytesSkipped += read;
            if (listener != null) {
                listener.onBytesTransferred(this, read);
            }
        }

        // Release the shared skip buffer.
        skipBufferReference.set(skipBuffer);
    }

    private int readInternal(byte[] buffer, int offset, int readLength) throws IOException {
        if (readLength == 0) {
            return 0;
        }
        if (bytesToRead != C.LENGTH_UNSET) {
            long bytesRemaining = bytesToRead - bytesRead;
            if (bytesRemaining == 0) {
                return C.RESULT_END_OF_INPUT;
            }
            readLength = (int) Math.min(readLength, bytesRemaining);
        }

        int read = inputStream.read(buffer, offset, readLength);
        if (read == -1) {
            if (bytesToRead != C.LENGTH_UNSET) {
                // End of stream reached having not read sufficient data.
                throw new EOFException();
            }
            return C.RESULT_END_OF_INPUT;
        }

        bytesRead += read;
        if (listener != null) {
            listener.onBytesTransferred(this, read);
        }
        return read;
    }

    private static void maybeTerminateInputStream(HttpURLConnection connection, long bytesRemaining) {
        if (Util.SDK_INT != 19 && Util.SDK_INT != 20) {
            return;
        }

        try {
            InputStream inputStream = connection.getInputStream();
            if (bytesRemaining == C.LENGTH_UNSET) {
                // If the input stream has already ended, do nothing. The socket may be re-used.
                if (inputStream.read() == -1) {
                    return;
                }
            } else if (bytesRemaining <= MAX_BYTES_TO_DRAIN) {
                // There isn't much data left. Prefer to allow it to drain, which may allow the socket to be
                // re-used.
                return;
            }
            String className = inputStream.getClass().getName();
            if (className.equals("com.android.okhttp.internal.http.HttpTransport$ChunkedInputStream")
                    || className.equals(
                    "com.android.okhttp.internal.http.HttpTransport$FixedLengthInputStream")) {
                Class<?> superclass = inputStream.getClass().getSuperclass();
                Method unexpectedEndOfInput = superclass.getDeclaredMethod("unexpectedEndOfInput");
                unexpectedEndOfInput.setAccessible(true);
                unexpectedEndOfInput.invoke(inputStream);
            }
        } catch (Exception e) {
            // If an IOException then the connection didn't ever have an input stream, or it was closed
            // already. If another type of exception then something went wrong, most likely the device
            // isn't using okhttp.
        }
    }

    private void closeConnectionQuietly() {
        if (connection != null) {
            try {
                connection.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error while disconnecting", e);
            }
            connection = null;
        }
    }
}
