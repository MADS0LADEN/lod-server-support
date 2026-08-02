#!/usr/bin/env python3
"""release_check.py — gate the release artifacts before they ship.

Inspects the built release jars and the workflow/metadata that publishes them, asserting:
  * no dev-only code ships — Fabric excludes dev/vox/lss/benchmark/** (which also holds the
    soak driver); Paper's release shadowJar excludes dev/vox/lss/paper/soak/**; any future
    dev/vox/lss/common/{soak,benchmark}/** is forbidden on both platforms, and the scan
    recurses into Loom's nested Jar-in-Jar entries (META-INF/jars/*.jar — the Fabric jar
    ships common/ that way, invisible to a top-level-only namelist scan);
  * the dev-only Paper soak jar (lss-paper-soak*.jar) never matches the release glob;
  * required content is present — fabric.mod.json / plugin.yml, the common classes, LICENSE;
  * version placeholders are expanded (no literal ${version} in plugin.yml / fabric.mod.json);
  * Paper keeps the paperweight-mappings-namespace: mojang manifest attr through the shadowJar;
  * the local RELEASE_GLOBS artifact contract matches the CI artifact names (and not the
    soak jar) — NOTE: release.yml publishes only the LSS pair on EVERY line since v0.8.0;
    the VSS globs here still gate the locally built byte-copies (built, not published);
  * discovery is unambiguous — stale jars from earlier builds fail the run (or are excluded
    by an explicit --version), so a green pre-flight always validated the jar being tagged.

Run after a CI-style build:
  CI=true ./gradlew :fabric:build -x runClientGameTest :paper:shadowJar -Pmod_version=X.Y.Z
  python3 scripts/release_check.py --version X.Y.Z   # check exactly the release jars
  python3 scripts/release_check.py            # auto-discovers fabric/ + paper/ build/libs
  python3 scripts/release_check.py --selftest # synthetic-jar fixtures, no build needed

Exit nonzero if any violation is found. Stdlib only.
"""

import argparse
import fnmatch
import hashlib
import io
import json
import os
import re
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

FABRIC_FORBIDDEN = "dev/vox/lss/benchmark/"        # benchmark + soak driver live here on Fabric
PAPER_FORBIDDEN = "dev/vox/lss/paper/soak/"
# Dev-only namespaces that would live in common/ (e.g. a deduped soak-driver twin): common
# ships on BOTH platforms — nested in the Fabric jar, shaded into the Paper jar.
COMMON_FORBIDDEN = ("dev/vox/lss/common/soak/", "dev/vox/lss/common/benchmark/")
# All four shipped artifacts: the LSS pair (Modrinth lKiXKLvv) and the Voxy Server Side pair
# (Modrinth voxy-server-side). The VSS jars are branded byte-copies of the LSS jars — same
# classes, mod id `lss` / plugin name LodServerSupport, so they get the IDENTICAL safety
# gate plus an identity guardrail (check_vss_*_identity). See docs/planning/ci-dual-publish.md.
RELEASE_GLOBS = ("lod-server-support-fabric-*.jar", "lod-server-support-paper-*.jar",
                 "voxy-server-side-fabric-*.jar", "voxy-server-side-paper-*.jar")
CI_NAME_SUFFIX = "0.4.0+26.1.2.jar"  # a representative CI filename for glob round-tripping
# The Fabric jar's mapping namespace for THIS line: 26.x fabric-loom ships official-mapped
# jars; 1.21.x fabric-loom-remap ships intermediary. A forward merge swapping the loom
# plugin produces a right-named, gate-green jar in the WRONG namespace — unloadable on any
# real server of this line — so the manifest attribute is pinned like Paper's namespace.
FABRIC_MAPPING_NAMESPACE = "official"
SOAK_JAR_PREFIX = "lss-paper-soak"


def _names(jar):
    with zipfile.ZipFile(jar) as z:
        return z.namelist()


def _nested_jars(jar):
    """[(label, namelist)] for every jar nested inside (Loom Jar-in-Jar), recursively —
    the Fabric release jar ships common/ as META-INF/jars/common-*.jar, so a top-level
    namelist scan never sees its classes."""
    with zipfile.ZipFile(jar) as z:
        out = []
        for entry in z.namelist():
            if entry.startswith("META-INF/jars/") and entry.endswith(".jar"):
                out.extend(_walk_nested(entry, z.read(entry)))
        return out


def _walk_nested(label, data):
    out = []
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        names = z.namelist()
        out.append((label, names))
        for entry in names:
            if entry.startswith("META-INF/jars/") and entry.endswith(".jar"):
                out.extend(_walk_nested(f"{label}!{entry}", z.read(entry)))
    return out


def _scan_forbidden(jar, base, prefixes, problems):
    """Flag forbidden-prefix entries at the top level AND inside every nested jar."""
    for label, names in [(None, _names(jar))] + _nested_jars(jar):
        leaked = [n for n in names if n.startswith(prefixes)]
        if leaked:
            where = base if label is None else f"{base}!{label}"
            problems.append(f"{where}: ships dev-only code "
                            f"({len(leaked)} entries, e.g. {leaked[0]})")


def _read(jar, entry):
    with zipfile.ZipFile(jar) as z:
        return z.read(entry).decode("utf-8", "replace")


def _read_raw(jar, entry):
    with zipfile.ZipFile(jar) as z:
        return z.read(entry)


def _manifest(jar):
    try:
        return _read(jar, "META-INF/MANIFEST.MF")
    except KeyError:
        return ""


def _looks_unexpanded(text):
    return "${version}" in text or "${ version }" in text


def check_fabric_jar(jar, problems):
    names = _names(jar)
    base = os.path.basename(jar)
    _scan_forbidden(jar, base, (FABRIC_FORBIDDEN,) + COMMON_FORBIDDEN, problems)
    if not any(n == "fabric.mod.json" for n in names):
        problems.append(f"{base}: missing fabric.mod.json")
    else:
        meta = _read(jar, "fabric.mod.json")
        if _looks_unexpanded(meta):
            problems.append(f"{base}: fabric.mod.json has an unexpanded ${{version}} placeholder")
        else:
            try:
                v = json.loads(meta).get("version", "")
                if not v or v == "${version}":
                    problems.append(f"{base}: fabric.mod.json version is empty/placeholder ({v!r})")
            except json.JSONDecodeError as e:
                problems.append(f"{base}: fabric.mod.json is not valid JSON ({e.msg})")
    if not any(n.startswith("dev/vox/lss/") and n.endswith(".class") for n in names):
        problems.append(f"{base}: contains no dev/vox/lss classes (empty build?)")
    if not any(n.startswith("LICENSE") for n in names):
        problems.append(f"{base}: LICENSE not bundled")
    ns_match = re.search(r"^Fabric-Mapping-Namespace:\s*(\S+)", _manifest(jar), re.MULTILINE)
    got_ns = ns_match.group(1) if ns_match else "<absent>"
    if got_ns != FABRIC_MAPPING_NAMESPACE:
        problems.append(f"{base}: Fabric-Mapping-Namespace is {got_ns} — this line ships "
                        f"{FABRIC_MAPPING_NAMESPACE}-mapped jars (wrong/missing namespace means "
                        "the loom plugin flavor regressed and the jar cannot load on a real server)")
    # The Fabric jar ships common/ as nested Jar-in-Jar; a Loom include regression would
    # otherwise ship a jar with no shared classes, green on every other check.
    nested = _nested_jars(jar)
    if not any("/common-" in label or label.startswith("META-INF/jars/common")
               for label, _ in nested):
        problems.append(f"{base}: no nested common jar (META-INF/jars/common-*.jar) — "
                        "the Loom include of :common is broken")
    elif not any(any(n.startswith("dev/vox/lss/common/") and n.endswith(".class") for n in ns)
                 for _, ns in nested):
        problems.append(f"{base}: nested common jar carries no dev/vox/lss/common classes")


def check_paper_jar(jar, problems):
    names = _names(jar)
    base = os.path.basename(jar)
    _scan_forbidden(jar, base, (PAPER_FORBIDDEN,) + COMMON_FORBIDDEN, problems)
    if not any(n == "plugin.yml" for n in names):
        problems.append(f"{base}: missing plugin.yml")
    else:
        ymltext = _read(jar, "plugin.yml")
        if _looks_unexpanded(ymltext):
            problems.append(f"{base}: plugin.yml has an unexpanded ${{version}} placeholder")
        # Re-inverted 2026-08-01: Folia shipped its first MC 26.2 build (26.2-1, BETA), so
        # the flag is declared again and the single jar must keep serving Folia. A jar that
        # LOSES the flag silently stops loading on Folia servers, which is the failure this
        # now guards (the previous inversion guarded the opposite risk, when no 26.2 Folia
        # existed to load onto).
        if not re.search(r"^folia-supported:\s*true\s*$", ymltext, re.MULTILINE):
            problems.append(f"{base}: plugin.yml lost folia-supported: true — Folia servers "
                            "will refuse this jar")
    if not any(n.startswith("dev/vox/lss/common/") and n.endswith(".class") for n in names):
        problems.append(f"{base}: shaded jar missing the shared common/ classes")
    if "paperweight-mappings-namespace: mojang" not in _manifest(jar):
        problems.append(f"{base}: manifest lost 'paperweight-mappings-namespace: mojang' "
                        "(server will refuse to load remapped NMS)")


# LOD-store engine natives (plan §3): every release jar must carry the FULL supported
# matrix — a missing native silently degrades that platform's store to the containment
# latch (store-off), which is fail-safe but must be a deliberate choice, not a packaging
# regression. Keep in sync with fabric/build.gradle slimStoreDepJars + paper/build.gradle.
SQLITE_NATIVES = (
    "org/sqlite/native/Linux/x86_64/libsqlitejdbc.so",
    "org/sqlite/native/Linux/aarch64/libsqlitejdbc.so",
    "org/sqlite/native/Linux-Musl/x86_64/libsqlitejdbc.so",
    "org/sqlite/native/Linux-Musl/aarch64/libsqlitejdbc.so",
    "org/sqlite/native/Windows/x86_64/sqlitejdbc.dll",
    "org/sqlite/native/Windows/aarch64/sqlitejdbc.dll",
    "org/sqlite/native/Mac/x86_64/libsqlitejdbc.dylib",
    "org/sqlite/native/Mac/aarch64/libsqlitejdbc.dylib",
)
# zstd natives embed the version in the file name — match by directory prefix.
ZSTD_NATIVE_DIRS = (
    "linux/amd64/", "linux/aarch64/", "win/amd64/", "win/aarch64/",
    "darwin/x86_64/", "darwin/aarch64/",
)


