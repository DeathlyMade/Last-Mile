pipeline {
    agent any

    options {
        disableConcurrentBuilds()
        timestamps()
    }

    environment {
        PATH = "/usr/local/bin:/opt/homebrew/bin:${env.PATH}"
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build & Init') {
            parallel {
                stage('Backend Build') {
                    steps {
                        script {
                            echo "Building Backend Services..."
                            sh '''
                                set -e
                                export MINIKUBE_IN_STYLE=false
                                eval $(minikube -p minikube docker-env)
                                cd backend
                                ./build-all.sh
                            '''
                        }
                    }
                }

                stage('Frontend Init') {
                    steps {
                        script {
                            echo "Generating Protos & Installing Dependencies..."
                            // Run as root inside container to install tools, but chown back to host user at the end
                            sh '''
                                docker run --rm \
                                  -v $(pwd):/workspace \
                                  -w /workspace \
                                  -e HOST_UID=$(id -u) \
                                  -e HOST_GID=$(id -g) \
                                  node:18 \
                                  bash -c "apt-get update && apt-get install -y protobuf-compiler && npm install -g grpc-tools protoc-gen-js && cd frontend && npm install && cd .. && ./generate-proto.sh && chown -R \$(id -u):\$(id -g) ."
                            '''
                        }
                    }
                }
            }
        }

        stage('Package Frontend') {
            steps {
                script {
                    echo "Building Frontend & Redis Images..."
                    sh '''
                        set -e
                        export MINIKUBE_IN_STYLE=false
                        eval $(minikube -p minikube docker-env)
                        
                        echo "Building Redis..."
                        docker build -t lastmile/redis:latest backend/
                        
                        echo "Building Frontend..."
                        docker build -t lastmile/new-frontend:latest -f frontend/Dockerfile .
                    '''
                }
            }
        }

        stage('Deploy') {
            steps {
                script {
                    echo "Deploying via Ansible..."
                    sh 'ansible-playbook -i ansible/inventory/hosts.ini ansible/playbook.yml'
                }
            }
        }
    }

    post {
        success {
            mail to: 'nathanmathewv@gmail.com',
                 subject: "Pipeline Success: ${currentBuild.fullDisplayName}",
                 body: "Build, Test, and Deployment succeeded!\n\nCheck console output."
        }
        failure {
            mail to: 'nathanmathewv@gmail.com',
                 subject: "Pipeline Failed: ${currentBuild.fullDisplayName}",
                 body: "Pipeline failed during ${env.STAGE_NAME}.\n\nCheck Jenkins logs for details."
        }
    }
}