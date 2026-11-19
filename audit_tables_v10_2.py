#!/usr/bin/env python3
"""v10.2 Phase G exhaustive table audit — v10.1 codebase vs ground truth. (rev 2)
Robust brace-matched extraction + positional MAP_EFFECTS_INFOS comparison.
"""
import re, sys

AUTH = "/home/z/my-project/audit/auth"
POC = "/home/z/my-project/v10_2/v10_1_src/app/src/main/cpp"
results = []

def check(name, ok, detail):
    results.append((name, ok, detail))
    print(("PASS" if ok else "FAIL"), "-", name, "-", detail)

def strip_comments(s):
    s = re.sub(r"/\*.*?\*/", " ", s, flags=re.S)
    s = re.sub(r"//[^\n]*", " ", s)
    return s

def top_level_groups(region):
    """Split a region into top-level {...} groups by brace matching."""
    groups, depth, start = [], 0, -1
    for i, ch in enumerate(region):
        if ch == '{':
            if depth == 0:
                start = i
            depth += 1
        elif ch == '}':
            depth -= 1
            if depth == 0 and start >= 0:
                groups.append(region[start:i+1])
                start = -1
    return groups

def extract_region(path, decl_regex):
    """Extract the initializer region of a table via decl regex + brace match."""
    s = strip_comments(open(path, encoding="utf-8", errors="replace").read())
    m = re.search(decl_regex, s)
    if not m:
        return None
    i = s.index('{', m.end() - 1) if '{' in s[m.end()-1:m.end()+200] else None
    # find first '{' after the decl
    j = m.end()
    while j < len(s) and s[j] != '{':
        j += 1
    depth, k = 0, j
    while k < len(s):
        if s[k] == '{':
            depth += 1
        elif s[k] == '}':
            depth -= 1
            if depth == 0:
                return s[j:k+1]
        k += 1
    return None

def nums(txt):
    return [int(v, 0) for v in re.findall(r"0x[0-9A-Fa-f]+|\-?\d+", txt)]
# ---------- 1. SINE TABLE ----------
src = open(f"{AUTH}/silent-hill-decomp-master/src/bodyprog/libkmath/libkmath.s",
           encoding="utf-8", errors="replace").read()
m = re.search(r"glabel\s+\w+\b(.*?)enddlabel g_SineTable", src, re.S)
body = m.group(1) if m else src
shorts = re.findall(r"\.short\s+(0x[0-9A-Fa-f]+|\-?\d+)", body)
eng = [int(v, 0) for v in shorts]
eng = [v if v < 0x8000 else v - 0x10000 for v in eng]
poc_src = open(f"{POC}/sh_sine_table.h", encoding="utf-8", errors="replace").read()
m2 = re.search(r"kSineTable\[5120\]\s*=\s*\{(.*?)\};", poc_src, re.S)
poc = [int(v, 0) for v in re.findall(r"-?\d+", m2.group(1))]
diffs = [i for i, (a, b) in enumerate(zip(eng, poc)) if a != b]
check("SINE TABLE (g_SineTable, 5120)",
      len(eng) == 5120 and len(poc) == 5120 and not diffs,
      f"eng={len(eng)} poc={len(poc)} diffs={len(diffs)}")

# ---------- 2. UNR TABLE ----------
def table_nums(path, name):
    reg = extract_region(path, name + r"\s*(\[[0-9]+\])*\s*=")
    return nums(reg) if reg else None

swan_unr = table_nums(f"{AUTH}/swanstation-main/src/core/gte.cpp", r"unr_table")
poc_unr = table_nums(f"{POC}/fog_poc.c", r"GTE_UNR_TABLE")
diffs = [i for i, (a, b) in enumerate(zip(swan_unr or [], poc_unr or [])) if a != b]
check("UNR TABLE (gte.cpp unr_table, 257)",
      swan_unr and poc_unr and len(swan_unr) == 257 and len(poc_unr) == 257 and not diffs,
      f"swan={len(swan_unr or [])} poc={len(poc_unr or [])} diffs={len(diffs)}")

# ---------- 3. DITHER4 ----------
poc_d = table_nums(f"{POC}/fog_poc.c", r"DITHER4")
SWAN_DITHER = [-4, 0, -3, 1, 2, -2, 3, -1, -3, 1, -4, 0, 3, -1, 2, -2]
check("DITHER4 (gpu_types.h DITHER_MATRIX)", poc_d == SWAN_DITHER, f"poc={poc_d}")

