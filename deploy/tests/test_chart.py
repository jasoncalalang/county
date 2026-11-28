"""Chart tests: render deploy/county with `helm template` and assert on the parsed objects.

Run from the repository root: python3 -m unittest discover deploy/tests
Needs `helm` (3.x) on PATH.
"""
import re
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
        typed = item.endswith(("=null", "=true", "=false"))
        args += ["--set" if typed else "--set-string", item]
    return helm(*args)


class Strict(dict):
    """A missing key is a failed assertion, not a KeyError."""

    def __missing__(self, key):
        raise AssertionError(f"missing key {key!r} in {sorted(self)}")


def strict(node):
    if isinstance(node, dict):
        return Strict({key: strict(value) for key, value in node.items()})
    if isinstance(node, list):
        return [strict(item) for item in node]
    return node


def parse(text):
    return [strict(doc) for doc in yaml.safe_load_all(text) if doc]


def objects(*sets, **kwargs):
    result = render(*sets, **kwargs)
    assert result.returncode == 0, result.stderr
    return parse(result.stdout)


def find(docs, kind, name):
    matches = [d for d in docs if d["kind"] == kind and d["metadata"]["name"] == name]
    assert len(matches) == 1, f"expected one {kind}/{name}, found {len(matches)}"
    return matches[0]


def container_image(docs, deployment):
    return find(docs, "Deployment", deployment)["spec"]["template"]["spec"]["containers"][0]["image"]


def pod_spec(workload):
    return workload["spec"]["template"]["spec"]


def container(docs, kind, name):
    return pod_spec(find(docs, kind, name))["containers"][0]


def env(container_):
    return Strict({item["name"]: item for item in container_.get("env", [])})


def config_map(docs, name):
    return find(docs, "ConfigMap", name)["data"]


WORKLOADS = (("Deployment", "county-api"), ("Deployment", "county-web"), ("StatefulSet", "county-postgres"))


class ChartRendersTest(unittest.TestCase):
    """C0"""

    def test_lints_with_the_demo_values(self):
        result = helm("lint", str(CHART), "-f", str(DEMO_VALUES), "--set-string", f"image.tag={SHA}")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_renders_with_the_demo_values(self):
        result = render()
        self.assertEqual(result.returncode, 0, result.stderr)
        docs = parse(result.stdout)
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


class SecretsTest(unittest.TestCase):
    """C1"""

    def test_the_chart_renders_no_secret_object(self):
        self.assertEqual([d["metadata"]["name"] for d in objects() if d["kind"] == "Secret"], [])

    def assertSecretRefs(self, kind, name, expected, *sets):
        variables = env(container(objects(*sets), kind, name))
        for variable, (secret, key) in expected.items():
            with self.subTest(variable=variable):
                self.assertNotIn("value", variables[variable])
                self.assertEqual(variables[variable]["valueFrom"]["secretKeyRef"], {"name": secret, "key": key})

    def test_api_takes_its_secrets_by_reference(self):
        self.assertSecretRefs("Deployment", "county-api", {
            "COUNTY_DB_PASSWORD": ("county-postgres", "POSTGRES_PASSWORD"),
            "GOOGLE_CLIENT_ID": ("county-google", "GOOGLE_CLIENT_ID"),
        })

    def test_web_takes_its_secrets_by_reference(self):
        self.assertSecretRefs("Deployment", "county-web", {
            "AUTH_SECRET": ("county-web", "AUTH_SECRET"),
            "AUTH_GOOGLE_ID": ("county-google", "GOOGLE_CLIENT_ID"),
            "AUTH_GOOGLE_SECRET": ("county-google", "GOOGLE_CLIENT_SECRET"),
        })

    def test_postgres_takes_its_password_by_reference(self):
        self.assertSecretRefs("StatefulSet", "county-postgres", {
            "POSTGRES_PASSWORD": ("county-postgres", "POSTGRES_PASSWORD"),
        })

    def test_secret_names_come_from_values(self):
        sets = ("secrets.google=g-secret", "secrets.postgres=p-secret", "secrets.web=w-secret")
        self.assertSecretRefs("Deployment", "county-api", {
            "COUNTY_DB_PASSWORD": ("p-secret", "POSTGRES_PASSWORD"),
            "GOOGLE_CLIENT_ID": ("g-secret", "GOOGLE_CLIENT_ID"),
        }, *sets)
        self.assertSecretRefs("Deployment", "county-web", {
            "AUTH_SECRET": ("w-secret", "AUTH_SECRET"),
            "AUTH_GOOGLE_SECRET": ("g-secret", "GOOGLE_CLIENT_SECRET"),
        }, *sets)
        self.assertSecretRefs("StatefulSet", "county-postgres", {
            "POSTGRES_PASSWORD": ("p-secret", "POSTGRES_PASSWORD"),
        }, *sets)

    def test_no_config_map_key_looks_like_a_secret(self):
        docs = [d for d in objects() if d["kind"] == "ConfigMap"]
        self.assertEqual(len(docs), 2)
        for doc in docs:
            for key in doc["data"]:
                self.assertIsNone(re.search("password|secret|token", key, re.I), key)

    def test_a_secret_looking_variable_never_has_a_literal_value(self):
        docs = objects()
        for kind, name in WORKLOADS:
            for variable, item in env(container(docs, kind, name)).items():
                if re.search("password|secret|token|_id$", variable, re.I):
                    with self.subTest(workload=name, variable=variable):
                        self.assertNotIn("value", item)


