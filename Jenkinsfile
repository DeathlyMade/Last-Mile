pipeline {
    agent any

    environment {
        KUBECONFIG = '/tmp/kubeconfig'
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

        stage('Deploy with Ansible') {
            steps {
                // Run the Ansible playbook from the ansible directory to load ansible.cfg
                dir('ansible') {
                    sh 'ansible-playbook playbook.yml -i inventory/hosts.ini'
                }
            }
        }
    }
}
