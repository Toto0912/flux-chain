# 多阶段构建 - 构建阶段
FROM node:20.19.0 AS builder

WORKDIR /app

RUN npm install --global pnpm@9.15.9
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

COPY . .
RUN pnpm run build

FROM --platform=$BUILDPLATFORM golang:1.23.4 AS udp-echo-builder
WORKDIR /build
COPY udp-echo/main.go ./main.go
RUN mkdir /out && \
    CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o /out/fluxchain-udp-echo-linux-amd64 main.go && \
    CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags="-s -w" -o /out/fluxchain-udp-echo-linux-arm64 main.go && \
    cd /out && sha256sum fluxchain-udp-echo-linux-* > SHA256SUMS

# 生产阶段
FROM nginx:stable-alpine AS production-stage

COPY nginx.conf /etc/nginx/nginx.conf

COPY --from=builder /app/dist /usr/share/nginx/html
COPY --from=udp-echo-builder /out/ /usr/share/nginx/html/udp-echo/

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"] 
