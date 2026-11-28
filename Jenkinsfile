// CI build and deploy of Garrulus to https://garrulus.app.
// Backend builds with the agent's native JDK 25 + Maven (its tests use Docker via Testcontainers); npm runs in Docker (node:24),
// matching the Blixa job. Deploy = rsync over ssh as jenkins-deploy + systemd restart, like Blixa. The units, the env
// file, the vhost and the VM setup are in the private deploy repo, folder garrulus/ (its README.md).
pipeline {
  agent any

  parameters {
    choice(name: 'TARGET', choices: ['none', 'all', 'web', 'indexer'],
           description: 'none = build only; all = indexer, web and SPA; web = web and SPA; indexer = indexer only')
  }

  options { timestamps(); disableConcurrentBuilds() }

  // Managed tool names as registered in this Jenkins (same as the other CoL Java jobs).
  // Puts mvn on PATH and pins the build to JDK 25 (the apps need JDK 25).
  tools {
    maven 'Maven 3.9.9'
    jdk 'LibericaJDK25'
  }

  environment {
    DEPLOY_USER = 'jenkins-deploy'
    // The GBIF VM that serves garrulus.app (ssh host of jenkins-deploy).
    DEPLOY_HOST = 'garrulus.checklistbank.org'
    APP_DIR = '/home/col/bin/garrulus'
    APP_DOCROOT = '/var/www/html/garrulus'
  }

  stages {
    stage('Build') {
      parallel {
        stage('Backend') {
          steps {
            // Native JDK 25 + Maven on the agent; reuses the agent's ~/.m2 cache.
            // The ITs run here too: Testcontainers starts postgres:19 through the agent's Docker daemon
            // (the Frontend stage needs Docker as well). If the daemon is not at the default socket, set
            // DOCKER_HOST (and TESTCONTAINERS_HOST_OVERRIDE if the containers are not reachable on localhost).
            sh 'mvn -B clean verify'
          }
        }
        stage('Frontend SPA') {
          steps {
            // npm in Docker, like Blixa. Do NOT set NODE_ENV=production:
            // that makes `npm ci` skip devDependencies (typescript/vite), and the build needs
            // `tsc`/`vite`. `vite build` still emits a production bundle regardless of NODE_ENV.
            sh '''
              docker run --rm --user "$(id -u)" \
                -v "$PWD":/usr/src/app -w /usr/src/app/frontend \
                -e npm_config_cache=/usr/src/app/frontend/.npm \
                -e GIT_COMMIT \
                node:24 sh -c "npm ci && npm run build"
            '''
          }
        }
      }
    }

    stage('Deploy') {
      // A single-branch job builds only main (`when { branch }` never matches outside Multibranch). params.TARGET is
      // null on the first run of the job, before Jenkins knows the parameter.
      when { expression { (params.TARGET ?: 'none') != 'none' } }
      steps {
        sh '''
          set -eu
          [ -n "$DEPLOY_HOST" ] || { echo "ERROR: DEPLOY_HOST is not set in the Jenkinsfile"; exit 1; }
          SSHOPT="-o StrictHostKeyChecking=accept-new"
          SSH="ssh $SSHOPT ${DEPLOY_USER}@${DEPLOY_HOST}"

          echo "== Refresh the deploy checkout (units, garrulus.env, vhost) =="
          $SSH 'sudo -u col git -C /home/col/bin pull --ff-only'
          if $SSH "sudo -u col grep -n CHANGE_ME $APP_DIR/garrulus.env"; then
            echo "ERROR: placeholders left in garrulus.env -- refusing to deploy"; exit 1
          fi

          # deploy <app> <port>: jar -> staging -> install as col -> restart -> health check on loopback
          deploy() {
            app=$1; port=$2
            jar=$(ls "$app"/target/garrulus-"$app"-*.jar)
            echo "== $app: $jar =="
            rsync -t -e "ssh $SSHOPT" "$jar" "${DEPLOY_USER}@${DEPLOY_HOST}:/tmp/garrulus-$app.jar"
            $SSH "sudo -u col install -m 644 /tmp/garrulus-$app.jar $APP_DIR/garrulus-$app.jar && rm -f /tmp/garrulus-$app.jar"
            $SSH "sudo /usr/bin/systemctl restart col-garrulus-$app.service"
            # A standalone command: under set -e a failed curl inside an && list would pass unnoticed.
            $SSH "curl -fsS --retry 40 --retry-delay 3 --retry-connrefused http://127.0.0.1:$port/actuator/health"
            echo
          }

          # The indexer first: it applies the Flyway migrations that web validates.
          case "$TARGET" in all|indexer) deploy indexer 8121 ;; esac
          case "$TARGET" in all|web) deploy web 8120 ;; esac

          case "$TARGET" in all|web)
            echo "== SPA -> ${DEPLOY_HOST}:${APP_DOCROOT} =="
            rsync -rt --delete -e "ssh $SSHOPT" frontend/dist/ "${DEPLOY_USER}@${DEPLOY_HOST}:${APP_DOCROOT}/"
            curl -fsS https://garrulus.app/api/ping
            echo
          ;; esac
        '''
      }
    }
  }

  post {
    success {
      archiveArtifacts artifacts: 'indexer/target/*.jar, web/target/*.jar, frontend/dist/**',
                       fingerprint: true, allowEmptyArchive: false
    }
  }
}
