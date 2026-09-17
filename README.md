# ebooks-api

API y web para consultar y descargar una biblioteca personal de ebooks (epub/pdf/mobi/azw3),
con portadas y metadata extraidas directamente de los propios archivos. Sin base de datos:
el indice se construye en memoria escaneando una carpeta (local o del NAS).

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

Los valores por defecto estan en `src/main/resources/application.properties`. Para no
subir datos propios (URL real del NAS, usuarios locales) al repositorio, hay dos archivos
que se cargan automaticamente si existen junto al `.jar`, pero **no** estan versionados:

- `application.properties` (en la raiz del proyecto, no en `src/`): sobreescribe cualquier
  propiedad, tipicamente `dsm.base-url`.
- `local-users.json`: usuarios que no tienen cuenta en el NAS (ver `local-users.example.json`
  como plantilla). Cada contrasena se guarda como hash BCrypt, nunca en texto plano.

Propiedades principales:

| Propiedad | Que hace |
|---|---|
| `ebooks.root-path` | Carpeta raiz de la biblioteca a indexar |
| `ebooks.allowed-extensions` | Extensiones que se catalogan |
| `ebooks.auto-refresh-minutes` | Cada cuanto se reescanea la carpeta sola |
| `dsm.base-url` | Direccion del NAS Synology para validar login contra sus cuentas |
| `app.local-users-file` | Ruta al JSON de usuarios locales |

## Login

Login hibrido: primero intenta validar contra las cuentas del NAS (API `auth.cgi` de
Synology DSM); si esa cuenta no existe alli (o el NAS no responde), cae a la lista de
`local-users.json`. Util para cuentas del NAS con verificacion en dos pasos (el login
simple no soporta OTP) o para gente sin cuenta en el NAS.

Para generar el hash de una contrasena de un usuario local, usando la utilidad incluida
en `tools/HashGen.java` (PowerShell):

```
mvnw.cmd -q dependency:build-classpath -Dmdep.outputFile=cp.txt
javac -cp (Get-Content cp.txt) -d . tools/HashGen.java
java -cp ".;$(Get-Content cp.txt)" HashGen "la-contrasena"
Remove-Item HashGen.class, cp.txt
```

Copia el hash resultante a `local-users.json` (ver `local-users.example.json`).
