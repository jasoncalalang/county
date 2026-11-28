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