# ---------- 4. FOG_CURVE ----------
reg = extract_region(f"{AUTH}/silent-hill-decomp-master/src/bodyprog/gfx/bodyprog_80055028.c",
                     r"s32\s+D_800AE1C0\s*\[\]\s*=")
eng_fog = nums(reg) if reg else None
poc_fog = table_nums(f"{POC}/fog_poc.c", r"FOG_CURVE")
diffs = [i for i, (a, b) in enumerate(zip(eng_fog or [], poc_fog or [])) if a != b]
check("FOG_CURVE (D_800AE1C0, 17)",
      eng_fog and poc_fog and len(eng_fog) == 17 and len(poc_fog) == 17 and not diffs,
      f"eng={eng_fog} poc={poc_fog}")

# ---------- 5. MAP_EFFECTS_INFOS (full 21 entries, positional) ----------
def q12v(expr):
    expr = expr.strip()
    mm = re.fullmatch(r"Q12\(\s*(-?[\d.]+)f?\s*\)", expr)
    if mm:
        return int(float(mm.group(1)) * 4096)
    mm = re.fullmatch(r"Q12\(\s*(\w+)\s*\)", expr)
    if mm and mm.group(1) == "CHUNK_SIZE":   # terrain.h: 40.0f
        return int(40.0 * 4096)
    return int(expr, 0)

FLAG_BITS = {
    "SpecialEnvEventFlags_None": 0,
    "SpecialEnvEventFlags_DarkEnvironment": 1 << 0,
    "SpecialEnvEventFlags_FlashlightAllowed": 1 << 1,
    "SpecialEnvEventFlags_EnableBrightness": 1 << 2,
    "SpecialEnvEventFlags_UseLighter": 1 << 3,
    "SpecialEnvEventFlags_EnableLensflare": 1 << 4,
    "UnkGfxEnum_0": 0, "UnkGfxEnum_1": 1, "UnkGfxEnum_2": 2,
    "true": 1, "false": 0,
    # PoC-side flag constants (fog_poc.c, same bit layout as the decomp)
    "SHENV_FLAG_DARKENV": 1 << 0,
    "SHENV_FLAG_FLASHLIGHT": 1 << 1,
    "SHENV_FLAG_BRIGHTNESS": 1 << 2,
    "SHENV_FLAG_USELIGHTER": 1 << 3,
    "SHENV_FLAG_LENSFLARE": 1 << 4,
    "SHENUM_0": 0, "SHENUM_1": 1, "SHENUM_2": 2,
}
def ev(expr):
    """Evaluate an expression of | - combined named constants or hex."""
    expr = expr.strip()
    total = 0
    for part in expr.split("|"):
        part = part.strip().strip("()")
        if part in FLAG_BITS:
            total |= FLAG_BITS[part]
        elif re.fullmatch(r"0x[0-9A-Fa-f]+", part):
            total |= int(part, 16)
        elif re.fullmatch(r"\d+", part):
            total |= int(part)
        else:
            return None
    return total

def split_top(txt):
    """Split comma-separated items at brace depth 0, stripping braces."""
    txt = txt.strip().strip("{}").strip()
    items, depth, cur = [], 0, ""
    for ch in txt:
        if ch == '{':
            depth += 1
        elif ch == '}':
            depth -= 1
        if ch == ',' and depth == 0:
            items.append(cur.strip()); cur = ""
        else:
            cur += ch
    if cur.strip():
        items.append(cur.strip())
    return items

def parse_decomp_entry(txt):
    d = {}
    mf = re.search(r"\.flags\s*=\s*\{\s*\.field_00\s*=\s*\{(.*?)\}\s*\}", txt, re.S)
    if mf:
        vals = [ev(x) for x in mf.group(1).split(",")]
        d["flags"] = vals
    for key in ("field_E",):
        mm = re.search(r"\." + key + r"\s*=\s*([^,\n]+),", txt)
        d[key] = ev(mm.group(1)) if mm else None
    for key in ("spotLightIntensity", "worldLightIntensity", "fogDistance"):
        mm = re.search(r"\." + key + r"\s*=\s*([^,\n]+),", txt)
        d[key] = q12v(mm.group(1)) if mm else None
    for key in ("worldTintR", "worldTintG", "worldTintB"):
        mm = re.search(r"\." + key + r"\s*=\s*([^,\n]+),", txt)
        d.setdefault("worldTintQ12", []).append(q12v(mm.group(1)) if mm else None)
    for key in ("fogColor", "pointLightTint", "worldTint", "field_21", "field_25"):
        mm = re.search(r"\." + key + r"\s*=\s*\{(.*?)\}", txt, re.S)
        d[key] = nums(mm.group(1))[:3] if mm else None
    mm = re.search(r"\.enableTintLightOverlap\s*=\s*(\w+),", txt)
    d["enableTintLightOverlap"] = ev(mm.group(1)) if mm else None
    return d

