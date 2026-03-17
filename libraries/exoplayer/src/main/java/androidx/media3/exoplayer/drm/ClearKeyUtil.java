/*
 * Copyright (C) 2017 The Android Open Source Project
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
package androidx.media3.exoplayer.drm;

import static android.os.Build.VERSION.SDK_INT;

import android.util.Base64;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.DrmInitData.SchemeData;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Util;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Utility methods for ClearKey. */
public final class ClearKeyUtil {

  private static final String TAG = "ClearKeyUtil";
  private static final Pattern REGEX_KEYID = Pattern.compile("KEYID=0x([0-9a-fA-F]{32})");
  private static final Pattern REGEX_PLAYREADY_KID_VALUE = Pattern.compile("VALUE=\\\"([^\"]+)\\\"");
  private static final Pattern REGEX_PLAYREADY_KID_LEGACY = Pattern.compile("<KID>([^<]+)</KID>");

  private ClearKeyUtil() {}

  /**
   * Adjusts ClearKey request data obtained from the Android ClearKey CDM to be spec compliant.
   *
   * @param request The request data.
   * @return The adjusted request data.
   */
  public static byte[] adjustRequestData(byte[] request) {
    if (SDK_INT >= 27) {
      return request;
    }
    // Prior to O-MR1 the ClearKey CDM encoded the values in the "kids" array using Base64 encoding
    // rather than Base64Url encoding. See [Internal: b/64388098]. We know the exact request format
    // from the platform's InitDataParser.cpp. Since there aren't any "+" or "/" symbols elsewhere
    // in the request, it's safe to fix the encoding by replacement through the whole request.
    String requestString = Util.fromUtf8Bytes(request);
    return Util.getUtf8Bytes(base64ToBase64Url(requestString));
  }

  /**
   * Adjusts ClearKey response data to be suitable for providing to the Android ClearKey CDM.
   *
   * @param response The response data.
   * @return The adjusted response data.
   */
  public static byte[] adjustResponseData(byte[] response) {
    if (SDK_INT >= 27) {
      return response;
    }
    // Prior to O-MR1 the ClearKey CDM expected Base64 encoding rather than Base64Url encoding for
    // the "k" and "kid" strings. See [Internal: b/64388098]. We know that the ClearKey CDM only
    // looks at the k, kid and kty parameters in each key, so can ignore the rest of the response.
    try {
      JSONObject responseJson = new JSONObject(Util.fromUtf8Bytes(response));
      StringBuilder adjustedResponseBuilder = new StringBuilder("{\"keys\":[");
      JSONArray keysArray = responseJson.getJSONArray("keys");
      for (int i = 0; i < keysArray.length(); i++) {
        if (i != 0) {
          adjustedResponseBuilder.append(",");
        }
        JSONObject key = keysArray.getJSONObject(i);
        adjustedResponseBuilder.append("{\"k\":\"");
        adjustedResponseBuilder.append(base64UrlToBase64(key.getString("k")));
        adjustedResponseBuilder.append("\",\"kid\":\"");
        adjustedResponseBuilder.append(base64UrlToBase64(key.getString("kid")));
        adjustedResponseBuilder.append("\",\"kty\":\"");
        adjustedResponseBuilder.append(key.getString("kty"));
        adjustedResponseBuilder.append("\"}");
      }
      adjustedResponseBuilder.append("]}");
      return Util.getUtf8Bytes(adjustedResponseBuilder.toString());
    } catch (JSONException e) {
      Log.e(TAG, "Failed to adjust response data: " + Util.fromUtf8Bytes(response), e);
      return response;
    }
  }

  private static String base64ToBase64Url(String base64) {
    return base64.replace('+', '-').replace('/', '_');
  }

  private static String base64UrlToBase64(String base64Url) {
    return base64Url.replace('-', '+').replace('_', '/');
  }

  @Nullable
  public static byte[] buildClearKeyPssh(String line, SchemeData schemeData) {
    byte[] pssh = buildClearKeyPsshFromKeyId(line);
    return pssh != null ? pssh : buildClearKeyPssh(schemeData);
  }

  @Nullable
  public static byte[] buildClearKeyPssh(SchemeData schemeData) {
    if (!schemeData.hasData()) {
      return null;
    }
    if (C.WIDEVINE_UUID.equals(schemeData.uuid)) {
      return buildClearKeyPsshFromWidevinePssh(schemeData.data);
    }
    if (C.PLAYREADY_UUID.equals(schemeData.uuid)) {
      return buildClearKeyPsshFromPlayReadyPssh(schemeData.data);
    }
    return null;
  }

