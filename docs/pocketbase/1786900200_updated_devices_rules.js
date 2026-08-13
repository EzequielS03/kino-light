/// <reference path="../pb_data/types.d.ts" />
// Cierra las dos puertas que quedaban abiertas en `devices`.
//
// ## createRule: null
//
// Era `@request.auth.id = "" || accountId = @request.auth.accountId`, o sea que aceptaba
// creaciones **sin autenticar y con cualquier accountId**. Con eso, cualquiera con la llave del
// APK podia fabricarse aparatos dentro de la cuenta de otro, o inventar los que quisiera para
// esquivar el tope de la licencia sin pasar nunca por el candado del gateway.
//
// El alta legitima pasa ahora por POST /v1/cuenta/aparatos/alta, que la hace con credenciales de
// admin. Por eso esto se puede cerrar del todo -- pero SOLO despues de que los aparatos instalados
// usen ese endpoint: una app vieja que escriba `devices` directo deja de arrancar.
//
// ## updateRule: constrenida, NO cerrada
//
// Cerrarla del todo romperia features vivas que escriben su PROPIO registro:
//   - `PresenceManager`  -> presencia / ultima vez visto
//   - `NowPlayingPublisher` -> "reproduciendo ahora", que alimenta el mini-player del celular
//
// Lo unico que hay que impedir es que un aparato **se cambie de cuenta solo**, que es como se
// esquiva el tope: mover un aparato a una cuenta es lo que cuenta cupo, y eso ahora lo hace el
// gateway (`adoptar_aparato`) con el candado de Redis. La regla deja que el aparato se actualice a
// si mismo mientras el `accountId` del cuerpo no exista o sea igual al que ya tiene.
//
// Ojo con el orden en que se leen los campos: en un updateRule, `accountId` a secas es el valor
// GUARDADO y `@request.body.accountId` el que se quiere escribir. La condicion dice "o no lo
// mandas, o lo mandas igual".
migrate((app) => {
  const devices = app.findCollectionByNameOrId("devices")
  devices.createRule = null
  devices.updateRule =
    'id = @request.auth.id && (@request.body.accountId:isset = false || @request.body.accountId = accountId)'
  return app.save(devices)
}, (app) => {
  const devices = app.findCollectionByNameOrId("devices")
  devices.createRule = '@request.auth.id = "" || accountId = @request.auth.accountId'
  devices.updateRule = 'accountId = @request.auth.accountId'
  return app.save(devices)
})
