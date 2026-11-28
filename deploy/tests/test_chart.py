"""Chart tests: render deploy/county with `helm template` and assert on the parsed objects.

Run from the repository root: python3 -m unittest discover deploy/tests
Needs `helm` (3.x) on PATH.
"""
import subprocess
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
CHART = ROOT / "deploy" / "county"
DEMO_VALUES = ROOT / "deploy" / "values" / "demo.yaml"
SHA = "0123456789abcdef0123456789abcdef01234567"


def helm(*args):
    return subprocess.run(["helm", *args], capture_output=True, text=True, cwd=ROOT)


def render(*sets, tag=SHA, values=DEMO_VALUES):
    """helm template with the demo values. `sets` are raw --set arguments, tag=None leaves image.tag unset."""
    args = ["template", "county", str(CHART), "-n", "county-demo", "-f", str(values)]
    if tag is not None:
        args += ["--set-string", f"image.tag={tag}"]
    for item in sets:
        args += ["--set-string", item] if not item.endswith("=null") else ["--set", item]
    return helm(*args)


def objects(*sets, **kwargs):
    result = render(*sets, **kwargs)
    assert result.returncode == 0, result.stderr
    return [doc for doc in yaml.safe_load_all(result.stdout) if doc]


def find(docs, kind, name):
    matches = [d for d in docs if d["kind"] == kind and d["metadata"]["name"] == name]
    assert len(matches) == 1, f"expected one {kind}/{name}, found {len(matches)}"
    return matches[0]


def container_image(docs, deployment):
    return find(docs, "Deployment", deployment)["spec"]["template"]["spec"]["containers"][0]["image"]


class ChartRendersTest(unittest.TestCase):
    """C0"""

    def test_lints_with_the_demo_values(self):
        result = helm("lint", str(CHART), "-f", str(DEMO_VALUES), "--set-string", f"image.tag={SHA}")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_renders_with_the_demo_values(self):
        result = render()
        self.assertEqual(result.returncode, 0, result.stderr)
        docs = [d for d in yaml.safe_load_all(result.stdout) if d]
        self.assertIn("county-api", [d["metadata"]["name"] for d in docs if d["kind"] == "Deployment"])


class RenderGuardsTest(unittest.TestCase):
    """C5"""

    def assertRenderFails(self, mentions, *sets, **kwargs):
        result = render(*sets, **kwargs)
        self.assertNotEqual(result.returncode, 0, "rendered although the input is invalid")
        self.assertIn(mentions, result.stderr)

    def test_image_tag_is_required(self):
        self.assertRenderFails("image.tag", tag=None)

    def test_latest_is_not_a_tag(self):
        self.assertRenderFails("image.tag", tag="latest")

    def test_tag_must_be_forty_hex_characters(self):
        for tag in (SHA[:39], SHA + "0", SHA.upper(), "g" + SHA[1:]):
            with self.subTest(tag=tag):
                self.assertRenderFails("image.tag", tag=tag)

    def test_public_url_is_required(self):
        self.assertRenderFails("publicUrl", "publicUrl=null")

    def test_public_url_must_be_https(self):
        self.assertRenderFails("publicUrl", "publicUrl=http://county.example.ph")

    def test_images_are_registry_name_and_sha(self):
        docs = objects()
        self.assertEqual(container_image(docs, "county-api"), f"ghcr.io/jasoncalalang/county-api:{SHA}")
        self.assertEqual(container_image(docs, "county-web"), f"ghcr.io/jasoncalalang/county-web:{SHA}")

    def test_registry_is_a_value(self):
        docs = objects("image.registry=registry.test/team")
        self.assertEqual(container_image(docs, "county-api"), f"registry.test/team/county-api:{SHA}")


if __name__ == "__main__":
    unittest.main()
