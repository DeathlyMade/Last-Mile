pipeline {
    agent any

    options {
        disableConcurrentBuilds()
        timestamps()
    }

    environment {
        // PATH setup: Includes /opt/homebrew/bin (Apple Silicon) and /usr/local/bin (Intel Mac/Linux)
        PATH = "/usr/local/bin:/opt/homebrew/bin:${env.PATH}"
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
                    echo "Generating Protos..."
                    sh 'chmod +x ./generate-proto.sh'
                    sh './generate-proto.sh'
                }
            }
        }

        stage('Build Parallel') {
            steps {
                script {
                    // Define map of parallel builds
                    def builds = [:]

                    // Helper to create build step
                    def createBuild = { name, dockerfile ->
                        return {
                            stage("Build ${name}") {
                                sh """
                                    eval \$(minikube -p minikube docker-env)
                                    docker build -t lastmile/${name}:latest -f backend/${dockerfile} backend
                                """
                            }
                        }
                    }

                    builds['Station'] = createBuild('station-service', 'Dockerfile.station')
                    builds['User'] = createBuild('user-service', 'Dockerfile.user')
                    builds['Driver'] = createBuild('driver-service', 'Dockerfile.driver')
                    builds['Rider'] = createBuild('rider-service', 'Dockerfile.rider')
                    builds['Location'] = createBuild('location-service', 'Dockerfile.location')
                    builds['Matching'] = createBuild('matching-service', 'Dockerfile.matching')
                    builds['Trip'] = createBuild('trip-service', 'Dockerfile.trip')
                    builds['Notification'] = createBuild('notification-service', 'Dockerfile.notification')
                    
                    // Frontend and Redis can also run in parallel
                    builds['Redis'] = {
                        stage('Build Redis') {
                             sh """
                                eval \$(minikube -p minikube docker-env)
                                docker build -t lastmile/redis:latest backend/
                             """
                        }
                    }
                    
                    builds['Frontend'] = {
                        stage('Build Frontend') {
                             sh """
                                eval \$(minikube -p minikube docker-env)
                                docker build -t lastmile/new-frontend:latest -f frontend/Dockerfile .
                             """
                        }
                    }

                    parallel builds
                }
            }
        }

        stage('Deploy') {
            steps {
                script {
                    echo "Deploying to Kubernetes via Ansible..."
                    sh 'ansible-playbook -i ansible/inventory/hosts.ini ansible/playbook.yml'
                }
            }
        }

        stage('Port Forward Services') {
            steps {
                script {
                    echo "Setting up port forwarding..."
                    withEnv(['JENKINS_NODE_COOKIE=dontKillMe', 'BUILD_ID=dontKillMe']) {
                        // Kill existing port-forwards to avoid conflicts
                        sh "pkill -f 'kubectl.*port-forward' || true"

                        // Start new port-forwards (Gateway & Frontend)
                        // Note: Using & to run in background, nohup ensures they survive pipeline exit
                        sh "nohup minikube kubectl -- port-forward svc/lastmile-gateway 8080:8080 --address 0.0.0.0 > /dev/null 2>&1 &"
                        sh "nohup minikube kubectl -- port-forward svc/frontend 3000:3000 --address 0.0.0.0 > /dev/null 2>&1 &"
                        
                        echo "Port forwarding started: Gateway (8080), Frontend (3000)"
                    }
                }
            }
        }
    }

    post {
        success {
            mail to: 'nathanmathewv@gmail.com',
                 subject: "Pipeline Success: ${currentBuild.fullDisplayName}",
                 body: "Build (Parallel) + Deploy + Port Forwarding succeeded!\n\nCheck console output."
        }
        failure {
            mail to: 'nathanmathewv@gmail.com',
                 subject: "Pipeline Failed: ${currentBuild.fullDisplayName}",
                 body: "Pipeline failed during ${env.STAGE_NAME}.\n\nCheck Jenkins logs for details."
        }
    }
}