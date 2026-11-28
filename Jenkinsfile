// county pipeline (spec 2026-10-31-county-v1-kubernetes-deployment.md section 8, ADR 0006, ADR 0007).
// Every branch builds, tests and pushes images tagged with the commit SHA. Only main deploys.
// Credentials: the registry token is a Kubernetes Secret mounted as a file into the Kaniko container,
// and only the deploy stage runs as the ServiceAccount that can touch the cluster. Nothing here
// holds or prints a secret value. deploy/README.md describes both objects.

def installHelm() {
    sh '''
        set -eu
        wget -q -O /tmp/helm.tgz https://get.helm.sh/helm-v3.19.0-linux-amd64.tar.gz
        echo "a7f81ce08007091b86d8bd696eb4d86b8d0f2e1b9f6c714be62f82f96a594496  /tmp/helm.tgz" | sha256sum -c -
        tar -xzf /tmp/helm.tgz -C /tmp linux-amd64/helm
        mv /tmp/linux-amd64/helm /usr/local/bin/helm
    '''
}

pipeline {
    agent {
        kubernetes {
            yaml '''
apiVersion: v1
kind: Pod
spec:
  containers:
    - name: jdk
      image: eclipse-temurin:21-jdk@sha256:8ad118b146aa92da815b576441acd6b42b38236797ad0b6edc8c200b85bb0734
      command: [cat]
      tty: true
      resources:
        requests: {cpu: "1", memory: 2Gi}
    - name: node
      image: node:22-alpine@sha256:1314fb30ec37592d5845e5a160d522c0e680972aa534549940b8d73bde88481a
      command: [cat]
      tty: true
      resources:
        requests: {cpu: 500m, memory: 1Gi}
    - name: postgres
      image: postgres:17@sha256:97fb53376be3e4fb1a8a61421daa69dcc3bf2fa39bda3259f5245f9a455af1a9
      args: ["-c", "listen_addresses=127.0.0.1"]
      env:
        - {name: POSTGRES_USER, value: county}
        - {name: POSTGRES_DB, value: county_test}
        - {name: POSTGRES_HOST_AUTH_METHOD, value: trust}
    - name: kaniko
      image: ghcr.io/osscontainertools/kaniko:debug@sha256:b3cadc638a5b0a7e8ae7b3b76704821b2b5f191163222da401283541c8fa2d41
      command: [/busybox/cat]
      tty: true
      volumeMounts:
        - {name: registry-config, mountPath: /kaniko/.docker}
    - name: helm
      image: alpine:3.22@sha256:49340b346798875b5592e5d420241f2f9cfba5f47e62ebb1446a1b34734e898f
      command: [cat]
      tty: true
  volumes:
    - name: registry-config
      secret:
        secretName: ghcr-push
        items:
          - {key: config.json, path: config.json}
'''
        }
    }

    options {
        timeout(time: 45, unit: 'MINUTES')
        disableConcurrentBuilds()
    }

    stages {
        stage('Checkout') {
            steps {
                // GIT_COMMIT is the release id for every stage below.
                sh 'echo "$GIT_COMMIT" | grep -Eq "^[0-9a-f]{40}$"'
                echo "release ${env.GIT_COMMIT}"
            }
        }

        stage('api') {
            environment {
                COUNTY_TEST_JDBC_URL = 'jdbc:postgresql://localhost:5432/county_test'
                COUNTY_TEST_DB_USER = 'county'
            }
            steps {
                container('jdk') {
                    dir('api') {
                        // A new schema per run: the tests never clean one. The build also checks api/contract.
                        sh '''
                            bash -c 'for i in $(seq 60); do (echo > /dev/tcp/localhost/5432) 2>/dev/null && exit 0; sleep 1; done; exit 1'
                            COUNTY_TEST_SCHEMA="county_test_$(date +%s)" ./gradlew --no-daemon build
                        '''
                    }
                }
            }
        }

        stage('web') {
            steps {
                container('node') {
                    dir('web') {
                        sh 'npm ci && npm run lint && npm test && npm run build'
                    }
                }
            }
        }

        stage('images') {
            parallel {
                stage('api image') {
                    steps {
                        container('kaniko') {
                            sh '''
                                /kaniko/executor \
                                    --context=dir://$WORKSPACE \
                                    --dockerfile=api/Dockerfile \
                                    --destination=ghcr.io/jasoncalalang/county-api:$GIT_COMMIT \
                                    --cache=true \
                                    --cache-repo=ghcr.io/jasoncalalang/county-api-cache
                            '''
                        }
                    }
                }
                stage('web image') {
                    steps {
                        container('kaniko') {
                            sh '''
                                /kaniko/executor \
                                    --context=dir://$WORKSPACE \
                                    --dockerfile=web/Dockerfile \
                                    --destination=ghcr.io/jasoncalalang/county-web:$GIT_COMMIT \
                                    --cache=true \
                                    --cache-repo=ghcr.io/jasoncalalang/county-web-cache
                            '''
                        }
                    }
                }
            }
        }

        stage('chart') {
            steps {
                container('helm') {
                    script { installHelm() }
                    sh '''
                        helm lint deploy/county -f deploy/values/demo.yaml --set-string image.tag=$GIT_COMMIT
                        helm template county deploy/county -n county-demo -f deploy/values/demo.yaml \
                            --set-string image.tag=$GIT_COMMIT > /dev/null
                    '''
                }
            }
        }

        stage('deploy') {
            when {
                branch 'main'
                beforeAgent true
            }
            // Its own pod: the build and test containers above never run with cluster rights.
            agent {
                kubernetes {
                    yaml '''
apiVersion: v1
kind: Pod
spec:
  serviceAccountName: jenkins-deployer
  containers:
    - name: helm
      image: alpine:3.22@sha256:49340b346798875b5592e5d420241f2f9cfba5f47e62ebb1446a1b34734e898f
      command: [cat]
      tty: true
'''
                }
            }
            steps {
                container('helm') {
                    script { installHelm() }
                    sh '''
                        helm upgrade --install county deploy/county -n county-demo \
                            -f deploy/values/demo.yaml --set-string image.tag=$GIT_COMMIT \
                            --atomic --wait --timeout 5m
                    '''
                }
            }
        }

        stage('smoke') {
            when {
                branch 'main'
                beforeAgent true
            }
            steps {
                container('node') {
                    // Through the public hostname: the NetworkPolicies keep this pod away from
                    // the api and web Services, and --wait already ran their probes.
                    sh '''
                        PUBLIC_URL="$(sed -n 's/^publicUrl: *//p' deploy/values/demo.yaml)"
                        export PUBLIC_URL
                        node -e '
                          async function expect(path, want) {
                            const response = await fetch(process.env.PUBLIC_URL + path, { redirect: "manual" });
                            if (response.status !== want) {
                              console.error(path + " answered " + response.status + ", want " + want);
                              process.exit(1);
                            }
                            console.log(path + " " + response.status);
                          }
                          expect("/", 200).then(() => expect("/api/me", 401));
                        '
                    '''
                }
            }
        }
    }
}
