"""Pipeline and image-context tests: plain text checks of files that cannot be executed here.

Run from the repository root: python3 -m unittest discover deploy/tests
"""
import re
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]


def read(path):
    assert (ROOT / path).is_file(), f"{path} does not exist"
    return (ROOT / path).read_text()


def dockerignore_patterns():
    text = read(".dockerignore")
    return [line.strip() for line in text.splitlines() if line.strip() and not line.startswith("#")]


class DockerignoreTest(unittest.TestCase):
    """I3: the build context keeps secrets and build output out of the images."""

    def test_git_history_is_excluded(self):
        self.assertIn(".git", dockerignore_patterns())

    def test_env_files_are_excluded_except_the_example(self):
        patterns = dockerignore_patterns()
        self.assertIn("**/.env*", patterns)
        self.assertIn("!**/.env.example", patterns)
        self.assertLess(patterns.index("**/.env*"), patterns.index("!**/.env.example"))

    def test_dependencies_and_build_output_are_excluded(self):
        patterns = dockerignore_patterns()
        for pattern in ("**/node_modules", "**/build", "**/.gradle", "**/.next", "**/test-results"):
            with self.subTest(pattern=pattern):
                self.assertIn(pattern, patterns)

    def test_nothing_the_images_build_from_is_excluded(self):
        patterns = dockerignore_patterns()
        for source in ("api", "web", "api/src", "web/app", "web/lib", "web/package-lock.json", "api/gradlew"):
            with self.subTest(source=source):
                self.assertNotIn(source, patterns)
                self.assertNotIn(f"{source}/**", patterns)


HELM_VERSION = "3.19.0"
HELM_SHA256 = "a7f81ce08007091b86d8bd696eb4d86b8d0f2e1b9f6c714be62f82f96a594496"
DIGEST_PIN = re.compile(r"@sha256:[0-9a-f]{64}")


def stage(text, name):
    """The body of `stage('name') { ... }`, found by matching braces."""
    start = re.search(rf"stage\('{re.escape(name)}'\)\s*\{{", text)
    assert start, f"no stage '{name}'"
    depth, index = 1, start.end()
    while depth:
        depth += {"{": 1, "}": -1}.get(text[index], 0)
        index += 1
    return text[start.start():index]


class JenkinsfileTest(unittest.TestCase):
    """P1"""

    def setUp(self):
        self.text = read("Jenkinsfile")

    def test_stages_follow_the_spec_in_order(self):
        names = re.findall(r"stage\('([^']+)'\)", self.text)
        self.assertEqual(names, ["Checkout", "api", "web", "images", "api image", "web image", "chart", "deploy", "smoke"])

    def test_deploy_and_smoke_run_only_on_main_before_taking_an_agent(self):
        for name in ("deploy", "smoke"):
            with self.subTest(stage=name):
                self.assertRegex(stage(self.text, name), r"when\s*\{\s*branch 'main'\s*beforeAgent true\s*\}")

    def test_every_other_stage_runs_on_every_branch(self):
        for name in ("Checkout", "api", "web", "images", "chart"):
            with self.subTest(stage=name):
                self.assertNotIn("when", stage(self.text, name))

    def test_deploy_rolls_back_on_failure_and_pins_the_commit(self):
        deploy = stage(self.text, "deploy")
        for fragment in ("helm upgrade --install county deploy/county", "-n county-demo", "-f deploy/values/demo.yaml",
                         "--set-string image.tag=$GIT_COMMIT", "--atomic", "--wait", "--timeout 5m"):
            with self.subTest(fragment=fragment):
                self.assertIn(fragment, deploy)

    def test_no_other_stage_touches_the_cluster(self):
        for name in ("Checkout", "api", "web", "images", "chart", "smoke"):
            with self.subTest(stage=name):
                body = stage(self.text, name)
                for command in ("helm upgrade", "helm install", "helm rollback", "kubectl"):
                    self.assertNotIn(command, body)

    def test_only_the_deploy_agent_has_cluster_rights(self):
        self.assertEqual(self.text.count("serviceAccountName: jenkins-deployer"), 1)
        self.assertIn("serviceAccountName: jenkins-deployer", stage(self.text, "deploy"))

    def test_images_are_tagged_with_the_commit_and_never_latest(self):
        destinations = re.findall(r"--destination=(\S+)", self.text)
        self.assertEqual(destinations, ["ghcr.io/jasoncalalang/county-api:$GIT_COMMIT",
                                        "ghcr.io/jasoncalalang/county-web:$GIT_COMMIT"])
        self.assertNotIn("latest", self.text)

    def test_kaniko_caches_in_image_cache_repositories(self):
        self.assertEqual(re.findall(r"--cache-repo=(\S+)", self.text),
                         ["ghcr.io/jasoncalalang/county-api-cache", "ghcr.io/jasoncalalang/county-web-cache"])

    def test_the_registry_token_is_a_mounted_file_not_a_value(self):
        self.assertIn("/kaniko/.docker", self.text)
        self.assertRegex(self.text, r"secretName: \S+")
        self.assertNotIn("credentials(", self.text)
        self.assertNotIn("withCredentials", self.text)

    def test_no_literal_credential(self):
        patterns = (r"ghp_[A-Za-z0-9]{20,}", r"github_pat_", r"AKIA[0-9A-Z]{12,}", r"BEGIN [A-Z ]*PRIVATE KEY",
                    r"(?i)\b(password|passwd|token|apikey|api_key)\b\s*[:=]\s*[\"']?[A-Za-z0-9+/_-]{8,}")
        for pattern in patterns:
            with self.subTest(pattern=pattern):
                self.assertIsNone(re.search(pattern, self.text))

    def test_every_agent_image_is_pinned_by_digest(self):
        images = re.findall(r"^\s*image: (\S+)", self.text, re.M)
        self.assertGreaterEqual(len(images), 6)
        for image in images:
            with self.subTest(image=image):
                self.assertRegex(image, DIGEST_PIN)

    def test_the_test_database_sidecar_listens_on_localhost_only(self):
        self.assertIn("listen_addresses=127.0.0.1", self.text)


