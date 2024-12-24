/*
 * Copyright (C) 2021 Yunify, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this work except in compliance with the License.
 * You may obtain a copy of the License in the LICENSE file, or at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.qingstor.sdk.utils;

import com.qingstor.sdk.config.EnvContext;
import com.qingstor.sdk.constants.ParamType;
import com.qingstor.sdk.constants.QSConstant;
import com.qingstor.sdk.exception.QSException;
import com.qingstor.sdk.model.RequestInputModel;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QSSignatureUtil {
    private static final Logger log = LoggerFactory.getLogger(QSSignatureUtil.class);

    private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;
    private static final String ALGORITHM = "HmacSHA256";
    private static final String GMT_DATE_FORMAT = "EEE, dd MMM yyyy HH:mm:ss z";
    private static final DateTimeFormatter timeFormatter =
            DateTimeFormatter.ofPattern(GMT_DATE_FORMAT, Locale.US).withZone(ZoneId.of("GMT"));

    private static Set<String> subSources = new HashSet<>();

    static {
        subSources.addAll(
                Arrays.asList(
                        "acl",
                        "cors",
                        "delete",
                        "mirror",
                        "part_number",
                        "policy",
                        "stats",
                        "upload_id",
                        "uploads",
                        "image",
                        "append",
                        "position",
                        "notification",
                        "lifecycle",
                        "logging",
                        "cname",
                        "response-expires",
                        "response-cache-control",
                        "response-content-type",
                        "response-content-language",
                        "response-content-encoding",
                        "response-content-disposition",
                        "versioning",
                        "version_id",
                        "versions"));
    }

    /**
     * @param parameters parameters to sign
     * @param requestUrl base request url
     * @return generated url
     * @throws QSException UnsupportedEncodingException
     */
    @Deprecated
    public static String generateQSURL(Map<String, String> parameters, String requestUrl)
            throws QSException {

        parameters = QSParamInvokeUtil.serializeParams(parameters);
        StringBuilder sbStringToSign = new StringBuilder();

        String[] sortedKeys = parameters.keySet().toArray(new String[] {});
        Arrays.sort(sortedKeys);
        int count = 0;

        for (String key : sortedKeys) {
            if (key.equals("image")) {
                continue;
            }
            if (count != 0) {
                sbStringToSign.append("&");
            }
            String value = parameters.get(key);
            sbStringToSign
                    .append(UrlUtils.rfc3986UriEncode(key, true))
                    .append("=")
                    .append(value == null ? null : UrlUtils.rfc3986UriEncode(value, true));
            count++;
        }

        if (sbStringToSign.length() > 0) {

            if (requestUrl.indexOf("?") > 0) {
                return String.format("%s&%s", requestUrl, sbStringToSign.toString());
            } else {
                return String.format("%s?%s", requestUrl, sbStringToSign.toString());
            }
        }
        return requestUrl;
    }

    /**
     * Generate signature for request against QingStor.
     *
     * @param accessKey: API access key ID
     * @param secretKey: API secret access key ID
     * @param method: HTTP method
     * @param requestURI:
     * @param params: HTTP request parameters
     * @param headers: HTTP request headers
     * @return a string which can be used as value of HTTP request header field "Authorization"
     *     directly.
     *     <p>See <a
     *     href="https://docsv4.qingcloud.com/user_guide/storage/object_storage/api/signature/">https://docsv4.qingcloud.com/user_guide/storage/object_storage/api/signature/</a>
     *     for more details about how to do signature of request against QingStor.
     */
    public static String generateAuthorization(
            String accessKey,
            String secretKey,
            String method,
            String requestURI,
            Map<String, String> params,
            Map<String, String> headers) {
        String signature = generateSignature(secretKey, method, requestURI, params, headers);
        return String.format("QS %s:%s", accessKey, signature);
    }

    /**
     * Generate signature for request against QingStor.
     *
     * @param accessKey: API access key ID
     * @param secretKey: API secret access key ID
     * @param strToSign: strToSign
     * @return a string which can be used as value of HTTP request header field "Authorization"
     *     directly.
     *     <p>See <a
     *     href="https://docsv4.qingcloud.com/user_guide/storage/object_storage/api/signature/">https://docsv4.qingcloud.com/user_guide/storage/object_storage/api/signature/</a>
     *     for more details about how to do signature of request against QingStor.
     */
    public static String generateAuthorization(
            String accessKey, String secretKey, String strToSign) {
        String signature = generateSignature(secretKey, strToSign);
        return String.format("QS %s:%s", accessKey, signature);
    }

    /**
     * Generate signature for request against QingStor.
     *
     * @param secretKey: API secret access key ID
     * @param method: HTTP method
     * @param requestURI:
     * @param params: HTTP request parameters
     * @param headers: HTTP request headers
     * @return a string which can be used as value of HTTP request header field "Authorization"
     *     directly.
     *     <p>See <a
     *     href="https://docsv4.qingcloud.com/user_guide/storage/object_storage/api/signature/">https://docsv4.qingcloud.com/user_guide/storage/object_storage/api/signature/</a>
     *     for more details about how to do signature of request against QingStor.
     */
    public static String generateSignature(
            String secretKey,
            String method,
            String requestURI,
            Map<String, String> params,
            Map<String, String> headers) {
        String signature;
        String strToSign = getStringToSignature(method, requestURI, params, headers);
        signature = generateSignature(secretKey, strToSign);
        return signature;
    }

    public static String getStringToSignature(
            String method,
            String authPath,
            Map<String, String> params,
            Map<String, String> headers) {
        final String SEPARATOR = "&";
        StringBuilder sb = new StringBuilder();

        sb.append(method.toUpperCase()).append("\n");

        String contentMD5 = "";
        String contentType = "";
        if (headers != null) {
            if (headers.containsKey("content-md5")) contentMD5 = headers.get("content-md5");
            if (headers.containsKey("content-type")) contentType = headers.get("content-type");
        }
        sb.append(contentMD5).append("\n");
        sb.append(contentType);

        // Append request time as string
        String dateStr = "";
        if (headers != null) {
            if (headers.containsKey(QSConstant.HEADER_PARAM_KEY_EXPIRES)) {
                dateStr = headers.get(QSConstant.HEADER_PARAM_KEY_EXPIRES);
            } else {
                if (!headers.containsKey(QSConstant.HEADER_PARAM_KEY_QS_DATE)) {
                    dateStr = headers.get(QSConstant.HEADER_PARAM_KEY_DATE);
                }
            }
        }
        sb.append("\n").append(dateStr);

        // Generate signed headers.
        if (headers != null) {
            String[] sortedHeadersKeys = headers.keySet().toArray(new String[] {});
            Arrays.sort(sortedHeadersKeys);
            for (String key : sortedHeadersKeys) {
                if (!key.startsWith("x-qs-") && !key.startsWith("X-QS-")) continue;
                sb.append(String.format("\n%s:%s", key.toLowerCase(), headers.get(key)));
            }
        }

        // Generate canonicalized query string.
        StringBuilder canonicalizedQuery = new StringBuilder();
        if (params != null) {
            String[] sortedParamsKeys = params.keySet().toArray(new String[] {});
            Arrays.sort(sortedParamsKeys);
            for (String key : sortedParamsKeys) {

                if (!isSubSource(key)) {
                    continue;
                }

                if (canonicalizedQuery.length() > 0) {
                    canonicalizedQuery.append(SEPARATOR);
                }

                try {
                    canonicalizedQuery.append(key);
                    String value = String.valueOf(params.get(key));
                    if (!value.isEmpty() && !value.equals("null")) {
                        canonicalizedQuery.append("=").append(value);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }

        // Generate canonicalized resource.
        String canonicalizedResource = authPath;
        if (canonicalizedQuery.length() > 0) {
            canonicalizedResource += "?" + canonicalizedQuery;
        }
        sb.append(String.format("\n%s", canonicalizedResource));
        String strToSign = sb.toString();

        log.debug("== String to sign == " + strToSign.replaceAll("\n", "\\\\n"));

        return strToSign;
    }

    public static String generateSignature(String secretKey, String strToSign) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secretKey.getBytes(DEFAULT_CHARSET), ALGORITHM));
            byte[] signData = mac.doFinal(strToSign.getBytes(DEFAULT_CHARSET));
            return Base64.getEncoder().encodeToString(signData);
        } catch (NoSuchAlgorithmException | IllegalStateException | InvalidKeyException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean isSubSource(String key) {
        return subSources.contains(key);
    }

    /**
     * format {@code time} to specified datetime format which used by QingStor. This returned string
     * will be the value part of http header: {@code Date} or {@code x-qs-date}.
     *
     * @param time time we will calculated(often init with ZonedDateTime.now())
     * @return formatted datetime string
     */
    public static String formatDateTime(ZonedDateTime time) {
        return time.format(timeFormatter);
    }

    @Deprecated
    public static String formatGmtDate(Date date) {
        SimpleDateFormat df = new SimpleDateFormat(GMT_DATE_FORMAT, Locale.US);
        df.setTimeZone(TimeZone.getTimeZone("GMT"));
        String dateStr = df.format(date);
        if (dateStr.indexOf("+") > 0) {
            return dateStr.substring(0, dateStr.indexOf("+"));
        }
        return dateStr;
    }

    @Deprecated
    public static String getObjectAuthRequestUrl(
            EnvContext envContext,
            String zone,
            String bucketName,
            String objectName,
            int expiresSecond)
            throws QSException {
        Map context = new HashMap();
        try {
            objectName = QSStringUtil.asciiCharactersEncoding(objectName);
            context.put(QSConstant.PARAM_KEY_REQUEST_ZONE, zone);
            context.put(QSConstant.ENV_CONTEXT_KEY, envContext);
            context.put("OperationName", "GetObject");
            context.put("APIName", "GetObject");
            context.put("ServiceName", "QingStor");
            context.put("RequestMethod", "GET");
            context.put("RequestURI", "/<bucket-name>/<object-key>");
            context.put("bucketNameInput", bucketName);
            context.put("objectNameInput", objectName);
            long expiresTime = (new Date().getTime() / 1000 + expiresSecond);
            String expireAuth = getExpireAuth(context, expiresTime, new RequestInputModel());
            String serviceUrl = envContext.getEndpoint().toString();
            String storRequestUrl = serviceUrl.replace("://", "://%s." + zone + ".");
            if (objectName.indexOf("?") > 0) {
                return String.format(
                        storRequestUrl + "/%s&access_key_id=%s&expires=%s&signature=%s",
                        bucketName,
                        objectName,
                        envContext.getAccessKeyId(),
                        expiresTime + "",
                        expireAuth);
            } else {
                return String.format(
                        storRequestUrl + "/%s?access_key_id=%s&expires=%s&signature=%s",
                        bucketName,
                        objectName,
                        envContext.getAccessKeyId(),
                        expiresTime + "",
                        expireAuth);
            }
        } catch (UnsupportedEncodingException e) {
            throw new QSException("Auth signature error", e);
        }
    }

    @Deprecated
    public static String getExpireAuth(Map context, long expiresSecond, RequestInputModel params)
            throws UnsupportedEncodingException {

        EnvContext envContext = (EnvContext) context.get(QSConstant.ENV_CONTEXT_KEY);

        Map paramsQuery = QSParamInvokeUtil.getRequestParams(params, ParamType.QUERY);
        Map paramsHeaders = QSParamInvokeUtil.getRequestParams(params, ParamType.HEADER);
        paramsHeaders.remove(QSConstant.HEADER_PARAM_KEY_DATE);
        paramsHeaders.clear();
        paramsHeaders.put(QSConstant.HEADER_PARAM_KEY_EXPIRES, expiresSecond + "");

        String method = (String) context.get(QSConstant.PARAM_KEY_REQUEST_METHOD);
        String bucketName = (String) context.get(QSConstant.PARAM_KEY_BUCKET_NAME);
        String requestPath = (String) context.get(QSConstant.PARAM_KEY_REQUEST_PATH);
        requestPath = requestPath.replace(QSConstant.BUCKET_PLACEHOLDER, bucketName);
        if (context.containsKey(QSConstant.PARAM_KEY_OBJECT_NAME)) {
            requestPath =
                    requestPath.replace(
                            QSConstant.OBJECT_PLACEHOLDER,
                            (String) context.get(QSConstant.PARAM_KEY_OBJECT_NAME));
        }
        String authSign =
                generateSignature(
                        envContext.getSecretAccessKey(),
                        method,
                        requestPath,
                        paramsQuery,
                        paramsHeaders);

        return URLEncoder.encode(authSign, QSConstant.ENCODING_UTF8);
    }
}
