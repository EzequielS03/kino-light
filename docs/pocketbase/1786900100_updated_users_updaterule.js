/// <reference path="../pb_data/types.d.ts" />
// Cierra la escritura del propio record de users: a partir de aca, ni siquiera la propia
// persona logueada puede PATCHear su record de `users`. Solo el admin puede.
//
// La regla que habia -- `id = @request.auth.id` -- dejaba que cualquier persona logueada
// reescribiera CUALQUIER campo de su propio record con un PATCH autenticado con su propio
// token, incluidos `accountId` y `licencia`: los dos campos de los que cuelga toda la
// identidad de esta rama (`sesion.py` lee `licencia` para saber si la cuenta sigue
// habilitada, y `accountId` para saber que aparatos son suyos). Confirmado contra la
// instancia real:
//
//   - Revocada una licencia, la persona podia apuntar su `licencia` a CUALQUIER otro
//     codigo que siguiera activo (el de un familiar, o el que tenia antes de que se lo
//     revocaran) y volver a entrar -- con el tope de aparatos de ESA otra licencia. El
//     "cortarle el acceso a una persona" -- la razon por la que existe la coleccion
//     `licencias` -- no se sostenia contra esto.
//   - Cualquier persona logueada podia reescribir su `accountId` a un valor con comillas
//     (`zzz" || id!="`) e inyectar un filtro de PocketBase: el gateway corre esas consultas
//     como SUPERUSUARIO, asi que la inyeccion salta por encima de las reglas de lista que
//     aislan por cuenta. Con eso, GET /aparatos devolvia los aparatos de TODAS las cuentas,
//     y el conteo de cupo dejaba de matchear nada -- el tope de "1 celular y 1 TV" dejaba de
//     contar aunque la respuesta siguiera diciendo "usados: 1, tope: 1".
//
// El codigo del gateway (identidad/sesion.py, identidad/cuentas.py) ya no confia en que
// estos dos campos vengan sanos -- valida la licencia contra `usadaPor` y el accountId
// contra su forma antes de armar cualquier filtro -- pero esta migracion cierra el agujero
// de raiz: sin ella, cualquier variante nueva de la misma idea (un campo mas que se agregue
// a `users` el dia de manana, una validacion que alguien se olvide de copiar) queda abierta
// por el mismo camino.
//
// No rompe la app instalada: se reviso el codigo Android completo y el UNICO uso de
// `users` es `auth-with-password` (login) -- la app nunca escribe un record de `users`. El
// unico camino legitimo para cambiar `licencia`/`accountId` pasa a ser el gateway
// (`/v1/cuenta/registrar`, `/v1/cuenta/aparatos`), que valida antes de escribir con sus
// credenciales de admin.
migrate((app) => {
  const users = app.findCollectionByNameOrId("users")
  users.updateRule = null
  return app.save(users)
}, (app) => {
  const users = app.findCollectionByNameOrId("users")
  users.updateRule = "id = @request.auth.id"
  return app.save(users)
})
