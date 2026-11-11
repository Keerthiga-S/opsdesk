
# --------------------------------------------------
# Stage 1: Frontend build
# --------------------------------------------------
FROM node:22-alpine AS build

WORKDIR /app

COPY package.json package-lock.json ./

RUN npm ci --no-audit --no-fund

COPY . .

RUN npm run build


# --------------------------------------------------
# Stage 2: Nginx production server
# --------------------------------------------------
FROM nginx:1.27-alpine

COPY nginx.conf /etc/nginx/templates/default.conf.template

COPY --from=build /app/dist /usr/share/nginx/html

ENV API_URL=http://api:8001

# Prepare Nginx directories for non-root execution
RUN chown -R nginx:nginx \
    /usr/share/nginx/html \
    /var/cache/nginx \
    /var/log/nginx \
    /etc/nginx/conf.d \
    /etc/nginx/templates \
    && touch /var/run/nginx.pid \
    && chown nginx:nginx /var/run/nginx.pid

# Run Nginx as a non-root user
USER nginx

EXPOSE 8080
