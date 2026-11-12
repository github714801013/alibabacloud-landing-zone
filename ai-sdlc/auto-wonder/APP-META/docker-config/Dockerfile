# syntax=docker/dockerfile:1
FROM maven:3.9.9-eclipse-temurin-21 AS build

# npm 包走国内镜像，覆盖 frontend/.npmrc 固定的 registry.npmjs.org，避免 npm ci 空闲超时。
ENV npm_config_registry=https://registry.npmmirror.com \
    npm_config_replace_registry_host=always

# 国内镜像源：Maven 走阿里云，Node/npm 由下方构建参数指向 npmmirror。
RUN printf '%s\n' \
    '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"><mirrors><mirror>' \
    '<id>aliyun</id><mirrorOf>*</mirrorOf>' \
    '<url>https://maven.aliyun.com/repository/public</url>' \
    '</mirror></mirrors></settings>' > /opt/maven-settings.xml

WORKDIR /workspace
# 依赖层：只复制依赖描述，依赖未变时复用缓存；构建阶段跳过测试。
COPY pom.xml ./
COPY frontend/package.json frontend/package-lock.json ./frontend/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -s /opt/maven-settings.xml -DskipGitCommitId=true -DskipTests -DskipFrontend=true \
        dependency:go-offline || true

COPY frontend ./frontend
COPY src ./src
COPY docs ./docs
COPY scripts ./scripts
COPY APP-META ./APP-META
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=cache,target=/workspace/target/npm-cache \
    mvn -B -s /opt/maven-settings.xml -DskipGitCommitId=true -DskipTests \
        -Dnode.download.root=https://npmmirror.com/mirrors/node/ \
        -Dnpm.download.root=https://registry.npmmirror.com/npm/-/ \
        package

FROM eclipse-temurin:21-jre-jammy@sha256:468586c92d39f8cbad76574623db3fe001625ed4d895431c3ff7bd2ec9ce7ae3

RUN groupadd --system autowonder && \
    useradd --system --gid autowonder --home /app autowonder && \
    install -d -o autowonder -g autowonder /app/logs

WORKDIR /app
COPY --from=build /workspace/target/auto-wonder.jar /app/auto-wonder.jar

USER autowonder
EXPOSE 7001

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/auto-wonder.jar"]