class PodSecurityTest(unittest.TestCase):
    """C3"""

    HARDENED = {
        "allowPrivilegeEscalation": False,
        "readOnlyRootFilesystem": True,
        "capabilities": {"drop": ["ALL"]},
        "seccompProfile": {"type": "RuntimeDefault"},
    }

    def check_hardened(self, name, uid):
        docs = objects()
        workload = find(docs, "Deployment", name)
        pod = pod_spec(workload)
        self.assertIs(pod["securityContext"]["runAsNonRoot"], True)
        self.assertEqual(pod["securityContext"]["runAsUser"], uid)
        context = pod["containers"][0]["securityContext"]
        for key, value in self.HARDENED.items():
            self.assertEqual(context.get(key), value, key)
        return pod

    def test_api_runs_hardened_with_only_tmp_writable(self):
        pod = self.check_hardened("county-api", 185)
        self.assertEqual([m["mountPath"] for m in pod["containers"][0]["volumeMounts"]], ["/tmp"])
        self.assertEqual([("emptyDir" in v) for v in pod["volumes"]], [True])

    def test_web_runs_hardened_with_only_the_next_cache_writable(self):
        pod = self.check_hardened("county-web", 1000)
        self.assertEqual([m["mountPath"] for m in pod["containers"][0]["volumeMounts"]], ["/app/.next/cache"])
        self.assertEqual([("emptyDir" in v) for v in pod["volumes"]], [True])

    def test_postgres_runs_as_uid_999_with_fs_group_999(self):
        statefulset = find(objects(), "StatefulSet", "county-postgres")
        context = pod_spec(statefulset)["securityContext"]
        self.assertEqual((context["runAsUser"], context["fsGroup"]), (999, 999))
        self.assertIs(context["runAsNonRoot"], True)

    def test_postgres_writes_only_to_its_claim(self):
        statefulset = find(objects(), "StatefulSet", "county-postgres")
        mounts = pod_spec(statefulset)["containers"][0]["volumeMounts"]
        self.assertEqual([m["mountPath"] for m in mounts], ["/var/lib/postgresql/data"])
        self.assertEqual([m["name"] for m in mounts], [c["metadata"]["name"] for c in statefulset["spec"]["volumeClaimTemplates"]])
        self.assertNotIn("volumes", pod_spec(statefulset))


