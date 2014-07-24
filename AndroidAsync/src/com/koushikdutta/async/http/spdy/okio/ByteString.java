/*
 * Copyright 2014 Square Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.koushikdutta.async.http.spdy.okio;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import static com.koushikdutta.async.http.spdy.okio.Util.checkOffsetAndCount;

/**
 * An immutable sequence of bytes.
 *
 * <p><strong>Full disclosure:</strong> this class provides untrusted input and
 * output streams with raw access to the underlying byte array. A hostile
 * stream implementation could keep a reference to the mutable byte string,
 * violating the immutable guarantee of this class. For this reason a byte
 * string's immutability guarantee cannot be relied upon for security in applets
 * and other environments that run both trusted and untrusted code in the same
 * process.
 */
public final class ByteString implements Serializable {
  private static final char[] HEX_DIGITS =
      { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };
  private static final long serialVersionUID = 1L;

  /** A singleton empty {@code ByteString}. */
  public static final ByteString EMPTY = ByteString.of();

  final byte[] data;
  private transient int hashCode; // Lazily computed; 0 if unknown.
  private transient String utf8; // Lazily computed.

  ByteString(byte[] data) {
    this.data = data; // Trusted internal constructor doesn't clone data.
  }

  /**
   * Returns a new byte string containing a clone of the bytes of {@code data}.
   */
  public static ByteString of(byte... data) {
    if (data == null) throw new IllegalArgumentException("data == null");
    return new ByteString(data.clone());
  }

  /**
   * Returns a new byte string containing a copy of {@code byteCount} bytes of {@code data} starting
   * at {@code offset}.
   */
  public static ByteString of(byte[] data, int offset, int byteCount) {
    if (data == null) throw new IllegalArgumentException("data == null");
    checkOffsetAndCount(data.length, offset, byteCount);

    byte[] copy = new byte[byteCount];
    System.arraycopy(data, offset, copy, 0, byteCount);
    return new ByteString(copy);
  }

  /** Returns a new byte string containing the {@code UTF-8} bytes of {@code s}. */
  public static ByteString encodeUtf8(String s) {
    if (s == null) throw new IllegalArgumentException("s == null");
    ByteString byteString = new ByteString(s.getBytes(Util.UTF_8));
    byteString.utf8 = s;
    return byteString;
  }

  /** Constructs a new {@code String} by decoding the bytes as {@code UTF-8}. */
  public String utf8() {
    String result = utf8;
    // We don't care if we double-allocate in racy code.
    return result != null ? result : (utf8 = new String(data, Util.UTF_8));
  }

  /**
   * Returns this byte string encoded as <a
   * href="http://www.ietf.org/rfc/rfc2045.txt">Base64</a>. In violation of the
   * RFC, the returned string does not wrap lines at 76 columns.
   */
  public String base64() {
    return Base64.encode(data);
  }

  /**
   * Decodes the Base64-encoded bytes and returns their value as a byte string.
   * Returns null if {@code base64} is not a Base64-encoded sequence of bytes.
   */
  public static ByteString decodeBase64(String base64) {
    if (base64 == null) throw new IllegalArgumentException("base64 == null");
    byte[] decoded = Base64.decode(base64);
    return decoded != null ? new ByteString(decoded) : null;
  }

  /** Returns this byte string encoded in hexadecimal. */
  public String hex() {
    char[] result = new char[data.length * 2];
    int c = 0;
    for (byte b : data) {
      result[c++] = HEX_DIGITS[(b >> 4) & 0xf];
      result[c++] = HEX_DIGITS[b & 0xf];
    }
    return new String(result);
  }

  /** Decodes the hex-encoded bytes and returns their value a byte string. */
  public static ByteString decodeHex(String hex) {
    if (hex == null) throw new IllegalArgumentException("hex == null");
    if (hex.length() % 2 != 0) throw new IllegalArgumentException("Unexpected hex string: " + hex);

    byte[] result = new byte[hex.length() / 2];
    for (int i = 0; i < result.length; i++) {
      int d1 = decodeHexDigit(hex.charAt(i * 2)) << 4;
      int d2 = decodeHexDigit(hex.charAt(i * 2 + 1));
      result[i] = (byte) (d1 + d2);
    }
    return of(result);
  }

  private static int decodeHexDigit(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    throw new IllegalArgumentException("Unexpected hex digit: " + c);
  }

