# skiwi アプリ（scripts/dist.sh の成果物を詰めるだけ）
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S skiwi && adduser -S -G skiwi skiwi
COPY dist/lib /app/lib
COPY dist/static /app/static
ENV STATIC_DIR=/app/static HTTP_PORT=8080
USER skiwi
EXPOSE 8080
CMD ["java", "-XX:MaxRAMPercentage=70", "-cp", "/app/lib/*", "skiwi.app.Main"]
