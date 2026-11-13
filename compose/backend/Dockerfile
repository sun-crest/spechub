# SpecHub backend 镜像：多阶段构建，产物仅 fatjar 进运行镜像。
# 运行镜像内嵌系统 git CLI（git-store 以系统 git 子进程驱动，见 §7.3），JRE 25 长驻。
# ── 构建源：仓库根（compose build context = ..，见 docker-compose.yml）──

FROM gradle:9.9.1-jdk25 AS builder
WORKDIR /src

# 先拷版本目录与构建脚本，最大化 BuildKit 层缓存（依赖变更只影响依赖层）
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY modules modules
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle :server:bootJar --no-daemon -p /src

FROM eclipse-temurin:25-jre
# git-store 以系统 git 子进程驱动，运行镜像必须带 git；curl 供 compose healthcheck
RUN apt-get update \
    && apt-get install -y --no-install-recommends git ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=builder /src/modules/server/build/libs/spechub-server.jar /app/spechub.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseZGC", "-jar", "/app/spechub.jar"]