def _check_sqlite_natives(base, where, names, problems):
    for native in SQLITE_NATIVES:
        if native not in names:
            problems.append(f"{base}: {where} is missing sqlite native {native} — "
                            "the store silently degrades to off on that platform")


def _check_zstd_natives(base, where, names, problems):
    for d in ZSTD_NATIVE_DIRS:
        if not any(n.startswith(d) and (n.endswith(".so") or n.endswith(".dll")
                                        or n.endswith(".dylib")) for n in names):
            problems.append(f"{base}: {where} is missing a zstd native under {d} — "
                            "the store silently degrades to off on that platform")


# Native roots the strip lists prune (mirrors the gradle strip lists). Any native FILE
# under these that is NOT in the kept matrix means the strip regressed and the full
# multi-platform payload (+~10 MB) is shipping — presence checks alone stay green then.
STORE_NATIVE_ROOTS = ("org/sqlite/native/", "linux/", "win/", "darwin/", "freebsd/", "aix/")


def _check_native_strip(base, where, names, problems):
    kept_dirs = tuple({n[:n.rfind("/") + 1] for n in SQLITE_NATIVES}) + ZSTD_NATIVE_DIRS
    stray = [n for n in names
             if any(n.startswith(r) for r in STORE_NATIVE_ROOTS)
             and (n.endswith(".so") or n.endswith(".dll") or n.endswith(".dylib")
                  or n.endswith(".jnilib"))
             and not any(n.startswith(d) for d in kept_dirs)]
    if stray:
        problems.append(f"{base}: {where} carries {len(stray)} native(s) outside the "
                        f"supported matrix (e.g. {stray[0]}) — the native strip regressed "
                        "and the full multi-platform payload is shipping")


def check_store_natives_fabric(jar, problems):
    """The Fabric jar nests native-stripped sqlite-jdbc/zstd-jni as Jar-in-Jar; the
    fabric.mod.json 'jars' entries and the matrix inside each nested jar are the ship gate."""
    base = os.path.basename(jar)
    nested = dict(_nested_jars(jar))
    sq = nested.get("META-INF/jars/sqlite-jdbc-slim.jar")
    zs = nested.get("META-INF/jars/zstd-jni-slim.jar")
    if sq is None or zs is None:
        problems.append(f"{base}: missing nested store dep jar(s) "
                        f"(sqlite={'ok' if sq else 'MISSING'}, zstd={'ok' if zs else 'MISSING'})")
        return
    try:
        declared = {j.get("file") for j in json.loads(_read(jar, "fabric.mod.json")).get("jars", [])}
    except (KeyError, json.JSONDecodeError):
        return  # check_fabric_jar already flags the descriptor
    for f in ("META-INF/jars/sqlite-jdbc-slim.jar", "META-INF/jars/zstd-jni-slim.jar"):
        if f not in declared:
            problems.append(f"{base}: fabric.mod.json 'jars' does not declare {f} — "
                            "the loader will never load it")
    # The CONVERSE (4-agent round R4): every nested jar must be declared. The 'jars'
    # array is hand-maintained while slimStoreDepJars emits one jar per RESOLVED
    # artifact — a future transitive dep would ship undeclared (Fabric Loader ignores
    # undeclared nested jars), fail only at class-load time on real servers, and every
    # localRuntime-classpath gate would stay green.
    actual = {n for n in _names(jar)
              if n.startswith("META-INF/jars/") and n.endswith(".jar")}
    undeclared = sorted(actual - declared)
    if undeclared:
        problems.append(f"{base}: nested jar(s) not declared in fabric.mod.json 'jars': "
                        f"{', '.join(undeclared)} — the loader silently ignores them")
    _check_sqlite_natives(base, "nested sqlite-jdbc-slim.jar", set(sq), problems)
    _check_zstd_natives(base, "nested zstd-jni-slim.jar", set(zs), problems)
    _check_native_strip(base, "nested sqlite-jdbc-slim.jar", set(sq), problems)
    _check_native_strip(base, "nested zstd-jni-slim.jar", set(zs), problems)


def check_store_natives_paper(jar, problems):
    """Paper shades the store deps flat (org.sqlite deliberately NOT relocated — relocation
    breaks its native loader); the matrix sits at the top level of the shadow jar."""
    base = os.path.basename(jar)
    names = set(_names(jar))
    if not any(n == "org/sqlite/JDBC.class" for n in names):
        problems.append(f"{base}: org/sqlite classes missing from the shadow jar")
        return
    if any(n.startswith("dev/vox/lss/") and "/sqlite/" in n for n in names):
        problems.append(f"{base}: org.sqlite appears RELOCATED — relocation breaks the "
                        "sqlite native loader; it must stay at org/sqlite")
    _check_sqlite_natives(base, "shadow jar", names, problems)
    _check_zstd_natives(base, "shadow jar", names, problems)
    _check_native_strip(base, "shadow jar", names, problems)


def check_third_party_notices(jar, is_fabric, problems):
    """C6 (review-fixes round): the release jars redistribute two third-party native
    binaries (sqlite-jdbc, zstd-jni); the notices file shipped only by grace of a
    build.gradle resources line — a resources refactor dropped it with every gate
    green. Presence + non-empty at the top level of every release jar, and the Fabric
    nested sqlite-jdbc-slim.jar must retain its in-jar Apache-2.0 text (the slim
    repack must not strip it — the notices file DELEGATES sqlite's license to it)."""
    base = os.path.basename(jar)
    names = set(_names(jar))
    if "THIRD-PARTY-NOTICES" not in names:
        problems.append(f"{base}: THIRD-PARTY-NOTICES missing — the jar redistributes "
                        "sqlite-jdbc/zstd-jni binaries and must carry their notices")
        return
    if len(_read_raw(jar, "THIRD-PARTY-NOTICES")) < 100:
        problems.append(f"{base}: THIRD-PARTY-NOTICES is (near-)empty")
    if is_fabric:
        nested = dict(_nested_jars(jar))
        sq = nested.get("META-INF/jars/sqlite-jdbc-slim.jar")
        if sq is not None and "META-INF/maven/org.xerial/sqlite-jdbc/LICENSE" not in set(sq):
            problems.append(f"{base}: nested sqlite-jdbc-slim.jar lost its in-jar LICENSE "
                            "— the slim task must not strip it (THIRD-PARTY-NOTICES "
                            "delegates sqlite's license text to it)")


def check_vss_fabric_identity(jar, problems):
    """The VSS Fabric jar is a branded byte-copy of the LSS jar. Rebranding may touch ONLY
    name/description/icon/contact — the mod `id` MUST stay `lss` (a forked id breaks
    wire+config interchangeability, the whole point of the dual distribution), and the jar
    MUST actually be rebranded (an un-rebranded LSS copy under the vss name is a mistake)."""
    base = os.path.basename(jar)
    try:
        meta = json.loads(_read(jar, "fabric.mod.json"))
    except (KeyError, json.JSONDecodeError):
        return  # check_fabric_jar already flags a missing/invalid descriptor
    if meta.get("id") != "lss":
        problems.append(f"{base}: vss Fabric jar mod id is {meta.get('id')!r}, must stay "
                        "'lss' — a forked id breaks LSS/VSS wire + config interchangeability")
    if meta.get("name") == "LOD Server Support":
        problems.append(f"{base}: vss Fabric jar is not rebranded "
                        "(name is still 'LOD Server Support')")


def check_vss_paper_identity(jar, problems):
    """The VSS Paper jar is a branded byte-copy of the LSS shadowJar. `name: LodServerSupport`
    IS the plugin identity + config-folder name and MUST stay verbatim — only the top-level
    description is rebranded (Paper plugins carry no display name/icon distinct from `name`)."""
    base = os.path.basename(jar)
    try:
        yml = _read(jar, "plugin.yml")
    except KeyError:
        return  # check_paper_jar already flags a missing plugin.yml
    if not re.search(r"^name:\s*LodServerSupport\s*$", yml, re.MULTILINE):
        problems.append(f"{base}: vss Paper jar plugin name must stay 'LodServerSupport' "
                        "— forking it breaks LSS/VSS interchangeability + the config folder")


# fabric.mod.json fields the VSS rebrand MAY touch; everything else must be byte-equal to the
# LSS jar's descriptor (the build comments in fabric/build.gradle claim this invariant — this
# check enforces it, so a future vssJar edit can't silently fork entrypoints/mixins/depends).
VSS_FABRIC_ALLOWED_DIFF = {"name", "description", "icon", "contact"}


def check_vss_pair_fabric(lss_jar, vss_jar, problems):
    """Field-by-field diff of the two built descriptors: only branding fields may differ,
    and `name` MUST differ (a silent un-rebranded copy is a build regression). Also pins
    that the descriptor's icon path actually exists inside the vss jar."""
    vbase = os.path.basename(vss_jar)
    try:
        lmeta = json.loads(_read(lss_jar, "fabric.mod.json"))
        vmeta = json.loads(_read(vss_jar, "fabric.mod.json"))
    except (KeyError, json.JSONDecodeError):
        return  # per-jar checks already flag a missing/invalid descriptor
    for key in sorted(set(lmeta) | set(vmeta)):
        if key in VSS_FABRIC_ALLOWED_DIFF:
            continue
        if lmeta.get(key) != vmeta.get(key):
            problems.append(f"{vbase}: fabric.mod.json field {key!r} differs from the LSS jar "
                            f"— the VSS rebrand may only touch {sorted(VSS_FABRIC_ALLOWED_DIFF)}")
    if lmeta.get("name") == vmeta.get("name"):
        problems.append(f"{vbase}: fabric.mod.json 'name' equals the LSS jar's — not rebranded")
    icon = vmeta.get("icon")
    if icon and icon not in _names(vss_jar):
        problems.append(f"{vbase}: fabric.mod.json icon {icon!r} is not an entry in the jar")


