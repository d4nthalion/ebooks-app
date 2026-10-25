#!/bin/bash
# Script de arranque/parada del tunel de Cloudflare que publica ebooks-api
# (libros.danthalion.com -> localhost:8081) sin abrir puertos en el router.
#
# Instalacion:
#   1. Binario en ~/apps/cloudflared/cloudflared, config.yml al lado y las
#      credenciales del tunel en ~/.cloudflared/ (cloudflared tunnel login/create)
#   2. Copia este archivo a ~/apps/cloudflared/cloudflared.sh y chmod +x
#   3. Panel de control > Programador de tareas > Crear > Tarea activada >
#        Evento: Arranque, Usuario: el dueno de la carpeta
#        Comando: /volume1/homes/<usuario>/apps/cloudflared/cloudflared.sh start
#
# Uso manual por SSH:
#   ./cloudflared.sh start|stop|restart|status

set -u

# --- Configuracion ---------------------------------------------------------
APP_DIR="$(cd "$(dirname "$0")" && pwd)"
BIN="$APP_DIR/cloudflared"
CONFIG="$APP_DIR/config.yml"
PIDFILE="$APP_DIR/cloudflared.pid"
LOGDIR="$APP_DIR/logs"
LOGFILE="$LOGDIR/tunnel-$(date +%Y%m%d-%H%M%S).log"
KEEP_LOGS=10
# ----------------------------------------------------------------------------

mkdir -p "$LOGDIR"

is_running() {
    [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null
}

start() {
    if is_running; then
        echo "cloudflared ya esta corriendo (PID $(cat "$PIDFILE"))."
        exit 0
    fi

    if [ ! -x "$BIN" ]; then
        echo "No se encuentra cloudflared en $BIN" >&2
        exit 1
    fi
    if [ ! -f "$CONFIG" ]; then
        echo "No se encuentra la config en $CONFIG" >&2
        exit 1
    fi

    cd "$APP_DIR" || exit 1
    nohup "$BIN" tunnel --config "$CONFIG" run > "$LOGFILE" 2>&1 &
    echo $! > "$PIDFILE"
    disown

    ls -1t "$LOGDIR"/tunnel-*.log 2>/dev/null | tail -n +$((KEEP_LOGS + 1)) | xargs -r rm -f

    echo "cloudflared arrancado (PID $(cat "$PIDFILE")). Log: $LOGFILE"
}

stop() {
    if ! is_running; then
        echo "cloudflared no esta corriendo."
        rm -f "$PIDFILE"
        exit 0
    fi
    PID=$(cat "$PIDFILE")
    kill "$PID"
    for _ in $(seq 1 15); do
        kill -0 "$PID" 2>/dev/null || break
        sleep 1
    done
    kill -0 "$PID" 2>/dev/null && kill -9 "$PID"
    rm -f "$PIDFILE"
    echo "cloudflared parado."
}

status() {
    if is_running; then
        echo "cloudflared activo (PID $(cat "$PIDFILE"))."
    else
        echo "cloudflared parado."
    fi
}

case "${1:-start}" in
    start)   start ;;
    stop)    stop ;;
    restart) stop; start ;;
    status)  status ;;
    *) echo "Uso: $0 {start|stop|restart|status}" >&2; exit 1 ;;
esac