class WorkloadSettingsTest(unittest.TestCase):
    """C6"""

    def startup_budget_seconds(self, probe):
        return probe.get("initialDelaySeconds", 0) + probe["periodSeconds"] * probe["failureThreshold"]

    def test_api_listens_on_8080_and_probes_the_q_endpoints(self):
        api = container(objects(), "Deployment", "county-api")
        self.assertEqual([p["containerPort"] for p in api["ports"]], [8080])
        self.assertEqual(api["startupProbe"]["httpGet"], {"path": "/q/health/started", "port": 8080})
        self.assertEqual(api["livenessProbe"]["httpGet"], {"path": "/q/health/live", "port": 8080})
        self.assertEqual(api["readinessProbe"]["httpGet"], {"path": "/q/health/ready", "port": 8080})
        self.assertEqual(self.startup_budget_seconds(api["startupProbe"]), 60)

    def test_web_listens_on_3000_and_probes_only_healthz(self):
        web = container(objects(), "Deployment", "county-web")
        self.assertEqual([p["containerPort"] for p in web["ports"]], [3000])
        for probe in ("startupProbe", "livenessProbe", "readinessProbe"):
            with self.subTest(probe=probe):
                self.assertEqual(web[probe]["httpGet"], {"path": "/healthz", "port": 3000})
        self.assertEqual(self.startup_budget_seconds(web["startupProbe"]), 30)

    def test_postgres_listens_on_5432_and_probes_with_pg_isready(self):
        postgres = container(objects(), "StatefulSet", "county-postgres")
        self.assertEqual([p["containerPort"] for p in postgres["ports"]], [5432])
        for probe in ("startupProbe", "livenessProbe", "readinessProbe"):
            with self.subTest(probe=probe):
                self.assertEqual(postgres[probe]["exec"]["command"][0], "pg_isready")
        self.assertEqual(self.startup_budget_seconds(postgres["startupProbe"]), 60)

    def test_resources_follow_the_spec_with_no_cpu_limit(self):
        docs = objects()
        expected = {
            ("Deployment", "county-api"): ({"cpu": "250m", "memory": "384Mi"}, {"memory": "768Mi"}),
            ("Deployment", "county-web"): ({"cpu": "100m", "memory": "128Mi"}, {"memory": "256Mi"}),
            ("StatefulSet", "county-postgres"): ({"cpu": "100m", "memory": "256Mi"}, {"memory": "512Mi"}),
        }
        for (kind, name), (requests, limits) in expected.items():
            with self.subTest(workload=name):
                resources = container(docs, kind, name)["resources"]
                self.assertEqual(resources, {"requests": requests, "limits": limits})

    def test_api_and_web_roll_one_surge_and_no_unavailable(self):
        docs = objects()
        for name in ("county-api", "county-web"):
            with self.subTest(workload=name):
                self.assertEqual(find(docs, "Deployment", name)["spec"]["strategy"],
                                 {"type": "RollingUpdate", "rollingUpdate": {"maxSurge": 1, "maxUnavailable": 0}})

    def test_replicas_and_resources_are_values(self):
        docs = objects("api.replicas=2", "web.replicas=3", "api.resources.limits.memory=1Gi")
        self.assertEqual(find(docs, "Deployment", "county-api")["spec"]["replicas"], 2)
        self.assertEqual(find(docs, "Deployment", "county-web")["spec"]["replicas"], 3)
        self.assertEqual(container(docs, "Deployment", "county-api")["resources"]["limits"]["memory"], "1Gi")

    def test_postgres_is_one_replica_and_its_image_is_a_pinned_value(self):
        statefulset = find(objects(), "StatefulSet", "county-postgres")
        self.assertEqual(statefulset["spec"]["replicas"], 1)
        self.assertEqual(statefulset["spec"]["updateStrategy"], {"type": "RollingUpdate"})
        self.assertRegex(container(objects(), "StatefulSet", "county-postgres")["image"], r"^postgres:17@sha256:[0-9a-f]{64}$")
        docs = objects("postgres.image=postgres:17.99")
        self.assertEqual(container(docs, "StatefulSet", "county-postgres")["image"], "postgres:17.99")

    def test_postgres_is_configured_for_the_county_database(self):
        variables = env(container(objects(), "StatefulSet", "county-postgres"))
        self.assertEqual(variables["POSTGRES_DB"]["value"], "county")
        self.assertEqual(variables["POSTGRES_USER"]["value"], "county")
        self.assertEqual(variables["PGDATA"]["value"], "/var/lib/postgresql/data/pgdata")