# plugin.yml lines that are the identity + wire contract: the VSS rebrand must leave every
# one of them byte-identical to the LSS jar (rebranding them would break interchangeability,
# the config-folder name, or — for a client on the other brand — nothing on the wire, but the
# plugin identity a server operator depends on). `main:` also pins the package/class names.
VSS_PAPER_IDENTITY_PREFIXES = ("name:", "main:", "api-version:", "folia-supported:")


def check_vss_pair_paper(lss_jar, vss_jar, problems):
    """The VSS Paper jar rebrands plugin.yml's DISPLAY + LOCAL-command surface only. Line
    count must be unchanged (every rewrite is in-place). The identity/wire lines
    (name/main/api-version/folia) must be byte-identical. And the rebrand must actually have
    happened: the VSS jar carries vsslod / vss.admin / the Voxy description and NONE of the
    LSS tokens; the LSS jar carries the LSS tokens. The command name + permission node are
    LOCAL (never on the wire), so this rebrand does not affect LSS<->VSS compatibility."""
    vbase = os.path.basename(vss_jar)
    try:
        ltext = _read(lss_jar, "plugin.yml")
        vtext = _read(vss_jar, "plugin.yml")
    except KeyError:
        return  # per-jar checks already flag a missing plugin.yml
    llines, vlines = ltext.splitlines(), vtext.splitlines()
    if len(llines) != len(vlines):
        problems.append(f"{vbase}: plugin.yml line count differs from the LSS jar "
                        f"({len(llines)} vs {len(vlines)}) — the rebrand must rewrite lines "
                        "in place, never add or remove them")
        return
    if ltext == vtext:
        problems.append(f"{vbase}: plugin.yml is byte-identical to the LSS jar — the rebrand "
                        "silently no-opped (not rebranded)")
        return
    # Identity/wire lines must be untouched.
    for i, (a, b) in enumerate(zip(llines, vlines)):
        if any(a.lstrip().startswith(p) for p in VSS_PAPER_IDENTITY_PREFIXES) and a != b:
            problems.append(f"{vbase}: plugin.yml identity line {a.strip()!r} was rebranded "
                            "— name/main/api-version/folia are the identity + wire contract "
                            "and must stay verbatim")
    # The two top-level (column-0) display lines must each actually differ — their rewrites
    # are replaceFirst calls that fail SILENT on source-shape drift, and the token checks
    # below don't cover them (the website URL is in neither token list, and "Voxy Server
    # Side" is satisfied by the command description). A no-op here ships a VSS jar linking to
    # / described as the LSS project.
    for prefix in ("description:", "website:"):
        lln = next((ln for ln in llines if ln.startswith(prefix)), None)
        vln = next((ln for ln in vlines if ln.startswith(prefix)), None)
        if lln is not None and vln is not None and lln == vln:
            problems.append(f"{vbase}: plugin.yml top-level {prefix!r} line was not rebranded "
                            "(replaceFirst silently no-opped) — VSS would show the LSS "
                            "description / Modrinth link")
    # The rebrand must have swapped every LSS token for its VSS counterpart.
    for tok in ("lsslod", "lss.admin", "LOD Server Support admin", "Access to LSS admin"):
        if tok not in ltext:
            problems.append(f"{vbase}: LSS plugin.yml is missing expected token {tok!r} "
                            "(source shape changed — the VSS rewrite may silently no-op)")
        if tok in vtext:
            problems.append(f"{vbase}: VSS plugin.yml still contains LSS token {tok!r} "
                            "— the rebrand rewrite no-opped")
    for tok in ("vsslod", "vss.admin", "Voxy Server Side"):
        if tok not in vtext:
            problems.append(f"{vbase}: VSS plugin.yml is missing expected VSS token {tok!r} "
                            "— the rebrand did not fully apply")


# ---------------------------------------------------------------- brand.properties + wire

# Expected runtime-branding values per jar brand. The VSS repackage rewrites lss-brand.properties
# (the ONLY thing Brand.java reads); everything it changes is display + LOCAL command text.
_BRAND_LSS = {"shortName": "LSS", "displayName": "LOD Server Support",
              "clientCommand": "lss", "serverCommand": "lsslod"}
_BRAND_VSS = {"shortName": "VSS", "displayName": "Voxy Server Side",
              "clientCommand": "vss", "serverCommand": "vsslod"}


def _brand_props(jar):
    props = {}
    for line in _read(jar, "lss-brand.properties").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()
    return props


def check_brand_properties(jar, expected, problems):
    """lss-brand.properties (both platforms) must carry exactly this brand's values — a
    mismatch means the repackage's rewrite drifted or no-opped."""
    base = os.path.basename(jar)
    try:
        props = _brand_props(jar)
    except KeyError:
        problems.append(f"{base}: no lss-brand.properties (Brand.java would fall back to LSS defaults)")
        return
    for key, want in expected.items():
        if props.get(key) != want:
            problems.append(f"{base}: lss-brand.properties {key}={props.get(key)!r}, expected {want!r}")


def check_wire_identity_fabric(lss_jar, vss_jar, problems):
    """The strongest LSS<->VSS wire-compat pin: the nested common jar (which holds every
    channel id, the protocol version, and all payload codecs) must be byte-for-byte identical
    between the LSS and VSS Fabric jars. If they match, the two brands are provably wire-equal."""
    vbase = os.path.basename(vss_jar)
    lname = next((n for n in _names(lss_jar) if re.search(r"META-INF/jars/common-.*\.jar$", n)), None)
    vname = next((n for n in _names(vss_jar) if re.search(r"META-INF/jars/common-.*\.jar$", n)), None)
    if lname is None or vname is None:
        problems.append(f"{vbase}: cannot locate the nested common jar in the LSS/VSS pair "
                        "— wire-identity unverifiable")
        return
    lsha = hashlib.sha256(_read_raw(lss_jar, lname)).hexdigest()
    vsha = hashlib.sha256(_read_raw(vss_jar, vname)).hexdigest()
    if lsha != vsha:
        problems.append(f"{vbase}: nested common jar differs from the LSS jar (sha {vsha[:12]} "
                        f"vs {lsha[:12]}) — branding must NEVER touch common (it carries the "
                        "wire: channels, protocol, payloads); LSS<->VSS compatibility is broken")


def _class_digest(jar):
    """sha256 over the sorted (name, bytes) of every dev/vox/lss/**.class entry — the code
    that determines wire behavior. Order-independent, content-exact."""
    h = hashlib.sha256()
    with zipfile.ZipFile(jar) as z:
        for name in sorted(n for n in z.namelist()
                           if n.startswith("dev/vox/lss/") and n.endswith(".class")):
            h.update(name.encode("utf-8"))
            h.update(b"\0")
            h.update(z.read(name))
    return h.hexdigest()


def check_wire_identity_paper(lss_jar, vss_jar, problems):
    """The Paper twin of check_wire_identity_fabric. Paper shades common FLAT (top-level
    dev/vox/lss/common/*.class), so there is no nested jar to hash — instead compare the
    class bytes of the whole dev/vox/lss/** tree between the LSS and VSS Paper jars. The VSS
    repackage rewrites only plugin.yml + lss-brand.properties; every class (common + paper,
    incl. the wire codecs) must be byte-identical, or branding leaked into behavior."""
    vbase = os.path.basename(vss_jar)
    ldig = _class_digest(lss_jar)
    vdig = _class_digest(vss_jar)
    if ldig != vdig:
        problems.append(f"{vbase}: dev/vox/lss/**.class bytes differ from the LSS jar "
                        f"(digest {vdig[:12]} vs {ldig[:12]}) — the VSS Paper repackage must "
                        "rewrite ONLY plugin.yml + lss-brand.properties; a changed class means "
                        "branding touched behavior (wire compat / identity at risk)")


def _vss_counterpart(vss_jar, lss_jars, vss_prefix, lss_prefix):
    """The LSS jar this vss jar was repackaged from: same filename with the prefix swapped
    (the version suffix, CI or local, is shared by construction in the vssJar tasks)."""
    want = os.path.basename(vss_jar).replace(vss_prefix, lss_prefix, 1)
    for j in lss_jars:
        if os.path.basename(j) == want:
            return j
    return None


def check_glob_hygiene(problems, soak_jars):
    """The dev-only soak jar must never be picked up by a release glob; every CI-named release
    jar (all four brand/platform combinations) must be picked up by exactly one release glob (a
    publish that matches nothing fails CI)."""
    for sj in soak_jars:
        base = os.path.basename(sj)
        for glob in RELEASE_GLOBS:
            if fnmatch.fnmatch(base, glob):
                problems.append(f"{base}: dev soak jar MATCHES release glob {glob} — would be published")
    # Round-trip every CI artifact name format against the globs (HD-043). Each of the four
    # shipped prefixes must match one release glob; the soak jar must match none.
    for prefix in ("lod-server-support-fabric", "lod-server-support-paper",
                   "voxy-server-side-fabric", "voxy-server-side-paper"):
        ci_name = f"{prefix}-{CI_NAME_SUFFIX}"
        if not any(fnmatch.fnmatch(ci_name, g) for g in RELEASE_GLOBS):
            problems.append(f"CI name {ci_name} matches no release glob")
    if any(fnmatch.fnmatch(f"{SOAK_JAR_PREFIX}-{CI_NAME_SUFFIX}", g) for g in RELEASE_GLOBS):
        problems.append("CI-named soak jar matches a release glob")


