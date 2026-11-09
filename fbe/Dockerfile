# Factorio Blueprint Editor (https://github.com/FactoryGameFan/factorio-blueprint-editor, MIT)
# auto-hébergé pour Texto : patché pour pouvoir être affiché en iframe et renvoyer ses exports à Texto.
FROM node:24-alpine AS build
ARG FBE_REF=51460244a1b7e69297ecbba8dc04b6c45f18cbfe
RUN apk add --no-cache git
RUN git clone https://github.com/FactoryGameFan/factorio-blueprint-editor.git /fbe \
 && cd /fbe && git checkout "$FBE_REF"
WORKDIR /fbe
COPY texto-embed.patch /tmp/texto-embed.patch
RUN git apply --whitespace=nowarn /tmp/texto-embed.patch
# Le projet exige npm 12 (devEngines).
RUN npm install -g npm@12 && npm ci --no-audit --no-fund
RUN cd packages/website && npx vp build

FROM nginx:1.29-alpine
COPY --from=build /fbe/packages/website/dist /usr/share/nginx/html
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
# Origines autorisées à afficher FBE en iframe (séparées par des espaces), ex. "https://texto.example.com".
ENV FBE_FRAME_ANCESTORS="'self'"
EXPOSE 80
