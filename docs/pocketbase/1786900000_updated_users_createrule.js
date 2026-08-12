/// <reference path="../pb_data/types.d.ts" />
// Cierra la creacion de cuentas: a partir de aca solo el admin puede crear un `users`.
//
// La regla que habia -- `@request.auth.id != "" && accountId = @request.auth.accountId` -- exigia un
// aparato autenticado pero NINGUNA licencia. Y todo install se da de alta solo como aparato anonimo,
// asi que en la practica cualquiera que consiguiera el APK se creaba una cuenta y usaba el gateway.
// Ese es exactamente el agujero que el spec de licencias viene a cerrar.
//
// `null` significa "solo superusuario". El camino legitimo pasa a ser POST /v1/cuenta/registrar en el
// gateway, que valida el codigo contra `licencias` y crea la cuenta con credenciales de admin. Ese
// endpoint todavia no existe: hasta que exista, NADIE se puede registrar. Es a proposito -- las dos
// cuentas que hay ya estan creadas y con licencia, y preferimos que no se pueda entrar a que pueda
// entrar cualquiera.
//
// Se cierra `users` y no `devices` en la misma migracion: los aparatos ya instalados dan de alta su
// identidad anonima escribiendo `devices` directo, y cerrarlo hoy los dejaria sin arrancar. Las
// reglas de `devices` se cierran junto con el cambio de la app que mueve ese alta al gateway.
migrate((app) => {
  const users = app.findCollectionByNameOrId("users")
  users.createRule = null
  return app.save(users)
}, (app) => {
  const users = app.findCollectionByNameOrId("users")
  users.createRule = "@request.auth.id != \"\" && accountId = @request.auth.accountId"
  return app.save(users)
})
