#!/bin/bash
# Script de arranque/parada de ebooks-api en el Synology NAS (DS418, DSM 7.x).
#
# Instalacion:
#   1. Copia este archivo a ~/apps/ebooks-api/ebooks-api.sh (junto al jar)
#   2. chmod +x ~/apps/ebooks-api/ebooks-api.sh
#   3. Da de alta una tarea en Panel de control > Programador de tareas:
#        Nueva > Tarea desencadenada > Al arrancar
#        Usuario: el dueno de la carpeta (no hace falta root, el puerto es >1024)
#        Comando: /volume1/homes/<usuario>/apps/ebooks-api/ebooks-api.sh start
#
# Uso manual por SSH:
#   ./ebooks-api.sh start|stop|restart|status

set -u

# DSM lanza las tareas sin locale (LANG vacio) y entonces Java decodifica los
# nombres de fichero como ASCII: las tildes salen como "??" y esos libros no
# se pueden abrir (sin metadata ni portada).
export LANG=en_US.UTF-8
export LC_ALL=en_US.UTF-8

# --- Configuracion ---------------------------------------------------------
# Rutas relativas a donde este el script, para no depender de root: la app
# vive en ~/apps/ebooks-api y el JRE en ~/apps/java/current.
APP_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR_NAME="ebooks-api-0.0.1-SNAPSHOT.jar"
JAVA_BIN="$(dirname "$APP_DIR")/java/current/bin/java"
PIDFILE="$APP_DIR/ebooks-api.pid"
LOGDIR="$APP_DIR/logs"
LOGFILE="$LOGDIR/app-$(date +%Y%m%d-%H%M%S).log"
KEEP_LOGS=10
# ----------------------------------------------------------------------------

mkdir -p "$LOGDIR"

is_running() {
    [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null
}

start() {
    if is_running; then
        echo "ebooks-api ya esta corriendo (PID $(cat "$PIDFILE"))."
        exit 0
    fi

    if [ ! -x "$JAVA_BIN" ]; then
        echo "No se encuentra el JRE en $JAVA_BIN" >&2
        exit 1
    fi
    if [ ! -f "$APP_DIR/$JAR_NAME" ]; then
        echo "No se encuentra el jar en $APP_DIR/$JAR_NAME" >&2
        exit 1
    fi

    cd "$APP_DIR" || exit 1
    # Se lanza en background con nohup+disown para que sobreviva a que el
    # Programador de tareas de DSM termine el proceso padre del script.
    nohup "$JAVA_BIN" -jar "$APP_DIR/$JAR_NAME" > "$LOGFILE" 2>&1 &
    echo $! > "$PIDFILE"
    disown

    # Purga logs antiguos, se queda solo con los ultimos $KEEP_LOGS
    ls -1t "$LOGDIR"/app-*.log 2>/dev/null | tail -n +$((KEEP_LOGS + 1)) | xargs -r rm -f

    echo "ebooks-api arrancado (PID $(cat "$PIDFILE")). Log: $LOGFILE"
}

stop() {
    if ! is_running; then
        echo "ebooks-api no esta corriendo."
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
    echo "ebooks-api parado."
}

status() {
    if is_running; then
        echo "ebooks-api activo (PID $(cat "$PIDFILE"))."
    else
        echo "ebooks-api parado."
    fi
}

case "${1:-start}" in
    start)   start ;;
    stop)    stop ;;
    restart) stop; start ;;
    status)  status ;;
    *) echo "Uso: $0 {start|stop|restart|status}" >&2; exit 1 ;;
esac
