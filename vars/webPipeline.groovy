void call(Map config = [:]) {
    String agentName = (config.agent != null) ? config.agent : 'web-agent'
    pipeline {
        agent { label "web-agent" }
        options {
            disableResume()
            buildDiscarder(logRotator(numToKeepStr: '3'))
            timeout(time: 1, unit: 'HOURS')
        }

        environment {
            GIT_SSL_NO_VERIFY=true
            HTTP_PROXY="http://proxy:8443"
            HTTPS_PROXY="http://proxy:8443"
            NO_PROXY="localhost"
            CONTAINER_IMAGE="private.registry.io/${config.name}"
            BUILD_IMAGE="private.registry.io/node:lts-alpine"
            BASE_IMAGE="private.registry.io/nginx:stable-alpine"
            DISABLE_LINT=false
            DISABLE_TEST=false
            DISABLE_PACKAGE=false
            
        }

        stages {

            stage('Lint') {
                agent { label "web-agent" }
                options { skipDefaultCheckout() }
                when {
                    expression {
                        (env.DISABLE_LINT != "true")
                    }
                }
                steps {
                    echo 'Linting..'
                    container('nodejs') {
                        catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                            sh 'mkdir -p .cache/node'
                            sh "npm config set prefix '${WORKSPACE}/.cache/node'"
                            sh 'npm i'
                            sh 'npm i -g eslint'
                            sh 'eslint .'
                        }
                    }
                }
            }
        
            stage('Test') {
                agent { label "web-agent" }
                options { skipDefaultCheckout() }
                when {
                    expression {
                        (env.DISABLE_TEST != "true")
                    }
                }
                steps {
                    echo 'Testing..'
                    container('nodejs') {
                        catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                            sh "npm config set prefix '${WORKSPACE}/.cache/node'"
                            sh 'npm test'
                        }
                    }
                }
            }

            stage('Package') {
                agent { label "kaniko-agent" }
                options { skipDefaultCheckout() }
                when {
                    allOf {
                        expression {
                            (env.DISABLE_PACKAGE != "true")
                        }
                        anyOf {
                            tag "sit-*"
                            tag "uat-*"
                        }
                    }
                }
                steps {
                    echo 'Packaging..'
                    container('kaniko') {
                        sh """
/kaniko/executor --context ${WORKSPACE} --dockerfile ${WORKSPACE}/Dockerfile --destination=${CONTAINER_IMAGE}:${env.TAG_NAME.substring(0,3)} \
    --build-arg \"BUILDER_IMAGE=${BUILD_IMAGE}\" --build-arg \"BASE_IMAGE=${BASE_IMAGE}\" \
    --build-arg \"http_proxy=${HTTPS_PROXY}\" --build-arg \"HTTP_PROXY=${HTTPS_PROXY}\" \
    --build-arg \"https_proxy=${HTTPS_PROXY}\" --build-arg \"HTTPS_PROXY=${HTTPS_PROXY}\" \
    --build-arg \"no_proxy=${NO_PROXY}\" --build-arg \"NO_PROXY=${NO_PROXY}\"
                           """
                    }
                }
            }

            stage('Deploy') {
                when {
                    anyOf {
                        tag "sit-*"
                        tag "uat-*"
                    }
                }
                steps {
                    build(job: config.deploy_job,
                          parameters: [
                              [$class: 'StringParameterValue', name: 'DEPLOY_ENV', value: env.TAG_NAME.substring(0,3)],
                              [$class: 'StringParameterValue', name: 'DEPLOY_APP', value: config.name],
                          ],
                          wait: true)
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