def discover(problems, expected_version=None, root=ROOT):
    fab_libs = os.path.join(root, "fabric", "build", "libs")
    pap_libs = os.path.join(root, "paper", "build", "libs")
    # `voxy-server-side-*` and `lod-server-support-*` are disjoint prefixes, so neither
    # discovery list contaminates the other.
    fab = _jars_in(fab_libs, "lod-server-support-fabric")
    pap = _jars_in(pap_libs, "lod-server-support-paper")
    vfab = _jars_in(fab_libs, "voxy-server-side-fabric")
    vpap = _jars_in(pap_libs, "voxy-server-side-paper")
    soak = _jars_in(pap_libs, SOAK_JAR_PREFIX)
    # All four families must be present — a release ships all four, and a missing family
    # (e.g. the vssJar finalizer silently unwired) must fail the gate, not shrink it.
    for jars, what, hint in ((fab, "lod-server-support-fabric", "run :fabric:build"),
                             (pap, "lod-server-support-paper", "run :paper:shadowJar"),
                             (vfab, "voxy-server-side-fabric", "the fabric vssJar task did not run"),
                             (vpap, "voxy-server-side-paper", "the paper vssJar finalizer did not run")):
        if not jars:
            problems.append(f"no {what} jar found in build/libs — {hint}")
    if expected_version:
        # A release ships all four; each must exist at the tag version — pinned to THIS
        # line's minecraft_version when known: on a multi-line repo the same mod_version
        # legitimately exists per release line, so version pinning alone could select a
        # stale other-line jar (0.7.3+26.2 beside 0.7.3+26.1.2) and green-light shipping it.
        mc = _minecraft_version(root)
        if mc is None:
            # Fail CLOSED: without the line's minecraft_version the mc pin silently reverts
            # to any-suffix matching — the exact multi-line stale-jar hole it exists to close.
            problems.append("cannot read minecraft_version from gradle.properties — refusing "
                            "to version-match release jars without the line pin")
        fab = _require_version(fab, "lod-server-support-fabric", expected_version, problems, mc=mc)
        pap = _require_version(pap, "lod-server-support-paper", expected_version, problems, mc=mc)
        vfab = _require_version(vfab, "voxy-server-side-fabric", expected_version, problems, mc=mc)
        vpap = _require_version(vpap, "voxy-server-side-paper", expected_version, problems, mc=mc)
        # With --version the ambiguity guard is off, but release.yml's upload globs are
        # greedy: any OTHER versioned jar sitting beside the selected one would be attached
        # to the release too. Flag them (the suffixless local dev names stay tolerated).
        for sel, allj, prefix in ((fab, _jars_in(fab_libs, "lod-server-support-fabric"), "lod-server-support-fabric"),
                                  (pap, _jars_in(pap_libs, "lod-server-support-paper"), "lod-server-support-paper"),
                                  (vfab, _jars_in(fab_libs, "voxy-server-side-fabric"), "voxy-server-side-fabric"),
                                  (vpap, _jars_in(pap_libs, "voxy-server-side-paper"), "voxy-server-side-paper")):
            stale = [os.path.basename(j) for j in allj
                     if j not in sel and os.path.basename(j) != f"{prefix}.jar"]
            if sel and stale:
                problems.append(f"{prefix}: stale versioned jar(s) beside the release build: "
                                f"{stale} — delete them (release.yml globs would attach them)")
    else:
        _flag_ambiguous(fab, "lod-server-support-fabric", problems)
        _flag_ambiguous(pap, "lod-server-support-paper", problems)
        _flag_ambiguous(vfab, "voxy-server-side-fabric", problems)
        _flag_ambiguous(vpap, "voxy-server-side-paper", problems)
    for jar in fab:
        check_fabric_jar(jar, problems)
        check_store_natives_fabric(jar, problems)
        check_third_party_notices(jar, True, problems)
    for jar in pap:
        check_paper_jar(jar, problems)
        check_store_natives_paper(jar, problems)
        check_third_party_notices(jar, False, problems)
    # The vss jars ship to real users → identical safety gate, plus the identity guardrail
    # that pins them as branded byte-copies (mod id `lss` / plugin name LodServerSupport),
    # plus a descriptor pair-diff against the LSS jar they were repackaged from (only the
    # branding fields may differ — and must). A vss jar without its LSS counterpart cannot
    # be pair-verified and is a failure: the repackage task guarantees the source jar exists.
    # LSS jars must carry the LSS branding values (a rewrite regression could flip them).
    for jar in fab + pap:
        check_brand_properties(jar, _BRAND_LSS, problems)
    for jar in vfab:
        check_fabric_jar(jar, problems)
        check_store_natives_fabric(jar, problems)
        check_third_party_notices(jar, True, problems)
        check_vss_fabric_identity(jar, problems)
        check_brand_properties(jar, _BRAND_VSS, problems)
        src = _vss_counterpart(jar, fab, "voxy-server-side-fabric", "lod-server-support-fabric")
        if src is None:
            problems.append(f"{os.path.basename(jar)}: no matching lod-server-support-fabric "
                            "jar to pair-verify against (stale vss jar?)")
        else:
            check_vss_pair_fabric(src, jar, problems)
            check_wire_identity_fabric(src, jar, problems)
    for jar in vpap:
        check_paper_jar(jar, problems)
        check_store_natives_paper(jar, problems)
        check_third_party_notices(jar, False, problems)
        check_vss_paper_identity(jar, problems)
        check_brand_properties(jar, _BRAND_VSS, problems)
        src = _vss_counterpart(jar, pap, "voxy-server-side-paper", "lod-server-support-paper")
        if src is None:
            problems.append(f"{os.path.basename(jar)}: no matching lod-server-support-paper "
                            "jar to pair-verify against (stale vss jar?)")
        else:
            check_vss_pair_paper(src, jar, problems)
            check_wire_identity_paper(src, jar, problems)
    check_glob_hygiene(problems, soak)
    return fab, pap, vfab, vpap, soak


def _jars_in(d, prefix):
    if not os.path.isdir(d):
        return []
    return [os.path.join(d, n) for n in sorted(os.listdir(d))
            if n.startswith(prefix) and n.endswith(".jar")]


def _minecraft_version(root=ROOT):
    """The line's minecraft_version from gradle.properties, or None if unreadable. Used to
    pin --version matching to the FULL CI jar name: support branches make the same
    mod_version exist on several MC lines at once, so `-{version}+` alone is ambiguous."""
    try:
        with open(os.path.join(root, "gradle.properties"), encoding="utf-8") as fh:
            for line in fh:
                if line.strip().startswith("minecraft_version="):
                    return line.split("=", 1)[1].strip()
    except OSError:
        pass
    return None


def _require_version(jars, prefix, version, problems, mc=None):
    """Restrict checking to the exact release jar for `version`; a missing jar is a failure
    — otherwise a stale jar from an earlier build gets validated in its place and the
    pre-flight green-lights code that was never built. `prefix` is the full jar base name
    (e.g. `lod-server-support-fabric` or `voxy-server-side-paper`). When `mc` is known the
    match requires the full `-{version}+{mc}.jar` CI name, so a stale jar of the SAME
    version from ANOTHER release line (0.7.3+26.2 vs 0.7.3+26.1.2) can never be selected;
    mc=None keeps the legacy any-suffix behavior (single-line repos, selftest fixtures)."""
    want_exact = f"{prefix}-{version}.jar"
    if mc:
        # Exact CI name only: the suffixless dev name never carries a version, and the bare
        # escape would re-admit a same-version jar of unknown provenance.
        want_versioned = f"{prefix}-{version}+{mc}.jar"
        matched = [j for j in jars if os.path.basename(j) == want_versioned]
    else:
        want_prefix = f"{prefix}-{version}+"
        matched = [j for j in jars
                   if os.path.basename(j).startswith(want_prefix)
                   or os.path.basename(j) == want_exact]
    if not matched:
        problems.append(f"{prefix}: no jar for version {version}"
                        + (f" on MC {mc}" if mc else "") + " in build/libs "
                        f"(found: {[os.path.basename(j) for j in jars] or 'none'}) — "
                        f"build with CI=true and -Pmod_version={version} first")
    return matched


def _flag_ambiguous(jars, platform, problems):
    """Without --version, more than one candidate jar means stale artifacts from earlier
    builds would be validated alongside (or instead of) the fresh one — refuse to guess."""
    if len(jars) > 1:
        problems.append(f"{platform}: {len(jars)} release jars in build/libs "
                        f"({[os.path.basename(j) for j in jars]}) — stale artifacts from "
                        f"earlier builds; run './gradlew clean' or pass --version")


# ----------------------------------------------------------------------------- selftest

def _make_jar(path, entries, manifest=None):
    with zipfile.ZipFile(path, "w") as z:
        if manifest is not None:
            z.writestr("META-INF/MANIFEST.MF", manifest)
        for name, data in entries.items():
            z.writestr(name, data)


