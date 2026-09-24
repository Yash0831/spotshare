// SpotShare CI — skeleton for Phase 1.
// Grown phase by phase: Phase 7 adds the frontend build, Phase 9/10 add
// test and Docker-image stages. Builds must run OFFLINE in this environment
// (JVM TCP is blocked), so /root/.m2 is pre-seeded on the build agent.

pipeline {
    agent any

    environment {
        JAVA_HOME = '/opt/jdk'
        PATH = "/opt/jdk/bin:/opt/maven/bin:${env.PATH}"
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }
        stage('Build backend') {
            steps {
                dir('backend') {
                    sh 'mvn -o -B -DskipTests package'
                }
            }
        }
    }

    post {
        always {
            archiveArtifacts artifacts: 'backend/target/*.jar', allowEmptyArchive: true
        }
    }
}
