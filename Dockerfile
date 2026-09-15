# Pinned build environment for reproducing app-release-unsigned.apk (the OFFLINE flavor;
# the room flavor pulls MediaPipe models and is not the reproducibility target).
#
#   docker build -t attestable-recorder-build .
#   docker run --rm -v "$PWD/out:/out" attestable-recorder-build
#   # → /out/app-release-unsigned.apk and its sha256, built from the sources baked into the image
#
# Everything that influences the APK bytes is pinned: JDK 17 (Temurin), Android platform 34,
# build-tools 36.0.0, the Gradle wrapper (8.14.3), AGP 8.13.2 and Kotlin 2.4.20 from build.gradle.kts,
# and exact dependency versions. The signature is deliberately NOT part of the reproducible artifact.
FROM eclipse-temurin:17.0.16_8-jdk-jammy

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_OPTS="-Dorg.gradle.daemon=false" \
    TZ=UTC LANG=C.UTF-8 LC_ALL=C.UTF-8

RUN apt-get update && apt-get install -y --no-install-recommends unzip curl git python3 ca-certificates && rm -rf /var/lib/apt/lists/*

# Android command-line tools 13114758 (2025-03) – pinned by download URL.
RUN mkdir -p "$ANDROID_HOME/cmdline-tools" && cd "$ANDROID_HOME/cmdline-tools" \
 && curl -fsSLo tools.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip \
 && unzip -q tools.zip && rm tools.zip && mv cmdline-tools latest
ENV PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
RUN yes | sdkmanager --licenses >/dev/null \
 && sdkmanager "platforms;android-34" "build-tools;36.0.0" "platform-tools" >/dev/null

WORKDIR /src
COPY . /src
# Never bake signing material into the image.
RUN rm -f keystore.properties release.jks

RUN ./gradlew --no-daemon -q :app:assembleOfflineRelease -PunsignedRelease \
 && shasum -a 256 app/build/outputs/apk/offline/release/app-offline-release-unsigned.apk

CMD mkdir -p /out && cp app/build/outputs/apk/offline/release/app-offline-release-unsigned.apk /out/app-release-unsigned.apk \
 && shasum -a 256 /out/app-release-unsigned.apk | tee /out/SHA256SUMS
