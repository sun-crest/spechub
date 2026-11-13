# SpecHub backend 镜像：多阶段、分卷缓存；builder 阶段用 Gradle 9.9.1 + JDK25，产物仅 jar 进运行时
# 注：网络依赖走仓库内 settings 的阿里/华为镜像（Maven Central 403 已规避）
FROM gradle:9.9.1-jdk25 AS builder
WORKDIR /src
# 先拷版本目录与构建脚本，利用 BuildKit 缓存最大化层复用
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts gradle/libs.versions.toml ./
COPY modules/server modules/server
COPY modules/kernel modules/kernel
RUN --mount=type=cache,target=/root/.gradle gradle :server:bootJar --no-daemon -p /src

FROM eclipse-temurin:25-jre
# git-store 以系统 git 子进程驱动，运行时必须带 git；apt 装最小集（git、cacert 已含）
RUN apt-get update \
    && apt-get install -y --no-install-recommends git ca-certificates \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=builder /src/modules/server/build/libs/server-*.jar /app/spechub.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/spechub.jar"]