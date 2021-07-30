void call(Map config = [:]) {
    String agentName = (config.agent != null) ? config.agent : 'helm-agent'
    pipeline {
        agent {
            label "${agentName}"
        }

        parameters {
            string(name: 'DEPLOY_ENV', defaultValue: 'sit', description: '')
            string(name: 'DEPLOY_APP', defaultValue: '', description: '')
        }

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
        }

        stages {
            stage('Deploy') {
                when {
                    allOf {
                        expression {
                            (DEPLOY_ENV != "")
                        }
                        expression {
                            (DEPLOY_APP != "")
                        }
                    }
                }
                steps {
                    echo "Deploying....${DEPLOY_APP}@${DEPLOY_ENV}"
                    container("helm") {
                        sh "kubectl config use-context ${DEPLOY_ENV}"
                        sh "helm delete ${DEPLOY_APP} -n ${DEPLOY_ENV}  || true"
                        sleep 30
                        sh "helm install ${DEPLOY_APP} -n ${DEPLOY_ENV} -f ${DEPLOY_ENV}/${DEPLOY_APP}.yaml ./charts/generics"
                        sleep 20
                        sh "kubectl get po -n ${DEPLOY_ENV} | grep ${DEPLOY_APP}"
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