class ConfigurationTest(unittest.TestCase):
    """C7"""

    def test_api_config_map_carries_the_variables_the_code_reads(self):
        data = config_map(objects(), "county-api")
        self.assertEqual(data["COUNTY_JDBC_URL"], "jdbc:postgresql://county-postgres:5432/county")
        self.assertEqual(data["COUNTY_DB_USER"], "county")
        self.assertEqual(data["COUNTY_PUBLIC_URL"], "https://county.example.ph")
        self.assertEqual(data["TZ"], "UTC")
        self.assertEqual(data["QUARKUS_HTTP_PROXY_PROXY_ADDRESS_FORWARDING"], "true")

    def test_the_datasource_url_names_the_postgres_service(self):
        docs = objects()
        service = find(docs, "Service", "county-postgres")
        self.assertEqual(service["spec"]["clusterIP"], "None")
        self.assertEqual([p["port"] for p in service["spec"]["ports"]], [5432])
        self.assertIn("//county-postgres:5432/", config_map(docs, "county-api")["COUNTY_JDBC_URL"])

    def test_public_url_is_what_the_values_say(self):
        docs = objects("publicUrl=https://orders.test.example")
        self.assertEqual(config_map(docs, "county-api")["COUNTY_PUBLIC_URL"], "https://orders.test.example")
        self.assertEqual(config_map(docs, "county-web")["AUTH_URL"], "https://orders.test.example/auth")

    def test_web_config_map_carries_the_variables_the_code_reads(self):
        data = config_map(objects(), "county-web")
        self.assertEqual(data["COUNTY_API_URL"], "http://county-api:8080/api")
        self.assertEqual(data["AUTH_URL"], "https://county.example.ph/auth")
        self.assertEqual(data["AUTH_TRUST_HOST"], "true")
        self.assertEqual(data["PORT"], "3000")
        self.assertEqual(data["HOSTNAME"], "0.0.0.0")

    def test_the_containers_read_their_config_maps(self):
        docs = objects()
        for name in ("county-api", "county-web"):
            with self.subTest(workload=name):
                self.assertEqual(container(docs, "Deployment", name)["envFrom"], [{"configMapRef": {"name": name}}])

    def test_the_access_log_is_not_turned_on(self):
        docs = objects()
        for doc in docs:
            if doc["kind"] == "ConfigMap":
                self.assertEqual([k for k in doc["data"] if "ACCESS_LOG" in k.upper()], [])
        for kind, name in WORKLOADS:
            self.assertEqual([v for v in env(container(docs, kind, name)) if "ACCESS_LOG" in v.upper()], [])

    def checksum(self, docs, name):
        return find(docs, "Deployment", name)["spec"]["template"]["metadata"]["annotations"]["checksum/config"]

    def test_a_changed_config_value_changes_the_pod_template_checksum(self):
        before, after = objects(), objects("publicUrl=https://orders.test.example")
        self.assertEqual(self.checksum(before, "county-api"), self.checksum(objects(), "county-api"))
        for name in ("county-api", "county-web"):
            with self.subTest(workload=name):
                self.assertNotEqual(self.checksum(before, name), self.checksum(after, name))

    def test_the_two_checksums_follow_their_own_config_maps(self):
        docs = objects()
        self.assertNotEqual(self.checksum(docs, "county-api"), self.checksum(docs, "county-web"))


