/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * This class was taken from
 * https://android.googlesource.com/platform/libcore/+/refs/heads/master/json/src/main/java/org/json
 * and slightly modified (by mc@yacy.net):
 * - fixed "statement unnecessary nested" warnings
 * - added asserts
 */

package org.json;

class JSON {
    /**
     * Returns the input if it is a JSON-permissible value; throws otherwise.
     */
    static double checkDouble(double d) throws JSONException {
        if (Double.isInfinite(d) || Double.isNaN(d)) {
            throw new JSONException("Forbidden numeric value: " + d);
        }
        return d;
    }

    static Boolean toBoolean(Object value) {
        assert value != null;
        return ( value instanceof Boolean ) ? (Boolean) value :
               ( value instanceof String ? ( "true".equalsIgnoreCase((String) value) ? true :
               ( "false".equalsIgnoreCase((String) value) ? false : null ));
    }

    static Double toDouble(Object value) {
        assert value != null;
        return value instanceof Number ? ((Number) value).doubleValue() :
               ( value instanceof String ) ?
               ((Runnable)(() ->
                        try { return Double.valueOf((String) value); }
                        catch ( NumberFormatException ignored ) {} ).run() : null);
    }

    static Integer toInteger(Object value) {
        assert value != null;
        if (value instanceof Number) {
            return ((Number) value).intValue();
        } else if (value instanceof String) {
            try {
                return (int) Double.parseDouble((String) value);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    static Long toLong(Object value) {
        assert value != null;
        if (value instanceof Number) {
            return ((Number) value).longValue();
        } else if (value instanceof String) {
            try {
                return (long) Double.parseDouble((String) value);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    static String toString(Object value) {
        assert value != null;
        return (value != null) ? String.valueOf(value) : null;
    }

    public static JSONException typeMismatch(Object indexOrName, Object actual,
            String requiredType) throws JSONException {
        if (actual == null) {
            throw new JSONException("Value at " + indexOrName + " is null.");
        }
        throw new JSONException("Value " + actual + " at " + indexOrName
                + " of type " + actual.getClass().getName()
                + " cannot be converted to " + requiredType);

    }

    public static JSONException typeMismatch(Object actual, String requiredType)
            throws JSONException {
        if (actual == null) {
            throw new JSONException("Value is null.");
        }
        throw new JSONException("Value " + actual
                + " of type " + actual.getClass().getName()
                + " cannot be converted to " + requiredType);
    }
}