def parse_poc_entry(txt):
    """Positional: flags{4}, spot, wLI, worldTint{3}, field_E, fogDist,
    fogColor{3}, overlap, pointLightTint{3}, worldTint8{3}, f21{3}, f25{3}"""
    txt = strip_comments(txt)
    flat = re.sub(r"\s+", " ", txt).strip().strip("{}").strip()
    parts, depth, cur = [], 0, ""
    for ch in flat:
        if ch == '{':
            depth += 1
        if depth > 0 or ch != ',':
            cur += ch
        if ch == ',' and depth == 0:
            parts.append(cur.strip()); cur = ""
        elif ch == '}':
            depth -= 1
            if depth == 0:
                cur += ' '
    if cur.strip():
        parts.append(cur.strip())
    d = {}
    try:
        d["flags"] = [ev(x) for x in split_top(parts[0])]   # {f0, f1, enum, f3}
        d["spotLightIntensity"] = int(parts[1], 0)
        d["worldLightIntensity"] = int(parts[2], 0)
        d["worldTintQ12"] = nums(parts[3])
        d["field_E"] = int(parts[4], 0)
        d["fogDistance"] = int(parts[5], 0)
        d["fogColor"] = nums(parts[6])
        d["enableTintLightOverlap"] = int(parts[7], 0)
        d["pointLightTint"] = nums(parts[8])
        d["worldTint8"] = nums(parts[9])
        d["field_21"] = nums(parts[10])
        d["field_25"] = nums(parts[11])
    except Exception as e:
        d["parse_error"] = str(e)
    return d

dreg = extract_region(f"{AUTH}/silent-hill-decomp-master/src/bodyprog/sys/map_info.c",
                      r"MAP_EFFECTS_INFOS\[21\]\s*=")
preg = extract_region(f"{POC}/fog_poc.c", r"SH_MAP_EFFECTS_INFOS\s*\[21\]\s*=")
dents = [parse_decomp_entry(g) for g in top_level_groups(dreg[1:-1])]
pents = [parse_poc_entry(g) for g in top_level_groups(preg[1:-1])]
print(f"decomp entries: {len(dents)}, poc entries: {len(pents)}")
if len(dents) == 21 and len(pents) == 21:
    mismatch = 0
    for i in range(21):
        de, pe = dents[i], pents[i]
        probs = []
        if "parse_error" in pe:
            probs.append(f"parse_error {pe['parse_error']}")
        else:
            if de["flags"] != pe["flags"]:
                probs.append(f"flags {de['flags']} vs {pe['flags']}")
            for key in ("spotLightIntensity", "worldLightIntensity", "fogDistance",
                        "field_E", "enableTintLightOverlap"):
                if de.get(key) != pe.get(key):
                    probs.append(f"{key} {de.get(key)} vs {pe.get(key)}")
            if de["worldTintQ12"] != pe["worldTintQ12"]:
                probs.append(f"worldTintQ12 {de['worldTintQ12']} vs {pe['worldTintQ12']}")
            for key in ("fogColor", "field_21", "field_25"):
                if de[key] != pe[key]:
                    probs.append(f"{key} {de[key]} vs {pe[key]}")
            # decomp pointLightTint/worldTint vs poc pointLightTint/worldTint8
            if de["pointLightTint"] != pe["pointLightTint"]:
                probs.append(f"pointLightTint {de['pointLightTint']} vs {pe['pointLightTint']}")
            if de["worldTint"] != pe["worldTint8"]:
                probs.append(f"worldTint8 {de['worldTint']} vs {pe['worldTint8']}")
        if probs:
            mismatch += 1
            print(f"  entry[{i}] PROBLEMS: {probs}")
    check("MAP_EFFECTS_INFOS (map_info.c, 21 entries full)",
          mismatch == 0, f"{21-mismatch}/21 entries exact")
else:
    check("MAP_EFFECTS_INFOS", False,
          f"entry counts decomp={len(dents)} poc={len(pents)}")

print()
fails = [r for r in results if not r[1]]
print(f"== TABLE AUDIT SUMMARY: {len(results)-len(fails)}/{len(results)} PASS ==")
sys.exit(1 if fails else 0)