class LabelsTest(unittest.TestCase):
    """C8"""

    def test_every_object_carries_the_spec_labels(self):
        for doc in objects():
            labels = doc["metadata"]["labels"]
            with self.subTest(object=f"{doc['kind']}/{doc['metadata']['name']}"):
                self.assertEqual(labels["app.kubernetes.io/name"], "county")
                self.assertEqual(labels["app.kubernetes.io/instance"], "county")
                self.assertIn(labels["app.kubernetes.io/component"], ("api", "web", "postgres", "ingress"))

    def test_the_release_name_is_the_instance(self):
        result = helm("template", "other", str(CHART), "-f", str(DEMO_VALUES), "--set-string", f"image.tag={SHA}")
        self.assertEqual(result.returncode, 0, result.stderr)
        for doc in parse(result.stdout):
            self.assertEqual(doc["metadata"]["labels"]["app.kubernetes.io/instance"], "other")

    def test_workload_selectors_match_their_pod_labels(self):
        docs = objects()
        for kind, name in WORKLOADS:
            workload = find(docs, kind, name)
            selector = workload["spec"]["selector"]["matchLabels"]
            pod_labels = workload["spec"]["template"]["metadata"]["labels"]
            with self.subTest(workload=name):
                self.assertEqual(selector, {k: pod_labels[k] for k in selector})
                self.assertEqual(selector["app.kubernetes.io/component"], name.removeprefix("county-"))
                self.assertEqual(pod_labels["app.kubernetes.io/name"], "county")
                self.assertEqual(pod_labels["app.kubernetes.io/instance"], "county")

    def test_services_select_their_pods_and_expose_one_port(self):
        docs = objects()
        for kind, name, port in (("Deployment", "county-api", 8080), ("Deployment", "county-web", 3000),
                                 ("StatefulSet", "county-postgres", 5432)):
            service = find(docs, "Service", name)
            pod_labels = find(docs, kind, name)["spec"]["template"]["metadata"]["labels"]
            with self.subTest(service=name):
                self.assertEqual(service["spec"]["selector"], {k: pod_labels[k] for k in service["spec"]["selector"]})
                self.assertEqual(service["spec"]["selector"]["app.kubernetes.io/component"], name.removeprefix("county-"))
                self.assertEqual([p["port"] for p in service["spec"]["ports"]], [port])

    def test_postgres_has_one_ten_gi_longhorn_claim(self):
        claims = find(objects(), "StatefulSet", "county-postgres")["spec"]["volumeClaimTemplates"]
        self.assertEqual(len(claims), 1)
        self.assertEqual(claims[0]["spec"]["storageClassName"], "longhorn")
        self.assertEqual(claims[0]["spec"]["resources"]["requests"]["storage"], "10Gi")
        self.assertEqual(claims[0]["spec"]["accessModes"], ["ReadWriteOnce"])

    def test_the_claim_class_and_size_are_values(self):
        docs = objects("postgres.storageClass=fast", "postgres.size=20Gi")
        claim = find(docs, "StatefulSet", "county-postgres")["spec"]["volumeClaimTemplates"][0]["spec"]
        self.assertEqual((claim["storageClassName"], claim["resources"]["requests"]["storage"]), ("fast", "20Gi"))

    def test_deleting_the_release_keeps_the_claim(self):
        spec = find(objects(), "StatefulSet", "county-postgres")["spec"]
        self.assertEqual(spec["persistentVolumeClaimRetentionPolicy"], {"whenDeleted": "Retain", "whenScaled": "Retain"})


