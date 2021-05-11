import groovy.json.JsonSlurper

# You must setup a few parameter for jenkins job: BUILD_CLEAN, RUN_TESTS, RUN_MIGRATION, AWS_SECRET_ID
# AWS_SECRET_ID - This secret contain a few parameters for job: jenkins_environment, jenkins_project_name, jenkins_image_backend, jenkins_region, jenkins_ecs_cluster, jenkins_ecs_taskmigrations

node {
  vaultResponse = sh(returnStdout: true, script: "docker run --rm amazon/aws-cli secretsmanager get-secret-value --secret-id ${env.AWS_SECRET_ID} --query SecretString --output json").trim()
  credentials = readJSON text: new JsonSlurper().parseText(vaultResponse)

  stage('cleanws') {
    if ("${env.BUILD_CLEAN}" == 'true') {
      cleanWs()
    }
  }

  stage('scm') {
    checkout(scm)
  }

  stage('build:backend') {
    sh("docker build -f docker/Dockerfile.base --build-arg PHP_ENV=${credentials.jenkins_environment} --tag ${credentials.jenkins_project}:base .")
    sh("docker build -f docker/Dockerfile --build-arg PHP_ENV=${credentials.jenkins_environment} --tag ${credentials.jenkins_image_backend} .")
  }


  stage('tests') {
    if ("${RUN_TESTS}" == 'true') {
      wrap([$class: 'AnsiColorBuildWrapper', 'colorMapName': 'XTerm']) {
        sh"docker-compose -f docker/testing_develop.yml -p tests run --rm tests"
        sh"docker-compose -f docker/testing_develop.yml -p tests down"
      }
    }
  }

  stage('ecr') {
    sh(script: "docker run --rm amazon/aws-cli ecr --region ${credentials.} get-login-password \
      | docker login -u AWS --password-stdin https://${credentials.jenkins_image_backend}", returnStdout: true)
    sh(script: "docker push ${credentials.jenkins_image_backend}")
  }

  stage('migrations') {
    if ("${RUN_MIGRATION}" == 'true') {
      taskId = sh(script: "docker run --rm amazon/aws-cli ecs run-task \
      --region ${credentials.jenkins_region} \
      --cluster ${credentials.jenkins_ecs_cluster} \
      --task-definition ${credentials.jenkins_ecs_taskmigrations} \
      --launch-type FARGATE \
      --network-configuration 'awsvpcConfiguration={subnets=[subnet-1,subnet-2,subnet-3],securityGroups=[sg-01],assignPublicIp=ENABLED}' \
      --count 1 \
      --started-by jenkins \
      --output text \
      --query tasks[0].containers[0].taskArn", returnStdout: true).trim()

      endTimer = System.currentTimeMillis() + 300000;
      while(true) {
        sleep(10)
        exitCode = sh(script: "docker run --rm amazon/aws-cli ecs describe-tasks \
        --region ${credentials.jenkins_region} \
        --cluster ${credentials.jenkins_ecs_cluster} \
        --tasks '${taskId}' \
        --output text \
        --query tasks[0].containers[0].exitCode", returnStdout: true).trim()
        if (exitCode == "0") {
          break;
        }
        if(System.currentTimeMillis() > endTimer) {
          currentBuild.result = 'FAILURE'
          return;
        }
      }
    }
  }

  stage('deploy:backend') {
    sh("docker run --rm amazon/aws-cli ecs update-service \
      --region ${credentials.jenkins_regions} \
      --cluster ${credentials.jenkins_ecs_cluster} \
      --service ${credentials.jenkins_ecs_service_backend} \
      --force-new-deployment")
  }

}
