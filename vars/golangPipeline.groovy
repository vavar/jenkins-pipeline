void call(Map config) {
    String agentName = (config.agent != null) ? config.agent : 'golang-agent'
    String deploymentJob = (config.deploy_job != null) ? config.deploy_job : '../deployments/master'
    Map batchHosts = [
        'sit' : [ host: 'user@sit', path: 'batch/sit' ],
        'uat' : [ host: 'user@uat', path: 'batch/uat' ],
    ]
    pipeline {
        agent { label "golang-agent" }
        options {
            disableResume()
            buildDiscarder(logRotator(numToKeepStr: '3'))
            timeout(time: 1, unit: 'HOURS')
        }
        environment {
            GIT_SSL_NO_VERIFY=true
            GOCACHE="${WORKSPACE}/.cache/cache"
            GOPATH="${WORKSPACE}/.cache/go"
            CGO_ENABLED="0"
            GOOS="linux"
            GOARCH="amd64"
            GOLANGCI_LINT_CACHE="${WORKSPACE}/.cache/linter"
            CONTAINER_IMAGE="private.registry.io/${config.name}"
            BUILD_IMAGE="private.registry.io/builder:1.15"
            BASE_IMAGE="private.registry.io/base:3.12"
        }

        stages {
            stage('Lint') {
                options { skipDefaultCheckout() }
                agent { label "golanglint-agent" }
                steps {
                    echo 'Linting..'
                    container('linter') {
                        catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                            sh 'mkdir -p .cache/go'
                            sh 'mkdir -p .cache/cache'
                            sh 'mkdir -p .cache/linter'
                            sh "golangci-lint run -v --color always --max-issues-per-linter 10 --timeout 5m ./..."
                        }
                    }
                }
            }
        
            stage('Test') {
                options { skipDefaultCheckout() }
                agent { label "golang-agent" }
                steps {
                    echo 'Testing..'
                    container('golang') {
                        sh "go test -failfast -coverprofile coverage.out ./..."
                        sh "go tool cover -func=coverage.out"
                    }
                }
            }

            stage('Package') {
                when {
                    allOf {
                        expression {
                            (env.DISABLE_PACKAGE != "true")
                        }
                        anyOf {
                            tag "dev-*"
                            tag "sit-*"
                            tag "uat-*"
                        }
                    }
                }
                parallel {
                    stage('Package Container') {
                        agent { label "kaniko-agent" }
                        options { skipDefaultCheckout() }
                        steps {
                            echo 'Packaging..'
                            container('kaniko') {
                                sh """
/kaniko/executor --context=${WORKSPACE} --dockerfile=${WORKSPACE}/Dockerfile --destination=${CONTAINER_IMAGE}:${env.TAG_NAME.substring(0,3)} \
    --build-arg \"GOCACHE=/app/.cache/cache\" --build-arg \"CACHE_GOPATH=/app/.cache/go\" \
    --build-arg \"BUILDER_IMAGE=${BUILD_IMAGE}\" --build-arg \"BASE_IMAGE=${BASE_IMAGE}\" \
    --build-arg \"http_proxy=${HTTPS_PROXY}\" --build-arg \"HTTP_PROXY=${HTTPS_PROXY}\" \
    --build-arg \"https_proxy=${HTTPS_PROXY}\" --build-arg \"HTTPS_PROXY=${HTTPS_PROXY}\" \
    --build-arg \"no_proxy=${NO_PROXY}\" --build-arg \"NO_PROXY=${NO_PROXY}\"
                            """
                            }
                        }
                    }
                    stage('Package Binary') {
                        when {
                            anyOf {
                                tag "sit-*"
                                tag "uat-*"
                            }
                        }
                        agent { label "golang-agent" }
                        options { skipDefaultCheckout() }
                        steps {
                            container('golang') {
                                script {
                                    def deployEnv = env.TAG_NAME.substring(0,3)
                                    sh 'go env'
                                    sh 'mkdir -p .dist/batchjob && go build -ldflags="-w -s" -o .dist/batchjob/batchjob ./cmd/batch/batchjob'
                                    sh "cp scripts/${deployEnv}/env.sh .dist/env.sh"
                                    sh """sed -e "2 a cd batch/${deployEnv}" -e '2 a source env.sh' scripts/batchjob.sh > .dist/batchjob.sh"""
                                    sh "chmod +x .dist/*.sh"
                                }
                            }
                        }
                    }
                }
                
            }

            stage('Deploy') {
                when {
                    anyOf {
                        tag "dev-*"
                        tag "sit-*"
                        tag "uat-*"
                    }
                }
                parallel {
                    stage ('Deploy K8s') {
                        steps {
                            echo "Deploying....K8s - job: ${deploymentJob}"
                            build(job: deploymentJob,
                                parameters: [
                                    [$class: 'StringParameterValue', name: 'DEPLOY_ENV', value: env.TAG_NAME.substring(0,3)],
                                    [$class: 'StringParameterValue', name: 'DEPLOY_APP', value: config.name],
                                ],
                                wait: true)
                        }
                    }
                    stage ('Deploy Binary') {
                        when {
                            anyOf {
                                tag "sit-*"
                                tag "uat-*"
                            }
                        }
                        agent { label "helm-agent" }
                        options { skipDefaultCheckout() }
                        steps {
                            echo "Deploying....Binary"
                            script {
                                def deployEnv = env.TAG_NAME.substring(0,3)
                                def target = batchHosts[deployEnv]
                                echo "deploy to ${target.host} - ${target.path}"
                                withCredentials([sshUserPrivateKey(credentialsId: "${deployEnv}", keyFileVariable: 'keyfile')]) {
                                    sh """
                                        scp -o StrictHostKeyChecking=no -i ${keyfile} -rp .dist/* ${target.host}:${target.path}
                                    """

                                }
                            }
                        }
                    }
                }
            }

        }

        post {
            cleanup {
                cleanWs cleanWhenFailure: true, cleanWhenAborted: true
            }
        }
    }
}