class IngressTest(unittest.TestCase):
    """C2"""

    def ingress(self, *sets):
        return find(objects(*sets), "Ingress", "county")

    def paths(self, ingress):
        rules = ingress["spec"]["rules"]
        self.assertEqual(len(rules), 1)
        return rules[0]["http"]["paths"]

    def test_exactly_two_paths_api_and_web(self):
        paths = self.paths(self.ingress())
        routes = {p["path"]: (p["pathType"], p["backend"]["service"]["name"], p["backend"]["service"]["port"]["number"])
                  for p in paths}
        self.assertEqual(len(paths), 2)
        self.assertEqual(routes, {"/api": ("Prefix", "county-api", 8080), "/": ("Prefix", "county-web", 3000)})

    def test_the_q_endpoints_are_not_routed(self):
        for path in self.paths(self.ingress()):
            self.assertFalse(path["path"].startswith("/q"), path["path"])

    def test_there_is_no_tls_section(self):
        self.assertNotIn("tls", self.ingress()["spec"])

    def test_the_host_is_the_one_in_public_url(self):
        self.assertEqual(self.ingress()["spec"]["rules"][0]["host"], "county.example.ph")
        ingress = self.ingress("publicUrl=https://orders.test.example")
        self.assertEqual(ingress["spec"]["rules"][0]["host"], "orders.test.example")

    def test_the_class_is_nginx_and_a_value(self):
        self.assertEqual(self.ingress()["spec"]["ingressClassName"], "nginx")
        self.assertEqual(self.ingress("ingress.className=traefik")["spec"]["ingressClassName"], "traefik")

    def test_ingress_enabled_false_renders_no_ingress(self):
        self.assertEqual([d for d in objects("ingress.enabled=false") if d["kind"] == "Ingress"], [])

    def test_the_ingress_is_labelled(self):
        labels = self.ingress()["metadata"]["labels"]
        self.assertEqual(labels["app.kubernetes.io/name"], "county")
        self.assertEqual(labels["app.kubernetes.io/component"], "ingress")


class NetworkPolicyTest(unittest.TestCase):
    """C4"""

    INGRESS_NAMESPACE = {"namespaceSelector": {"matchLabels": {"kubernetes.io/metadata.name": "ingress-nginx"}}}

    def pods(self, component):
        return {"podSelector": {"matchLabels": {"app.kubernetes.io/name": "county",
                                                "app.kubernetes.io/instance": "county",
                                                "app.kubernetes.io/component": component}}}

    def policy(self, name, *sets):
        return find(objects(*sets), "NetworkPolicy", name)

    def assertAllows(self, name, peers, port, *sets):
        policy = self.policy(name, *sets)
        self.assertEqual(policy["spec"]["podSelector"], self.pods(name.removeprefix("county-"))["podSelector"])
        self.assertEqual(policy["spec"]["policyTypes"], ["Ingress"])
        self.assertEqual(len(policy["spec"]["ingress"]), 1)
        rule = policy["spec"]["ingress"][0]
        self.assertEqual(rule["from"], peers)
        self.assertEqual(rule["ports"], [{"protocol": "TCP", "port": port}])

    def test_postgres_accepts_5432_from_api_pods_only(self):
        self.assertAllows("county-postgres", [self.pods("api")], 5432)

    def test_api_accepts_8080_from_web_and_the_ingress_namespace(self):
        self.assertAllows("county-api", [self.pods("web"), self.INGRESS_NAMESPACE], 8080)

    def test_web_accepts_3000_from_the_ingress_namespace_only(self):
        self.assertAllows("county-web", [self.INGRESS_NAMESPACE], 3000)

    def test_the_ingress_namespace_is_a_value(self):
        peers = [self.pods("web"), {"namespaceSelector": {"matchLabels": {"kubernetes.io/metadata.name": "traefik"}}}]
        self.assertAllows("county-api", peers, 8080, "networkPolicy.ingressNamespace=traefik")

    def test_the_policies_are_labelled(self):
        for name in ("county-api", "county-web", "county-postgres"):
            labels = self.policy(name)["metadata"]["labels"]
            self.assertEqual(labels["app.kubernetes.io/component"], name.removeprefix("county-"))

    def test_network_policy_disabled_renders_none(self):
        self.assertEqual([d for d in objects("networkPolicy.enabled=false") if d["kind"] == "NetworkPolicy"], [])


if __name__ == "__main__":
    unittest.main()
