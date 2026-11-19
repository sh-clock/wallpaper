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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 * v9.7 (Phase I): the JSON parse/serialize lossless round-trip proofs for
 * the native Camtrack editor's data core — NO data loss, NO schema
 * corruption for v13 camdats. The test resources carry the SHIPPED
 * camdat_v13.json (the reference editor's own output; md5
 * 3956260e2d39fabe7c66cfa0f037e8ea).
 */
public class CamtrackDocTest {

  private static final String SHIPPED = readResource("/camdat_v13.json");

  private static String readResource(String name) {
    try (InputStream in = CamtrackDocTest.class.getResourceAsStream(name)) {
      Assert.assertNotNull("test resource " + name + " missing", in);
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      byte[] buf = new byte[4096];
      int n;
      while ((n = in.read(buf)) > 0) {
        bos.write(buf, 0, n);
      }
      return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /* ------------------------------------------------------------------ */
  /* the lossless round-trip proofs                                       */

  @Test
  public void parseSerializeFixedPoint() {
    // serialize(parse(serialize(parse(x)))) == serialize(parse(x)):
    // the editor's save of its own save is a byte-stable fixed point
    CamtrackDoc doc = CamtrackDoc.parse(SHIPPED);
    String once = doc.serialize();
    CamtrackDoc again = CamtrackDoc.parse(once);
    String twice = again.serialize();
    Assert.assertEquals(once, twice);
  }

  @Test
  public void semanticRoundTripNoDataLoss() {
    CamtrackDoc original = CamtrackDoc.parse(SHIPPED);
    CamtrackDoc roundTripped = CamtrackDoc.parse(original.serialize());
    Assert.assertEquals(1, original.models.size());
    Assert.assertEquals(1, roundTripped.models.size());
    CamtrackDoc.Model a = original.models.get(0);
    CamtrackDoc.Model b = roundTripped.models.get(0);
    Assert.assertEquals(a.file, b.file);
    Assert.assertEquals(a.addedAt, b.addedAt);
    Assert.assertArrayEquals(a.boundsMin, b.boundsMin, 0);
    Assert.assertArrayEquals(a.boundsMax, b.boundsMax, 0);
    Assert.assertEquals(a.fogon, b.fogon);
    Assert.assertEquals(a.nighton, b.nighton);
    Assert.assertEquals(a.flashlighton, b.flashlighton);
    Assert.assertEquals(2, a.tracks.size());
    Assert.assertEquals(2, b.tracks.size());
    for (int i = 0; i < a.tracks.size(); i++) {
      CamtrackDoc.Track ta = a.tracks.get(i);
      CamtrackDoc.Track tb = b.tracks.get(i);
      Assert.assertEquals(ta.id, tb.id);
      Assert.assertEquals(ta.name, tb.name);
      Assert.assertEquals(ta.type, tb.type);
      Assert.assertEquals(ta.enabled, tb.enabled);
      Assert.assertEquals(ta.fovDeg, tb.fovDeg, 0);
      Assert.assertEquals(ta.loopMode, tb.loopMode);
      Assert.assertEquals(ta.lookMode, tb.lookMode);
      Assert.assertEquals(ta.weatherTarget, tb.weatherTarget);
      Assert.assertEquals(ta.envDay, tb.envDay);
      Assert.assertEquals(ta.envNight, tb.envNight);
      Assert.assertEquals(ta.speedMS, tb.speedMS, 0);
      Assert.assertArrayEquals(ta.start, tb.start, 0);
      Assert.assertArrayEquals(ta.end, tb.end, 0);
      if (ta.lookAt != null) {
        Assert.assertArrayEquals(ta.lookAt, tb.lookAt, 0);
      }
    }
    // the model env: every authored field survives
    Assert.assertNotNull(a.env);
    Assert.assertNotNull(b.env);
    Assert.assertEquals(a.env.pair0, b.env.pair0);
    Assert.assertEquals(a.env.pair1, b.env.pair1);
    Assert.assertEquals(a.env.weatherTarget, b.env.weatherTarget);
    Assert.assertEquals(a.env.bootWeather, b.env.bootWeather);
    Assert.assertEquals(a.env.lensFlare, b.env.lensFlare);
    Assert.assertEquals(a.env.flashlightOn, b.env.flashlightOn);
    Assert.assertEquals(a.env.psxWobble, b.env.psxWobble);
    Assert.assertEquals(a.env.dither, b.env.dither);
    Assert.assertEquals(a.env.particles, b.env.particles);
    Assert.assertEquals(a.env.engine, b.env.engine);
    Assert.assertEquals(a.env.dayEnv, b.env.dayEnv);
    Assert.assertEquals(a.env.nightEnv, b.env.nightEnv);
  }

  @Test
  public void byteCompatibleWithReferenceEditor() {
    // The native writer reproduces JSON.stringify(docToJSON(), null, 2):
    // byte-identical to the shipped file EXCEPT the two documented,
    // reference-editor-matching differences:
    //   1) the env key order — the CURRENT v13 editor's sanitizeEnv adds
    //      dayEnv/nightEnv right after pair (the shipped FILE predates it);
    //   2) durationSec recomputes from the ROUNDED coordinates on disk
    //      (the reference editor's own re-save behavior: the authoring-time
    //      unrounded values are not in the file) — 40.9009 -> 40.9008.
    CamtrackDoc doc = CamtrackDoc.parse(SHIPPED);
    String out = doc.serialize();
    // (1) key order: the current editor order, pair then dayEnv/nightEnv
    int pair = out.indexOf("\"pair\": [");
    int dayEnv = out.indexOf("\"dayEnv\":");
    int weather = out.indexOf("\"weatherTarget\": 4");
    Assert.assertTrue(pair >= 0 && dayEnv > pair && weather > dayEnv);
    // (2) durationSec recompute (the only VALUE difference)
    Assert.assertTrue(out.contains("40.9008"));
    Assert.assertFalse(out.contains("40.9009"));
    // everything else byte-equal: compare with the two normalizations
    Assert.assertEquals(canonicalize(SHIPPED), out);
  }

  /** the shipped file with the reference editor's CURRENT key order */
  private static String canonicalize(String shipped) {
    // move dayEnv/nightEnv after the pair block; move the track's
    // weatherTarget before durationSec (the current trackToJSON order)
    String pairBlock = "\"pair\": [\n          1,\n          1\n        ],\n";
    String weatherLine = "\"weatherTarget\": 4,";
    String s = shipped.replace(
        pairBlock + "        " + weatherLine,
        pairBlock + "        \"dayEnv\": 1,\n        \"nightEnv\": 3,\n"
            + "        " + weatherLine);
    // remove the old trailing dayEnv/nightEnv entries
    s = s.replace(",\n        \"dayEnv\": 1,\n        \"nightEnv\": 3\n      },",
        "\n      },");
    s = s.replace(
        "\"durationSec\": 40.9009,\n          \"weatherTarget\": 6",
        "\"weatherTarget\": 6,\n          \"durationSec\": 40.9008");
    // the shipped FILE carries a trailing newline (the download-writer's
    // convention); JSON.stringify itself emits none — normalize it away
    return s.stripTrailing();
  }

  /* ------------------------------------------------------------------ */
  /* the number formatter (ECMAScript parity)                            */

  @Test
  public void jsNumberFormatting() {
    Assert.assertEquals("60", CamtrackJson.formatNumber(60.0));
    Assert.assertEquals("-274", CamtrackJson.formatNumber(-274.0));
    Assert.assertEquals("1.7012", CamtrackJson.formatNumber(1.7012));
    Assert.assertEquals("110.9005", CamtrackJson.formatNumber(110.9005));
    Assert.assertEquals("0.0001", CamtrackJson.formatNumber(0.0001));
    Assert.assertEquals("78.46393585205078",
        CamtrackJson.formatNumber(78.46393585205078));
    Assert.assertEquals("-239.9765625",
        CamtrackJson.formatNumber(-239.9765625));
    Assert.assertEquals("0", CamtrackJson.formatNumber(0.0));
    Assert.assertEquals("null", CamtrackJson.formatNumber(Double.NaN));
    Assert.assertEquals("null", CamtrackJson.formatNumber(
        Double.POSITIVE_INFINITY));
  }

  /* ------------------------------------------------------------------ */
  /* clamps + presence rules (the engine parse spec)                     */

  @Test
  public void strictNumberClamps() {
    String json = "{\"models\":[{\"file\":\"a.glb\",\"tracks\":[{"
        + "\"type\":\"line\",\"weatherTarget\":99,\"envDay\":-5,"
        + "\"envNight\":33,"
        + "\"start\":[0,1,0],\"end\":[1,1,0],\"speedMS\":2,"
        + "\"lookMode\":\"path\"}]}]}";
    CamtrackDoc doc = CamtrackDoc.parse(json);
    CamtrackDoc.Track tr = doc.models.get(0).tracks.get(0);
    Assert.assertEquals(Integer.valueOf(8), tr.weatherTarget);   // clamp 0..8
    Assert.assertEquals(Integer.valueOf(0), tr.envDay);          // clamp 0..20
    Assert.assertEquals(Integer.valueOf(20), tr.envNight);       // clamp 0..20
    // serialization re-emits the CLAMPED values (sanitizeEnv semantics)
    String out = doc.serialize();
    Assert.assertTrue(out.contains("\"weatherTarget\": 8"));
    Assert.assertTrue(out.contains("\"envDay\": 0"));
    Assert.assertTrue(out.contains("\"envNight\": 20"));
  }

  @Test
  public void absentOptionalFieldsOmitted() {
    // no weatherTarget/envDay/envNight -> the fields are ABSENT (inherit)
    String json = "{\"models\":[{\"file\":\"a.glb\",\"tracks\":[{"
        + "\"type\":\"line\",\"start\":[0,1,0],\"end\":[1,1,0],"
        + "\"speedMS\":2,\"lookMode\":\"path\"}]}]}";
    CamtrackDoc doc = CamtrackDoc.parse(json);
    CamtrackDoc.Track tr = doc.models.get(0).tracks.get(0);
    Assert.assertNull(tr.weatherTarget);
    Assert.assertNull(tr.envDay);
    Assert.assertNull(tr.envNight);
    String out = doc.serialize();
    // NOTE: the format DOC_STRING mentions the field NAMES — assert on
    // the serialized FIELD pattern ("weatherTarget":) instead of the word
    Assert.assertFalse(out.contains("\"weatherTarget\":"));
    Assert.assertFalse(out.contains("\"envDay\":"));
    Assert.assertFalse(out.contains("\"envNight\":"));
  }

  @Test
  public void envClampsAndKeyOrder() {
    String json = "{\"models\":[{\"file\":\"a.glb\","
        + "\"env\":{\"pair\":[99,-7],\"weatherTarget\":42,"
        + "\"bootWeather\":99,\"lensFlare\":-3,"
        + "\"dayEnv\":77,\"nightEnv\":-1},"
        + "\"tracks\":[{\"type\":\"line\",\"start\":[0,1,0],"
        + "\"end\":[1,1,0],\"speedMS\":2,\"lookMode\":\"path\"}]}]}";
    CamtrackDoc doc = CamtrackDoc.parse(json);
    CamtrackDoc.Env env = doc.models.get(0).env;
    Assert.assertEquals(Integer.valueOf(20), env.pair0);
    Assert.assertEquals(Integer.valueOf(0), env.pair1);
    Assert.assertEquals(Integer.valueOf(8), env.weatherTarget);
    Assert.assertEquals(Integer.valueOf(6), env.bootWeather);
    Assert.assertEquals(Integer.valueOf(0), env.lensFlare);
    Assert.assertEquals(Integer.valueOf(20), env.dayEnv);
    Assert.assertEquals(Integer.valueOf(0), env.nightEnv);
    // the export key order: pair, dayEnv, nightEnv, weatherTarget, ...
    String out = doc.serialize();
    int pair = out.indexOf("\"pair\": [");
    int dayEnv = out.indexOf("\"dayEnv\": 20");
    int nightEnv = out.indexOf("\"nightEnv\": 0");
    int weather = out.indexOf("\"weatherTarget\": 8");
    int boot = out.indexOf("\"bootWeather\": 6");
    Assert.assertTrue(pair < dayEnv);
    Assert.assertTrue(dayEnv < nightEnv);
    Assert.assertTrue(nightEnv < weather);
    Assert.assertTrue(weather < boot);
  }

  @Test
  public void invalidTracksDropped() {
    // unknown type + a 1-point curve are DROPPED (the engine skip rule)
    String json = "{\"models\":[{\"file\":\"a.glb\",\"tracks\":["
        + "{\"type\":\"wormhole\",\"start\":[0,1,0]},"
        + "{\"type\":\"curve\",\"points\":[[0,1,0]],\"speedMS\":1},"
        + "{\"type\":\"line\",\"start\":[0,1,0],\"end\":[1,1,0],"
        + "\"speedMS\":2,\"lookMode\":\"path\"}]}]}";
    CamtrackDoc doc = CamtrackDoc.parse(json);
    Assert.assertEquals(1, doc.models.get(0).tracks.size());
    Assert.assertEquals(2, doc.droppedTracks);
    // the surviving track is engine-valid and index 0 (the --track N rule)
    Assert.assertTrue(doc.models.get(0).tracks.get(0).engineValid());
  }

  @Test
  public void boundsFlagReconstruction() {
    // arcBounds/yawBounds RECONSTRUCT from min/max pairs (never serialized)
    String json = "{\"models\":[{\"file\":\"a.glb\",\"tracks\":["
        + "{\"type\":\"circle\",\"center\":[0,1,0],\"radiusX\":2,"
        + "\"speedDegS\":6,\"lookMode\":\"center\","
        + "\"arcMinDeg\":-90,\"arcMaxDeg\":90}]}]}";
    CamtrackDoc doc = CamtrackDoc.parse(json);
    CamtrackDoc.Track tr = doc.models.get(0).tracks.get(0);
    Assert.assertTrue(tr.arcBounds);
    String out = doc.serialize();
    Assert.assertFalse(out.contains("arcBounds"));
    Assert.assertTrue(out.contains("\"arcMinDeg\": -90"));
    Assert.assertTrue(out.contains("\"arcMaxDeg\": 90"));
    // min > max -> NOT bounded (the reconstruction rule)
    String json2 = json.replace("\"arcMaxDeg\":90", "\"arcMaxDeg\":-120");
    CamtrackDoc doc2 = CamtrackDoc.parse(json2);
    Assert.assertFalse(doc2.models.get(0).tracks.get(0).arcBounds);
  }

  @Test
  public void fileNameSanitization() {
    Assert.assertEquals("room.glb", CamtrackDoc.sanitizeFileName("room.glb"));
    Assert.assertNull(CamtrackDoc.sanitizeFileName(""));
    Assert.assertNull(CamtrackDoc.sanitizeFileName(null));
    Assert.assertNull(CamtrackDoc.sanitizeFileName(".."));
    Assert.assertNull(CamtrackDoc.sanitizeFileName("a..b.glb"));
    // path-y names reduce to the plain BASENAME (the Sh1Camdat rule —
    // traversal is neutralized by stripping the directories)
    Assert.assertEquals("passwd", CamtrackDoc.sanitizeFileName("/etc/passwd"));
    Assert.assertEquals("evil.glb", CamtrackDoc.sanitizeFileName("..\\evil.glb"));
  }

  @Test
  public void validationGate() {
    CamtrackDoc empty = CamtrackDoc.blank();
    Assert.assertNotNull(empty.validateForSave());   // "no models"
    CamtrackDoc doc = CamtrackDoc.parse(SHIPPED);
    Assert.assertNull(doc.validateForSave());        // the shipped doc saves
  }

  /* ------------------------------------------------------------------ */
  /* makeTrack defaults + cycleDuration                                  */

  @Test
  public void makeTrackDefaults() {
    CamtrackDoc doc = CamtrackDoc.parse(SHIPPED);
    CamtrackDoc.Model model = doc.models.get(0);
    CamtrackDoc.Track line = CamtrackDoc.makeTrack(
        CamtrackDoc.TYPE_LINE, model);
    Assert.assertEquals(CamtrackDoc.TYPE_LINE, line.type);
    Assert.assertTrue(line.enabled);
    Assert.assertEquals(60, line.fovDeg, 0);
    Assert.assertEquals(CamtrackDoc.LOOP_PINGPONG, line.loopMode);
    Assert.assertEquals(CamtrackDoc.LOOK_PATH, line.lookMode);
    Assert.assertEquals(25, line.lazyRateDegS, 0);
    Assert.assertNull(line.weatherTarget);
    // the model already has "Line 1" and "Line 2" — nextName gives 3
    Assert.assertEquals("Line 3", line.name);
    // the line straddles the model bounds center (start = center - dx,
    // end = center + dx with dx = max(sizeX*0.3, 0.5)); r4 rounding of the
    // two endpoints moves the midpoint by at most one rounding step
    double centerX = (line.start[0] + line.end[0]) / 2.0;
    Assert.assertEquals(-256.98828125, centerX, 1e-3);
    // the model has two "Line" tracks already; the next is Line 3
    CamtrackDoc.Track another = CamtrackDoc.makeTrack(
        CamtrackDoc.TYPE_LINE, model);
    Assert.assertNotEquals(another.id, line.id);
  }

  @Test
  public void cycleDurationFormulas() {
    CamtrackDoc doc = CamtrackDoc.parse(SHIPPED);
    CamtrackDoc.Track line2 = doc.models.get(0).tracks.get(1);
    // Line 2: |end-start| / speed * 2 (pingPong) = 40.900826...
    Assert.assertEquals(40.90082621203456,
        CamtrackMath.cycleDuration(line2), 1e-9);
    CamtrackDoc.Track line1 = doc.models.get(0).tracks.get(0);
    Assert.assertEquals(29.6176, CamtrackDoc.r4(
        CamtrackMath.cycleDuration(line1)), 1e-9);
    // circle loop = 360 / speedDegS; pingPong doubles; once = infinite
    CamtrackDoc.Track circle = CamtrackDoc.makeTrack(
        CamtrackDoc.TYPE_CIRCLE, doc.models.get(0));
    circle.loopMode = CamtrackDoc.LOOP_LOOP;
    Assert.assertEquals(60, CamtrackMath.cycleDuration(circle), 1e-9);
    circle.loopMode = CamtrackDoc.LOOP_PINGPONG;
    // the reference v13 cycleDuration: an UNBOUNDED circle does NOT double
    // for pingPong (only the bounded arc does) — ported verbatim
    Assert.assertEquals(60, CamtrackMath.cycleDuration(circle), 1e-9);
    circle.loopMode = CamtrackDoc.LOOP_ONCE;
    Assert.assertEquals(Double.POSITIVE_INFINITY,
        CamtrackMath.cycleDuration(circle), 0);
    // a bounded arc: pingPong doubles the span walk
    circle.arcBounds = true;
    circle.arcMinDeg = -90;
    circle.arcMaxDeg = 90;
    circle.loopMode = CamtrackDoc.LOOP_LOOP;
    Assert.assertEquals(30, CamtrackMath.cycleDuration(circle), 1e-9);
    circle.loopMode = CamtrackDoc.LOOP_PINGPONG;
    Assert.assertEquals(60, CamtrackMath.cycleDuration(circle), 1e-9);
  }

  /* ------------------------------------------------------------------ */
  /* the JSON core                                                        */

  @Test
  public void jsonParserStrictness() {
    // valid JSON parses to the typed value tree
    Object arr = CamtrackJson.parse("[1.5, \"s\", true, null]");
    Assert.assertTrue(CamtrackJson.isArr(arr));
    List<Object> list = CamtrackJson.asArr(arr);
    Assert.assertEquals(Double.valueOf(1.5), list.get(0));
    Assert.assertEquals("s", list.get(1));
    Assert.assertEquals(Boolean.TRUE, list.get(2));
    Assert.assertNull(list.get(3));
    // malformed input throws
    try {
      CamtrackJson.parse("{");
      Assert.fail("expected ParseException");
    } catch (CamtrackJson.ParseException expected) {
    }
    try {
      CamtrackJson.parse("[1,]");
      Assert.fail("expected ParseException");
    } catch (CamtrackJson.ParseException expected) {
    }
    try {
      CamtrackJson.parse("nul");
      Assert.fail("expected ParseException");
    } catch (CamtrackJson.ParseException expected) {
    }
  }

  @Test
  public void jsonWriterFormatting() {
    // 2-space indent, insertion order, JS quoting — JSON.stringify parity
    java.util.Map<String, Object> obj = new java.util.LinkedHashMap<>();
    obj.put("a", 1.0);
    List<Object> arr = new java.util.ArrayList<>();
    arr.add(2.5);
    arr.add("x\"y");
    obj.put("b", arr);
    String out = CamtrackJson.write(obj);
    String expected = "{\n  \"a\": 1,\n  \"b\": [\n    2.5,\n    \"x\\\"y\"\n  ]\n}";
    Assert.assertEquals(expected, out);
    // empty containers
    Assert.assertEquals("{}", CamtrackJson.write(
        new java.util.LinkedHashMap<String, Object>()));
    Assert.assertEquals("[]", CamtrackJson.write(
        new java.util.ArrayList<Object>()));
  }

  @Test
  public void duplicateKeysLastWins() {
    // JS JSON.parse semantics: the LAST duplicate key wins
    Object v = CamtrackJson.parse("{\"k\": 1, \"k\": 2}");
    @SuppressWarnings("unchecked")
    java.util.Map<String, Object> m = (java.util.Map<String, Object>) v;
    Assert.assertEquals(Double.valueOf(2), m.get("k"));
  }

  /* ------------------------------------------------------------------ */
  /* the legacy flag derivation (writeEnvToModelLive port)               */

  @Test
  public void legacyFlagDerivation() {
    CamtrackDoc doc = CamtrackDoc.parse(SHIPPED);
    CamtrackDoc.Model m = doc.models.get(0);
    // the shipped env: pair {1,1} (day street: fog ON, not dark, no flash)
    m.deriveLegacyFlags();
    Assert.assertTrue(m.fogon);        // preset 1 has field_E=1 -> fog on
    Assert.assertFalse(m.nighton);     // preset 1 is not DARKENV
    Assert.assertFalse(m.flashlighton);
    // a night pair {6,x}: fog on + DARKENV
    m.env.pair0 = 6;
    m.env.pair1 = 6;
    m.deriveLegacyFlags();
    Assert.assertTrue(m.fogon);
    Assert.assertTrue(m.nighton);
    // engine-off clears fogon (the derivation's && engine rule)
    m.env.engine = false;
    m.deriveLegacyFlags();
    Assert.assertFalse(m.fogon);
  }

  /* ------------------------------------------------------------------ */
  /* v9.8 (Phase H): the two harness-discovered extreme-value fixes       */

  @Test
  public void r4ExtremeMagnitudesPreserved() {
    // v9.8 fix: Java's long-based Math.round clamped |v|*10000 beyond
    // Long.MAX_VALUE — r4(1e18) used to return 9.223372036854776e14
    // (garbage). The fixed r4 implements the JS Math.round double
    // semantics: floor(scaled + 0.5), and returns v unchanged in the
    // *10000-overflow range (where the JS reference itself corrupts to
    // null via JSON.stringify(Infinity)).
    Assert.assertEquals(1.0e18, CamtrackDoc.r4(1.0e18), 0.0);
    // NOT 1e21: 1e21*10000 rounds in f64 first — the JS reference returns
    // exactly the same 1.0000000000000001e21 (verified in node: the fixed
    // r4 is bit-identical to the reference for finite scaled values)
    Assert.assertEquals(1.0000000000000001E21, CamtrackDoc.r4(1.0e21), 0.0);
    // the *10000-overflow range: the JS reference returns Infinity here
    // (JSON.stringify -> null = data loss); the fixed port preserves v
    Assert.assertEquals(1.0e308, CamtrackDoc.r4(1.0e308), 0.0);
    Assert.assertEquals(-1.0e308, CamtrackDoc.r4(-1.0e308), 0.0);
    // NaN propagates as NaN (serialize() maps it to null like JSON.stringify)
    Assert.assertTrue(Double.isNaN(CamtrackDoc.r4(Double.NaN)));
    // subnormals collapse to 0 through the 4-decimal rounding
    Assert.assertEquals(0.0, CamtrackDoc.r4(1.0e-320), 0.0);
    // the sane domain is BIT-IDENTICAL to the old behavior (and to JS):
    // r4 = Math.round(v*10000)/10000 for every |v| <= ~9.2e14
    Assert.assertEquals(78.4639, CamtrackDoc.r4(78.46393585205078), 0.0);
    Assert.assertEquals(40.9008, CamtrackDoc.r4(40.90082621203456), 0.0);
    Assert.assertEquals(1.7012, CamtrackDoc.r4(1.7012), 0.0);
    Assert.assertEquals(60.0, CamtrackDoc.r4(60), 0.0);
    Assert.assertEquals(0.0, CamtrackDoc.r4(0), 0.0);
    Assert.assertEquals(-0.0, CamtrackDoc.r4(-0.0), 0.0);
    // negative halves round half-up like JS Math.round (floor(x+0.5))
    Assert.assertEquals(0.0, CamtrackDoc.r4(-0.00005), 0.0);
    Assert.assertEquals(-0.0001, CamtrackDoc.r4(-0.00006), 0.0);
    Assert.assertEquals(1.0001, CamtrackDoc.r4(1.00005), 0.0);
  }

  @Test
  public void serializeExtremeCoordinatesStayEngineParseable() {
    // v9.8 fix: |v| >= 1e21 must serialize in the ECMAScript exponential
    // form ("1e+308") — the previous plain-decimal expansion of 1e308
    // emitted a 309-digit literal that the ENGINE's 64-byte JSON number
    // scanner cannot tokenise (the WHOLE camdat failed to load).
    String json = "{\"format\":\"camdat\",\"version\":1,\"models\":[{\"file\":"
        + "\"X.glb\",\"tracks\":[{\"id\":\"t1\",\"name\":\"t\",\"type\":\"line\","
        + "\"enabled\":true,\"fovDeg\":60,\"loopMode\":\"loop\","
        + "\"start\":[1e308,-1e308,0],\"end\":[1e18,-1e18,0],"
        + "\"speedMS\":1,\"lookMode\":\"path\"}]}]}";
    CamtrackDoc doc = CamtrackDoc.parse(json);
    String out = doc.serialize();
    Assert.assertTrue("exponential form expected: " + out,
        out.contains("1e+308") && out.contains("-1e+308"));
    // NO number literal may exceed the engine's 63-char token budget
    for (String token : out.split("[^0-9eE+.-]")) {
      if (token.matches("-?[0-9][0-9.eE+\\-]*")) {
        Assert.assertTrue("engine-unparseable number token: " + token,
            token.length() <= 63);
      }
    }
    // and the values survive the round trip EXACTLY (lossless, unlike the
    // JS reference which writes null there = data loss on reload)
    CamtrackDoc back = CamtrackDoc.parse(out);
    CamtrackDoc.Track t = back.models.get(0).tracks.get(0);
    Assert.assertEquals(1.0e308, t.start[0], 0.0);
    Assert.assertEquals(-1.0e308, t.start[1], 0.0);
    Assert.assertEquals(0.0, t.start[2], 0.0);
    Assert.assertEquals(1.0e18, t.end[0], 0.0);
  }
}
