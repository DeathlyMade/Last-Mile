pipeline {
    agent any

    stages {
        stage('Checkout') {
            steps {
                // Checkout from version control
                // gitscmpoll requires a standard checkout or the 'git' step
                checkout scm
            }
        }

        stage('Deploy with Ansible') {
            steps {
                // Run the Ansible playbook
                // Assumes ansible-playbook is available in the path
                sh 'ansible-playbook ansible/playbook.yml -i ansible/inventory/hosts.ini'
            }
        }
    }
}