class DeployWorkflowTest(unittest.TestCase):
    """P2"""

    def setUp(self):
        self.text = read(".github/workflows/deploy.yml")
        self.workflow = yaml.safe_load(self.text)

    def test_the_workflow_can_only_read_the_repository(self):
        self.assertEqual(self.workflow["permissions"], {"contents": "read"})
        for name, job in self.workflow["jobs"].items():
            with self.subTest(job=name):
                self.assertNotIn("permissions", job)

    def test_every_action_is_pinned_to_a_commit_sha(self):
        uses = re.findall(r"^\s*-?\s*uses: (\S+)", self.text, re.M)
        self.assertTrue(uses)
        for action in uses:
            with self.subTest(action=action):
                self.assertRegex(action, r"@[0-9a-f]{40}$")

    def test_it_never_pushes_deploys_or_holds_credentials(self):
        for forbidden in ("docker push", "docker login", "helm upgrade", "helm install", "kubectl", "secrets."):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, self.text)

    def test_it_runs_for_pull_requests_and_main_on_the_files_it_checks(self):
        triggers = self.workflow[True]
        self.assertEqual(triggers["push"]["branches"], ["main"])
        for paths in (triggers["pull_request"]["paths"], triggers["push"]["paths"]):
            for path in ("deploy/**", "api/**", "web/**", "Jenkinsfile", ".dockerignore", ".github/workflows/deploy.yml"):
                self.assertIn(path, paths)

    def test_helm_is_installed_from_a_pinned_release_with_its_checksum_verified(self):
        self.assertIn(f"helm-v{HELM_VERSION}-linux-amd64.tar.gz", self.text)
        self.assertIn(HELM_SHA256, self.text)
        self.assertIn("sha256sum -c", self.text)

    def test_it_lints_tests_the_chart_and_runs_the_image_smoke_test(self):
        self.assertEqual(sorted(self.workflow["jobs"]), ["chart", "images"])
        self.assertIn("helm lint deploy/county", self.text)
        self.assertIn("unittest discover deploy/tests", self.text)
        self.assertIn("deploy/tests/smoke.sh", self.text)

    def test_it_prints_the_digest_of_the_api_runtime_base_image(self):
        base = re.search(r"^FROM (registry\.access\.redhat\.com/\S+)$", read("api/Dockerfile"), re.M).group(1)
        self.assertIn(f"imagetools inspect {base}", self.text)


class PinsTest(unittest.TestCase):
    """E12: what is pinned, is pinned the same way everywhere."""

    def test_helm_version_and_checksum_match_in_jenkinsfile_and_workflow(self):
        for path in ("Jenkinsfile", ".github/workflows/deploy.yml"):
            with self.subTest(path=path):
                text = read(path)
                self.assertIn(f"helm-v{HELM_VERSION}-linux-amd64.tar.gz", text)
                self.assertIn(HELM_SHA256, text)

    def test_dockerfile_base_images_are_pinned_by_digest_except_the_ubi_runtime(self):
        for path in ("api/Dockerfile", "web/Dockerfile"):
            stages = set()
            for line in read(path).splitlines():
                match = re.match(r"FROM (\S+)(?: AS (\S+))?$", line)
                if not match:
                    continue
                image, alias = match.groups()
                with self.subTest(path=path, image=image):
                    if image in stages:
                        pass
                    elif image.startswith("registry.access.redhat.com/ubi9/openjdk-21-runtime:"):
                        self.assertNotRegex(image, r":(latest)?$")
                    else:
                        self.assertRegex(image, DIGEST_PIN)
                if alias:
                    stages.add(alias)


if __name__ == "__main__":
    unittest.main()
