# Etapa 1: compila com JDK
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY src ./src
RUN mkdir out && javac -d out $(find src -name '*.java')

# Etapa 2: imagem final só com o JRE, rodando sem root
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/out ./out
RUN useradd -r -u 10001 app
USER app
ENV BIND_HOST=0.0.0.0
EXPOSE 8080
CMD ["java", "-XX:MaxRAMPercentage=75", "-cp", "out", "br.pay.Api"]
