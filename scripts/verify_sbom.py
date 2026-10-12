#!/usr/bin/env python3
"""Check SBOM graph coverage, scope separation and binding to the assembled APK.

This stdlib check complements (does not replace) CycloneDX schema validation.
Run from the repository root after `./gradlew sbom`.
"""
import hashlib
import json
from pathlib import Path
import unittest
import zipfile

ROOT = Path(__file__).resolve().parent.parent


class SbomTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.boms = {
            scope: json.loads((ROOT / "sbom" / f"{scope}.cdx.json").read_text())
            for scope in ("build", "runtime")
        }

    def test_graph_has_no_duplicates_or_dangling_edges(self):
        for scope, bom in self.boms.items():
            with self.subTest(scope=scope):
                self.assertEqual((bom["bomFormat"], bom["specVersion"]), ("CycloneDX", "1.6"))
                components = bom["components"]
                root = bom["metadata"]["component"]["bom-ref"]
                refs = {c["bom-ref"] for c in components} | {root}
                self.assertEqual(len(refs), len(components) + 1)
                graph = {d["ref"]: set(d["dependsOn"]) for d in bom["dependencies"]}
                self.assertEqual(len(graph), len(bom["dependencies"]))
                self.assertEqual(set(graph), refs)
                for children in graph.values():
                    self.assertTrue(children <= refs)
                reachable, queue = set(), [root]
                while queue:
                    ref = queue.pop()
                    if ref not in reachable:
                        reachable.add(ref)
                        queue.extend(graph[ref])
                self.assertEqual(reachable, refs)

    def test_build_and_runtime_are_distinct(self):
        def coordinates(scope):
            return {(c.get("group"), c["name"], c.get("version")) for c in self.boms[scope]["components"]}
        build, runtime = coordinates("build"), coordinates("runtime")
        for dependency in [("com.android.tools.build", "gradle", "8.6.1"),
                           ("org.jetbrains.kotlin", "kotlin-gradle-plugin", "1.9.24"),
                           ("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "1.9.24")]:
            self.assertIn(dependency, build)
            self.assertNotIn(dependency, runtime)
        for dependency in [("com.github.oshi", "oshi-core", "6.9.3"),
                           ("net.java.dev.jna", "jna", "5.18.1"),
                           ("net.java.dev.jna", "jna-platform", "5.18.1"),
                           ("org.slf4j", "slf4j-api", "2.0.17")]:
            self.assertIn(dependency, runtime)
        self.assertTrue({"tool:gradle", "tool:jdk", "tool:android-platform", "tool:android-build-tools"}
                        <= {c["bom-ref"] for c in self.boms["build"]["components"]})
        self.assertIn("environment:android", {c["bom-ref"] for c in self.boms["runtime"]["components"]})

    def test_checksums_and_android_jna_artifact(self):
        for bom in self.boms.values():
            for component in bom["components"]:
                for prop in component.get("properties", []):
                    if prop["name"].endswith(":sha256"):
                        self.assertRegex(prop["value"], r"^[0-9a-f]{64}$")
                        self.assertNotIn("/", prop["name"].removeprefix("ragbaz:artifact:"))
        jna = next(c for c in self.boms["runtime"]["components"]
                   if c.get("group") == "net.java.dev.jna" and c["name"] == "jna")
        artifacts = [p["name"] for p in jna["properties"] if p["name"].startswith("ragbaz:artifact:")]
        self.assertEqual(artifacts, ["ragbaz:artifact:jna-5.18.1.aar:sha256"])

    def test_reports_bind_to_the_built_apk_and_native_libraries(self):
        runtime = self.boms["runtime"]
        variant = next(p["value"] for p in runtime["metadata"]["properties"] if p["name"] == "ragbaz:variant")
        root = runtime["metadata"]["component"]
        apk_name = next(p["value"] for p in root["properties"] if p["name"] == "ragbaz:artifact")
        apk = ROOT / "app" / "build" / "outputs" / "apk" / variant / apk_name
        digest = hashlib.sha256(apk.read_bytes()).hexdigest()
        for bom in self.boms.values():
            self.assertEqual(bom["metadata"]["component"]["hashes"], [{"alg": "SHA-256", "content": digest}])
        with zipfile.ZipFile(apk) as archive:
            self.assertIn("lib/arm64-v8a/libjnidispatch.so", archive.namelist())
            self.assertIn("lib/armeabi-v7a/libjnidispatch.so", archive.namelist())
            self.assertIn("META-INF/AL2.0", archive.namelist())
            self.assertIn("META-INF/LGPL2.1", archive.namelist())


if __name__ == "__main__":
    unittest.main()