  /**
   * Reads {@code count} bytes from {@code in} and returns the result.
   *
   * @throws java.io.EOFException if {@code in} has fewer than {@code count}
   *     bytes to read.
   */
  public static ByteString read(InputStream in, int byteCount) throws IOException {
    if (in == null) throw new IllegalArgumentException("in == null");
    if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);

    byte[] result = new byte[byteCount];
    for (int offset = 0, read; offset < byteCount; offset += read) {
      read = in.read(result, offset, byteCount - offset);
      if (read == -1) throw new EOFException();
    }
    return new ByteString(result);
  }

  /**
   * Returns a byte string equal to this byte string, but with the bytes 'A'
   * through 'Z' replaced with the corresponding byte in 'a' through 'z'.
   * Returns this byte string if it contains no bytes in 'A' through 'Z'.
   */
  public ByteString toAsciiLowercase() {
    // Search for an uppercase character. If we don't find one, return this.
    for (int i = 0; i < data.length; i++) {
      byte c = data[i];
      if (c < 'A' || c > 'Z') continue;

      // If we reach this point, this string is not not lowercase. Create and
      // return a new byte string.
      byte[] lowercase = data.clone();
      lowercase[i++] = (byte) (c - ('A' - 'a'));
      for (; i < lowercase.length; i++) {
        c = lowercase[i];
        if (c < 'A' || c > 'Z') continue;
        lowercase[i] = (byte) (c - ('A' - 'a'));
      }
      return new ByteString(lowercase);
    }
    return this;
  }

  /**
   * Returns a byte string equal to this byte string, but with the bytes 'a'
   * through 'z' replaced with the corresponding byte in 'A' through 'Z'.
   * Returns this byte string if it contains no bytes in 'a' through 'z'.
   */
  public ByteString toAsciiUppercase() {
    // Search for an lowercase character. If we don't find one, return this.
    for (int i = 0; i < data.length; i++) {
      byte c = data[i];
      if (c < 'a' || c > 'z') continue;

      // If we reach this point, this string is not not uppercase. Create and
      // return a new byte string.
      byte[] lowercase = data.clone();
      lowercase[i++] = (byte) (c - ('a' - 'A'));
      for (; i < lowercase.length; i++) {
        c = lowercase[i];
        if (c < 'a' || c > 'z') continue;
        lowercase[i] = (byte) (c - ('a' - 'A'));
      }
      return new ByteString(lowercase);
    }
    return this;
  }

  /** Returns the byte at {@code pos}. */
  public byte getByte(int pos) {
    return data[pos];
  }

  /**
   * Returns the number of bytes in this ByteString.
   */
  public int size() {
    return data.length;
  }

  /**
   * Returns a byte array containing a copy of the bytes in this {@code ByteString}.
   */
  public byte[] toByteArray() {
    return data.clone();
  }

  /** Writes the contents of this byte string to {@code out}. */
  public void write(OutputStream out) throws IOException {
    if (out == null) throw new IllegalArgumentException("out == null");
    out.write(data);
  }

  @Override public boolean equals(Object o) {
    return o == this || o instanceof ByteString && Arrays.equals(((ByteString) o).data, data);
  }

  @Override public int hashCode() {
    int result = hashCode;
    return result != 0 ? result : (hashCode = Arrays.hashCode(data));
  }

  @Override public String toString() {
    if (data.length == 0) {
      return "ByteString[size=0]";
    }

    if (data.length <= 16) {
      return String.format("ByteString[size=%s data=%s]", data.length, hex());
    }

    try {
      return String.format("ByteString[size=%s md5=%s]", data.length,
          ByteString.of(MessageDigest.getInstance("MD5").digest(data)).hex());
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError();
    }
  }

  private void readObject(ObjectInputStream in) throws IOException {
    int dataLength = in.readInt();
    ByteString byteString = ByteString.read(in, dataLength);
    try {
      Field field = ByteString.class.getDeclaredField("data");
      field.setAccessible(true);
      field.set(this, byteString.data);
    } catch (NoSuchFieldException e) {
      throw new AssertionError();
    } catch (IllegalAccessException e) {
      throw new AssertionError();
    }
  }

  private void writeObject(ObjectOutputStream out) throws IOException {
    out.writeInt(data.length);
    out.write(data);
  }
}
