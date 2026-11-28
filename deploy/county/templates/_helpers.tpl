{{- define "county.tag" -}}
{{- $tag := required "image.tag is required: pass the 40-character commit SHA with --set image.tag=<sha>" .Values.image.tag | toString -}}
{{- if not (regexMatch "^[0-9a-f]{40}$" $tag) -}}
{{- fail (printf "image.tag must be a 40-character lowercase hex commit SHA, got %q" $tag) -}}
{{- end -}}
{{- $tag -}}
{{- end -}}

{{- define "county.publicUrl" -}}
{{- $url := required "publicUrl is required, for example https://county.example.ph" .Values.publicUrl | toString -}}
{{- if not (hasPrefix "https://" $url) -}}
{{- fail (printf "publicUrl must start with https://, got %q" $url) -}}
{{- end -}}
{{- $url -}}
{{- end -}}

{{- define "county.image" -}}
{{- printf "%s/%s:%s" .root.Values.image.registry .name (include "county.tag" .root) -}}
{{- end -}}

{{/* Labels that identify one component's pods. Selectors use exactly these. */}}
{{- define "county.labels" -}}
app.kubernetes.io/name: county
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end -}}

{{/* An environment variable read from a key of an existing Secret. */}}
{{- define "county.secretEnv" -}}
- name: {{ .name }}
  valueFrom:
    secretKeyRef:
      name: {{ .secret }}
      key: {{ .key }}
{{- end -}}

{{/* Container security settings shared by api and web. */}}
{{- define "county.hardenedContainer" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
capabilities:
  drop: ["ALL"]
seccompProfile:
  type: RuntimeDefault
{{- end -}}

{{/* The Service of one component, named county-<component>; headless for the StatefulSet. */}}
{{- define "county.service" -}}
{{- $labels := include "county.labels" (dict "root" .root "component" .component) -}}
apiVersion: v1
kind: Service
metadata:
  name: county-{{ .component }}
  labels:
    {{- $labels | nindent 4 }}
spec:
  {{- if .headless }}
  clusterIP: None
  {{- end }}
  selector:
    {{- $labels | nindent 4 }}
  ports:
    - name: {{ .portName }}
      port: {{ .port }}
      targetPort: {{ .portName }}
{{- end -}}

{{/* A peer matching the pods of one component. */}}
{{- define "county.podPeer" -}}
podSelector:
  matchLabels:
    {{- include "county.labels" . | nindent 4 }}
{{- end -}}

{{/* A peer matching every pod of the ingress controller's namespace. */}}
{{- define "county.ingressNamespacePeer" -}}
namespaceSelector:
  matchLabels:
    kubernetes.io/metadata.name: {{ .Values.networkPolicy.ingressNamespace }}
{{- end -}}

{{/* Admits TCP traffic to one component's pods from the listed peers (rendered YAML maps) and nothing else. */}}
{{- define "county.networkPolicy" -}}
{{- $labels := include "county.labels" (dict "root" .root "component" .component) -}}
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: county-{{ .component }}
  labels:
    {{- $labels | nindent 4 }}
spec:
  podSelector:
    matchLabels:
      {{- $labels | nindent 6 }}
  policyTypes: [Ingress]
  ingress:
    - from:
        {{- range .from }}
        - {{ . | indent 10 | trim }}
        {{- end }}
      ports:
        - protocol: TCP
          port: {{ .port }}
{{- end -}}