def _selftest():
    n = 0

    def check(cond, msg):
        nonlocal n
        assert cond, "selftest FAIL: " + msg
        n += 1

    def _nested_common(version="0.4.0"):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z:
            z.writestr("dev/vox/lss/common/PositionUtil.class", "x")
        return buf.getvalue()

    def _nested_sqlite(with_license=True):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z:
            z.writestr("org/sqlite/JDBC.class", "x")
            if with_license:  # C6: the notices check pins this entry's survival
                z.writestr("META-INF/maven/org.xerial/sqlite-jdbc/LICENSE", "Apache-2.0 ...")
            for native in SQLITE_NATIVES:
                z.writestr(native, "elf")
        return buf.getvalue()

    def _nested_zstd(missing_dir=None):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z:
            z.writestr("com/github/luben/zstd/Zstd.class", "x")
            for d in ZSTD_NATIVE_DIRS:
                if d == missing_dir:
                    continue
                ext = ".dll" if d.startswith("win") else (".dylib" if d.startswith("darwin") else ".so")
                z.writestr(d + "libzstd-jni-1.5.7-3" + ext, "elf")
        return buf.getvalue()

    # Entries every schema-complete synthetic fabric release jar carries for the
    # store-native matrix check (the real jars nest these via slimStoreDepJars).
    STORE_FABRIC_ENTRIES = {
        "META-INF/jars/sqlite-jdbc-slim.jar": _nested_sqlite(),
        "META-INF/jars/zstd-jni-slim.jar": _nested_zstd(),
    }
    # Mirrors the REAL fabric.mod.json: all three nested jars declared (the converse
    # check rejects any undeclared nested jar, so the fixture must declare common too).
    STORE_FABRIC_JARS_FIELD = [{"file": "META-INF/jars/sqlite-jdbc-slim.jar"},
                               {"file": "META-INF/jars/zstd-jni-slim.jar"},
                               {"file": "META-INF/jars/common-0.7.0.jar"}]

    def _store_paper_entries():
        out = {"org/sqlite/JDBC.class": "x", "com/github/luben/zstd/Zstd.class": "x"}
        for native in SQLITE_NATIVES:
            out[native] = "elf"
        for d in ZSTD_NATIVE_DIRS:
            ext = ".dll" if d.startswith("win") else (".dylib" if d.startswith("darwin") else ".so")
            out[d + "libzstd-jni-1.5.7-3" + ext] = "elf"
        return out

    with tempfile.TemporaryDirectory() as td:
        fab_manifest = "Manifest-Version: 1.0\nFabric-Mapping-Namespace: official\n"
        good_fab = os.path.join(td, "lod-server-support-fabric.jar")
        _make_jar(good_fab, {
            "fabric.mod.json": json.dumps({"version": "0.4.0"}),
            "dev/vox/lss/LSSMod.class": "x",
            "dev/vox/lss/common/PositionUtil.class": "x",
            "META-INF/jars/common-0.4.0.jar": _nested_common(),
            "LICENSE_lod-server-support-fabric": "MIT",
        }, manifest=fab_manifest)
        p = []
        check_fabric_jar(good_fab, p)
        check(p == [], f"clean fabric jar flagged: {p}")

        # the loom-flavor regression: right name, wrong mapping namespace must be caught
        wrongns_fab = os.path.join(td, "wrongns-fabric.jar")
        _make_jar(wrongns_fab, {
            "fabric.mod.json": json.dumps({"version": "0.4.0"}),
            "dev/vox/lss/LSSMod.class": "x",
            "META-INF/jars/common-0.4.0.jar": _nested_common(),
            "LICENSE_lod-server-support-fabric": "MIT",
        }, manifest="Manifest-Version: 1.0\nFabric-Mapping-Namespace: named\n")
        p = []
        check_fabric_jar(wrongns_fab, p)
        check(any("Fabric-Mapping-Namespace" in m for m in p),
              f"wrong fabric mapping namespace not caught: {p}")

        # a fabric jar with NO nested common jar means the Loom include of :common broke
        no_common_fab = os.path.join(td, "no-common-fabric.jar")
        _make_jar(no_common_fab, {
            "fabric.mod.json": json.dumps({"version": "0.4.0"}),
            "dev/vox/lss/LSSMod.class": "x",
            "LICENSE_lod-server-support-fabric": "MIT",
        })
        p = []
        check_fabric_jar(no_common_fab, p)
        check(any("no nested common jar" in m for m in p),
              f"missing nested common jar not caught: {p}")

        bad_fab = os.path.join(td, "bad-fabric.jar")
        _make_jar(bad_fab, {
            "fabric.mod.json": json.dumps({"version": "${version}"}),
            "dev/vox/lss/benchmark/SoakScenarioDriver.class": "x",  # leaked dev code
            "dev/vox/lss/LSSMod.class": "x",
        })
        p = []
        check_fabric_jar(bad_fab, p)
        check(any("benchmark" in m for m in p), "leaked benchmark package not caught")
        check(any("placeholder" in m for m in p), "unexpanded version not caught")
        check(any("LICENSE" in m for m in p), "missing LICENSE not caught")

        # The real Fabric jar ships common/ as nested Jar-in-Jar (META-INF/jars/*.jar):
        # a clean nested common jar passes, dev code hidden inside it must be caught.
        nested_clean = io.BytesIO()
        with zipfile.ZipFile(nested_clean, "w") as z:
            z.writestr("dev/vox/lss/common/PositionUtil.class", "x")
        nested_fab = os.path.join(td, "lod-server-support-fabric-nested.jar")
        _make_jar(nested_fab, {
            "fabric.mod.json": json.dumps({"version": "0.4.0"}),
            "dev/vox/lss/LSSMod.class": "x",
            "META-INF/jars/common-0.4.0.jar": nested_clean.getvalue(),
            "LICENSE_lod-server-support-fabric": "MIT",
        }, manifest=fab_manifest)
        p = []
        check_fabric_jar(nested_fab, p)
        check(p == [], f"clean nested common jar flagged: {p}")

        nested_dirty = io.BytesIO()
        with zipfile.ZipFile(nested_dirty, "w") as z:
            z.writestr("dev/vox/lss/common/PositionUtil.class", "x")
            z.writestr("dev/vox/lss/common/soak/SharedSoakDriver.class", "x")  # hidden leak
        leaky_fab = os.path.join(td, "leaky-nested-fabric.jar")
        _make_jar(leaky_fab, {
            "fabric.mod.json": json.dumps({"version": "0.4.0"}),
            "dev/vox/lss/LSSMod.class": "x",
            "META-INF/jars/common-0.4.0.jar": nested_dirty.getvalue(),
            "LICENSE_lod-server-support-fabric": "MIT",
        })
        p = []
        check_fabric_jar(leaky_fab, p)
        check(any("common/soak" in m and "META-INF/jars" in m for m in p),
              f"dev code inside a nested jar not caught: {p}")

        good_pap = os.path.join(td, "lod-server-support-paper.jar")
        _make_jar(good_pap, {
            "plugin.yml": "name: LodServerSupport\nversion: 0.4.0\nfolia-supported: true\n",
            "dev/vox/lss/paper/LSSPaperPlugin.class": "x",
            "dev/vox/lss/common/PositionUtil.class": "x",
        }, manifest="Manifest-Version: 1.0\npaperweight-mappings-namespace: mojang\n")
        p = []
        check_paper_jar(good_pap, p)
        check(p == [], f"clean paper jar flagged: {p}")

        # a paper jar LOSING folia-supported must be caught: Folia ships a 26.2 build since
        # 2026-07-28, so a jar without the flag silently stops loading on Folia servers
        # (re-inverted 2026-08-01 — the previous direction guarded the opposite risk, back
        # when no Folia 26.2 existed to load onto)
        foliaflag_pap = os.path.join(td, "foliaflag-paper.jar")
        _make_jar(foliaflag_pap, {
            "plugin.yml": "name: LodServerSupport\nversion: 0.4.0\n",
            "dev/vox/lss/paper/LSSPaperPlugin.class": "x",
            "dev/vox/lss/common/PositionUtil.class": "x",
        }, manifest="Manifest-Version: 1.0\npaperweight-mappings-namespace: mojang\n")
        p = []
        check_paper_jar(foliaflag_pap, p)
        check(any("folia-supported" in m for m in p), "missing folia-supported flag not caught")

        bad_pap = os.path.join(td, "bad-paper.jar")
        _make_jar(bad_pap, {
            "plugin.yml": "name: X\nversion: ${version}\n",
            "dev/vox/lss/paper/soak/PaperSoakScenarioDriver.class": "x",  # leaked soak code
            "dev/vox/lss/paper/LSSPaperPlugin.class": "x",
            "dev/vox/lss/common/PositionUtil.class": "x",
        }, manifest="Manifest-Version: 1.0\n")  # missing mappings-namespace
        p = []
        check_paper_jar(bad_pap, p)
        check(any("soak" in m for m in p), "leaked soak package not caught")
        check(any("placeholder" in m for m in p), "paper unexpanded version not caught")
        check(any("mappings-namespace" in m for m in p), "lost mappings namespace not caught")

        # Paper shades common/ at the top level: a dev-only common namespace must be caught.
        common_leak_pap = os.path.join(td, "common-leak-paper.jar")
        _make_jar(common_leak_pap, {
            "plugin.yml": "name: LodServerSupport\nversion: 0.4.0\n",
            "dev/vox/lss/paper/LSSPaperPlugin.class": "x",
            "dev/vox/lss/common/PositionUtil.class": "x",
            "dev/vox/lss/common/benchmark/SharedBenchHook.class": "x",  # leaked dev code
        }, manifest="Manifest-Version: 1.0\npaperweight-mappings-namespace: mojang\n")
        p = []
        check_paper_jar(common_leak_pap, p)
        check(any("common/benchmark" in m for m in p),
              f"dev code in a shaded common namespace not caught: {p}")

        # Discovery ambiguity: stale jars alongside the fresh one must fail without
        # --version, and --version must select exactly the requested release jar.
        p = []
        _flag_ambiguous(["a/lod-server-support-fabric.jar",
                         "a/lod-server-support-fabric-0.4.0+26.1.2.jar"], "fabric", p)
        check(any("stale artifacts" in m for m in p), "ambiguous multi-jar dir not flagged")
        p = []
        _flag_ambiguous(["a/lod-server-support-fabric-0.5.0+26.1.2.jar"], "fabric", p)
        check(p == [], f"single jar wrongly flagged as ambiguous: {p}")
        p = []
        got = _require_version(["a/lod-server-support-fabric-0.4.0+26.1.2.jar",
                                "a/lod-server-support-fabric-0.5.0+26.1.2.jar"],
                               "lod-server-support-fabric", "0.5.0", p)
        check(p == [] and [os.path.basename(j) for j in got]
              == ["lod-server-support-fabric-0.5.0+26.1.2.jar"],
              f"--version did not select exactly the requested jar: {got} {p}")
        p = []
        got = _require_version(["a/lod-server-support-fabric-0.4.0+26.1.2.jar"],
                               "lod-server-support-fabric", "0.5.0", p)
        check(got == [] and any("no jar for version 0.5.0" in m for m in p),
              f"missing requested version not caught: {got} {p}")
        # the vss prefix is version-pinned the same way, disjoint from the LSS prefix
        p = []
        got = _require_version(["a/voxy-server-side-fabric-0.7.0+26.2.jar"],
                               "voxy-server-side-fabric", "0.7.0", p)
        check(p == [] and [os.path.basename(j) for j in got]
              == ["voxy-server-side-fabric-0.7.0+26.2.jar"],
              f"vss --version did not select the vss jar: {got} {p}")

        # mc-pinned matching (support lines): the SAME mod_version exists on multiple
        # release lines, so a stale other-line jar of the right version must be rejected
        # when gradle.properties' minecraft_version is known.
        p = []
        got = _require_version(["a/lod-server-support-fabric-0.7.3+26.2.jar",
                                "a/lod-server-support-fabric-0.7.3+26.1.2.jar"],
                               "lod-server-support-fabric", "0.7.3", p, mc="26.1.2")
        check(p == [] and [os.path.basename(j) for j in got]
              == ["lod-server-support-fabric-0.7.3+26.1.2.jar"],
              f"mc-pinned selection did not pick exactly this line's jar: {got} {p}")
        p = []
        got = _require_version(["a/lod-server-support-fabric-0.7.3+26.2.jar"],
                               "lod-server-support-fabric", "0.7.3", p, mc="26.1.2")
        check(got == [] and any("no jar for version 0.7.3 on MC 26.1.2" in m for m in p),
              f"wrong-line stale jar passed the mc-pinned version gate: {got} {p}")
        # mc known: the bare suffixless escape must be gone too (exact CI name only)
        p = []
        got = _require_version(["a/lod-server-support-fabric-0.7.3.jar"],
                               "lod-server-support-fabric", "0.7.3", p, mc="26.1.2")
        check(got == [], "suffixless same-version jar must not satisfy the mc-pinned gate")

        # ---- Voxy Server Side branded jars: full LSS gate + identity guardrail ----
        # A clean vss Fabric jar: rebranded name, but mod id STILL `lss` → passes both gates.
        good_vfab = os.path.join(td, "voxy-server-side-fabric.jar")
        _make_jar(good_vfab, {
            "fabric.mod.json": json.dumps({"id": "lss", "name": "Voxy Server Side",
                                           "version": "0.7.0"}),
            "dev/vox/lss/LSSMod.class": "x",
            "dev/vox/lss/common/PositionUtil.class": "x",
            "META-INF/jars/common-0.7.0.jar": _nested_common("0.7.0"),
            "assets/lss/icon-vss.png": "PNG",
            "LICENSE_lod-server-support-fabric": "MIT",
        }, manifest=fab_manifest)
        p = []
        check_fabric_jar(good_vfab, p)
        check_vss_fabric_identity(good_vfab, p)
        check(p == [], f"clean vss fabric jar flagged: {p}")

        # A vss Fabric jar whose id was forked away from `lss` MUST fail — that silently
        # breaks LSS/VSS wire + config interchangeability.
        forked_vfab = os.path.join(td, "voxy-server-side-fabric-forked.jar")
        _make_jar(forked_vfab, {
            "fabric.mod.json": json.dumps({"id": "vss", "name": "Voxy Server Side",
                                           "version": "0.7.0"}),
            "dev/vox/lss/LSSMod.class": "x",
            "LICENSE_lod-server-support-fabric": "MIT",
        })
        p = []
        check_vss_fabric_identity(forked_vfab, p)
        check(any("must stay 'lss'" in m for m in p), f"forked vss id not caught: {p}")

        # A vss Fabric jar that was never actually rebranded MUST fail.
        unbranded_vfab = os.path.join(td, "voxy-server-side-fabric-unbranded.jar")
        _make_jar(unbranded_vfab, {
            "fabric.mod.json": json.dumps({"id": "lss", "name": "LOD Server Support",
                                           "version": "0.7.0"}),
            "dev/vox/lss/LSSMod.class": "x",
            "LICENSE_lod-server-support-fabric": "MIT",
        })
        p = []
        check_vss_fabric_identity(unbranded_vfab, p)
        check(any("not rebranded" in m for m in p), f"un-rebranded vss jar not caught: {p}")

        # A vss Fabric jar that leaked dev code MUST fail the shared gate too.
        leaky_vfab = os.path.join(td, "voxy-server-side-fabric-leaky.jar")
        _make_jar(leaky_vfab, {
            "fabric.mod.json": json.dumps({"id": "lss", "name": "Voxy Server Side",
                                           "version": "0.7.0"}),
            "dev/vox/lss/benchmark/BenchmarkHook.class": "x",  # leaked dev code
            "dev/vox/lss/LSSMod.class": "x",
            "LICENSE_lod-server-support-fabric": "MIT",
        })
        p = []
        check_fabric_jar(leaky_vfab, p)
        check(any("benchmark" in m for m in p), "vss jar dev-code leak not caught by shared gate")

        # A clean vss Paper jar: name STILL LodServerSupport → passes both gates.
        good_vpap = os.path.join(td, "voxy-server-side-paper.jar")
        _make_jar(good_vpap, {
            "plugin.yml": ("name: LodServerSupport\nversion: 0.7.0\n"
                           "folia-supported: true\n"
                           "description: Server-side backend for Voxy...\n"),
            "dev/vox/lss/paper/LSSPaperPlugin.class": "x",
            "dev/vox/lss/common/PositionUtil.class": "x",
        }, manifest="Manifest-Version: 1.0\npaperweight-mappings-namespace: mojang\n")
        p = []
        check_paper_jar(good_vpap, p)
        check_vss_paper_identity(good_vpap, p)
        check(p == [], f"clean vss paper jar flagged: {p}")

        # A vss Paper jar whose plugin name was forked MUST fail — that IS the config-folder
        # + plugin identity, and forking it breaks interchangeability.
        forked_vpap = os.path.join(td, "voxy-server-side-paper-forked.jar")
        _make_jar(forked_vpap, {
            "plugin.yml": "name: VoxyServerSide\nversion: 0.7.0\n",
            "dev/vox/lss/paper/LSSPaperPlugin.class": "x",
            "dev/vox/lss/common/PositionUtil.class": "x",
        }, manifest="Manifest-Version: 1.0\npaperweight-mappings-namespace: mojang\n")
        p = []
        check_vss_paper_identity(forked_vpap, p)
        check(any("must stay 'LodServerSupport'" in m for m in p),
              f"forked vss plugin name not caught: {p}")

        # ---- VSS≡LSS pair checks: only branding fields may differ, and must ----
        pair_lss_fab = os.path.join(td, "pair-lss-fabric.jar")
        _make_jar(pair_lss_fab, {
            "fabric.mod.json": json.dumps({
                "id": "lss", "name": "LOD Server Support", "description": "LSS.",
                "version": "0.7.0", "entrypoints": {"main": ["dev.vox.lss.LSSMod"]},
                "mixins": ["lss.mixins.json"], "icon": "assets/lss/icon.png"}),
            "assets/lss/icon.png": "PNG",
        })
        pair_ok_vfab = os.path.join(td, "pair-ok-vss-fabric.jar")
        _make_jar(pair_ok_vfab, {
            "fabric.mod.json": json.dumps({
                "id": "lss", "name": "Voxy Server Side", "description": "VSS.",
                "version": "0.7.0", "entrypoints": {"main": ["dev.vox.lss.LSSMod"]},
                "mixins": ["lss.mixins.json"], "icon": "assets/lss/icon-vss.png"}),
            "assets/lss/icon.png": "PNG",
            "assets/lss/icon-vss.png": "PNG2",
        })
        p = []
        check_vss_pair_fabric(pair_lss_fab, pair_ok_vfab, p)
        check(p == [], f"clean fabric pair flagged: {p}")

        # a vss descriptor whose NON-branding field drifted (entrypoints fork) MUST fail
        pair_forked_vfab = os.path.join(td, "pair-forked-vss-fabric.jar")
        _make_jar(pair_forked_vfab, {
            "fabric.mod.json": json.dumps({
                "id": "lss", "name": "Voxy Server Side", "description": "VSS.",
                "version": "0.7.0", "entrypoints": {"main": ["dev.vox.lss.OtherMod"]},
                "mixins": ["lss.mixins.json"], "icon": "assets/lss/icon-vss.png"}),
            "assets/lss/icon-vss.png": "PNG2",
        })
        p = []
        check_vss_pair_fabric(pair_lss_fab, pair_forked_vfab, p)
        check(any("'entrypoints' differs" in m for m in p),
              f"forked entrypoints not caught by pair check: {p}")

        # an un-rebranded pair (name equal) MUST fail
        pair_same_vfab = os.path.join(td, "pair-same-vss-fabric.jar")
        _make_jar(pair_same_vfab, {
            "fabric.mod.json": json.dumps({
                "id": "lss", "name": "LOD Server Support", "description": "LSS.",
                "version": "0.7.0", "entrypoints": {"main": ["dev.vox.lss.LSSMod"]},
                "mixins": ["lss.mixins.json"], "icon": "assets/lss/icon.png"}),
            "assets/lss/icon.png": "PNG",
        })
        p = []
        check_vss_pair_fabric(pair_lss_fab, pair_same_vfab, p)
        check(any("not rebranded" in m for m in p), f"pair name-equal not caught: {p}")

        # a descriptor icon that points at a missing jar entry MUST fail
        pair_noicon_vfab = os.path.join(td, "pair-noicon-vss-fabric.jar")
        _make_jar(pair_noicon_vfab, {
            "fabric.mod.json": json.dumps({
                "id": "lss", "name": "Voxy Server Side", "description": "VSS.",
                "version": "0.7.0", "entrypoints": {"main": ["dev.vox.lss.LSSMod"]},
                "mixins": ["lss.mixins.json"], "icon": "assets/lss/icon-vss.png"}),
            "assets/lss/icon.png": "PNG",
        })
        p = []
        check_vss_pair_fabric(pair_lss_fab, pair_noicon_vfab, p)
        check(any("not an entry in the jar" in m for m in p),
              f"missing icon entry not caught: {p}")

        LSS_PLUGIN_YML = ("name: LodServerSupport\nversion: '0.7.0'\n"
                          "main: dev.vox.lss.paper.LSSPaperPlugin\n"
                          "api-version: '26.2'\n"
                          "description: LSS plugin.\n"
                          "website: https://modrinth.com/plugin/lod-server-support\n"
                          "commands:\n  lsslod:\n"
                          "    description: LOD Server Support admin commands\n"
                          "    usage: /lsslod <stats|diag>\n"
                          "    permission: lss.admin\n"
                          "permissions:\n  lss.admin:\n"
                          "    description: Access to LSS admin commands\n"
                          "    default: op\n")
        # Mirror vssJar's exact rewrites.
        VSS_PLUGIN_YML = (LSS_PLUGIN_YML
            .replace("description: LSS plugin.", "description: VSS plugin.", 1)
            .replace("website: https://modrinth.com/plugin/lod-server-support",
                     "website: https://modrinth.com/plugin/voxy-server-side", 1)
            .replace("lsslod", "vsslod")
            .replace("LOD Server Support admin commands", "Voxy Server Side admin commands")
            .replace("Access to LSS admin commands", "Access to VSS admin commands")
            .replace("lss.admin", "vss.admin"))
        pair_lss_pap = os.path.join(td, "pair-lss-paper.jar")
        _make_jar(pair_lss_pap, {"plugin.yml": LSS_PLUGIN_YML})
        pair_ok_vpap = os.path.join(td, "pair-ok-vss-paper.jar")
        _make_jar(pair_ok_vpap, {"plugin.yml": VSS_PLUGIN_YML})
        p = []
        check_vss_pair_paper(pair_lss_pap, pair_ok_vpap, p)
        check(p == [], f"clean paper pair flagged: {p}")

        # a byte-identical plugin.yml means the rebrand rewrites silently no-opped
        pair_same_vpap = os.path.join(td, "pair-same-vss-paper.jar")
        _make_jar(pair_same_vpap, {"plugin.yml": LSS_PLUGIN_YML})
        p = []
        check_vss_pair_paper(pair_lss_pap, pair_same_vpap, p)
        check(any("silently no-opped" in m for m in p),
              f"paper no-op rewrite not caught: {p}")

        # an incomplete rebrand (command key left as lsslod) MUST fail both directions
        pair_partial_vpap = os.path.join(td, "pair-partial-vss-paper.jar")
        _make_jar(pair_partial_vpap, {"plugin.yml": VSS_PLUGIN_YML.replace("vsslod", "lsslod")})
        p = []
        check_vss_pair_paper(pair_lss_pap, pair_partial_vpap, p)
        check(any("still contains LSS token 'lsslod'" in m for m in p)
              and any("missing expected VSS token 'vsslod'" in m for m in p),
              f"paper partial rebrand not caught: {p}")

        # rebranding an IDENTITY line (plugin name / config-folder name) MUST fail
        pair_idname_vpap = os.path.join(td, "pair-idname-vss-paper.jar")
        _make_jar(pair_idname_vpap, {"plugin.yml": VSS_PLUGIN_YML.replace(
            "name: LodServerSupport", "name: VoxyServerSide", 1)})
        p = []
        check_vss_pair_paper(pair_lss_pap, pair_idname_vpap, p)
        check(any("identity line" in m for m in p),
              f"paper identity-line rebrand not caught: {p}")

        # the top-level website/description rewrites failing SILENT (command surface rebranded,
        # display lines left as LSS) MUST fail — the VSS jar would link to the LSS project
        pair_noweb_vpap = os.path.join(td, "pair-noweb-vss-paper.jar")
        _make_jar(pair_noweb_vpap, {"plugin.yml": VSS_PLUGIN_YML.replace(
            "website: https://modrinth.com/plugin/voxy-server-side",
            "website: https://modrinth.com/plugin/lod-server-support", 1)})
        p = []
        check_vss_pair_paper(pair_lss_pap, pair_noweb_vpap, p)
        check(any("'website:' line was not rebranded" in m for m in p),
              f"paper website no-op not caught: {p}")
        pair_nodesc_vpap = os.path.join(td, "pair-nodesc-vss-paper.jar")
        _make_jar(pair_nodesc_vpap, {"plugin.yml": VSS_PLUGIN_YML.replace(
            "description: VSS plugin.", "description: LSS plugin.", 1)})
        p = []
        check_vss_pair_paper(pair_lss_pap, pair_nodesc_vpap, p)
        check(any("'description:' line was not rebranded" in m for m in p),
              f"paper description no-op not caught: {p}")

        # a line-count change (a rewrite that added/removed a line) MUST fail (F4 branch)
        pair_linecount_vpap = os.path.join(td, "pair-linecount-vss-paper.jar")
        _make_jar(pair_linecount_vpap, {"plugin.yml": VSS_PLUGIN_YML + "extra: line\n"})
        p = []
        check_vss_pair_paper(pair_lss_pap, pair_linecount_vpap, p)
        check(any("line count differs" in m for m in p),
              f"paper line-count change not caught: {p}")

        # ---- brand.properties (both platforms) ----
        BRAND_LSS_TXT = ("shortName=LSS\ndisplayName=LOD Server Support\n"
                         "clientCommand=lss\nserverCommand=lsslod\n")
        BRAND_VSS_TXT = ("shortName=VSS\ndisplayName=Voxy Server Side\n"
                         "clientCommand=vss\nserverCommand=vsslod\n")
        brand_lss = os.path.join(td, "brand-lss.jar")
        _make_jar(brand_lss, {"lss-brand.properties": BRAND_LSS_TXT})
        brand_vss = os.path.join(td, "brand-vss.jar")
        _make_jar(brand_vss, {"lss-brand.properties": BRAND_VSS_TXT})
        p = []
        check_brand_properties(brand_lss, _BRAND_LSS, p)
        check_brand_properties(brand_vss, _BRAND_VSS, p)
        check(p == [], f"clean brand.properties flagged: {p}")
        # a VSS jar that still carries LSS branding (rewrite no-opped) MUST fail
        p = []
        check_brand_properties(brand_lss, _BRAND_VSS, p)
        check(any("lss-brand.properties" in m for m in p), "VSS brand no-op not caught")
        # a jar with NO brand.properties MUST fail (Brand would fall back to LSS defaults)
        p = []
        check_brand_properties(pair_lss_pap, _BRAND_LSS, p)
        check(any("no lss-brand.properties" in m for m in p), "missing brand.properties not caught")

        # ---- wire identity: nested common jar must be byte-identical across the pair ----
        nested_common = io.BytesIO()
        with zipfile.ZipFile(nested_common, "w") as z:
            z.writestr("dev/vox/lss/common/LSSConstants.class", "lss:handshake_c2s")
        wire_lss_fab = os.path.join(td, "wire-lss-fabric.jar")
        _make_jar(wire_lss_fab, {"META-INF/jars/common-0.7.0.jar": nested_common.getvalue()})
        wire_vss_ok = os.path.join(td, "wire-vss-ok-fabric.jar")
        _make_jar(wire_vss_ok, {"META-INF/jars/common-0.7.0.jar": nested_common.getvalue()})
        p = []
        check_wire_identity_fabric(wire_lss_fab, wire_vss_ok, p)
        check(p == [], f"identical nested common jar flagged: {p}")
        # a common jar that DIFFERS (branding leaked into the wire) MUST fail
        nested_forked = io.BytesIO()
        with zipfile.ZipFile(nested_forked, "w") as z:
            z.writestr("dev/vox/lss/common/LSSConstants.class", "vss:handshake_c2s")
        wire_vss_bad = os.path.join(td, "wire-vss-bad-fabric.jar")
        _make_jar(wire_vss_bad, {"META-INF/jars/common-0.7.0.jar": nested_forked.getvalue()})
        p = []
        check_wire_identity_fabric(wire_lss_fab, wire_vss_bad, p)
        check(any("nested common jar differs" in m for m in p),
              f"wire-identity divergence not caught: {p}")

        # ---- Paper wire identity: the flat-shaded class bytes must match across the pair ----
        wire_lss_pap = os.path.join(td, "wire-lss-paper.jar")
        _make_jar(wire_lss_pap, {"plugin.yml": "name: x\n",
                                 "dev/vox/lss/common/LSSConstants.class": "lss:handshake",
                                 "dev/vox/lss/paper/LSSPaperPlugin.class": "main"})
        wire_vss_pap_ok = os.path.join(td, "wire-vss-ok-paper.jar")
        # Same classes, different plugin.yml (the legitimate rebrand) → wire-identical.
        _make_jar(wire_vss_pap_ok, {"plugin.yml": "name: y\n",
                                    "dev/vox/lss/common/LSSConstants.class": "lss:handshake",
                                    "dev/vox/lss/paper/LSSPaperPlugin.class": "main"})
        p = []
        check_wire_identity_paper(wire_lss_pap, wire_vss_pap_ok, p)
        check(p == [], f"identical paper class bytes flagged: {p}")
        # a changed class byte (branding leaked into behavior) MUST fail
        wire_vss_pap_bad = os.path.join(td, "wire-vss-bad-paper.jar")
        _make_jar(wire_vss_pap_bad, {"plugin.yml": "name: y\n",
                                     "dev/vox/lss/common/LSSConstants.class": "vss:handshake",
                                     "dev/vox/lss/paper/LSSPaperPlugin.class": "main"})
        p = []
        check_wire_identity_paper(wire_lss_pap, wire_vss_pap_bad, p)
        check(any("class bytes differ" in m for m in p),
              f"paper wire-identity divergence not caught: {p}")

        # glob hygiene: a CI-named soak jar must not match any release glob
        p = []
        check_glob_hygiene(p, [os.path.join(td, "lss-paper-soak-0.4.0+26.1.2.jar")])
        check(p == [], f"clean glob hygiene flagged: {p}")
        p = []
        # a soak jar mis-named to look like a release artifact MUST be caught
        check_glob_hygiene(p, [os.path.join(td, "lod-server-support-paper-soaky.jar")])
        check(any("MATCHES release glob" in m for m in p), "mis-named soak jar not caught")
        p = []
        # a soak jar mis-named to look like the VOXY release artifact MUST also be caught
        check_glob_hygiene(p, [os.path.join(td, "voxy-server-side-paper-soaky.jar")])
        check(any("MATCHES release glob" in m for m in p), "mis-named vss soak jar not caught")

        # ---- store-native matrix (plan §3) ----
        sn_fab = os.path.join(td, "store-fab.jar")
        _make_jar(sn_fab, {
            "fabric.mod.json": json.dumps({"version": "1", "jars": STORE_FABRIC_JARS_FIELD}),
            "META-INF/jars/sqlite-jdbc-slim.jar": _nested_sqlite(),
            "META-INF/jars/zstd-jni-slim.jar": _nested_zstd(missing_dir="linux/aarch64/"),
        })
        p = []
        check_store_natives_fabric(sn_fab, p)
        check(any("linux/aarch64/" in m for m in p),
              f"fabric jar missing a zstd native not caught: {p}")
        sn_fab_undeclared = os.path.join(td, "store-fab-undeclared.jar")
        _make_jar(sn_fab_undeclared, {
            "fabric.mod.json": json.dumps({"version": "1"}),  # no "jars" field
            "META-INF/jars/sqlite-jdbc-slim.jar": _nested_sqlite(),
            "META-INF/jars/zstd-jni-slim.jar": _nested_zstd(),
        })
        p = []
        check_store_natives_fabric(sn_fab_undeclared, p)
        check(any("does not declare" in m for m in p),
              f"undeclared nested store jar not caught: {p}")
        sn_pap = os.path.join(td, "store-pap.jar")
        pap_entries = _store_paper_entries()
        del pap_entries["org/sqlite/native/Mac/aarch64/libsqlitejdbc.dylib"]
        _make_jar(sn_pap, pap_entries)
        p = []
        check_store_natives_paper(sn_pap, p)
        check(any("Mac/aarch64" in m for m in p),
              f"paper jar missing a sqlite native not caught: {p}")
        # a regressed strip (an out-of-matrix native shipping) must be caught (F3)
        sn_pap_fat = os.path.join(td, "store-pap-fat.jar")
        fat_entries = _store_paper_entries()
        fat_entries["org/sqlite/native/Linux-Android/aarch64/libsqlitejdbc.so"] = "elf"
        fat_entries["linux/ppc64le/libzstd-jni-1.5.7-3.so"] = "elf"
        _make_jar(sn_pap_fat, fat_entries)
        p = []
        check_store_natives_paper(sn_pap_fat, p)
        check(any("outside the supported matrix" in m for m in p),
              f"regressed native strip not caught: {p}")

        # ---- third-party notices (review-fixes C6) ----
        tp_ok = os.path.join(td, "tp-ok.jar")
        _make_jar(tp_ok, {
            "THIRD-PARTY-NOTICES": "zstd-jni BSD-2 ... Zstandard BSD-3 ... " + "x" * 100,
            "META-INF/jars/sqlite-jdbc-slim.jar": _nested_sqlite(),
        })
        p = []
        check_third_party_notices(tp_ok, True, p)
        check(p == [], f"clean notices jar flagged: {p}")
        tp_missing = os.path.join(td, "tp-missing.jar")
        _make_jar(tp_missing, {"some.class": "x"})
        p = []
        check_third_party_notices(tp_missing, False, p)
        check(any("THIRD-PARTY-NOTICES missing" in m for m in p),
              f"missing notices not caught: {p}")
        tp_stripped = os.path.join(td, "tp-stripped-license.jar")
        _make_jar(tp_stripped, {
            "THIRD-PARTY-NOTICES": "x" * 200,
            "META-INF/jars/sqlite-jdbc-slim.jar": _nested_sqlite(with_license=False),
        })
        p = []
        check_third_party_notices(tp_stripped, True, p)
        check(any("lost its in-jar LICENSE" in m for m in p),
              f"stripped nested sqlite license not caught: {p}")

        # ---- discover(): end-to-end wiring over a synthetic build tree ----
        # The leaf checks above prove each check works; these prove discover() actually
        # CALLS them (presence, pair wiring, identity) — a refactor that drops a call
        # would otherwise leave the gate vacuously green.
        droot = os.path.join(td, "tree")
        dfab = os.path.join(droot, "fabric", "build", "libs")
        dpap = os.path.join(droot, "paper", "build", "libs")
        os.makedirs(dfab)
        os.makedirs(dpap)
        PY_LSS = ("name: LodServerSupport\nversion: '0.7.0'\n"
                  "main: dev.vox.lss.paper.LSSPaperPlugin\napi-version: '26.2'\n"
                  "folia-supported: true\n"
                  "description: LSS plugin.\n"
                  "website: https://modrinth.com/plugin/lod-server-support\n"
                  "commands:\n  lsslod:\n"
                  "    description: LOD Server Support admin commands\n"
                  "    usage: /lsslod <stats|diag>\n    permission: lss.admin\n"
                  "permissions:\n  lss.admin:\n"
                  "    description: Access to LSS admin commands\n    default: op\n")
        PY_VSS = (PY_LSS
                  .replace("description: LSS plugin.", "description: VSS plugin.", 1)
                  .replace("plugin/lod-server-support", "plugin/voxy-server-side", 1)
                  .replace("lsslod", "vsslod")
                  .replace("LOD Server Support admin commands", "Voxy Server Side admin commands")
                  .replace("Access to LSS admin commands", "Access to VSS admin commands")
                  .replace("lss.admin", "vss.admin"))
        BRAND_LSS = ("shortName=LSS\ndisplayName=LOD Server Support\n"
                     "clientCommand=lss\nserverCommand=lsslod\n")
        BRAND_VSS = ("shortName=VSS\ndisplayName=Voxy Server Side\n"
                     "clientCommand=vss\nserverCommand=vsslod\n")
        pap_manifest = "Manifest-Version: 1.0\npaperweight-mappings-namespace: mojang\n"

        def _write_tree_fabric(name, meta, brand, extra=None):
            meta = dict(meta)
            meta.setdefault("jars", STORE_FABRIC_JARS_FIELD)
            entries = {
                "fabric.mod.json": json.dumps(meta),
                "dev/vox/lss/LSSMod.class": "x",
                "META-INF/jars/common-0.7.0.jar": _nested_common("0.7.0"),
                "lss-brand.properties": brand,
                "LICENSE_lod-server-support-fabric": "MIT",
                "THIRD-PARTY-NOTICES": "sqlite-jdbc / zstd-jni notices " + "x" * 100,
            }
            entries.update(STORE_FABRIC_ENTRIES)
            entries.update(extra or {})
            _make_jar(os.path.join(dfab, name), entries, manifest=fab_manifest)

        def _write_tree_paper(name, yml, brand):
            entries = {
                "plugin.yml": yml,
                "lss-brand.properties": brand,
                "dev/vox/lss/paper/LSSPaperPlugin.class": "x",
                "dev/vox/lss/common/PositionUtil.class": "x",
                "THIRD-PARTY-NOTICES": "sqlite-jdbc / zstd-jni notices " + "x" * 100,
            }
            entries.update(_store_paper_entries())
            _make_jar(os.path.join(dpap, name), entries, manifest=pap_manifest)

        _write_tree_fabric("lod-server-support-fabric.jar",
                           {"id": "lss", "name": "LOD Server Support", "version": "0.7.0"},
                           BRAND_LSS)
        _write_tree_fabric("voxy-server-side-fabric.jar",
                           {"id": "lss", "name": "Voxy Server Side", "version": "0.7.0",
                            "icon": "assets/lss/icon-vss.png"},
                           BRAND_VSS,
                           extra={"assets/lss/icon-vss.png": "PNG"})
        _write_tree_paper("lod-server-support-paper.jar", PY_LSS, BRAND_LSS)
        _write_tree_paper("voxy-server-side-paper.jar", PY_VSS, BRAND_VSS)
        p = []
        fab_d, pap_d, vfab_d, vpap_d, _ = discover(p, root=droot)
        check(p == [] and len(fab_d) == len(pap_d) == len(vfab_d) == len(vpap_d) == 1,
              f"clean synthetic tree flagged by discover: {p}")

        # a missing vss family must fail the gate (silently unwired repackage task)
        os.remove(os.path.join(dfab, "voxy-server-side-fabric.jar"))
        p = []
        discover(p, root=droot)
        check(any("no voxy-server-side-fabric jar" in m for m in p),
              f"missing vss fabric family not caught by discover: {p}")

        # a forked vss id must be caught THROUGH discover (identity wiring intact)
        _write_tree_fabric("voxy-server-side-fabric.jar",
                           {"id": "vss", "name": "Voxy Server Side", "version": "0.7.0",
                            "icon": "assets/lss/icon-vss.png"},
                           BRAND_VSS,
                           extra={"assets/lss/icon-vss.png": "PNG"})
        p = []
        discover(p, root=droot)
        check(any("must stay 'lss'" in m for m in p),
              f"forked vss id not caught through discover: {p}")

    print(f"release_check selftest OK: {n} cases")
    return 0


# ----------------------------------------------------------------------------- main

def main(argv):
    ap = argparse.ArgumentParser(description="Gate release jars + publish metadata before shipping.")
    ap.add_argument("--selftest", action="store_true", help="run synthetic-fixture checks and exit")
    ap.add_argument("--version", metavar="X.Y.Z",
                    help="require and check exactly the release jars for this mod_version "
                         "(ignores stale artifacts from earlier builds)")
    args = ap.parse_args(argv)
    if args.selftest:
        return _selftest()

    problems = []
    fab, pap, vfab, vpap, soak = discover(problems, expected_version=args.version)
    print(f"release_check: lss(fabric={len(fab)} paper={len(pap)}) "
          f"vss(fabric={len(vfab)} paper={len(vpap)}) soak={len(soak)}")
    if problems:
        print(f"FAIL: {len(problems)} release problem(s):")
        for m in problems:
            print(f"  - {m}")
        return 1
    print("OK: release artifacts clean")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
