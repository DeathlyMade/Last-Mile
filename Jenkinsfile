pipeline {
    agent any

    environment {
        PATH = "/usr/local/bin:/opt/homebrew/bin:${env.PATH}"
    }

    triggers {
        githubPush()
    }

    options {
        disableConcurrentBuilds()
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Prepare') {
            steps {
                script {
                    echo "Installing Frontend Dependencies and Generating Protos (using Node.js 18)..."
                    sh '''
                        docker run --rm \
                          -v $(pwd):/workspace \
                          -w /workspace \
                          -e HOST_UID=$(id -u) \
                          -e HOST_GID=$(id -g) \
                          node:18 \
                          bash -c "apt-get update && apt-get install -y protobuf-compiler && npm install -g grpc-tools && cd frontend && npm install && cd .. && ./generate-proto.sh && chown -R \$(id -u):\$(id -g) ."
                    '''
                }
            }
        }

        stage('Build & Test (Backend)') {
            steps {
                script {
                    echo "Building and Testing Backend Services..."
                    // Connect to Minikube Docker Daemon and run build script (which runs unit tests)
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

        stage('Build Frontend & Redis') {
            steps {
                script {
                    echo "Building Frontend and Redis..."
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
                    echo "Deploying to Kubernetes via Ansible..."
                    // Ansible runs configuration and applies manifests
                    // We expect Ansible to return success (0) or failure (nonzero)
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
