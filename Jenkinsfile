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
        stage('Load Config') {
            steps {
                script {
                    def props = readProperties file: '/tmp/jenkins_env.properties'
                    env.DOCKER_HOST = props['DOCKER_HOST']
                    env.DOCKER_TLS_VERIFY = props['DOCKER_TLS_VERIFY']
                    env.DOCKER_CERT_PATH = props['DOCKER_CERT_PATH']
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
            parallel {
                stage('Backend Services') {
                    steps {
                        script {
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
                                sh "docker build -t lastmile/${name}:latest -f ${dockerfile} backend/"
                            }
                        }
                    }
                }
                stage('Redis') {
                    steps {
                        sh "docker build -t lastmile/redis:latest backend/"
                    }
                }
                stage('Frontend') {
                    steps {
                        sh "docker build -t lastmile/new-frontend:latest -f frontend/Dockerfile ."
                    }
                }
            }
        }

        stage('Deploy with Ansible') {
            steps {
                // Run the Ansible playbook from the ansible directory to load ansible.cfg
                dir('ansible') {
                    sh 'ansible-playbook playbook.yml -i inventory/hosts.ini'
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