  @Nullable
  public static byte[] buildClearKeyPssh(List<SchemeData> schemeDatas) {
    for (SchemeData sd : schemeDatas) {
      if (C.WIDEVINE_UUID.equals(sd.uuid)) {
        byte[] pssh = buildClearKeyPssh(sd);
        if (pssh != null) {
          return pssh;
        }
      }
    }
    for (SchemeData sd : schemeDatas) {
      if (C.PLAYREADY_UUID.equals(sd.uuid)) {
        byte[] pssh = buildClearKeyPssh(sd);
        if (pssh != null) {
          return pssh;
        }
      }
    }
    return null;
  }

  @Nullable
  private static byte[] buildClearKeyPsshFromKeyId(String line) {
    Matcher matcher = REGEX_KEYID.matcher(line);
    if (!matcher.find()) {
      return null;
    }
    String hex = matcher.group(1);
    if (hex == null || hex.length() != 32) {
      return null;
    }
    byte[] keyId = new byte[16];
    for (int i = 0; i < 16; i++) {
      keyId[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
    }
    return buildCencPssh(keyId);
  }

  @Nullable
  private static byte[] buildClearKeyPsshFromWidevinePssh(byte[] pssh) {
    int pos = 32;
    while (pos + 18 <= pssh.length) {
      if ((pssh[pos] & 0xFF) == 0x12 && (pssh[pos + 1] & 0xFF) == 0x10) {
        return buildCencPssh(Arrays.copyOfRange(pssh, pos + 2, pos + 18));
      }
      pos++;
    }
    return null;
  }

  @Nullable
  private static byte[] buildClearKeyPsshFromPlayReadyPssh(byte[] pssh) {
    try {
      int pos = 38;
      while (pos + 4 < pssh.length) {
        int objectType = (pssh[pos] & 0xFF) | ((pssh[pos + 1] & 0xFF) << 8);
        int objectLength = (pssh[pos + 2] & 0xFF) | ((pssh[pos + 3] & 0xFF) << 8);
        pos += 4;
        if (objectType == 1) {
          if (pos + objectLength <= pssh.length) {
            String xml = new String(pssh, pos, objectLength, StandardCharsets.UTF_16LE);
            Matcher valueMatcher = REGEX_PLAYREADY_KID_VALUE.matcher(xml);
            if (valueMatcher.find()) {
              String encoded = valueMatcher.group(1);
              if (encoded != null) {
                byte[] kidLE = Base64.decode(encoded, Base64.DEFAULT);
                if (kidLE.length == 16) {
                  return buildCencPssh(playReadyKidToCenc(kidLE));
                }
              }
            }
            Matcher kidMatcher = REGEX_PLAYREADY_KID_LEGACY.matcher(xml);
            if (kidMatcher.find()) {
              String encoded = kidMatcher.group(1);
              if (encoded != null) {
                byte[] kidLE = Base64.decode(encoded, Base64.DEFAULT);
                if (kidLE.length == 16) {
                  return buildCencPssh(playReadyKidToCenc(kidLE));
                }
              }
            }
          }
          break;
        }
        pos += objectLength;
      }
    } catch (Exception e) {
      Log.w(TAG, "Failed to extract KID from PlayReady PSSH", e);
    }
    return null;
  }

  private static byte[] playReadyKidToCenc(byte[] kidLE) {
    byte[] be = new byte[16];
    be[0] = kidLE[3];
    be[1] = kidLE[2];
    be[2] = kidLE[1];
    be[3] = kidLE[0];
    be[4] = kidLE[5];
    be[5] = kidLE[4];
    be[6] = kidLE[7];
    be[7] = kidLE[6];
    System.arraycopy(kidLE, 8, be, 8, 8);
    return be;
  }

  private static byte[] buildCencPssh(byte[] kid) {
    int size = 4 + 4 + 4 + 16 + 4 + 16 + 4;
    ByteBuffer buf = ByteBuffer.allocate(size);
    buf.putInt(size);
    buf.put(new byte[]{'p', 's', 's', 'h'});
    buf.put(new byte[]{0x01, 0x00, 0x00, 0x00});
    buf.putLong(C.COMMON_PSSH_UUID.getMostSignificantBits());
    buf.putLong(C.COMMON_PSSH_UUID.getLeastSignificantBits());
    buf.putInt(1);
    buf.put(kid);
    buf.putInt(0);
    return buf.array();
  }
}
