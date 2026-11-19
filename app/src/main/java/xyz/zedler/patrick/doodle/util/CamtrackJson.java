/*
 * This file is part of Doodle Android.
 *
 * Doodle Android is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Doodle Android is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Doodle Android. If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright (c) 2019-2025 by Patrick Zedler
 */

package xyz.zedler.patrick.doodle.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v9.7 (Camtrack native editor): a strict, minimal JSON reader/writer used
 * by {@link CamtrackDoc} — the camdat.json data core of the native editor.
 *
 * <p><b>Why a private JSON implementation</b> (instead of android's
 * org.json): (1) the data core becomes plain-JVM unit-testable without
 * Robolectric; (2) the writer reproduces the CamTrack v13 HTML editor's
 * {@code JSON.stringify(docToJSON(), null, 2)} byte formatting — 2-space
 * indent, insertion-ordered keys, ECMAScript number formatting — so a
 * camdat saved by the native editor is byte-compatible with the reference
 * editor's export; (3) parsing accepts exactly the grammar the engine's
 * own MINI JSON PARSER (cpp/fog_poc.c) accepts: standard JSON with
 * objects, arrays, strings (incl. escapes and 4-hex unicode escapes),
 * numbers, true/false/null.</p>
 *
 * <p>Values map to: {@code LinkedHashMap<String,Object>} for objects
 * (insertion order preserved — duplicate keys resolve LAST-wins like
 * JavaScript's JSON.parse), {@code ArrayList<Object>} for arrays,
 * {@code String}, {@code Double} for ALL numbers (JS numbers are doubles;
 * integral doubles serialize without a decimal point), {@code Boolean}
 * and {@code null}.</p>
 *
 * <p>Number formatting parity (verified empirically against the shipped
 * camdat_v13.json values on the build JDK): integral values print as
 * integers ("60", "-274"); fractional values print via
 * BigDecimal.valueOf(v).stripTrailingZeros().toPlainString(), which for
 * the camdat domain (r4-rounded values and float32-derived bounds) is
 * character-identical to ECMAScript's Number::toString — e.g.
 * 78.46393585205078, 1.7012, 0.0001. NaN/Infinity serialize as
 * {@code null} exactly like JSON.stringify.</p>
 */
public final class CamtrackJson {

  private CamtrackJson() {
  }

  /** raised on malformed JSON (message carries the 1-based position) */
  public static class ParseException extends RuntimeException {
    public ParseException(String message) {
      super(message);
    }
  }

  /* ------------------------------------------------------------------ */
  /* parse                                                               */

  public static Object parse(String text) {
    if (text == null) {
      throw new ParseException("null input");
    }
    Parser p = new Parser(text);
    p.ws();
    Object v = p.value();
    p.ws();
    if (p.pos < p.len) {
      throw p.err("trailing content");
    }
    return v;
  }

  private static final class Parser {
    final String s;
    final int len;
    int pos;
    int depth;

    Parser(String s) {
      this.s = s;
      this.len = s.length();
    }

    ParseException err(String why) {
      return new ParseException(why + " at position " + (pos + 1));
    }

    /* v9.8 fix (Phase H malformed-JSON battery): the ENGINE's own JSON
     * parser caps nesting at depth 64 (fog_poc.c jparse_value:
     * "if (p->failed || p->depth > 64) { p->failed = 1; }") — the port
     * previously recursed without a cap, so a hostile/corrupted camdat
     * with thousands of nested arrays crashed the editor with a
     * StackOverflowError instead of a clean ParseException. Same limit,
     * same semantics (reject the whole document). */
    private static final int MAX_DEPTH = 64;

    void ws() {
      while (pos < len) {
        char c = s.charAt(pos);
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
          pos++;
        } else {
          break;
        }
      }
    }

    char peek() {
      if (pos >= len) {
        throw err("unexpected end of input");
      }
      return s.charAt(pos);
    }

    void expect(char c) {
      if (pos >= len || s.charAt(pos) != c) {
        throw err("expected '" + c + "'");
      }
      pos++;
    }

    Object value() {
      if (depth > MAX_DEPTH) {
        throw err("nesting deeper than " + MAX_DEPTH + " (the engine's own "
            + "JSON depth limit)");
      }
      char c = peek();
      switch (c) {
        case '{':
          return object();
        case '[':
          return array();
        case '"':
          return string();
        case 't':
          literal("true");
          return Boolean.TRUE;
        case 'f':
          literal("false");
          return Boolean.FALSE;
        case 'n':
          literal("null");
          return null;
        default:
          return number();
      }
    }

    void literal(String lit) {
      if (pos + lit.length() > len || !s.startsWith(lit, pos)) {
        throw err("invalid literal");
      }
      pos += lit.length();
    }

    Map<String, Object> object() {
      expect('{');
      depth++;
      Map<String, Object> map = new LinkedHashMap<>();
      ws();
      if (pos < len && s.charAt(pos) == '}') {
        pos++;
        depth--;
        return map;
      }
      while (true) {
        ws();
        String key = string();
        ws();
        expect(':');
        ws();
        Object v = value();
        map.put(key, v);      /* duplicate keys: LAST wins (JS semantics) */
        ws();
        char c = peek();
        if (c == ',') {
          pos++;
          continue;
        }
        if (c == '}') {
          pos++;
          depth--;
          return map;
        }
        throw err("expected ',' or '}'");
      }
    }

    List<Object> array() {
      expect('[');
      depth++;
      List<Object> list = new ArrayList<>();
      ws();
      if (pos < len && s.charAt(pos) == ']') {
        pos++;
        depth--;
        return list;
      }
      while (true) {
        ws();
        list.add(value());
        ws();
        char c = peek();
        if (c == ',') {
          pos++;
          continue;
        }
        if (c == ']') {
          pos++;
          depth--;
          return list;
        }
        throw err("expected ',' or ']'");
      }
    }

    String string() {
      expect('"');
      StringBuilder out = new StringBuilder();
      while (true) {
        if (pos >= len) {
          throw err("unterminated string");
        }
        char c = s.charAt(pos++);
        if (c == '"') {
          return out.toString();
        }
        if (c == '\\') {
          if (pos >= len) {
            throw err("unterminated escape");
          }
          char e = s.charAt(pos++);
          switch (e) {
            case 'n': out.append('\n'); break;
            case 't': out.append('\t'); break;
            case 'r': out.append('\r'); break;
            case 'b': out.append('\b'); break;
            case 'f': out.append('\f'); break;
            case '/': out.append('/');  break;
            case '"': out.append('"');  break;
            case '\\': out.append('\\'); break;
            case 'u':
              if (pos + 4 > len) {
                throw err("bad unicode escape");
              }
              int cp = 0;
              for (int i = 0; i < 4; i++) {
                char h = s.charAt(pos++);
                cp <<= 4;
                if (h >= '0' && h <= '9') {
                  cp |= h - '0';
                } else if (h >= 'a' && h <= 'f') {
                  cp |= h - 'a' + 10;
                } else if (h >= 'A' && h <= 'F') {
                  cp |= h - 'A' + 10;
                } else {
                  throw err("bad hex digit in unicode escape");
                }
              }
              out.append((char) cp);   /* surrogates pass through raw —
                                          the engine parser does the same */
              break;
            default:
              throw err("invalid escape '\\" + e + "'");
          }
        } else if (c < 0x20) {
          throw err("raw control character in string");
        } else {
          out.append(c);
        }
      }
    }

    Double number() {
      int start = pos;
      if (pos < len && s.charAt(pos) == '-') {
        pos++;
      }
      boolean digits = false;
      while (pos < len) {
        char c = s.charAt(pos);
        if (c >= '0' && c <= '9') {
          pos++;
          digits = true;
        } else {
          break;
        }
      }
      if (!digits) {
        throw err("invalid number");
      }
      if (pos < len && s.charAt(pos) == '.') {
        pos++;
        boolean frac = false;
        while (pos < len) {
          char c = s.charAt(pos);
          if (c >= '0' && c <= '9') {
            pos++;
            frac = true;
          } else {
            break;
          }
        }
        if (!frac) {
          throw err("invalid number (missing fraction digits)");
        }
      }
      if (pos < len && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
        pos++;
        if (pos < len && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
          pos++;
        }
        boolean exp = false;
        while (pos < len) {
          char c = s.charAt(pos);
          if (c >= '0' && c <= '9') {
            pos++;
            exp = true;
          } else {
            break;
          }
        }
        if (!exp) {
          throw err("invalid number (missing exponent digits)");
        }
      }
      try {
        return Double.parseDouble(s.substring(start, pos));
      } catch (NumberFormatException e) {
        throw err("number out of range");
      }
    }
  }

  /* ------------------------------------------------------------------ */
  /* write — JSON.stringify(v, null, 2) formatting                       */

  public static String write(Object value) {
    StringBuilder sb = new StringBuilder();
    writeValue(sb, value, 0);
    return sb.toString();
  }

  private static void writeValue(StringBuilder sb, Object v, int indent) {
    if (v == null) {
      sb.append("null");
    } else if (v instanceof String) {
      writeString(sb, (String) v);
    } else if (v instanceof Boolean) {
      sb.append(v.toString());
    } else if (v instanceof Double) {
      sb.append(formatNumber((Double) v));
    } else if (v instanceof Number) {
      /* only Double is produced by parse(); accept Number defensively */
      sb.append(formatNumber(((Number) v).doubleValue()));
    } else if (v instanceof Map) {
      writeObject(sb, (Map<?, ?>) v, indent);
    } else if (v instanceof List) {
      writeArray(sb, (List<?>) v, indent);
    } else {
      throw new IllegalArgumentException("unsupported value: " + v.getClass());
    }
  }

  private static void writeObject(StringBuilder sb, Map<?, ?> map, int indent) {
    if (map.isEmpty()) {
      sb.append("{}");
      return;
    }
    sb.append("{\n");
    int i = 0;
    for (Map.Entry<?, ?> e : map.entrySet()) {
      sb.append(spaces(indent + 2));
      writeString(sb, String.valueOf(e.getKey()));
      sb.append(": ");
      writeValue(sb, e.getValue(), indent + 2);
      if (++i < map.size()) {
        sb.append(',');
      }
      sb.append('\n');
    }
    sb.append(spaces(indent)).append('}');
  }

  private static void writeArray(StringBuilder sb, List<?> list, int indent) {
    if (list.isEmpty()) {
      sb.append("[]");
      return;
    }
    sb.append("[\n");
    for (int i = 0; i < list.size(); i++) {
      sb.append(spaces(indent + 2));
      writeValue(sb, list.get(i), indent + 2);
      if (i + 1 < list.size()) {
        sb.append(',');
      }
      sb.append('\n');
    }
    sb.append(spaces(indent)).append(']');
  }

  private static String spaces(int n) {
    char[] c = new char[n];
    java.util.Arrays.fill(c, ' ');
    return new String(c);
  }

  private static void writeString(StringBuilder sb, String s) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"': sb.append("\\\""); break;
        case '\\': sb.append("\\\\"); break;
        case '\n': sb.append("\\n"); break;
        case '\t': sb.append("\\t"); break;
        case '\r': sb.append("\\r"); break;
        case '\b': sb.append("\\b"); break;
        case '\f': sb.append("\\f"); break;
        default:
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);    /* non-ASCII emitted raw, like JSON.stringify */
          }
      }
    }
    sb.append('"');
  }

  /**
   * ECMAScript-compatible number text for the camdat value domain:
   * integers without a fraction part, fractional values via the shortest
   * decimal repr (verified identical to JS Number::toString for r4 values
   * and float32-derived bounds doubles), NaN/Infinity as {@code null}.
   *
   * <p><b>v9.8 fix (Phase H harness finding):</b> ECMAScript switches to
   * the exponential form for {@code |v| >= 1e21} ("1e+308", not a
   * 309-digit plain integer). The previous plain-decimal expansion was
   * valid JSON semantically, but the ENGINE's JSON number scanner
   * tokenises into a 64-byte buffer — a 309-digit literal overflows it
   * and made the WHOLE camdat fail to load ("JSON parse error"). Java's
   * {@code Double.toString} digits are exact-round-trip on every JDK
   * (shortest on JDK 19+), reformatted to the ECMAScript shape: mantissa
   * without a trailing {@code .0}, {@code e} with an explicit exponent
   * sign. Sub-{@code 1e-6} exponent formatting for pathological
   * pass-through bounds values is out of the camdat domain (r4 collapses
   * every serialized track value below 5e-5 to 0) and stays on the plain
   * path — valid JSON, identical semantics.</p>
   */
  static String formatNumber(double v) {
    if (Double.isNaN(v) || Double.isInfinite(v)) {
      return "null";
    }
    if (v == Math.rint(v) && Math.abs(v) < 1.0e18) {
      return Long.toString((long) v);
    }
    if (Math.abs(v) >= 1.0e21) {
      String s = Double.toString(v);            /* "1.0E308" / "1.23E300" */
      int e = s.indexOf('E');
      String mant = s.substring(0, e);
      String exp = s.substring(e + 1);
      int dot = mant.indexOf('.');
      if (dot >= 0) {
        while (mant.length() > dot + 2
            && mant.charAt(mant.length() - 1) == '0') {
          mant = mant.substring(0, mant.length() - 1);
        }
        if (mant.length() == dot + 2 && mant.charAt(dot + 1) == '0') {
          mant = mant.substring(0, dot);        /* "1.0" -> "1"            */
        }
      }
      if (!exp.startsWith("-") && !exp.startsWith("+")) {
        exp = "+" + exp;
      }
      return mant + "e" + exp;                  /* "1e+308"                */
    }
    java.math.BigDecimal bd = java.math.BigDecimal.valueOf(v)
        .stripTrailingZeros();
    String s = bd.toPlainString();
    /* 0 stripTrailingZeros yields "0" on all supported JDKs; guard the
     * legacy "0.0" edge just in case */
    if ("0.0".equals(s)) {
      return "0";
    }
    return s;
  }

  /* ------------------------------------------------------------------ */
  /* typed accessors — the jstr()/jnum()/jbool()/jget() lookup helpers   */
  /* mirror the ENGINE's own semantics (fog_poc.c): wrong-typed values   */
  /* degrade to the DEFAULT, exactly like the engine's parser            */

  static Object jget(Map<String, Object> obj, String key) {
    if (obj == null) {
      return null;
    }
    Object v = obj.get(key);
    return v;
  }

  static boolean isNum(Object v) {
    return v instanceof Double;
  }

  static boolean isBool(Object v) {
    return v instanceof Boolean;
  }

  static boolean isObj(Object v) {
    return v instanceof Map;
  }

  static boolean isArr(Object v) {
    return v instanceof List;
  }

  /** engine jstr(): the string when the value IS a string, else def */
  static String jstr(Map<String, Object> obj, String key, String def) {
    Object v = jget(obj, key);
    if (v instanceof String) {
      return (String) v;
    }
    return def;
  }

  /** engine jnum(): the number when the value IS a number, else def */
  static double jnum(Map<String, Object> obj, String key, double def) {
    Object v = jget(obj, key);
    if (v instanceof Double) {
      return (Double) v;
    }
    return def;
  }

  /** engine jbool(): true/false when the value IS a boolean, else def */
  static boolean jbool(Map<String, Object> obj, String key, boolean def) {
    Object v = jget(obj, key);
    if (v instanceof Boolean) {
      return (Boolean) v;
    }
    return def;
  }

  /** engine jint(): the number truncated toward zero, else def */
  static int jint(Map<String, Object> obj, String key, int def) {
    Object v = jget(obj, key);
    if (v instanceof Double) {
      return (int) (double) (Double) v;
    }
    return def;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> asObj(Object v) {
    if (v instanceof Map) {
      return (Map<String, Object>) v;
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  static List<Object> asArr(Object v) {
    if (v instanceof List) {
      return (List<Object>) v;
    }
    return null;
  }
}
