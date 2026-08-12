/// <reference path="../pb_data/types.d.ts" />
// Suma `licencia` a `users`: que persona esta habilitada por que licencia. El gateway lo lee en cada
// pedido para poder revocar (ver el spec de licencias).
//
// Es un UPDATE y no un create: `users` ya existe -- es la coleccion por defecto de PocketBase, ya
// adaptada por 1786369101_updated_users.js. Crearla de nuevo falla.
//
// Guarda el CODIGO y no una relation: el gateway resuelve la licencia por codigo en cada validacion,
// y una relation lo obligaria a expandirla en cada consulta sin darle nada a cambio.
//
// Va sin `required`: los records que ya existen no tienen licencia, y marcarlo obligatorio los
// dejaria invalidos. Que no falte de verdad lo garantiza el registro, no el esquema.
migrate((app) => {
  const users = app.findCollectionByNameOrId("users")
  users.fields.add(new Field({
    "hidden": false,
    "id": "text_licencia_ark",
    "max": 64,
    "min": 0,
    "name": "licencia",
    "presentable": false,
    "primaryKey": false,
    "required": false,
    "system": false,
    "type": "text"
  }))
  return app.save(users)
}, (app) => {
  const users = app.findCollectionByNameOrId("users")
  users.fields.removeByName("licencia")
  return app.save(users)
})
