# BiblioLocal

La biblioteca de ebooks del NAS (epub/pdf/mobi/azw3), pariente de CineLocal: portadas y
metadata sacadas de los propios archivos, sagas en orden, universos, peticiones de libros y
descargas con el aMule del NAS. Sin base de datos: el indice se construye en memoria
escaneando la carpeta, y lo propio de la app (peticiones, universos, registro) son archivos
JSON en `data/`.

## Requisitos

- Java 21
- No hace falta instalar Maven: usa el wrapper incluido (`mvnw` / `mvnw.cmd`)

## Arrancar en local

```
mvnw.cmd clean package
java -jar target/ebooks-api-0.0.1-SNAPSHOT.jar
```

La app arranca en `http://localhost:8081`.

## Configuracion

Los valores por defecto estan en `src/main/resources/application.properties`. Lo propio de
cada instalacion va en archivos que se cargan si existen junto al `.jar` y **no** estan
versionados:

- `application.properties` (en la raiz del proyecto, no en `src/`): sobreescribe cualquier
  propiedad.
- `secrets.properties`: contraseñas que solo viven en el NAS (la de aMule). Se importa con
  `spring.config.import=optional:file:./secrets.properties`.
- `local-users.json`: usuarios que no tienen cuenta en el NAS (ver `local-users.example.json`).
  Cada contrasena se guarda como hash BCrypt.

| Propiedad | Que hace |
|---|---|
| `ebooks.root-path` | Carpeta raiz de la biblioteca |
| `ebooks.auto-refresh-minutes` | Cada cuanto se reescanea la carpeta sola |
| `dsm.base-url` | DSM del NAS, para validar el login con sus cuentas |
| `app.admin-group` / `app.user-group` | Grupos de DSM de administradores y lectores (`bibliolocal-admin`, `bibliolocal-user`) |
| `app.admins` | Administradores mientras esos grupos no existan |
| `app.data-dir` | Carpeta de peticiones, universos, descargas y registro (`data`) |
| `app.amule.password` | Contraseña del API de aMule (la de CineLocal). Sin ella no hay descargas |
| `app.amule.incoming` | Carpeta donde aMule deja lo terminado |

## Quien entra

Login con las cuentas del NAS (API `auth.cgi` de DSM) y, si no existe alli, con
`local-users.json`. Quien administra lo deciden dos grupos de DSM (*Panel de control → Grupo*):

- **`bibliolocal-admin`**: editar libros y portadas, renombrar autores y sagas, universos,
  gestionar peticiones, descargar con aMule y ver el registro.
- **`bibliolocal-user`**: leer, descargar, enviar al Kindle y pedir libros.

En cuanto existe uno de los dos grupos, quien no este en ninguno no entra. El grupo se mira en
cada peticion (la app lee `/etc/group`), asi que los cambios valen al momento.

Intentos fallidos: 5 por IP o 10 por cuenta en 15 minutos bloquean 15 minutos. El bloqueo
automatico de DSM no sirve aqui: DSM ve todos los logins llegar desde localhost.

## Peticiones y descargas

Los lectores buscan en Open Library (primero las ediciones en español) y piden lo que falta,
tambien desde el hueco de una saga. Los administradores lo buscan en aMule desde
*Admin → Descargas*; al terminar, el libro se coloca solo en `Autor/[Saga/]Autor - Titulo.epub`
(la carpeta del autor y de la saga que ya existan) y la peticion se da por conseguida. Lo que
no se puede identificar queda «pendiente de colocar».

aMule es el de CineLocal (mismo contenedor y misma contraseña). CineLocal ignora los ebooks
desde su version con el filtro `EBOOK` en `server/amule/service.js`.

## Universos

Sagas de sagas: el Cosmere, la Tierra Media, el Circulo del Mundo. Se guardan en
`data/universes.json` y se editan desde la ficha de un libro (campo Universo: a toda su saga, o
solo al libro si no tiene) o desde la pagina de cada saga.

Ni las sagas ni los universos salen del nombre de las carpetas: la saga es la que lleva el epub
(Calibre o esta app) y el universo, el que pone un administrador.

## Leer en la web

Los epub se leen en la propia web (epub.js, servido desde `static/vendor`): paginas con clic en
los bordes, flechas o deslizando; indice; tamaño y tipo de letra; fondo claro, sepia u oscuro.
Por donde va cada persona y lo que ha terminado se guarda en `data/reading.json`, asi que se
sigue en otro dispositivo donde se dejo. Al llegar al final, el libro se marca como leido.

## Registro

*Admin → Registro*: sesiones, descargas, envios al Kindle, cambios, peticiones, aMule y
errores, en `data/logs/access-AAAA-MM.jsonl`. Se guardan 12 meses.

## Generar el hash de un usuario local

```
mvnw.cmd -q dependency:build-classpath -Dmdep.outputFile=cp.txt
javac -cp (Get-Content cp.txt) -d . tools/HashGen.java
java -cp ".;$(Get-Content cp.txt)" HashGen "la-contrasena"
Remove-Item HashGen.class, cp.txt
```
