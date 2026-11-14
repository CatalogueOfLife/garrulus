// CI build for Garrulus (nametracker). Place at the root of the repo.
// Backend builds with the agent's native JDK 25 + Maven; only npm runs in Docker (node:24),
// matching the Blixa job. Build only: no deploy stage yet (added in M2 with the systemd units).
pipeline {
  agent any

  options { timestamps(); disableConcurrentBuilds() }

  // Managed tool names as registered in this Jenkins (same as the other CoL Java jobs).
  // Puts mvn on PATH and pins the build to JDK 25 (the apps need JDK 25).
  tools {
    maven 'Maven 3.9.9'
    jdk 'LibericaJDK25'
  }

  stages {
    stage('Build') {
      parallel {
        stage('Backend') {
          steps {
            // Native JDK 25 + Maven on the agent (no Docker); reuses the agent's ~/.m2 cache.
            // ITs are skipped here (they need Testcontainers); run `mvn -B verify` locally.
            sh 'mvn -B -DskipITs clean verify'
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
                node:24 sh -c "npm ci && npm run build"
            '''
          }
        }
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
