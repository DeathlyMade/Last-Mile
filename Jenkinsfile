pipeline {
    agent any

    environment {
        KUBECONFIG = '/tmp/kubeconfig'
        // PATH setup: Includes /opt/homebrew/bin (Apple Silicon) and /usr/local/bin (Intel Mac/Linux)
        PATH = "/usr/local/bin:/opt/homebrew/bin:${env.PATH}"
    }

    stages {
        stage('Checkout') {
            steps {
                // Clean workspace to remove any root-owned files from previous builds
                cleanWs()
                // Checkout from version control
                // gitscmpoll requires a standard checkout or the 'git' step
                checkout scm
            }
        }
        stage('Load Config') {
            steps {
                script {
                    env.MK_DOCKER_HOST = sh(script: "grep DOCKER_HOST /tmp/jenkins_env.properties | cut -d'=' -f2", returnStdout: true).trim()
                    env.MK_DOCKER_TLS_VERIFY = sh(script: "grep DOCKER_TLS_VERIFY /tmp/jenkins_env.properties | cut -d'=' -f2", returnStdout: true).trim()
                    env.MK_DOCKER_CERT_PATH = sh(script: "grep DOCKER_CERT_PATH /tmp/jenkins_env.properties | cut -d'=' -f2", returnStdout: true).trim()
                }
            }
        }

        stage('Generate Protos') {
            steps {
                sh '''
                    docker run --rm \
                    -v "$(pwd):/workspace" \
                    -w /workspace \
                    node:18-bullseye \
                    /bin/bash -c "apt-get update && apt-get install -y protobuf-compiler && cd frontend && npm install && cd .. && ./generate-proto.sh && chown -R $(id -u):$(id -g) frontend/src/proto proto.pb frontend/node_modules"
                '''
                // Apply the proto config map
                sh '''
                    if [ -f "proto.pb" ]; then
                        kubectl create configmap proto-config --from-file=proto.pb=proto.pb --dry-run=client -o yaml | kubectl apply --validate=false -f -
                    fi
                '''
            }
        }

        stage('Build Images') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'dockerhub-cred', usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')]) {
                    withEnv([
                        "DOCKER_HOST=${env.MK_DOCKER_HOST}",
                        "DOCKER_TLS_VERIFY=${env.MK_DOCKER_TLS_VERIFY}",
                        "DOCKER_CERT_PATH=${env.MK_DOCKER_CERT_PATH}"
                    ]) {
                        script {
                            parallel backend: {
                                def services = [
                                    'station-service': 'backend/Dockerfile.station',
                                    'user-service': 'backend/Dockerfile.user',
                                    'driver-service': 'backend/Dockerfile.driver',
                                    'rider-service': 'backend/Dockerfile.rider',
                                    'location-service': 'backend/Dockerfile.location',
                                    'matching-service': 'backend/Dockerfile.matching',
                                    'trip-service': 'backend/Dockerfile.trip',
                                    'notification-service': 'backend/Dockerfile.notification'
                                ]
                                services.each { name, dockerfile ->
                                    sh "docker build -t ${env.DOCKER_USER}/${name}:latest -f ${dockerfile} backend/"
                                }
                            }, redis: {
                                sh "docker build -t ${env.DOCKER_USER}/redis:latest backend/"
                            }, frontend: {
                                sh "docker build -t ${env.DOCKER_USER}/new-frontend:latest -f frontend/Dockerfile ."
                            }
                        }
                    }
                }
            }
        }

        stage('Push Images') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'dockerhub-cred', usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')]) {
                    withEnv([
                        "DOCKER_HOST=${env.MK_DOCKER_HOST}",
                        "DOCKER_TLS_VERIFY=${env.MK_DOCKER_TLS_VERIFY}",
                        "DOCKER_CERT_PATH=${env.MK_DOCKER_CERT_PATH}"
                    ]) {
                        sh 'echo $DOCKER_PASS | docker login -u $DOCKER_USER --password-stdin'
                        script {
                            def images = [
                                'station-service', 'user-service', 'driver-service',
                                'rider-service', 'location-service', 'matching-service',
                                'trip-service', 'notification-service', 'redis', 'new-frontend'
                            ]
                            images.each { name ->
                                sh "docker push ${env.DOCKER_USER}/${name}:latest"
                            }
                        }
                    }
                }
            }
        }

        stage('Deploy with Ansible') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'dockerhub-cred', usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')]) {
                    // Run the Ansible playbook from the ansible directory to load ansible.cfg
                    dir('ansible') {
                        sh "ansible-playbook playbook.yml -i inventory/hosts.ini -e 'docker_user=${DOCKER_USER}'"
                    }
                }
            }
        }
    }

    post {
        success {
            mail to: 'divyamsareen@gmail.com',
                 subject: "Build Success: ${env.JOB_NAME} #${env.BUILD_NUMBER}",
                 body: "The build finished successfully.\nSee: ${env.BUILD_URL}"
        }
        failure {
            mail to: 'divyamsareen@gmail.com',
                 subject: "Build Failed: ${env.JOB_NAME} #${env.BUILD_NUMBER}",
                 body: "The build failed.\nSee: ${env.BUILD_URL}"
        }
    }
}
