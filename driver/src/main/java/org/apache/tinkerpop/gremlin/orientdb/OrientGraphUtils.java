package org.apache.tinkerpop.gremlin.orientdb;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;

public class OrientGraphUtils {
    public static final String CONNECTION_OUT = "out";
    public static final String CONNECTION_IN = "in";

    public static String encodeClassName(String iClassName) {
        if (iClassName == null)
            return null;

        if (Character.isDigit(iClassName.charAt(0)))
            iClassName = "-" + iClassName;

        try {
            return URLEncoder.encode(iClassName, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return iClassName;
        }
    }

    public static String decodeClassName(String iClassName) {
        if (iClassName == null)
            return null;

        if (iClassName.charAt(0) == '-')
            iClassName = iClassName.substring(1);

        try {
            return URLDecoder.decode(iClassName, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return iClassName;
        }
    }

}
